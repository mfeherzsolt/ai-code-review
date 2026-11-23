package hu.demo.countdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class CountdownTest {

    private static final String NL = System.lineSeparator();

    @Test
    void remainingDecreasesWithTime() {
        long end = Duration.ofSeconds(90).toNanos();
        assertEquals(Duration.ofSeconds(90), Countdown.remaining(0, end));
        assertEquals(Duration.ofSeconds(60), Countdown.remaining(Duration.ofSeconds(30).toNanos(), end));
    }

    @Test
    void remainingNeverGoesNegative() {
        assertEquals(Duration.ZERO, Countdown.remaining(Duration.ofSeconds(100).toNanos(), Duration.ofSeconds(10).toNanos()));
    }

    @Test
    void remainingHandlesNanoTimeOverflow() {
        long now = Long.MAX_VALUE - 500;
        long end = now + Duration.ofSeconds(2).toNanos(); // átfordul negatívba
        assertEquals(Duration.ofSeconds(2), Countdown.remaining(now, end));
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
    void runPrintsEachSecondAndDone() {
        FakeNanoTime time = new FakeNanoTime();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        Countdown.run(Duration.ofSeconds(3), time, new PrintStream(buffer), time::advance);

        assertEquals(String.join(NL, "00:00:03", "00:00:02", "00:00:01", "Done!") + NL, buffer.toString());
    }

    @Test
    void runSleepsOnlyTheRemainingTimeForTheLastFraction() {
        FakeNanoTime time = new FakeNanoTime();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        List<Duration> sleeps = new ArrayList<>();

        Countdown.run(Duration.ofMillis(1500), time, new PrintStream(buffer), duration -> {
            sleeps.add(duration);
            time.advance(duration);
        });

        assertEquals(String.join(NL, "00:00:02", "00:00:01", "Done!") + NL, buffer.toString());
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofMillis(500)), sleeps);
    }

    @Test
    void runCorrectsForOversleeping() {
        FakeNanoTime time = new FakeNanoTime();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        // Minden várakozás 200 ms-mal hosszabb a kértnél (lassú kimenet): a hátralévő idő az óráról jön, nem a lépésekből.
        Countdown.run(
                Duration.ofSeconds(3), time, new PrintStream(buffer), duration -> time.advance(duration.plusMillis(200)));

        assertEquals(String.join(NL, "00:00:03", "00:00:02", "00:00:01", "Done!") + NL, buffer.toString());
    }

    @Test
    void runStartsTimingWhenCalled() {
        FakeNanoTime time = new FakeNanoTime();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        time.advance(Duration.ofHours(1)); // a hívás előtt eltelt idő nem számít bele

        Countdown.run(Duration.ofSeconds(2), time, new PrintStream(buffer), time::advance);

        assertEquals(String.join(NL, "00:00:02", "00:00:01", "Done!") + NL, buffer.toString());
    }

    @Test
    void runStopsWhenInterrupted() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        boolean completed = Countdown.run(Duration.ofSeconds(5), new FakeNanoTime(), new PrintStream(buffer), duration -> {
            throw new InterruptedException();
        });

        assertFalse(completed);
        assertEquals("00:00:05" + NL, buffer.toString());
        assertTrue(Thread.interrupted());
    }

    @Test
    void executeReturnsInterruptedExitCodeWhenInterrupted() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int exitCode = Countdown.execute(
                new String[] {"5"},
                new FakeNanoTime(),
                new PrintStream(out),
                new PrintStream(new ByteArrayOutputStream()),
                duration -> {
                    throw new InterruptedException();
                });

        assertEquals(Countdown.EXIT_INTERRUPTED, exitCode);
        assertTrue(Thread.interrupted());
    }

    @Test
    void executeReturnsErrorCodeAndMessageOnInvalidInput() {
        for (String arg : new String[] {"-3", String.valueOf(Long.MAX_VALUE)}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();

            int exitCode = Countdown.execute(
                    new String[] {arg}, new FakeNanoTime(), new PrintStream(out), new PrintStream(err), duration -> {});

            assertEquals(1, exitCode, arg);
            assertEquals("", out.toString(), arg);
            assertTrue(err.toString().contains("Használat: Countdown <másodperc>"), err.toString());
        }
    }

    @Test
    void executeRunsCountdownOnValidInput() {
        FakeNanoTime time = new FakeNanoTime();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int exitCode = Countdown.execute(
                new String[] {"1"}, time, new PrintStream(out), new PrintStream(new ByteArrayOutputStream()), time::advance);

        assertEquals(0, exitCode);
        assertEquals("00:00:01" + NL + "Done!" + NL, out.toString());
    }

    /** Kézzel léptethető {@code nanoTime} a determinisztikus tesztekhez. */
    private static final class FakeNanoTime implements LongSupplier {
        private long nanos;

        void advance(Duration duration) {
            nanos += duration.toNanos();
        }

        @Override
        public long getAsLong() {
            return nanos;
        }
    }
}
