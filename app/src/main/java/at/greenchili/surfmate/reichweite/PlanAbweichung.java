package at.greenchili.surfmate.reichweite;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 0.1.16: Auto-Seite der Neuplanung unterwegs (Planer-Vertrag §17, {@code POST plan-abweichung}, zustandslos).
 *
 * <p>Nur mit Einwilligung {@code planner.abweichung} (Standard aus, zusätzlich zur Unterwegs-Hilfe): höchstens alle
 * {@link #ABSTAND_MS} oder nach {@link #SOC_SCHRITT_PP} Prozentpunkten Akku geht die angenommene Route mit Position
 * (auf ~100 m gerundet) und Akkustand an den Planer. Der Planer prüft, ob das Auto noch im Plan liegt, und speichert
 * nichts. Sagt er {@code neu_planen}, plant der Dienst mit seiner {@code neu_plan_anfrage} neu ({@code POST plan})
 * und zeigt den Vorschlag wie die lokale Neuplanung an – nichts geht automatisch ins Navi (Regel A).
 * Rein, ohne Netz.
 */
public final class PlanAbweichung {

    public static final long ABSTAND_MS = 10 * 60_000L;
    public static final double SOC_SCHRITT_PP = 5.0;

    private long letzte = Long.MIN_VALUE / 2;
    private double socLetzte = Double.NaN;

    /** Ob jetzt gefragt werden soll: erste Frage, ≥ 10 min seit der letzten oder ≥ 5 pp weniger Akku. */
    public boolean faellig(long now, double soc) {
        if (now - letzte >= ABSTAND_MS) return true;
        return !Double.isNaN(socLetzte) && !Double.isNaN(soc) && socLetzte - soc >= SOC_SCHRITT_PP;
    }

    /** Versuch merken (auch bei Netzfehler: nicht öfter fragen). */
    public void gefragt(long now, double soc) {
        letzte = now;
        socLetzte = soc;
    }

    public void zuruecksetzen() {
        letzte = Long.MIN_VALUE / 2;
        socLetzte = Double.NaN;
    }

    /**
     * Body für {@code POST plan-abweichung}: genau die Planfelder aus auto-abholen (§3), Position gerundet,
     * Akku 0–100, Reserve. null, wenn der Plan keine Geometrie hat.
     */
    public static JSONObject anfrage(JSONObject planRoh, double lat, double lon, double soc, double reserve) {
        if (planRoh == null || Double.isNaN(lat) || Double.isNaN(lon) || Double.isNaN(soc)) return null;
        JSONArray geo = planRoh.optJSONArray("geometrie");
        if (geo == null || geo.length() < 2) return null;
        try {
            JSONObject plan = new JSONObject();
            for (String k : new String[]{"geometrie", "soc_start", "soc_ankunft", "ziel", "stopps", "zwischenziele"}) {
                if (planRoh.has(k)) plan.put(k, planRoh.get(k));
            }
            JSONObject b = new JSONObject();
            b.put("plan", plan);
            b.put("position", new JSONArray().put(Math.round(lat * 1000) / 1000.0).put(Math.round(lon * 1000) / 1000.0));
            b.put("soc", Math.max(0, Math.min(100, Math.round(soc))));
            if (!Double.isNaN(reserve)) b.put("reserve", Math.round(reserve));
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    /** Ausgewertete Antwort. */
    public static final class Befund {
        public boolean ok;
        public boolean neuPlanen;
        public double abweichungPp = Double.NaN;
        public final JSONArray gruende = new JSONArray();
        /** Body für {@code POST plan} (vom Planer vorbereitet) oder null. */
        public JSONObject neuPlanAnfrage;

        public JSONObject alsJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("ok", ok).put("neuPlanen", neuPlanen).put("gruende", gruende);
                if (!Double.isNaN(abweichungPp)) o.put("abweichungPp", abweichungPp);
            } catch (Exception ignored) { }
            return o;
        }
    }

    public static Befund auswerten(JSONObject antwort) {
        Befund b = new Befund();
        if (antwort == null || !antwort.optBoolean("ok", false)) return b;
        b.ok = true;
        b.neuPlanen = antwort.optBoolean("neu_planen", false);
        b.abweichungPp = antwort.optDouble("abweichung_pp", Double.NaN);
        JSONArray g = antwort.optJSONArray("gruende");
        if (g != null) for (int i = 0; i < g.length() && i < 5; i++) b.gruende.put(g.optString(i, ""));
        JSONObject a = antwort.optJSONObject("neu_plan_anfrage");
        if (b.neuPlanen && a != null && a.has("start") && a.has("ziel")) b.neuPlanAnfrage = a;
        return b;
    }

    /**
     * {@code neu_plan_anfrage} des Planers als Body für {@code POST plan}, ergänzt um Fahrzeug und Auto-Kennung wie
     * bei der lokalen Neuplanung. Nur die Felder aus §17 werden übernommen.
     */
    public static JSONObject planAnfrage(JSONObject neuPlanAnfrage, String fahrzeug, String autoId) {
        JSONObject b = new JSONObject();
        try {
            for (String k : new String[]{"start", "ziel", "soc_start", "via", "soc_reserve"}) {
                if (neuPlanAnfrage.has(k)) b.put(k, neuPlanAnfrage.get(k));
            }
            if (b.has("soc_reserve")) b.put("soc_ziel_min", b.get("soc_reserve"));
            if (fahrzeug != null && !fahrzeug.isEmpty()) b.put("fahrzeug", fahrzeug);
            if (autoId != null && autoId.matches("^a-[0-9a-f]{16}$")) b.put("auto", autoId);
        } catch (Exception ignored) { }
        return b;
    }
}
