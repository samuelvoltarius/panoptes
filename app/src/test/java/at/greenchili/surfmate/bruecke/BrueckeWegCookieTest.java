package at.greenchili.surfmate.bruecke;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/** 0.1.26.1: Kanal „web“ setzt den Merker pn_weg=bruecke, damit live-view statt WebSocket den Hinweis zeigt. */
public class BrueckeWegCookieTest {

    @Test
    public void webBekommtDenMerkerNativNicht() throws Exception {
        Map<String, String> kopf = new LinkedHashMap<>();
        kopf.put("Content-Type", "text/html");
        kopf.put("Set-Cookie", "byd_session=x; Path=/");
        JSONObject web = BrueckeWeiterleitung.kopfRaus(kopf, true);
        String c = web.getString("set-cookie");
        assertTrue(c, c.contains("byd_session=x"));
        assertTrue(c, c.contains("\npn_weg=bruecke"));
        assertFalse("kein HttpOnly, stream.js liest es", c.contains("HttpOnly"));

        Map<String, String> ohne = new LinkedHashMap<>();
        ohne.put("Content-Type", "text/html");
        assertTrue(BrueckeWeiterleitung.kopfRaus(ohne, true).getString("set-cookie").startsWith("pn_weg=bruecke"));
        assertFalse(BrueckeWeiterleitung.kopfRaus(kopf, false).has("set-cookie"));
    }
}
