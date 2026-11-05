package at.greenchili.surfmate.recording;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.junit.Test;

import java.nio.file.Paths;

/**
 * 0.1.38.11 (K1, ANALYSE_KAMERA_STALL_013810): Nach dem Entschärfen bei Zündung aus blieb die Pano-Pipeline im Leerlauf
 * offen, niemand schloss sie, und der Stall-Wächter öffnete die Kamera alle 10–15 s neu (Surf 05.11. ab 07:24).
 * Vertrag: (a) Zündung aus + kein Verbraucher seit der Karenz → Pipeline zu, (b) Leerlauf ohne Verbraucher → kein
 * Stall-Neustart, (c) Zündung aus ohne Verbraucher → kein Prozess-Neustart. Eine Regel: {@link KameraParkRegel}.
 */
public class KameraLeerlaufTest {

    private static KameraParkRegel.Lage accAusFrei() {
        KameraParkRegel.Lage l = new KameraParkRegel.Lage();
        l.zuendungAn = false;
        return l;
    }

    private static String lies(String p) throws Exception {
        return Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/" + p));
    }

    // ---------------------------------------------------------------- Regel

    @Test
    public void zuendungAusOhneVerbraucherErstNachKarenz() {
        KameraParkRegel r = new KameraParkRegel();
        KameraParkRegel.Lage l = accAusFrei();
        assertTrue(KameraParkRegel.ohneVerbraucherBeiAccAus(l));
        assertFalse(r.schliessenBeiAccAus(l, 0));
        assertFalse(r.schliessenBeiAccAus(l, KameraParkRegel.KARENZ_ACC_AUS_MS - 1));
        assertTrue(r.schliessenBeiAccAus(l, KameraParkRegel.KARENZ_ACC_AUS_MS));
    }

    @Test
    public void ohneSchalterUndGangGueltig() {
        KameraParkRegel.Lage l = accAusFrei();
        l.schalterAn = false;   // Park-Freigabe-Schalter gilt nur bei Zündung an
        l.gangP = false;
        assertTrue(KameraParkRegel.ohneVerbraucherBeiAccAus(l));
    }

    @Test
    public void jederVerbraucherHaeltDieKameraOffen() {
        for (int i = 0; i < 7; i++) {
            KameraParkRegel r = new KameraParkRegel();
            KameraParkRegel.Lage l = accAusFrei();
            switch (i) {
                case 0: l.zuendungAn = true; break;
                case 1: l.dauerhafteKamera = true; break;   // DiLink 4/5: OEM-Vorbild, Kamera bleibt offen
                case 2: l.aufnahmeOderWaechter = true; break;
                case 3: l.streamAktiv = true; break;
                case 4: l.kameraAnsichtAktiv = true; break;
                case 5: l.totwinkelAnsichtSichtbar = true; break;
                default: l.totwinkelKalibrierung = true; break;
            }
            assertFalse("Fall " + i, KameraParkRegel.ohneVerbraucherBeiAccAus(l));
            r.schliessenBeiAccAus(l, 0);
            assertFalse("Fall " + i, r.schliessenBeiAccAus(l, KameraParkRegel.KARENZ_ACC_AUS_MS * 10));
        }
    }

    @Test
    public void verbraucherSetztKarenzZurueck() {
        KameraParkRegel r = new KameraParkRegel();
        KameraParkRegel.Lage l = accAusFrei();
        r.schliessenBeiAccAus(l, 0);
        l.aufnahmeOderWaechter = true;   // z. B. wieder verriegelt → Wächter scharf
        assertFalse(r.schliessenBeiAccAus(l, KameraParkRegel.KARENZ_ACC_AUS_MS));
        l.aufnahmeOderWaechter = false;
        assertFalse(r.schliessenBeiAccAus(l, KameraParkRegel.KARENZ_ACC_AUS_MS + 1));
        assertTrue(r.schliessenBeiAccAus(l, KameraParkRegel.KARENZ_ACC_AUS_MS * 2 + 1));
    }

    @Test
    public void parkFreigabeUnveraendertUndEineListe() throws Exception {
        KameraParkRegel.Lage l = new KameraParkRegel.Lage();
        l.schalterAn = true; l.zuendungAn = true; l.gangP = true;
        assertTrue(KameraParkRegel.bedingungenErfuellt(l));
        assertFalse(KameraParkRegel.ohneVerbraucherBeiAccAus(l));   // Zündung an: nur die Park-Freigabe
        String regel = lies("recording/KameraParkRegel.java");
        assertTrue(regel.contains("l.schalterAn && l.zuendungAn && l.gangP && keinVerbraucher(l)"));
        assertTrue(regel.contains("!l.zuendungAn && keinVerbraucher(l)"));
    }

