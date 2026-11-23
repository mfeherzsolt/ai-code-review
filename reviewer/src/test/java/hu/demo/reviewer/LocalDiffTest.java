package hu.demo.reviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import hu.demo.reviewer.GitHubClient.ChangedFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalDiffTest {

    @Test
    void stripsFileHeaderKeepingHunks() {
        String diff = "diff --git a/A.java b/A.java\nindex 1..2 100644\n--- a/A.java\n+++ b/A.java\n"
                + "@@ -1,2 +1,3 @@\n a\n+b\n c\n";
        assertEquals("@@ -1,2 +1,3 @@\n a\n+b\n c", LocalDiff.stripHeader(diff));
    }

    @Test
    void emptyWhenThereAreNoHunks() {
        assertEquals("", LocalDiff.stripHeader("diff --git a/B.bin b/B.bin\nBinary files differ\n"));
    }

    @Test
    void readsChangedFilesWithSpecialNamesAndSkipsDeleted(@TempDir Path repo) throws Exception {
        git(repo, "init", "-q", "-b", "main");
        Files.writeString(repo.resolve("régi fájl.txt"), "a\n");
        Files.writeString(repo.resolve("torolt.txt"), "x\n");
        git(repo, "add", ".");
        git(repo, "commit", "-q", "-m", "base");
        git(repo, "checkout", "-q", "-b", "feature");
        Files.writeString(repo.resolve("régi fájl.txt"), "a\nb\n");
        Files.delete(repo.resolve("torolt.txt"));
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "change");

        List<ChangedFile> files = LocalDiff.changedFiles(repo, "main");

        assertEquals(1, files.size());
        assertEquals("régi fájl.txt", files.get(0).filename());
        assertEquals("@@ -1 +1,2 @@\n a\n+b", files.get(0).patch());
    }

    @Test
    void failsOnUnknownBase(@TempDir Path repo) throws Exception {
        git(repo, "init", "-q", "-b", "main");
        assertThrows(IllegalStateException.class, () -> LocalDiff.changedFiles(repo, "nincs-ilyen-ag"));
    }

    private static void git(Path repo, String... args) throws IOException, InterruptedException {
        String[] cmd = new String[args.length + 7];
        cmd[0] = "git";
        cmd[1] = "-c";
        cmd[2] = "user.name=test";
        cmd[3] = "-c";
        cmd[4] = "user.email=test@example.com";
        cmd[5] = "-c";
        cmd[6] = "commit.gpgsign=false";
        System.arraycopy(args, 0, cmd, 7, args.length);
        Process process = new ProcessBuilder(cmd).directory(repo.toFile()).inheritIO().start();
        assertEquals(0, process.waitFor(), String.join(" ", args));
    }
}
