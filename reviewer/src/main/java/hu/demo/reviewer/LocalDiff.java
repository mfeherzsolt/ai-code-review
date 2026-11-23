package hu.demo.reviewer;

import hu.demo.reviewer.GitHubClient.ChangedFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
     * {@code patch} mezőjével azonos formában. Egyetlen git hívás; a kimenetet a {@code diff --git} fejlécek
     * mentén darabolja, a fájlnevet pedig minden darab saját {@code +++} sorából olvassa.
     */
    static List<ChangedFile> changedFiles(Path repo, String base) throws IOException, InterruptedException {
        // --no-renames: az átnevezés-felismerés nem fogja egy bejegyzésbe a törölt és az új fájlt.
        String diff = git(repo, "diff", "--no-renames", "--diff-filter=d", base + "...HEAD");
        List<ChangedFile> files = new ArrayList<>();
        // Tartalmi sor soha nem kezdődik "diff --git "-tel (azok szóközzel, +/- jellel indulnak).
        for (String section : diff.split("(?m)^diff --git ")) {
            String patch = stripHeader(section);
            if (!patch.isBlank()) {
                files.add(new ChangedFile(fileName(section), patch));
            }
        }
        return files;
    }

    /**
     * A darab fejlécének {@code +++ b/<név>} sorából a fájlnév. A git a szóközt tartalmazó név után tabulátort tesz,
     * az idézőjelet vagy fordított perjelet tartalmazó nevet pedig idézőjelbe és escape-elve adja; ez utóbbit
     * nem támogatjuk, hogy ne csendben rossz néven küldjünk kommentet.
     */
    private static String fileName(String section) {
        for (String line : section.split("\n")) {
            if (line.startsWith("@@")) {
                break;
            }
            if (line.startsWith("+++ ")) {
                int tab = line.indexOf('\t');
                String name = tab < 0 ? line.substring(4) : line.substring(4, tab);
                if (!name.startsWith("b/")) {
                    throw new IllegalStateException("Nem támogatott fájlnév a diff fejlécében: " + line);
                }
                return name.substring(2);
            }
        }
        throw new IllegalStateException("Nincs +++ sor a diff fejlécében: " + section);
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
