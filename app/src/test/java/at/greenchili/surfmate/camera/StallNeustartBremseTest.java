package at.greenchili.surfmate.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.camera.StallNeustartBremse.Urteil;

import org.junit.Test;

/** 0.1.43.18 (F1): Schwelle, Obergrenze, Pause mit Rückkehr, Sofort-Reparatur, Ruhe, Uhr rückwärts. */
public class StallNeustartBremseTest {

    @Test
    public void schwelleRichtetSichNachZielFps() {
        assertEquals(6_000L, StallNeustartBremse.schwelleMs(1));   // Befund 19.11.: 1 fps → nicht mehr 4 s
        assertEquals(4_000L, StallNeustartBremse.schwelleMs(2));
        assertEquals(4_000L, StallNeustartBremse.schwelleMs(5));
        assertEquals(4_000L, StallNeustartBremse.schwelleMs(15));
        assertEquals(4_000L, StallNeustartBremse.schwelleMs(30));
        assertEquals(4_000L, StallNeustartBremse.schwelleMs(0));   // unbekannt = wie bisher
        assertEquals(4_000L, StallNeustartBremse.schwelleMs(-3));
        assertTrue(StallNeustartBremse.schwelleMs(1) >= 4 * 1000L);
    }

    @Test
    public void ohneBedarfNieNeustart() {
        StallNeustartBremse b = new StallNeustartBremse();
        for (int i = 0; i < 20; i++) assertEquals(Urteil.KEIN_BEDARF, b.pruefen(1_000L * i, false));
        assertEquals(0, b.inFolgeFuerTest());
    }

    @Test
    public void hoechstensDreiInFolgeDannPauseMitRueckkehr() {
        StallNeustartBremse b = new StallNeustartBremse();
        long t = 100_000L;
        for (int i = 0; i < StallNeustartBremse.MAX_IN_FOLGE; i++) {
            assertEquals(Urteil.NEUSTART, b.pruefen(t, true));
            b.neustartGemacht(t);
            t += 12_000L;
        }
        long pauseStart = t - 12_000L;
        assertTrue(b.pausiert(t));
        assertEquals(Urteil.PAUSE, b.pruefen(t, true));
        assertEquals(Urteil.PAUSE, b.pruefen(pauseStart + 29_999L, true));
        // Rückkehr nach 30 s
        assertEquals(Urteil.NEUSTART, b.pruefen(pauseStart + 30_000L, true));
        // zweite Runde → 60 s, dritte → 120 s, danach bleibt es bei 120 s
        long[] erwartet = {60_000L, 120_000L, 120_000L};
        long jetzt = pauseStart + 30_000L;
        for (long pause : erwartet) {
            for (int i = 0; i < StallNeustartBremse.MAX_IN_FOLGE; i++) {
                assertEquals(Urteil.NEUSTART, b.pruefen(jetzt, true));
                b.neustartGemacht(jetzt);
                if (i < StallNeustartBremse.MAX_IN_FOLGE - 1) jetzt += 10_000L;
            }
            assertEquals(jetzt + pause, b.pauseBis());
            assertEquals(Urteil.PAUSE, b.pruefen(jetzt + pause - 1, true));
            jetzt += pause;
            assertEquals(Urteil.NEUSTART, b.pruefen(jetzt, true));
        }
    }

    @Test
    public void bedarfSteigtRepariertSofortAuchInDerPause() {
        StallNeustartBremse b = new StallNeustartBremse();
        long t = 0L;
        for (int i = 0; i < 3; i++) { b.neustartGemacht(t); t += 10_000L; }
        assertEquals(Urteil.PAUSE, b.pruefen(t, true));
        b.bedarfGestiegen();
        assertEquals(Urteil.NEUSTART, b.pruefen(t, true));
        b.neustartGemacht(t);
        // genau einmal: danach wieder Pause, die Sofort-Reparatur zählt nicht gegen die Grenze
        assertEquals(Urteil.PAUSE, b.pruefen(t + 1_000L, true));
        assertEquals(0, b.inFolgeFuerTest());
    }

    @Test
    public void sofortFreibriefVerfaelltWennBilderLaufen() {
        StallNeustartBremse b = new StallNeustartBremse();
        for (int i = 0; i < 3; i++) b.neustartGemacht(i * 10_000L);
        b.bedarfGestiegen();
        b.ruhig(25_000L);   // Bilder laufen: kein Stillstand, Freibrief weg
        assertEquals(Urteil.PAUSE, b.pruefen(26_000L, true));
    }

    @Test
    public void nachRuheBeginntAllesVonVorn() {
        StallNeustartBremse b = new StallNeustartBremse();
        b.neustartGemacht(0L);
        b.neustartGemacht(10_000L);
        assertEquals(2, b.inFolgeFuerTest());
        b.ruhig(30_000L);
        assertEquals(2, b.inFolgeFuerTest());       // noch keine 60 s Ruhe
        b.ruhig(70_000L);
        assertEquals(0, b.inFolgeFuerTest());       // 60 s ohne Neustart
        b.neustartGemacht(71_000L);
        b.neustartGemacht(81_000L);
        assertFalse(b.pausiert(82_000L));
    }

    @Test
    public void uhrRueckwaertsSperrtNieEndlos() {
        StallNeustartBremse b = new StallNeustartBremse();
        for (int i = 0; i < 3; i++) b.neustartGemacht(1_000_000L + i * 10_000L);
        assertEquals(Urteil.PAUSE, b.pruefen(1_025_000L, true));
        // Uhr springt zurück: keine Pause, die nie endet
        assertEquals(Urteil.NEUSTART, b.pruefen(5_000L, true));
        assertFalse(b.pausiert(5_000L));
    }
}
