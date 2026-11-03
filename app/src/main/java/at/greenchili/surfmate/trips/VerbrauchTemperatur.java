package at.greenchili.surfmate.trips;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Kurve „Verbrauch gegen Außentemperatur“ (03.11.2026, Konkurrenz-Liste Nr. 9). Rein, ohne Datenbank.
 *
 * <p>Je Fahrt zählt die Außentemperatur beim Fahrtbeginn ({@link TripRecord#extTempC}). Fahrten ohne sicher
 * bekannte Temperatur ({@link TripRecord#hatAussenTemperatur()}) fließen NICHT ein, sie werden nur gezählt.
 * Der Verbrauch je Topf ist gewichtet: Summe kWh / Summe km × 100, damit kurze Fahrten nicht überwiegen.
 * Keine Orte, keine Zeitpunkte in der Antwort.
 */
public final class VerbrauchTemperatur {

    /** Breite eines Temperatur-Topfs in °C. */
    static final int TOPF_GRAD = 5;
    /** Kürzere Fahrten sind zu ungenau (Akku-kWh kommt aus einer 1-%-Anzeige). */
    static final double MIN_KM = 2.0;
    /** Unplausible Werte (Messfehler, Rekuperation bergab) bleiben draußen. */
    static final double MIN_KWH_100 = 3.0;
    static final double MAX_KWH_100 = 60.0;

    private VerbrauchTemperatur() {}

    /** Untere Grenze des Topfs, z. B. −3 °C → −5, 7 °C → 5. */
    static int topf(int gradC) {
        return Math.floorDiv(gradC, TOPF_GRAD) * TOPF_GRAD;
    }

    /**
     * @param fahrten abgeschlossene Fahrten (beliebige Reihenfolge)
     * @return {@code {punkte:[{von,bis,fahrten,km,kwh,kwhPro100km}], fahrtenMitTemperatur, fahrtenOhneTemperatur,
     *         fahrtenZuKurz, topfGrad}}
     */
    public static JSONObject berechne(List<TripRecord> fahrten) {
        Map<Integer, double[]> toepfe = new TreeMap<>(); // von → {fahrten, km, kwh}
        int mit = 0, ohne = 0, kurz = 0;
        if (fahrten != null) {
            for (TripRecord t : fahrten) {
                if (t == null) continue;
                if (t.distanceKm < MIN_KM) { kurz++; continue; }
                double kwh = t.getResolvedEnergyKwh();
                if (!(kwh > 0) || Double.isInfinite(kwh)) { kurz++; continue; }
                double pro100 = kwh / t.distanceKm * 100.0;
                if (pro100 < MIN_KWH_100 || pro100 > MAX_KWH_100) { kurz++; continue; }
                if (!t.hatAussenTemperatur()) { ohne++; continue; }
                mit++;
                double[] s = toepfe.computeIfAbsent(topf(t.extTempC), k -> new double[3]);
                s[0] += 1; s[1] += t.distanceKm; s[2] += kwh;
            }
        }
        JSONObject out = new JSONObject();
        try {
            JSONArray punkte = new JSONArray();
            for (Map.Entry<Integer, double[]> e : toepfe.entrySet()) {
                double[] s = e.getValue();
                JSONObject p = new JSONObject();
                p.put("von", e.getKey());
                p.put("bis", e.getKey() + TOPF_GRAD);
                p.put("fahrten", (int) s[0]);
                p.put("km", runde(s[1], 1));
                p.put("kwh", runde(s[2], 2));
                p.put("kwhPro100km", runde(s[2] / s[1] * 100.0, 1));
                punkte.put(p);
            }
            out.put("punkte", punkte);
            out.put("fahrtenMitTemperatur", mit);
            out.put("fahrtenOhneTemperatur", ohne);
            out.put("fahrtenZuKurz", kurz);
            out.put("topfGrad", TOPF_GRAD);
        } catch (Exception ignored) {
        }
        return out;
    }

    static double runde(double v, int stellen) {
        double f = Math.pow(10, stellen);
        return Math.round(v * f) / f;
    }
}
