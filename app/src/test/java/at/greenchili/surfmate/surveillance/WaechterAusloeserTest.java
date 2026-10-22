package at.greenchili.surfmate.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.einstellungen.Laenderprofil;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Wächter-Auslöser a/b/c + KI-Bestätigung (Koordination 21.10.2026). */
public class WaechterAusloeserTest {

    private static final Laenderprofil.Land AT = Laenderprofil.Land.ANDERES;
    private static final Laenderprofil.Land CH = Laenderprofil.Land.SCHWEIZ;
    private static final Laenderprofil.Land UNBEKANNT = Laenderprofil.Land.UNBEKANNT;

    private static JSONArray garage() throws Exception {
        return new JSONArray().put(new JSONObject().put("lat", 47.8).put("lon", 13.04).put("radiusM", 50));
    }

    /** 0.1.9.1 (SETUP_WIZARD Teil A): nichts gespeichert = nicht gewählt → keine Bewegungs-/KI-Auslösung. */
    @Test
    public void nichtGewaehltNurErschuetterung() throws Exception {
        for (String gesetzt : new String[] {null, "", "quatsch"}) {
            WaechterAusloeser.Lage l = WaechterAusloeser.bestimme(gesetzt, AT, null, 0, 0, false);
            assertFalse(l.kiErlaubt);
            assertFalse(l.kiPflicht);
            assertEquals("shock", l.wirksam);
            assertEquals(WaechterAusloeser.Urteil.UNTERDRUECKEN, WaechterAusloeser.urteil(l, true, true, true));
        }
        WaechterAusloeser.Lage aus = WaechterAusloeser.bestimme("aus", AT, null, 0, 0, false);
        assertFalse(aus.kiErlaubt);
        assertEquals("aus", aus.wirksam);
        // gespeicherte Wahl (b) bleibt unverändert, mit KI-Bestätigung und Fail-open
        WaechterAusloeser.Lage b = WaechterAusloeser.bestimme("ai_everywhere", AT, null, 0, 0, false);
        assertTrue(b.kiErlaubt);
        assertTrue(b.kiPflicht);
        assertEquals("ai_everywhere", b.wirksam);
        assertEquals(WaechterAusloeser.Urteil.FAIL_OPEN_KI_AUS, WaechterAusloeser.urteil(b, false, true, false));
    }

    @Test
    public void shockModeAndOutsideZoneAlwaysSuppress() throws Exception {
        WaechterAusloeser.Lage a = WaechterAusloeser.bestimme("shock", AT, null, 0, 0, false);
        assertEquals(WaechterAusloeser.Urteil.UNTERDRUECKEN, WaechterAusloeser.urteil(a, true, true, true));
        assertEquals(WaechterAusloeser.Urteil.UNTERDRUECKEN, WaechterAusloeser.urteil(a, false, true, false));
    }

    @Test
    public void modeSwitch() throws Exception {
        WaechterAusloeser.Lage a = WaechterAusloeser.bestimme("shock", AT, garage(), 47.8, 13.04, true);
        assertFalse(a.kiErlaubt);
        assertEquals("shock", a.wirksam);
        WaechterAusloeser.Lage b = WaechterAusloeser.bestimme("ai_everywhere", AT, null, 48.2, 16.37, true);
        assertTrue(b.kiErlaubt);
        assertTrue("b gewählt: nur nach KI-Bestätigung", b.kiPflicht);
        WaechterAusloeser.Lage c = WaechterAusloeser.bestimme("ai_private", AT, garage(), 47.8, 13.04, true);
        assertTrue(c.kiErlaubt);
        assertTrue(c.kiPflicht);
    }

    @Test
    public void zoneBoundary() throws Exception {
        // 50 m Radius: 33 m innen ja, 60 m außen nein
        assertTrue(WaechterAusloeser.bestimme("ai_private", AT, garage(), 47.8003, 13.04, true).kiErlaubt);
        assertFalse(WaechterAusloeser.bestimme("ai_private", AT, garage(), 47.80054, 13.04, true).kiErlaubt);
        assertFalse("ohne GPS-Fix nie KI bei c", WaechterAusloeser.bestimme("ai_private", AT, garage(), 47.8, 13.04, false).kiErlaubt);
        assertEquals("shock", WaechterAusloeser.bestimme("ai_private", AT, new JSONArray(), 47.8, 13.04, true).wirksam);
    }

