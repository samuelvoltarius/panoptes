package at.greenchili.surfmate.notifications.push;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 0.1.32: Apple lehnt Push ohne {@code sub} ab. Kontakt ist fest fürs Projekt, nie eine persönliche Adresse. */
public class VapidKontaktTest {

    @Test
    public void projektKontaktStehtImJwt() throws Exception {
        JSONObject c = new VapidSigner(null, VapidSigner.PROJEKT_KONTAKT).claims("https://web.push.apple.com/abc", 1_000L);
        assertEquals("https://byd.serveone.at/panoptes/", c.getString("sub"));
        assertEquals("https://web.push.apple.com", c.getString("aud"));
        assertEquals(1_000L + 12 * 3600L, c.getLong("exp"));
        assertEquals("nur aud, exp, sub: keine Daten des Autos", 3, c.length());
    }

    @Test
    public void kontaktIstKeinePersoenlicheAdresse() {
        assertTrue(VapidSigner.PROJEKT_KONTAKT.startsWith("https://"));
        assertFalse(VapidSigner.PROJEKT_KONTAKT.contains("@"));
    }

    @Test
    public void daemonNutztDenProjektKontakt() throws Exception {
        File f = new File("src/main/java/at/greenchili/surfmate/daemon/CameraDaemon.java");
        if (!f.isFile()) f = new File("app/" + f.getPath());
        String q = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        assertTrue(q.contains("VapidSigner.PROJEKT_KONTAKT"));
        assertFalse(q.contains("VapidSigner(keyStore, \"\")"));
    }
}
