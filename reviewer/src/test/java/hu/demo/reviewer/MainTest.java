package hu.demo.reviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {

    private static final String REVIEW = """
            {"summary": "S", "comments": [
              {"file": "A.java", "line": 3, "severity": "major", "message": "inline"},
              {"file": "A.java", "line": 99, "severity": "minor", "message": "diffen kivul"},
              {"file": "A.java", "line": 3, "severity": "nit", "message": "kicsi"}
            ]}""";

    @Test
    void filtersBelowMinimumSeverity() {
        assertFalse(Main.atLeast("nit", "minor"));
        assertTrue(Main.atLeast("minor", "minor"));
        assertTrue(Main.atLeast("major", "minor"));
        assertTrue(Main.atLeast("critical", "minor"));
    }

    @Test
    void severityMatchingIgnoresCase() {
        assertTrue(Main.atLeast("MAJOR", "major"));
        assertFalse(Main.atLeast("NIT", "minor"));
    }

    @Test
    void unknownCommentSeverityCountsAsMinor() {
        assertTrue(Main.atLeast("weird", "minor"));
        assertFalse(Main.atLeast("weird", "major"));
    }

    @Test
    void rejectsUnknownMinimumInAtLeast() {
        assertThrows(IllegalArgumentException.class, () -> Main.atLeast("minor", "weird"));
    }

    @Test
    void parsesMinimumSeverity() {
        assertEquals("minor", Main.parseSeverity("Minor"));
        assertEquals("nit", Main.parseSeverity("nit"));
    }

    @Test
    void rejectsUnknownMinimumSeverity() {
        assertThrows(IllegalArgumentException.class, () -> Main.parseSeverity("mijor"));
    }

    @Test
    void rendersFindingsAtOrAboveThresholdAndCollapsesTheRest() throws Exception {
        Main.Result result =
                Main.render(new ObjectMapper().readTree(REVIEW), "minor", Map.of("A.java", Set.of(3)));

        assertEquals(2, result.findings());
        assertEquals(1, result.inline().size());
        assertEquals(3, result.inline().get(0).line());
        assertTrue(result.summary().contains("`A.java:99`"));
        assertTrue(result.summary().contains("1 kisebb észrevétel (minor alatt)"));
    }

    @Test
    void dropsCommentsThatSayNoActionIsNeeded() throws Exception {
        String review = """
                {"summary": "S", "comments": [
                  {"file": "A.java", "line": 3, "severity": "major", "message": "Osszessegeben elfogadható, nincs teendő."}
                ]}""";
        Main.Result result = Main.render(new ObjectMapper().readTree(review), "minor", Map.of("A.java", Set.of(3)));

        assertEquals(0, result.findings());
        assertTrue(result.inline().isEmpty());
        assertFalse(result.summary().contains("elfogadható"));
    }

    @Test
    void keepsCommentsThatSayItIsNotAcceptable() throws Exception {
        String review = """
                {"summary": "S", "comments": [
                  {"file": "A.java", "line": 3, "severity": "major", "message": "Ez nem elfogadható: hibát nyel el."}
                ]}""";
        Main.Result result = Main.render(new ObjectMapper().readTree(review), "minor", Map.of("A.java", Set.of(3)));

        assertEquals(1, result.findings());
    }

    @Test
    void reportContainsSummaryAndInlineComments() throws Exception {
        Main.Result result =
                Main.render(new ObjectMapper().readTree(REVIEW), "minor", Map.of("A.java", Set.of(3)));

        String report = Main.report(result.summary(), result.inline());

        assertTrue(report.startsWith("## AI Code Review"));
        assertTrue(report.contains("[A.java:3]\n**[major]** inline"));
    }

    @Test
    void savesOneReportPerChangedModuleWithItsOwnComments(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("mod"));
        Files.writeString(root.resolve("mod").resolve("pom.xml"), "<project/>");
        Files.createDirectories(root.resolve("other"));
        Files.writeString(root.resolve("other").resolve("pom.xml"), "<project/>");
        Main.Result result = new Main.Result(
                "SUMMARY",
                List.of(
                        new GitHubClient.InlineComment("mod/A.java", 3, "modul-komment"),
                        new GitHubClient.InlineComment("agent.md", 1, "gyoker-komment")),
                2);
        List<GitHubClient.ChangedFile> files = List.of(
                new GitHubClient.ChangedFile("mod/A.java", "@@"), new GitHubClient.ChangedFile("agent.md", "@@"));

        Main.saveReports(root, files, result, "local-review");

        String modReport = Files.readString(root.resolve("mod/target/local-review/latest.md"));
        String rootReport = Files.readString(root.resolve("target/local-review/latest.md"));
        assertTrue(modReport.contains("SUMMARY") && modReport.contains("modul-komment"));
        assertFalse(modReport.contains("gyoker-komment"));
        assertTrue(rootReport.contains("gyoker-komment"));
        assertFalse(rootReport.contains("modul-komment"));
        assertFalse(Files.exists(root.resolve("other/target")));
    }

    @Test
    void invalidJsonErrorContainsTheRawAnswer() {
        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> Main.parseReview("{\"summary\": nem-json}"));

        assertTrue(e.getMessage().contains("nem-json"));
    }

    @Test
    void reportSaveFailureDoesNotThrow(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("target"), "fajl, nem konyvtar");
        Main.Result result = new Main.Result("S", List.of(), 0);
        List<GitHubClient.ChangedFile> files = List.of(new GitHubClient.ChangedFile("agent.md", "@@"));

        assertThrows(java.io.IOException.class, () -> Main.saveReports(root, files, result, "review"));
        Main.saveReportsOrWarn(root, files, result, "review");
    }

    @Test
    void modulIsTheFirstPathSegmentWithAPom(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("mod"));
        Files.writeString(root.resolve("mod").resolve("pom.xml"), "<project/>");
        Files.createDirectories(root.resolve("scripts"));

        assertEquals("mod", Main.moduleOf(root, "mod/src/A.java"));
        assertEquals("", Main.moduleOf(root, "scripts/local-review.sh"));
        assertEquals("", Main.moduleOf(root, "agent.md"));
    }

    @Test
    void noFindingsWhenEverythingIsBelowThreshold() throws Exception {
        Main.Result result =
                Main.render(new ObjectMapper().readTree(REVIEW), "critical", Map.of("A.java", Set.of(3)));

        assertEquals(0, result.findings());
        assertTrue(result.inline().isEmpty());
    }
}
