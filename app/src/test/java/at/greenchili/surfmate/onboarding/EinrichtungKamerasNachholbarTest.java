package at.greenchili.surfmate.onboarding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.einstellungen.Katalog;
import at.greenchili.surfmate.server.EinrichtungApiHandler;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 0.1.38.3 (Community, BYD Seal U DM-i, Bestand aus 0.1.36): „Die Einrichtung der Kameras hat der Assistent von allein
 * übersprungen. War aber im Nachhinein nicht möglich.“ Kameras müssen immer nachholbar sein:
 * Bestand → „Noch einrichten“ statt „entfällt“, erledigt nur mit eigener Zuordnung; Web-Ziel und Knopf unter
 * Kameras &amp; Wächter; Klartext statt „Speichern hat nicht geklappt“, wenn die Kamera-Einstellungen nicht antworten.
 */
public class EinrichtungKamerasNachholbarTest {

    private static String status(JSONObject sicht, String id) throws Exception {
        JSONArray s = sicht.getJSONArray("schritte");
        for (int i = 0; i < s.length(); i++) if (id.equals(s.getJSONObject(i).getString("id"))) return s.getJSONObject(i).getString("status");
        throw new AssertionError(id);
    }

    private static JSONArray offenSpaeter(JSONObject sicht) throws Exception {
        return sicht.getJSONArray("offen_spaeter");
    }

    /** Bestand ohne gespeicherten Einrichtungs-Zustand (erste Einrichtungs-Abfrage nach dem Update). */
    private static JSONObject bestand(JSONObject camera) throws Exception {
        JSONObject root = new JSONObject()
                .put("nativeShell", new JSONObject().put("locale", "de").put("localeGewaehlt", true))
                .put("hinweise", new JSONObject().put("version", Katalog.HINWEISE_VERSION))
                .put("vehicle", new JSONObject().put("modelId", "atto3").put("modelSource", "user"))
                .put("surveillance", new JSONObject().put("triggerMode", "ai_everywhere"));
        if (camera != null) root.put("camera", camera);
        return root;
    }

    private static JSONObject sicht(JSONObject root) throws Exception {
        return EinrichtungApiHandler.sicht(root, EinrichtungApiHandler.zustand(root), null, null);
    }

    @Test
    public void bestandBekommtKamerasUnterNochEinrichtenStattEntfaellt() throws Exception {
        JSONObject s = sicht(bestand(null));
        assertEquals("spaeter", status(s, "kameras"));
        assertTrue(offenSpaeter(s).toString().contains("\"kameras\""));
    }

    @Test
    public void automatischeProbeZaehltNichtAlsEingerichtet() throws Exception {
        JSONObject s = sicht(bestand(new JSONObject().put("probedCameraId", 0).put("probedAndValidated", true)));
        assertEquals("spaeter", status(s, "kameras"));
    }

    @Test
    public void eigeneZuordnungIstErledigt() throws Exception {
        assertEquals("erledigt", status(sicht(bestand(new JSONObject().put("probedCameraId", 2).put("manualOverride", true))), "kameras"));
        assertEquals("erledigt", status(sicht(bestand(new JSONObject().put("dilink5CameraMapping", "a"))), "kameras"));
    }

    @Test
    public void nachholenImWebUndKlartextInDerApp() throws Exception {
        String web = lies("app/src/main/assets/web/shared/einrichtung-web.js");
        assertTrue(web.contains("kameras: 'waechter.html#kamerasEinrichten'"));
        String w = lies("app/src/main/assets/web/local/waechter.html");
        assertTrue(w.contains("id=\"kamerasEinrichten\"") && w.contains("id=\"kamerasKnopf\""));
        assertTrue(w.contains("window.AndroidBridge.openCameraMapping()"));
        assertTrue("Klartext außerhalb des Autos", w.contains("guard.kameras_nur_auto"));
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            String k = lies("app/src/main/assets/web/i18n/" + l + ".json");
            assertTrue(l, k.contains("\"kameras_nur_auto\"") && k.contains("\"kameras_fehler\"") && k.contains("\"kameras_knopf\""));
        }
        String ma = lies("app/src/main/java/at/greenchili/surfmate/ui/MainActivity.kt");
        int i = ma.indexOf("private fun onReconfigureCameraClicked()");
        String block = ma.substring(i, ma.indexOf("showCameraMappingDialog(state)", i));
        assertTrue(block.contains("R.string.camera_mapping_unreachable"));
        assertFalse("nicht mehr „Speichern hat nicht geklappt“", block.contains("toast_failed_to_save_short"));
        for (String d : new String[] {"values", "values-de", "values-fr", "values-it", "values-nl"}) {
            assertTrue(d, lies("app/src/main/res/" + d + "/strings.xml").contains("name=\"camera_mapping_unreachable\""));
        }
    }

    private static String lies(String rel) throws Exception {
        Path p = Paths.get(rel);
        if (!Files.exists(p)) p = Paths.get("..").resolve(rel);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
