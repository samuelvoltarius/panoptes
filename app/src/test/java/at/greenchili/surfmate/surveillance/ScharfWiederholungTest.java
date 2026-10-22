package at.greenchili.surfmate.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.junit.Test;

import java.nio.file.Paths;

/** Befund Deploy 21.10.: nach Daemon-Start erster Scharf-Versuch abgewiesen → 5 min blind. */
public class ScharfWiederholungTest {

    @Test
    public void backoffFiveTenTwentyFortyThenEveryMinuteWithoutCap() {
        ScharfWiederholung w = new ScharfWiederholung();
        long[] erwartet = {5_000, 10_000, 20_000, 40_000, 60_000, 60_000, 60_000};
        for (long e : erwartet) assertEquals(e, w.abgewiesen(0));
        for (int i = 0; i < 100; i++) w.abgewiesen(0);
        assertEquals("keine Obergrenze", 60_000, w.abgewiesen(0));
    }

    @Test
    public void dueOnlyOnceAndResetClears() {
        ScharfWiederholung w = new ScharfWiederholung();
        assertFalse(w.faellig(0));
        w.abgewiesen(1_000);
        assertTrue(w.wartet());
        assertFalse(w.faellig(5_999));
        assertTrue(w.faellig(6_000));
        assertFalse("nur einmal", w.faellig(6_001));
        w.abgewiesen(7_000);
        w.zuruecksetzen();
        assertFalse(w.wartet());
        assertEquals(0, w.versuche());
        assertEquals("nach Erfolg wieder ab 5 s", 5_000, w.abgewiesen(0));
    }

    /** Nachgestellt: Pipeline wird 3 s nach dem Daemon-Start verfügbar → scharf nach 5 s statt 5 min. */
    @Test
    public void firstAttemptRefusedArmedWellUnder30Seconds() {
        assertTrue(scharfNach(3_000) < 30_000);
        assertEquals(5_000, scharfNach(3_000));
        assertEquals(15_000, scharfNach(12_000));
        assertTrue("auch bei 20 s Anlaufzeit unter 40 s", scharfNach(20_000) <= 35_000);
    }

    private static long scharfNach(long pipelineBereitAb) {
        ScharfWiederholung w = new ScharfWiederholung();
        long t = 0;
        while (t < 600_000) {
            boolean scharf = t >= pipelineBereitAb;   // enableSurveillance() nimmt erst an, wenn running
            if (scharf) {
                w.zuruecksetzen();
                return t;
            }
            t += w.abgewiesen(t);
            assertTrue(w.faellig(t));
        }
        throw new AssertionError("nie scharf");
    }

    @Test
    public void statusTellsTheTruth() {
        assertEquals("scharf", ScharfWiederholung.zustand(true, true, false));
        assertEquals("startet", ScharfWiederholung.zustand(true, false, false));
        assertEquals("unterdrueckt", ScharfWiederholung.zustand(true, false, true));
        assertEquals("aus", ScharfWiederholung.zustand(false, false, false));
    }

    @Test
    public void wiring() throws Exception {
        String d = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/daemon/CameraDaemon.java"));
        assertTrue("Abweisung führt zur Wiederholung",
                d.contains("if (!pipeline.enableSurveillance()) {\n                    planeScharfWiederholung("));
        int ok = d.indexOf("Surveillance mode activated successfully");
        assertTrue("Erfolg nur nach echter Annahme", ok > d.indexOf("if (!pipeline.enableSurveillance())"));
        assertTrue(d.substring(ok - 120, ok).contains("SCHARF_WIEDERHOLUNG.zuruecksetzen();"));
        assertTrue("active = wirklich scharf", d.contains("status.put(\"active\", isSentryArmed());"));
        assertFalse(d.contains("status.put(\"active\", gpuPipeline.isRunning());"));
        assertTrue(d.contains("status.put(\"zustand\", surveillanceZustand());"));
        int dis = d.indexOf("public static void disableSurveillance() {");
        assertTrue("Aus setzt die Wiederholung zurück", d.substring(dis, dis + 120).contains("SCHARF_WIEDERHOLUNG.zuruecksetzen();"));
        assertFalse("kein blockierendes Warten auf dem ACC-Thread", d.contains("pipeline warm-up"));
        String p = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/surveillance/GpuSurveillancePipeline.java"));
        assertTrue(p.contains("public boolean enableSurveillance() {"));
        String ipc = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/SurveillanceIpcServer.java"));
        assertFalse("Telegram /status nicht mehr „Pipeline läuft“", ipc.contains("boolean active = CameraDaemon.isSurveillanceActive();"));
    }
}
