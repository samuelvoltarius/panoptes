package at.greenchili.surfmate.reichweite;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Plan aus dem Planer ({@code GET meine-route} / {@code auto-abholen}, Vertrag
 * API_FUER_SURFMATE.md §3) in einen {@link RangeWatch.Plan}. Rein.
 */
public final class PlanLeser {

    private PlanLeser() {}

    /** Standard-Reserve, wenn weder Plan noch Einstellung eine nennen (F1_KONZEPT §4). */
    public static final double RESERVE_STANDARD = 10.0;

    /**
     * @param plan               das Objekt unter {@code "plan"} oder null
     * @param reserveUeberschrieben Einstellung „Reserve überschreiben“ (System) oder NaN
     * @return Plan oder null, wenn unbrauchbar (keine Linie, kein Start-Ladestand)
     */
    public static RangeWatch.Plan lies(JSONObject plan, double reserveUeberschrieben) {
        if (plan == null) return null;
        JSONArray geo = plan.optJSONArray("geometrie");
        if (geo == null || geo.length() < 2) return null;
        List<double[]> linie = new ArrayList<>();
        for (int i = 0; i < geo.length(); i++) {
            JSONArray p = geo.optJSONArray(i);
            if (p == null || p.length() < 2) continue;
            double lat = p.optDouble(0, Double.NaN), lon = p.optDouble(1, Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lon)) continue;
            linie.add(new double[] {lat, lon});
        }
        if (linie.size() < 2) return null;
        double socStart = plan.optDouble("soc_start", Double.NaN);
        double socAnkunft = plan.optDouble("soc_ankunft", Double.NaN);
        if (Double.isNaN(socStart) || Double.isNaN(socAnkunft)) return null;
        List<RangeWatch.Stopp> stopps = new ArrayList<>();
        JSONArray st = plan.optJSONArray("stopps");
        for (int i = 0; st != null && i < st.length(); i++) {
            JSONObject s = st.optJSONObject(i);
            if (s == null) continue;
            double km = s.optDouble("km", Double.NaN), an = s.optDouble("soc_an", Double.NaN), ab = s.optDouble("soc_ab", Double.NaN);
            if (Double.isNaN(km) || Double.isNaN(an) || Double.isNaN(ab)) continue;
            stopps.add(new RangeWatch.Stopp(s.optString("name", "Ladestopp"), km, an, ab,
                    s.optDouble("lat", Double.NaN), s.optDouble("lon", Double.NaN), s.optDouble("kw", 0)));
        }
        double reserve = !Double.isNaN(reserveUeberschrieben) && reserveUeberschrieben > 0 ? reserveUeberschrieben
                : plan.optDouble("soc_ziel_min", RESERVE_STANDARD);
        JSONObject ziel = plan.optJSONObject("ziel");
        String zielName = ziel != null ? ziel.optString("name", "Ziel") : "Ziel";
        return new RangeWatch.Plan(linie, stopps, socStart, socAnkunft, reserve, zielName);
    }

    /** Kennung eines Plans, um Wechsel zu erkennen (erstellt + Titel). */
    public static String kennung(JSONObject plan) {
        if (plan == null) return null;
        return plan.optString("erstellt", "") + "|" + plan.optString("titel", "");
    }
}
