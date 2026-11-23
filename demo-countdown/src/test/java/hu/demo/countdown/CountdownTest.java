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

        Countdown.run(countdown, new PrintStream(buffer), seconds -> clock.advance(Duration.ofSeconds(seconds)));

        String lineSeparator = System.lineSeparator();
        assertEquals(
                String.join(lineSeparator, "00:00:03", "00:00:02", "00:00:01", "Done!") + lineSeparator,
                buffer.toString());
    }

    @Test
    void runRoundsFractionalRemainingUp() {
        MutableClock clock = new MutableClock(START);
        Countdown countdown = new Countdown(Duration.ofMillis(1500), clock);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        Countdown.run(countdown, new PrintStream(buffer), seconds -> clock.advance(Duration.ofSeconds(seconds)));

        String output = buffer.toString();
        assertTrue(output.startsWith("00:00:02"), output);
        assertTrue(output.endsWith("Done!" + System.lineSeparator()), output);
    }

    @Test
    void runStopsWhenInterrupted() {
        MutableClock clock = new MutableClock(START);
        Countdown countdown = new Countdown(Duration.ofSeconds(5), clock);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        Countdown.run(countdown, new PrintStream(buffer), seconds -> {
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