    // ---------------------------------------------------------------- Kamera (b)

    @Test
    public void leerlaufMarkierungVerfaellt() {
        long g = at.greenchili.surfmate.camera.PanoramicCameraGpuLeerlaufZugang.gueltigMs();
        assertTrue(at.greenchili.surfmate.camera.PanoramicCameraGpuLeerlaufZugang.gueltig(1_000L, 1_000L + g - 1));
        assertFalse(at.greenchili.surfmate.camera.PanoramicCameraGpuLeerlaufZugang.gueltig(1_000L, 1_000L + g));
        assertFalse("nie gesetzt", at.greenchili.surfmate.camera.PanoramicCameraGpuLeerlaufZugang.gueltig(0L, 5_000L));
        assertTrue("länger als der 30-s-Takt", g > 30_000L);
    }

    // ---------------------------------------------------------------- Verdrahtung (Quelltext)

    @Test
    public void verdrahtung() throws Exception {
        String rmm = lies("recording/RecordingModeManager.java");
        // (a) der 30-s-Takt wertet beide Regeln aus und schließt bei Zündung aus ohne Verbraucher
        int pruef = rmm.indexOf("private void parkFreigabePruefen()");
        String takt = rmm.substring(pruef, rmm.indexOf("private void parkWiederOeffnen", pruef));
        assertTrue(takt.contains("boolean park = parkFreigabeAktiv();"));
        assertTrue(takt.contains("boolean accAus = accAusOhneVerbraucher();"));
        assertTrue(takt.contains("KameraParkRegel.ohneVerbraucherBeiAccAus(parkLage())"));
        assertTrue(takt.contains("pipeline.stop();"));
        assertTrue(takt.contains("CameraDaemon.stopAvcKeepAlive();"));
        assertTrue(rmm.contains("parkRegel.schliessenBeiAccAus(parkLage(), android.os.SystemClock.elapsedRealtime())"));
        // (b) nur die unterste Stufe der Leiter ist „ohne Verbraucher“, und der Abgleich gibt es an die Kamera
        assertTrue(rmm.contains("\"no active consumer (idle)\", true);"));
        assertEquals("genau eine Stufe ohne Verbraucher", 1, zaehle(rmm, "\", true);"));
        assertTrue(rmm.contains("pipeline.setCameraOhneVerbraucher(want.ohneVerbraucher);"));
        // (c) forceWarmupRestart: bei Zündung aus ohne Verbraucher schließen, VOR dem Prozess-Neustart
        int f = rmm.indexOf("public void forceWarmupRestart(String reason)");
        String force = rmm.substring(f, rmm.indexOf("full camera/GL pipeline", f));
        int schliessen = force.indexOf("!acc && KameraParkRegel.ohneVerbraucherBeiAccAus(parkLage())");
        int neustart = force.indexOf("requestProcessRestartPreservingTrip");
        assertTrue(schliessen > 0 && neustart > schliessen);

        String cam = lies("camera/PanoramicCameraGpu.java");
        int wd = cam.indexOf("Frame stall is HAL issue — restarting camera");
        int leer = cam.lastIndexOf("leerlaufGueltig(ohneVerbraucherSeitMs", wd);
        assertTrue("Leerlauf-Zweig vor dem Neustart", leer > 0 && wd - leer < 1200);
        assertTrue(cam.contains("if (enabled) setOhneVerbraucher(false);"));
        assertTrue(cam.contains("&& !leerlaufStill)"));
        // DiLink 4/5 behalten ihre eigenen Wege
        int di4 = cam.indexOf("maybeRestartStalledDilink4Producer(now, episodeMs,");
        assertTrue(di4 > 0 && di4 < wd);

        String pipe = lies("surveillance/GpuSurveillancePipeline.java");
        assertTrue(pipe.contains("public void setCameraOhneVerbraucher(boolean ohne)"));
    }

    private static int zaehle(String s, String teil) {
        int n = 0, i = 0;
        while ((i = s.indexOf(teil, i)) >= 0) { n++; i += teil.length(); }
        return n;
    }
}
