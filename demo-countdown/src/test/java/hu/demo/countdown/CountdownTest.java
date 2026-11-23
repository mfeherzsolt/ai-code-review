package hu.demo.countdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CountdownTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final String NL = System.lineSeparator();

    @Test
    void remainingDecreasesWithTime() {
        Instant end = START.plusSeconds(90);
        assertEquals(Duration.ofSeconds(90), Countdown.remaining(START, end));
        assertEquals(Duration.ofSeconds(60), Countdown.remaining(START.plusSeconds(30), end));
    }

    @Test
    void remainingNeverGoesNegative() {
        assertEquals(Duration.ZERO, Countdown.remaining(START.plusSeconds(100), START.plusSeconds(10)));
    }

    @Test
    void formatsAsHoursMinutesSeconds() {
        assertEquals("00:00:00", Countdown.format(Duration.ZERO));
        assertEquals("01:05:03", Countdown.format(Duration.ofSeconds(3903)));
    }

    @Test
    void formatHoursAreNotLimitedToTwoDigits() {
        assertEquals("100:00:00", Countdown.format(Duration.ofHours(100)));
    }

    @Test
    void parsesValidSeconds() {
        assertEquals(Duration.ofSeconds(42), Countdown.parseSeconds(new String[] {"42"}));
        assertEquals(Countdown.MAX_TOTAL, Countdown.parseSeconds(new String[] {"" + Countdown.MAX_TOTAL.getSeconds()}));
    }

    @Test
    void parseRejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> Countdown.parseSeconds(new String[] {}));
        assertThrows(IllegalArgumentException.class, () -> Countdown.parseSeconds(new String[] {"1", "2"}));
        assertThrows(IllegalArgumentException.class, () -> Countdown.parseSeconds(new String[] {"abc"}));
        assertThrows(IllegalArgumentException.class, () -> Countdown.parseSeconds(new String[] {"0"}));
        assertThrows(IllegalArgumentException.class, () -> Countdown.parseSeconds(new String[] {"-3"}));
    }

    @Test
    void parseRejectsTooLargeValues() {
        String justOver = "" + (Countdown.MAX_TOTAL.getSeconds() + 1);
        assertThrows(IllegalArgumentException.class, () -> Countdown.parseSeconds(new String[] {justOver}));
        assertThrows(
                IllegalArgumentException.class,
                () -> Countdown.parseSeconds(new String[] {String.valueOf(Long.MAX_VALUE)}));
    }

    @Test
    void roundsUpToWholeSeconds() {
        assertEquals("00:00:02", Countdown.format(Countdown.roundUpToSeconds(Duration.ofMillis(1500))));
        assertEquals(Duration.ofSeconds(1), Countdown.roundUpToSeconds(Duration.ofMillis(1)));
        assertEquals(Duration.ofSeconds(3), Countdown.roundUpToSeconds(Duration.ofSeconds(3)));
        assertEquals(Duration.ZERO, Countdown.roundUpToSeconds(Duration.ZERO));
    }

    @Test
    void untilNextTickIsFractionOrFullSecond() {
        assertEquals(Duration.ofMillis(500), Countdown.untilNextTick(Duration.ofMillis(1500)));
        assertEquals(Duration.ofMillis(1), Countdown.untilNextTick(Duration.ofMillis(3001)));
        assertEquals(Duration.ofSeconds(1), Countdown.untilNextTick(Duration.ofSeconds(3)));
    }

    @Test
    void runPrintsEachSecondAndDone() {
        MutableClock clock = new MutableClock(START);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        Countdown.run(Duration.ofSeconds(3), clock, new PrintStream(buffer), clock::advance);

        assertEquals(String.join(NL, "00:00:03", "00:00:02", "00:00:01", "Done!") + NL, buffer.toString());
    }

    @Test
    void runSleepsUntilNextSecondBoundaryForFractionalTotal() {
        MutableClock clock = new MutableClock(START);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        List<Duration> sleeps = new ArrayList<>();

        Countdown.run(Duration.ofMillis(1500), clock, new PrintStream(buffer), duration -> {
            sleeps.add(duration);
            clock.advance(duration);
        });

        assertEquals(String.join(NL, "00:00:02", "00:00:01", "Done!") + NL, buffer.toString());
        assertEquals(List.of(Duration.ofMillis(500), Duration.ofSeconds(1)), sleeps);
    }

    @Test
    void runCorrectsForOversleeping() {
        MutableClock clock = new MutableClock(START);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        // Minden várakozás 200 ms-mal hosszabb a kértnél (lassú kimenet): a kijelzés nem csúszhat el.
        Countdown.run(
                Duration.ofSeconds(3), clock, new PrintStream(buffer), duration -> clock.advance(duration.plusMillis(200)));

        assertEquals(String.join(NL, "00:00:03", "00:00:02", "00:00:01", "Done!") + NL, buffer.toString());
    }

    @Test
    void runStartsTimingWhenCalled() {
        MutableClock clock = new MutableClock(START);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        clock.advance(Duration.ofHours(1)); // a hívás előtt eltelt idő nem számít bele

        Countdown.run(Duration.ofSeconds(2), clock, new PrintStream(buffer), clock::advance);

        assertEquals(String.join(NL, "00:00:02", "00:00:01", "Done!") + NL, buffer.toString());
    }

    @Test
    void runStopsWhenInterrupted() {
        MutableClock clock = new MutableClock(START);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        Countdown.run(Duration.ofSeconds(5), clock, new PrintStream(buffer), duration -> {
            throw new InterruptedException();
        });

        assertEquals("00:00:05" + NL, buffer.toString());
        assertTrue(Thread.interrupted());
    }

    @Test
    void executeReturnsErrorCodeAndMessageOnInvalidInput() {
        for (String arg : new String[] {"-3", String.valueOf(Long.MAX_VALUE)}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();

            int exitCode = Countdown.execute(
                    new String[] {arg},
                    new MutableClock(START),
                    new PrintStream(out),
                    new PrintStream(err),
                    duration -> {});

            assertEquals(1, exitCode, arg);
            assertEquals("", out.toString(), arg);
            assertTrue(err.toString().contains("Használat: Countdown <másodperc>"), err.toString());
        }
    }

    @Test
    void executeRunsCountdownOnValidInput() {
        MutableClock clock = new MutableClock(START);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int exitCode = Countdown.execute(
                new String[] {"1"}, clock, new PrintStream(out), new PrintStream(new ByteArrayOutputStream()), clock::advance);

        assertEquals(0, exitCode);
        assertEquals("00:00:01" + NL + "Done!" + NL, out.toString());
    }

    /** Kézzel léptethető óra a determinisztikus tesztekhez. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
