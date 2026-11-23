package hu.demo.countdown;

import java.io.PrintStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Egyszerű visszaszámláló: a hátralévő időt a megadott óra alapján számolja. */
public final class Countdown {

    private final Clock clock;
    private final Instant end;

    /**
     * A visszaszámlálás a konstrukció pillanatában indul: a példány létrehozása és a {@link #run}
     * meghívása között eltelt idő is beleszámol a hosszába.
     *
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

    /**
     * HH:mm:ss formátum. Az órák nincsenek 24-re korlátozva, 99 óra felett az óra mező háromjegyű (pl. 100:00:00).
     */
    public static String format(Duration duration) {
        return String.format("%02d:%02d:%02d", duration.toHours(), duration.toMinutesPart(), duration.toSecondsPart());
    }

    /** A várakozás absztrakciója, hogy a ciklus tesztelhető legyen. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /**
     * Használat: {@code Countdown <másodperc>}. A másodpercnek pozitív egész számnak kell lennie.
     * Hibás bemenet esetén rövid hibaüzenetet ír a hibakimenetre, és 1-es kilépési kóddal tér vissza.
     */
    public static void main(String[] args) {
        int exitCode = execute(args, new MonotonicClock(), System.out, System.err, Thread::sleep);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * A {@code main} tesztelhető magja. Csak a bemenet feldolgozásakor dobott hibát kezeli (1-es kilépési kód),
     * a visszaszámlálás közbeni hibák nem nyelődnek el.
     *
     * @return a folyamat kilépési kódja
     */
    static int execute(String[] args, Clock clock, PrintStream out, PrintStream err, Sleeper sleeper) {
        Duration total;
        try {
            total = parseSeconds(args);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return 1;
        }
        run(new Countdown(total, clock), out, sleeper);
        return 0;
    }

    static Duration parseSeconds(String[] args) {
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
     * Ennyit kell várni, hogy a kijelzett (felfelé kerekített) másodperc változzon: a hátralévő idő tört
     * része, egész másodpercnél egy teljes másodperc. Mindig az aktuális hátralévő időből számol, így a ciklus
     * lemaradása a következő lépésnél korrigálódik.
     */
    static Duration untilNextTick(Duration left) {
        Duration fraction = left.minus(left.truncatedTo(ChronoUnit.SECONDS));
        return fraction.isZero() ? Duration.ofSeconds(1) : fraction;
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
                sleeper.sleep(untilNextTick(left));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            left = countdown.remaining();
        }
        out.println("Done!");
    }
}
