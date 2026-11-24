package at.greenchili.surfmate.charging;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** 0.1.43.20 CSV-Export Ladungen/Ladekosten: Excel DE (Semikolon, Komma, BOM), Umlaute, Freitext, keine Koordinaten. */
public class LadeCsvTest {

    static final ZoneId WIEN = ZoneId.of("Europe/Vienna");

    static long t(int j, int m, int d, int h, int min) {
        return ZonedDateTime.of(j, m, d, h, min, 0, 0, WIEN).toInstant().toEpochMilli();
    }

    /** Wie chargingRowToJson, inkl. Koordinaten, die nie in die Datei dürfen. */
    static JSONObject ladung(long id, long start, double kwh, double rate, String ort) throws Exception {
        return new JSONObject().put("id", id).put("startTime", start).put("endTime", start + 3_600_000L)
                .put("energyAdded", kwh).put("isDc", false).put("electricityRate", rate).put("cost", kwh * rate)
                .put("currency", "€").put("preisQuelle", "tarif").put("tariffId", "t1").put("tariffLabel", "Hausstrom")
                .put("placeLabel", ort).put("betreiber", JSONObject.NULL)
                .put("latitude", 48.2082).put("longitude", 16.3738);
    }

    static String[] zeilen(byte[] b) {
        String s = new String(b, StandardCharsets.UTF_8);
        assertTrue("BOM für Excel", s.startsWith("﻿"));
        return s.substring(1).split("\r\n");
    }

    @Test
    public void ladungenDeutschMitSemikolonUndKomma() throws Exception {
        JSONArray a = new JSONArray()
                .put(ladung(2, t(2026, 10, 12, 18, 5), 30.456, 0.2, "Zuhause"))
                .put(ladung(1, t(2026, 10, 3, 7, 30), 10, 0.25, "Garage Müllerstraße"));
        String[] z = zeilen(LadeCsv.ladungen(a, "de", WIEN));
        assertEquals(String.join(";", LadeCsv.KOPF_LADUNGEN_DE), z[0]);
        assertEquals("älteste zuerst", "2026-10-03;07:30;08:30;AC;10;10;0,25;2,5;2,5;€;tarif;Hausstrom;Garage Müllerstraße;", z[1]);
        assertTrue(z[2].startsWith("2026-10-12;18:05;19:05;AC;30,46;30,46;0,2;6,09;"));
        String alles = String.join("\n", z);
        for (String verboten : new String[] {"48,2", "48.2", "16,37", "16.37"}) assertFalse(verboten, alles.contains(verboten));
        assertEquals(3, z.length);
    }

    @Test
    public void freitextMitSemikolonAnfuehrungszeichenUndFormel() throws Exception {
        JSONArray a = new JSONArray()
                .put(ladung(1, t(2026, 10, 3, 7, 30), 10, 0.25, "Büro; Tor 2"))
                .put(ladung(2, t(2026, 10, 4, 7, 30), 10, 0.25, "Halle \"Nord\""))
                .put(ladung(3, t(2026, 10, 5, 7, 30), 10, 0.25, "=HYPERLINK(\"x\")"))
                .put(ladung(4, t(2026, 10, 6, 7, 30), 10, 0.25, "Zeile1\nZeile2"));
        byte[] b = LadeCsv.ladungen(a, "de", WIEN);
        String s = new String(b, StandardCharsets.UTF_8);
        assertTrue(s.contains(";\"Büro; Tor 2\";"));
        assertTrue(s.contains(";\"Halle \"\"Nord\"\"\";"));
        assertTrue("Formel entschärft", s.contains(";\"'=HYPERLINK(\"\"x\"\")\";"));
        assertTrue(s.contains(";\"Zeile1\nZeile2\";"));
        // Umlaute bleiben echte UTF-8-Zeichen
        assertTrue(new String(b, StandardCharsets.UTF_8).contains("Büro"));
        assertFalse(new String(b, StandardCharsets.ISO_8859_1).contains("Büro"));
    }

    @Test
    public void textZelle() {
        assertEquals("", LadeCsv.text(null, ';'));
        assertEquals("Zuhause", LadeCsv.text("Zuhause", ';'));
        assertEquals("a,b", LadeCsv.text("a,b", ';'));
        assertEquals("\"a,b\"", LadeCsv.text("a,b", ','));
        assertEquals("\"a;b\"", LadeCsv.text("a;b", ';'));
        assertEquals("'+43", LadeCsv.text("+43", ';'));
        assertEquals("'-1", LadeCsv.text("-1", ';'));
        assertEquals("'@x", LadeCsv.text("@x", ';'));
    }

