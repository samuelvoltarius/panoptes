package at.greenchili.surfmate.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 0.1.23 Gurt-Flattern (Deploy-Beleg 28.10., onSafetyBeltStatusChanged alle ~0,4 s am geparkten Auto). */
public class GurtEntprellerTest {

    private static final Pattern ZEILE = Pattern.compile(
            "\\[(\\d{4}-\\d{2}-\\d{2}) (\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})\\] onSafetyBeltStatusChanged: front passenger=(buckled|unbuckled)");

    /** Spielt Logzeilen der Form „BydDataCollector: [2026-10-28 10:25:46.331] onSafetyBeltStatusChanged: front passenger=…“ ab. */
    static final class Abspiel {
        final GurtEntpreller g = new GurtEntpreller();
        final List<String> log = new ArrayList<>();
        final List<Integer> zustaende = new ArrayList<>();
        long letzt;

        void zeile(String z) {
            Matcher m = ZEILE.matcher(z);
            if (!m.find()) return;
            long t = ((Long.parseLong(m.group(2)) * 60 + Long.parseLong(m.group(3))) * 60 + Long.parseLong(m.group(4))) * 1000
                    + Long.parseLong(m.group(5));
            // Nachlauf-Takte, die der Daemon zwischen zwei Callbacks laufen ließe
            if (letzt > 0 && t - letzt > GurtEntpreller.HALTE_MS) nimm(g.takt(letzt + GurtEntpreller.HALTE_MS + 50));
            if (letzt > 0 && t - letzt > GurtEntpreller.FENSTER_MS) nimm(g.takt(letzt + GurtEntpreller.FENSTER_MS + 50));
            nimm(g.roh("buckled".equals(m.group(6)) ? 1 : 0, t));
            letzt = t;
        }

        void ende() {
            nimm(g.takt(letzt + GurtEntpreller.HALTE_MS + 50));
            nimm(g.takt(letzt + GurtEntpreller.FENSTER_MS + 50));
        }

        void nimm(GurtEntpreller.Ausgabe a) {
            if (a.logZeile != null) log.add(a.logZeile);
            if (a.zustandGeaendert()) zustaende.add(a.neuerZustand);
        }
    }

    /** Erzeugt die Zeilenfolge wie am Auto: abwechselnd buckled/unbuckled, Abstand 380–420 ms. */
    static List<String> flattern(int stunde, int minute, int sekunden) {
        List<String> l = new ArrayList<>();
        long t = ((stunde * 60L + minute) * 60L) * 1000L + 331;
        long ende = t + sekunden * 1000L;
        boolean b = true;
        int i = 0;
        while (t < ende) {
            long h = t / 3_600_000L, mi = t / 60_000L % 60, s = t / 1000 % 60, ms = t % 1000;
            l.add(String.format(java.util.Locale.ROOT, "BydDataCollector: [2026-10-28 %02d:%02d:%02d.%03d] onSafetyBeltStatusChanged: front passenger=%s",
                    h, mi, s, ms, b ? "buckled" : "unbuckled"));
            b = !b;
            t += 380 + (i++ % 5) * 10;
        }
        return l;
    }

    @Test
    public void eineStundeFlatternWieAmAuto() {
        Abspiel a = new Abspiel();
        List<String> zeilen = flattern(8, 0, 3600);
        assertTrue("≈ 9000 Callbacks wie am Auto", zeilen.size() > 8500);
        for (String z : zeilen) a.zeile(z);
        // vorher: eine Zeile je Callback (≈ 9000); jetzt: höchstens drei Wechsel, „flattert“, je Minute eine Zusammenfassung
        assertTrue("Logzeilen: " + a.log.size(), a.log.size() <= 3 + 1 + 61);
        assertTrue(a.log.stream().anyMatch(s -> s.contains("flattert (")));
        assertTrue(a.log.stream().anyMatch(s -> s.contains("flattert weiter")));
        assertTrue("ein flatternder Wert gilt nie", a.zustaende.isEmpty());
        a.ende();
        assertTrue("danach wieder ruhig", a.log.get(a.log.size() - 1).contains("wieder ruhig"));
        assertEquals("erst nach Ruhe gilt der Endwert", 1, a.zustaende.size());
    }

    /** Echte Folge vom Auto (Deploy, GURT_FLATTERN_AUSZUG; nur die Gurtzeilen, ohne Ort). */
    static List<String> echt(String teil) throws Exception {
        try (java.io.InputStream in = GurtEntprellerTest.class.getClassLoader().getResourceAsStream("gurt/flattern_" + teil + ".txt")) {
            List<String> l = new ArrayList<>();
            for (String z : new String(in.readAllBytes(), "UTF-8").split("\n")) if (!z.startsWith("#") && !z.trim().isEmpty()) l.add(z);
            return l;
        }
    }

