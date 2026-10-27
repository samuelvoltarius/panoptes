package at.greenchili.surfmate.ladekurve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.analytics.Mitzaehlen;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** 0.1.19 Ladekurve: Bänder, Median je Ladung, Qualität, Startkurve, geteilte Zusammenfassung ohne Ort/Zeit/VIN. */
public class LadekurveTest {

    /** DC-Ladung 20→80 % mit fallender Leistung (Schätzer aus der SoC-Steigung wie am Surf). */
    private static List<Ladekurve.Punkt> ladung(double kwBei20, boolean gemessen) {
        List<Ladekurve.Punkt> l = new ArrayList<>();
        for (double soc = 20; soc < 80; soc += 0.5) l.add(new Ladekurve.Punkt(soc, kwBei20 - (soc - 20) * 0.3, gemessen));
        return l;
    }

    @Test
    public void baenderUndMedianJeLadung() throws Exception {
        assertEquals(0, Ladekurve.band(0));
        assertEquals(20, Ladekurve.band(24.9));
        assertEquals(95, Ladekurve.band(100));
        JSONObject s = Ladekurve.sitzung(1000, ladung(40, false));
        assertEquals(Ladekurve.Q_GESCHAETZT, s.getString("q"));
        JSONObject b = s.getJSONObject("b");
        assertEquals(12, b.length());
        assertEquals("Median Band 20–25 %", 40 - 2.25 * 0.3, b.getDouble("20"), 0.11);
        assertFalse(b.has("80"));
    }

    @Test
    public void ungueltigeUndZuWenigePunkteFallenWeg() throws Exception {
        List<Ladekurve.Punkt> l = new ArrayList<>();
        l.add(new Ladekurve.Punkt(50, 0.2, true));      // < 1 kW
        l.add(new Ladekurve.Punkt(50, 900, true));      // > 400 kW
        l.add(new Ladekurve.Punkt(Double.NaN, 40, true));
        l.add(new Ladekurve.Punkt(52, 40, true));       // nur 1 Punkt im Band
        assertNull(Ladekurve.sitzung(1, l));
    }

    @Test
    public void gemessenSchlaegtGeschaetztJeBand() throws Exception {
        JSONObject sp = Ladekurve.leer();
        Ladekurve.aufnehmen(sp, Ladekurve.sitzung(1, ladung(40, false)));
        Ladekurve.aufnehmen(sp, Ladekurve.sitzung(2, ladung(42, false)));
        JSONArray k = Ladekurve.kurve(sp, 2);
        assertEquals(Ladekurve.Q_GESCHAETZT, k.getJSONObject(0).getString("qualitaet"));
        Ladekurve.aufnehmen(sp, Ladekurve.sitzung(3, ladung(60, true)));
        Ladekurve.aufnehmen(sp, Ladekurve.sitzung(4, ladung(62, true)));
        k = Ladekurve.kurve(sp, 2);
        JSONObject b20 = k.getJSONObject(0);
        assertEquals(20, b20.getInt("soc_von"));
        assertEquals(25, b20.getInt("soc_bis"));
        assertEquals(Ladekurve.Q_GEMESSEN, b20.getString("qualitaet"));
        assertEquals(2, b20.getInt("n"));
        assertTrue(b20.getDouble("kw") > 55);
    }

    @Test
    public void startkurveAusDurchschnittUndHoechstzahl() throws Exception {
        JSONObject s = Ladekurve.ausDurchschnitt(5, 21, 79, 37.1);
        assertEquals(Ladekurve.Q_SITZUNG, s.getString("q"));
        assertTrue(s.getJSONObject("b").has("25"));
        assertFalse("21 % liegt nicht ganz im Band 20–25", s.getJSONObject("b").has("20"));
        assertFalse(s.getJSONObject("b").has("75"));
        assertNull(Ladekurve.ausDurchschnitt(5, 50, 52, 37));
        JSONObject sp = Ladekurve.leer();
        for (int i = 0; i < Ladekurve.MAX_SITZUNGEN + 5; i++) Ladekurve.aufnehmen(sp, Ladekurve.ausDurchschnitt(i, 20, 80, 40));
        assertEquals(Ladekurve.MAX_SITZUNGEN, Ladekurve.ladungen(sp));
        assertEquals("älteste fallen weg", 5, sp.getJSONArray("sitzungen").getJSONObject(0).getLong("start"));
    }

