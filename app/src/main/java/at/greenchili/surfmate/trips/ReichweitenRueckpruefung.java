package at.greenchili.surfmate.trips;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.1.26.1 (Koordination 28.10., REICHWEITE_ANALYSE.md): ehrliche Spanne für „Reichweite (geschätzt)“.
 *
 * <p>Die alte Spanne war Mittel ± σ·k der EINZELNEN Fahrten (187–409 km an Alfreds Auto). Die Reichweite mittelt aber
 * über viele Kilometer. Darum rechnet das Auto aus den eigenen Fahrten nach, wie gut die Töpfe den Verbrauch der
 * jeweils nächsten ≥ 100 km vorhergesagt hätten – jede Vorhersage nur aus den Fahrten davor – und nimmt als Spanne
 * P25–P75 des Verhältnisses echt/vorhergesagt (an Alfreds Auto 0,91–1,10 → bei 260 km etwa 236–287 km).
 * Unter {@link #MIN_FENSTER} Fenstern: kein Ergebnis („lernt noch“). Rein, ohne Datenbank, alles im Auto.
 */
public final class ReichweitenRueckpruefung {

    public static final int MIN_FENSTER = 10;
    static final double FENSTER_KM = 100.0;
    /** Erst ab so vielen früheren Fahrten wird ein Fenster gewertet (sonst sind die Töpfe leer). */
    static final int VORLAUF = 20;

    private ReichweitenRueckpruefung() {}

    /** Verhältnis echter / vorhergesagter Verbrauch über 100 km: P25, P75 und Zahl der Fenster. */
    public static final class Ergebnis {
        public final double p25, p75;
        public final int fenster;

        Ergebnis(double p25, double p75, int fenster) {
            this.p25 = p25;
            this.p75 = p75;
            this.fenster = fenster;
        }
    }

    /** Töpfe im Speicher, gleich befüllt wie {@code consumption_buckets} (RangeEstimator.onTripCompleted). */
    static final class SpeicherToepfe implements RangeEstimator.Toepfe {
        final Map<String, ConsumptionBucket> toepfe = new HashMap<>();

        void dazu(String key, double rate) {
            ConsumptionBucket b = toepfe.get(key);
            if (b == null) {
                b = new ConsumptionBucket();
                b.bucketKey = key;
                toepfe.put(key, b);
            }
            b.sampleCount++;
            b.sumKwhPerKm += rate;
            b.sumSquaredKwhPerKm += rate * rate;
        }

        public ConsumptionBucket topf(String key) { return toepfe.get(key); }

        public ConsumptionBucket gesamt() {
            ConsumptionBucket g = new ConsumptionBucket();
            g.bucketKey = "overall";
            for (ConsumptionBucket b : toepfe.values()) {
                g.sampleCount += b.sampleCount;
                g.sumKwhPerKm += b.sumKwhPerKm;
                g.sumSquaredKwhPerKm += b.sumSquaredKwhPerKm;
            }
            return g;
        }
    }

    /** Kurzfahrt (kalter Start, grobe Auflösung): zählt nicht als Fensterbeginn. */
    static boolean kurz(TripRecord t) {
        return t.distanceKm < 3 || t.durationSeconds < 300;
    }

    /**
     * @param fahrten EV-Fahrten (Mischbetrieb schon aussortiert), beliebig sortiert
     * @param nennKwh Nennkapazität für Fahrten ohne kWh-Messung (wie RangeEstimator)
     * @return null, solange weniger als {@link #MIN_FENSTER} Fenster vorliegen
     */
    public static Ergebnis rechne(List<TripRecord> fahrten, double nennKwh) {
        List<TripRecord> g = new ArrayList<>();
        List<Double> raten = new ArrayList<>();
        List<TripRecord> sortiert = new ArrayList<>(fahrten);
        Collections.sort(sortiert, (a, b) -> Long.compare(a.startTime, b.startTime));
        for (TripRecord t : sortiert) {
            double r = RangeEstimator.verbrauch(t, nennKwh);
            if (Double.isNaN(r)) continue;
            g.add(t);
            raten.add(r);
        }
        List<Double> q = new ArrayList<>();
        SpeicherToepfe toepfe = new SpeicherToepfe();
        for (int i = 0; i < g.size(); i++) {
            TripRecord x = g.get(i);
            if (i >= VORLAUF && !kurz(x)) {
                double km = 0, echt = 0, vorher = 0;
                for (int j = i; j < g.size() && km < FENSTER_KM; j++) {
                    TripRecord y = g.get(j);
                    RangeEstimator.BucketResult p = RangeEstimator.aufloesen(toepfe,
                            y.avgSpeedKmh, y.extTempC, y.getOverallScore());
                    if (p == null || p.mean <= 0) { km = -1; break; }
                    km += y.distanceKm;
                    echt += raten.get(j) * y.distanceKm;
                    vorher += p.mean * y.distanceKm;
                }
                if (km >= FENSTER_KM && vorher > 0) q.add(echt / vorher);
            }
            toepfe.dazu(RangeEstimator.computeBucketKey(x.avgSpeedKmh, x.extTempC, x.getOverallScore()), raten.get(i));
        }
        if (q.size() < MIN_FENSTER) return null;
        Collections.sort(q);
        return new Ergebnis(q.get(q.size() / 4), q.get(3 * q.size() / 4), q.size());
    }
}
