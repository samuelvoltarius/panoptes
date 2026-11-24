package at.greenchili.surfmate.charging;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 0.1.43.21 Ladekosten für Home Assistant ({@code GET /api/ha/v1/daten}, Block {@code laden}). Rein, auf
 * {@code chargingRowToJson}-Zeilen wie {@link LadeKosten}, mit derselben Abrechnung (Betrag laut Säule bzw. abgerechnete
 * kWh). Keine Orte, keine Säulen-Namen, keine Kennungen.
 *
 * <p>Ø-Preis = Kosten ÷ abgerechnete kWh, aber NUR über Ladungen mit bekanntem Preis; eine Ladung ohne Preis zählt nie
 * als 0 (dann {@code vollstaendig=false}, und {@code kosten} ist zu niedrig).
 */
public final class LadeSummen {

    private LadeSummen() {}

    /** Summen über alle abgeschlossenen Ladungen in {@code sitzungen} (der Aufrufer wählt den Zeitraum). */
    public static JSONObject zeitraum(JSONArray sitzungen, long von, long bis) throws Exception {
        int n = 0, ohne = 0;
        double kwh = 0, kwhAbg = 0, kosten = 0, kwhBepreist = 0;
        String waehrung = null;
        for (int i = 0; sitzungen != null && i < sitzungen.length(); i++) {
            JSONObject s = sitzungen.optJSONObject(i);
            if (!LadeKosten.zaehlt(s)) continue;
            n++;
            kwh += s.optDouble("energyAdded", 0);
            double ka = LadeKosten.kwhAbgerechnet(s);
            if (Double.isFinite(ka)) kwhAbg += ka;
            double k = LadeKosten.kostenAbgerechnet(s);
            if (Double.isNaN(k)) {
                ohne++;
            } else {
                kosten += k;
                if (Double.isFinite(ka)) kwhBepreist += ka;
                String w = s.optString("currency", "");
                if (waehrung == null && !w.isEmpty()) waehrung = w;
            }
        }
        JSONObject o = new JSONObject();
        o.put("von", von);
        o.put("bis", bis);
        o.put("ladungen", n);
        o.put("kwh", runde(kwh, 1));
        o.put("kwh_abgerechnet", runde(kwhAbg, 1));
        o.put("kosten", n > ohne ? (Object) runde(kosten, 2) : JSONObject.NULL);
        o.put("preis_kwh", kwhBepreist > 0 ? (Object) runde(kosten / kwhBepreist, 3) : JSONObject.NULL);
        o.put("ladungen_ohne_preis", ohne);
        o.put("vollstaendig", ohne == 0);
        o.put("waehrung", waehrung == null ? JSONObject.NULL : waehrung);
        return o;
    }

    /** Die zuletzt begonnene abgeschlossene Ladung oder {@code null}. */
    public static JSONObject letzte(JSONArray sitzungen) throws Exception {
        JSONObject best = null;
        for (int i = 0; sitzungen != null && i < sitzungen.length(); i++) {
            JSONObject s = sitzungen.optJSONObject(i);
            if (!LadeKosten.zaehlt(s)) continue;
            if (best == null || s.optLong("startTime") > best.optLong("startTime")) best = s;
        }
        if (best == null) return null;
        JSONObject o = new JSONObject();
        o.put("start", best.optLong("startTime"));
        long ende = best.optLong("endTime", 0);
        o.put("ende", ende > 0 ? (Object) ende : JSONObject.NULL);
        o.put("dc", best.has("isDc") && !best.isNull("isDc") ? best.opt("isDc") : JSONObject.NULL);
        o.put("kwh", runde(best.optDouble("energyAdded", 0), 2));
        double ka = LadeKosten.kwhAbgerechnet(best);
        o.put("kwh_abgerechnet", Double.isFinite(ka) ? (Object) runde(ka, 2) : JSONObject.NULL);
        double k = LadeKosten.kostenAbgerechnet(best);
        o.put("kosten", Double.isNaN(k) ? JSONObject.NULL : (Object) runde(k, 2));
        o.put("preis_kwh", !Double.isNaN(k) && Double.isFinite(ka) && ka > 0 ? (Object) runde(k / ka, 3) : JSONObject.NULL);
        String w = best.optString("currency", "");
        o.put("waehrung", w.isEmpty() ? JSONObject.NULL : w);
        return o;
    }

    static double runde(double v, int stellen) {
        double f = Math.pow(10, stellen);
        return Math.round(v * f) / f;
    }
}
