package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.BuildConfig;
import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.1.38.2 Demo-Profil: am Auto ab Werk unerreichbar, nie schreibend, nie Fahrzeugbefehle, Musterdaten nur Wien.
 */
public class DemoProfilTest {

    private static final File DEMO = new File("src/main/assets/demo");

    @Test
    public void amAutoNieErlaubt() {
        // Auto-Build: kein Screenshot-Modus, kein qemu. Schalter allein reicht nie.
        assertFalse(BuildConfig.SCREENSHOT_MODUS);
        assertFalse(DemoProfil.aktiv("", "", "byd", false, "1"));
        assertFalse(DemoProfil.aktiv("0", "0", "qcom", false, "1"));
        // qemu-Kennung ohne Emulator-Hardware reicht nicht, Emulator-Hardware ohne qemu auch nicht
        assertFalse(DemoProfil.aktiv("1", "", "qcom", false, "1"));
        assertFalse(DemoProfil.aktiv("", "", "ranchu", false, "1"));
        // Unit-Test-JVM: keine System-Eigenschaften → aus
        assertFalse(DemoProfil.aktiv());
    }

    @Test
    public void emulatorOderScreenshotBuildNurMitSchalter() {
        assertTrue(DemoProfil.erlaubt("1", "", "ranchu", false));
        assertTrue(DemoProfil.erlaubt("", "1", "goldfish", false));
        assertTrue(DemoProfil.erlaubt("", "", "qcom", true));
        assertFalse(DemoProfil.aktiv("1", "", "ranchu", false, ""));
        assertFalse(DemoProfil.aktiv("1", "", "ranchu", false, "0"));
        assertTrue(DemoProfil.aktiv("1", "", "ranchu", false, "1"));
        assertTrue(DemoProfil.aktiv("", "", "qcom", true, "1"));
    }

    @Test
    public void nieSchreibenNieFahrzeugbefehle() {
        for (String m : new String[] {"POST", "PUT", "DELETE", "PATCH"}) {
            for (String p : new String[] {"/api/vehicle/lock", "/api/vehicle/flash", "/api/einstellungen", "/api/automations",
                    "/api/charging/1/saeule", "/api/launcher/v1/vehicle/klima", "/api/tiermodus", "/api/apps/launch",
                    "/api/trips", "/api/kopplungen", "/api/quick-controls", "/api/system/ivi-reboot"}) {
                assertTrue(m + " " + p, DemoProfil.gesperrt(m, p));
            }
        }
        for (String p : new String[] {"/api/vehicle/lock", "/api/vehicle/flash", "/api/start/0", "/api/view/1", "/api/stop/0",
                "/api/stopall", "/api/tacho/normal", "/api/launcher/v1/vehicle/klima", "/api/audio/engine-sound",
                "/api/quick-controls/klima"}) {
            assertTrue("GET " + p, DemoProfil.gesperrt("GET", p));
        }
        assertTrue(DemoProfil.gesperrt("GET", null));
    }

    @Test
    public void lesenGehtMusterOderNormal() {
        assertFalse(DemoProfil.gesperrt("GET", "/api/vehicle/state"));
        assertFalse(DemoProfil.gesperrt("GET", "/api/trips"));
        assertFalse(DemoProfil.gesperrt("GET", "/local/trips.html"));
        assertFalse(DemoProfil.gesperrt("POST", "/api/navi/umkreis"));
        assertEquals("trips.json", DemoProfil.datei("GET", "/api/trips"));
        assertEquals("trips_42.json", DemoProfil.datei("GET", "/api/trips/42"));
        assertEquals("trips_42_telemetry.json", DemoProfil.datei("GET", "/api/trips/42/telemetry"));
        assertNull(DemoProfil.datei("GET", "/api/trips/42/x"));
        assertNull(DemoProfil.datei("GET", "/api/trips/7"));
        assertNull(DemoProfil.datei("POST", "/api/trips"));
        assertNull(DemoProfil.datei("GET", "/api/einstellungen"));
    }

    @Test
    public void zeitstempelRueckenAufHeute() {
        long anker = DemoProfil.ANKER_MS;
        long tag = 86_400_000L;
        long heute = anker + 14 * tag + 5_000_000L;
        assertEquals(14 * tag, DemoProfil.verschiebungMs(heute));
        assertEquals(0, DemoProfil.verschiebungMs(anker - tag));
        String vorher = "{\"startTime\":" + (anker - tag) + ",\"alt\":1600000000000,\"lat\":48.2082,\"km\":1792540800000.5}";
        String nachher = DemoProfil.verschiebe(vorher, heute);
        assertTrue(nachher, nachher.contains("\"startTime\":" + (anker + 13 * tag)));
        assertTrue("ausserhalb des Fensters bleibt", nachher.contains("1600000000000"));
        assertTrue("Kommazahl bleibt", nachher.contains("1792540800000.5"));
        assertTrue(nachher.contains("48.2082"));
    }

