package at.greenchili.surfmate.fahrtenbuch;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Klartext einer Fahrtenbuch-Seite (Planer §8.5): höchstens {@link #JE_SEITE} Fahrten, keine Koordinaten,
 * keine Ortsnamen (das Auto schickt sie bewusst nicht), keine Notizen. Rein und testbar.
 */
public final class FahrtenbuchSeite {

    private FahrtenbuchSeite() {}

    public static final int JE_SEITE = 200;
    public static final int MAX_TAGE = 92;

    /** Eine Fahrt, wie sie die Seite braucht. */
    public static final class Fahrt {
        public long start, ende;
        public double km, kwh = Double.NaN, socStart = Double.NaN, socEnde = Double.NaN;
    }

    /** [vonMs, bisMs) in der Zeitzone des Autos, oder null bei ungültigem Zeitraum (Format, Reihenfolge, > 92 Tage). */
    public static long[] zeitraum(String von, String bis, TimeZone tz) {
        if (von == null || bis == null || !von.matches("\\d{4}-\\d{2}-\\d{2}") || !bis.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return null;
        }
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
            f.setTimeZone(tz);
            f.setLenient(false);
            long a = f.parse(von).getTime();
            Calendar c = Calendar.getInstance(tz, Locale.ROOT);
            c.setTimeInMillis(f.parse(bis).getTime());
            c.add(Calendar.DAY_OF_MONTH, 1);
            long b = c.getTimeInMillis();
            if (b <= a) return null;
            if ((b - a) > (MAX_TAGE + 1) * 86_400_000L) return null;
            return new long[]{a, b};
        } catch (Exception e) {
            return null;
        }
    }

    /** Klartext-JSON für Seite {@code seite} (ab 1) aus allen Fahrten des Zeitraums (alt → neu sortiert erwartet). */
    public static JSONObject seite(String auto, String von, String bis, int seite, List<Fahrt> alle, TimeZone tz) {
        JSONObject o = new JSONObject();
        try {
            int seiten = Math.max(1, (alle.size() + JE_SEITE - 1) / JE_SEITE);
            int s = Math.max(1, Math.min(seite, seiten));
            o.put("version", 1);
            o.put("auto", auto);
            o.put("zeitraum", new JSONObject().put("von", von).put("bis", bis));
            o.put("seite", s);
            o.put("seiten", seiten);
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT);
            iso.setTimeZone(tz);
            JSONArray a = new JSONArray();
            double sumKm = 0, sumKwh = 0;
            boolean kwhBekannt = false;
            for (Fahrt f : alle) {
                sumKm += f.km;
                if (!Double.isNaN(f.kwh)) { sumKwh += f.kwh; kwhBekannt = true; }
            }
            int ab = (s - 1) * JE_SEITE;
            for (int i = ab; i < Math.min(alle.size(), ab + JE_SEITE); i++) {
                Fahrt f = alle.get(i);
                JSONObject j = new JSONObject();
                j.put("start", iso.format(new java.util.Date(f.start)));
                j.put("ende", iso.format(new java.util.Date(f.ende)));
                j.put("km", runde(f.km));
                j.put("kwh", Double.isNaN(f.kwh) ? JSONObject.NULL : runde(f.kwh));
                j.put("soc_start", Double.isNaN(f.socStart) ? JSONObject.NULL : Math.round(f.socStart));
                j.put("soc_ende", Double.isNaN(f.socEnde) ? JSONObject.NULL : Math.round(f.socEnde));
                j.put("von", JSONObject.NULL);
                j.put("nach", JSONObject.NULL);
                j.put("notiz", JSONObject.NULL);
                a.put(j);
            }
            o.put("fahrten", a);
            o.put("summe", new JSONObject().put("km", runde(sumKm)).put("kwh", kwhBekannt ? runde(sumKwh) : JSONObject.NULL));
        } catch (Exception ignored) { }
        return o;
    }

    static double runde(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
