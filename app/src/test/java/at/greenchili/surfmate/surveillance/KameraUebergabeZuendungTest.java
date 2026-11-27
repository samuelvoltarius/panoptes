package at.greenchili.surfmate.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.junit.Test;

import java.nio.file.Paths;

/**
 * 0.1.43.22: Kamera-Übergabe bei Zündung an (DiLink 3). Grundlage OverDrive PR #674 (MIT) plus die Nachbesserung des
 * Maintainers im Release-Commit d50b89966 (17.11.2026).
 *
 * <p>Vertrag: (a) AVC-HAL-Aufwärmen läuft parallel zur Übergabe an die BYD-App, über dasselbe Warmup-Gate;
 * (b) das feste 3-s-Warten vor der ersten Besitzer-Abfrage bleibt; (c) die Kamera wird auf DiLink 3 bei Zündung an
 * immer freigegeben und neu geöffnet (kein „Live-Sekundär behalten“); (d) das erste {@code cam_*}-Video öffnet erst
 * nach zwei frisch codierten Bildern, ohne geparkten Vorlauf, mit Verfall nach 90 s und Sofort-Anstoß;
 * (e) unser Leerlauf-Fix 0.1.38.11 (K1, „Rung 4: NO owner“) bleibt unberührt.
 */
public class KameraUebergabeZuendungTest {

    private static String lies(String p) throws Exception {
        return Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/" + p));
    }

    // ---------------------------------------------------------------- Regel (rein)

    @Test
    public void ohneSperreNieWarten() {
        assertEquals(FrischBildSperre.AUS, FrischBildSperre.pruefe(0L, 0L, 2, 5_000L, 0L));
        assertFalse(FrischBildSperre.sperrt(0L, 0L, 2, 5_000L, 0L));
    }

    @Test
    public void wartetBisZweiFrischeBilder() {
        long gesetzt = 10_000L;
        long basis = 500L;
        assertTrue(FrischBildSperre.sperrt(gesetzt, basis, 2, gesetzt + 1_000L, basis));
        // ein vor dem Neu-Öffnen eingereihtes Bild reicht nicht
        assertTrue(FrischBildSperre.sperrt(gesetzt, basis, 2, gesetzt + 6_000L, basis + 1));
        assertEquals(FrischBildSperre.FRISCH, FrischBildSperre.pruefe(gesetzt, basis, 2, gesetzt + 7_000L, basis + 2));
        assertFalse(FrischBildSperre.sperrt(gesetzt, basis, 2, gesetzt + 7_000L, basis + 40));
    }

    @Test
    public void mindestensEinBild() {
        assertTrue(FrischBildSperre.sperrt(1_000L, 7L, 0, 2_000L, 7L));
        assertFalse(FrischBildSperre.sperrt(1_000L, 7L, 0, 2_000L, 8L));
    }

    @Test
    public void verfaelltNach90Sekunden() {
        long gesetzt = 20_000L;
        assertEquals(90_000L, FrischBildSperre.VERFALL_MS);
        assertTrue(FrischBildSperre.sperrt(gesetzt, 0L, 2, gesetzt + FrischBildSperre.VERFALL_MS, 0L));
        assertEquals(FrischBildSperre.VERFALLEN,
                FrischBildSperre.pruefe(gesetzt, 0L, 2, gesetzt + FrischBildSperre.VERFALL_MS + 1, 0L));
        // Uhr rückwärts: nie ewig sperren
        assertEquals(FrischBildSperre.VERFALLEN, FrischBildSperre.pruefe(gesetzt, 0L, 2, gesetzt - 1, 0L));
    }

    @Test
    public void nurFahrtVideos() {
        assertTrue(FrischBildSperre.betrifft("cam_20261126_081500.mp4"));
        assertFalse(FrischBildSperre.betrifft("event_20261126_081500.mp4"));
        assertFalse(FrischBildSperre.betrifft("proximity_20261126_081500.mp4"));
        assertFalse(FrischBildSperre.betrifft(null));
        assertEquals(2, FrischBildSperre.MIN_BILDER);
    }

    // ---------------------------------------------------------------- Verdrahtung (Quelltext)

    @Test
    public void halAufwaermenParallelUndDreiSekundenBleiben() throws Exception {
        String cam = lies("camera/PanoramicCameraGpu.java");
        int r = cam.indexOf("public void reopenCamera(long maxWaitMs)");
        assertTrue(r > 0);
        String reopen = cam.substring(r, cam.indexOf("openLegacyCameraWithHardTimeout(\"ACC ON camera reopen\")", r));
        int zu = reopen.indexOf("closeCameraForPath(toClose);");
        int warm = reopen.indexOf("\"AvcHalWarmup-Handoff\"");
        int dreiS = reopen.indexOf("final long minWaitMs = 3000;");
        int poll = reopen.indexOf("cameraCoordinator.checkNativeAppActive()");
        assertTrue("Aufwärmen nach dem Schließen", zu > 0 && warm > zu);
        assertTrue("Aufwärmen vor dem 3-s-Warten", dreiS > warm);
        assertTrue("3 s vor der ersten Besitzer-Abfrage", poll > dreiS);
        assertTrue(reopen.contains("sleepWithHeartbeat(minWaitMs);"));
        assertTrue(reopen.contains("+ (maxWaitMs - minWaitMs);"));
        assertTrue("gleiches Gate wie der Kalt-Öffnungsweg", reopen.contains("AvcHalWarmup.warmupBeforeColdOpen("));
        assertTrue("nur auf dem Legacy-Weg (DiLink 3)", reopen.contains("if (legacyOpenAttempt) {\n                Thread warmupThread"));
        assertFalse("kein Sofort-Abfragen", cam.contains("Polling fallback immediately"));

        String warmup = lies("camera/AvcHalWarmup.java");
        assertTrue("Gate fasst zusammen", warmup.contains("synchronized (WARMUP_LANE)"));
        assertTrue(warmup.contains("Reusing completed AVC warmup"));
    }

