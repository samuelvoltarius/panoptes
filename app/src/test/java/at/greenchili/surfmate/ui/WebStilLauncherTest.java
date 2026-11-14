package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.einstellungen.Feld;
import at.greenchili.surfmate.einstellungen.Katalog;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.1.43.6 (14.11., „als alternatives Design mit einbauen“): Launcher-Stil L3 als wählbares Design der
 * Weboberfläche. Standard bleibt das bisherige Panoptes-Design; umgeschaltet wird nur über html[data-stil] und ein
 * darunter gekapseltes Stylesheet; die Wahl liegt im Kern (anzeige.stil) und gilt für alle Geräte.
 */
public class WebStilLauncherTest {
    private static final String WEB = "src/main/assets/web/";
    private static final String[] SPRACHEN = { "de", "en", "fr", "it", "nl" };

    // ---------- Kern: Feld, Standard, Speicherung ----------

    @Test
    public void feldImKernStandardPanoptes() {
        Feld f = Katalog.finde(Katalog.felder(new Katalog.Kontext()), Katalog.STIL);
        assertNotNull(f);
        assertEquals(Feld.Typ.AUSWAHL, f.typ);
        assertEquals("Standard = altes Design", "panoptes", f.standard);
        assertEquals("system", f.gruppe);
        assertFalse("vom PC/Handy aus änderbar, gilt für alle Geräte", f.nurLesendExtern);
        assertEquals(2, f.optionen.size());
        assertEquals("panoptes", f.optionen.get(0).wert);
        assertEquals("launcher", f.optionen.get(1).wert);
    }

    @Test
    public void gespeicherterWertOhneWertPanoptes() throws Exception {
        assertEquals("panoptes", Katalog.stil(null));
        assertEquals("panoptes", Katalog.stil(new JSONObject()));
        assertEquals("panoptes", Katalog.stil(new JSONObject("{\"anzeige\":{\"stil\":\"quatsch\"}}")));
        assertEquals("launcher", Katalog.stil(new JSONObject("{\"anzeige\":{\"stil\":\"launcher\"}}")));
        String h = lies("src/main/java/at/greenchili/surfmate/server/SystemApiHandler.java");
        assertTrue("jede Seite erfährt den Stil über das Profil", h.contains("res.put(\"stil\", at.greenchili.surfmate.einstellungen.Katalog.stil(cfg));"));
    }

    // ---------- Schalter: theme.js stempelt, app-shell gleicht ab, display.html wählt ----------

    @Test
    public void schalterWirkungNurUeberHtmlAttribut() throws Exception {
        String t = lies(WEB + "shared/theme.js");
        assertTrue(t.contains("function stilGueltig(v) { return v === 'launcher' ? 'launcher' : 'panoptes'; }"));
        assertTrue("Standard: kein Attribut, kein zusätzliches Stylesheet",
                t.contains("if (stilGueltig(v) === 'launcher') { stilCssLaden(); root.setAttribute('data-stil', 'launcher'); }\n        else root.removeAttribute('data-stil');")
                        || t.contains("if (stilGueltig(v) === 'launcher') { stilCssLaden(); root.setAttribute('data-stil', 'launcher'); }\r\n        else root.removeAttribute('data-stil');"));
        assertTrue("erster Aufbau aus dem Spiegel, ohne Flackern", t.contains("stampStil(readStoredStil());"));
        assertTrue("setzen liest den Kern zurück, erst dann wird angewendet", t.contains("if (n !== v) return false;"));
        assertTrue(t.contains("w['anzeige.stil'] = v;"));
        String s = lies(WEB + "shared/app-shell.js");
        assertTrue(s.contains("if ((p.stil === 'panoptes' || p.stil === 'launcher') && window.BYD && BYD.stil) BYD.stil.anwenden(p.stil);"));
        String d = lies(WEB + "local/display.html");
        assertTrue(d.contains("data-stil-wahl=\"panoptes\"") && d.contains("data-stil-wahl=\"launcher\""));
        assertTrue("kleine Vorschau neben der Wahl", d.contains("class=\"dp-stilv\"") && d.contains("dp-sv-l-n") && d.contains("dp-sv-l-t"));
        assertTrue(d.contains("BYD.stil.setzen(w)"));
    }

    @Test
    public void es5InTheme() throws Exception {
        String t = lies(WEB + "shared/theme.js");
        int a = t.indexOf("Step 1b: Aussehen"), b = t.indexOf("Step 2: server sync");
        String teil = t.substring(a, b) + t.substring(t.indexOf("window.BYD.stil = {"), t.indexOf("Step 5: floating theme picker"));
        for (String verboten : new String[] { "=>", "let ", "const ", "`", "class " }) {
            assertFalse("ES5 (Chromium 74): " + verboten, teil.contains(verboten));
        }
    }

    // ---------- CSS: gekapselt, Tag und Nacht, Rückfall ohne backdrop-filter, Schriften lokal ----------

