package at.greenchili.surfmate.camera.dilink5;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.rueckmeldung.FehlerMelden;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** K2 (0.1.38.11): Kamera-Eingang erkennen - eine Stelle, belegte Signale, nie still umschalten. */
public class AutoArtErkennungTest {

    private static void pruefe(AutoArtErkennung.Ergebnis e, String modus, Integer dilink, String sicherheit) {
        assertEquals(modus, e.modus);
        assertEquals(dilink, e.dilink);
        assertEquals(sicherheit, e.sicherheit);
    }

    @Test
    public void erkennungsMatrix() {
        // Surf DiLink 3 / Android 10
        pruefe(AutoArtErkennung.erkennen(false, 29, "seagull"), "default", 3, AutoArtErkennung.SICHER);
        pruefe(AutoArtErkennung.erkennen(false, 29, null), "default", 3, AutoArtErkennung.VERMUTLICH);
        pruefe(AutoArtErkennung.erkennen(false, 28, "seal-u-dmi"), "default", 3, AutoArtErkennung.VERMUTLICH);
        // Android >= 11 ohne libais
        pruefe(AutoArtErkennung.erkennen(false, 30, null), "dilink4", 4, AutoArtErkennung.VERMUTLICH);
        pruefe(AutoArtErkennung.erkennen(false, 31, "seal-u-dmi"), "dilink4", 4, AutoArtErkennung.VERMUTLICH);
        pruefe(AutoArtErkennung.erkennen(false, 33, "sealion7"), null, null, AutoArtErkennung.UNBEKANNT);
        // mit libais (QCarCam) - immer DiLink 5, auch bei Android 10 oder anderem Modell
        pruefe(AutoArtErkennung.erkennen(true, 33, null), "dilink5", 5, AutoArtErkennung.SICHER);
        pruefe(AutoArtErkennung.erkennen(true, 29, "seagull"), "dilink5", 5, AutoArtErkennung.SICHER);
        // ohne Android-Version: nichts raten
        pruefe(AutoArtErkennung.erkennen(false, 0, "seagull"), null, null, AutoArtErkennung.UNBEKANNT);
    }

    @Test
    public void vorschlagNurBeiWiderspruch() throws Exception {
        JSONObject passt = AutoArtErkennung.status(AutoArtErkennung.erkennen(false, 29, "seagull"), "default");
        assertTrue(passt.getBoolean("passt"));
        assertTrue(passt.isNull("vorschlag"));
        JSONObject falsch = AutoArtErkennung.status(AutoArtErkennung.erkennen(false, 31, null), "default");
        assertFalse(falsch.getBoolean("passt"));
        assertEquals("dilink4", falsch.getString("vorschlag"));
        assertEquals("vermutlich", falsch.getString("sicherheit"));
        assertEquals("default", falsch.getString("gespeichert"));
        JSONObject di5 = AutoArtErkennung.status(AutoArtErkennung.erkennen(true, 33, null), "dilink4");
        assertEquals("dilink5", di5.getString("vorschlag"));
        // unbekannt -> kein Vorschlag, egal was gespeichert ist
        JSONObject unb = AutoArtErkennung.status(AutoArtErkennung.erkennen(false, 0, null), "dilink5");
        assertTrue(unb.getBoolean("passt"));
        assertTrue(unb.isNull("vorschlag"));
        // ungueltiger gespeicherter Wert zaehlt als default
        assertEquals("default", AutoArtErkennung.status(AutoArtErkennung.erkennen(false, 29, null), "kaputt")
                .getString("gespeichert"));
    }

    @Test
    public void fehlerMeldenNimmtNurDieKurzform() throws Exception {
        assertEquals("dilink4/vermutlich", AutoArtErkennung.kurz(AutoArtErkennung.erkennen(false, 31, null)));
        assertEquals("unbekannt/unbekannt", AutoArtErkennung.kurz(AutoArtErkennung.erkennen(false, 0, null)));
        JSONObject k = FehlerMelden.koerper("x", "0.1.38.11", 3, "Dolphin Surf", "de", null, null, false, null,
                "default/sicher");
        assertEquals("default/sicher", k.getString("erkannt"));
        assertFalse(FehlerMelden.koerper("x", "0.1.38.11", 3, "Dolphin Surf", "de", null, null, false, null,
                "irgendwas mit Ort 47.1").has("erkannt"));
        assertFalse("altes Format ohne erkannt", FehlerMelden.koerper("x", "0.1.38.11", 3, "Seal", "de", null)
                .has("erkannt"));
    }

