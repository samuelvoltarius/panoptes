package at.greenchili.surfmate.bruecke;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 0.1.26.1 gzip im Kanal „web“ (Befund Handy-App: eine Seite ~2 MB, Server-Grenze je Postfach): nur wenn das Handy
 * accept-encoding gzip schickt, nur Text ab 1 KiB, nie Bereiche/SSE/Kamera; accept-encoding geht nicht ans Auto weiter.
 */
public class BrueckeGzipTest {

    static byte[] text(int n) {
        StringBuilder s = new StringBuilder();
        while (s.length() < n) s.append("function panoptes(){return 'Brücke';}\n");
        return s.toString().getBytes(StandardCharsets.UTF_8);
    }

    static byte[] gunzip(byte[] z) throws Exception {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(z))) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }

    static final class Auto implements BrueckeWeiterleitung.Aufruf {
        Map<String, String> kopf;
        Map<String, String> raus = new HashMap<>();
        byte[] inhalt = text(50_000);
        int status = 200;

        public BrueckeWeiterleitung.Antwort senden(String m, String p, Map<String, String> k, byte[] b) {
            kopf = k;
            Map<String, String> rk = new HashMap<>(raus);
            rk.putIfAbsent("content-type", "application/javascript");
            return new BrueckeWeiterleitung.Antwort(status, rk, inhalt);
        }
    }

    final Auto auto = new Auto();
    final BrueckeWeiterleitung w = new BrueckeWeiterleitung(jwt -> jwt.startsWith("kid-") ? jwt.substring(4) : null, auto,
            kid -> "web-jwt-" + kid);

    JSONObject frage(boolean web, String ae, int v) throws Exception {
        JSONObject h = new JSONObject().put("Authorization", "Bearer kid-k1");
        if (ae != null) h.put("Accept-Encoding", ae);
        JSONObject r = new JSONObject().put("id", 5).put("m", "GET").put("p", "/shared/app.js").put("h", h);
        if (web) r.put("x", "web");
        if (v == 2) r.put("v", 2);
        return new JSONObject(new String(w.bearbeiten(r.toString().getBytes(StandardCharsets.UTF_8), "k1"),
                StandardCharsets.UTF_8));
    }

    @Test
    public void webMitGzipKomprimiertUndReichtNichtWeiter() throws Exception {
        JSONObject r = frage(true, "gzip, deflate, br", 1);
        assertEquals(200, r.getInt("s"));
        assertEquals("gzip", r.getJSONObject("h").getString("content-encoding"));
        assertTrue(r.getBoolean("b64"));
        byte[] z = Base64.getDecoder().decode(r.getString("b"));
        assertTrue("deutlich kleiner: " + z.length, z.length < auto.inhalt.length / 4);
        assertArrayEquals(auto.inhalt, gunzip(z));
        assertFalse("accept-encoding nicht ans Auto", auto.kopf.containsKey("accept-encoding"));
    }

    @Test
    public void ohneGzipOderOhneWebOderUngeeignetWieBisher() throws Exception {
        JSONObject r = frage(true, null, 1);
        assertFalse(r.getJSONObject("h").has("content-encoding"));
        assertEquals(new String(auto.inhalt, StandardCharsets.UTF_8), r.getString("b"));

        r = frage(false, "gzip", 1);
        assertFalse("nur Kanal web", r.getJSONObject("h").has("content-encoding"));

        auto.inhalt = text(500);
        r = frage(true, "gzip", 1);
        assertFalse("unter 1 KiB nicht", r.getJSONObject("h").has("content-encoding"));

        auto.inhalt = text(50_000);
        auto.raus.put("content-type", "image/jpeg");
        assertFalse("kein Text", frage(true, "gzip", 1).getJSONObject("h").has("content-encoding"));

        auto.raus.put("content-type", "text/event-stream");
        assertFalse("kein SSE", frage(true, "gzip", 1).getJSONObject("h").has("content-encoding"));

        auto.raus.put("content-type", "text/plain");
        auto.status = 206;
        auto.raus.put("content-range", "bytes 0-9/100");
        assertFalse("keine Bereiche", frage(true, "gzip", 1).getJSONObject("h").has("content-encoding"));

        auto.status = 304;
        auto.raus.clear();
        assertFalse("304 bleibt leer", frage(true, "gzip", 1).getJSONObject("h").has("content-encoding"));
    }

    @Test
    public void gzipStromStueckweiseGleichWieGzip() throws Exception {
        for (int n : new int[]{0, 1, 1000, 200_000}) {
            byte[] roh = text(n);
            if (n == 0) roh = new byte[0];
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (InputStream z = new BrueckeWeiterleitung.GzipStrom(new ByteArrayInputStream(roh))) {
                byte[] buf = new byte[777];
                int k;
                while ((k = z.read(buf, 0, buf.length)) >= 0) b.write(buf, 0, k);
            }
            assertArrayEquals("n=" + n, roh, gunzip(b.toByteArray()));
        }
    }

    @Test
    public void v2WebMitGzipStueckweise() throws Exception {
        byte[] inhalt = text(300_000);
        BrueckeWeiterleitung v2 = new BrueckeWeiterleitung(jwt -> jwt.startsWith("kid-") ? jwt.substring(4) : null,
                new BrueckeTest.Mitschnitt(), kid -> "web-jwt-" + kid, (kid, r) -> true).mitStrom((m, p, k, b) -> {
                    Map<String, String> rk = new HashMap<>();
                    rk.put("content-type", "application/json");
                    rk.put("etag", "\"x1\"");
                    return new BrueckeWeiterleitung.Strom(200, rk, new ByteArrayInputStream(inhalt), null);
                }, n -> true);
        List<byte[]> stuecke = new ArrayList<>();
        BrueckeWeiterleitung.StueckAusgang aus = new BrueckeWeiterleitung.StueckAusgang() {
            public boolean senden(byte[] s) { stuecke.add(s); return true; }
            public long warteschlange() { return 0; }
        };
        byte[] req = new JSONObject().put("id", 7).put("v", 2).put("x", "web").put("m", "GET").put("p", "/i18n/de.json")
                .put("h", new JSONObject().put("Authorization", "Bearer kid-k1").put("Accept-Encoding", "gzip"))
                .toString().getBytes(StandardCharsets.UTF_8);
        v2.strom(req, "k1", false, aus, id -> false);
        assertEquals(BrueckeWeiterleitung.V2_KOPF, stuecke.get(0)[0]);
        JSONObject kopf = new JSONObject(new String(stuecke.get(0), 5, stuecke.get(0).length - 5, StandardCharsets.UTF_8));
        assertEquals("gzip", kopf.getJSONObject("h").getString("content-encoding"));
        assertEquals("ETag bleibt (If-None-Match/304)", "\"x1\"", kopf.getJSONObject("h").getString("etag"));
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        for (int i = 1; i < stuecke.size() - 1; i++) {
            assertEquals(BrueckeWeiterleitung.V2_DATEN, stuecke.get(i)[0]);
            z.write(stuecke.get(i), 5, stuecke.get(i).length - 5);
        }
        assertEquals(BrueckeWeiterleitung.V2_ENDE, stuecke.get(stuecke.size() - 1)[0]);
        assertTrue(z.size() < inhalt.length / 4);
        assertArrayEquals(inhalt, gunzip(z.toByteArray()));
    }
}
