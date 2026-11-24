package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.TimeZone;

/** 0.1.43.20: GET/POST /api/laden/ersparnis und GET /api/laden/export (nur mit Login, nie Kopplungen). */
public class LadenErsparnisExportApiTest {

    static final TimeZone WIEN = TimeZone.getTimeZone("Europe/Vienna");
    static final long JETZT = ZonedDateTime.of(2026, 11, 24, 12, 0, 0, 0, WIEN.toZoneId()).toInstant().toEpochMilli();

    static final class U implements LadenApiHandler.Umgebung {
        JSONObject root = new JSONObject();
        boolean extern;
        JSONArray ladungen = new JSONArray();
        double kmJeTag = 40;
        boolean phev;
        long letzteVon, letzteBis;

        public JSONObject config() {
            try { return new JSONObject(root.toString()); } catch (Exception e) { throw new RuntimeException(e); }
        }
        public boolean schreibe(String abschnitt, Map<String, Object> werte) {
            try {
                JSONObject s = root.optJSONObject(abschnitt);
                if (s == null) { s = new JSONObject(); root.put(abschnitt, s); }
                for (Map.Entry<String, Object> e : werte.entrySet()) s.put(e.getKey(), e.getValue());
                return true;
            } catch (Exception e) { return false; }
        }
        public boolean abschnittLeeren(String abschnitt) { root.remove(abschnitt); return true; }
        public boolean extern() { return extern; }
        public Object[] planerGet(String op, String[] k) { return null; }
        public String[] planerKopf() { return new String[] {"X", "y"}; }
        @Override public long jetzt() { return JETZT; }
        @Override public TimeZone zeitzone() { return WIEN; }
        @Override public String standardWaehrung() { return "€"; }
        @Override public JSONArray sitzungen(long von, long bis) {
            letzteVon = von;
            letzteBis = bis;
            JSONArray a = new JSONArray();
            for (int i = 0; i < ladungen.length(); i++) {
                JSONObject s = ladungen.optJSONObject(i);
                long t = s.optLong("startTime");
                if (t >= von && t < bis) a.put(s);
            }
            return a;
        }
        @Override public boolean phev() { return phev; }
        @Override public double km(long von, long bis) { return Math.max(0, (bis - von) / 86_400_000.0 * kmJeTag); }
    }

    static long t(int j, int m, int d) {
        return ZonedDateTime.of(j, m, d, 8, 0, 0, 0, WIEN.toZoneId()).toInstant().toEpochMilli();
    }

    static JSONObject ladung(long start, double kwh, double rate, String ort) throws Exception {
        return new JSONObject().put("id", start / 1000).put("startTime", start).put("endTime", start + 3_600_000L)
                .put("energyAdded", kwh).put("isDc", false).put("electricityRate", rate).put("cost", kwh * rate)
                .put("currency", "€").put("preisQuelle", "tarif").put("tariffId", "t1").put("placeLabel", ort);
    }

    private static Object[] r(U u, String m, String p, String body) throws Exception {
        String q = p.contains("?") ? p.substring(p.indexOf('?') + 1) : "";
        String po = p.contains("?") ? p.substring(0, p.indexOf('?')) : p;
        return LadenApiHandler.bearbeite(m, po, q, body, u);
    }

    @Test
    public void ersparnisMonatJahrUndGesamt() throws Exception {
        U u = new U();
        u.ladungen.put(ladung(t(2026, 11, 3), 40, 0.25, "Zuhause")).put(ladung(t(2026, 6, 3), 40, 0.25, "Zuhause"));
        Object[] g = r(u, "GET", "/api/laden/ersparnis", null);
        assertEquals(200, g[0]);
        JSONObject o = (JSONObject) g[1];
        assertTrue(o.getJSONObject("vergleich").getBoolean("preis_richtwert"));
        JSONObject monat = o.getJSONObject("monat");
        assertEquals("zeitraum", monat.getString("basis"));
        assertEquals(10.0, monat.getDouble("strom_kosten"), 1e-9);
        assertTrue(monat.getDouble("ersparnis") > 0);
        assertTrue(o.getJSONObject("jahr").getDouble("km") > monat.getDouble("km"));
        assertFalse(o.getJSONObject("gesamt").isNull("ersparnis"));
    }

    @Test
    public void ersparnisFuerEineFahrt() throws Exception {
        U u = new U();
        // 100 km × 6,5 l × 1,65 € = 10,725 €; Strom 3,725 € (Dezimalkomma aus der Oberfläche) → 7 € gespart
        JSONObject f = ((JSONObject) r(u, "GET", "/api/laden/ersparnis?km=100&strom=3,725", null)[1]).getJSONObject("fahrt");
        assertEquals(6.5, f.getDouble("liter"), 1e-9);
        assertEquals(7.0, f.getDouble("ersparnis"), 1e-9);
        JSONObject ohne = ((JSONObject) r(u, "GET", "/api/laden/ersparnis?km=100", null)[1]).getJSONObject("fahrt");
        assertTrue(ohne.isNull("ersparnis"));
        assertEquals("strom_fehlt", ohne.getString("grund"));
    }

