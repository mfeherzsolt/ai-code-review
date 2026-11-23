package hu.demo.reviewer;

import hu.demo.reviewer.GitHubClient.ChangedFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Lokális mód: a változásokat a GitHub API helyett a {@code git diff <base>...HEAD} parancsból olvassa. */
final class LocalDiff {

    private static final long GIT_TIMEOUT_SECONDS = 60;

    private LocalDiff() {}

    /** A {@code base...HEAD} közötti, nem törölt fájlok patchei az aktuális könyvtár repójából. */
    static List<ChangedFile> changedFiles(String base) throws IOException, InterruptedException {
        return changedFiles(Path.of("."), base);
    }

    /**
     * A {@code base...HEAD} közötti, nem törölt fájlok patchei a megadott könyvtár repójából, a GitHub API
     * {@code patch} mezőjével azonos formában. Két git hívás: a fájlnevek, és az összes diff egyben, amit a
     * {@code diff --git} fejlécek mentén darabol (a git mindkét kimenetben azonos sorrendben adja a fájlokat).
     */
    static List<ChangedFile> changedFiles(Path repo, String base) throws IOException, InterruptedException {
        String range = base + "...HEAD";
        // --no-renames: az átnevezés-felismerés a törölt+új fájlpárt egy bejegyzéssé vonná össze, és elcsúsztatná a sorrendet.
        // -z: NUL-lal elválasztott, idézőjelezés nélküli fájlnevek (szóköz, ékezet is biztonságos).
        List<String> names = Arrays.stream(git(repo, "diff", "--no-renames", "--name-only", "-z", "--diff-filter=d", range).split("\0"))
                .filter(name -> !name.isEmpty())
                .toList();
        // Fejléc nélküli darabok; tartalmi sor soha nem kezdődik "diff --git "-tel (azok szóközzel, +/- jellel indulnak).
        List<String> sections = Arrays.stream(git(repo, "diff", "--no-renames", "--diff-filter=d", range).split("(?m)^diff --git "))
                .filter(section -> !section.isEmpty())
                .toList();
        if (names.size() != sections.size()) {
            throw new IllegalStateException(
                    "A fájlnevek (" + names.size() + ") és a diff darabok (" + sections.size() + ") száma eltér.");
        }
        List<ChangedFile> files = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String patch = stripHeader(sections.get(i));
            if (!patch.isBlank()) {
                files.add(new ChangedFile(names.get(i), patch));
            }
        }
        return files;
    }

    /** Eldobja a fájlfejlécet (diff --git, index, ---, +++), az első hunk fejlécétől ({@code @@}) tartja meg a szöveget. */
    static String stripHeader(String diff) {
        int start = diff.startsWith("@@") ? 0 : diff.indexOf("\n@@");
        return start < 0 ? "" : diff.substring(start == 0 ? 0 : start + 1).stripTrailing();
    }

    /** A kimenetet ideiglenes fájlba írja, hogy a várakozás időkorlátos lehessen (a csőből olvasás blokkolna). */
    private static String git(Path repo, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "core.quotePath=false"));
        cmd.addAll(List.of(args));
        Path output = Files.createTempFile("local-diff", ".out");
        try {
            Process process = new ProcessBuilder(cmd)
                    .directory(repo.toFile())
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .redirectOutput(output.toFile())
                    .start();
            try {
                if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "A git parancs " + GIT_TIMEOUT_SECONDS + " mp alatt sem fejeződött be: " + String.join(" ", cmd));
                }
                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    throw new IllegalStateException(
                            "Sikertelen git parancs (" + exitCode + "): " + String.join(" ", cmd));
                }
                return new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
            } finally {
                process.destroyForcibly(); // időtúllépés, megszakítás vagy hiba esetén ne maradjon futó git folyamat
            }
        } finally {
            Files.deleteIfExists(output);
        }
    }
}
