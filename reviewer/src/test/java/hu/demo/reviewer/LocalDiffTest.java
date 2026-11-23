package hu.demo.reviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

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
}
