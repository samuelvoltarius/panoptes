package at.greenchili.surfmate.einrichtung;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Paths;

/** H3 (0.1.10, Koordination 22.10.): Band wie bisher, Ton nie übersteuert, einmaliger Eintrag „Noch offen“. */
public class ProjektionsHinweisTest {

    private static JSONObject pakete() throws Exception {
        return new JSONObject(new String(java.nio.file.Files.readAllBytes(
                Paths.get("src/main/assets/web/shared/faehigkeiten_belegt.json")),
                java.nio.charset.StandardCharsets.UTF_8)).getJSONObject("projektion_pakete");
    }

    @Test
    public void projektionErkennen() throws Exception {
        JSONObject p = pakete();
        assertEquals("androidauto", ProjektionsHinweis.aktiv(
                "  mResumedActivity: ActivityRecord{1 u0 com.byd.androidauto/.MainActivity t5}", p));
        assertEquals("androidauto", ProjektionsHinweis.aktiv(
                "  mResumedActivity: ActivityRecord{1 u0 com.google.android.projection.gearhead/.CarActivity t5}", p));
        assertEquals("carplay", ProjektionsHinweis.aktiv(
                "  topResumedActivity=ActivityRecord{2 u0 com.byd.carplay.ui/.MainActivity t7}", p));
        assertNull("nicht in der Tabelle", ProjektionsHinweis.aktiv(
                "  topResumedActivity=ActivityRecord{2 u0 com.byd.carplay.ui.extra/.X t7}", p));
        assertNull(ProjektionsHinweis.aktiv("  mResumedActivity: ActivityRecord{3 u0 com.byd.launcher/.Home t1}", p));
        assertNull(ProjektionsHinweis.aktiv(null, p));
        assertNull("ohne Tabelle nichts", ProjektionsHinweis.aktiv(
                "  mResumedActivity: ActivityRecord{1 u0 com.byd.androidauto/.MainActivity t5}", null));
    }

    @Test
    public void nurBeiTonAusUndUnbelegtUndNurEinmal() throws Exception {
        JSONObject root = new JSONObject();
        assertTrue(ProjektionsHinweis.merken(root, "carplay", "unbekannt", "aus"));
        assertFalse("Ton gewählt: kein Eintrag", ProjektionsHinweis.merken(root, "carplay", "unbekannt", "hinweiston"));
        assertFalse("belegt sichtbar", ProjektionsHinweis.merken(root, "carplay", "ja", "aus"));
        assertFalse("keine Projektion", ProjektionsHinweis.merken(root, null, "unbekannt", "aus"));
        root.put("einrichtung", new JSONObject().put("projektionUnbekannt", new JSONObject().put("carplay", 5L)));
        assertFalse("einmalig", ProjektionsHinweis.merken(root, "carplay", "unbekannt", "aus"));
        assertTrue("andere Art einmal", ProjektionsHinweis.merken(root, "androidauto", "unbekannt", "aus"));
    }

    @Test
    public void nochOffenBisAngabeOderTon() throws Exception {
        JSONObject root = new JSONObject()
                .put("einrichtung", new JSONObject().put("projektionUnbekannt", new JSONObject().put("carplay", 5L)));
        JSONArray o = ProjektionsHinweis.offen(root, "aus");
        assertEquals(1, o.length());
        assertEquals("einr.hinweis.projektion_unbekannt.carplay", o.getJSONObject(0).getString("textKey"));
        assertEquals("[\"faehigkeiten.ueberCarPlay\",\"speedcam.ton\"]", o.getJSONObject(0).getJSONArray("einstellungen").toString());
        assertEquals(0, ProjektionsHinweis.offen(root, "hinweiston").length());
        root.put("faehigkeiten", new JSONObject().put("ueberCarPlay", "nein"));
        assertEquals("Angabe gemacht", 0, ProjektionsHinweis.offen(root, "aus").length());
    }

    @Test
    public void selbsttestPunkt() throws Exception {
        assertEquals("entfaellt", Selbsttest.projektion(new JSONObject(), null, "aus"));
        JSONObject root = new JSONObject()
                .put("einrichtung", new JSONObject().put("projektionUnbekannt", new JSONObject().put("carplay", 5L)));
        assertEquals("ungeprueft", Selbsttest.projektion(root, null, "aus"));
        assertEquals("ok", Selbsttest.projektion(root, null, "hinweiston"));
        JSONObject caps = new JSONObject().put("ueberProjektion", new JSONObject().put("carplay", "ja"));
        assertEquals("ok", Selbsttest.projektion(root, caps, "aus"));
        root.put("faehigkeiten", new JSONObject().put("ueberCarPlay", "nein"));
        assertEquals("Angabe nein + Ton aus", "fehlt", Selbsttest.projektion(root, caps, "aus"));
    }

    @Test
    public void tonWirdNieUebersteuert() throws Exception {
        String b = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/blitzer/BlitzerDienst.java"));
        int nach = b.indexOf("ProjektionsHinweis.nachBand(ton)");
        assertTrue(nach > b.indexOf("showBand(titel"));
        String h = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/einrichtung/ProjektionsHinweis.java"));
        assertFalse(h.contains("speak(") || h.contains("playRawResource") || h.contains("showBand"));
    }
}
