package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.ui.daemon.DaemonStartPlan;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 0.1.38.8 Schnellstart (STARTZEIT_ANALYSE_01388): V1 die 45 s zählen ab Boot des Geräts und werden bei der Übergabe
 * nicht neu gestartet, V3 das Watchdog-Skript wartet nur kurz nach dem Boot, V5 die App fragt anfangs alle 500 ms.
 */
public class SchnellstartTest {

    private static String quelle(String rel) throws Exception {
        File f = new File(rel);
        if (!f.exists()) f = new File("app/" + rel);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    // ------------------------------------------------------------- V1
    @Test
    public void kameraStartZaehltAbBootNichtAbAufruf() {
        // direkt nach dem Boot: fast die ganze Wartezeit
        assertEquals(45_000L, DaemonStartPlan.kameraVerzoegerungMs(0L));
        assertEquals(40_000L, DaemonStartPlan.kameraVerzoegerungMs(5_000L));
        // Übergabe / App später geöffnet: nur der Rest, nicht noch einmal 45 s
        assertEquals(15_000L, DaemonStartPlan.kameraVerzoegerungMs(30_000L));
        assertEquals(1_000L, DaemonStartPlan.kameraVerzoegerungMs(44_500L));
        // Gerät läuft schon lange (ACC an nach dem Parken, nach einem Update): sofort (Mindestverzögerung)
        assertEquals(DaemonStartPlan.MIN_MS, DaemonStartPlan.kameraVerzoegerungMs(45_000L));
        assertEquals(DaemonStartPlan.MIN_MS, DaemonStartPlan.kameraVerzoegerungMs(3_600_000L));
        // ungültige Uhr (negativ) wird nie länger als die Höchstzeit
        assertEquals(45_000L, DaemonStartPlan.kameraVerzoegerungMs(-1L));
    }

    @Test
    public void uebergabeStartetKeineNeueUhr() {
        // Boot-Manager plant bei Uptime 2 s: 43 s. Die MainActivity übernimmt bei Uptime 30 s: 15 s. Beide enden bei Uptime 45 s.
        long boot = 2_000L + DaemonStartPlan.kameraVerzoegerungMs(2_000L);
        long uebergabe = 30_000L + DaemonStartPlan.kameraVerzoegerungMs(30_000L);
        assertEquals(45_000L, boot);
        assertEquals(45_000L, uebergabe);
    }

    @Test
    public void optionaleDienstePlanenSichRelativZurKamera() {
        assertEquals(90_000L, DaemonStartPlan.BOOT_STABIL_MS + DaemonStartPlan.HEALTH_NACH_KAMERA_MS);
    }

    @Test
    public void managerNutztDenPlanKeineFesten45Sekunden() throws Exception {
        String m = quelle("src/main/java/at/greenchili/surfmate/ui/daemon/DaemonStartupManager.kt");
        assertFalse("keine feste 45-s-Verzögerung mehr", m.contains("}, 45000)"));
        assertFalse(m.contains("}, 60000)\n"));
        assertEquals("App-Start und Boot rechnen beide mit der Geräte-Laufzeit", 2, count(m, "DaemonStartPlan.kameraVerzoegerungMs(android.os.SystemClock.elapsedRealtime())"));
        assertEquals("App-Start und Boot starten über denselben Stufen-Ablauf (0.1.38.10)", 2, count(m, "starteStufen(kameraWarteMs)"));
    }

    // ------------------------------------------------------------- V3
    @Test
    public void watchdogWartetNurKurzNachDemBoot() throws Exception {
        String d = quelle("src/main/java/at/greenchili/surfmate/launcher/DaemonLauncher.kt");
        assertTrue(d.contains("\"UP=\\$(cut -d. -f1 /proc/uptime 2>/dev/null)\""));
        assertTrue(d.contains("-ge 120 ] 2>/dev/null; then"));
        assertTrue("die 5 s bleiben nur für den frischen Boot", d.contains("\"  sleep 5\","));
        assertEquals("kein unbedingtes sleep 5 mehr", 0, count(d, "\"sleep 5\","));
        // das eine \"sleep 5\" steht NICHT mehr ohne Bedingung vor der Schleife
        assertFalse(d.contains("\"sleep 5\",\n                \"\",\n                \"while true; do\","));
    }

    // ------------------------------------------------------------- V5
    @Test
    public void startAbfrageAnfangsAlle500Millisekunden() {
        StartWarten w = new StartWarten(10_000L);
        assertEquals(500L, StartWarten.SCHNELL_INTERVALL_MS);
        assertEquals(500L, w.naechstesIntervallMs(10_000L));
        assertEquals(500L, w.naechstesIntervallMs(10_000L + 29_999L));
        assertEquals(StartWarten.INTERVALL_MS, w.naechstesIntervallMs(10_000L + 30_000L));
        assertEquals(StartWarten.INTERVALL_MS, w.naechstesIntervallMs(10_000L + 120_000L));
    }

    @Test
    public void webViewNutztDasNeueIntervall() throws Exception {
        String w = quelle("src/main/java/at/greenchili/surfmate/ui/fragment/WebViewFragment.kt");
        assertFalse(w.contains("startAbfrage(runde) }, at.greenchili.surfmate.ui.StartWarten.INTERVALL_MS)"));
        assertTrue(w.contains("naechstesIntervallMs(android.os.SystemClock.elapsedRealtime())"));
    }

    private static int count(String s, String t) {
        int n = 0, i = 0;
        while ((i = s.indexOf(t, i)) >= 0) { n++; i += t.length(); }
        return n;
    }
}
