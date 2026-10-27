package at.greenchili.surfmate.charging;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Börsenpreise (Day-Ahead) von aWATTar für AT/DE (LADEPLAN_KONZEPT §3, 0.1.21) – <b>nur</b> hinter dem Schalter
 * „dynamischer Tarif“ (Standard aus). Ohne Schalter: kein einziger Abruf, und {@link #euroProKwh} liefert nichts
 * (auch nicht aus einem alten Cache) – Börsenpreise erscheinen dann nirgends.
 * <pre>
 * GET https://api.awattar.at|de/v1/marketdata?start=&lt;ms&gt;&amp;end=&lt;ms&gt;
 *   → {data:[{start_timestamp, end_timestamp, marketprice (Eur/MWh), unit}]}   (öffentliche Marktdaten, kein Konto)
 * </pre>
 * Abruf: fehlt der heutige Tag bzw. ab 14:00 der morgige → holen; höchstens einmal je Stunde je Land (auch bei Fehler).
 * Cache je Land in einer Datei (nur Zeitraum + Preis, nichts über den Nutzer), älter als 3 Tage wird verworfen.
 */
public final class BoersenPreise {

    /** HTTP-GET (Laufzeit: HttpURLConnection; Tests: Attrappe). Liefert {code, text}. */
    public interface Http {
        Object[] hole(String url) throws Exception;
    }

    public static final String[] LAENDER = {"AT", "DE"};
    static final long MIN_ABSTAND_MS = 60 * 60_000L;
    static final long BEHALTEN_MS = 3 * 24 * 3_600_000L;
    static final int NACHMITTAG = 14;

    /** Laufzeit-Instanz für {@link Zeittarif} (Abrechnung, Planer); null = keine Börse. */
    private static volatile BoersenPreise aktiv;

    public static void setzeAktiv(BoersenPreise b) {
        aktiv = b;
    }

    /** €/kWh (ohne Aufschläge) zum Zeitpunkt oder NaN (Schalter aus, unbekannt, kein Land). */
    public static double aktuell(String land, long ms) {
        BoersenPreise b = aktiv;
        return b == null ? Double.NaN : b.euroProKwh(land, ms);
    }

    /** Letzter bekannter Zeitpunkt (Ende) der Börsenpreise oder 0. */
    public static long aktuellBis(String land) {
        BoersenPreise b = aktiv;
        return b == null || !b.an() ? 0 : b.bis(land);
    }

    private final File ordner;
    private final Http http;
    private final LongSupplier uhr;
    private final ZoneId zone;
    private final BooleanSupplier schalter;
    /** Land → (Start ms → {Ende ms, €/MWh}). */
    private final Map<String, TreeMap<Long, double[]>> preise = new ConcurrentHashMap<>();
    private final Map<String, Long> letzterVersuch = new ConcurrentHashMap<>();
    private final Map<String, String> letzterFehler = new ConcurrentHashMap<>();

    public BoersenPreise(File ordner, Http http, LongSupplier uhr, ZoneId zone, BooleanSupplier schalter) {
        this.ordner = ordner;
        this.http = http;
        this.uhr = uhr;
        this.zone = zone;
        this.schalter = schalter;
    }

    boolean an() {
        try {
            return schalter.getAsBoolean();
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean landOk(String land) {
        return "AT".equals(land) || "DE".equals(land);
    }

    public double euroProKwh(String land, long ms) {
        if (!an() || !landOk(land)) return Double.NaN;
        TreeMap<Long, double[]> m = karte(land);
        Map.Entry<Long, double[]> e = m.floorEntry(ms);
        if (e == null || ms >= (long) e.getValue()[0]) return Double.NaN;
        return e.getValue()[1] / 1000.0;
    }

    public long bis(String land) {
        if (!landOk(land)) return 0;
        TreeMap<Long, double[]> m = karte(land);
        return m.isEmpty() ? 0 : (long) m.lastEntry().getValue()[0];
    }

    private TreeMap<Long, double[]> karte(String land) {
        return preise.computeIfAbsent(land, this::ladeDatei);
    }

    /**
     * Holt die Preise, wenn nötig (nur mit Schalter). Liefert true, wenn abgerufen wurde.
     * Nötig: heute fehlt, oder ab 14:00 fehlt morgen. Höchstens einmal je Stunde je Land.
     */
    public synchronized boolean aktualisiere(String land) {
        if (!an() || !landOk(land)) return false;
        long jetzt = uhr.getAsLong();
        ZonedDateTime z = Instant.ofEpochMilli(jetzt).atZone(zone);
        LocalDate heute = z.toLocalDate();
        long heuteEnde = heute.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        long morgenEnde = heute.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli();
        long bis = bis(land);
        boolean noetig = bis < heuteEnde || (z.getHour() >= NACHMITTAG && bis < morgenEnde);
        if (!noetig) return false;
        Long vorher = letzterVersuch.get(land);
        if (vorher != null && jetzt - vorher < MIN_ABSTAND_MS) return false;
        letzterVersuch.put(land, jetzt);
        long start = heute.atStartOfDay(zone).toInstant().toEpochMilli();
        String url = "https://api.awattar." + ("DE".equals(land) ? "de" : "at") + "/v1/marketdata?start=" + start + "&end=" + morgenEnde;
        try {
            Object[] a = http.hole(url);
            int code = (Integer) a[0];
            if (code != 200) throw new IllegalStateException("http_" + code);
            JSONArray d = new JSONObject((String) a[1]).getJSONArray("data");
            TreeMap<Long, double[]> m = new TreeMap<>(karte(land));
            int n = 0;
            for (int i = 0; i < d.length(); i++) {
                JSONObject e = d.getJSONObject(i);
                long s = e.getLong("start_timestamp"), en = e.getLong("end_timestamp");
                double p = e.getDouble("marketprice");
                if (en <= s || en - s > 3 * 3_600_000L || Double.isNaN(p) || Math.abs(p) > 10_000
                        || !"Eur/MWh".equalsIgnoreCase(e.optString("unit", "Eur/MWh"))) continue;
                m.put(s, new double[]{en, p});
                n++;
            }
            if (n == 0) throw new IllegalStateException("keine_daten");
            m.headMap(jetzt - BEHALTEN_MS).clear();
            preise.put(land, m);
            letzterFehler.remove(land);
            speichere(land, m);
            return true;
        } catch (Exception e) {
            letzterFehler.put(land, e.getMessage() == null ? "fehler" : e.getMessage());
            return false;
        }
    }

    /** Für die Anzeige (nur mit Schalter): Stand, bis wann bekannt, letzter Fehler. */
    public JSONObject status(String land) throws JSONException {
        JSONObject o = new JSONObject().put("an", an()).put("land", landOk(land) ? land : JSONObject.NULL);
        if (!an() || !landOk(land)) return o;
        long b = bis(land);
        o.put("bis", b > 0 ? (Object) b : JSONObject.NULL);
        o.put("fehler", letzterFehler.containsKey(land) ? letzterFehler.get(land) : JSONObject.NULL);
        return o;
    }

    // ------------------------------------------------------------------ Datei

    private File datei(String land) {
        return new File(ordner, "boerse_" + land.toLowerCase(java.util.Locale.ROOT) + ".json");
    }

    private TreeMap<Long, double[]> ladeDatei(String land) {
        TreeMap<Long, double[]> m = new TreeMap<>();
        try {
            File f = datei(land);
            if (!f.exists()) return m;
            JSONArray a = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).getJSONArray("preise");
            for (int i = 0; i < a.length(); i++) {
                JSONArray x = a.getJSONArray(i);
                m.put(x.getLong(0), new double[]{x.getLong(1), x.getDouble(2)});
            }
        } catch (Exception ignored) { }
        return m;
    }

    private void speichere(String land, TreeMap<Long, double[]> m) {
        try {
            JSONArray a = new JSONArray();
            for (Map.Entry<Long, double[]> e : m.entrySet()) {
                a.put(new JSONArray().put(e.getKey()).put((long) e.getValue()[0]).put(e.getValue()[1]));
            }
            File tmp = new File(ordner, "boerse_" + land.toLowerCase(java.util.Locale.ROOT) + ".tmp");
            Files.write(tmp.toPath(), new JSONObject().put("stand", uhr.getAsLong()).put("preise", a).toString()
                    .getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), datei(land).toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) { }
    }

    /** Laufzeit-HTTP (nur https, kurze Zeitgrenzen, keine Kopfdaten über den Nutzer). */
    public static final Http HTTP = url -> {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        try {
            c.setConnectTimeout(8_000);
            c.setReadTimeout(12_000);
            c.setRequestProperty("User-Agent", "Panoptes");
            int code = c.getResponseCode();
            java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] b = in == null ? new byte[0] : readAll(in);
            return new Object[]{code, new String(b, StandardCharsets.UTF_8)};
        } finally {
            c.disconnect();
        }
    };

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int max = 2 * 1024 * 1024;
        while ((n = in.read(buf)) > 0) {
            o.write(buf, 0, n);
            if (o.size() > max) throw new java.io.IOException("zu_gross");
        }
        return o.toByteArray();
    }
}
