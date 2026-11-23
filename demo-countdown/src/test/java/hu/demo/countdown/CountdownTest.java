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

    @Test
    void remainingDecreasesWithTime() {
        MutableClock clock = new MutableClock(START);
        Countdown countdown = new Countdown(Duration.ofSeconds(90), clock);
        assertEquals(Duration.ofSeconds(90), countdown.remaining());

        clock.advance(Duration.ofSeconds(30));
        assertEquals(Duration.ofSeconds(60), countdown.remaining());
    }

    @Test
    void remainingNeverGoesNegative() {
        MutableClock clock = new MutableClock(START);
        Countdown countdown = new Countdown(Duration.ofSeconds(10), clock);

        clock.advance(Duration.ofSeconds(100));
        assertEquals(Duration.ZERO, countdown.remaining());
    }

    @Test
    void rejectsNonPositiveDuration() {
        Clock clock = new MutableClock(START);
        assertThrows(IllegalArgumentException.class, () -> new Countdown(Duration.ZERO, clock));
        assertThrows(IllegalArgumentException.class, () -> new Countdown(Duration.ofSeconds(-5), clock));
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
    void executeReturnsErrorCodeAndMessageOnInvalidInput() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = Countdown.execute(
                new String[] {"-3"}, new MutableClock(START), new PrintStream(out), new PrintStream(err), duration -> {});

        assertEquals(1, exitCode);
        assertEquals("", out.toString());
        assertTrue(err.toString().contains("Használat: Countdown <másodperc>"), err.toString());
    }

    @Test
    void executeRunsCountdownOnValidInput() {
        MutableClock clock = new MutableClock(START);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int exitCode = Countdown.execute(
                new String[] {"1"}, clock, new PrintStream(out), new PrintStream(new ByteArrayOutputStream()), clock::advance);

        assertEquals(0, exitCode);
        assertEquals("00:00:01" + System.lineSeparator() + "Done!" + System.lineSeparator(), out.toString());
    }

    @Test
    void monotonicClockNeverGoesBackwards() {
        Clock clock = new MonotonicClock();
        Instant previous = clock.instant();
        for (int i = 0; i < 1000; i++) {
            Instant current = clock.instant();
            assertTrue(!current.isBefore(previous));
            previous = current;
        }
    }

    @Test
    void roundsUpToWholeSeconds() {
        assertEquals("00:00:02", Countdown.format(Countdown.roundUpToSeconds(Duration.ofMillis(1500))));
        assertEquals(Duration.ofSeconds(1), Countdown.roundUpToSeconds(Duration.ofMillis(1)));
        assertEquals(Duration.ofSeconds(3), Countdown.roundUpToSeconds(Duration.ofSeconds(3)));
        assertEquals(Duration.ZERO, Countdown.roundUpToSeconds(Duration.ZERO));
    }

    @Test
    void runPrintsEachSecondAndDone() {
        MutableClock clock = new MutableClock(START);
        Countdown countdown = new Countdown(Duration.ofSeconds(3), clock);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        Countdown.run(countdown, new PrintStream(buffer), clock::advance);

        String lineSeparator = System.lineSeparator();
        assertEquals(
                String.join(lineSeparator, "00:00:03", "00:00:02", "00:00:01", "Done!") + lineSeparator,
                buffer.toString());
    }

    @Test
    void runSleepsUntilNextSecondBoundaryForFractionalRemaining() {
        MutableClock clock = new MutableClock(START);
        Countdown countdown = new Countdown(Duration.ofMillis(1500), clock);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        List<Duration> sleeps = new ArrayList<>();

        Countdown.run(countdown, new PrintStream(buffer), duration -> {
            sleeps.add(duration);
            clock.advance(duration);
        });

        String lineSeparator = System.lineSeparator();
        assertEquals(
                String.join(lineSeparator, "00:00:02", "00:00:01", "Done!") + lineSeparator, buffer.toString());
        assertEquals(List.of(Duration.ofMillis(500), Duration.ofSeconds(1)), sleeps);
    }

    @Test
    void runCorrectsForOversleeping() {
        MutableClock clock = new MutableClock(START);
        Countdown countdown = new Countdown(Duration.ofSeconds(3), clock);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        // Minden várakozás 200 ms-mal hosszabb a kértnél (lassú kimenet): a kijelzés nem csúszhat el.
        Countdown.run(countdown, new PrintStream(buffer), duration -> clock.advance(duration.plusMillis(200)));

        String lineSeparator = System.lineSeparator();
        assertEquals(
                String.join(lineSeparator, "00:00:03", "00:00:02", "00:00:01", "Done!") + lineSeparator,
                buffer.toString());
    }

    @Test
    void untilNextTickIsFractionOrFullSecond() {
        assertEquals(Duration.ofMillis(500), Countdown.untilNextTick(Duration.ofMillis(1500)));
        assertEquals(Duration.ofMillis(1), Countdown.untilNextTick(Duration.ofMillis(3001)));
        assertEquals(Duration.ofSeconds(1), Countdown.untilNextTick(Duration.ofSeconds(3)));
    }

    @Test
    void runStopsWhenInterrupted() {
        MutableClock clock = new MutableClock(START);
        Countdown countdown = new Countdown(Duration.ofSeconds(5), clock);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        Countdown.run(countdown, new PrintStream(buffer), duration -> {
            throw new InterruptedException();
        });

        assertEquals("00:00:05" + System.lineSeparator(), buffer.toString());
        assertTrue(Thread.interrupted());
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