    @Test
    public void switzerlandBlocksAiEverywhere() throws Exception {
        WaechterAusloeser.Lage b = WaechterAusloeser.bestimme("ai_everywhere", CH, null, 47.37, 8.54, true);
        assertFalse(b.kiErlaubt);
        assertEquals("shock", b.wirksam);
        WaechterAusloeser.Lage mitOrt = WaechterAusloeser.bestimme("ai_everywhere", CH, garage(), 47.8, 13.04, true);
        assertTrue("in CH fällt b auf c zurück", mitOrt.kiErlaubt);
        assertEquals("ai_private", mitOrt.wirksam);
        WaechterAusloeser.Lage alt = WaechterAusloeser.bestimme(null, CH, null, 47.37, 8.54, true);
        assertFalse("auch die Alt-Installation in CH", alt.kiErlaubt);
    }

    @Test
    public void engineAppliesGateLastBeforeTrigger() throws Exception {
        String src = new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/surveillance/SurveillanceEngineGpu.java")), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        int ai = src.indexOf("if (aiAvailable && !aiRecentlyConfirmed) {\n                        shouldSuppress = true;");
        int gate = src.indexOf("WaechterAusloeser.lage(", ai);
        int decide = src.indexOf("if (shouldSuppress) {", gate);
        int trigger = src.indexOf(">>> RECORDING TRIGGERED <<<", decide);
        assertTrue(ai > 0 && gate > ai && decide > gate && trigger > decide);
        String block = src.substring(gate, decide);
        assertTrue(block.contains("WaechterAusloeser.urteil("));
        assertTrue(block.contains("Urteil.UNTERDRUECKEN"));
        assertTrue("fail-open meldet und lädt nach", block.contains("kiAusMelden()"));
    }

    @Test
    public void everyCameraTriggerPathPassesTheGate() throws Exception {
        String src = new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/surveillance/SurveillanceEngineGpu.java")), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        int n = 0;
        for (int i = src.indexOf("long startedGeneration = startRecording();"); i >= 0;
             i = src.indexOf("long startedGeneration = startRecording();", i + 1)) {
            n++;
            // Der nächste Riegel (bzw. Erschütterungs-Einstieg) vor diesem Aufruf muss hinter dem
            // vorigen startRecording()-Aufruf liegen – sonst gehört er zu einem anderen Pfad.
            int vorigerAufruf = src.lastIndexOf("startRecording();", i - 1);
            int riegel = src.lastIndexOf("WaechterAusloeser.lage(", i);
            int shock = src.lastIndexOf("triggerShockEvent(", i);
            assertTrue("Auslösepfad ohne a/b/c-Riegel bei Offset " + i,
                    Math.max(riegel, shock) > vorigerAufruf);
        }
        assertTrue("mindestens die drei Kamera-Auslösepfade", n >= 3);
    }

    @Test
    public void lateYoloAssetsReachTheLiveEngine() throws Exception {
        String pipe = new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/surveillance/GpuSurveillancePipeline.java")), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        int already = pipe.indexOf("logger.warn(\"Already initialized\");");
        int forward = pipe.lastIndexOf("sentry.provideYoloAssets(assetManager, context);", already);
        assertTrue("späte Assets gehen an die Engine, bevor 'Already initialized' zurückkehrt",
                forward > 0 && already - forward < 400);
        String eng = new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/surveillance/SurveillanceEngineGpu.java")), StandardCharsets.UTF_8);
        int m = eng.indexOf("public synchronized void provideYoloAssets(");
        assertTrue(m > 0);
        assertTrue(eng.indexOf("reloadYoloDetectorIfPossible();", m) > m);
    }
}
