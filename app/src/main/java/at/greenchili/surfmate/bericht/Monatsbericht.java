package at.greenchili.surfmate.bericht;

import at.greenchili.surfmate.fahrtenbuch.FahrtenbuchSeite;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * H7 Monatsbericht (NEUBAU2_API_VORSCHLAG.md §3), alles im Auto gerechnet. Rein, testbar.
 * Was Panoptes nicht sicher weiß, ist {@code null} (kein Raten): Standverlust, Wächter-Zahlen, SoH, 12-V-Minimum.
 * Kosten nur, wenn die Ladevorgänge einen Tarif tragen. „zuhause“ = Ladeort in einer gespeicherten Zone
 * (sichere Orte), sonst „unterwegs“ (auch ohne bekannten Ort).
 */
public final class Monatsbericht {

    private Monatsbericht() {}

    /** Eine gespeicherte Zone (nur für die Zuordnung, verlässt das Auto nie). */
    public static final class Zone {
        public final double lat, lon, radiusM;
        public Zone(double lat, double lon, double radiusM) { this.lat = lat; this.lon = lon; this.radiusM = radiusM; }
    }

    /** [von, bis) des Monats „YYYY-MM“ in der Zeitzone des Autos, oder null. */
    public static long[] monat(String m, TimeZone tz) {
        if (m == null || !m.matches("\\d{4}-(0[1-9]|1[0-2])")) return null;
        Calendar c = Calendar.getInstance(tz, Locale.ROOT);
        c.clear();
        c.set(Integer.parseInt(m.substring(0, 4)), Integer.parseInt(m.substring(5)) - 1, 1, 0, 0, 0);
        long a = c.getTimeInMillis();
        c.add(Calendar.MONTH, 1);
        return new long[]{a, c.getTimeInMillis()};
    }

    public static String vormonat(String m) {
        int y = Integer.parseInt(m.substring(0, 4)), mo = Integer.parseInt(m.substring(5));
        if (--mo == 0) { mo = 12; y--; }
        return String.format(Locale.ROOT, "%04d-%02d", y, mo);
    }

    public static String monatVon(long ms, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz, Locale.ROOT);
        c.setTimeInMillis(ms);
        return String.format(Locale.ROOT, "%04d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1);
    }

    /** Fahren-Block; leer = null-Werte außer Zählern. */
    public static JSONObject fahren(List<FahrtenbuchSeite.Fahrt> fahrten) {
        JSONObject o = new JSONObject();
        double km = 0, kwh = 0, sek = 0;
        boolean kwhBekannt = false;
        double kmMitKwh = 0;
        for (FahrtenbuchSeite.Fahrt f : fahrten) {
            km += f.km;
            sek += Math.max(0, f.ende - f.start) / 1000.0;
            if (!Double.isNaN(f.kwh)) { kwh += f.kwh; kmMitKwh += f.km; kwhBekannt = true; }
        }
        try {
            o.put("km", r1(km));
            o.put("fahrten", fahrten.size());
            o.put("kwh", kwhBekannt ? r1(kwh) : JSONObject.NULL);
            o.put("kwhPro100km", kwhBekannt && kmMitKwh >= 1 ? r1(kwh / kmMitKwh * 100) : JSONObject.NULL);
            o.put("stunden", r1(sek / 3600.0));
        } catch (Exception ignored) { }
        return o;
    }

