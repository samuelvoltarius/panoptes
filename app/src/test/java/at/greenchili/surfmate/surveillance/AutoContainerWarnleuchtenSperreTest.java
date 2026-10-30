package at.greenchili.surfmate.surveillance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Kern Tacho 0.1.30: AutoContainer-Kommandos 2 und 3 schalten Warnleuchten am Tacho. Sie dürfen nie
 * gesendet werden – weder über Binder noch über {@code service call}, auch nicht über einen Konfig-Wert.
 */
public class AutoContainerWarnleuchtenSperreTest {

    @Test
    public void warnleuchtenKommandosSindGesperrt() {
        assertFalse(ClusterProjectionController.isPermittedLegacyContainerOpcode(2));
        assertFalse(ClusterProjectionController.isPermittedLegacyContainerOpcode(3));
        assertTrue(ClusterProjectionController.isForbiddenWarningLampOpcode(2));
        assertTrue(ClusterProjectionController.isForbiddenWarningLampOpcode(3));
        assertFalse(ClusterProjectionController.isAllowedDiLink5ContainerOpcode(2));
        assertFalse(ClusterProjectionController.isAllowedDiLink5ContainerOpcode(3));
    }

    @Test
    public void nurDieBekanntenProjektionsKommandosSindErlaubt() {
        for (int op : new int[] {0, 16, 18, 29, 30, 31, 35}) {
            assertTrue("op " + op, ClusterProjectionController.isPermittedLegacyContainerOpcode(op));
        }
        for (int op = -5; op <= 100; op++) {
            boolean bekannt = op == 0 || op == 16 || op == 18 || op == 29 || op == 30 || op == 31 || op == 35;
            if (!bekannt) {
                assertFalse("op " + op, ClusterProjectionController.isPermittedLegacyContainerOpcode(op));
            }
        }
    }

    @Test
    public void jederSendewegPrueftVorDemAbschicken() throws IOException {
        String src = lies("app/src/main/java/at/greenchili/surfmate/surveillance/ClusterProjectionController.java");
        assertGuardBefore(src, "private static LegacyCommandResult sendInfoMarkedResult(",
                "isPermittedLegacyContainerOpcode(op)", "markLegacyCommandInFlight()");
        assertGuardBefore(src, "sendInfoBinderTransportResult(String service, int op) {",
                "isPermittedLegacyContainerOpcode(op)", "send.invoke(");
        assertGuardBefore(src, "private static LegacyCommandResult sendInfoShellMarkedResult(",
                "isPermittedLegacyContainerOpcode(op)", "new ProcessBuilder(");
        assertGuardBefore(src, "private static DiLink5EndpointResult sendDiLink5InfoShell(",
                "isAllowedDiLink5ContainerOpcode(op)", "new ProcessBuilder(");
    }

    /** Kein fest verdrahtetes „AutoContainer 2 i32 1000 i32 2|3“ irgendwo im App-Code. */
    @Test
    public void keinFestesWarnleuchtenKommandoImCode() throws IOException {
        Path wurzel = finde("app/src/main/java");
        Pattern muster = Pattern.compile("i32\\s+1000\\s+i32\\s+[23]\\b");
        try (Stream<Path> dateien = Files.walk(wurzel)) {
            dateien.filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".kt")).forEach(p -> {
                try {
                    String s = at.greenchili.surfmate.testutil.Quelltext.lies(p);
                    assertFalse(p.toString(), muster.matcher(s).find());
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            });
        }
    }

    private static void assertGuardBefore(String src, String kopf, String guard, String dispatch) {
        int k = src.indexOf(kopf);
        assertTrue("fehlt: " + kopf, k >= 0);
        int g = src.indexOf(guard, k);
        int d = src.indexOf(dispatch, k);
        assertTrue(kopf + ": Sperre vor " + dispatch, g > k && d > g);
    }

    private static String lies(String relativ) throws IOException {
        return at.greenchili.surfmate.testutil.Quelltext.lies(finde(relativ));
    }

    private static Path finde(String relativ) {
        Path cur = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (cur != null) {
            Path c = cur.resolve(relativ);
            if (Files.exists(c)) return c;
            Path m = cur.resolve(relativ.replaceFirst("^app/", ""));
            if (Files.exists(m)) return m;
            cur = cur.getParent();
        }
        throw new AssertionError("nicht gefunden: " + relativ);
    }
}
