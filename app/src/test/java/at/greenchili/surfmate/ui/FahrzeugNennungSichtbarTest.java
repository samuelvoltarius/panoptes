package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import at.greenchili.surfmate.testutil.RepoWurzel;

/**
 * 0.1.43.5 (E2E_01435 R1): Die Namensnennung auf der Panoptes-Fahrzeugseite muss sichtbar sein (CC BY). Sie lag mit
 * {@code position:absolute; bottom:168px} unter dem Reifen-Panel. Jetzt steht sie im Fluss direkt unter dem Bild, und
 * {@code VC.placeLiteNennung()} schiebt sie über Reifen-Panel und Dock, falls die sie trotzdem überdecken (kleines
 * Fenster, Handy). Im Browser geprüft (1280×720 normal und mit erzwungener Überdeckung, 375×812 Handy):
 * {@code elementFromPoint} trifft die Nennung.
 */
public class FahrzeugNennungSichtbarTest {

    private static Path wurzel() {
        Path p = java.nio.file.Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("app/src/main/assets/web"))) p = RepoWurzel.hoch(p);
        if (p == null) throw new IllegalStateException("Repo nicht gefunden");
        return p;
    }

    private static String lies(String rel) throws Exception {
        return new String(Files.readAllBytes(wurzel().resolve(rel)), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String block(String text, String start) {
        int a = text.indexOf(start);
        assertTrue("fehlt: " + start, a >= 0);
        int b = text.indexOf("\n}", a);
        return text.substring(a, b + 2);
    }

    @Test
    public void nennungImFlussUnterDemBildNichtAmRandFest() throws Exception {
        String css = lies("app/src/main/assets/web/shared/vehicle-control.css");
        String n = block(css, ".vc-lite-hero-nennung {");
        assertFalse("nicht mehr absolut am unteren Rand (lag unter dem Reifen-Panel)", n.contains("position: absolute"));
        assertFalse(n.contains("bottom:"));
        assertTrue(n.contains("position: relative;") && n.contains("z-index: 2;"));
        assertTrue("für elementFromPoint und über dem Hero (pointer-events:none) erreichbar", n.contains("pointer-events: auto;"));
        assertFalse("keine Regel setzt sie wieder an den Rand", css.contains(".vc-lite-hero-nennung { bottom"));
        String hero = block(css, "html.vc-mode-lite .vc-lite-hero {");
        assertTrue("Spalte: Bild, darunter die Nennung", hero.contains("flex-direction: column;") && hero.contains("-webkit-flex-direction: column;"));
        assertTrue("Platz für die Nennung im Bildbereich", block(css, ".vc-lite-hero img {").contains("max-height: calc(100% - 22px);"));
        String html = lies("app/src/main/assets/web/local/vehicle-control.html");
        int img = html.indexOf("id=\"vcLiteHeroImg\"");
        int nennung = html.indexOf("id=\"vcLiteHeroNennung\"");
        assertTrue("Nennung im Hero direkt nach dem Bild", img > 0 && nennung > img && nennung < html.indexOf("</div>", img));
    }

    @Test
    public void weichtReifenPanelUndDockAusEs5() throws Exception {
        String js = lies("app/src/main/assets/web/shared/vehicle-control.js");
        int a = js.indexOf("placeLiteNennung: function() {");
        assertTrue(a > 0);
        String f = js.substring(a, js.indexOf("\n    },", a));
        for (String v : new String[] {"=>", "let ", "const ", "`"}) assertFalse("ES5: " + v, f.contains(v));
        assertTrue(js.contains("LITE_NENNUNG_HINDERNISSE: ['vcTyreOverlay', 'vcDock'],"));
        assertTrue("Überdeckung erkannt", f.contains("if (q.top < r.bottom && q.bottom > r.top) hoch = Math.max(hoch, r.bottom - q.top + 4);"));
        assertTrue("nach oben geschoben", f.contains("el.style.transform = 'translateY(-' + Math.ceil(hoch) + 'px)';"));
        assertTrue("vorher zurückgesetzt, sonst wächst die Verschiebung", f.indexOf("el.style.transform = '';") < f.indexOf("getBoundingClientRect()"));
        // aufgerufen: nach dem Bild, beim Größenwechsel und nach Umschaltern
        assertTrue(js.contains("img.onload = function() { VC.placeLiteNennung(); };"));
        assertTrue(js.contains("window.addEventListener('resize', function() { VC.placeLiteNennung(); });"));
        assertTrue(js.contains("document.addEventListener('click', function() { setTimeout(function() { VC.placeLiteNennung(); }, 350); }, true);"));
        // Hindernisse gibt es wirklich auf der Seite
        String html = lies("app/src/main/assets/web/local/vehicle-control.html");
        assertTrue(html.contains("id=\"vcTyreOverlay\"") && html.contains("id=\"vcDock\""));
    }
}
