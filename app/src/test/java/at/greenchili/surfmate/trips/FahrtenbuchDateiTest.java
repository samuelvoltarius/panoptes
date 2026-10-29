package at.greenchili.surfmate.trips;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;

/** 0.1.28 P9: Fahrtenbuch als Datei – gleiche Spalten wie das EV-Cockpit, keine Orte, nur im Auto. */
public class FahrtenbuchDateiTest {

    static final ZoneId WIEN = ZoneId.of("Europe/Vienna");

    /** Wie {@code GET /api/trips} (Zusammenfassung), inkl. Koordinaten, die NIE in die Datei dürfen. */
    static JSONObject fahrt(long start, double km, double kwh) throws Exception {
        return new JSONObject().put("id", 1).put("startTime", start).put("endTime", start + 1_800_000L)
                .put("distanceKm", km).put("durationSeconds", 1800).put("energyUsedKwh", kwh)
                .put("socStart", 80.0).put("socEnd", 74.0).put("tripCost", 1.234).put("currency", "€")
                .put("extTempC", 9).put("startLat", 48.2082).put("startLng", 16.3738).put("endLat", 48.3069)
                .put("endLng", 14.2858);
    }

    @Test
    public void monatsgrenzenInDerZeitzoneDesAutos() {
        long[] g = FahrtenbuchDatei.monat("2026-10", WIEN);
        assertEquals(java.time.ZonedDateTime.of(2026, 10, 1, 0, 0, 0, 0, WIEN).toInstant().toEpochMilli(), g[0]);
        assertEquals(java.time.ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, WIEN).toInstant().toEpochMilli() - 1, g[1]);
        assertNull(FahrtenbuchDatei.monat("2026-13", WIEN));
        assertNull(FahrtenbuchDatei.monat("26-10", WIEN));
        assertNull(FahrtenbuchDatei.monat(null, WIEN));
    }

    @Test
    public void csvDeutschWieDasCockpit() throws Exception {
        long t = java.time.ZonedDateTime.of(2026, 10, 12, 7, 5, 0, 0, WIEN).toInstant().toEpochMilli();
        byte[] b = FahrtenbuchDatei.csv(new JSONArray().put(fahrt(t, 23.456, 3.456)), "de", WIEN);
        String s = new String(b, StandardCharsets.UTF_8);
        assertTrue("BOM für Excel", s.startsWith("\uFEFF"));
        String[] z = s.substring(1).split("\r\n");
        // Kopf exakt wie EV-Cockpit fahrtenbuch.py
        assertEquals("Datum;Start;Ende;km;Dauer_min;kWh;kWh_pro_100km;Ladestand_Start;Ladestand_Ende;Kosten;Waehrung;Aussentemp_C", z[0]);
        assertEquals("2026-10-12;07:05;07:35;23,5;30;3,46;14,7;80;74;1,23;€;9", z[1]);
        for (String verboten : new String[] {"48,2", "16,37", "48.2", "14,28", "Lat", "Lng"}) {
            assertFalse(verboten, s.contains(verboten));
        }
    }

    @Test
    public void csvEnglischMitKommaUndPunkt() throws Exception {
        long t = java.time.ZonedDateTime.of(2026, 10, 12, 7, 5, 0, 0, WIEN).toInstant().toEpochMilli();
        String s = new String(FahrtenbuchDatei.csv(new JSONArray().put(fahrt(t, 23.456, 3.456).put("currency", "CHF,x")),
                "en", WIEN), StandardCharsets.UTF_8);
        String[] z = s.substring(1).split("\r\n");
        assertEquals(String.join(",", FahrtenbuchDatei.KOPF_EN), z[0]);
        assertEquals("2026-10-12,07:05,07:35,23.5,30,3.46,14.7,80,74,1.23,CHF x,9", z[1]);
    }

    @Test
    public void druckansichtMitSummeUndOhneOrte() throws Exception {
        long t = java.time.ZonedDateTime.of(2026, 10, 12, 7, 5, 0, 0, WIEN).toInstant().toEpochMilli();
        JSONArray f = new JSONArray().put(fahrt(t, 20, 3)).put(fahrt(t + 86_400_000L, 30, 4.5).put("currency", "<b>"));
        String h = FahrtenbuchDatei.druck(f, "2026-10", "de", WIEN);
        assertTrue(h.contains("window.print()"));
        assertTrue(h.contains("<td>50</td>"));
        assertTrue("Ø 15 kWh/100 km", h.contains("<td>15</td>"));
        assertTrue("escaped", h.contains("&lt;b&gt;"));
        assertFalse(h.contains("48.2"));
        assertTrue(FahrtenbuchDatei.druck(new JSONArray(), "2026-10", "de", WIEN).contains("<p>"));
    }

    static String rufe(String pfad, FahrtenbuchDatei.Quelle q) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(FahrtenbuchDatei.handle("GET", pfad, out, q, WIEN));
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    public void endpunktSeitenweiseAeltesteZuerst() throws Exception {
        long t = java.time.ZonedDateTime.of(2026, 10, 1, 8, 0, 0, 0, WIEN).toInstant().toEpochMilli();
        long[] abgefragt = new long[2];
        FahrtenbuchDatei.Quelle q = (von, bis, limit, offset) -> {
            abgefragt[0] = von;
            abgefragt[1] = bis;
            JSONArray a = new JSONArray();
            if (offset == 0) {
                for (int i = 0; i < limit; i++) a.put(fahrt(t + (limit - i) * 60_000L, 5, 1));   // neueste zuerst
            } else if (offset == limit) {
                a.put(fahrt(t, 5, 1));
            }
            return a;
        };
        String r = rufe("/api/trips/datei?monat=2026-10&sprache=de", q);
        assertTrue(r.startsWith("HTTP/1.1 200"));
        assertTrue(r.contains("Content-Disposition: attachment; filename=\"fahrtenbuch-2026-10.csv\""));
        assertTrue(r.contains("text/csv"));
        assertArrayEquals(FahrtenbuchDatei.monat("2026-10", WIEN), abgefragt);
        String daten = r.substring(r.indexOf("\r\n\r\n") + 4);
        String[] z = daten.split("\r\n");
        assertEquals(1 + FahrtenbuchDatei.SEITE + 1, z.length);
        assertTrue("älteste zuerst", z[1].contains(";08:00;"));
        assertTrue(rufe("/api/trips/datei?monat=2026-10&format=druck", q).contains("text/html"));
        assertTrue(rufe("/api/trips/datei?monat=x", q).startsWith("HTTP/1.1 400"));
        assertTrue(rufe("/api/trips/datei?monat=2026-10", null).startsWith("HTTP/1.1 503"));
        assertFalse(FahrtenbuchDatei.handle("GET", "/api/trips/dateix", new ByteArrayOutputStream(), q, WIEN));
    }

    @Test
    public void nurLesenFuerGekoppelteGeraete() {
        assertFalse(at.greenchili.surfmate.server.ExternGuard.blocks("GET", "/api/trips/datei?monat=2026-10"));
        assertTrue(at.greenchili.surfmate.server.ExternGuard.blocks("POST", "/api/trips/datei"));
    }

    @Test
    public void seiteUndTexteInFuenfSprachen() throws Exception {
        File base = new File("src/main/assets");
        if (!base.isDirectory()) base = new File("app/src/main/assets");
        String html = new String(java.nio.file.Files.readAllBytes(new File(base, "web/local/trips.html").toPath()),
                StandardCharsets.UTF_8);
        assertTrue(html.contains("/api/trips/datei?monat="));
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject w = new JSONObject(new String(java.nio.file.Files.readAllBytes(
                    new File(base, "web/i18n/" + l + ".json").toPath()), StandardCharsets.UTF_8)).getJSONObject("fahrtenbuch_datei");
            for (String k : new String[] {"titel", "hinweis", "monat", "tabelle", "drucken"}) assertTrue(l + k, w.has(k));
            JSONObject s = new JSONObject(new String(java.nio.file.Files.readAllBytes(
                    new File(base, "server-i18n/" + l + ".json").toPath()), StandardCharsets.UTF_8)).getJSONObject("fahrtenbuch_datei");
            assertEquals(12, s.getJSONObject("spalte").length());
            assertTrue(s.getString("titel").contains("{0}"));
        }
    }
}
