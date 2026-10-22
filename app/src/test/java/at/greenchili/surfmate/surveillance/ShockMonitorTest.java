package at.greenchili.surfmate.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Erschütterung im Parkmodus: Einschaltlogik und Verdrahtung (Koordination 21.10.2026). */
public class ShockMonitorTest {

    private static String src(String p) throws Exception {
        return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    public void legacyInstallStaysOffNewInstallDefaultsToMedium() throws Exception {
        // 0.1.9.1: nicht gewählt = nur Erschütterung → Sensor an (mittel); „Wächter aus“ → aus
        assertEquals(ShockDetector.Empfindlichkeit.MITTEL, ShockMonitor.empfindlichkeit(null));
        assertEquals(ShockDetector.Empfindlichkeit.MITTEL, ShockMonitor.empfindlichkeit(new JSONObject()));
        assertEquals(ShockDetector.Empfindlichkeit.MITTEL,
                ShockMonitor.empfindlichkeit(new JSONObject().put("shockSensitivity", "auto")));
        assertNull(ShockMonitor.empfindlichkeit(new JSONObject().put("triggerMode", "aus")));
        assertEquals(ShockDetector.Empfindlichkeit.MITTEL,
                ShockMonitor.empfindlichkeit(new JSONObject().put("triggerMode", "shock")));
        assertEquals(ShockDetector.Empfindlichkeit.MITTEL,
                ShockMonitor.empfindlichkeit(new JSONObject().put("triggerMode", "ai_private").put("shockSensitivity", "auto")));
        assertNull(ShockMonitor.empfindlichkeit(new JSONObject().put("triggerMode", "shock").put("shockSensitivity", "aus")));
        assertEquals(ShockDetector.Empfindlichkeit.HOCH,
                ShockMonitor.empfindlichkeit(new JSONObject().put("shockSensitivity", "hoch")));
    }

    @Test
    public void activeOnlyWhenArmedParkedAndNoGuardStop() throws Exception {
        String m = src("src/main/java/at/greenchili/surfmate/surveillance/ShockMonitor.java");
        int soll = m.indexOf("boolean soll = e != null");
        String bed = m.substring(soll, m.indexOf(";", soll));
        assertTrue(bed.contains("isAccStateAuthoritative()"));
        assertTrue(bed.contains("!at.greenchili.surfmate.monitor.AccMonitor.isAccOn()"));
        assertTrue(bed.contains("CameraDaemon.isSentryArmed()"));
        assertTrue(bed.contains("!GuardStopMonitor.blocksArming()"));
        assertTrue("Sensor in sparsamer Rate", m.contains("ImuRate.PARK"));
        assertTrue("Sensor wieder frei, wenn RoadSense ihn nicht braucht", m.contains("rs == null || !rs.isStarted()"));
    }

    @Test
    public void wiring() throws Exception {
        String ipc = src("src/main/java/at/greenchili/surfmate/server/SurveillanceIpcServer.java");
        assertTrue(ipc.contains("ShockMonitor.onImuBatch(request);"));
        String d = src("src/main/java/at/greenchili/surfmate/daemon/CameraDaemon.java");
        assertTrue(d.contains("ShockMonitor.start();"));
        assertTrue(d.contains("ShockMonitor.putStatus(status);"));
        String e = src("src/main/java/at/greenchili/surfmate/surveillance/SurveillanceEngineGpu.java");
        int t = e.indexOf("public boolean triggerShockEvent(");
        assertTrue(t > 0 && e.indexOf("startRecording();", t) > t);
        String sc = src("src/main/java/at/greenchili/surfmate/roadsense/sidecar/RoadSenseImuSidecarService.kt");
        assertTrue(sc.contains("enum class ImuRate { FAST, SLOW, PARK }"));
        assertTrue("25 Hz", sc.contains("PARK_SAMPLING_US = 40_000"));
        assertTrue("1 s Bündelung im Sensor-FIFO", sc.contains("PARK_REPORT_LATENCY_US = 1_000_000"));
        assertTrue("Leistung des Sensors wird geloggt", sc.contains("power=${activeAccel.power} mA"));
    }
}
