package hu.demo.countdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
