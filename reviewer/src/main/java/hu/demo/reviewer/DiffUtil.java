package hu.demo.reviewer;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A GitHub unified diff (patch) feldolgozása: sorszámozás és a kommentelhető sorok kigyűjtése. */
final class DiffUtil {

    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");

    record Annotated(String text, Set<Integer> commentableLines) {}

    private DiffUtil() {}

    /**
     * Minden hozzáadott/kontextus sor elé odaírja az új fájlbeli sorszámot ("L12| +kód"),
     * hogy az LLM pontos sorszámot tudjon megadni. Törölt sorokhoz nem tartozik sorszám.
     */
    static Annotated annotate(String patch) {
        StringBuilder out = new StringBuilder();
        Set<Integer> lines = new HashSet<>();
        int newLine = 0;
        for (String raw : patch.split("\n", -1)) {
            Matcher m = HUNK.matcher(raw);
            if (m.matches()) {
                newLine = Integer.parseInt(m.group(1));
                out.append(raw).append('\n');
            } else if (raw.startsWith("-")) {
                out.append("    | ").append(raw).append('\n');
            } else if (raw.startsWith("\\")) {
                continue; // "\ No newline at end of file"
            } else if (newLine > 0) {
                lines.add(newLine);
                out.append('L').append(newLine).append("| ").append(raw).append('\n');
                newLine++;
            }
        }
        return new Annotated(out.toString(), lines);
    }
}
