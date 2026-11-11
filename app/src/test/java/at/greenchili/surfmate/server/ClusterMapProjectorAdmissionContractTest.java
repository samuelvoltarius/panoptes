package at.greenchili.surfmate.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import at.greenchili.surfmate.tacho.TachoFaehigkeit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Central single-OS admission contract shared by API, ACC and map restore callers. */
public class ClusterMapProjectorAdmissionContractTest {
    private static final String ONLY_DISPLAY_ZERO =
            "  mDisplayId=0\n  mName=Built-in Screen\n";
    private static final String AUTOCONTAINER =
            "0\tAutoContainer: [android.os.IAutoContainer]\n";

    @Test
    public void dolphinSurfSingleOsIsRejectedEvenWhenProjectionServiceIsListed() {
        TachoFaehigkeit.Eingaben surf = legacyPlatform("1");
        assertFalse(TachoFaehigkeit.karteVerfuegbar(surf));
    }

    @Test
    public void supportedDualOsIsAdmittedBeforeVirtualDisplayExists() {
        TachoFaehigkeit.Eingaben dualOs = legacyPlatform("0");
        assertTrue(TachoFaehigkeit.karteVerfuegbar(dualOs));
    }

    @Test
    public void missingServiceOrShellRightsFailClosed() {
        TachoFaehigkeit.Eingaben noService = legacyPlatform("0");
        noService.dienste = "";
        assertFalse(TachoFaehigkeit.karteVerfuegbar(noService));

        TachoFaehigkeit.Eingaben noShell = legacyPlatform("0");
        noShell.uid = "10123";
        assertFalse(TachoFaehigkeit.karteVerfuegbar(noShell));
    }

    @Test
    public void projectorGatesBeforeAnyProjectionSideEffect() throws Exception {
        String source = read("app/src/main/java/at/greenchili/surfmate/navmap/ClusterMapProjector.java");
        int start = source.indexOf("public static synchronized boolean start()");
        int end = source.indexOf("public static synchronized boolean stop()", start);
        String method = source.substring(start, end);

        int capability = method.indexOf("TachoFaehigkeit.karteVerfuegbar()");
        int activeCheck = method.indexOf("if (active)");
        int publish = method.indexOf("publishActiveFlag(true)");
        int acquire = method.indexOf("acquireSustained()");
        int di5Start = method.indexOf("DiLink5ClusterCast.startMap()");
        assertTrue(capability >= 0);
        assertTrue(capability < activeCheck);
        assertTrue(capability < publish);
        assertTrue(capability < acquire);
        assertTrue(capability < di5Start);
        assertTrue(method.substring(capability, activeCheck).contains("return false;"));
    }

    @Test
    public void apiAndAccBothUseCentralProjectorAdmission() throws Exception {
        String api = read("app/src/main/java/at/greenchili/surfmate/server/NavMapApiHandler.java");
        String acc = read("app/src/main/java/at/greenchili/surfmate/monitor/AccMonitor.java");
        String cast = read("app/src/main/java/at/greenchili/surfmate/launcher/ClusterCast.java");

        assertTrue(api.contains("boolean accepted = at.greenchili.surfmate.navmap.ClusterMapProjector.start()"));
        assertTrue(api.contains("sendClusterStatus(out, accepted)"));
        assertTrue(api.contains("response.put(\"available\", at.greenchili.surfmate.tacho.TachoFaehigkeit.karteVerfuegbar())"));
        assertTrue(acc.contains("at.greenchili.surfmate.navmap.ClusterMapProjector.start()"));
        assertTrue(cast.contains("at.greenchili.surfmate.navmap.ClusterMapProjector.start()"));
        assertFalse(acc.contains("ClusterProjectionController.getInstance().acquireSustained"));
    }

    private static TachoFaehigkeit.Eingaben legacyPlatform(String singleOs) {
        TachoFaehigkeit.Eingaben e = new TachoFaehigkeit.Eingaben();
        e.singleOs = singleOs;
        e.sdk = 29;
        e.displays = ONLY_DISPLAY_ZERO;
        e.dienste = AUTOCONTAINER;
        e.uid = "2000";
        return e;
    }

    private static String read(String relative) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8)
                        .replace("\r\n", "\n");
            }
            current = at.greenchili.surfmate.testutil.RepoWurzel.hoch(current);
        }
        throw new IllegalStateException("missing " + relative);
    }
}
