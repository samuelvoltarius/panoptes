package at.greenchili.surfmate.charging;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * „Was kosten 100 km?“ und die Ladungsliste (Kern 5, 0.1.10.1; Projektinhaber über die Koordination 22.10.).
 * Rein: arbeitet auf {@code chargingRowToJson}-Zeilen und den gefahrenen km. Gratis zählt als 0, eine Ladung ohne Preis
 * NIE als 0 – dann {@code vollstaendig=false} und der Anteil wird ausgewiesen. Keine Koordinaten in den Antworten.
 */
public final class LadeKosten {

    private LadeKosten() {}

    public static final String MONAT = "monat", TAGE30 = "30t", JAHR = "jahr", GESAMT = "gesamt";

    /** {von, bis} in ms: monat = laufender Kalendermonat, 30t = letzte 30 Tage, jahr = laufendes Kalenderjahr, gesamt. */
    public static long[] zeitraum(String z, long jetzt, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz, Locale.ROOT);
        c.setTimeInMillis(jetzt);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        switch (z == null ? MONAT : z) {
            case TAGE30:
                return new long[] {jetzt - 30L * 86_400_000L, jetzt};
            case JAHR:
                c.set(Calendar.DAY_OF_YEAR, 1);
                return new long[] {c.getTimeInMillis(), jetzt};
            case GESAMT:
                return new long[] {0, jetzt};
            case MONAT:
            default:
                c.set(Calendar.DAY_OF_MONTH, 1);
                return new long[] {c.getTimeInMillis(), jetzt};
        }
    }

    public static boolean gueltig(String z) {
        return MONAT.equals(z) || TAGE30.equals(z) || JAHR.equals(z) || GESAMT.equals(z);
    }

    /** Ist der Preis dieser Ladung bekannt (Tarif, Nutzer, AC-Standard; gratis = 0 ist bekannt)? */
    static boolean preisBekannt(JSONObject s) {
        String q = s.optString("preisQuelle", "");
        boolean bekannt = !s.optString("tariffId", "").isEmpty()
                || "tarif".equals(q) || "nutzer".equals(q) || "standard".equals(q);
        return bekannt && s.has("cost") && !s.isNull("cost") && s.optDouble("cost", -1) >= 0;
    }

    static boolean zaehlt(JSONObject s) {
        return s != null && !s.optBoolean("inProgress", false) && s.optDouble("energyAdded", 0) > 0;
    }

    /** Antwort für {@code GET /api/laden/kosten}. */
    public static JSONObject kosten(JSONArray sitzungen, double km, String zeitraum, long von, long bis) throws Exception {
        int n = 0, ohne = 0, gratis = 0;
        double kwh = 0, kwhOhne = 0, summe = 0;
        String waehrung = null;
        for (int i = 0; sitzungen != null && i < sitzungen.length(); i++) {
            JSONObject s = sitzungen.optJSONObject(i);
            if (!zaehlt(s)) continue;
            double e = s.optDouble("energyAdded");
            n++;
            kwh += e;
            if (preisBekannt(s)) {
                double k = s.optDouble("cost");
                summe += k;
                if (k == 0) gratis++;
                String w = s.optString("currency", "");
                if (waehrung == null && !w.isEmpty()) waehrung = w;
            } else {
                ohne++;
                kwhOhne += e;
            }
        }
        boolean mitKm = km >= 1;
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("zeitraum", zeitraum);
        o.put("von", von);
        o.put("bis", bis);
        o.put("km", Math.round(km * 10) / 10.0);
        o.put("kwh_geladen", Math.round(kwh * 10) / 10.0);
        o.put("kosten_summe", Math.round(summe * 100) / 100.0);
        o.put("kosten_je_100km", mitKm ? (Object) (Math.round(summe / km * 100 * 100) / 100.0) : JSONObject.NULL);
        o.put("kwh_je_100km", mitKm ? (Object) (Math.round(kwh / km * 100 * 10) / 10.0) : JSONObject.NULL);
        o.put("ladungen", n);
        o.put("ladungen_ohne_preis", ohne);
        o.put("gratis_ladungen", gratis);
        o.put("anteil_ohne_preis_kwh", kwh > 0 ? Math.round(kwhOhne / kwh * 1000) / 1000.0 : 0);
        o.put("vollstaendig", ohne == 0);
        o.put("waehrung", waehrung == null ? JSONObject.NULL : waehrung);
        return o;
    }

    /** Antwort für {@code GET /api/laden/ladungen}: Seite der Liste (neueste zuerst) + Monatssummen über den ganzen Bereich. */
    public static JSONObject ladungen(JSONArray sitzungen, int seite, int anzahl, TimeZone tz) throws Exception {
        List<JSONObject> alle = new ArrayList<>();
        for (int i = 0; sitzungen != null && i < sitzungen.length(); i++) {
            JSONObject s = sitzungen.optJSONObject(i);
            if (zaehlt(s)) alle.add(s);
        }
        alle.sort((a, b) -> Long.compare(b.optLong("startTime"), a.optLong("startTime")));
        anzahl = Math.max(1, Math.min(200, anzahl));
        seite = Math.max(0, seite);
        JSONArray liste = new JSONArray();
        for (int i = seite * anzahl; i < alle.size() && i < (seite + 1) * anzahl; i++) liste.put(eintrag(alle.get(i)));
        Map<String, JSONObject> monate = new LinkedHashMap<>();
        Calendar c = Calendar.getInstance(tz, Locale.ROOT);
        for (JSONObject s : alle) {
            c.setTimeInMillis(s.optLong("startTime"));
            String m = String.format(Locale.ROOT, "%04d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1);
            JSONObject x = monate.get(m);
            if (x == null) {
                x = new JSONObject().put("monat", m).put("ladungen", 0).put("kwh", 0.0).put("kosten", 0.0).put("ohne_preis", 0);
                monate.put(m, x);
            }
            x.put("ladungen", x.getInt("ladungen") + 1);
            x.put("kwh", Math.round((x.getDouble("kwh") + s.optDouble("energyAdded")) * 10) / 10.0);
            if (preisBekannt(s)) x.put("kosten", Math.round((x.getDouble("kosten") + s.optDouble("cost")) * 100) / 100.0);
            else x.put("ohne_preis", x.getInt("ohne_preis") + 1);
        }
        JSONArray ma = new JSONArray();
        for (JSONObject x : monate.values()) ma.put(x.put("vollstaendig", x.getInt("ohne_preis") == 0));
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("seite", seite);
        o.put("anzahl", anzahl);
        o.put("gesamt", alle.size());
        o.put("mehr", (seite + 1) * anzahl < alle.size());
        o.put("ladungen", liste);
        o.put("monate", ma);
        return o;
    }

    static JSONObject eintrag(JSONObject s) throws Exception {
        JSONObject e = new JSONObject();
        e.put("id", s.optLong("id"));
        e.put("start", s.optLong("startTime"));
        e.put("ende", s.opt("endTime"));
        e.put("dc", s.has("isDc") ? s.opt("isDc") : JSONObject.NULL);
        e.put("kwh", Math.round(s.optDouble("energyAdded") * 100) / 100.0);
        boolean bekannt = preisBekannt(s);
        e.put("rate", bekannt ? s.opt("electricityRate") : JSONObject.NULL);
        e.put("kosten", bekannt ? s.opt("cost") : JSONObject.NULL);
        e.put("waehrung", s.optString("currency", ""));
        e.put("quelle", s.isNull("preisQuelle") ? "" : s.optString("preisQuelle", ""));
        e.put("tarifName", s.isNull("tariffLabel") ? JSONObject.NULL : s.opt("tariffLabel"));
        String ort = s.isNull("placeLabel") ? "" : s.optString("placeLabel", "");
        e.put("ort", ort.isEmpty() ? JSONObject.NULL : ort);
        return e;
    }
}
