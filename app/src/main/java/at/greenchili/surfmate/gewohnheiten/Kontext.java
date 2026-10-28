package at.greenchili.surfmate.gewohnheiten;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 0.1.22 Gewohnheiten (KONZEPT §2): Kontext einer Bedienhandlung – Tagestyp, Wochentag, Minute des Tages, Ort,
 * Außentemperatur, Akku, angesteckt, Zündung. Rein (testbar); der Ort kommt aus der einen Quelle {@code Orte}
 * (Entscheidung F5): Ort mit {@code eigenschaften.zuhause} → „zuhause“, ein anderer Ort → seine Id, sonst „weg“.
 */
public final class Kontext {

    public static final String WERKTAG = "werktag", WOCHENENDE = "wochenende";
    public static final String ZUHAUSE = "zuhause", WEG = "weg", UNBEKANNT = "unbekannt";
    static final String[] WT = {"mo", "di", "mi", "do", "fr", "sa", "so"};

    private Kontext() {}

    /** Rohwerte; null bzw. NaN = unbekannt. */
    public static final class Werte {
        public double lat = Double.NaN, lon = Double.NaN;
        public Integer aussen, soc;
        public Boolean angesteckt, zuendung;
        public JSONArray orte;
    }

    public static JSONObject bilde(long jetzt, ZoneId zone, Werte w) throws JSONException {
        ZonedDateTime z = Instant.ofEpochMilli(jetzt).atZone(zone);
        DayOfWeek d = z.getDayOfWeek();
        JSONObject k = new JSONObject();
        k.put("tag", tagestyp(d));
        k.put("wt", WT[d.getValue() - 1]);
        k.put("min", z.getHour() * 60 + z.getMinute());
        k.put("ort", ort(w == null ? null : w.orte, w == null ? Double.NaN : w.lat, w == null ? Double.NaN : w.lon));
        if (w != null) {
            if (w.aussen != null) k.put("aussen", w.aussen);
            if (w.soc != null) k.put("soc", w.soc);
            if (w.angesteckt != null) k.put("angesteckt", w.angesteckt);
            if (w.zuendung != null) k.put("zuendung", w.zuendung);
        }
        return k;
    }

    public static String tagestyp(DayOfWeek d) {
        return d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY ? WOCHENENDE : WERKTAG;
    }

    /** Ort nach {@code Orte}: zuhause vor anderen Orten; ohne Position „unbekannt“. */
    public static String ort(JSONArray orte, double lat, double lon) {
        if (Double.isNaN(lat) || Double.isNaN(lon)) return UNBEKANNT;
        if (orte == null) return WEG;
        String anderer = null;
        for (int i = 0; i < orte.length(); i++) {
            JSONObject o = orte.optJSONObject(i);
            if (o == null || !o.has("lat") || !o.has("lon")) continue;
            if (at.greenchili.surfmate.orte.Orte.meter(lat, lon, o.optDouble("lat"), o.optDouble("lon")) > o.optDouble("r", 0)) continue;
            JSONObject eig = o.optJSONObject("eigenschaften");
            if (eig != null && eig.optBoolean("zuhause")) return ZUHAUSE;
            if (anderer == null && !o.optString("id").isEmpty()) anderer = o.optString("id");
        }
        return anderer != null ? anderer : WEG;
    }
}