    @Test
    public void zusammenfassungNurModellBaenderMedianAnzahl() throws Exception {
        JSONObject sp = Ladekurve.leer();
        for (int i = 1; i <= 3; i++) Ladekurve.aufnehmen(sp, Ladekurve.sitzung(1761000000000L + i, ladung(40 + i, false)));
        JSONObject z = Ladekurve.zusammenfassung(sp, "BYD Dolphin Surf Comfort", "k-1", "0.1.19");
        assertNotNull(z);
        String text = z.toString();
        assertFalse("keine Zeit", text.contains("1761000000"));
        assertFalse(text.contains("start"));
        for (String verboten : new String[] {"lat", "lon", "vin", "VIN", "ort", "zeit", "datum"}) {
            assertFalse(verboten, z.has(verboten));
        }
        assertEquals("BYD Dolphin Surf Comfort", z.getString("fahrzeug"));
        assertEquals("panoptes", z.getString("quelle"));
        assertEquals(3, z.getInt("ladungen"));
        JSONObject b = z.getJSONArray("baender").getJSONObject(0);
        assertEquals(5, b.length());   // soc_von, soc_bis, kw, n, qualitaet
        assertNull("ohne Modell kein Teilen", Ladekurve.zusammenfassung(sp, null, "k-1", "0.1.19"));
        JSONObject wenig = Ladekurve.leer();
        Ladekurve.aufnehmen(wenig, Ladekurve.sitzung(1, ladung(40, false)));
        assertNull("eine Ladung reicht nicht", Ladekurve.zusammenfassung(wenig, "BYD Seal", "k", "0.1.19"));
    }

    // ---------------------------------------------------------------- Teilen (Einwilligung)

