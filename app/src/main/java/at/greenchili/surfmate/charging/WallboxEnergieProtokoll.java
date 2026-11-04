package at.greenchili.surfmate.charging;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Wallbox-Energie je Ladung nur als PROTOKOLLZEILE (Koordination 04.11.2026, SoH-Anker): Die Wallbox misst netzseitig
 * (vor dem Bordlader, 8-15 % mehr als im Akku ankommt, nicht geeicht). Diese Zahl fließt NIE in den SOH-Anker, sie
 * dient nur dem späteren Abgleich (Wirkungsgrad). Rein, ohne Android, testbar.
 */
public final class WallboxEnergieProtokoll {

    private WallboxEnergieProtokoll() {}

    /**
     * Protokollzeile aus dem Wallbox-Status ({@code WallboxDienst.status()}), oder null, wenn keine Wallbox
     * eingerichtet ist (dann ist nichts zu protokollieren).
     *
     * @param status      Status-JSON (ladepunkte[].energieSitzungKwh / zaehlerRoh, erreichbar, eingerichtet)
     * @param sitzungStart Start der Ladung im Auto (ms)
     * @param deltaSoc    Ladehub in Prozentpunkten (nur zur Zuordnung im Protokoll)
     */
    public static String zeile(JSONObject status, long sitzungStart, double deltaSoc) {
        if (status == null || !status.optBoolean("eingerichtet", false)) return null;
        String kopf = "Wallbox-Energie zur Ladung " + sitzungStart + " (dSoc=" + String.format(java.util.Locale.US, "%.1f", deltaSoc)
                + "): ";
        if (!status.optBoolean("erreichbar", false)) return kopf + "Wallbox nicht erreichbar";
        JSONArray lps = status.optJSONArray("ladepunkte");
        for (int i = 0; lps != null && i < lps.length(); i++) {
            JSONObject lp = lps.optJSONObject(i);
            if (lp == null || !lp.has("energieSitzungKwh") || lp.isNull("energieSitzungKwh")) continue;
            double kwh = lp.optDouble("energieSitzungKwh", Double.NaN);
            if (Double.isNaN(kwh) || Double.isInfinite(kwh) || kwh < 0) continue;
            String roh = lp.has("zaehlerRoh") && !lp.isNull("zaehlerRoh") ? String.valueOf(lp.opt("zaehlerRoh")) : "n/a";
            return kopf + String.format(java.util.Locale.US, "energie_sitzung_kwh=%.3f", kwh) + " zaehler_roh=" + roh
                    + " (netzseitig, nicht geeicht, NICHT im SOH-Anker)";
        }
        return kopf + "kein Sitzungswert im Status";
    }
}