    @Test
    public void echteFolgeDauerabschnitt() throws Exception {
        List<String> zeilen = echt("A");
        assertEquals(300, zeilen.size());
        Abspiel a = new Abspiel();
        for (String z : zeilen) a.zeile(z);
        // vorher 300 Logzeilen in ~2 min; jetzt: ≤ 3 Wechsel, „flattert“, ≤ 1 Zusammenfassung je Minute
        assertTrue("Logzeilen: " + a.log, a.log.size() <= 3 + 1 + 3);
        assertTrue(a.log.get(a.log.size() - 1).contains("flattert"));
        assertTrue("kein flatternder Wert gilt", a.zustaende.isEmpty());
    }

    @Test
    public void echteFolgeAnfangUndEndeEinesSchubs() throws Exception {
        Abspiel b = new Abspiel();
        for (String z : echt("B")) b.zeile(z);
        assertTrue("Schubanfang wird als Flattern erkannt: " + b.log, b.g.flattert());
        assertTrue(b.log.size() <= 5);
        assertTrue(b.zustaende.isEmpty());

        Abspiel c = new Abspiel();
        for (String z : echt("C")) c.zeile(z);
        c.ende();   // danach 261 s Ruhe
        assertFalse(c.g.flattert());
        assertTrue("Schubende: " + c.log, c.log.get(c.log.size() - 1).contains("wieder ruhig"));
        assertEquals("erst in der Ruhe gilt genau ein Wert", 1, c.zustaende.size());
    }

    @Test
    public void echtesAnschnallenGiltNachZweiSekunden() {
        GurtEntpreller g = new GurtEntpreller();
        long t = 1_000_000;
        GurtEntpreller.Ausgabe a = g.roh(0, t);
        assertEquals("onSafetyBeltStatusChanged: front passenger=unbuckled", a.logZeile);
        assertFalse(a.zustandGeaendert());
        assertEquals(0, g.takt(t + 2_000).neuerZustand);
        a = g.roh(1, t + 30_000);
        assertEquals("onSafetyBeltStatusChanged: front passenger=buckled", a.logZeile);
        assertFalse("noch nicht gehalten", a.zustandGeaendert());
        assertFalse(g.takt(t + 31_000).zustandGeaendert());
        assertEquals(1, g.takt(t + 32_000).neuerZustand);
        assertFalse("nicht doppelt", g.takt(t + 40_000).zustandGeaendert());
        assertFalse(g.flattert());
    }

    @Test
    public void dreiWechselSindNochKeinFlattern() {
        GurtEntpreller g = new GurtEntpreller();
        long t = 5_000_000;
        g.roh(0, t);
        g.takt(t + 2_000);
        assertEquals("…=buckled", "onSafetyBeltStatusChanged: front passenger=buckled", g.roh(1, t + 3_000).logZeile);
        g.roh(0, t + 4_000);
        g.roh(1, t + 5_000);
        assertFalse(g.flattert());
        assertTrue(g.roh(0, t + 6_000).logZeile.contains("flattert (4 Wechsel"));
        assertTrue(g.flattert());
        assertNull("binnen der Minute still", g.roh(1, t + 7_000).logZeile);
        assertEquals("es gilt weiter der letzte ruhige Wert", 0, g.stabil());
    }

    @Test
    public void verdrahtetVorTrackerUndAutomation() throws Exception {
        String q = new String(java.nio.file.Files.readAllBytes(quelle()), "UTF-8");
        int cb = q.indexOf("if (\"onSafetyBeltStatusChanged\".equals(method))");
        int ende = q.indexOf("return;", cb);
        String block = q.substring(cb, ende);
        assertTrue(block.contains("gurtEntpreller.roh(passengerState"));
        assertFalse("der rohe Callback geht nicht mehr direkt an den Tracker", block.contains("recordCallback"));
        assertFalse("und nicht direkt an Automationen", block.contains("publishPassengerSeatbeltEdge"));
        int aus = q.indexOf("private void gurtAusgabe(");
        String a = q.substring(aus, q.indexOf("\n    }", aus));
        assertTrue(a.contains("passengerSeatbeltTracker.recordCallback(state)") && a.contains("publishPassengerSeatbeltEdge(state)"));
    }

    private static java.nio.file.Path quelle() {
        java.nio.file.Path p = java.nio.file.Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        String rel = "src/main/java/at/greenchili/surfmate/byd/BydDataCollector.java";
        while (p != null) {
            if (java.nio.file.Files.isRegularFile(p.resolve(rel))) return p.resolve(rel);
            if (java.nio.file.Files.isRegularFile(p.resolve("app/" + rel))) return p.resolve("app/" + rel);
            p = p.getParent();
        }
        throw new IllegalStateException(rel);
    }
}
