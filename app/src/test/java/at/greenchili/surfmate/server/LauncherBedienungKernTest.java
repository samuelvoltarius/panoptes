package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 0.1.44 (P1 „Bedienung 50+“): Kern-Wege für Zurück/Letzte App und „Startbildschirm an“ aus dem Launcher. */
public class LauncherBedienungKernTest {

    private static String quelle(String rel) throws Exception {
        Path cur = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (cur != null) {
            for (Path p : new Path[] {cur.resolve(rel), cur.resolve("app/" + rel)}) {
                if (Files.isRegularFile(p)) return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            }
            cur = at.greenchili.surfmate.testutil.RepoWurzel.hoch(cur);
        }
        throw new AssertionError(rel);
    }

    @Test
    public void nurZweiTastenKeinFreierCode() throws Exception {
        assertEquals(Integer.valueOf(4), LauncherBedienung.code("zurueck"));
        assertEquals(Integer.valueOf(187), LauncherBedienung.code("letzte"));
        for (String t : new String[] {"home", "3", "4", "power", "", null, "zurueck; reboot"})
            assertNull(t, LauncherBedienung.code(t));
        Object[] r = LauncherBedienung.taste("{\"taste\":\"26\"}");
        assertEquals(400, r[0]);
        assertFalse(((JSONObject) r[1]).optBoolean("ok", true));
        assertEquals(400, LauncherBedienung.taste("{\"keycode\":4}")[0]);
    }

    @Test
    public void nurVonDerLauncherApkUndNieVonAussen() throws Exception {
        String h = quelle("src/main/java/at/greenchili/surfmate/server/LauncherApiHandler.java");
        int t = h.indexOf("cleanPath.equals(LauncherBedienung.TASTE_PFAD)");
        assertTrue(t > 0 && h.indexOf("launcherDarf(out)", t) < h.indexOf("LauncherBedienung.taste(", t));
        int a = h.indexOf("cleanPath.equals(LauncherBedienung.HOME_AN_PFAD)");
        assertTrue(a > 0 && h.indexOf("launcherDarf(out)", a) < h.indexOf("LauncherBedienung.homeAn()", a));
        assertFalse(ExternGuard.erlaubt("POST", LauncherBedienung.TASTE_PFAD));
        assertFalse(ExternGuard.erlaubt("POST", LauncherBedienung.HOME_AN_PFAD));
        // Startbildschirm an heißt nur Schalter an; „Behalten“ (Totmann aus) bleibt dem eigenen Weg vorbehalten
        String b = quelle("src/main/java/at/greenchili/surfmate/server/LauncherBedienung.java");
        assertTrue(b.contains("HomeWaechter.setze(true, false)"));
        assertFalse(b.contains("setze(true, true)"));
        assertFalse(b.contains("optInt(\"keycode\""));
    }
}
