package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * 0.1.37 Launcher: Fahrzeugbefehle sind über /api/launcher/v1/vehicle/* gesperrt (REGEL A+),
 * neue Kacheln (Navi, Wächter), Startansicht und Hintergrund sind rein lesend.
 */
public class LauncherApiSperreTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static String anfrage(String method, String path) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(LauncherApiHandler.handle(method, path, "{}", out));
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    public void fahrzeugBefehleLiefern404() throws Exception {
        for (String cmd : new String[] {"flash", "find", "lock", "unlock", "trunk", "window", "climate", ""}) {
            for (String m : new String[] {"POST", "GET"}) {
                String r = anfrage(m, "/api/launcher/v1/vehicle/" + cmd);
                assertTrue(m + " vehicle/" + cmd + " → " + r, r.startsWith("HTTP/1.1 404"));
                assertFalse(r.contains("\"ok\":true"));
            }
        }
        assertTrue(anfrage("POST", "/api/launcher/v1/vehicle").startsWith("HTTP/1.1 404"));
    }

    /**
     * 0.1.39 L1: apps/launch mode=split_ende verschiebt nur den eigenen Launcher-Task (am stack move-task, nichts
     * gestoppt oder entfernt) und nur auf Aufruf der Launcher-APK; von außen (Kopplung/Brücke/Extern) nie erreichbar.
     */
    @Test
    public void splitEndeNurVomLauncherUndNieVonAussen() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(LauncherApiHandler.handle("POST", "/api/launcher/v1/apps/launch", "{\"mode\":\"split_ende\"}", out));
        String r = new String(out.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(r, r.startsWith("HTTP/1.1 403"));
        assertFalse(r.contains("\"success\":true"));
        assertFalse(ExternGuard.erlaubt("POST", "/api/launcher/v1/apps/launch"));
        assertFalse(ExternGuard.erlaubt("PUT", "/api/launcher/v1/apps/launch"));
        String src = lies("app/src/main/java/at/greenchili/surfmate/server/LauncherApiHandler.java");
        int weg = src.indexOf("\"split_ende\".equals(mode)");
        assertTrue(weg > 0 && src.indexOf("launcherDarf(out)", weg) < src.indexOf("AppLauncher.splitBeenden(", weg));
        assertTrue("nur das feste Launcher-Paket", src.contains("AppLauncher.splitBeenden(LAUNCHER_PAKET)"));
        String al = lies("app/src/main/java/at/greenchili/surfmate/launcher/AppLauncher.java");
        int a = al.indexOf("public static boolean splitBeenden(");
        String rumpf = al.substring(a, al.indexOf("static int primaryTaskAusListe(", a));
        assertTrue(rumpf.contains("am stack move-task "));
        for (String verboten : new String[] {"force-stop", "remove-task", "am kill", "stack remove", "pm "}) {
            assertFalse(verboten, rumpf.contains(verboten));
        }
    }

    /** 0.1.37: „Tacho wieder normal“ nur von der Launcher-APK; sonst 403, nichts gestoppt. Pflicht im Befehls-Log. */
    @Test
    public void tachoNormalNurVomLauncher() throws Exception {
        String r = anfrage("POST", "/api/launcher/v1/tacho/normal");
        assertTrue(r, r.startsWith("HTTP/1.1 403"));
        assertFalse(r.contains("\"ok\":true"));
        String src = lies("app/src/main/java/at/greenchili/surfmate/server/LauncherApiHandler.java");
        int weg = src.indexOf("cleanPath.equals(TACHO_NORMAL_PFAD)");
        assertTrue(weg > 0 && src.indexOf("launcherDarf(out)", weg) < src.indexOf("TachoNaviApp.tachoNormal()", weg));
        assertTrue(lies("app/src/main/java/at/greenchili/surfmate/server/BefehlsLog.java").contains("\"/api/launcher/v1/tacho/\""));
        assertTrue(LauncherApiHandler.buildTacho().has("aktiv"));
    }

    @Test
    public void keineDurchreicheZurFahrzeugsteuerungImQuelltext() throws Exception {
        String src = lies("app/src/main/java/at/greenchili/surfmate/server/LauncherApiHandler.java");
        assertFalse(src.contains("VehicleControlApiHandler.handle("));
        assertFalse(src.contains("/api/vehicle/flash"));
        assertFalse(src.contains("/api/vehicle/find-car"));
        assertFalse(src.contains("sendVehicleCommand"));
    }

    @Test
    public void startAnsichtNurBekannteWerte() {
        for (String s : new String[] {"panoptes", "fahren", "fahrzeug", "kompakt", "cockpit"}) {
            assertEquals(s, LauncherApiHandler.startAnsichtPruefen(s));
        }
        assertEquals("panoptes", LauncherApiHandler.startAnsichtPruefen(null));
        assertEquals("panoptes", LauncherApiHandler.startAnsichtPruefen("../etc"));
        assertEquals("panoptes", LauncherApiHandler.startAnsichtPruefen("Cockpit"));
    }

    @Test
    public void modusWiePanoptesWeb() {
        assertEquals("light", LauncherApiHandler.modusPruefen("light"));
        assertEquals("auto", LauncherApiHandler.modusPruefen("auto"));
        assertEquals("dark", LauncherApiHandler.modusPruefen("dark"));
        assertEquals("dark", LauncherApiHandler.modusPruefen("polar"));
        assertEquals("dark", LauncherApiHandler.modusPruefen(null));
    }

    @Test
    public void tempoNurEchterMesswert() {
        assertEquals(JSONObject.NULL, LauncherApiHandler.speedOrNull(Double.NaN));
        assertEquals(JSONObject.NULL, LauncherApiHandler.speedOrNull(-1));
        assertEquals(JSONObject.NULL, LauncherApiHandler.speedOrNull(999));
        assertEquals(0L, LauncherApiHandler.speedOrNull(0.2));
        assertEquals(87L, LauncherApiHandler.speedOrNull(86.6));
    }

    @Test
    public void klimaImLauncherNurLesend() throws Exception {
        String src = lies("app/src/main/java/at/greenchili/surfmate/server/LauncherApiHandler.java");
        int a = src.indexOf("static Object buildKlima()");
        int b = src.indexOf("static Object buildWaechter()");
        String klima = src.substring(a, b);
        assertFalse(klima.contains("VehicleControlApiHandler.handle"));
        assertFalse(klima.contains("execute("));
        assertFalse(klima.contains("Command"));
    }

    @Test
    public void naviNurGekuerzt() throws Exception {
        JSONObject aus = (JSONObject) LauncherApiHandler.naviAus(new JSONObject().put("aktiv", false).put("grund", "steht"));
        assertFalse(aus.getBoolean("aktiv"));
        assertEquals("steht", aus.getString("grund"));
        assertTrue(aus.isNull("naechsterStopp"));

        JSONObject roh = new JSONObject().put("aktiv", true).put("prognoseProzent", 23.4).put("delta", 1.0)
                .put("verlauf", new org.json.JSONArray().put(1))
                .put("naechsterStopp", new JSONObject().put("name", "Ladepark Linz").put("km", 41.2).put("istZiel", false));
        JSONObject an = (JSONObject) LauncherApiHandler.naviAus(roh);
        assertEquals("Ladepark Linz", an.getString("naechsterStopp"));
        assertEquals(41.2, an.getDouble("naechsterKm"), 1e-9);
        assertEquals(23.4, an.getDouble("prognoseProzent"), 1e-9);
        assertFalse("kein Verlauf an den Launcher", an.has("verlauf"));
        assertEquals(JSONObject.NULL, LauncherApiHandler.naviAus(null));
    }

    @Test
    public void waechterNurStatus() throws Exception {
        Map<String, Object> m = new HashMap<>();
        m.put("enabled", true);
        m.put("active", false);
        m.put("recording", false);
        m.put("zustand", "bereit");
        m.put("frameCount", 99);
        JSONObject w = (JSONObject) LauncherApiHandler.waechterAus(m);
        assertTrue(w.getBoolean("an"));
        assertFalse(w.getBoolean("scharf"));
        assertEquals("bereit", w.getString("zustand"));
        assertEquals(4, w.length());
        JSONObject leer = (JSONObject) LauncherApiHandler.waechterAus(new HashMap<>());
        assertTrue(leer.isNull("an"));
    }

    /** 0.1.43 (S2b): bei Zündung an ist enabled immer false – eingeschaltet heißt die Einstellung (gewollt). */
    @Test
    public void waechterAnAusDerEinstellungWieChip() throws Exception {
        Map<String, Object> m = new HashMap<>();
        m.put("enabled", false);
        m.put("gewollt", true);
        assertTrue("Zündung an, Wächter eingeschaltet: nicht „aus“", ((JSONObject) LauncherApiHandler.waechterAus(m)).getBoolean("an"));
        m.put("gewollt", false);
        assertFalse("in den Einstellungen aus = aus", ((JSONObject) LauncherApiHandler.waechterAus(m)).getBoolean("an"));
        Map<String, Object> alt = new HashMap<>();
        alt.put("gewollt", true);
        assertTrue("nur gewollt (Pipeline fehlt)", ((JSONObject) LauncherApiHandler.waechterAus(alt)).getBoolean("an"));
    }

    @Test
    public void hintergrundNurFesteRegulaereDateien() throws Exception {
        File ordner = tmp.newFolder("dateien");
        assertNull(LauncherApiHandler.hintergrundBild(ordner));
        File leer = new File(ordner, "launcher-hintergrund.jpg");
        assertTrue(leer.createNewFile());
        assertNull("leere Datei zählt nicht", LauncherApiHandler.hintergrundBild(ordner));
        Files.write(new File(ordner, "launcher-hintergrund.png").toPath(), new byte[] {1, 2, 3});
        assertEquals("launcher-hintergrund.png", LauncherApiHandler.hintergrundBild(ordner).getName());
        assertTrue(new File(ordner, "unterordner.mp4").mkdir());
        assertNull("Ordner ist keine Datei", LauncherApiHandler.ablageDatei(ordner, "unterordner.mp4"));
        assertNull(LauncherApiHandler.ablageDatei(null, "launcher-hintergrund.mp4"));
        assertNotNull(LauncherApiHandler.ablageDatei(ordner, "launcher-hintergrund.png"));
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
