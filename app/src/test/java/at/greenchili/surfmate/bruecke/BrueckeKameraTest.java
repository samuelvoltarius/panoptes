package at.greenchili.surfmate.bruecke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** 0.1.25 Recht „kamera“ (Standard aus): Kamerabilder, Aufnahmen, Live-Bild über die Brücke nur damit. */
public class BrueckeKameraTest {

    static JSONObject json(byte[] b) throws Exception {
        return new JSONObject(new String(b, StandardCharsets.UTF_8));
    }

    @Test
    public void kameraPfadeErkannt() {
        for (String p : new String[] {"/video/id/0123", "/thumb/id/ab", "/snapshot/0", "/api/recordings", "/api/recordings/dates",
                "/api/recordings?x=1", "/api/events/x", "/parking/asset/a.jpg", "/api/stream/enable", "/api/bs/start",
                "/api/camview/1", "/ws?token=x", "/h264/x"}) {
            assertTrue(p, BrueckeWeiterleitung.istKameraPfad(p));
        }
        for (String p : new String[] {"/api/vehicle/state", "/api/ha/v1/zustand", "/api/handy/v1/aktion", "/api/laden",
                "/api/surveillance/status", "/index.html", "/wallbox.html", "/api/parking/sessions"}) {
            assertFalse(p, BrueckeWeiterleitung.istKameraPfad(p));
        }
    }

    @Test
    public void nurMitRechtKamera() throws Exception {
        Set<String> mitKamera = new HashSet<>();
        BrueckeTest.Mitschnitt mit = new BrueckeTest.Mitschnitt();
        BrueckeWeiterleitung w = new BrueckeWeiterleitung(jwt -> jwt.startsWith("kid-") ? jwt.substring(4) : null, mit,
                kid -> null, (kid, r) -> "kamera".equals(r) && mitKamera.contains(kid));
        JSONObject r = json(w.bearbeiten(BrueckeTest.anfrage("GET", "/api/recordings", "kid-k1", null), "k1"));
        assertEquals(403, r.getInt("s"));
        assertTrue(r.getString("b").contains("kamera"));
        assertNull("nichts weitergereicht", mit.pfad);
        // andere Pfade unberührt
        assertEquals(200, json(w.bearbeiten(BrueckeTest.anfrage("GET", "/api/vehicle/state", "kid-k1", null), "k1")).getInt("s"));
        mitKamera.add("k1");
        assertEquals(200, json(w.bearbeiten(BrueckeTest.anfrage("GET", "/video/id/abc", "kid-k1", null), "k1")).getInt("s"));
        assertEquals("/video/id/abc", mit.pfad);
        // Standard-Konstruktor: kein Recht → Kamera gesperrt
        BrueckeWeiterleitung ohne = new BrueckeWeiterleitung(jwt -> "k1", new BrueckeTest.Mitschnitt());
        assertEquals(403, json(ohne.bearbeiten(BrueckeTest.anfrage("GET", "/snapshot/0", "x", null), "k1")).getInt("s"));
    }

    @Test
    public void kameraNichtImStandard() throws Exception {
        assertTrue(at.greenchili.surfmate.auth.Kopplungen.RECHTE.contains("kamera"));
        java.lang.reflect.Field f = at.greenchili.surfmate.auth.Kopplungen.class.getDeclaredField("HANDY_STANDARD");
        f.setAccessible(true);
        assertFalse("Standard aus (Passanten)", ((java.util.List<?>) f.get(null)).contains("kamera"));
        java.io.File s = new java.io.File("src/main/assets/web/local/system.html");
        if (!s.isFile()) s = new java.io.File("app/" + s.getPath());
        String html = new String(java.nio.file.Files.readAllBytes(s.toPath()), StandardCharsets.UTF_8);
        assertTrue(html.contains("'push', 'web', 'kamera'"));
        assertTrue("eigene Rückfrage", html.contains("system.handy_kamera_confirm"));
    }
}