    static final class Cfg implements Mitzaehlen.Speicher {
        JSONObject z = new JSONObject();
        public JSONObject lese() { return z; }
        public void schreibe(JSONObject teil) {
            try {
                java.util.Iterator<String> it = teil.keys();
                while (it.hasNext()) { String k = it.next(); z.put(k, teil.get(k)); }
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    private static JSONObject dreiLadungen() throws Exception {
        JSONObject sp = Ladekurve.leer();
        for (int i = 1; i <= 3; i++) Ladekurve.aufnehmen(sp, Ladekurve.sitzung(i, ladung(40 + i, false)));
        return sp;
    }

    @Test
    public void standardAusSendetNichts() throws Exception {
        Cfg c = new Cfg();
        List<String> gesendet = new ArrayList<>();
        LadekurveTeilen.takt(0, "0.1.19", dreiLadungen(), "BYD Seal", c, (u, j) -> { gesendet.add(j); return 200; });
        assertTrue(gesendet.isEmpty());
        assertEquals("", c.z.optString("kennung"));
    }

    @Test
    public void anSendetEinmalProTagUndNurBeiAenderung() throws Exception {
        Cfg c = new Cfg();
        c.z.put("teilen", true);
        List<String> gesendet = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        Mitzaehlen.Sender s = (u, j) -> { urls.add(u); gesendet.add(j); return 200; };
        JSONObject sp = dreiLadungen();
        long tag1 = 1761523200000L;
        LadekurveTeilen.takt(tag1, "0.1.19", sp, "BYD Seal", c, s);
        assertEquals(1, gesendet.size());
        assertEquals(LadekurveTeilen.STANDARD_URL, urls.get(0));
        assertFalse("anonym: keine Mitglieds-Kennung im Body", gesendet.get(0).contains("Member"));
        LadekurveTeilen.takt(tag1 + 3600_000L, "0.1.19", sp, "BYD Seal", c, s);
        assertEquals("gleicher Tag", 1, gesendet.size());
        LadekurveTeilen.takt(tag1 + 86_400_000L, "0.1.19", sp, "BYD Seal", c, s);
        assertEquals("unverändert: nichts", 1, gesendet.size());
        Ladekurve.aufnehmen(sp, Ladekurve.sitzung(9, ladung(55, true)));
        LadekurveTeilen.takt(tag1 + 2 * 86_400_000L, "0.1.19", sp, "BYD Seal", c, s);
        assertEquals("neue Ladung, neuer Tag", 2, gesendet.size());
    }

    @Test
    public void ausschaltenLoeschtAmServerBisZurAntwort() throws Exception {
        Cfg c = new Cfg();
        c.z.put("teilen", false).put("kennung", "k-alt");
        List<String> gesendet = new ArrayList<>();
        LadekurveTeilen.takt(0, "0.1.19", dreiLadungen(), "BYD Seal", c, (u, j) -> { throw new java.io.IOException("offline"); });
        assertEquals("", c.z.getString("kennung"));
        assertEquals("k-alt", c.z.getString("loeschen"));
        LadekurveTeilen.takt(1, "0.1.19", dreiLadungen(), "BYD Seal", c, (u, j) -> { gesendet.add(j); return 200; });
        assertEquals(1, gesendet.size());
        JSONObject b = new JSONObject(gesendet.get(0));
        assertEquals("k-alt", b.getString("kennung"));
        assertTrue(b.getBoolean("loeschen"));
        assertEquals("", c.z.getString("loeschen"));
    }

    // ---------------------------------------------------------------- Verdrahtung

    @Test
    public void samplerUndTaktSindVerdrahtetOhneDiePreisreiheZuBeruehren() throws Exception {
        String csm = lies("app/src/main/java/at/greenchili/surfmate/charging/ChargingSessionManager.java");
        int probe = csm.indexOf("LadekurveDienst.probe(sessionStart, socPct, power, cs,");
        int schaetzSperre = csm.indexOf("|| cs.isEstimated) {");
        assertTrue("Probe vor der Schätz-Sperre der Energiereihe", probe > 0 && probe < schaetzSperre);
        assertTrue(csm.contains("LadekurveDienst.sitzungEnde();"));
        String cam = lies("app/src/main/java/at/greenchili/surfmate/daemon/CameraDaemon.java");
        assertTrue(cam.contains("LadekurveDienst.taktImDaemon(System.currentTimeMillis())"));
        String kat = lies("app/src/main/java/at/greenchili/surfmate/einstellungen/Katalog.java");
        int f = kat.indexOf("new Feld(\"ladekurve.teilen\", Feld.Typ.BOOL)");
        assertTrue(f > 0);
        String feld = kat.substring(f, kat.indexOf("l.add(kurve);", f));
        assertTrue("Standard aus", feld.contains("kurve.standard = false"));
        assertTrue("Datenschutz-Gruppe (Einrichten „Daten teilen“)", feld.contains("kurve.gruppe = \"datenschutz\""));
        assertTrue(feld.contains("kurve.bestaetigung = \"planer.ladekurveEinwilligung\""));
        String dienst = lies("app/src/main/java/at/greenchili/surfmate/ladekurve/LadekurveDienst.java");
        assertFalse("anonym: kein Planer-Schlüssel", dienst.contains("X-Member-Token") || dienst.contains("PlanerSchluessel"));
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject d = new JSONObject(lies("app/src/main/assets/server-i18n/" + l + ".json"));
            assertFalse(l, d.getJSONObject("settings").getJSONObject("ladekurve").getJSONObject("teilen").optString("title").isEmpty());
            assertFalse(l, d.getJSONObject("planer").getJSONObject("ladekurveEinwilligung").optString("text").isEmpty());
        }
    }

    private static String lies(String rel) throws Exception {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (p != null) {
            for (Path c : new Path[] {p.resolve(rel), p.resolve(rel.replaceFirst("^app/", ""))}) {
                if (Files.isRegularFile(c)) return new String(Files.readAllBytes(c), "UTF-8");
            }
            p = p.getParent();
        }
        throw new java.io.FileNotFoundException(rel);
    }
}
