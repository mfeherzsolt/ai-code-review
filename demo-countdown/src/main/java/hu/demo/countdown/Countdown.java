package hu.demo.countdown;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.io.PrintStream;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

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

    /** Az alvás szemcsézettsége másodpercben. */
    private static final long TICK_SECONDS = 1;

    /** A várakozás absztrakciója, hogy a ciklus tesztelhető legyen. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long seconds) throws InterruptedException;
    }

    /**
     * Használat: {@code Countdown <másodperc>}. A másodpercnek pozitív egész számnak kell lennie.
     * Hibás bemenet esetén rövid hibaüzenetet ír a hibakimenetre, és 1-es kilépési kóddal tér vissza.
     */
    public static void main(String[] args) {
        try {
            Countdown countdown = new Countdown(parseSeconds(args), Clock.systemUTC());
            run(countdown, System.out, TimeUnit.SECONDS::sleep);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    private static Duration parseSeconds(String[] args) {
        if (args.length != 1) {
            throw usage("Hiányzó paraméter.");
        }
        long seconds;
        try {
            seconds = Long.parseLong(args[0]);
        } catch (NumberFormatException e) {
            throw usage("Nem szám: " + args[0]);
        }
        if (seconds <= 0) {
            throw usage("A másodpercnek pozitívnak kell lennie: " + seconds + ".");
        }
        return Duration.ofSeconds(seconds);
    }

    private static IllegalArgumentException usage(String reason) {
        return new IllegalArgumentException(reason + " Használat: Countdown <másodperc>");
    }

    /** Felfelé kerekít egész másodpercre (pl. 1,5 mp -> 2 mp). */
    static Duration roundUpToSeconds(Duration duration) {
        Duration truncated = duration.truncatedTo(ChronoUnit.SECONDS);
        return truncated.equals(duration) ? duration : truncated.plusSeconds(1);
    }

    /**
     * Másodpercenként kiírja a hátralévő időt, amíg le nem telik, majd a "Done!" üzenetet.
     * Megszakítás esetén visszaállítja a megszakítási jelzőt és kilép.
     */
    static void run(Countdown countdown, PrintStream out, Sleeper sleeper) {
        Duration left = countdown.remaining();
        while (!left.isZero()) {
            out.println(format(roundUpToSeconds(left)));
            try {
                sleeper.sleep(TICK_SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            left = countdown.remaining();
        }
        out.println("Done!");
    }
}
