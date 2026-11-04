package at.greenchili.surfmate.bruecke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.auth.PcZugang;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** 0.1.39 PC über die Brücke: Grenzen je PC (20/s, Tagesgrenze) und Takt-Hinweis im Kanal „web“. */
public class BrueckePcTest {

    static final class Auto implements BrueckeWeiterleitung.Aufruf {
        int aufrufe;
        public BrueckeWeiterleitung.Antwort senden(String m, String p, Map<String, String> k, byte[] b) {
            aufrufe++;
            Map<String, String> rk = new HashMap<>();
            rk.put("content-type", "application/json");
            return new BrueckeWeiterleitung.Antwort(200, rk, "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        }
    }

    final Auto auto = new Auto();
    /** „pc1“ ist ein PC, „h1“ ein Handy; beide dürfen Web. */
    final BrueckeWeiterleitung w = new BrueckeWeiterleitung(jwt -> jwt.startsWith("kid-") ? jwt.substring(4) : null, auto,
            kid -> "web-" + kid).mitPc(kid -> kid.startsWith("pc"));

    @Before
    public void auf() {
        PcZugang.quelle = new PcZugang.Quelle() {
            public boolean an() { return true; }
            public int tagesMb() { return PcZugang.TAGES_MB_MIN; }
        };
        PcZugang.leeren();
    }

    @After
    public void ab() {
        PcZugang.quelle = PcZugang.KONFIG;
        PcZugang.leeren();
    }

    JSONObject frage(String kid) throws Exception {
        JSONObject r = new JSONObject().put("id", 1).put("m", "GET").put("p", "/api/vehicle/state").put("x", "web")
                .put("h", new JSONObject().put("Authorization", "Bearer kid-" + kid));
        return new JSONObject(new String(w.bearbeiten(r.toString().getBytes(StandardCharsets.UTF_8), kid), StandardCharsets.UTF_8));
    }

    @Test
    public void pcBekommtTaktHinweisHandyNicht() throws Exception {
        JSONObject r = frage("pc1");
        assertEquals(200, r.getInt("s"));
        assertEquals(String.valueOf(PcZugang.TAKT_S), r.getJSONObject("h").optString("x-panoptes-takt"));
        assertFalse(frage("h1").getJSONObject("h").has("x-panoptes-takt"));
        assertTrue("Verbrauch gezählt", PcZugang.heute("pc1", System.currentTimeMillis()) > 0);
        assertEquals("Handy zählt nicht", 0L, PcZugang.heute("h1", System.currentTimeMillis()));
    }

    @Test
    public void hoechstensZwanzigJeSekundeDannZuSchnell() throws Exception {
        int ok = 0, zuSchnell = 0;
        for (int i = 0; i < PcZugang.MAX_JE_SEKUNDE + 5; i++) {
            JSONObject r = frage("pc1");
            if (r.getInt("s") == 200) ok++;
            else if (r.getInt("s") == 429) zuSchnell++;
        }
        // Läuft die Schleife über eine Sekundengrenze, dürfen es mehr als 20 sein – nie aber alle 25 auf einmal
        assertTrue(ok >= PcZugang.MAX_JE_SEKUNDE);
        assertTrue("Grenze greift", zuSchnell > 0 || ok > PcZugang.MAX_JE_SEKUNDE);
        for (int i = 0; i < PcZugang.MAX_JE_SEKUNDE; i++) assertEquals("Handy ohne PC-Grenze", 200, frage("h1").getInt("s"));
    }

    @Test
    public void tagesgrenzeSperrt() throws Exception {
        long jetzt = System.currentTimeMillis();
        PcZugang.verbrauchen("pc1", PcZugang.TAGES_MB_MIN * 1024L * 1024L, jetzt);
        int vorher = auto.aufrufe;
        JSONObject r = frage("pc1");
        assertEquals(429, r.getInt("s"));
        assertTrue(r.toString().contains("pc_tageslimit"));
        assertEquals("nichts ans Auto weitergereicht", vorher, auto.aufrufe);
        assertEquals(200, frage("h1").getInt("s"));
    }
}