    @Test
    public void stylesheetNurUnterDemAttribut() throws Exception {
        String css = lies(WEB + "shared/stil-launcher.css").replaceAll("/\\*[\\s\\S]*?\\*/", "");
        Matcher m = Pattern.compile("([^{}]+)\\{").matcher(css);
        int regeln = 0;
        while (m.find()) {
            String sel = m.group(1).trim();
            if (sel.startsWith("@supports")) continue;
            for (String teil : sel.split(",")) {
                assertTrue("gekapselt: " + teil.trim(), teil.trim().startsWith("html[data-stil=\"launcher\"]"));
            }
            regeln++;
        }
        assertTrue(regeln > 15);
        assertFalse("kein CDN", css.contains("http://") || css.contains("https://") || css.contains("googleapis"));
        assertTrue("Chromium 74: festes Glas statt Durchschein",
                css.contains("@supports not ((backdrop-filter: blur(1px)) or (-webkit-backdrop-filter: blur(1px)))"));
        assertTrue(css.contains("html[data-stil=\"launcher\"][data-theme=\"light\"]:not(#pn) {"));
        String tokens = lies(WEB + "shared/design-tokens.css");
        assertTrue("Sora/Manrope lokal eingebettet", tokens.contains("url('fonts/sora-latin-wght.woff2')") && tokens.contains("url('fonts/manrope-latin-wght.woff2')"));
        assertTrue(new File(basis(), WEB + "shared/fonts/sora-OFL.txt").isFile() && new File(basis(), WEB + "shared/fonts/manrope-OFL.txt").isFile());
    }

    @Test
    public void tagUndNachtSetzenDieselbenFarben() throws Exception {
        String css = lies(WEB + "shared/stil-launcher.css");
        Set<String> nacht = variablen(block(css, "html[data-stil=\"launcher\"]:not(#pn) {"));
        Set<String> tag = variablen(block(css, "html[data-stil=\"launcher\"][data-theme=\"light\"]:not(#pn) {"));
        // Jede Farbe, die das helle Panoptes-Thema setzt, muss der Tag-Block des Launcher-Stils ebenfalls setzen
        String tokens = lies(WEB + "shared/design-tokens.css");
        Set<String> panoptesHell = variablen(block(tokens, ":root[data-theme=\"light\"] {"));
        for (String v : panoptesHell) {
            if (v.startsWith("--radius") || v.startsWith("--shadow") || v.equals("--scrim")) continue;
            if (nacht.contains(v)) assertTrue("Tag-Block setzt " + v, tag.contains(v));
        }
        for (String v : new String[] { "--primary", "--background", "--surface", "--on-surface", "--sm-card", "--sm-line", "--sm-page-bg",
                "--status-success", "--status-warning", "--status-danger", "--pn-glas-dicht", "--pn-leiste-dicht" }) {
            assertTrue("Nacht " + v, nacht.contains(v));
            assertTrue("Tag " + v, tag.contains(v));
        }
        assertTrue(css.contains("--primary: #35D0E6;") && css.contains("--status-warning: #FFB454;") && css.contains("--status-success: #5EE3A1;"));
    }

    // ---------- Abdeckung: jede Seite mit dem Panoptes-Design bekommt auch den Launcher-Stil ----------

    @Test
    public void beideDesignsDeckenAlleSeitenAb() throws Exception {
        File[] seiten = new File(basis(), WEB + "local").listFiles((dir, n) -> n.endsWith(".html"));
        assertNotNull(seiten);
        int n = 0;
        for (File f : seiten) {
            String h = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            if (!h.contains("styles.css")) continue;
            if (!h.contains("theme.js")) continue;   // eingebettete Launcher-Seiten ohne Panoptes-Thema
            assertTrue(f.getName() + ": neue theme.js", h.contains("theme.js?v=14"));
            assertFalse(f.getName(), h.contains("theme.js?v=13"));
            n++;
        }
        assertTrue("alle Hauptseiten", n >= 40);
        for (String haupt : new String[] { "index", "navi", "trips", "vehicle-control", "waechter", "recording", "automations", "apps", "display", "system" }) {
            String h = lies(WEB + "local/" + haupt + ".html");
            assertTrue(haupt, h.contains("theme.js?v=14") && h.contains("styles.css"));
        }
    }

    // ---------- Texte in 5 Sprachen (Web und Einstellungs-Schema) ----------

    @Test
    public void texteInFuenfSprachen() throws Exception {
        for (String l : SPRACHEN) {
            JSONObject w = new JSONObject(lies(WEB + "i18n/" + l + ".json")).getJSONObject("display");
            for (String k : new String[] { "stil_titel", "stil_hilfe", "stil_panoptes", "stil_launcher", "stil_fehler" }) {
                assertTrue(l + " " + k, w.optString(k, "").length() > 1);
            }
            JSONObject s = new JSONObject(lies("src/main/assets/server-i18n/" + l + ".json"))
                    .getJSONObject("settings").getJSONObject("anzeige").getJSONObject("stil");
            for (String k : new String[] { "title", "help", "panoptes", "launcher" }) assertTrue(l + " " + k, s.optString(k, "").length() > 1);
        }
        assertEquals("Du-Form", "Gilt für alle Geräte: Auto, PC und Handy. Es ändert sich nur das Aussehen, keine Funktion.",
                new JSONObject(lies(WEB + "i18n/de.json")).getJSONObject("display").getString("stil_hilfe"));
    }

    // ---------- Hilfen ----------

    private static String block(String css, String kopf) {
        int i = css.indexOf(kopf);
        assertTrue("Block fehlt: " + kopf, i >= 0);
        return css.substring(i, css.indexOf('}', i));
    }

    private static Set<String> variablen(String block) {
        Set<String> s = new LinkedHashSet<>();
        Matcher m = Pattern.compile("(--[a-z0-9-]+)\\s*:").matcher(block);
        while (m.find()) s.add(m.group(1));
        return s;
    }

    private static File basis() {
        return new File("src/main").isDirectory() ? new File(".") : new File("app");
    }

    private static String lies(String rel) throws Exception {
        Path p = Paths.get(rel);
        if (!Files.exists(p)) p = Paths.get("app").resolve(rel);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