    @Test
    public void standOhneDaten() throws Exception {
        assertEquals("{\"aktiv\":false}", DemoProfil.standJson(false));
        assertTrue(new JSONObject(DemoProfil.standJson(true)).getBoolean("aktiv"));
    }

    @Test
    public void jedeMusterdateiDaUndGueltig() throws Exception {
        Set<String> genannt = new HashSet<>(DemoProfil.DATEIEN.values());
        genannt.addAll(DemoProfil.LESE_POSTS.values());
        for (String name : genannt) {
            File f = new File(DEMO, name);
            assertTrue("fehlt: " + f, f.isFile());
            Object j = new JSONTokener(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).nextValue();
            assertTrue(name, j instanceof JSONObject || j instanceof JSONArray);
        }
        String[] alle = DEMO.list();
        assertNotNull(alle);
        for (String name : alle) assertTrue("ungenutzt: " + name, genannt.contains(name));
    }

    /** Keine privaten Orte: jede Koordinate liegt in Wien und Umgebung (Ziele St. Pölten, Wiener Neustadt; Park-Regel des Repos). */
    @Test
    public void koordinatenNurWien() throws Exception {
        Pattern feld = Pattern.compile("\"(lat|lon|lng|latitude|longitude|startLat|startLon|endLat|endLon|start_lat|start_lon|end_lat|end_lon)\"\\s*:\\s*(-?\\d+\\.\\d+)");
        Pattern paar = Pattern.compile("\\[\\s*(-?\\d+\\.\\d+)\\s*,\\s*(-?\\d+\\.\\d+)\\s*[,\\]]");
        File[] dateien = DEMO.listFiles();
        assertNotNull(dateien);
        int geprueft = 0;
        for (File f : dateien) {
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Matcher m = feld.matcher(s);
            while (m.find()) {
                double v = Double.parseDouble(m.group(2));
                boolean breite = m.group(1).toLowerCase().contains("lat");
                assertTrue(f.getName() + " " + m.group(0), breite ? wienBreite(v) : wienLaenge(v));
                geprueft++;
            }
            Matcher p = paar.matcher(s);
            while (p.find()) {
                double a = Double.parseDouble(p.group(1));
                double b = Double.parseDouble(p.group(2));
                if ((a > 40 && a < 60) || (b > 40 && b < 60)) {
                    assertTrue(f.getName() + " " + p.group(0), (wienBreite(a) && wienLaenge(b)) || (wienLaenge(a) && wienBreite(b)));
                    geprueft++;
                }
            }
        }
        String karte = new String(Files.readAllBytes(Paths.get("src/main/assets/web/shared/demo-karte-wien.json")), StandardCharsets.UTF_8);
        Matcher k = paar.matcher(karte);
        while (k.find()) {
            assertTrue(k.group(0), wienLaenge(Double.parseDouble(k.group(1))) && wienBreite(Double.parseDouble(k.group(2))));
            geprueft++;
        }
        assertTrue(geprueft > 50);
    }

    private static boolean wienBreite(double v) { return v > 47.7 && v < 48.45; }

    private static boolean wienLaenge(double v) { return v > 15.5 && v < 16.75; }

    @Test
    public void hakenNachDenWaechternVorDenHandlern() throws Exception {
        String s = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/HttpServer.java"));
        int guard = s.indexOf("ExternGuard.blocks(method, pathOnly");
        int web = s.indexOf("WebHandyGuard.sendeGesperrt(out, pathOnly)");
        int demo = s.indexOf("DemoProfil.beantworte(method, pathOnly, out)");
        int route = s.indexOf("routeToHandlers(method, path, body");
        assertTrue(guard > 0 && web > guard && demo > web && route > demo);
    }

    @Test
    public void bandInDerOberflaeche() throws Exception {
        String shell = Quelltext.lies(Paths.get("src/main/assets/web/shared/app-shell.js"));
        assertTrue(shell.contains("fetch('/api/demo'"));
        assertTrue(shell.contains("shell.demo_band"));
        String karte = Quelltext.lies(Paths.get("src/main/assets/web/shared/panoptes-map.js"));
        assertTrue("Musterkarte ohne Netz", karte.contains("demo-karte-wien.json"));
    }
}
