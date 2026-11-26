package hu.demo.countdown;

import java.io.PrintStream;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * Egyszerű visszaszámláló: a hátralévő időt a {@code System.nanoTime()} alapján számolja, nem a ciklus
 * lépéseiből, és másodpercenként kiírja. A {@code nanoTime} monoton, ezért a rendszeróra átállítása (NTP, kézi
 * módosítás) nem tolja el a hátralévő időt.
 */
public final class Countdown {

    /** A megadható legnagyobb hossz (ésszerű felső korlát; nanoszekundumban is belül van a {@code long} tartományon). */
    static final Duration MAX_TOTAL = Duration.ofDays(365);

    private static final Duration ONE_SECOND = Duration.ofSeconds(1);

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
     * legfeljebb {@link #MAX_TOTAL}. Hibás bemenet esetén rövid hibaüzenetet ír a hibakimenetre,
     * és 1-es kilépési kóddal tér vissza; megszakítás esetén a kilépési kód {@link #EXIT_INTERRUPTED}.
     */
    public static void main(String[] args) {
        System.exit(execute(args, System::nanoTime, System.out, System.err, Thread::sleep));
    }

    /**
     * A {@code main} tesztelhető magja. Csak a bemenet feldolgozásakor dobott hibát kezeli (1-es kilépési kód),
     * a visszaszámlálás közbeni hibák nem nyelődnek el.
     *
     * @return a folyamat kilépési kódja: 0 siker, 1 hibás bemenet, {@link #EXIT_INTERRUPTED} megszakítás
     */
    static int execute(String[] args, LongSupplier nanoTime, PrintStream out, PrintStream err, Sleeper sleeper) {
        Duration total;
        try {
            total = parseSeconds(args);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return 1;
        }
        return run(total, nanoTime, out, sleeper) ? 0 : EXIT_INTERRUPTED;
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
            throw usage("A másodpercnek egész számnak kell lennie (pl. 30).");
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
    static String format(Duration duration) {
        return String.format(
                Locale.ROOT, "%02d:%02d:%02d", duration.toHours(), duration.toMinutesPart(), duration.toSecondsPart());
    }

    /** A {@code nowNanos} és {@code endNanos} ({@code System.nanoTime()} értékek) közti hátralévő idő, soha nem negatív. */
    static Duration remaining(long nowNanos, long endNanos) {
        return Duration.ofNanos(Math.max(0, endNanos - nowNanos));
    }

    /** Felfelé kerekít egész másodpercre (pl. 1,5 mp -> 2 mp). */
    static Duration roundUpToSeconds(Duration duration) {
        Duration truncated = duration.truncatedTo(ChronoUnit.SECONDS);
        return truncated.equals(duration) ? duration : truncated.plusSeconds(1);
    }

    /**
     * Elindítja a visszaszámlálást: a végpontot a hívás pillanatában számolja ki, majd másodpercenként kiírja a
     * hátralévő időt, végül a "Done!" üzenetet. Megszakítás esetén visszaállítja a megszakítási jelzőt és kilép.
     *
     * @param total a visszaszámlálás hossza, legfeljebb {@link #MAX_TOTAL}
     * @param nanoTime az idő forrása, éles futásnál {@code System::nanoTime} (tesztben cserélhető)
     * @return {@code true}, ha a visszaszámlálás lejárt; {@code false}, ha megszakították
     */
    static boolean run(Duration total, LongSupplier nanoTime, PrintStream out, Sleeper sleeper) {
        long endNanos = nanoTime.getAsLong() + total.toNanos();
        Duration left = total;
        while (!left.isZero()) {
            out.println(format(roundUpToSeconds(left)));
            try {
                sleeper.sleep(left.compareTo(ONE_SECOND) < 0 ? left : ONE_SECOND);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            left = remaining(nanoTime.getAsLong(), endNanos);
        }
        out.println("Done!");
        return true;
    }
}
