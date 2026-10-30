package at.greenchili.surfmate.surveillance;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * 12-V-Auswertung für den Wächter-Stopp (guardStop.v12Below / v12Resume).
 *
 * <p>Ein einzelner Messwert taugt nicht: Kurz nach dem Abstellen liegt die Spannung durch
 * Oberflächenladung bzw. den nachlaufenden DC-DC-Wandler zu hoch, und Lastspitzen (Kamera,
 * Funkmodem) drücken einzelne Werte nach unten. Deshalb: Median über ein Zeitfenster, eine
 * Beruhigungszeit nach dem Parken, und Ladephasen des DC-DC-Wandlers (≥ {@link Profile#chargingAboveV})
 * gelten als "gesund".
 *
 * <p><b>Sägezahn (0.1.31, HA-Verlauf 20.–30.10.2026):</b> Im Stand fällt die Spannung über Stunden von
 * ~13,2 auf 12,6–12,9 V, dann lädt das Auto von selbst auf 13,7 V nach, ohne Fahrt. Der Tiefpunkt ist
 * normal. {@link State#LOW} gilt deshalb nur in einem von drei Fällen:
 * <ul>
 *   <li><b>Zyklus:</b> {@link #ZYKLUS_MS} lang kein Nachladen (≥ {@link #NACHLADEN_V}) und seit
 *       {@link #TIEF_MS} unter der Schwelle. Pausen bis 16 h kommen vor, enden aber bei 12,8–12,9 V, darum
 *       reicht die Zeit allein nicht.</li>
 *   <li><b>Anteil:</b> mindestens {@link #ANTEIL_TIEF} der letzten {@link #ZYKLUS_MS} unter der Schwelle.
 *       Das fängt eine Batterie, die nach jedem Nachladen gleich wieder einbricht.</li>
 *   <li><b>Notfall:</b> {@link #NOTFALL_MS} lang unter {@link #NOTFALL_V} (oder der tieferen Schwelle). So
 *       tief kam der Sägezahn nie (Minimum 12,6 V), hier wird nicht auf den Zyklus gewartet.</li>
 * </ul>
 * Wieder-Ein: {@link #ERHOLT_WERTE} Werte in Folge über der Wieder-Ein-Schwelle, nicht der Median des
 * Fensters, der noch minutenlang an den alten Werten hängt (30.10.: 13,7 V, Median 12,7 V).
 *
 * <p><b>DiLink 3 (Surf, Atto 3, Dolphin …)</b>: Es gibt nur die Spannung aus
 * {@code BodyworkDevice.getBatteryPowerValue} (0,1 V Auflösung; {@code batt_12v_level} ist dort
 * 65535 = nicht verfügbar, gemessen am Surf 21.10.2026), geparkt alle 90 s gepollt. Dafür das
 * strengere {@link Profile#DILINK3}: 20 min Beruhigung, mindestens 3 Werte über ≥ 3 min.
 * Hinweis: Die Surf zeigt in Ruhe 13,1 V – das passt zu einer LFP-Starterbatterie, bei der die
 * Bleisäure-Schwellen 12,2/12,5 V erst kurz vor leer greifen. Die Schwellen bleiben Einstellung.
 */
public final class Battery12vEvaluator {

    public enum State { UNKNOWN, OK, LOW }

    /** Ab hier gilt ein Wert als Nachladen (Ruhe bleibt darunter: 13,1–13,2 V). */
    static final double NACHLADEN_V = 13.3;
    /** Zwei Werte in Folge, ein einzelner Ausreißer ist kein Nachladen. */
    static final int NACHLADEN_WERTE = 2;
    static final long ZYKLUS_MS = 8 * 60 * 60_000L;
    static final long TIEF_MS = 60 * 60_000L;
    static final double ANTEIL_TIEF = 2.0 / 3.0;
    static final double NOTFALL_V = 12.4;
    static final long NOTFALL_MS = 30 * 60_000L;
    static final int ERHOLT_WERTE = 3;
    /** Eine Messlücke zählt höchstens so lange: Darin könnte ein Nachladen verborgen sein. */
    static final long LUECKE_MS = 45 * 60_000L;
    /** Verlauf für die Oberkante: Auch lange Pausen sollen den oberen Teil des Sägezahns enthalten. */
    static final long VERLAUF_MS = 18 * 60 * 60_000L;
    static final long OBEN_MIN_MS = 6 * 60 * 60_000L;
    static final long OBEN_OHNE_NACHLADEN_MS = 12 * 60 * 60_000L;
    static final double OBEN_ANTEIL = 0.75;

    public static final class Profile {
        final long settleMs;
        final long windowMs;
        final int minSamples;
        final long minSpanMs;
        final double chargingAboveV;

        Profile(long settleMs, long windowMs, int minSamples, long minSpanMs, double chargingAboveV) {
            this.settleMs = settleMs;
            this.windowMs = windowMs;
            this.minSamples = minSamples;
            this.minSpanMs = minSpanMs;
            this.chargingAboveV = chargingAboveV;
        }

        public static final Profile DILINK3 = new Profile(20 * 60_000L, 15 * 60_000L, 3, 3 * 60_000L, 13.6);
        public static final Profile DEFAULT = new Profile(10 * 60_000L, 10 * 60_000L, 2, 60_000L, 13.6);
    }

    private static final class Sample {
        final double v;
        final long at;
        Sample(double v, long at) { this.v = v; this.at = at; }
    }

    private final Profile profile;
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private long lastSampleAt;
    private State state = State.UNKNOWN;
    private double lastMedian = Double.NaN;
    private boolean charging;
    /** Beobachtete Zeit ohne Nachladen. */
    private long zyklusMs;
    private int hochInFolge;
    private int erholtInFolge;
    private double erholtAb = Double.NaN;
    private boolean nachladenGesehen;
    /** Anteil-Regel: nur Werte ab hier (nach Wieder-Ein zählt die Zeit davor nicht mehr). */
    private long anteilAb;

    /** Was einen Daemon-Neustart überlebt. */
    public static final class Stand {
        public final long zyklusMs, letzteMessungMs;
        public final boolean low;

        public Stand(long zyklusMs, long letzteMessungMs, boolean low) {
            this.zyklusMs = zyklusMs;
            this.letzteMessungMs = letzteMessungMs;
            this.low = low;
        }
    }

    public Battery12vEvaluator(Profile profile) {
        this.profile = profile == null ? Profile.DEFAULT : profile;
    }

    /** Neues Parken bzw. Fahrt: alles vergessen. */
    public synchronized void reset() {
        samples.clear();
        lastSampleAt = 0L;
        state = State.UNKNOWN;
        lastMedian = Double.NaN;
        charging = false;
        zyklusMs = 0L;
        hochInFolge = 0;
        erholtInFolge = 0;
        nachladenGesehen = false;
        anteilAb = 0L;
    }

    public synchronized Stand stand() {
        return new Stand(zyklusMs, lastSampleAt, state == State.LOW);
    }

    /** Nach Daemon-Start. Bei einer Lücke über {@link #LUECKE_MS} kann ein Nachladen verpasst sein: verwerfen. */
    public synchronized void wiederherstellen(Stand s, long nowMs) {
        if (s == null || s.letzteMessungMs <= 0L || nowMs - s.letzteMessungMs > LUECKE_MS) return;
        zyklusMs = s.zyklusMs;
        lastSampleAt = s.letzteMessungMs;
        if (s.low) state = State.LOW;
    }

    /**
     * Messwert anbieten. Nur neue Lesungen ({@code readAtMs} jünger als die letzte) und nur nach der
     * Beruhigungszeit seit {@code parkedSinceMs} zählen.
     */
    public synchronized void offer(double volts, long readAtMs, long parkedSinceMs) {
        if (Double.isNaN(volts) || volts <= 0.0 || volts >= 20.0 || readAtMs <= 0L) return;
        if (readAtMs <= lastSampleAt) return;
        if (parkedSinceMs <= 0L || readAtMs - parkedSinceMs < profile.settleMs) return;
        if (lastSampleAt > 0L) zyklusMs += Math.min(readAtMs - lastSampleAt, LUECKE_MS);
        hochInFolge = volts >= NACHLADEN_V ? hochInFolge + 1 : 0;
        if (hochInFolge >= NACHLADEN_WERTE) {
            zyklusMs = 0L;
            nachladenGesehen = true;
        }
        erholtInFolge = !Double.isNaN(erholtAb) && volts >= erholtAb ? erholtInFolge + 1 : 0;
        lastSampleAt = readAtMs;
        samples.addLast(new Sample(volts, readAtMs));
    }

    /**
     * Auswerten. {@code belowV <= 0} schaltet die 12-V-Regel ab (immer OK).
     * Wird nur aufgerufen, wenn neue Werte da sein können; ein LOW bleibt bestehen, bis
     * {@link #ERHOLT_WERTE} Werte in Folge {@code resumeV} (höchstens {@link #NACHLADEN_V}) erreichen.
     */
    public synchronized State evaluate(double belowV, double resumeV, long nowMs) {
        if (belowV <= 0.0) {
            state = State.OK;
            return state;
        }
        erholtAb = Math.min(resumeV, NACHLADEN_V);
        while (!samples.isEmpty() && nowMs - samples.peekFirst().at > VERLAUF_MS) samples.removeFirst();
        List<Double> frisch = werteSeit(nowMs - profile.windowMs);
        if (frisch.size() < profile.minSamples
                || spanneSeit(nowMs - profile.windowMs) < profile.minSpanMs) {
            // Zu wenig frische Daten: LOW nicht von selbst aufheben, sonst unbekannt.
            if (state != State.LOW) state = State.UNKNOWN;
            lastMedian = Double.NaN;
            return state;
        }
        lastMedian = median(frisch);
        charging = lastMedian >= profile.chargingAboveV;
        if (state == State.LOW) {
            if (charging || erholtInFolge >= ERHOLT_WERTE) {
                state = State.OK;
                // Neu beginnen, sonst stoppt der alte Tiefstand gleich wieder; der Notfall gilt weiter.
                zyklusMs = 0L;
                anteilAb = nowMs;
            }
            return state;
        }
        state = !charging && (zyklusTief(belowV, nowMs) || anteilTief(belowV, nowMs) || notfall(belowV, nowMs))
                ? State.LOW : State.OK;
        return state;
    }

    private boolean zyklusTief(double belowV, long nowMs) {
        return zyklusMs >= ZYKLUS_MS && spanneSeit(nowMs - TIEF_MS) >= TIEF_MS * 3 / 4
                && median(werteSeit(nowMs - TIEF_MS)) < belowV;
    }

    private boolean anteilTief(double belowV, long nowMs) {
        long ab = Math.max(nowMs - ZYKLUS_MS, anteilAb);
        if (nowMs - ab < ZYKLUS_MS || spanneSeit(ab) < ZYKLUS_MS * 9 / 10) return false;
        List<Double> werte = werteSeit(ab);
        int tief = 0;
        for (double v : werte) if (v < belowV) tief++;
        return tief >= ANTEIL_TIEF * werte.size();
    }

    private boolean notfall(double belowV, long nowMs) {
        return spanneSeit(nowMs - NOTFALL_MS) >= NOTFALL_MS * 5 / 6
                && median(werteSeit(nowMs - NOTFALL_MS)) < Math.min(belowV, NOTFALL_V);
    }

    private List<Double> werteSeit(long abMs) {
        List<Double> vs = new ArrayList<>();
        for (Sample s : samples) if (s.at >= abMs) vs.add(s.v);
        return vs;
    }

    private long spanneSeit(long abMs) {
        for (Sample s : samples) if (s.at >= abMs) return samples.peekLast().at - s.at;
        return 0L;
    }

    private static double median(List<Double> vs) {
        Collections.sort(vs);
        int n = vs.size();
        return n % 2 == 1 ? vs.get(n / 2) : (vs.get(n / 2 - 1) + vs.get(n / 2)) / 2.0;
    }

    /**
     * Oberkante der Ruhespannung: oberes Viertel der Werte ohne Nachladen, der Tiefpunkt vor dem Nachladen zählt
     * nicht. NaN, solange der Verlauf den oberen Teil des Sägezahns nicht sicher enthält (mindestens
     * {@link #OBEN_MIN_MS} mit Nachladen, sonst {@link #OBEN_OHNE_NACHLADEN_MS}).
     */
    public synchronized double ruheOben() {
        if (samples.isEmpty()) return Double.NaN;
        long spanne = samples.peekLast().at - samples.peekFirst().at;
        if (spanne < (nachladenGesehen ? OBEN_MIN_MS : OBEN_OHNE_NACHLADEN_MS)) return Double.NaN;
        List<Double> vs = new ArrayList<>(samples.size());
        for (Sample s : samples) if (s.v < NACHLADEN_V) vs.add(s.v);
        if (vs.isEmpty()) return Double.NaN;
        Collections.sort(vs);
        return vs.get((int) (OBEN_ANTEIL * (vs.size() - 1)));
    }

    public synchronized double lastMedian() { return lastMedian; }
    public synchronized int sampleCount() { return samples.size(); }
    public synchronized boolean isCharging() { return charging; }
    public synchronized State state() { return state; }
}
