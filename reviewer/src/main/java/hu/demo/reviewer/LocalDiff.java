package hu.demo.reviewer;

import hu.demo.reviewer.GitHubClient.ChangedFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Lokális mód: a változásokat a GitHub API helyett a {@code git diff <base>...HEAD} parancsból olvassa. */
final class LocalDiff {

    private LocalDiff() {}

    /** A {@code base...HEAD} közötti, nem törölt fájlok patchei, a GitHub API {@code patch} mezőjével azonos formában. */
    static List<ChangedFile> changedFiles(String base) throws IOException, InterruptedException {
        List<ChangedFile> files = new ArrayList<>();
        for (String name : git("diff", "--name-only", "--diff-filter=d", base + "...HEAD").split("\n")) {
            if (name.isBlank()) {
                continue;
            }
            String patch = stripHeader(git("diff", base + "...HEAD", "--", name));
            if (!patch.isBlank()) {
                files.add(new ChangedFile(name, patch));
            }
        }
        return files;
    }

    /** Eldobja a fájlfejlécet (diff --git, index, ---, +++), az első hunk fejlécétől ({@code @@}) tartja meg a szöveget. */
    static String stripHeader(String diff) {
        int start = diff.startsWith("@@") ? 0 : diff.indexOf("\n@@");
        return start < 0 ? "" : diff.substring(start == 0 ? 0 : start + 1).stripTrailing();
    }

    private static String git(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process process = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException("Sikertelen git parancs: " + String.join(" ", cmd));
        }
        return output;
    }
}
