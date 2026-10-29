package at.greenchili.surfmate.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** 0.1.29: Grund für verworfene Kacheln (Befund 28.10. 18:49 / 29.10. 14:21) und native Salienz-Prüfung. */
public class VerworfenDiagTest {

    @Test
    public void gruendeInDerReihenfolgeDerNativenStufen() {
        assertEquals("Lichtwechsel", VerworfenDiag.grund(true, 0, 30, 0, 0, 0f, 1, 2, 2));
        assertEquals("Fläche > 25 %, als Lichtblitz gewertet", VerworfenDiag.grund(false, 1, 30, 0, 0, 0f, 1, 2, 2));
        assertEquals("Fläche > 25 %, als Lichtblitz gewertet", VerworfenDiag.grund(false, 0, 34, 0, 0, 0f, 1, 2, 2));
        assertEquals("noch nicht bestätigt", VerworfenDiag.grund(false, 0, 6, 0, 0, 0f, 1, 2, 2));
        // Prüfstand 18:49, Heckkachel: nahe Person, Schwerpunkt oben im Bild
        assertEquals("Zone (Schwerpunkt Zeile 0.5 < 2, gilt als zu weit weg)",
                VerworfenDiag.grund(false, 0, 17, 4, 4, 0.5f, 1, 2, 2));
        assertEquals("unter Alarmschwelle", VerworfenDiag.grund(false, 0, 5, 1, 1, 3f, 1, 2, 2));
        assertEquals("nur Vorbeigehen", VerworfenDiag.grund(false, 0, 8, 5, 5, 4f, 1, 2, 2));
        assertEquals("zu klein", VerworfenDiag.grund(false, 0, 8, 3, 1, 4f, 2, 2, 2));
    }

    @Test
    public void nurBeiVielBewegungOhneMotionUndGedrosselt() {
        assertTrue(VerworfenDiag.loggen(4, false, 10_000L, 0L));
        assertFalse("zu wenig", VerworfenDiag.loggen(3, false, 10_000L, 0L));
        assertFalse("Bewegung erkannt", VerworfenDiag.loggen(20, true, 10_000L, 0L));
        assertFalse("gedrosselt", VerworfenDiag.loggen(20, false, 10_000L, 6_000L));
        assertTrue(VerworfenDiag.loggen(20, false, 11_000L, 6_000L));
    }

    @Test
    public void nativeSalienzPruefungImmerAnJavaWegBeimSchalter() throws Exception {
        assertTrue(SurveillanceEngineGpu.SALIENZ_PRUEFUNG_NATIV);
        String se = new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/surveillance/SurveillanceEngineGpu.java")), StandardCharsets.UTF_8);
        assertTrue(se.contains("pipelineV2Config.salienceEnabled = SALIENZ_PRUEFUNG_NATIV;"));
        assertFalse(se.contains("pipelineV2Config.salienceEnabled = config.isMotionSalienceEnabled();"));
        // Java-Auslöseweg bleibt beim Schalter
        assertTrue(se.contains("this.salienceEnabled = config.isMotionSalienceEnabled();"));
    }
}
