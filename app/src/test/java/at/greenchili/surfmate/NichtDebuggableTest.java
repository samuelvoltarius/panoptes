package at.greenchili.surfmate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 0.1.15 (Kern 8, Koordination 23.10.: Panoptes wird öffentlich): Die Auto-/Release-APK (debug-Build) ist NICHT
 * debuggable – sonst liest jeder mit adb per run-as die Tokens der App. Was bisher an BuildConfig.DEBUG hing
 * (Od-Freigabe, kein Auto-Neustart nach Update, Logs), hängt an BuildConfig.ENTWICKLUNG, damit sich das Verhalten
 * nicht ändert (u. a. A+: kein sichtbarer App-Start nach jedem Update).
 */
public class NichtDebuggableTest {

    private static File datei(String rel) {
        File f = new File(rel);
        return f.exists() ? f : new File("app/" + rel);
    }

    @Test
    public void debugBuildNichtDebuggableUndEntwicklungsSchalter() throws Exception {
        String g = new String(Files.readAllBytes(datei("build.gradle.kts").toPath()), StandardCharsets.UTF_8);
        int debug = g.indexOf("        debug {");
        assertTrue(debug > 0);
        String block = g.substring(debug, g.indexOf("\n        }", debug));
        assertTrue("debug-Build nicht debuggable", block.contains("isDebuggable = false"));
        assertTrue(block.contains("buildConfigField(\"boolean\", \"ENTWICKLUNG\", \"true\")"));
        assertTrue("Standard false", g.contains("buildConfigField(\"boolean\", \"ENTWICKLUNG\", \"false\")"));
        assertFalse(g.contains("isDebuggable = true"));
        String m = new String(Files.readAllBytes(datei("src/main/AndroidManifest.xml").toPath()), StandardCharsets.UTF_8);
        assertFalse("Manifest setzt debuggable nicht selbst", m.contains("android:debuggable"));
    }

    @Test
    public void keinBuildConfigDebugImAppCode() throws IOException {
        List<String> treffer = new ArrayList<>();
        Path wurzel = datei("src/main/java").toPath();
        try (Stream<Path> s = Files.walk(wurzel)) {
            s.filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".kt")).forEach(p -> {
                try {
                    if (new String(Files.readAllBytes(p), StandardCharsets.UTF_8).contains("BuildConfig.DEBUG")) {
                        treffer.add(wurzel.relativize(p).toString());
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        assertEquals("BuildConfig.DEBUG ist ab 0.1.15 im Auto-Build false – ENTWICKLUNG nutzen", "[]", treffer.toString());
    }

    @Test
    public void entwicklungsSchalterAnDenFruehrerenStellen() throws IOException {
        String[][] stellen = {
                {"src/main/java/at/greenchili/surfmate/od/Od.kt", "if (at.greenchili.surfmate.BuildConfig.ENTWICKLUNG) { ready = true; return true }"},
                // 0.1.15.1: BootReceiver hängt am eigenen Schalter IDE_START (StartNachUpdateTest)
                {"src/main/java/at/greenchili/surfmate/receiver/BootReceiver.kt", "if (at.greenchili.surfmate.BuildConfig.IDE_START) {"},
                {"src/main/java/at/greenchili/surfmate/logging/DaemonLogConfig.java", "SOH_ESTIMATOR = BuildConfig.ENTWICKLUNG;"},
        };
        for (String[] st : stellen) {
            String s = new String(Files.readAllBytes(datei(st[0]).toPath()), StandardCharsets.UTF_8);
            assertTrue(st[0], s.contains(st[1]));
        }
    }
}
