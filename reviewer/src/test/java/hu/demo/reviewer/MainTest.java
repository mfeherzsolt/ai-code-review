package hu.demo.reviewer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MainTest {

    @Test
    void filtersBelowMinimumSeverity() {
        assertFalse(Main.atLeast("nit", "major"));
        assertFalse(Main.atLeast("minor", "major"));
        assertTrue(Main.atLeast("major", "major"));
        assertTrue(Main.atLeast("critical", "major"));
    }

    @Test
    void unknownSeverityIsNeverFiltered() {
        assertTrue(Main.atLeast("weird", "major"));
        assertTrue(Main.atLeast("minor", "weird"));
    }
}
