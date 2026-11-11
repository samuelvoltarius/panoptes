package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * 0.1.41 (LC): „Navi aufs Kombi“ und „Seitenblick beim Blinken“ im Launcher. Beide nur von der Launcher-APK,
 * beide nur über vorhandene Kern-Wege; der Blinker wird nur gelesen, nie bedient (REGEL A+).
 */
public class LauncherKombiSeitenblickTest {

    private static String anfrage(String method, String path, String body) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(LauncherApiHandler.handle(method, path, body, out));
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    public void beideWegeNurVomLauncher() throws Exception {
        for (String[] a : new String[][] {
                {"GET", LauncherKombi.PFAD + "?paket=com.google.android.apps.maps", ""},
                {"POST", LauncherKombi.PFAD, "{\"paket\":\"com.google.android.apps.maps\"}"},
                {"GET", LauncherSeitenblick.PFAD, ""},
                {"POST", LauncherSeitenblick.PFAD, "{\"an\":true}"}}) {
            String r = anfrage(a[0], a[1], a[2]);
            assertTrue(a[0] + " " + a[1] + " → " + r, r.startsWith("HTTP/1.1 403"));
            assertFalse(r.contains("\"ok\":true"));
        }
        String src = lies("app/src/main/java/at/greenchili/surfmate/server/LauncherApiHandler.java");
        int k = src.indexOf("cleanPath.equals(LauncherKombi.PFAD)");
        assertTrue(k > 0 && src.indexOf("launcherDarf(out)", k) < src.indexOf("LauncherKombi.lesen(", k)
                && src.indexOf("launcherDarf(out)", k) < src.indexOf("LauncherKombi.starten(", k));
        int s = src.indexOf("cleanPath.equals(LauncherSeitenblick.PFAD)");
        assertTrue(s > 0 && src.indexOf("launcherDarf(out)", s) < src.indexOf("LauncherSeitenblick.lesen(", s)
                && src.indexOf("launcherDarf(out)", s) < src.indexOf("LauncherSeitenblick.schalten(", s));
    }

    @Test
    public void vonAussenNieErreichbarUndImLogUndDemoGesperrt() throws Exception {
        for (String m : new String[] {"GET", "POST", "PUT"}) {
            assertFalse(ExternGuard.erlaubt(m, LauncherKombi.PFAD));
            assertFalse(ExternGuard.erlaubt(m, LauncherSeitenblick.PFAD));
        }
        String log = lies("app/src/main/java/at/greenchili/surfmate/server/BefehlsLog.java");
        assertTrue("Kombi über den Präfix tacho/", log.contains("\"/api/launcher/v1/tacho/\""));
        assertTrue(log.contains("\"/api/launcher/v1/seitenblick\""));
        String demo = lies("app/src/main/java/at/greenchili/surfmate/server/DemoProfil.java");
        assertTrue(demo.contains("\"/api/launcher/v1/tacho/\"") && demo.contains("\"/api/launcher/v1/seitenblick\""));
    }

    /** Kombi: nur Navi-Apps, und nur über TachoNaviApp.setze (Hinweis, Freigabe, Stand, Plattformurteil). */
    @Test
    public void kombiNurNaviUndNurUeberDenKernWeg() throws Exception {
        assertEquals("ungueltiges_paket", LauncherKombi.paketSperre("", true));
        assertEquals("ungueltiges_paket", LauncherKombi.paketSperre(null, true));
        assertEquals("keine_navi", LauncherKombi.paketSperre("com.netflix.mediaclient", false));
        assertNull(LauncherKombi.paketSperre("com.google.android.apps.maps", true));
        String src = lies("app/src/main/java/at/greenchili/surfmate/server/LauncherKombi.java");
        assertTrue(src.contains("TachoNaviApp.setze(true, p)"));
        for (String verboten : new String[] {"ClusterCast.start", "ClusterMapProjector.start", "setzeFahrt",
                "fahrtHinweisBestaetigen", "hinweisBestaetigen", "bestaetige(", "probe(", "am start"}) {
            assertFalse(verboten, src.contains(verboten));
        }
        JSONObject nein = LauncherKombi.status(false, "single_os", false, true, null);
        assertFalse(nein.getBoolean("angeboten"));
        assertEquals("single_os", nein.getString("grund"));
        assertTrue(nein.isNull("aktiv"));
        assertEquals("faehigkeit_unbekannt", LauncherKombi.status(false, "", false, false, null).getString("grund"));
        assertEquals("", LauncherKombi.status(true, "x", true, true, "com.google.android.apps.maps").getString("grund"));
    }

    /** Seitenblick: beim Einschalten hat die Rückfahrkamera Vorrang; nie ein Schreibweg auf Licht/Blinker. */
    @Test
    public void seitenblickRegelnUndNurLesen() throws Exception {
        Map<String, Object> an = LauncherSeitenblick.regelnBeimEinschalten(true);
        assertEquals(Boolean.TRUE, an.get("suppressInReverse"));
        assertEquals(1, an.size());
        assertTrue(LauncherSeitenblick.regelnBeimEinschalten(false).isEmpty());
        assertEquals("unbekannt", LauncherSeitenblick.blinkerZustand(false, 1));
        assertEquals("nicht_lesbar", LauncherSeitenblick.blinkerZustand(true, -1));
        assertEquals("lesbar", LauncherSeitenblick.blinkerZustand(true, 0));
        assertTrue(LauncherSeitenblick.tweakOk("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"success\":true}"));
        assertFalse(LauncherSeitenblick.tweakOk("HTTP/1.1 200 OK\r\n\r\n{\"success\":false,\"persisted\":true}"));
        assertFalse(LauncherSeitenblick.tweakOk("HTTP/1.1 500 Error\r\n\r\n{\"success\":true}"));
        assertFalse(LauncherSeitenblick.tweakOk(null));
        String src = lies("app/src/main/java/at/greenchili/surfmate/server/LauncherSeitenblick.java");
        assertTrue(src.contains("\"/api/bs/tweak?enabled=\" + an"));
        assertTrue(src.contains("readTurnNow()"));
        for (String verboten : new String[] {"setTurnLight", "setHazard", "HazardCommand", "LIGHT_CMD", "FahrzeugAktionen",
                "VehicleCommandRouter", "debugPreview", "minSpeedKmh", "maxSpeedKmh", "\"target\""}) {
            assertFalse(verboten, src.contains(verboten));
        }
    }

    private static String lies(String rel) throws Exception {
        Path cur = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (cur != null) {
            for (Path p : new Path[] {cur.resolve(rel), cur.resolve(rel.replaceFirst("^app/", ""))}) {
                if (Files.isRegularFile(p)) return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            }
            cur = at.greenchili.surfmate.testutil.RepoWurzel.hoch(cur);
        }
        throw new AssertionError("Nicht gefunden: " + rel);
    }
}
