package at.greenchili.surfmate.ha;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.server.ExternGuard;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 0.1.43.21 HA-API: neue Daten in /api/ha/v1/daten, Gang und Kilometerstand in zustand. */
public class HaDatenTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** Das Auto steht in Graz (Zeitzone Europe/Vienna). */
    private static final ZoneId GRAZ = ZoneId.of("Europe/Vienna");

    private static long ms(int j, int m, int t, int h, int min) {
        return LocalDateTime.of(j, m, t, h, min).atZone(GRAZ).toInstant().toEpochMilli();
    }

    // ---------------------------------------------------------------- Gang

    @Test
    public void gangNieUnknown() {
        assertEquals("P", HaDaten.gang("P"));
        assertEquals("R", HaDaten.gang("r"));
        assertEquals("N", HaDaten.gang("N"));
        assertEquals("D", HaDaten.gang("D"));
        assertEquals("D", HaDaten.gang("S"));
        assertEquals(JSONObject.NULL, HaDaten.gang("UNKNOWN"));
        assertEquals(JSONObject.NULL, HaDaten.gang("UNKNOWN(7)"));
        assertEquals(JSONObject.NULL, HaDaten.gang(null));
        assertEquals(JSONObject.NULL, HaDaten.gang(JSONObject.NULL));
        assertEquals(JSONObject.NULL, HaDaten.gang(3));
    }

    @Test
    public void telemetrieGangOhneUnknown() throws Exception {
        JSONObject t = HaZustand.telemetrie(new JSONObject().put("gear", "UNKNOWN").put("soc", 55));
        assertTrue("gear bleibt als Schlüssel, Wert null", t.has("gear") && t.isNull("gear"));
        assertEquals("D", HaZustand.telemetrie(new JSONObject().put("gear", "D")).get("gear"));
        assertFalse(HaZustand.telemetrie(new JSONObject().put("soc", 55)).has("gear"));
    }

    // ---------------------------------------------------------------- Kilometerstand

    @Test
    public void kilometerWirdNieKleiner() {
        HaDaten.Kilometer k = new HaDaten.Kilometer(Double.NaN);
        assertTrue(Double.isNaN(k.melde(-1)));
        assertEquals(12345.6, k.melde(12345.6), 1e-9);
        assertEquals("Wackeln nach unten", 12345.6, k.melde(12345.0), 1e-9);
        assertEquals("ganzzahliger Rückfall", 12345.6, k.melde(12345), 1e-9);
        assertEquals(12346.2, k.melde(12346.2), 1e-9);
        assertEquals("fehlt → letzter", 12346.2, k.melde(Double.NaN), 1e-9);
    }

    @Test
    public void ausreisserNachObenZaehltErstNachBestaetigung() {
        HaDaten.Kilometer k = new HaDaten.Kilometer(20000);
        assertEquals("einzelner Ausreißer ×10", 20000, k.melde(200000), 1e-9);
        assertEquals(20000.5, k.melde(20000.5), 1e-9);
        for (int i = 0; i < HaDaten.Kilometer.SPRUNG_BESTAETIGT - 1; i++) assertEquals(20000.5, k.melde(30000), 1e-9);
        assertEquals("dreimal hintereinander → gilt", 30000, k.melde(30000), 1e-9);
    }

    @Test
    public void falscherGespeicherterWertFriertNichtEin() {
        HaDaten.Kilometer k = new HaDaten.Kilometer(999999);
        assertEquals(999999, k.melde(15000), 1e-9);
        assertEquals(999999, k.melde(15000.1), 1e-9);
        assertEquals(15000.2, k.melde(15000.2), 1e-9);
        assertEquals("danach wieder nie kleiner", 15000.2, k.melde(15000.1), 1e-9);
    }

    // ---------------------------------------------------------------- Ersparnis

    private static JSONObject ersparnisAntwort() throws Exception {
        JSONObject v = new JSONObject().put("kraftstoff", "benzin").put("verbrauch", 6.5).put("preis", 1.65)
                .put("preis_richtwert", true).put("waehrung", "EUR");
        JSONObject monat = new JSONObject().put("km", 812.4).put("strom_kosten", 31.2).put("strom_vollstaendig", true)
                .put("liter", 52.8).put("sprit_kosten", 87.13).put("ersparnis", 55.93).put("grund", JSONObject.NULL);
        JSONObject jahr = new JSONObject().put("km", 9100).put("strom_kosten", 350).put("strom_vollstaendig", false)
                .put("sprit_kosten", 976).put("ersparnis", 626).put("grund", "strom_unvollstaendig");
        return new JSONObject().put("ok", true).put("vergleich", v).put("monat", monat).put("jahr", jahr)
                .put("gesamt", jahr);
    }

    @Test
    public void ersparnisIstImmerSchaetzung() throws Exception {
        JSONObject e = HaDaten.ersparnis(ersparnisAntwort(), false, "Schätzung – hängt ab.");
        assertTrue(e.getBoolean("schaetzung"));
        assertTrue(e.getBoolean("verfuegbar"));
        assertEquals("Schätzung – hängt ab.", e.getString("hinweis"));
        assertEquals("ersparnis.schaetzung", e.getString("hinweis_schluessel"));
        JSONObject auto = e.getJSONObject("vergleichsauto");
        assertEquals("benzin", auto.getString("kraftstoff"));
        assertEquals(6.5, auto.getDouble("verbrauch_l_100km"), 1e-9);
        assertEquals(1.65, auto.getDouble("preis_l"), 1e-9);
        assertTrue(auto.getBoolean("preis_richtwert"));
        assertEquals(55.93, e.getJSONObject("monat").getDouble("ersparnis"), 1e-9);
        assertTrue(e.getJSONObject("monat").getBoolean("vollstaendig"));
        assertFalse(e.getJSONObject("jahr").getBoolean("vollstaendig"));
        assertEquals("strom_unvollstaendig", e.getJSONObject("jahr").getString("grund"));
        assertTrue(e.has("gesamt"));
    }

    @Test
    public void ersparnisNichtBeimPlugInHybrid() throws Exception {
        JSONObject e = HaDaten.ersparnis(ersparnisAntwort(), true, "x");
        assertTrue(e.getBoolean("schaetzung"));
        assertFalse(e.getBoolean("verfuegbar"));
        assertTrue(e.isNull("monat") && e.isNull("jahr") && e.isNull("gesamt"));
    }

    @Test
    public void ersparnisOhneAntwort() throws Exception {
        JSONObject e = HaDaten.ersparnis(null, false, null);
        assertFalse(e.getBoolean("verfuegbar"));
        assertTrue(e.isNull("hinweis"));
        assertTrue(e.getJSONObject("vergleichsauto").isNull("kraftstoff"));
    }

    // ---------------------------------------------------------------- Clips

    @Test
    public void clipsZaehlenUndLetzter() throws Exception {
        long jetzt = ms(2026, 11, 24, 18, 0);
        List<HaDaten.Clip> l = Arrays.asList(
                new HaDaten.Clip(ms(2026, 11, 20, 9, 0), null, null, true),
                new HaDaten.Clip(ms(2026, 11, 24, 8, 30), null, null, false),
                new HaDaten.Clip(ms(2026, 11, 24, 17, 45), "stoss", 40.0, false));
        JSONObject c = HaDaten.clips(l, jetzt, GRAZ);
        assertEquals(3, c.getInt("anzahl"));
        assertEquals(2, c.getInt("heute"));
        assertEquals(1, c.getInt("behalten"));
        JSONObject letzter = c.getJSONObject("letzter");
        assertEquals(ms(2026, 11, 24, 17, 45), letzter.getLong("zeit"));
        assertEquals("stoss", letzter.getString("grund"));
        assertEquals(40.0, letzter.getDouble("dauer_s"), 1e-9);
        assertFalse(letzter.getBoolean("behalten"));
        String s = c.toString();
        assertFalse("kein Name, kein Inhalt", s.contains("mp4") || s.contains("ereignis_") || s.contains("url"));
    }

    @Test
    public void keineClips() throws Exception {
        JSONObject c = HaDaten.clips(new ArrayList<>(), 1L, GRAZ);
        assertEquals(0, c.getInt("anzahl"));
        assertTrue(c.isNull("letzter"));
    }

    /** Kleinstes MP4: ftyp + moov/mvhd (Version 0), Zeitskala 1000, Dauer 40 500 → 40,5 s. */
    private File mp4(int version, long skala, long dauer, boolean moovAmEnde) throws Exception {
        ByteArrayOutputStream mv = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(mv);
        d.writeByte(version);
        d.write(new byte[3]);
        if (version == 1) {
            d.writeLong(0); d.writeLong(0); d.writeInt((int) skala); d.writeLong(dauer);
        } else {
            d.writeInt(0); d.writeInt(0); d.writeInt((int) skala); d.writeInt((int) dauer);
        }
        d.write(new byte[80]);
        byte[] mvhd = kasten("mvhd", mv.toByteArray());
        byte[] moov = kasten("moov", mvhd);
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write(kasten("ftyp", "isom0000".getBytes("ISO-8859-1")));
        if (!moovAmEnde) f.write(moov);
        f.write(kasten("mdat", new byte[300]));
        if (moovAmEnde) f.write(moov);
        File out = tmp.newFile();
        Files.write(out.toPath(), f.toByteArray());
        return out;
    }

    private static byte[] kasten(String typ, byte[] inhalt) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        d.writeInt(8 + inhalt.length);
        d.write(typ.getBytes("ISO-8859-1"));
        d.write(inhalt);
        return b.toByteArray();
    }

    @Test
    public void dauerAusDemMp4Kopf() throws Exception {
        assertEquals(40.5, HaDaten.dauerSek(mp4(0, 1000, 40_500, false)), 1e-9);
        assertEquals(40.0, HaDaten.dauerSek(mp4(0, 90_000, 3_600_000, true)), 1e-9);
        assertEquals(39.9, HaDaten.dauerSek(mp4(1, 1000, 39_900, false)), 1e-9);
        assertNull("fragmentiert, Dauer 0", HaDaten.dauerSek(mp4(0, 1000, 0, false)));
        File kaputt = tmp.newFile();
        Files.write(kaputt.toPath(), new byte[] {0, 0, 0, 3, 'x'});
        assertNull(HaDaten.dauerSek(kaputt));
        assertNull(HaDaten.dauerSek(new File(tmp.getRoot(), "fehlt.mp4")));
        assertNull(HaDaten.dauerSek(null));
    }

    // ---------------------------------------------------------------- Meldungen

    private static JSONObject auto(int... typen) throws Exception {
        JSONArray m = new JSONArray();
        for (int t : typen) m.put(new JSONObject().put("typ", t).put("schluessel", t == 13 ? "reifendruck" : "abs"));
        return new JSONObject().put("ok", true).put("verfuegbar", true).put("meldungen", m).put("stand", 5L);
    }

    @Test
    public void meldungenMitZeitCodeText() throws Exception {
        JSONObject katalog = new JSONObject().put("auto_meldungen", new JSONObject().put("typ",
                new JSONObject().put("reifendruck", "Reifendruck").put("abs", "ABS")));
        HaDaten.MeldungsVerlauf v = new HaDaten.MeldungsVerlauf();
        JSONObject a = v.aktualisieren(auto(13), 1000, s -> HaDaten.text(katalog, "auto_meldungen.typ." + s));
        assertTrue(a.getBoolean("verfuegbar"));
        assertEquals(1, a.getInt("anzahl"));
        JSONObject e = a.getJSONArray("liste").getJSONObject(0);
        assertEquals(1000, e.getLong("zeit"));
        assertEquals(13, e.getInt("code"));
        assertEquals("reifendruck", e.getString("schluessel"));
        assertEquals("Reifendruck", e.getString("text"));
        assertEquals("erstes Lesen: kein „kommt“ (Zeitpunkt unbekannt)", 0, a.getJSONArray("verlauf").length());

        JSONObject b = v.aktualisieren(auto(13, 6), 2000, s -> HaDaten.text(katalog, "auto_meldungen.typ." + s));
        assertEquals(2, b.getInt("anzahl"));
        assertEquals("seit bleibt", 1000, b.getJSONArray("liste").getJSONObject(0).getLong("zeit"));
        assertEquals(2000, b.getJSONArray("liste").getJSONObject(1).getLong("zeit"));
        assertEquals("kommt", b.getJSONArray("verlauf").getJSONObject(0).getString("ereignis"));

        JSONObject c = v.aktualisieren(auto(6), 3000, null);
        assertEquals(1, c.getInt("anzahl"));
        JSONObject geht = c.getJSONArray("verlauf").getJSONObject(0);
        assertEquals("neueste zuerst", "geht", geht.getString("ereignis"));
        assertEquals(13, geht.getInt("code"));
        assertEquals("ohne Katalog: Schlüssel als Text", "reifendruck", geht.getString("text"));
    }

    @Test
    public void meldungenNichtVerfuegbar() throws Exception {
        HaDaten.MeldungsVerlauf v = new HaDaten.MeldungsVerlauf();
        JSONObject a = v.aktualisieren(new JSONObject().put("verfuegbar", false), 1, null);
        assertFalse(a.getBoolean("verfuegbar"));
        assertEquals(0, a.getInt("anzahl"));
        assertFalse(v.aktualisieren(null, 2, null).getBoolean("verfuegbar"));
    }

    @Test
    public void verlaufBegrenzt() throws Exception {
        HaDaten.MeldungsVerlauf v = new HaDaten.MeldungsVerlauf();
        v.aktualisieren(auto(), 0, null);
        for (int i = 1; i <= 30; i++) v.aktualisieren(i % 2 == 1 ? auto(6) : auto(), i, null);
        JSONObject a = v.aktualisieren(auto(), 99, null);
        assertEquals(HaDaten.MeldungsVerlauf.MAX_VERLAUF, a.getJSONArray("verlauf").length());
    }

    // ---------------------------------------------------------------- Zugang, Regeln

    @Test
    public void datenNurLesendFuerKopplungen() {
        assertTrue(ExternGuard.erlaubt("GET", "/api/ha/v1/daten"));
        assertFalse(ExternGuard.erlaubt("POST", "/api/ha/v1/daten"));
        assertFalse(ExternGuard.erlaubt("PUT", "/api/ha/v1/daten"));
    }

    @Test
    public void quelleOhneVinBenutzerOderBefehle() throws Exception {
        String h = new String(Files.readAllBytes(quelle("server/HaApiHandler.java").toPath()), "UTF-8");
        int a = h.indexOf("// ------------------------------------------------------------------ daten (0.1.43.21)");
        int b = h.indexOf("private static Object nullSafe");
        assertTrue(a > 0 && b > a);
        String block = h.substring(a, b).toLowerCase(java.util.Locale.ROOT);
        for (String verboten : new String[] {"vin", "benutzer", "username", "konto", "befehl", "router", "lat", "lon"}) {
            assertFalse("daten-Block enthält „" + verboten + "“", block.matches("(?s).*\\b" + verboten + "\\b.*"));
        }
        String d = new String(Files.readAllBytes(quelle("ha/HaDaten.java").toPath()), "UTF-8");
        assertFalse(d.contains("VehicleCommandRouter") || d.contains("service call"));
    }

    private static File quelle(String rel) {
        File f = new File("src/main/java/at/greenchili/surfmate/" + rel);
        return f.isFile() ? f : new File("app/src/main/java/at/greenchili/surfmate/" + rel);
    }
}