    /** Laden-Block aus Ladevorgängen (SocHistoryDatabase.chargingRowToJson). */
    public static JSONObject laden(JSONArray sitzungen, List<Zone> zonen) {
        JSONObject o = new JSONObject();
        int n = 0;
        double kwh = 0, kwhH = 0, kwhU = 0, kostenH = 0, kostenU = 0;
        boolean kostenBekanntH = false, kostenBekanntU = false, unbekanntH = false, unbekanntU = false;
        String waehrung = null;
        for (int i = 0; sitzungen != null && i < sitzungen.length(); i++) {
            JSONObject s = sitzungen.optJSONObject(i);
            if (s == null || s.optBoolean("inProgress", false)) continue;
            double e = s.optDouble("energyAdded", Double.NaN);
            if (Double.isNaN(e) || e <= 0) continue;
            n++;
            kwh += e;
            boolean zuhause = inZone(s.optDouble("lat", Double.NaN), s.optDouble("lng", Double.NaN), zonen);
            // Bekannter Preis: Tarif, oder (Kern 5, 0.1.10.1) Nutzerangabe bzw. AC-Standard – auch gratis (0)
            String pq = s.optString("preisQuelle", "");
            boolean bekannt = !s.optString("tariffId", "").isEmpty()
                    || "tarif".equals(pq) || "nutzer".equals(pq) || "standard".equals(pq);
            boolean mitTarif = bekannt && !s.isNull("cost") && s.has("cost") && s.optDouble("cost", -1) >= 0;
            double k = s.optDouble("cost", Double.NaN);
            if (zuhause) kwhH += e; else kwhU += e;
            if (mitTarif && !Double.isNaN(k)) {
                if (zuhause) { kostenH += k; kostenBekanntH = true; } else { kostenU += k; kostenBekanntU = true; }
                String w = s.optString("currency", "");
                if (!w.isEmpty() && waehrung == null) waehrung = w;
            } else if (zuhause) {
                unbekanntH = true;
            } else {
                unbekanntU = true;
            }
        }
        try {
            o.put("anzahl", n);
            o.put("kwh", r1(kwh));
            // Kosten nur, wenn ALLE Vorgänge der Gruppe einen Tarif tragen – sonst wäre die Summe zu niedrig
            o.put("zuhause", new JSONObject().put("kwh", r1(kwhH))
                    .put("kosten", kostenBekanntH && !unbekanntH ? r2(kostenH) : JSONObject.NULL));
            o.put("unterwegs", new JSONObject().put("kwh", r1(kwhU))
                    .put("kosten", kostenBekanntU && !unbekanntU ? r2(kostenU) : JSONObject.NULL));
            o.put("waehrung", waehrung == null ? JSONObject.NULL : waehrung);
        } catch (Exception ignored) { }
        return o;
    }

    static boolean inZone(double lat, double lon, List<Zone> zonen) {
        if (Double.isNaN(lat) || Double.isNaN(lon) || zonen == null) return false;
        for (Zone z : zonen) if (meter(lat, lon, z.lat, z.lon) <= z.radiusM + 50) return true;
        return false;
    }

    static double meter(double a1, double o1, double a2, double o2) {
        double r = 6_371_000, p1 = Math.toRadians(a1), p2 = Math.toRadians(a2);
        double dp = p2 - p1, dl = Math.toRadians(o2 - o1);
        double h = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    public static JSONObject bericht(String monat, JSONArray monate, boolean bisHeute, JSONObject fahren,
                                     JSONObject laden, JSONObject vorFahren) {
        JSONObject o = new JSONObject();
        try {
            o.put("monat", monat);
            o.put("monate", monate);
            o.put("bisHeute", bisHeute);
            o.put("fahren", fahren);
            o.put("laden", laden);
            o.put("standverlust", new JSONObject().put("prozent", JSONObject.NULL).put("kwh", JSONObject.NULL));
            o.put("waechter", new JSONObject().put("ereignisse", JSONObject.NULL).put("aufnahmen", JSONObject.NULL));
            o.put("akku", new JSONObject().put("sohProzent", JSONObject.NULL).put("v12Min", JSONObject.NULL));
            if (vorFahren != null && vorFahren.optInt("fahrten", 0) > 0) {
                o.put("vormonat", new JSONObject().put("km", vorFahren.opt("km"))
                        .put("kwhPro100km", vorFahren.opt("kwhPro100km")));
            } else {
                o.put("vormonat", JSONObject.NULL);
            }
        } catch (Exception ignored) { }
        return o;
    }

    static double r1(double v) { return Math.round(v * 10) / 10.0; }
    static double r2(double v) { return Math.round(v * 100) / 100.0; }
}