    private static String lies(String rel) throws Exception {
        Path cur = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (cur != null) {
            for (Path c : new Path[] {cur.resolve(rel), cur.resolve("app").resolve(rel)}) {
                if (Files.isRegularFile(c)) return new String(Files.readAllBytes(c), StandardCharsets.UTF_8);
            }
            cur = cur.getParent();
        }
        throw new AssertionError("fehlt: " + rel);
    }

    @Test
    public void nieStillUmschalten() throws Exception {
        String e = lies("src/main/java/at/greenchili/surfmate/camera/dilink5/AutoArtErkennung.java");
        for (String verboten : new String[] {"stageConfiguredMode(", "updateSection(", "updateValues(", "saveConfig",
                "writeMode(", "activateConfiguredMode("}) {
            assertFalse("Erkennung schreibt nie: " + verboten, e.contains(verboten));
        }
        String api = lies("src/main/java/at/greenchili/surfmate/server/SurveillanceApiHandler.java");
        assertTrue(api.contains("cleanPath.equals(\"/api/surveillance/autoart\") && method.equals(\"GET\")"));
        // Umstellen nur nach Tipp + Rueckfrage, ueber denselben Speichern-und-Neustart-Weg
        String act = lies("src/main/java/at/greenchili/surfmate/ui/MainActivity.kt");
        String f = act.substring(act.indexOf("fun kameraModusVorschlagUmstellen("));
        f = f.substring(0, f.indexOf("\n    }\n") > 0 ? f.indexOf("\n    }\n") : f.length());
        assertTrue(f.contains("setPositiveButton(R.string.camera_mode_umstellen_ja)"));
        assertTrue(f.indexOf("setPositiveButton") < f.indexOf("postSurveillanceConfig("));
        assertTrue(f.contains("restartCameraDaemonForCameraSettings()"));
        assertTrue(act.contains("dialogView.findViewById<View>(R.id.btnSaveCameraMode)?.performClick()"));
        String br = lies("src/main/java/at/greenchili/surfmate/ui/fragment/WebViewFragment.kt");
        assertTrue(br.contains("if (m != \"default\" && m != \"dilink4\" && m != \"dilink5\") return \"ungueltig\""));
        assertFalse("nicht von aussen", lies("src/main/java/at/greenchili/surfmate/server/ExternGuard.java")
                .contains("/api/surveillance/autoart"));
    }

    @Test
    public void texteInFuenfSprachen() throws Exception {
        for (String d : new String[] {"values", "values-de", "values-fr", "values-it", "values-nl"}) {
            String x = lies("src/main/res/" + d + "/strings.xml");
            for (String k : new String[] {"camera_mode_erkannt_hinweis", "camera_mode_vorschlag_knopf", "camera_mode_sicher",
                    "camera_mode_vermutlich", "camera_mode_kurz_default", "camera_mode_kurz_dilink4",
                    "camera_mode_kurz_dilink5", "camera_mode_umstellen_frage", "camera_mode_umstellen_ja"}) {
                assertTrue(d + " " + k, x.contains("<string name=\"" + k + "\">"));
            }
        }
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject j = new JSONObject(lies("src/main/assets/web/i18n/" + l + ".json"));
            JSONObject g = j.getJSONObject("guard");
            for (String k : new String[] {"hinweis", "knopf", "zurueck", "sicher", "vermutlich", "nur_auto", "frage",
                    "name_default", "name_dilink4", "name_dilink5"}) {
                assertTrue(l + " guard.autoart_" + k, g.has("autoart_" + k));
            }
            assertTrue(g.getString("autoart_knopf").contains("{erkannt}"));
            assertTrue(j.getJSONObject("rueckmeldung").has("auto_erkannt"));
        }
        assertNull(null);
    }
}
