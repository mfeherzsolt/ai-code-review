package hu.demo.reviewer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hu.demo.reviewer.GitHubClient.ChangedFile;
import hu.demo.reviewer.GitHubClient.InlineComment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * AI code review belépési pont.
 *
 * <p>Környezeti változók: GITHUB_TOKEN, GITHUB_REPOSITORY, PR_NUMBER, COPILOT_GITHUB_TOKEN (a Copilot CLI-nek).
 * Opcionális: COPILOT_MODEL, COPILOT_CMD (alap: copilot), RULES_FILE (alap: agent.md), COMPILE_LOG, DRY_RUN=true,
 * SAVE_REPORT=true (modulonként menti a riportot a {@code <modul>/target/review/latest.md} fájlba; a modulba nem tartozó
 * fájlok riportja a {@code target/review/latest.md}),
 * MIN_SEVERITY (alap: minor; nit, minor, major vagy critical; az ez alatti kommentek nem inline kerülnek ki,
 * hanem egy összecsukott listába).
 *
 * <p>Lokális mód: ha a LOCAL_BASE be van állítva (pl. main), a változásokat a {@code git diff $LOCAL_BASE...HEAD}
 * adja, nem küldi el semmit a GitHubra (GITHUB_TOKEN, PR_NUMBER nem kell), és ha van MIN_SEVERITY-t elérő
 * találat, {@link #EXIT_FINDINGS} kilépési kóddal tér vissza.
 *
 * <p>Kilépési kódok: {@link #EXIT_OK} siker; {@link #EXIT_FINDINGS} lokális módban találat van; minden más
 * nem 0 érték (pl. 1, a JVM kezeletlen kivételnél) hiba.
 */
public final class Main {

    /** Siker: nincs (küszöböt elérő) találat, vagy a review elküldve. */
    static final int EXIT_OK = 0;

    /** Lokális módban van a küszöböt elérő találat; szándékosan különbözik a JVM hibakódjától (1). */
    static final int EXIT_FINDINGS = 3;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_PROMPT_CHARS = 60_000;
    private static final List<String> SEVERITIES = List.of("nit", "minor", "major", "critical");

    /** Az önmagát cáfoló "nincs teendő" jellegű megjegyzések (a "nem elfogadható" valódi észrevétel marad). */
    private static final Pattern NO_ACTION =
            Pattern.compile("nincs teendő|(?<!nem )elfogadható", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static void main(String[] args) throws Exception {
        System.exit(run());
    }

    /** A tényleges futás; a visszatérési érték a kilépési kód ({@link #EXIT_OK} vagy {@link #EXIT_FINDINGS}). */
    private static int run() throws Exception {
        String localBase = System.getenv("LOCAL_BASE");
        boolean local = localBase != null && !localBase.isBlank();
        boolean dryRun = local || "true".equalsIgnoreCase(System.getenv("DRY_RUN"));
        GitHubClient github =
                local ? null : new GitHubClient(env("GITHUB_TOKEN"), env("GITHUB_REPOSITORY"), env("PR_NUMBER"));
        CopilotClient llm = new CopilotClient(envOr("COPILOT_CMD", "copilot"), System.getenv("COPILOT_MODEL"));

        String rules = Files.readString(Path.of(envOr("RULES_FILE", "agent.md")));
        String minSeverity = parseSeverity(envOr("MIN_SEVERITY", "minor"));

        List<ChangedFile> files = local ? LocalDiff.changedFiles(localBase) : github.changedFiles();
        if (files.isEmpty()) {
            System.out.println("Nincs review-zható változás.");
            return EXIT_OK;
        }

        Map<String, Set<Integer>> commentable = new HashMap<>();
        String prompt = buildPrompt(files, commentable);

        String answer = llm.complete(rules, prompt);
        JsonNode review = MAPPER.readTree(extractJson(answer));
        Result result = render(review, minSeverity, commentable);
        String report = report(result.summary(), result.inline());
        saveReports(files, result);

        if (dryRun) {
            System.out.print(report);
            if (local) {
                System.out.println(
                        "\nLokális review: " + result.findings() + " " + minSeverity + " vagy annál súlyosabb találat.");
                return result.findings() > 0 ? EXIT_FINDINGS : EXIT_OK;
            }
            return EXIT_OK;
        }
        github.postReview(github.headSha(), result.summary(), result.inline());
        System.out.println("Review elküldve: " + result.inline().size() + " inline komment.");
        return EXIT_OK;
    }

    /** A review eredménye: a kiírandó összegzés, az inline kommentek, és a küszöböt elérő találatok száma. */
    record Result(String summary, List<InlineComment> inline, int findings) {}

    /** Olvasható riport: az összegzés, majd az inline kommentek helyként és szöveggel. */
    static String report(String summary, List<InlineComment> comments) {
        StringBuilder report = new StringBuilder(summary).append("\n");
        for (InlineComment c : comments) {
            report.append("\n[").append(c.path()).append(":").append(c.line()).append("]\n").append(c.body()).append("\n");
        }
        return report.toString();
    }

    /**
     * A fájl modulja: az első útvonalszegmens, ha abban van pom.xml; különben üres (a repó gyökere).
     * A {@code root} a repó gyökere, amelyhez a fájl útvonala képest relatív.
     */
    static String moduleOf(Path root, String file) {
        int slash = file.indexOf('/');
        String first = slash < 0 ? "" : file.substring(0, slash);
        return !first.isEmpty() && Files.exists(root.resolve(first).resolve("pom.xml")) ? first : "";
    }

    /**
     * Ha a SAVE_REPORT=true, modulonként menti a riportot a {@code <modul>/target/review/latest.md} fájlba: az összegzés
     * és az adott modul inline kommentjei. Minden olyan modulhoz készül riport, amelyben fájl változott, így nem
     * marad régi riport. A futtatás végén ír, hogy hiba ne hagyjon részlegesen felülírt fájlt.
     */
    private static void saveReports(List<ChangedFile> files, Result result) throws IOException {
        if (!"true".equalsIgnoreCase(System.getenv("SAVE_REPORT"))) {
            return;
        }
        Path root = Path.of(".");
        Set<String> modules = new TreeSet<>();
        files.forEach(f -> modules.add(moduleOf(root, f.filename())));
        for (String module : modules) {
            List<InlineComment> comments =
                    result.inline().stream().filter(c -> moduleOf(root, c.path()).equals(module)).toList();
            Path file = root.resolve(module).resolve("target").resolve("review").resolve("latest.md");
            Files.createDirectories(file.getParent());
            Files.writeString(file, report(result.summary(), comments));
            System.out.println("Riport: " + root.relativize(file).normalize());
        }
    }

    /** A prompt a diffekből és a fordítási kimenetből; a kommentelhető sorokat a {@code commentable} térképbe tölti. */
    private static String buildPrompt(List<ChangedFile> files, Map<String, Set<Integer>> commentable)
            throws Exception {
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
        return prompt.append("# Fordítási kimenet (mvn compile)\n```\n")
                .append(readCompileLog())
                .append("\n```\n")
                .toString();
    }

    /** A modell válaszából összegzést épít: a küszöb feletti kommentek inline mennek, a többi összecsukott listába. */
    static Result render(JsonNode review, String minSeverity, Map<String, Set<Integer>> commentable) {
        StringBuilder summary = new StringBuilder("## AI Code Review\n\n")
                .append(review.path("summary").asText("(nincs összegzés)")).append("\n");
        List<InlineComment> inline = new ArrayList<>();
        List<String> belowThreshold = new ArrayList<>();
        int findings = 0;
        for (JsonNode c : review.path("comments")) {
            if (NO_ACTION.matcher(c.path("message").asText()).find()) {
                continue;
            }
            String file = c.path("file").asText();
            int line = c.path("line").asInt(-1);
            String body = formatComment(c);
            if (!atLeast(c.path("severity").asText("minor"), minSeverity)) {
                belowThreshold.add("- `" + file + ":" + line + "` " + body);
                continue;
            }
            findings++;
            if (commentable.getOrDefault(file, Set.of()).contains(line)) {
                inline.add(new InlineComment(file, line, body));
            } else {
                summary.append("\n- `").append(file).append(":").append(line).append("` ").append(body);
            }
        }
        if (!belowThreshold.isEmpty()) {
            summary.append("\n<details><summary>").append(belowThreshold.size())
                    .append(" kisebb észrevétel (").append(minSeverity).append(" alatt)</summary>\n\n")
                    .append(String.join("\n", belowThreshold)).append("\n</details>\n");
        }
        return new Result(summary.toString(), inline, findings);
    }

    /**
     * Igaz, ha a komment súlyossága eléri a minimumot; a modell által adott ismeretlen súlyosságot nem szűri ki.
     * Ismeretlen {@code minimum}-ra hibát dob, hogy a szűrés ne kapcsolódjon ki csendben.
     */
    static boolean atLeast(String severity, String minimum) {
        int minimumIndex = SEVERITIES.indexOf(minimum.toLowerCase(Locale.ROOT));
        if (minimumIndex < 0) {
            throw new IllegalArgumentException("Ismeretlen minimum súlyosság: " + minimum + " (lehet: " + SEVERITIES + ")");
        }
        int actual = SEVERITIES.indexOf(severity.toLowerCase(Locale.ROOT));
        return actual < 0 || actual >= minimumIndex;
    }

    /** A beállított minimum súlyosság kisbetűs alakja; elgépelésnél hibát dob, hogy a szűrés ne kapcsolódjon ki csendben. */
    static String parseSeverity(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!SEVERITIES.contains(normalized)) {
            throw new IllegalArgumentException("Ismeretlen MIN_SEVERITY: " + value + " (lehet: " + SEVERITIES + ")");
        }
        return normalized;
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
