package hu.demo.countdown;

import java.io.PrintStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * Egyszerű visszaszámláló: a hátralévő időt a falióra alapján számolja, másodpercenként kiírja.
 *
 * <p>Az időforrás a {@link #nanoTimeClock()}: induláskor egyszer kiolvassa a falióra idejét, utána csak a
 * {@code System.nanoTime()} különbségét adja hozzá, így a rendszeróra átállítása (NTP, kézi módosítás) nem
 * tolja el a hátralévő időt.
 */
public final class Countdown {

    /** A megadható legnagyobb hossz; ennél nagyobb érték az {@link Instant} tartományát is túllépheti. */
    static final Duration MAX_TOTAL = Duration.ofDays(365);

    /** A megszakított visszaszámlálás kilépési kódja (a SIGINT-nél szokásos 128 + 2). */
    static final int EXIT_INTERRUPTED = 130;

    private Countdown() {}

    /** A várakozás absztrakciója, hogy a ciklus tesztelhető legyen. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /**
     * Használat: {@code Countdown <másodperc>}. A másodpercnek pozitív egész számnak kell lennie,
     * legfeljebb 365 napnyinak (31536000). Hibás bemenet esetén rövid hibaüzenetet ír a hibakimenetre,
     * és 1-es kilépési kóddal tér vissza; megszakítás esetén a kilépési kód {@link #EXIT_INTERRUPTED}.
     */
    public static void main(String[] args) {
        System.exit(execute(args, nanoTimeClock(), System.out, System.err, Thread::sleep));
    }

    /**
     * Monoton {@link Clock}: a hívás pillanatában mért falióra-időhöz adja hozzá a {@code System.nanoTime()}-mal
     * mért eltelt időt. Az időzónának nincs hatása, mert csak {@link Instant}-ot szolgáltat.
     */
    static Clock nanoTimeClock() {
        Instant origin = Instant.now();
        long originNanos = System.nanoTime();
        return new Clock() {
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
        };
    }

    /**
     * A {@code main} tesztelhető magja. Csak a bemenet feldolgozásakor dobott hibát kezeli (1-es kilépési kód),
     * a visszaszámlálás közbeni hibák nem nyelődnek el.
     *
     * @return a folyamat kilépési kódja: 0 siker, 1 hibás bemenet, {@link #EXIT_INTERRUPTED} megszakítás
     */
    static int execute(String[] args, Clock clock, PrintStream out, PrintStream err, Sleeper sleeper) {
        Duration total;
        try {
            total = parseSeconds(args);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return 1;
        }
        return run(total, clock, out, sleeper) ? 0 : EXIT_INTERRUPTED;
    }

    /**
     * A parancssori argumentumból kiolvassa a visszaszámlálás hosszát.
     *
     * @param args pontosan egy elem: a másodpercek száma, pozitív egész, legfeljebb {@link #MAX_TOTAL}
     * @return a visszaszámlálás hossza
     * @throws IllegalArgumentException ha az argumentum hiányzik, nem szám, nem pozitív vagy túl nagy; az
     *     üzenet a használati útmutatót is tartalmazza
     */
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
        if (seconds > MAX_TOTAL.getSeconds()) {
            throw usage("A másodperc legfeljebb " + MAX_TOTAL.getSeconds() + " lehet.");
        }
        return Duration.ofSeconds(seconds);
    }

    private static IllegalArgumentException usage(String reason) {
        return new IllegalArgumentException(reason + " Használat: Countdown <másodperc>");
    }

    /**
     * HH:mm:ss formátum. Az órák nincsenek 24-re korlátozva, 99 óra felett az óra mező háromjegyű (pl. 100:00:00).
     */
    public static String format(Duration duration) {
        return String.format(
                Locale.ROOT, "%02d:%02d:%02d", duration.toHours(), duration.toMinutesPart(), duration.toSecondsPart());
    }

    /** A {@code now} és {@code end} közti hátralévő idő, soha nem negatív. */
    static Duration remaining(Instant now, Instant end) {
        Duration left = Duration.between(now, end);
        return left.isNegative() ? Duration.ZERO : left;
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
     * Elindítja a visszaszámlálást: a végpontot a hívás pillanatában számolja ki, majd másodpercenként kiírja a
     * hátralévő időt, végül a "Done!" üzenetet. Megszakítás esetén visszaállítja a megszakítási jelzőt és kilép.
     *
     * @param total a visszaszámlálás hossza, legfeljebb {@link #MAX_TOTAL}
     * @param clock az idő forrása (tesztben cserélhető)
     * @return {@code true}, ha a visszaszámlálás lejárt; {@code false}, ha megszakították
     */
    static boolean run(Duration total, Clock clock, PrintStream out, Sleeper sleeper) {
        Instant end = clock.instant().plus(total);
        Duration left = total;
        while (!left.isZero()) {
            out.println(format(roundUpToSeconds(left)));
            try {
                sleeper.sleep(untilNextTick(left));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            left = remaining(clock.instant(), end);
        }
        out.println("Done!");
        return true;
    }
}