    @Test
    public void zuendungAnOeffnetImmerNeuUndSperrtDasErsteVideo() throws Exception {
        String pipe = lies("surveillance/GpuSurveillancePipeline.java");
        int a = pipe.indexOf("public void onAccOn()");
        String acc = pipe.substring(a, pipe.indexOf("} catch (Throwable t) {", pipe.indexOf("ACC ON transition complete - all recordings finalized\");", a)));
        assertFalse("kein Live-Sekundär-Behalten", pipe.contains("hasLiveNativeSharedCamera"));
        int reopen = acc.indexOf("camera.reopenCamera();");
        int sperre = acc.indexOf("requireFreshFrameForNextDriveClip(");
        assertTrue("Sperre NACH dem Neu-Öffnen", reopen > 0 && sperre > reopen);
        assertTrue(acc.contains("FrischBildSperre.MIN_BILDER, freshFrameKickoff())"));
        // Zündungs-Anlaufzeit (0.1.43.18) und Wächter-Nachlauf (0.1.25) bleiben vorne
        assertTrue(acc.indexOf("KameraAnlauf.zuendung();") < acc.indexOf("accNachlaufRestMs()"));
        assertTrue(acc.indexOf("accNachlaufRestMs()") < reopen);
        // Anstoß startet die zurückgestellte Aufnahme abseits des Drainers
        int k = pipe.indexOf("private Runnable freshFrameKickoff()");
        assertTrue(k > 0);
        String kick = pipe.substring(k, pipe.indexOf("void checkPendingRecording()", k));
        assertTrue(kick.contains("checkPendingRecording();"));
        assertTrue(kick.contains("\"FreshFrameKickoff\""));

        String cam = lies("camera/PanoramicCameraGpu.java");
        assertFalse(cam.contains("hasLiveNativeSharedCamera"));
        assertFalse(cam.contains("lastCameraFrameElapsedMs"));
    }

    @Test
    public void erstesFahrtVideoLiveOhneVorlauf() throws Exception {
        String enc = lies("surveillance/HardwareEventRecorderGpu.java");
        int sperre = enc.indexOf("if (driveBarrierBlocks(driveBarrier)) {");
        int liveOnly = enc.indexOf("final boolean firstPostSentryDriveClip = driveBarrier > 0L;");
        int vorlauf = enc.indexOf("if (!firstPostSentryDriveClip && !manualClipExportInProgress");
        assertTrue(sperre > 0 && liveOnly > sperre && vorlauf > liveOnly);
        // verbraucht wird erst, wenn ein gültiger Muxer steht (Fehlversuch lässt die Sperre für den Neuversuch)
        String start = enc.substring(sperre, liveOnly);
        assertTrue(start.contains("return false;"));
        // Bildzähler + Anstoß auf dem Drainer, nur für echte codierte Bilder
        int stempel = enc.indexOf("lastEncodedFrameElapsedMs = android.os.SystemClock.elapsedRealtime();\n                    codedFrameCount++;");
        assertTrue(stempel > 0);
        assertTrue(enc.contains("codedFrameCount++;               // 0.1.43.22: Zählsperre erstes Fahrt-Video\n                    dispatchDriveFreshListener();"));
        assertEquals("Zähler nur an einer Stelle erhöht", 1, zaehle(enc, "codedFrameCount++"));
        // IDR beim Scharfschalten
        int arm = enc.indexOf("public void requireFreshFrameForNextDriveClip(int minFrames, Runnable onFresh)");
        String armen = enc.substring(arm, enc.indexOf("public boolean isWaitingForFreshDriveFrame", arm));
        assertTrue(armen.contains("requestSyncFrame();"));
        assertTrue("Sperre zuletzt sichtbar machen",
                armen.indexOf("driveFrameBarrierMs.set(") > armen.indexOf("driveBarrierBaseCount = base;"));

        String mosaic = lies("surveillance/GpuMosaicRecorder.java");
        assertTrue(mosaic.contains("encoder.isWaitingForFreshDriveFrame(outputPath)"));
    }

    @Test
    public void leerlaufFixUnberuehrt() throws Exception {
        String cam = lies("camera/PanoramicCameraGpu.java");
        assertTrue(cam.contains("public void setOhneVerbraucher(boolean ohne)"));
        assertTrue(cam.contains("if (enabled) setOhneVerbraucher(false);"));
        assertTrue(cam.contains("Frame stall im Leerlauf ohne Verbraucher"));
        String rmm = lies("recording/RecordingModeManager.java");
        assertTrue(rmm.contains("// Rung 4: NO owner"));
        assertTrue(rmm.contains("\"no active consumer (idle)\", true);"));
        String pipe = lies("surveillance/GpuSurveillancePipeline.java");
        assertTrue(pipe.contains("public void setCameraOhneVerbraucher(boolean ohne)"));
    }

    private static int zaehle(String s, String teil) {
        int n = 0, i = 0;
        while ((i = s.indexOf(teil, i)) >= 0) { n++; i += teil.length(); }
        return n;
    }
}