    @Test
    public void vergleichsautoSpeichernMitDatumUndNichtInLaden() throws Exception {
        U u = new U();
        Object[] p = r(u, "POST", "/api/laden/ersparnis", "{\"kraftstoff\":\"diesel\",\"verbrauch\":5,\"preis\":1.579}");
        assertEquals(p[1].toString(), 200, p[0]);
        JSONObject v = ((JSONObject) p[1]).getJSONObject("vergleich");
        assertEquals("diesel", v.getString("kraftstoff"));
        assertEquals(1.579, v.getDouble("preis"), 1e-9);
        assertEquals(JETZT, v.getLong("preis_stand"));
        assertFalse(v.getBoolean("preis_richtwert"));
        // eigener Abschnitt, laden.stand bleibt unberührt (sonst bekäme der Planer Vorlieben)
        assertTrue(u.root.has("ersparnis"));
        assertFalse(u.root.has("laden"));
        assertEquals(400, r(u, "POST", "/api/laden/ersparnis", "{\"verbrauch\":99}")[0]);
        assertEquals(400, r(u, "POST", "/api/laden/ersparnis", "kaputt")[0]);
        u.extern = true;
        assertEquals(403, r(u, "POST", "/api/laden/ersparnis", "{\"verbrauch\":6}")[0]);
        assertEquals(405, r(u, "DELETE", "/api/laden/ersparnis", null)[0]);
    }

    @Test
    public void exportLadungenUndKosten() throws Exception {
        U u = new U();
        u.ladungen.put(ladung(t(2026, 10, 3), 10, 0.25, "Büro; Tor 2")).put(ladung(t(2026, 11, 3), 20, 0.2, "Zuhause"));
        Object[] d = LadenApiHandler.export("GET", "liste=ladungen&von=2026-10-01&bis=2026-10-31&sprache=de", u);
        assertEquals(3, d.length);
        assertEquals("ladungen-2026-10-01_2026-10-31.csv", d[1]);
        String s = new String((byte[]) d[2], StandardCharsets.UTF_8);
        assertTrue(s.startsWith("﻿"));
        assertTrue(s.contains("\"Büro; Tor 2\""));
        assertFalse("nur Oktober", s.contains("Zuhause"));
        // bis = Ende des Tages, inklusive
        assertEquals(ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, WIEN.toZoneId()).toInstant().toEpochMilli(), u.letzteBis);

        Object[] k = LadenApiHandler.export("GET", "liste=kosten&von=2026-01-01&bis=2026-11-24", u);
        assertEquals("ladekosten-2026-01-01_2026-11-24.csv", k[1]);
        String ks = new String((byte[]) k[2], StandardCharsets.UTF_8);
        assertTrue(ks.contains("2026-10;1;10;10;2,5;2,5;0;ja"));
        assertTrue(ks.contains("2026-11;1;20;20;4;4;0;ja"));
        // 0.1.43.21: Ersparnis-Spalte nur mit Kopfzeile „Schätzung“
        assertTrue(ks.startsWith("﻿Ersparnis_Schaetzung ist eine Schätzung"));
        assertTrue(ks.contains(";km;Ersparnis_Schaetzung\r\n"));
        // Plug-in-Hybrid: wie die Karte keine Ersparnis
        u.phev = true;
        String ph = new String((byte[]) LadenApiHandler.export("GET", "liste=kosten&von=2026-01-01&bis=2026-11-24", u)[2],
                StandardCharsets.UTF_8);
        assertFalse(ph.contains("Schätzung"));
        assertFalse(ph.contains("Ersparnis_Schaetzung"));
    }

    @Test
    public void exportFehlerUndRechte() throws Exception {
        U u = new U();
        assertEquals(400, LadenApiHandler.export("GET", "liste=alles", u)[0]);
        assertEquals(400, LadenApiHandler.export("GET", "liste=ladungen&von=2026-10-31&bis=2026-10-01", u)[0]);
        assertEquals(405, LadenApiHandler.export("POST", "liste=ladungen", u)[0]);
        u.extern = true;
        assertEquals(403, LadenApiHandler.export("GET", "liste=ladungen", u)[0]);
        // weder Kopplungen noch Handy dürfen die neuen Pfade
        assertTrue(ExternGuard.blocks("GET", "/api/laden/export"));
        assertTrue(ExternGuard.blocks("POST", "/api/laden/ersparnis"));
        assertTrue(ExternGuard.blocks("GET", "/api/auto/meldungen"));
    }
}
