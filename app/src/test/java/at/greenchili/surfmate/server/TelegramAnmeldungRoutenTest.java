package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;

/** 0.1.21 Web-Anmeldung per Telegram + PWA Stufe 1: Routen, Schutz, Manifest. */
public class TelegramAnmeldungRoutenTest {

    @Test
    public void webSitzungDarfKeineAnmeldungEntscheiden() throws Exception {
        assertTrue(WebHandyGuard.blocks("POST", "/api/telegram-anmeldung/0123456789abcdef/erlauben"));
        assertTrue(WebHandyGuard.blocks("POST", "/api/telegram-anmeldung/0123456789abcdef/ablehnen"));
        String h = WebHandyGuardTest.quelle("java/at/greenchili/surfmate/server/HttpServer.java");
        int i = h.indexOf("/api/telegram-anmeldung/[0-9a-f]{16}");
        assertTrue(i > 0);
        String block = h.substring(i, h.indexOf("return true;", i));
        assertTrue("nur voller Login, nie Kopplung/Web-Sitzung",
                block.contains("AuthMiddleware.isExternRequest() || AuthMiddleware.isHandyWebRequest()"));
    }

    @Test
    public void sitzungOhneSicherheitsrechte() throws Exception {
        String a = WebHandyGuardTest.quelle("java/at/greenchili/surfmate/server/AuthApiHandler.java");
        int i = a.indexOf("static at.greenchili.surfmate.auth.TelegramAnmeldung telegramAnmeldung()");
        String block = a.substring(i, a.indexOf("setzen(t);", i));
        assertTrue("Art web", block.contains("Kopplungen.ART_WEB"));
        assertTrue("Scope der Web-Sitzung (Stufe B)", block.contains("WebSitzung.SCOPE"));
        assertFalse("nie ein Aufsperr-Recht", block.contains("RECHT_AUFSPERREN") || block.contains("setzeRecht"));
        String m = WebHandyGuardTest.quelle("java/at/greenchili/surfmate/server/AuthMiddleware.java");
        assertTrue(m.contains("\"/auth/telegram/\""));
        assertFalse("Entscheidung nie öffentlich", m.contains("\"/api/telegram-anmeldung"));
    }

    @Test
    public void manifestMitPngIcons() throws Exception {
        JSONObject man = new JSONObject(WebHandyGuardTest.quelle("assets/web/local/manifest.json"));
        assertEquals("standalone", man.getString("display"));
        JSONArray icons = man.getJSONArray("icons");
        boolean maskable = false, g512 = false, g192 = false;
        for (int k = 0; k < icons.length(); k++) {
            JSONObject ic = icons.getJSONObject(k);
            String src = ic.getString("src");
            File f = datei(src.startsWith("/") ? "assets/web" + src : "assets/web/local/" + src);
            assertTrue(src, f.isFile());
            if (src.endsWith(".png")) {
                // PNG-Kopf (IHDR): Breite/Höhe an Byte 16/20, ohne AWT
                java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(java.nio.file.Files.readAllBytes(f.toPath()));
                assertEquals(src, 0x89504E47, b.getInt(0));
                int w = b.getInt(16), hh = b.getInt(20);
                String[] wh = ic.getString("sizes").split("x");
                assertEquals(src, Integer.parseInt(wh[0]), w);
                assertEquals(src, Integer.parseInt(wh[1]), hh);
                g512 |= w == 512;
                g192 |= w == 192;
            }
            maskable |= ic.optString("purpose").contains("maskable");
        }
        assertTrue(g512 && g192 && maskable);
        assertTrue(datei("assets/web/shared/apple-touch-icon.png").isFile());
        String m = WebHandyGuardTest.quelle("java/at/greenchili/surfmate/server/AuthMiddleware.java");
        assertTrue("iOS holt das Icon ohne Cookie", m.contains("\"/apple-touch-icon.png\""));
    }

    private static File datei(String rel) {
        File f = new File("src/main/" + rel);
        if (!f.exists()) f = new File("app/" + f.getPath());
        return f.toPath().normalize().toFile();
    }
}
