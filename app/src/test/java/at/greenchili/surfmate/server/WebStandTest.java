package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.TreeMap;

/**
 * 0.1.43.7 (Paket E, E2E_01436b H1): Nach einem Update zeigte die erste Seite noch die Dateien des alten Kerns, bis zum
 * ersten Seitenwechsel. Der Kern meldet seine ?v=-Zähler; app-shell.js vergleicht und lädt einmal neu, mit Schleifenschutz,
 * ohne eigenes Abfragen im Takt.
 */
public class WebStandTest {
    private static final String WEB = "src/main/assets/web/";

    @Test
    public void zaehlerAusDenAusgeliefertenSeiten() {
        Map<String, String> z = WebStand.auslesen(new File(basis(), WEB));
        assertEquals("pn39", z.get("app-shell.js"));
        assertEquals("14", z.get("theme.js"));
        assertEquals("aus theme.js geladen", "1", z.get("stil-launcher.css"));
        assertTrue("alle geteilten Dateien: " + z.size(), z.size() > 80);
        String k = WebStand.kennung(z);
        assertTrue(k, k.matches("[0-9a-f]{12}"));
        assertEquals("gleiche Liste, gleiche Kennung", k, WebStand.kennung(new TreeMap<>(z)));
        Map<String, String> neu = new TreeMap<>(z);
        neu.put("app-shell.js", "pn40");
        assertNotEquals("neuer Zähler, neue Kennung", k, WebStand.kennung(neu));
    }

    @Test
    public void mehrdeutigeZaehlerBleibenDraussen() throws Exception {
        File d = Files.createTempDirectory("webstand").toFile();
        File local = new File(d, "local"), shared = new File(d, "shared");
        assertTrue(local.mkdirs() && shared.mkdirs());
        Files.write(new File(local, "a.html").toPath(), "<script src=\"../shared/x.js?v=3\"></script><link href=\"../shared/y.css?v=pn2\">".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(local, "b.html").toPath(), "<script src=\"../shared/x.js?v=4\"></script>".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(shared, "z.js").toPath(), "var C = 'z.css?v=7';".getBytes(StandardCharsets.UTF_8));
        Map<String, String> z = WebStand.auslesen(d);
        assertNull("x.js hat zwei Zähler", z.get("x.js"));
        assertEquals("pn2", z.get("y.css"));
        assertEquals("7", z.get("z.css"));
        assertTrue("leeres Verzeichnis: leere Liste", WebStand.auslesen(new File(d, "fehlt")).isEmpty());
    }

    @Test
    public void kernMeldetKennungUndListe() throws Exception {
        String sys = lies("src/main/java/at/greenchili/surfmate/server/SystemApiHandler.java");
        assertTrue(sys.contains("path.equals(\"/api/system/webstand\") && method.equals(\"GET\")"));
        assertTrue("Profil beim Laden jeder Seite", sys.contains("res.put(\"webStand\", WebStand.kennung());"));
        String ein = lies("src/main/java/at/greenchili/surfmate/server/EinstellungenApiHandler.java");
        assertTrue("Auto-Display: im vorhandenen 8-s-Abgleich", ein.contains("stand.put(\"webStand\", WebStand.kennung());"));
    }

    @Test
    public void seiteLaedtEinmalNeuOhneEigenesAbfragen() throws Exception {
        String s = lies(WEB + "shared/app-shell.js");
        int a = s.indexOf("// 0.1.43.7 (Paket E, E2E_01436b H1)"), b = s.indexOf("    var anzeigeTaktId = null;");
        assertTrue(a > 0 && b > a);
        String teil = s.substring(a, b);
        for (String verboten : new String[] { "=>", "let ", "const ", "`", "class ", "setInterval", "setTimeout" }) {
            assertFalse("ES5, kein eigener Takt: " + verboten, teil.contains(verboten));
        }
        assertTrue("Schleifenschutz je Kennung und 60 s",
                teil.contains("if (alt && (alt.k === kennung || jetzt - (alt.t || 0) < 60000)) return false;"));
        assertTrue("ohne sessionStorage kein Neuladen", teil.contains("} catch (e) { return false; }"));
        assertTrue("Eingaben gehen nicht verloren", teil.contains("tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT'"));
        assertEquals("genau ein Neuladen-Aufruf", 1, teil.split("location\\.reload\\(\\)", -1).length - 1);
        assertTrue("Liste nur bei neuer Kennung", teil.contains("if (kennung && kennung === standGeprueft) return;"));
        assertTrue(s.contains("if (d.webStand) standPruefen(d.webStand);"));
        assertTrue(s.contains("if (p.webStand) standPruefen(p.webStand);"));
        assertTrue("kein ?v= in Kommentaren (sonst zwei Zähler)", !teil.contains("?v=13") && !teil.contains("?v=pn37"));
    }

    private static File basis() {
        return new File("src/main").isDirectory() ? new File(".") : new File("app");
    }

    private static String lies(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(basis(), rel).toPath()), StandardCharsets.UTF_8);
    }
}
