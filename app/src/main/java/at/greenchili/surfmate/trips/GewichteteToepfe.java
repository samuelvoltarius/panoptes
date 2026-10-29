package at.greenchili.surfmate.trips;

import java.util.HashMap;
import java.util.Map;

/**
 * 0.1.28 P7: die 27 Verbrauchstöpfe (Tempo × Temperatur × Fahrweise), aber jüngere Fahrten zählen mehr –
 * Halbwertszeit {@link #HALBWERTSZEIT_TAGE} Tage.
 *
 * <p>Rückprüfung an Alfreds 453 Fahrten (KERN_C_0128_STAND.md, Verbrauch der jeweils nächsten 100 km, jede
 * Vorhersage nur aus den Fahrten davor, 271 Fenster): Fehler 10,8 % → 10,2 %, Spanne P25–P75 0,197 → 0,179,
 * Trefferquote 54,0 → 56,7 %; in beiden Hälften besser. Geprüft und verworfen: Kurzfahrten abwerten, km-Gewichtung,
 * Temperatur-Gerade, Höhenmeter (kein Gewinn bzw. nicht stabil).
 *
 * <p>Rückfallkette wie {@link RangeEstimator#aufloesen}, aber mit Gewichtssummen statt Fahrtzahl (keine
 * Ganzzahl-Kürzung): genauer Topf (Σw ≥ 10) → Nachbarn ×0,5/×0,3 → gleiches Tempo → alle; sind alle Gewichte
 * verblasst (Auto lange gestanden), zählt noch der gewichtete Mittelwert, solange ≥ 10 Fahrten da sind.
 * Die Gewichte altern beim Lesen ({@code jetzt}), der Mittelwert bleibt dabei gleich. Rein, im Speicher.
 */
final class GewichteteToepfe {

    static final double HALBWERTSZEIT_TAGE = 21.0;
    static final double MIN = RangeEstimator.MIN_BUCKET_SAMPLES;
    private static final double TAG_MS = 86_400_000.0;
    private static final String[] TEMPO = {"city", "suburban", "highway"};
    private static final String[] TEMP = {"cold", "mild", "hot"};
    private static final String[] STIL = {"low", "mid", "high"};

    /** Summen eines Topfs, bezogen auf {@link #stand}. */
    static final class Topf {
        int n;
        double w, wr, wr2;
    }

    private final Map<String, Topf> toepfe = new HashMap<>();
    /** Zeitpunkt, auf den sich alle Gewichte beziehen (ms). */
    private long stand = Long.MIN_VALUE;

    static double faktor(long vonMs, long bisMs) {
        if (bisMs <= vonMs) return 1.0;
        return Math.pow(0.5, (bisMs - vonMs) / (HALBWERTSZEIT_TAGE * TAG_MS));
    }

    /** Alle Gewichte auf {@code jetzt} altern lassen (nur vorwärts). */
    void altern(long jetzt) {
        if (stand == Long.MIN_VALUE) {
            stand = jetzt;
            return;
        }
        if (jetzt <= stand) return;
        double f = faktor(stand, jetzt);
        for (Topf t : toepfe.values()) {
            t.w *= f;
            t.wr *= f;
            t.wr2 *= f;
        }
        stand = jetzt;
    }

    /** Fahrt mit Verbrauch {@code rate} (kWh/km), gestartet {@code startMs}, Gewicht 1 zum Startzeitpunkt. */
    void dazu(String key, double rate, long startMs) {
        altern(startMs);
        double w = startMs < stand ? faktor(startMs, stand) : 1.0;
        Topf t = toepfe.computeIfAbsent(key, k -> new Topf());
        t.n++;
        t.w += w;
        t.wr += w * rate;
        t.wr2 += w * rate * rate;
    }

    long stand() {
        return stand;
    }

    /** Rückfallkette mit Gewichten, gealtert auf {@code jetzt}; null, wenn weniger als 10 Fahrten da sind. */
    RangeEstimator.BucketResult aufloesen(double speedKmh, int tempC, int dnaScore, long jetzt) {
        double f = stand == Long.MIN_VALUE ? 1.0 : faktor(stand, jetzt);
        String key = RangeEstimator.computeBucketKey(speedKmh, tempC, dnaScore);
        String[] k = key.split("_");
        Topf ex = toepfe.get(key);
        if (ex != null && ex.n > 0 && ex.w * f >= MIN) return ergebnis(key, ex.w, ex.wr, ex.wr2, f, true);

        double w = 0, wr = 0, wr2 = 0;
        if (ex != null && ex.n > 0) {
            w += ex.w;
            wr += ex.wr;
            wr2 += ex.wr2;
        }
        for (String s : STIL) {
            if (s.equals(k[2])) continue;
            Topf b = toepfe.get(k[0] + "_" + k[1] + "_" + s);
            if (b != null && b.n > 0) {
                w += 0.5 * b.w;
                wr += 0.5 * b.wr;
                wr2 += 0.5 * b.wr2;
            }
        }
        for (String t : TEMP) {
            if (t.equals(k[1])) continue;
            Topf b = toepfe.get(k[0] + "_" + t + "_" + k[2]);
            if (b != null && b.n > 0) {
                w += 0.3 * b.w;
                wr += 0.3 * b.wr;
                wr2 += 0.3 * b.wr2;
            }
        }
        if (w * f >= MIN) return ergebnis(key + "(blend)", w, wr, wr2, f, false);

        w = wr = wr2 = 0;
        for (String t : TEMP) {
            for (String s : STIL) {
                Topf b = toepfe.get(k[0] + "_" + t + "_" + s);
                if (b != null && b.n > 0) {
                    w += b.w;
                    wr += b.wr;
                    wr2 += b.wr2;
                }
            }
        }
        if (w * f >= MIN) return ergebnis(k[0] + "(profile)", w, wr, wr2, f, false);

        int n = 0;
        w = wr = wr2 = 0;
        for (Topf b : toepfe.values()) {
            n += b.n;
            w += b.w;
            wr += b.wr;
            wr2 += b.wr2;
        }
        if (n >= MIN && w > 0) return ergebnis("overall", w, wr, wr2, f, false);
        return null;
    }

    private static RangeEstimator.BucketResult ergebnis(String key, double w, double wr, double wr2, double f,
                                                        boolean exakt) {
        double mean = wr / w;
        double var = Math.max(0, wr2 / w - mean * mean);
        int zahl = (int) Math.max(1, Math.round(w * f));
        return new RangeEstimator.BucketResult(key, mean, Math.sqrt(var), zahl, exakt);
    }

    /** Alle Fahrten (beliebig sortiert) in Zeitfolge einlernen, wie {@link RangeEstimator#verbrauch}. */
    static GewichteteToepfe aus(java.util.List<TripRecord> fahrten, double nennKwh) {
        java.util.List<TripRecord> s = new java.util.ArrayList<>(fahrten);
        s.sort((a, b) -> Long.compare(a.startTime, b.startTime));
        GewichteteToepfe g = new GewichteteToepfe();
        for (TripRecord t : s) {
            double r = RangeEstimator.verbrauch(t, nennKwh);
            if (Double.isNaN(r)) continue;
            g.dazu(RangeEstimator.computeBucketKey(t.avgSpeedKmh, t.extTempC, t.getOverallScore()), r, t.startTime);
        }
        return g;
    }
}
