package hu.demo.countdown;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A {@link System#nanoTime()} alapján számol, ezért a rendszeróra átállítása (NTP, kézi módosítás) nem
 * befolyásolja. Az induláskor egyszer kiolvasott falióra-időhöz adja hozzá az azóta eltelt monoton időt.
 */
final class MonotonicClock extends Clock {

    private final Instant origin = Instant.now();
    private final long originNanos = System.nanoTime();

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
        return origin.plusNanos(System.nanoTime() - originNanos);
    }
}
