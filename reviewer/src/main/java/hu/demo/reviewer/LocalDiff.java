package hu.demo.reviewer;

import hu.demo.reviewer.GitHubClient.ChangedFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Lokális mód: a változásokat a GitHub API helyett a {@code git diff <base>...HEAD} parancsból olvassa. */
final class LocalDiff {

    private LocalDiff() {}

    /** A {@code base...HEAD} közötti, nem törölt fájlok patchei az aktuális könyvtár repójából. */
    static List<ChangedFile> changedFiles(String base) throws IOException, InterruptedException {
        return changedFiles(Path.of("."), base);
    }

    /**
     * A {@code base...HEAD} közötti, nem törölt fájlok patchei a megadott könyvtár repójából, a GitHub API
     * {@code patch} mezőjével azonos formában.
     */
    static List<ChangedFile> changedFiles(Path repo, String base) throws IOException, InterruptedException {
        List<ChangedFile> files = new ArrayList<>();
        // -z: NUL-lal elválasztott, idézőjelezés nélküli fájlnevek (szóköz, ékezet is biztonságos).
        String names = git(repo, "diff", "--name-only", "-z", "--diff-filter=d", base + "...HEAD");
        for (String name : names.split("\0")) {
            if (name.isEmpty()) {
                continue;
            }
            String patch = stripHeader(git(repo, "diff", base + "...HEAD", "--", name));
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

    private static String git(Path repo, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "core.quotePath=false"));
        cmd.addAll(List.of(args));
        Process process = new ProcessBuilder(cmd)
                .directory(repo.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        try {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("Sikertelen git parancs (" + exitCode + "): " + String.join(" ", cmd));
            }
            return output;
        } finally {
            process.destroy(); // megszakítás vagy hiba esetén ne maradjon futó git folyamat
        }
    }
}
