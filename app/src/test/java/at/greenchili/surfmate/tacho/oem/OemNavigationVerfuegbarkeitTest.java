package at.greenchili.surfmate.tacho.oem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import at.greenchili.surfmate.tacho.TachoFaehigkeit;

/**
 * E2E 0.1.43b M2: Schalter „Abbiegepfeile im Fahrerdisplay“ ist grau mit „Bei diesem Auto nicht möglich“, wenn der
 * Kern single_os/unsupported meldet; Speichern geht dann nicht. Die Kernsperre in handle() bleibt unverändert.
 */
public class OemNavigationVerfuegbarkeitTest {

    @Test
    public void grundSingleOsOderUnsupported() throws Exception {
        TachoFaehigkeit.Eingaben surf = dualOs();
        surf.singleOs = "1";
        surf.modell = "BYD Dolphin Surf";
        JSONObject p = TachoFaehigkeit.bewerte(surf).getJSONObject("plattform");
        int weg = OemNavigationBridge.waehle(surf, p, AmapServiceBroadcast.PAKET_DILINK3, false);
        assertEquals(OemNavigationBridge.AUSGANG_KEIN, weg);
        assertEquals("single_os", OemNavigationBridge.grund(weg, p));

        TachoFaehigkeit.Eingaben ohneDienst = dualOs();
        ohneDienst.dienste = "";
        JSONObject p2 = TachoFaehigkeit.bewerte(ohneDienst).getJSONObject("plattform");
        int weg2 = OemNavigationBridge.waehle(ohneDienst, p2, null, false);
        assertEquals(OemNavigationBridge.AUSGANG_KEIN, weg2);
        assertEquals("unsupported", OemNavigationBridge.grund(weg2, p2));
        assertEquals("ohne Plattform-Daten: unsupported", "unsupported", OemNavigationBridge.grund(OemNavigationBridge.AUSGANG_KEIN, null));

        JSONObject p3 = TachoFaehigkeit.bewerte(dualOs()).getJSONObject("plattform");
        int weg3 = OemNavigationBridge.waehle(dualOs(), p3, null, false);
        assertEquals(OemNavigationBridge.AUSGANG_AUTOCONTAINER, weg3);
        assertEquals("", OemNavigationBridge.grund(weg3, p3));
    }

    @Test
    public void kernMeldetEsUndSperreBleibt() throws Exception {
        String br = read("app/src/main/java/at/greenchili/surfmate/tacho/oem/OemNavigationBridge.java");
        String v = br.substring(br.indexOf("public static JSONObject verfuegbarkeit()"), br.indexOf("static String grund("));
        assertTrue("gleiche Pruefung wie handle()", v.contains("int weg = ausgang();"));
        assertFalse("nur lesen", v.contains("sende(") || v.contains("sendNavigationPayload") || v.contains("enableNavigationMode"));
        assertTrue("Kernsperre unveraendert", br.contains("return out.put(\"status\", \"blocked\").put(\"reason\", \"unsupported\");"));
        String h = read("app/src/main/java/at/greenchili/surfmate/server/NavMapApiHandler.java");
        assertTrue(h.contains("response.put(\"abbiegepfeile\", at.greenchili.surfmate.tacho.oem.OemNavigationBridge.verfuegbarkeit());"));
    }

    @Test
    public void oberflaecheGrauUndOhneSpeichern() throws Exception {
        String js = read("app/src/main/assets/web/shared/road-sense.js");
        assertTrue(js.contains("this._setOemTurnMoeglich(data && data.abbiegepfeile);"));
        String set = js.substring(js.indexOf("_setOemTurnMoeglich(v) {"), js.indexOf("async toggleOemTurnGuidance() {"));
        assertTrue("nur bei ausdruecklich nicht moeglich", set.contains("v.moeglich === false"));
        assertTrue(set.contains("el.disabled = true;"));
        assertTrue(set.contains("el.checked = false;"));
        assertTrue(set.contains("hint.style.display = gesperrt ? '' : 'none';"));
        String toggle = js.substring(js.indexOf("async toggleOemTurnGuidance() {"), js.indexOf("async toggleExternalNavGuidance() {"));
        int sperre = toggle.indexOf("if (this._oemTurnGesperrt) {");
        assertTrue("Sperre vor dem Speichern", sperre > 0 && sperre < toggle.indexOf("_saveSection("));
        assertTrue(js.contains("this._setChecked('rsOemTurnGuidance', c.oemTurnGuidance && !this._oemTurnGesperrt);"));
        assertFalse("ES5-tauglich: keine ?. / ??", set.contains("?.") || set.contains("??"));

        String html = read("app/src/main/assets/web/local/road-sense.html");
        assertTrue(html.contains("id=\"rsOemTurnGesperrt\" style=\"display:none\" data-i18n=\"road_sense.oem_turn_guidance_gesperrt\""));
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject rs = new JSONObject(read("app/src/main/assets/web/i18n/" + l + ".json")).getJSONObject("road_sense");
            String t = rs.getString("oem_turn_guidance_gesperrt");
            assertTrue(l, t.length() > 10);
        }
        assertEquals("Bei diesem Auto nicht möglich.", new JSONObject(read("app/src/main/assets/web/i18n/de.json"))
                .getJSONObject("road_sense").getString("oem_turn_guidance_gesperrt"));
    }

    private static TachoFaehigkeit.Eingaben dualOs() {
        TachoFaehigkeit.Eingaben e = new TachoFaehigkeit.Eingaben();
        e.singleOs = "0";
        e.sdk = 29;
        e.dienste = "0\tAutoContainer: [android.os.IAutoContainer]\n";
        e.uid = "2000";
        return e;
    }

    private static String read(String relative) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8).replace("\r\n", "\n");
            }
            current = at.greenchili.surfmate.testutil.RepoWurzel.hoch(current);
        }
        throw new IllegalStateException("missing " + relative);
    }
}
