package at.greenchili.surfmate.onboarding.schritte;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 0.1.39 A1 (Integrator 10.11.): Im Schritt „Auto“ gibt es keinen Knopf „Modell und Akku wählen“ mehr (tat nichts),
 * „Weiß nicht“ bleibt, dazu der Satz mit dem Menüweg – wortgleich wie in der Web-Oberfläche, in 5 Sprachen.
 */
public class AutoSchrittMenuewegTest {

    @Test
    public void knopfFehltSatzIstDa() throws Exception {
        String w = lies("src/main/java/at/greenchili/surfmate/onboarding/schritte/WeitereSchritte.kt");
        int a = w.indexOf("class AutoSchritt");
        String auto = w.substring(a, w.indexOf("class KamerasSchritt", a));
        assertFalse(auto.contains("R.string.einr_auto_waehlen"));
        assertFalse(auto.contains("fahrzeugWaehlen"));
        assertTrue(auto.contains("R.string.einr_auto_menueweg"));
        assertTrue("„Weiß nicht“ bleibt", auto.contains("R.string.einr_weiss_nicht") && auto.contains("R.string.einr_auto_weiss_nicht_satz"));
        assertFalse(w.contains("fun fahrzeugWaehlen("));
        assertFalse(lies("src/main/java/at/greenchili/surfmate/ui/MainActivity.kt").contains("openVehicleProfileForOnboarding"));
    }

    @Test
    public void menuewegWortgleichMitDerWebOberflaeche() throws Exception {
        String[][] sp = {{"values", "en"}, {"values-de", "de"}, {"values-fr", "fr"}, {"values-it", "it"}, {"values-nl", "nl"}};
        for (String[] x : sp) {
            String xml = lies("src/main/res/" + x[0] + "/strings_einrichtung.xml")
                    .replace("&amp;", "&").replace("\\'", "'");
            int i = xml.indexOf("name=\"einr_auto_menueweg\">");
            assertTrue(x[0], i > 0);
            String text = xml.substring(i, xml.indexOf("</string>", i));
            JSONObject j = new JSONObject(lies("src/main/assets/web/i18n/" + x[1] + ".json"));
            String weg = j.getJSONObject("shell").getString("sec_einstellungen") + " → "
                    + j.getJSONObject("shell").getString("page_einstellungen_alle") + " → "
                    + j.getJSONObject("system").getString("group_vehicle") + " → "
                    + j.getJSONObject("system").getString("manual_vehicle_title");
            assertTrue(x[0] + ": " + text + " / " + weg, text.contains(weg));
            assertFalse(x[0], xml.contains("name=\"einr_auto_waehlen\""));
        }
    }

    private static String lies(String rel) throws Exception {
        Path p = Paths.get(rel);
        if (!Files.exists(p)) p = Paths.get("app").resolve(rel);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
