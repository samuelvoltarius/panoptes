package at.greenchili.surfmate.onboarding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.onboarding.schritte.HandyQr;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;

/**
 * 0.1.38.3 (K4, QR_BRUECKE_FELD.md): Einrichtungsschritt „Handy-App unterwegs nutzen?“ – ausdrücklich gefragt,
 * Standard aus; „Nur im gleichen WLAN“ schaltet nichts ein; der QR-Code trägt „bruecke“, wenn er nicht unterwegs geht,
 * und der Schritt sagt es in Klartext.
 */
public class EinrichtungHandyUnterwegsTest {

    private static JSONObject qrDaten(String inhalt) throws Exception {
        String b64 = inhalt.substring(inhalt.indexOf("#k=") + 3);
        return new JSONObject(new String(Base64.getUrlDecoder().decode(b64), StandardCharsets.UTF_8));
    }

    @Test
    public void qrTraegtBrueckeNurWennNichtUnterwegs() throws Exception {
        assertEquals("aus", qrDaten(HandyQr.INSTANCE.inhalt("123456", null, null, null, "aus")).getString("bruecke"));
        assertEquals("kein_internet", qrDaten(HandyQr.INSTANCE.inhalt("123456", null, null, null, "kein_internet")).getString("bruecke"));
        assertFalse(qrDaten(HandyQr.INSTANCE.inhalt("123456", null, null, null, "")).has("bruecke"));
        assertFalse("älterer Kern: nichts behaupten", qrDaten(HandyQr.INSTANCE.inhalt("123456", null, null, null, null)).has("bruecke"));
        assertEquals("123456", qrDaten(HandyQr.INSTANCE.inhalt("123456", null, null, null, "aus")).getString("c"));
    }

    @Test
    public void schrittFragtUndSchaltetNieSelbstEin() throws Exception {
        String h = lies("app/src/main/java/at/greenchili/surfmate/onboarding/schritte/HandySchritt.kt");
        assertTrue(h.contains("R.string.einr_handy_unterwegs_frage") && h.contains("R.string.einr_handy_unterwegs_text"));
        // „Nur im gleichen WLAN“ holt nur den Code, ohne POST /api/bruecke
        assertTrue(h.contains("a.knopf(t.res(R.string.einr_handy_nur_wlan)) { qrHolen() }"));
        // Einschalten nur über den Haken (nie vorausgewählt)
        assertTrue(h.contains("isChecked = false") && h.contains("setOnCheckedChangeListener { _, an -> knopf.isEnabled = an }"));
        assertTrue(h.contains("\"system.pair_nur_wlan\"") && h.contains("\"system.pair_kein_internet\"") && h.contains("\"system.pair_unterwegs\""));
        for (String d : new String[] {"values", "values-de", "values-fr", "values-it", "values-nl"}) {
            String x = lies("app/src/main/res/" + d + "/strings_einrichtung.xml");
            for (String k : new String[] {"einr_handy_unterwegs_frage", "einr_handy_unterwegs_text", "einr_handy_unterwegs_ja", "einr_handy_nur_wlan"}) {
                assertTrue(d + " " + k, x.contains("name=\"" + k + "\""));
            }
        }
    }

    private static String lies(String rel) throws Exception {
        Path p = Paths.get(rel);
        if (!Files.exists(p)) p = Paths.get("..").resolve(rel);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
