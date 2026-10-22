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
 * Beruhigungszeit nach dem Parken, Ladephasen des DC-DC-Wandlers (≥ {@link Profile#chargingAboveV})
 * gelten als "gesund", und "zu schwach" erst nach zwei Auswertungen in Folge.
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
    private int lowStreak;
    private State state = State.UNKNOWN;
    private double lastMedian = Double.NaN;
    private boolean charging;

    public Battery12vEvaluator(Profile profile) {
        this.profile = profile == null ? Profile.DEFAULT : profile;
    }

    /** Neues Parken bzw. Fahrt: alles vergessen. */
    public synchronized void reset() {
        samples.clear();
        lastSampleAt = 0L;
        lowStreak = 0;
        state = State.UNKNOWN;
        lastMedian = Double.NaN;
        charging = false;
    }

    /**
     * Messwert anbieten. Nur neue Lesungen ({@code readAtMs} jünger als die letzte) und nur nach der
     * Beruhigungszeit seit {@code parkedSinceMs} zählen.
     */
    public synchronized void offer(double volts, long readAtMs, long parkedSinceMs) {
        if (Double.isNaN(volts) || volts <= 0.0 || volts >= 20.0 || readAtMs <= 0L) return;
        if (readAtMs <= lastSampleAt) return;
        if (parkedSinceMs <= 0L || readAtMs - parkedSinceMs < profile.settleMs) return;
        lastSampleAt = readAtMs;
        samples.addLast(new Sample(volts, readAtMs));
    }

    /**
     * Auswerten. {@code belowV <= 0} schaltet die 12-V-Regel ab (immer OK).
     * Wird nur aufgerufen, wenn neue Werte da sein können; ein LOW bleibt bestehen, bis der Median
     * {@code resumeV} erreicht.
     */
    public synchronized State evaluate(double belowV, double resumeV, long nowMs) {
        if (belowV <= 0.0) {
            state = State.OK;
            lowStreak = 0;
            return state;
        }
        Iterator<Sample> it = samples.iterator();
        while (it.hasNext()) {
            if (nowMs - it.next().at > profile.windowMs) it.remove();
        }
        if (samples.size() < profile.minSamples
                || samples.peekLast().at - samples.peekFirst().at < profile.minSpanMs) {
            // Zu wenig frische Daten: LOW nicht von selbst aufheben, sonst unbekannt.
            if (state != State.LOW) state = State.UNKNOWN;
            lastMedian = Double.NaN;
            return state;
        }
        List<Double> vs = new ArrayList<>(samples.size());
        for (Sample s : samples) vs.add(s.v);
        Collections.sort(vs);
        int n = vs.size();
        double median = n % 2 == 1 ? vs.get(n / 2) : (vs.get(n / 2 - 1) + vs.get(n / 2)) / 2.0;
        lastMedian = median;
        charging = median >= profile.chargingAboveV;
        if (charging) {
            lowStreak = 0;
            state = State.OK;
            return state;
        }
        if (state == State.LOW) {
            if (median >= resumeV) {
                state = State.OK;
                lowStreak = 0;
            }
            return state;
        }
        if (median < belowV) {
            lowStreak++;
            state = lowStreak >= 2 ? State.LOW : State.OK;
        } else {
            lowStreak = 0;
            state = State.OK;
        }
        return state;
    }

    public synchronized double lastMedian() { return lastMedian; }
    public synchronized int sampleCount() { return samples.size(); }
    public synchronized boolean isCharging() { return charging; }
    public synchronized State state() { return state; }
}
