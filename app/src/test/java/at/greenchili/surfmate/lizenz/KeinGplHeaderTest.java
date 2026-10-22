package at.greenchili.surfmate.lizenz;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/**
 * Veröffentlichung des Quellcodes (MIT): cpp/include/fd_passing.h war eine Kopie aus dem GPL-3.0-Projekt
 * FAST_CAM_CAPTURE und wurde nirgends eingebunden (fd-Passing macht camera/fast_cam_bridge.cpp selbst).
 * Entfernt in 0.1.7.2; dieser Test verhindert, dass sie oder ein Verweis darauf zurückkommt.
 */
public class KeinGplHeaderTest {

    @Test
    public void fdPassingHeaderIstWeg() throws IOException {
        assertFalse(Files.exists(Paths.get("src/main/cpp/include/fd_passing.h")));
        try (Stream<Path> s = Files.walk(Paths.get("src/main/cpp"))) {
            s.filter(Files::isRegularFile)
             .filter(p -> { String n = p.toString(); return n.endsWith(".c") || n.endsWith(".cpp") || n.endsWith(".h")
                     || n.endsWith(".hpp") || n.endsWith("CMakeLists.txt"); })
             .forEach(p -> {
                 String t;
                 try {
                     t = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                 } catch (IOException e) {
                     throw new RuntimeException(e);
                 }
                 assertFalse(p.toString(), t.contains("fd_passing"));
             });
        }
        // camera/fast_cam_bridge.cpp ist seit dem Clean-Room (0.1.9) ebenfalls entfernt, siehe PkamCleanRoomTest.
    }

    /** libfast_cam_client.so (GPL-Herkunft) wurde nie geladen und ist aus dem APK entfernt (Koordination 21.10.). */
    @Test
    public void fastCamClientNichtImApk() {
        assertFalse(Files.exists(Paths.get("src/main/jniLibs/arm64-v8a/libfast_cam_client.so")));
    }
}
