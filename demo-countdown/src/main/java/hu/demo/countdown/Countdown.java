package hu.demo.countdown;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Egyszerű visszaszámláló: a hátralévő időt a megadott óra alapján számolja. */
public final class Countdown {

    private final Clock clock;
    private final Instant end;

    /**
     * @param total a visszaszámlálás hossza, pozitívnak kell lennie
     * @param clock az idő forrása (tesztben cserélhető)
     */
    public Countdown(Duration total, Clock clock) {
        if (total.isNegative() || total.isZero()) {
            throw new IllegalArgumentException("A visszaszámlálás hossza pozitív kell legyen: " + total);
        }
        this.clock = clock;
        this.end = clock.instant().plus(total);
    }

    /** A hátralévő idő, soha nem negatív. */
    public Duration remaining() {
        Duration left = Duration.between(clock.instant(), end);
        return left.isNegative() ? Duration.ZERO : left;
    }

    /** HH:mm:ss formátum. */
    public static String format(Duration duration) {
        return String.format("%02d:%02d:%02d", duration.toHours(), duration.toMinutesPart(), duration.toSecondsPart());
    }

    public static void main(String[] args) {
        Countdown countdown = new Countdown(parseSeconds(args), Clock.systemUTC());
        run(countdown);
    }

    private static Duration parseSeconds(String[] args) {
        if (args.length != 1) {
            throw usage("Hiányzó paraméter.");
        }
        try {
            return Duration.ofSeconds(Long.parseLong(args[0]));
        } catch (NumberFormatException e) {
            throw usage("Nem szám: " + args[0]);
        }
    }

    private static IllegalArgumentException usage(String reason) {
        return new IllegalArgumentException(reason + " Használat: Countdown <másodperc>");
    }

    private static Duration roundUpToSeconds(Duration duration) {
        Duration truncated = duration.truncatedTo(ChronoUnit.SECONDS);
        return truncated.equals(duration) ? duration : truncated.plusSeconds(1);
    }

    private static void run(Countdown countdown) {
        Duration left = countdown.remaining();
        while (!left.isZero()) {
            System.out.println(format(roundUpToSeconds(left)));
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            left = countdown.remaining();
        }
        System.out.println("Done!");
    }
}
