package hu.demo.reviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

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
    void unknownCommentSeverityIsNeverFiltered() {
        assertTrue(Main.atLeast("weird", "major"));
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
    void reportContainsSummaryAndInlineComments() throws Exception {
        Main.Result result =
                Main.render(new ObjectMapper().readTree(REVIEW), "minor", Map.of("A.java", Set.of(3)));

        String report = Main.report(result);

        assertTrue(report.startsWith("## AI Code Review"));
        assertTrue(report.contains("[A.java:3]\n**[major]** inline"));
    }

    @Test
    void noFindingsWhenEverythingIsBelowThreshold() throws Exception {
        Main.Result result =
                Main.render(new ObjectMapper().readTree(REVIEW), "critical", Map.of("A.java", Set.of(3)));

        assertEquals(0, result.findings());
        assertTrue(result.inline().isEmpty());
    }
}
