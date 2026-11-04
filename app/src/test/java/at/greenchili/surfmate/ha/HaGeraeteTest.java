package at.greenchili.surfmate.ha;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.auth.Kopplungen;
import at.greenchili.surfmate.server.ExternGuard;
import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Paths;

/** HA_VERTRAG v1.1 Teil D, Stufe 1 (lesen) und Prüfung der Stufe 2: Sperrliste, Freigaben, Recht, Pfade. */
public class HaGeraeteTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static JSONObject g(String entity, String art) throws JSONException {
        return new JSONObject().put("entity", entity).put("art", art).put("titel", "T " + entity).put("zustand", "on");
    }

    private static String rumpf(JSONObject... geraete) throws JSONException {
        JSONArray a = new JSONArray();
        for (JSONObject x : geraete) a.put(x);
        return new JSONObject().put("v", 1).put("geraete", a).toString();
    }

    @Test
    public void nieAlarmSireneMelderTorNurAlsGaragentor() throws Exception {
        HaGeraete.Meldung m = HaGeraete.pruefe(rumpf(
                g("alarm_control_panel.haus", "schalter"), g("siren.garten", "schalter"),
                g("switch.gas", "schalter").put("deviceClass", "gas"), g("switch.rauch", "schalter").put("deviceClass", "smoke"),
                g("cover.garage", "tor").put("deviceClass", "garage"), g("lock.haustuer", "schloss"),
                g("cover.rollo", "tor").put("deviceClass", "shutter"), g("cover.markise", "tor"),
                g("cover.einfahrt", "schalter").put("deviceClass", "gate"), g("switch.tor", "tor"),
                g("lock.x", "schalter"),
                g("sensor.wb_leistung", "wallbox"),
                g("switch.steckdose", "schalter")));
        assertEquals(3, m.geraete.length());
        assertEquals("cover.garage", m.geraete.getJSONObject(0).getString("entity"));
        assertEquals("lock.haustuer", m.geraete.getJSONObject(1).getString("entity"));
        assertEquals("switch.steckdose", m.geraete.getJSONObject(2).getString("entity"));
        assertEquals(10, m.verworfen);
    }

    @Test
    public void ungueltigeKennungUnbekannteArtDoppelte() throws Exception {
        HaGeraete.Meldung m = HaGeraete.pruefe(rumpf(
                g("Switch.GROSS", "schalter"), g("switch.a;rm", "schalter"), g("light.flur", "fernseher"),
                g("light.flur", "licht"), g("light.flur", "licht")));
        assertEquals(1, m.geraete.length());
        assertEquals(4, m.verworfen);
    }

    @Test
    public void nurBekannteFelderUndGrenzen() throws Exception {
        JSONObject roh = g("climate.bad", "klima").put("titel", "x\u0007" + new String(new char[100]).replace('\0', 'y'))
                .put("raum", "Bad").put("geheim", "nicht übernehmen")
                .put("faehigkeiten", new JSONObject().put("modi", new JSONArray().put("off").put("heat").put("BÖSE"))
                        .put("minTemp", 7).put("maxTemp", 30).put("soll", 21.5).put("roh", "weg"));
        JSONObject k = HaGeraete.pruefe(rumpf(roh)).geraete.getJSONObject(0);
        assertFalse(k.has("geheim"));
        assertEquals(60, k.getString("titel").length());
        assertFalse("Steuerzeichen weg", k.getString("titel").contains("\u0007"));
        assertEquals("Bad", k.getString("raum"));
        JSONObject f = k.getJSONObject("faehigkeiten");
        assertEquals(2, f.getJSONArray("modi").length());
        assertFalse(f.has("roh"));
        assertEquals(21.5, f.getDouble("soll"), 0.001);
    }

    @Test
    public void hoechstens200UndGroesse() throws Exception {
        JSONObject[] viele = new JSONObject[250];
        for (int i = 0; i < viele.length; i++) viele[i] = g("switch.s" + i, "schalter");
        HaGeraete.Meldung m = HaGeraete.pruefe(rumpf(viele));
        assertEquals(HaGeraete.MAX_GERAETE, m.geraete.length());
        assertEquals(50, m.verworfen);
        try {
            HaGeraete.pruefe(new String(new char[HaGeraete.MAX_BYTES + 1]).replace('\0', ' '));
            throw new AssertionError("zu groß angenommen");
        } catch (JSONException erwartet) { /* ok */ }
        try {
            HaGeraete.pruefe(new JSONObject().put("v", 2).put("geraete", new JSONArray()).toString());
            throw new AssertionError("falsche Version angenommen");
        } catch (JSONException erwartet) { /* ok */ }
    }

    @Test
    public void neueGeraeteSindAusFreigabeBleibtNurFuerGemeldete() throws Exception {
        HaGeraete.Meldung m1 = HaGeraete.pruefe(rumpf(g("switch.a", "schalter"), g("light.b", "licht")));
        JSONObject b1 = HaGeraete.zusammenfuehren(null, m1, 1L);
        assertEquals(0, b1.getJSONObject("freigaben").length());
        // Nutzer hat in Panoptes switch.a freigegeben (ab Stufe 2), dann meldet HA light.b nicht mehr
        b1.getJSONObject("freigaben").put("switch.a", true).put("light.b", true);
        HaGeraete.Meldung m2 = HaGeraete.pruefe(rumpf(g("switch.a", "schalter"), g("fan.c", "luefter")));
        JSONObject b2 = HaGeraete.zusammenfuehren(b1, m2, 2L);
        assertTrue(b2.getJSONObject("freigaben").optBoolean("switch.a"));
        assertFalse("weggefallen = Freigabe weg", b2.getJSONObject("freigaben").has("light.b"));
        assertFalse("neu = aus", b2.getJSONObject("freigaben").has("fan.c"));

        JSONObject fi = HaGeraete.fuerIntegration(b2);
        assertEquals(1, fi.getJSONArray("freigaben").length());
        assertEquals("switch.a", fi.getJSONArray("freigaben").getJSONObject(0).getString("entity"));
        assertFalse("keine Namen an HA zurück", fi.toString().contains("T switch.a"));
        String d = fi.getJSONArray("domaenen").toString();
        assertFalse(d.contains("alarm") || d.contains("siren"));
        assertTrue("Stufe 2: Tor und Schloss", d.contains("\"cover\"") && d.contains("\"lock\""));
        JSONObject anz = HaGeraete.fuerAnzeige(b2);
        assertEquals(2, anz.getJSONArray("geraete").length());
        assertTrue(anz.getJSONArray("geraete").getJSONObject(0).has("steuern"));
    }

    @Test
    public void speicherIst0600UndLoeschbar() throws Exception {
        File f = new File(tmp.getRoot(), "ha_geraete.json");
        HaGeraeteSpeicher sp = new HaGeraeteSpeicher(f);
        assertNull(sp.lies());
        assertTrue(sp.schreibe(new JSONObject().put("v", 1).put("kid", "k1")));
        assertEquals("k1", new HaGeraeteSpeicher(f).lies().getString("kid"));
        if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                    java.nio.file.Files.getPosixFilePermissions(f.toPath())));
        }
        sp.loeschen();
        assertFalse(f.exists());
        assertNull(sp.lies());
    }

    @Test
    public void rechtNurFuerHomeAssistantStandardAus() {
        assertTrue(Kopplungen.HA_RECHTE.contains(Kopplungen.RECHT_GERAETE));
        assertFalse(Kopplungen.FREMD_RECHTE.contains(Kopplungen.RECHT_GERAETE));
        assertFalse(Kopplungen.COCKPIT_RECHTE.contains(Kopplungen.RECHT_GERAETE));
        assertFalse("kein Handy-Recht", Kopplungen.RECHTE.contains(Kopplungen.RECHT_GERAETE));
    }

    @Test
    public void pfadNurFuerHomeAssistantMitRecht() {
        assertFalse(ExternGuard.blocks("GET", "/api/ha/v1/geraete", "homeassistant"));
        assertFalse(ExternGuard.blocks("POST", "/api/ha/v1/geraete", "homeassistant"));
        assertTrue(ExternGuard.blocks("DELETE", "/api/ha/v1/geraete", "homeassistant"));
        for (String art : new String[]{"cockpit", "handy", "dienst", "web"}) {
            assertTrue(art, ExternGuard.blocks("POST", "/api/ha/v1/geraete", art));
        }
        assertTrue("Anzeige nie für Kopplungen", ExternGuard.blocks("GET", "/api/ha/geraete", "homeassistant"));
        assertEquals(Kopplungen.RECHT_GERAETE, ExternGuard.noetigesRecht("/api/ha/v1/geraete"));
        assertFalse(ExternGuard.rechtReicht(Kopplungen.RECHT_GERAETE, r -> false));
        assertEquals("geraete", ExternGuard.rechtFehltAntwort(Kopplungen.RECHT_GERAETE).optString("recht"));
        assertTrue(at.greenchili.surfmate.server.WebHandyGuard.blocks("GET", "/api/ha/geraete"));
    }

    @Test
    public void handlerPrueftArtUndRechtUndVergisstBeimWiderruf() throws Exception {
        String h = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/HaApiHandler.java"));
        assertTrue(h.contains("!\"homeassistant\".equals(Kopplungen.get().art(kid))"));
        assertTrue(h.contains("!Kopplungen.get().hatRecht(kid, Kopplungen.RECHT_GERAETE)"));
        assertTrue("nie Namen ins Log", h.contains("BefehlsLog.ereignis(\"ha.geraete\", \"gemeldet\", null, null);"));
        String k = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/auth/Kopplungen.java"));
        assertTrue(k.contains("geraeteVergessen(id);   // 0.1.39: gemeldete HA-Geräte dieser Kopplung mit weg"));
        assertTrue(k.contains("!rechte.contains(RECHT_GERAETE))) {"));
    }
}
