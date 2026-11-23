package hu.demo.reviewer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hu.demo.reviewer.GitHubClient.ChangedFile;
import hu.demo.reviewer.GitHubClient.InlineComment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI code review belépési pont.
 *
 * <p>Környezeti változók: GITHUB_TOKEN, GITHUB_REPOSITORY, PR_NUMBER, COPILOT_GITHUB_TOKEN (a Copilot CLI-nek).
 * Opcionális: COPILOT_MODEL, COPILOT_CMD (alap: copilot), RULES_FILE (alap: agent.md), COMPILE_LOG, DRY_RUN=true,
 * MIN_SEVERITY (alap: major; az ez alatti kommentek nem inline kerülnek ki, hanem egy összecsukott listába).
 *
 * <p>Lokális mód: ha a LOCAL_BASE be van állítva (pl. main), a változásokat a {@code git diff $LOCAL_BASE...HEAD}
 * adja, nem küldi el semmit a GitHubra (GITHUB_TOKEN, PR_NUMBER nem kell), és ha van MIN_SEVERITY-t elérő
 * találat, 1-es kilépési kóddal tér vissza.
 */
public final class Main {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_PROMPT_CHARS = 60_000;
    private static final List<String> SEVERITIES = List.of("nit", "minor", "major", "critical");

    public static void main(String[] args) throws Exception {
        String localBase = System.getenv("LOCAL_BASE");
        boolean local = localBase != null && !localBase.isBlank();
        boolean dryRun = local || "true".equalsIgnoreCase(System.getenv("DRY_RUN"));
        GitHubClient github =
                local ? null : new GitHubClient(env("GITHUB_TOKEN"), env("GITHUB_REPOSITORY"), env("PR_NUMBER"));
        CopilotClient llm = new CopilotClient(envOr("COPILOT_CMD", "copilot"), System.getenv("COPILOT_MODEL"));

        String rules = Files.readString(Path.of(envOr("RULES_FILE", "agent.md")));

        List<ChangedFile> files = local ? LocalDiff.changedFiles(localBase) : github.changedFiles();
        if (files.isEmpty()) {
            System.out.println("Nincs review-zható változás.");
            return;
        }

        Map<String, Set<Integer>> commentable = new HashMap<>();
        StringBuilder prompt = new StringBuilder("# Pull Request diff\n\n");
        for (ChangedFile f : files) {
            DiffUtil.Annotated a = DiffUtil.annotate(f.patch());
            commentable.put(f.filename(), a.commentableLines());
            if (prompt.length() + a.text().length() > MAX_PROMPT_CHARS) {
                prompt.append("\n(A további fájlok méret miatt kimaradtak.)\n");
                break;
            }
            prompt.append("## Fájl: ").append(f.filename()).append("\n```\n").append(a.text()).append("```\n\n");
        }
        prompt.append("# Fordítási kimenet (mvn compile)\n```\n").append(readCompileLog()).append("\n```\n");

        String answer = llm.complete(rules, prompt.toString());
        JsonNode review = MAPPER.readTree(extractJson(answer));

        StringBuilder summary = new StringBuilder("## AI Code Review\n\n")
                .append(review.path("summary").asText("(nincs összegzés)")).append("\n");
        String minSeverity = envOr("MIN_SEVERITY", "major");
        List<InlineComment> inline = new ArrayList<>();
        List<String> minor = new ArrayList<>();
        int blocking = 0;
        for (JsonNode c : review.path("comments")) {
            String file = c.path("file").asText();
            int line = c.path("line").asInt(-1);
            String body = formatComment(c);
            if (!atLeast(c.path("severity").asText("minor"), minSeverity)) {
                minor.add("- `" + file + ":" + line + "` " + body);
                continue;
            }
            blocking++;
            if (commentable.getOrDefault(file, Set.of()).contains(line)) {
                inline.add(new InlineComment(file, line, body));
            } else {
                summary.append("\n- `").append(file).append(":").append(line).append("` ").append(body);
            }
        }
        if (!minor.isEmpty()) {
            summary.append("\n<details><summary>").append(minor.size())
                    .append(" kisebb észrevétel (").append(minSeverity).append(" alatt)</summary>\n\n")
                    .append(String.join("\n", minor)).append("\n</details>\n");
        }

        if (dryRun) {
            System.out.println(summary);
            inline.forEach(c -> System.out.println("\n[" + c.path() + ":" + c.line() + "]\n" + c.body()));
            if (local) {
                System.out.println("\nLokális review: " + blocking + " " + minSeverity + " vagy annál súlyosabb találat.");
                System.exit(blocking > 0 ? 1 : 0);
            }
            return;
        }
        github.postReview(github.headSha(), summary.toString(), inline);
        System.out.println("Review elküldve: " + inline.size() + " inline komment.");
    }

    /** Igaz, ha a komment súlyossága eléri a minimumot; ismeretlen súlyosság esetén nem szűr. */
    static boolean atLeast(String severity, String minimum) {
        int actual = SEVERITIES.indexOf(severity.toLowerCase());
        int required = SEVERITIES.indexOf(minimum.toLowerCase());
        return actual < 0 || required < 0 || actual >= required;
    }

    private static String formatComment(JsonNode c) {
        StringBuilder sb = new StringBuilder("**[").append(c.path("severity").asText("minor")).append("]** ")
                .append(c.path("message").asText());
        if (c.hasNonNull("suggestion") && !c.path("suggestion").asText().isBlank()) {
            sb.append("\n\n```suggestion\n").append(c.path("suggestion").asText()).append("\n```");
        }
        return sb.toString();
    }

    /** A modell válaszából kiszedi a JSON objektumot (kódblokk esetén is). */
    static String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalStateException("A modell nem JSON-t adott vissza: " + text);
        }
        return text.substring(start, end + 1);
    }

    private static String readCompileLog() throws Exception {
        String path = System.getenv("COMPILE_LOG");
        if (path == null || !Files.exists(Path.of(path))) {
            return "(nincs)";
        }
        String log = Files.readString(Path.of(path)).strip();
        return log.isEmpty() ? "Sikeres fordítás, nincs hiba." : log;
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Hiányzó környezeti változó: " + name);
        }
        return value;
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
