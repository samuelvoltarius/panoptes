package at.greenchili.surfmate.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 0.1.43.18 (F1): Stall-Wächter auf dem Legacy-Pfad. Befund 19.11.: in P nach der Zündung 45 Neu-Öffnungen alle
 * 10–14 s bei 1 fps für den verdeckten Toten Winkel. Jetzt nur bei Bedarf, Schwelle nach Ziel-fps, mit Bremse.
 */
public class LegacyStallBedarfTest {

    @Test
    public void verdeckterToterWinkelOhneAufnahmeIstKeinBedarf() {
        // genau der Fall vom 19.11.: Bedarf gemeldet = false, nichts sichtbar, nichts schreibt, kein Stream, Wächter aus
        assertFalse(PanoramicCameraGpu.legacyBedarf(true, false, false, false, false, false, false));
    }

    @Test
    public void jederEchteVerbraucherIstBedarf() {
        assertTrue(PanoramicCameraGpu.legacyBedarf(true, true, false, false, false, false, false));   // frische Bilder verlangt
        assertTrue(PanoramicCameraGpu.legacyBedarf(true, false, true, false, false, false, false));   // Toter Winkel sichtbar
        assertTrue(PanoramicCameraGpu.legacyBedarf(true, false, false, true, false, false, false));   // Aufnahme
        assertTrue(PanoramicCameraGpu.legacyBedarf(true, false, false, false, true, false, false));   // Wächter-Ereignis schreibt
        assertTrue(PanoramicCameraGpu.legacyBedarf(true, false, false, false, false, true, false));   // Stream
        assertTrue(PanoramicCameraGpu.legacyBedarf(true, false, false, false, false, false, true));   // Wächter aktiv
    }

    @Test
    public void ohneFrischeMeldungFailClosedWieBisher() {
        assertTrue(PanoramicCameraGpu.legacyBedarf(false, false, false, false, false, false, false));
        assertFalse(PanoramicCameraGpu.legacyBedarfGueltig(0L, 10_000L));                  // nie gemeldet
        assertTrue(PanoramicCameraGpu.legacyBedarfGueltig(10_000L, 40_000L));              // 30-s-Takt
        assertFalse(PanoramicCameraGpu.legacyBedarfGueltig(10_000L,
                10_000L + PanoramicCameraGpu.OHNE_VERBRAUCHER_GUELTIG_MS));                 // verfallen
        assertFalse(PanoramicCameraGpu.legacyBedarfGueltig(10_000L, 5_000L));              // Uhr rückwärts
    }

    @Test
    public void schwelleJePfad() {
        assertEquals(6_000L, PanoramicCameraGpu.stallSchwelleMs(true, false, 1));
        assertEquals(4_000L, PanoramicCameraGpu.stallSchwelleMs(true, false, 30));
        assertEquals(4_000L, PanoramicCameraGpu.stallSchwelleMs(false, false, 1));   // DiLink 4/5 unverändert
        assertEquals(3_000L, PanoramicCameraGpu.stallSchwelleMs(true, true, 1));     // BYD-App aktiv unverändert
    }

    @Test
    public void waechterBenutztDieBremse() throws Exception {
        String s = KameraAnlaufTest.lies("src/main/java/at/greenchili/surfmate/camera/PanoramicCameraGpu.java");
        int a = s.indexOf("No native app — this is a HAL issue, restart camera");
        int b = s.indexOf("No coordinator — just restart");
        assertTrue(a > 0 && b > a);
        assertTrue(s.substring(a, a + 400).contains("legacyStallNeustartOderBremse(bremseUrteil, bremseUhr, true)"));
        assertTrue(s.substring(b, b + 300).contains("legacyStallNeustartOderBremse(bremseUrteil, bremseUhr, false)"));
        // DiLink 4/5 behalten ihre eigenen Wege
        assertTrue(s.contains("maybeRestartStalledDilink4Producer(now, episodeMs,"));
        assertTrue(s.contains("requestDiLink5SourceOnlyRecovery(\n")
                || s.contains("requestDiLink5SourceOnlyRecovery(\r\n")
                || s.contains("\"FastCam bridge frame stall\""));
    }
}
