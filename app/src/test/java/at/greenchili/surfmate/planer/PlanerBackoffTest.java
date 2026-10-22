package at.greenchili.surfmate.planer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.junit.Test;

import java.nio.file.Paths;

/** Koordination 0.1.9, Punkt 5: Backoff und ehrlicher Status, wenn der Planer bei „umkreis“ nicht erreichbar ist. */
public class PlanerBackoffTest {

    @Test
    public void wartezeitVerdoppeltBisFuenfMinuten() {
        PlanerBackoff b = new PlanerBackoff();
        long t = 1_000_000L;
        assertEquals(0, b.warteMs(t));
        assertTrue(b.fehler(t));                          // erster Fehler → loggen
        assertEquals(15_000, b.warteMs(t));
        assertFalse(b.fehler(t));                         // weitere nicht
        assertEquals(30_000, b.warteMs(t));
        for (int i = 0; i < 10; i++) b.fehler(t);
        assertEquals(PlanerBackoff.MAX_MS, b.warteMs(t));
        assertEquals(0, b.warteMs(t + PlanerBackoff.MAX_MS));
        assertTrue(b.erfolg());                           // einmal „wieder erreichbar“
        assertFalse(b.erfolg());
        assertEquals(0, b.warteMs(t));
    }

    @Test
    public void nurNetzfehlerZaehlen() {
        assertTrue(PlanerBackoff.istNetzfehler(new java.net.UnknownHostException("byd.serveone.at")));
        assertTrue(PlanerBackoff.istNetzfehler(new java.net.ConnectException("refused")));
        assertTrue(PlanerBackoff.istNetzfehler(new java.net.SocketTimeoutException("t")));
        assertTrue(PlanerBackoff.istNetzfehler(new RuntimeException(new java.net.SocketException("x"))));
        assertFalse(PlanerBackoff.istNetzfehler(new IllegalStateException("Antwort zu groß")));
    }

    @Test
    public void durchreicherUndUnterwegsNutzenDenBackoff() throws Exception {
        String navi = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/NaviApiHandler.java"));
        assertTrue(navi.contains("PlanerBackoff.UMKREIS.warteMs(System.currentTimeMillis())"));
        assertTrue(navi.contains("o.put(\"grund\", \"netz\")"));
        assertTrue(navi.contains("o.put(\"wiederInS\", s)"));
        String rw = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/reichweite/RangeWatchDienst.java"));
        int b = rw.indexOf("PlanerBackoff.UMKREIS.warteMs(now) > 0");
        int abruf = rw.indexOf("http(\"POST\", \"umkreis\"");
        assertTrue(b > 0 && b < abruf);
        // Antwort im Backoff: ehrlicher Text mit Sekunden
        org.json.JSONObject o = at.greenchili.surfmate.server.NaviApiHandler.offline(14_200);
        assertEquals("netz", o.getString("grund"));
        assertEquals(15, o.getLong("wiederInS"));
        assertTrue(o.getString("fehler").contains("15"));
    }
}
