package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 0.1.38.3 (Community-Fall „Kopplung nur im gleichen WLAN“): Der Code-Weg sagt ehrlich, ob der QR-Code unterwegs
 * geht. Die Brücke bleibt Standard aus (REGEL NR. 2), sie wird nur angeboten.
 */
public class KopplungUnterwegsTest {

    private static String datei(String rel) throws Exception {
        Path p = Paths.get("src/main/assets/web/" + rel);
        if (!Files.exists(p)) p = Paths.get("app/src/main/assets/web/" + rel);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    public void grundJeLage() {
        assertEquals("aus", EinstellungenApiHandler.einmalGrund(false, false));
        assertEquals("aus", EinstellungenApiHandler.einmalGrund(false, true));
        assertEquals("kein_internet", EinstellungenApiHandler.einmalGrund(true, false));
        assertNull(EinstellungenApiHandler.einmalGrund(true, true));
    }

    @Test
    public void systemSeiteSagtEsUndBietetDenSchalterAn() throws Exception {
        String s = datei("local/system.html");
        assertTrue(s.contains("id=\"pairWeg\""));
        assertTrue(s.contains("system.pair_nur_wlan"));
        assertTrue(s.contains("system.pair_kein_internet"));
        assertTrue(s.contains("system.pair_unterwegs"));
        assertTrue(s.contains("d.brueckeEinmalGrund"));
        // QR-Daten: Feld „bruecke“, wenn der Code nicht unterwegs geht (Handy-App erklärt es)
        assertTrue(s.contains("j.bruecke = grund === 'kein_internet' ? 'kein_internet' : 'aus'"));
        // Web-App-Adresse groß + eigener QR nur zur Web-App (getrennt vom Kopplungs-QR)
        assertTrue(s.contains("panoptes.serveone.at</div>"));
        assertTrue(s.contains("q.addData('https://panoptes.serveone.at/')"));
        assertTrue(s.contains("byd.serveone.at/app/"));
        // nur anbieten, nie selbst einschalten
        assertTrue(!s.contains("brueckeEin').checked = true"));
    }

    @Test
    public void texteInAllenSprachen() throws Exception {
        for (String l : new String[]{"de", "en", "fr", "it", "nl"}) {
            org.json.JSONObject sys = new org.json.JSONObject(datei("i18n/" + l + ".json")).getJSONObject("system");
            for (String k : new String[]{"pair_nur_wlan", "pair_zur_bruecke", "pair_kein_internet", "pair_unterwegs",
                    "app_holen_titel", "app_holen_web", "app_holen_android", "app_holen_qr"}) {
                assertTrue(l + " " + k, !sys.optString(k, "").isEmpty());
            }
            assertTrue(l + " pair_lead nennt die Handy-App", sys.getString("pair_lead").toLowerCase().contains("app"));
        }
    }
}
