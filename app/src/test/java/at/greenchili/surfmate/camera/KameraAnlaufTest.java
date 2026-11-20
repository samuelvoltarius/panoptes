package at.greenchili.surfmate.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 0.1.43.18 (F4b/F5): Anlaufzeit 25 s ab Kern-Start, Zündung oder (Neu-)Öffnen; Live wartet sie ab. */
public class KameraAnlaufTest {

    private final AtomicLong uhr = new AtomicLong(1_000_000L);

    @After
    public void aufraeumen() {
        KameraAnlauf.fuerTestZuruecksetzen(null);
    }

    @Test
    public void restAbJuengstemEreignis() {
        assertEquals(0L, KameraAnlauf.restMs(1_000L, -1L, -1L, -1L));          // nichts bekannt
        assertEquals(25_000L, KameraAnlauf.restMs(1_000L, 1_000L, -1L, -1L));  // Kern gerade gestartet
        assertEquals(5_000L, KameraAnlauf.restMs(21_000L, 1_000L, -1L, -1L));
        assertEquals(0L, KameraAnlauf.restMs(26_000L, 1_000L, -1L, -1L));      // nach 25 s vorbei
        // Zündung später als Kern-Start → zählt ab Zündung
        assertEquals(20_000L, KameraAnlauf.restMs(105_000L, 1_000L, 100_000L, -1L));
        // Neu-Öffnen der Kamera verlängert
        assertEquals(24_000L, KameraAnlauf.restMs(130_000L, 1_000L, 100_000L, 129_000L));
        // Uhr rückwärts: kein Anlauf
        assertEquals(0L, KameraAnlauf.restMs(500L, 1_000L, -1L, -1L));
    }

    @Test
    public void anhaengenSchreibtRestInDieStartingAntwort() throws Exception {
        KameraAnlauf.fuerTestZuruecksetzen(uhr::get);
        KameraAnlauf.kernGestartet();
        uhr.addAndGet(3_000L);
        JSONObject a = new JSONObject().put("success", false).put("starting", true);
        KameraAnlauf.anhaengen(a);
        assertEquals(22_000L, a.getLong("anlaufRestMs"));
        uhr.addAndGet(30_000L);
        JSONObject b = new JSONObject().put("success", false).put("starting", true);
        KameraAnlauf.anhaengen(b);
        assertEquals(0L, b.getLong("anlaufRestMs"));
        KameraAnlauf.zuendung();
        JSONObject c = new JSONObject();
        KameraAnlauf.anhaengen(c);
        assertEquals(25_000L, c.getLong("anlaufRestMs"));
        KameraAnlauf.anhaengen(null);   // darf nicht werfen
    }

    @Test
    public void diagnoseOhneOrtUndKennung() {
        KameraAnlauf.fuerTestZuruecksetzen(uhr::get);
        KameraAnlauf.kernGestartet();
        uhr.addAndGet(40_000L);
        String z = KameraAnlauf.diagnose(uhr.get());
        assertTrue(z, z.startsWith("KAMERA_MELDUNG quelle=live_starting"));
        assertTrue(z, z.contains("seit_kernstart_ms=40000"));
        assertTrue(z, z.contains("seit_acc_ms=-1"));
        assertFalse(z, z.matches(".*\\d+\\.\\d{4,}.*"));   // keine Koordinaten
    }

    @Test
    public void jedeStartingAntwortTraegtDieAnlaufzeit() throws Exception {
        String s = lies("src/main/java/at/greenchili/surfmate/server/StreamingApiHandler.java");
        Matcher m = Pattern.compile("(\\w+)\\.put\\(\"starting\", true\\);").matcher(s);
        int n = 0;
        while (m.find()) {
            n++;
            String rest = s.substring(m.end(), Math.min(s.length(), m.end() + 120));
            assertTrue("starting ohne Anlaufzeit: " + m.group(), rest.contains("KameraAnlauf.anhaengen(" + m.group(1) + ")"));
        }
        assertTrue(n >= 10);
    }

    @Test
    public void ereignisseSindVerdrahtet() throws Exception {
        assertTrue(lies("src/main/java/at/greenchili/surfmate/daemon/CameraDaemon.java")
                .contains("KameraAnlauf.kernGestartet()"));
        assertTrue(lies("src/main/java/at/greenchili/surfmate/surveillance/GpuSurveillancePipeline.java")
                .contains("KameraAnlauf.zuendung()"));
        assertTrue(lies("src/main/java/at/greenchili/surfmate/camera/PanoramicCameraGpu.java")
                .contains("KameraAnlauf.kameraGeoeffnet()"));
    }

    @Test
    public void liveAnsichtWartetDieAnlaufzeitAb() throws Exception {
        String js = lies("src/main/assets/web/shared/stream.js");
        int poll = js.indexOf("async _pollViewUntilSettled(");
        int ende = js.indexOf("exhausted: true", poll);
        assertTrue(poll > 0 && ende > poll);
        String block = js.substring(poll, ende);
        assertTrue(block.contains("data.anlaufRestMs"));
        assertTrue(block.contains("Date.now() + 30000"));          // harte Obergrenze
        assertTrue(block.contains("!data.starting) break"));       // echter Fehler bricht sofort ab
        // 0.1.43.18b: der letzte Poll vor Fristende läuft zu Ende. Die Frist wird nur VOR dem Warten geprüft, der
        // Abruf selbst wird abgewartet und nie abgebrochen (keine Zeitgrenze, kein AbortController im Poll).
        int schleife = block.indexOf("while (data && data.success !== true && data.starting === true");
        String anlauf = block.substring(schleife);
        int frist = anlauf.indexOf("Date.now() < anlaufEnde");
        int warten = anlauf.indexOf("await new Promise(r => setTimeout(r, warte))");
        int abruf = anlauf.indexOf("await this._requestViewRoute(mode, selectionToken)");
        assertTrue(schleife > 0 && frist > 0 && warten > frist && abruf > warten);
        assertEquals("Frist nur in der Schleifenbedingung", 1, anlauf.split("anlaufEnde", -1).length - 1);
        assertFalse(block.contains("AbortController"));
        assertFalse(block.contains("Promise.race"));
    }

    static String lies(String pfad) throws Exception {
        File f = new File(pfad);
        if (!f.exists()) f = new File("app", pfad);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
