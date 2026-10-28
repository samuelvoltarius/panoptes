package at.greenchili.surfmate.bruecke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 0.1.24 Kanal „web“ (Kern 10, Weg a): die Handy-App bedient ihre WebView über den Noise-Kanal. Rechte wie die
 * Web-Sitzung (Stufe B, Recht „web“), Range je Bereich ≤ 1 MiB, zu große Antworten → 413 mit Range-Hinweis.
 */
public class BrueckeWebKanalTest {

    static final class Auto implements BrueckeWeiterleitung.Aufruf {
        Map<String, String> kopf;
        String pfad;
        Map<String, String> raus = new HashMap<>();
        int status = 200;
        boolean zuGross;

        public BrueckeWeiterleitung.Antwort senden(String m, String p, Map<String, String> k, byte[] b) throws Exception {
            pfad = p;
            kopf = k;
            if (zuGross) throw new BrueckeWeiterleitung.ZuGross();
            Map<String, String> rk = new HashMap<>(raus);
            rk.putIfAbsent("content-type", "text/html");
            return new BrueckeWeiterleitung.Antwort(status, rk, "<html>".getBytes(StandardCharsets.UTF_8));
        }
    }

    final Auto auto = new Auto();
    /** k1 darf Web (Recht „web“), k2 nicht. */
    final BrueckeWeiterleitung w = new BrueckeWeiterleitung(jwt -> jwt.startsWith("kid-") ? jwt.substring(4) : null, auto,
            kid -> kid.equals("k1") ? "web-jwt-k1" : null);

    JSONObject frage(String kid, boolean web, Map<String, String> h) throws Exception {
        JSONObject kopf = new JSONObject().put("Authorization", "Bearer kid-" + kid);
        for (Map.Entry<String, String> e : h.entrySet()) kopf.put(e.getKey(), e.getValue());
        JSONObject r = new JSONObject().put("id", 3).put("m", "GET").put("p", "/trips.html").put("h", kopf);
        if (web) r.put("x", "web");
        return new JSONObject(new String(w.bearbeiten(r.toString().getBytes(StandardCharsets.UTF_8), kid), StandardCharsets.UTF_8));
    }

    @Test
    public void webMitRechtBekommtWebJwtDerselbenKopplung() throws Exception {
        Map<String, String> h = new HashMap<>();
        h.put("Cookie", "byd_session=fremder.jwt; pn_einbettung=handy");
        auto.raus.put("set-cookie", "byd_session=neu; Path=/\npn_einbettung=handy; Path=/");
        auto.raus.put("location", "/trips.html");
        JSONObject r = frage("k1", true, h);
        assertEquals(200, r.getInt("s"));
        assertEquals("Stufe B: Web-JWT statt Kopplungs-JWT", "Bearer web-jwt-k1", auto.kopf.get("authorization"));
        assertEquals("fremdes Sitzungs-Cookie fällt weg", "pn_einbettung=handy", auto.kopf.get("cookie"));
        assertTrue(r.getJSONObject("h").getString("set-cookie").contains("byd_session=neu"));
        assertEquals("/trips.html", r.getJSONObject("h").getString("location"));
        assertEquals("<html>", r.getString("b"));
    }

    @Test
    public void ohneRechtWebKeinZugangUndOhneXWieBisher() throws Exception {
        JSONObject r = frage("k2", true, new HashMap<>());
        assertEquals(403, r.getInt("s"));
        assertEquals("nicht_erlaubt", new JSONObject(r.getString("b")).getString("grund"));
        assertNull("nichts weitergereicht", auto.pfad);

        Map<String, String> h = new HashMap<>();
        h.put("Cookie", "pn_einbettung=handy");
        auto.raus.put("set-cookie", "a=1");
        auto.raus.put("location", "/x.html");
        r = frage("k2", false, h);
        assertEquals(200, r.getInt("s"));
        assertEquals("ohne x: Kopplungs-JWT bleibt", "Bearer kid-k2", auto.kopf.get("authorization"));
        assertFalse("ohne x: kein Cookie hinein", auto.kopf.containsKey("cookie"));
        assertFalse("ohne x: kein Set-Cookie hinaus", r.getJSONObject("h").has("set-cookie"));
        assertFalse("ohne x: keine Weiterleitung hinaus", r.getJSONObject("h").has("location"));
    }

    @Test
    public void weiterleitungNurAufEigenePfade() throws Exception {
        for (String fremd : new String[]{"https://fremd.example/x", "//fremd.example/x", "javascript:alert(1)"}) {
            auto.raus.put("location", fremd);
            assertFalse(fremd, frage("k1", true, new HashMap<>()).getJSONObject("h").has("location"));
        }
    }

    @Test
    public void rangeHoechstensEinMiBUndDurchgereicht() throws Exception {
        assertEquals("bytes=0-1048575", BrueckeWeiterleitung.begrenzeRange("bytes=0-"));
        assertEquals("bytes=100-199", BrueckeWeiterleitung.begrenzeRange("bytes=100-199"));
        assertEquals("bytes=5000000-6048575", BrueckeWeiterleitung.begrenzeRange("bytes=5000000-9000000"));
        assertEquals("bytes=-1048576", BrueckeWeiterleitung.begrenzeRange("bytes=-9000000"));
        assertNull("mehrere Bereiche", BrueckeWeiterleitung.begrenzeRange("bytes=0-1,5-9"));
        assertNull(BrueckeWeiterleitung.begrenzeRange("bytes=9-1"));
        assertNull(BrueckeWeiterleitung.begrenzeRange("items=0-1"));

        Map<String, String> h = new HashMap<>();
        h.put("Range", "bytes=0-");
        auto.status = 206;
        auto.raus.put("content-range", "bytes 0-1048575/52428800");
        auto.raus.put("accept-ranges", "bytes");
        auto.raus.put("content-type", "video/mp4");
        JSONObject r = frage("k2", false, h);   // auch die native App (Clip-Vorschau)
        assertEquals("bytes=0-1048575", auto.kopf.get("range"));
        assertEquals(206, r.getInt("s"));
        assertEquals("bytes 0-1048575/52428800", r.getJSONObject("h").getString("content-range"));
        assertEquals("bytes", r.getJSONObject("h").getString("accept-ranges"));
        assertTrue("Binär als base64", r.getBoolean("b64"));
    }

    @Test
    public void zuGrossOhneRangeGibt413MitHinweis() throws Exception {
        auto.zuGross = true;
        JSONObject r = frage("k1", true, new HashMap<>());
        assertEquals(413, r.getInt("s"));
        JSONObject b = new JSONObject(r.getString("b"));
        assertEquals("zu_gross", b.getString("grund"));
        assertTrue(b.getBoolean("range"));
        assertEquals(BrueckeWeiterleitung.MAX_BEREICH, b.getInt("maxBereich"));
    }

    @Test
    public void imDaemonNurHandyMitRechtWebUndKurzGueltig() throws Exception {
        String q = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/at/greenchili/surfmate/bruecke/BrueckeWeiterleitung.java")), StandardCharsets.UTF_8);
        int i = q.indexOf("static BrueckeWeiterleitung imDaemon()");
        String d = q.substring(i, q.indexOf("static Antwort loopback", i));
        assertTrue(d.contains("kk.istHandy(kid) && kk.hatRecht(kid, at.greenchili.surfmate.auth.Kopplungen.RECHT_WEB)"));
        assertTrue(d.contains("WebSitzung.SCOPE, kid, 120)"));
        assertTrue("Tunnel-Kennzeichen bleibt", q.contains("c.setRequestProperty(\"Forwarded\", \"for=panoptes-bruecke\")"));
    }
}
