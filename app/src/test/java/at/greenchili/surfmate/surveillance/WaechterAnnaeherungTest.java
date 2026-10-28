package at.greenchili.surfmate.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 0.1.25 Wächter (WAECHTER_1849_ANALYSE §5a, Koordination): „Person kommt zum Auto“, Nachlauf bei ACC an,
 * ehrliche Schwelle im Log. Zeiten aus dem Fall 28.10. 18:49 (Sekunden nach 18:49:00).
 */
public class WaechterAnnaeherungTest {

    private static long ms(double sek) {
        return Math.round(sek * 1000);
    }

    @Test
    public void personKommtZumAutoNurMitPersonNahUndAnnaeherung() {
        long beginn = ms(29.726);   // „Motion started“
        long person = ms(30.297);   // YOLO person 0,38
        assertTrue(SurveillanceEngineGpu.personKommtZumAuto(true, person, beginn, true, true));
        assertFalse("Person VOR der Sequenz zählt nicht", SurveillanceEngineGpu.personKommtZumAuto(true, ms(20), beginn, true, true));
        assertFalse("ohne Personentreffer nie", SurveillanceEngineGpu.personKommtZumAuto(true, 0, beginn, true, true));
        assertFalse("nicht nah (Passant in der Ferne)", SurveillanceEngineGpu.personKommtZumAuto(true, person, beginn, false, true));
        assertFalse("keine Annäherung", SurveillanceEngineGpu.personKommtZumAuto(true, person, beginn, true, false));
        assertFalse("keine laufende Sequenz", SurveillanceEngineGpu.personKommtZumAuto(false, person, beginn, true, true));
        assertEquals(500, SurveillanceEngineGpu.PERSON_APPROACH_TRIGGER_MS);
    }

    @Test
    public void fall1849LoestVorDerTuerAus() {
        long beginn = ms(29.726), person = ms(30.297), tuer = ms(31.368);
        // erster verarbeiteter Frame nach dem Personentreffer (Log: 30.528)
        long frame = ms(30.528);
        assertTrue(SurveillanceEngineGpu.personKommtZumAuto(true, person, beginn, true, true));
        assertTrue("0,5 s erreicht", frame - beginn >= SurveillanceEngineGpu.PERSON_APPROACH_TRIGGER_MS);
        assertTrue("vor dem Türöffnen", frame < tuer);
        assertEquals("Person kommt zum Auto", SurveillanceEngineGpu.schwellenGrund(500, 2000, true));
        assertEquals("vertrauenswürdiges Verweilen", SurveillanceEngineGpu.schwellenGrund(500, 2000, false));
        assertEquals("Grundzeit (Verweilen)", SurveillanceEngineGpu.schwellenGrund(2000, 2000, false));
    }

    @Test
    public void nachlaufBeiAccAnHoechstensZehnSekunden() {
        long ausgeloest = ms(31.422), accAn = ms(31.757);
        long geplantesEnde = ausgeloest + 10_000;   // Post-record 10 s
        // gleich nach ACC an: fast der ganze Nachlauf bleibt (vorher: sofort aus, nur 0,5 s)
        assertEquals(geplantesEnde - accAn, SurveillanceEngineGpu.nachlaufRestMs(true, geplantesEnde, accAn, accAn));
        // geplantes Ende später als ACC an + 10 s → bei ACC an + 10 s Schluss
        assertEquals(10_000, SurveillanceEngineGpu.nachlaufRestMs(true, accAn + 60_000, accAn, accAn));
        assertEquals(0, SurveillanceEngineGpu.nachlaufRestMs(true, accAn + 60_000, accAn, accAn + 10_000));
        assertEquals("ohne laufende Aufnahme kein Nachlauf", 0, SurveillanceEngineGpu.nachlaufRestMs(false, geplantesEnde, accAn, accAn));
        assertEquals(SurveillanceEngineGpu.ACC_NACHLAUF_MAX_MS, 10_000);
    }

    @Test
    public void zeitleisteEndetMitDemVideo() {
        // 18:49: Video 6,0 s, Zeitleiste erst 7 s später geschrieben → 11 s
        long start = ms(25.94), ende = ms(31.95), geschrieben = ms(38.67);
        assertEquals(ende - start, EventTimelineCollector.dauerMs(start, ende, geschrieben));
        assertEquals("ohne bekanntes Ende wie bisher", geschrieben - start, EventTimelineCollector.dauerMs(start, 0, geschrieben));
    }

    @Test
    public void verdrahtung() throws Exception {
        String s = lies("app/src/main/java/at/greenchili/surfmate/surveillance/SurveillanceEngineGpu.java");
        int acc = s.indexOf("if (at.greenchili.surfmate.monitor.AccMonitor.isAccOn()) {");
        assertTrue("erst Nachlauf, dann abschalten", s.indexOf("accNachlaufRestMs()", acc) < s.indexOf("auto-disabling", acc));
        assertTrue("PERSON-Weg vor der KI-Sperre", s.contains("boolean aiRecentlyConfirmed = sequenceConfirmed || personPfadAktiv;"));
        assertTrue("ehrliche Schwelle", s.contains("Motion threshold: %.1fs sustained") && s.contains("base %.1fs sustained"));
        assertTrue(s.contains("timelineCollector.markiereAufnahmeEnde()"));
        String p = lies("app/src/main/java/at/greenchili/surfmate/surveillance/GpuSurveillancePipeline.java");
        int on = p.indexOf("public void onAccOn()");
        assertTrue("onAccOn wartet den Nachlauf ab, bevor gestoppt wird",
                p.indexOf("s.accNachlaufRestMs()", on) < p.indexOf("recorder.stopRecording();", on));
    }

    static String lies(String rel) throws Exception {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (p != null) {
            for (Path c : new Path[] {p.resolve(rel), p.resolve(rel.replaceFirst("^app/", ""))}) {
                if (Files.isRegularFile(c)) return new String(Files.readAllBytes(c), "UTF-8");
            }
            p = p.getParent();
        }
        throw new java.io.FileNotFoundException(rel);
    }
}
