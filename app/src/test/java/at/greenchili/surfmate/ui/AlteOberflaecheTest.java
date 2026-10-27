package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/**
 * 0.1.21 (Koordination): Deep-Links (navigate_to) landen in der Web-Oberfläche, nicht in der alten nativen; Modellnamen
 * nativ nur aus dem Manifest (alte Oberfläche zeigte „BYD Seagull“ statt „BYD Dolphin Surf“).
 */
public class AlteOberflaecheTest {

    @Test
    public void jedesZielHatEineWebSeiteDieDerDaemonKennt() throws Exception {
        String http = lies("app/src/main/java/at/greenchili/surfmate/server/HttpServer.java");
        for (String z : NavigateZiel.ZIELE) {
            String p = NavigateZiel.pfad(z);
            assertNotNull(z, p);
            if (p.equals("/")) continue;
            assertTrue(z + " → " + p + " ohne Route", http.contains("path.equals(\"" + p + "\")"));
        }
        assertEquals("/events", NavigateZiel.pfad("recordings"));
        assertEquals("/", NavigateZiel.pfad("dashboard"));
        assertNull(NavigateZiel.pfad("unbekannt"));
        assertNull(NavigateZiel.pfad(null));
    }

    @Test
    public void deepLinkFuehrtInsVollbildWebNichtInDieAlteOberflaeche() throws Exception {
        String a = lies("app/src/main/java/at/greenchili/surfmate/ui/MainActivity.kt");
        int von = a.indexOf("private fun handleNavigateExtra(");
        int bis = a.indexOf("private fun navigateToWebSeite(", von);
        assertTrue(von > 0 && bis > von);
        String h = a.substring(von, bis);
        assertTrue(h.contains("NavigateZiel.pfad(target)"));
        assertFalse("keine nativen Ziele mehr", h.contains("R.id."));
        int w = a.indexOf("private fun navigateToWebSeite(");
        String n = a.substring(w, a.indexOf("\n    }", w));
        assertTrue(n.contains("navController.navigate(R.id.panoptesStartFragment, args, options)"));
        assertTrue(n.contains("ARG_VOLLBILD, true"));
        assertTrue("aufgeschoben statt verloren", a.contains("pendingWebSeite?.let"));
    }

    @Test
    public void modellnamenAusDemManifest() throws Exception {
        JSONObject m = new JSONObject(lies("app/src/main/assets/web/shared/models/manifest.json"));
        assertEquals("BYD Dolphin Surf", ModellNamen.anzeige(m, "seagull"));
        assertEquals("BYD Dolphin Surf", ModellNamen.anzeige(m, "SEAGULL"));
        assertEquals("BYD Seal U", ModellNamen.anzeige(m, "sealu"));
        assertEquals("BYD Seal U", ModellNamen.anzeige(m, "seal-u"));
        assertEquals("BYD Atto 3", ModellNamen.anzeige(m, "atto-3"));
        assertEquals("BYD Atto 3 Evo", ModellNamen.anzeige(m, "atto3evo"));
        assertEquals("BYD Qin", ModellNamen.anzeige(m, "qin"));
        assertEquals("—", ModellNamen.anzeige(m, (String) null));
        assertEquals("BYD Seal", ModellNamen.anzeige(new JSONObject(), "seal"));
    }

    @Test
    public void keineEigeneNamenslisteMehr() throws Exception {
        for (String p : new String[] {
                "app/src/main/java/at/greenchili/surfmate/ui/MainActivity.kt",
                "app/src/main/java/at/greenchili/surfmate/ui/fragment/DashboardFragment.kt"}) {
            String s = lies(p);
            assertFalse(p, s.contains("R.string.vehicle_model_"));
            assertFalse(p, s.contains("\"seagull\" ->"));
        }
        Path res = wurzel().resolve("app/src/main/res");
        try (Stream<Path> st = Files.walk(res)) {
            st.filter(f -> f.getFileName().toString().equals("strings.xml")).forEach(f -> {
                try {
                    assertFalse(f.toString(), new String(Files.readAllBytes(f), "UTF-8").contains("\"vehicle_model_seagull\""));
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @Test
    public void ohneWahlKeinGeratenesModell() throws Exception {
        assertNull(at.greenchili.surfmate.config.VehicleModelSelection.gewaehlt(
                new JSONObject().put("modelId", "seal").put("modelSource", "unset")));
        assertNull(at.greenchili.surfmate.config.VehicleModelSelection.gewaehlt(new JSONObject()));
        assertNull(at.greenchili.surfmate.config.VehicleModelSelection.gewaehlt(null));
        assertEquals("seagull", at.greenchili.surfmate.config.VehicleModelSelection.gewaehlt(
                new JSONObject().put("modelId", "seagull").put("modelSource", "user")));
        assertEquals("ältere Konfiguration ohne Herkunft gilt als gewählt (wie bisher)", "dolphin",
                at.greenchili.surfmate.config.VehicleModelSelection.gewaehlt(new JSONObject().put("modelId", "dolphin")));
        String sys = lies("app/src/main/java/at/greenchili/surfmate/server/SystemApiHandler.java");
        assertTrue(sys.contains("VehicleModelSelection.gewaehlt(v)") && sys.contains("res.put(\"modellGewaehlt\", gewaehlt != null);"));
        assertTrue(lies("app/src/main/java/at/greenchili/surfmate/server/ModelsApiHandler.java")
                .contains("response.put(\"modellGewaehlt\", selectedModelId != null);"));
        String la = lies("app/src/main/java/at/greenchili/surfmate/server/LauncherApiHandler.java");
        assertTrue(la.contains("o.put(\"modellGewaehlt\", gewaehlt != null);"));
        assertFalse("kein fester Standard „seal“ mehr", la.contains("optString(\"modelId\", \"seal\")"));
    }

    @Test
    public void webKopfzeileNamenNurAusDemManifest() throws Exception {
        String s = lies("app/src/main/assets/web/shared/app-shell.js");
        assertTrue(s.contains("fetch('../shared/models/manifest.json')"));
        assertTrue(s.contains("arr[i].nameEu || arr[i].name"));
        assertFalse("keine eigene Namensliste", s.contains("'BYD Seal'") || s.contains("'BYD Dolphin Surf'"));
        assertTrue(s.contains("tr('shell.no_model'"));
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject i = new JSONObject(lies("app/src/main/assets/web/i18n/" + l + ".json"));
            assertFalse(l, i.getJSONObject("shell").optString("no_model").isEmpty());
        }
    }

    private static Path wurzel() {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (p != null) {
            if (Files.isDirectory(p.resolve("app/src/main"))) return p;
            if (Files.isDirectory(p.resolve("src/main")) && p.getFileName().toString().equals("app")) return p.getParent();
            p = p.getParent();
        }
        throw new IllegalStateException("Projektwurzel nicht gefunden");
    }

    private static String lies(String rel) throws Exception {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (p != null) {
            for (Path c : new Path[] {p.resolve(rel), p.resolve(rel.replaceFirst("^app/", ""))}) {
                if (Files.isRegularFile(c)) return new String(Files.readAllBytes(c), "UTF-8");
            }
            p = p.getParent();
        }
        throw new java.io.FileNotFoundException(rel);
    }
}
