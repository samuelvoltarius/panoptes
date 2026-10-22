package at.greenchili.surfmate.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Koordination 0.1.9 (Regel A, Panoptes wird öffentlich): Im ausgelieferten APK kann kein Debug-Weg einen
 * Schreib-Opcode bzw. eine Fahrzeug-Schreibaktion auslösen; der Log-Upload zu einem fremden Server ist entfernt.
 */
public class DebugSchreibsperreTest {

    @Test
    public void alleSchreibendenDebugPfadeGesperrt() {
        String[] pfade = {
                "/api/debug/autoservice/remote-control?opcode=9&confirm=YES",
                "/api/debug/autoservice/sdk-remote-control?method=x&arg=1&confirm=YES",
                "/api/debug/car-property/set?key=0x1&value=1&confirm=YES",
                "/api/debug/light/fire?confirm=YES", "/api/debug/light/fire-all?confirm=YES",
                "/api/debug/seat/write?confirm=YES"};
        for (String p : pfade) assertTrue(p, DebugSchreibsperre.gesperrt(p));
        // lesende bleiben
        assertFalse(DebugSchreibsperre.gesperrt("/api/debug/autoservice/get-int?area=1&cmd=2"));
        assertFalse(DebugSchreibsperre.gesperrt("/api/debug/seat/read"));
    }

    @Test
    public void sperreGreiftVorJedemDebugHandler() throws Exception {
        String http = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/HttpServer.java"));
        int sperre = http.indexOf("DebugSchreibsperre.gesperrt(path)");
        assertTrue(sperre > 0);
        for (String h : new String[] {"AutoServiceDebugApiHandler.handle(", "CarPropertyDebugApiHandler.handle(",
                "LightDebugApiHandler.handle(", "SeatDebugApiHandler.handle("}) {
            assertTrue(h, http.indexOf(h) > sperre);
        }
    }

    /** Kein Fernsteuer- oder CAN-Schreibweg mehr im Code, den irgendetwas aufrufen könnte. */
    @Test
    public void keinSchreibOpcodeWegImCode() throws Exception {
        String bridge = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/byd/AutoServiceBridge.kt"));
        assertFalse(bridge.contains("fun remoteControl("));
        assertFalse(bridge.contains("fun sendCanViaService("));
        assertFalse(bridge.contains("fun powerOff("));
        List<String> treffer = new ArrayList<>();
        try (Stream<Path> s = Files.walk(Paths.get("src/main/java"))) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String n = p.toString();
                if (!(n.endsWith(".java") || n.endsWith(".kt"))) continue;
                String q = Quelltext.lies(p);
                if (q.contains(".remoteControl(") || q.contains("sendCanViaService(")
                        || q.contains("handleSdkRemoteControl") || q.contains("handleRemoteControl(")) {
                    treffer.add(n);
                }
            }
        }
        assertTrue("Schreibweg noch da: " + treffer, treffer.isEmpty());
    }

    /** Regel 2: kein Log-Upload zu einem fremden Server (Cloudflare Worker) mehr. */
    @Test
    public void logUploadEntfernt() throws Exception {
        assertFalse(Files.exists(Paths.get("src/main/java/at/greenchili/surfmate/server/LogsApiHandler.java")));
        String lu = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/logging/LogUploader.java"));
        assertFalse(lu.contains("okhttp3"));
        assertFalse(lu.contains("LOG_UPLOAD_URL"));
        String ipc = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/SurveillanceIpcServer.java"));
        assertFalse(ipc.contains("LogUploader.upload("));
        String gradle = Quelltext.lies(Paths.get("build.gradle.kts"));
        assertFalse(gradle.contains("buildConfigField(\"String\", \"LOG_UPLOAD_URL\""));
        String frag = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/ui/fragment/DaemonsFragment.kt"));
        assertFalse(frag.contains("uploadDaemonLog"));
    }
}