    @Test
    public void englischMitKommaUndPunkt() throws Exception {
        JSONArray a = new JSONArray().put(ladung(1, t(2026, 10, 3, 7, 30), 10, 0.25, "Home, garage"));
        String[] z = zeilen(LadeCsv.ladungen(a, "en", WIEN));
        assertEquals(String.join(",", LadeCsv.KOPF_LADUNGEN_EN), z[0]);
        assertEquals("2026-10-03,07:30,08:30,AC,10,10,0.25,2.5,2.5,€,tarif,Hausstrom,\"Home, garage\",", z[1]);
    }

    @Test
    public void ohnePreisLeerStattNull() throws Exception {
        JSONObject l = ladung(1, t(2026, 10, 3, 7, 30), 10, 0.25, "Zuhause");
        l.put("tariffId", "").put("preisQuelle", "fehlt").put("isDc", true).put("placeLabel", JSONObject.NULL);
        String[] z = zeilen(LadeCsv.ladungen(new JSONArray().put(l), "de", WIEN));
        assertEquals("2026-10-03;07:30;08:30;DC;10;10;;;;€;fehlt;Hausstrom;;", z[1]);
    }

    @Test
    public void laufendeUndLeereLadungenFehlen() throws Exception {
        JSONArray a = new JSONArray()
                .put(ladung(1, t(2026, 10, 3, 7, 30), 10, 0.25, "x").put("inProgress", true))
                .put(ladung(2, t(2026, 10, 4, 7, 30), 0, 0.25, "x"));
        assertEquals(1, zeilen(LadeCsv.ladungen(a, "de", WIEN)).length);
    }

    @Test
    public void kostenJeMonat() throws Exception {
        JSONObject ohne = ladung(3, t(2026, 11, 2, 9, 0), 5, 0.3, "x").put("tariffId", "").put("preisQuelle", "fehlt");
        JSONArray a = new JSONArray()
                .put(ladung(1, t(2026, 10, 3, 7, 30), 10, 0.25, "x"))
                .put(ladung(2, t(2026, 10, 20, 7, 30), 20, 0.2, "x"))
                .put(ohne);
        String[] z = zeilen(LadeCsv.kosten(a, "de", WIEN));
        assertEquals(String.join(";", LadeCsv.KOPF_KOSTEN_DE), z[0]);
        assertEquals("2026-10;2;30;30;6,5;6,5;0;ja", z[1]);
        assertEquals("2026-11;1;5;5;0;0;1;nein", z[2]);
        assertEquals("2026-10,2,30,30,6.5,6.5,0,yes", zeilen(LadeCsv.kosten(a, "en", WIEN))[1]);
    }

    @Test
    public void datumsbereich() {
        long jetzt = t(2026, 11, 24, 12, 0);
        long[] b = LadeCsv.bereich("2026-10-01", "2026-10-31", jetzt, WIEN);
        assertArrayEquals(new long[] {t(2026, 10, 1, 0, 0), t(2026, 11, 1, 0, 0) - 1}, b);
        // leer = 1. Jänner bis heute
        assertArrayEquals(new long[] {t(2026, 1, 1, 0, 0), t(2026, 11, 25, 0, 0) - 1}, LadeCsv.bereich("", null, jetzt, WIEN));
        // ein Tag
        assertEquals(86_400_000L - 1, LadeCsv.bereich("2026-06-01", "2026-06-01", jetzt, WIEN)[1]
                - LadeCsv.bereich("2026-06-01", "2026-06-01", jetzt, WIEN)[0]);
        assertNull("verkehrt", LadeCsv.bereich("2026-10-31", "2026-10-01", jetzt, WIEN));
        assertNull(LadeCsv.bereich("2026-13-01", "2026-12-01", jetzt, WIEN));
        assertNull(LadeCsv.bereich("2026-02-30", "2026-03-01", jetzt, WIEN));
        assertNull(LadeCsv.bereich("26-10-01", "2026-10-02", jetzt, WIEN));
        assertNull("zu lang", LadeCsv.bereich("2001-01-01", "2026-01-01", jetzt, WIEN));
    }
}
