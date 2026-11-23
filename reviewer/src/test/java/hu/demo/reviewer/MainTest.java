package hu.demo.reviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MainTest {

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
    void parsesMinimumSeverity() {
        assertEquals("minor", Main.parseSeverity("Minor"));
        assertEquals("nit", Main.parseSeverity("nit"));
    }

    @Test
    void rejectsUnknownMinimumSeverity() {
        assertThrows(IllegalArgumentException.class, () -> Main.parseSeverity("mijor"));
    }
}
