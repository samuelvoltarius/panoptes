package at.greenchili.surfmate.tacho;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.1.42 (T2) Tacho-Galerie: 15 eigene Designs in tacho-galerie.js, zusammen 20. Vertrag zwischen Kern-Liste,
 * Renderer, Galerie-Oberfläche, Editor-Vorlage und Übersetzungen; dazu Ruhe, Kontrast und ES5.
 */
public class TachoGalerieTest {

    private static final String GAL = "app/src/main/assets/web/shared/tacho-galerie.js";
    private static final String DES = "app/src/main/assets/web/shared/tacho-designs.js";
    private static final String AUS = "app/src/main/assets/web/shared/tacho-auswahl.js";
    private static final String GES = "app/src/main/assets/web/shared/tacho-gestalten.js";

    private static String lies(String r) throws Exception { return TachoFaehigkeitTest.lies(r).replace("\r\n", "\n"); }

    private static List<String> registriert(String js) {
        List<String> r = new ArrayList<>();
        Matcher m = Pattern.compile("T\\.registriere\\('([a-z]+)'").matcher(js);
        while (m.find()) r.add(m.group(1));
        return r;
    }

    @Test
    public void zwanzigDesignsKernUndRendererGleich() throws Exception {
        String js = lies(GAL);
        assertEquals("gleiche Namen, gleiche Reihenfolge", TachoAnsicht.GALERIE, registriert(js));
        assertEquals(15, TachoAnsicht.GALERIE.size());
        assertEquals(20, TachoAnsicht.DESIGNS.size());
        for (String d : TachoAnsicht.GALERIE) {
            assertEquals(d, TachoAnsicht.design(d));
            assertEquals("design", TachoAuswahl.ziel("design:" + d)[0]);
            assertFalse("eigener Name: " + d, "Klar".equals(TachoAuswahl.designName(d)));
            assertTrue(d, d.matches("[a-z]{3,16}"));
        }
        // keine Kamera-, Video-, Musik- oder Motorsound-Ansicht in diesem Paket
        for (String verboten : new String[] {"kamera", "video", "musik", "sound", "camera"})
            assertFalse(verboten, TachoAnsicht.DESIGNS.contains(verboten) || js.toLowerCase().contains("'" + verboten));
        // Renderer: Anmeldung, Zeichnung über EXTRA, Vorlage mit Plätzen des Designs
        String des = lies(DES);
        assertTrue(des.contains("function registriere(name, def)"));
        assertTrue(des.contains("if (EXTRA[dsg]) { EXTRA[dsg].zeichne(c, w, h, u, th, d, st, jetzt, HILFE); return; }"));
        assertTrue(des.contains("if (x.plaetze) for (k in x.plaetze) if (p.hasOwnProperty(k)) p[k] = x.plaetze[k];"));
    }

    @Test
    public void jedeKategorieHatDesignsUndFilterStimmen() throws Exception {
        String js = lies(GAL);
        Map<String, Integer> zahl = new HashMap<>();
        Matcher m = Pattern.compile("arten: \\[([^\\]]*)\\]").matcher(js);
        int n = 0;
        while (m.find()) {
            n++;
            for (String a : m.group(1).replace("'", "").split(",")) {
                String k = a.trim();
                assertTrue("bekannte Art: " + k, k.equals("analog") || k.equals("digital") || k.equals("navi") || k.equals("ruhig"));
                zahl.put(k, zahl.getOrDefault(k, 0) + 1);
            }
        }
        assertEquals(15, n);
        for (String k : new String[] {"analog", "digital", "navi", "ruhig"}) assertTrue(k, zahl.getOrDefault(k, 0) >= 3);
        String aus = lies(AUS);
        assertTrue(aus.contains("['alle', 'analog', 'digital', 'navi', 'ruhig'].forEach"));
        assertTrue(aus.contains("PanoptesTacho.arten(e.design).indexOf(filter) >= 0"));
        assertTrue("ein Tipp wählt und zeigt die Vorschau",
            aus.contains("b.onclick = function () { if (e.art === 'design') zeigeLive(e.design); waehle(e); };"));
        String html = lies("app/src/main/assets/web/local/display.html");
        assertTrue(html.indexOf("tacho-galerie.js") > html.indexOf("tacho-designs.js"));
        assertTrue(html.contains("id=\"tachoGalerieFilter\"") && html.contains("id=\"tachoGalerieLive\""));
        String ans = lies("app/src/main/assets/web/local/tacho-ansicht.html");
        assertTrue("Fahrerdisplay zeichnet die Galerie-Designs", ans.indexOf("tacho-galerie.js") > ans.indexOf("tacho-designs.js"));
    }

    @Test
    public void galerieDesignAlsVorlageImEditorUndDateiPruefung() throws Exception {
        String ges = lies(GES);
        assertTrue(ges.contains("window.PanoptesTachoGestalten = {"));
        assertTrue("gespeichertes Design überschreibt die Vorlage nicht", ges.contains("e = T.vorlage(d);\n                stand.geladen = true;"));
        assertTrue(lies(AUS).contains("window.PanoptesTachoGestalten.vorlage(dsg)"));
        // Datei mit Galerie-Basis: Kern nimmt die Basis an, Geheimnisse und Fremdes fallen weg
        JSONObject roh = new JSONObject().put("format", TachoEigen.FORMAT).put("version", TachoEigen.VERSION).put("basis", "tunnel")
            .put("farben", new JSONObject().put("hintergrund", "#0A0E1A").put("ziffern", "#EEF2FF").put("zeiger", "#9C8CFF").put("akzent", "#5CF2B5"))
            .put("token", "geheim").put("url", "http://x");
        TachoEigen.Ergebnis g = TachoEigen.pruefen(roh);
        assertNotNull(g);
        assertEquals("tunnel", g.eigen.getString("basis"));
        assertFalse(g.eigen.has("token") || g.eigen.has("url"));
        assertEquals("klar", TachoEigen.pruefen(new JSONObject().put("basis", "fremd")).eigen.getString("basis"));
    }

    // ---------- Kontrast: Text mindestens 4,5:1 zu beiden Hintergrundtönen ----------

    private static double lum(String hex) {
        int n = Integer.parseInt(hex.substring(1), 16);
        double s = 0;
        int[] k = {(n >> 16) & 255, (n >> 8) & 255, n & 255};
        double[] g = {0.2126, 0.7152, 0.0722};
        for (int i = 0; i < 3; i++) {
            double v = k[i] / 255.0;
            s += g[i] * (v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4));
        }
        return s;
    }

    static double kontrast(String a, String b) {
        double x = lum(a), y = lum(b);
        return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
    }

    private static Map<String, String> farben(String block) {
        Map<String, String> r = new HashMap<>();
        Matcher m = Pattern.compile("(\\w+): '(#[0-9A-Fa-f]{6})'").matcher(block);
        while (m.find()) r.put(m.group(1), m.group(2));
        return r;
    }

    @Test
    public void textKontrastJedesDesignMindestens45() throws Exception {
        String js = lies(GAL);
        int a = js.indexOf("var t = {"), b = js.indexOf("};", a);
        Map<String, String> std = farben(js.substring(a, b));
        Matcher m = Pattern.compile("thema\\(\\{([^}]*)\\}\\)").matcher(js);
        int n = 0;
        while (m.find()) {
            n++;
            Map<String, String> th = new HashMap<>(std);
            th.putAll(farben(m.group(1)));
            for (String text : new String[] {"fg", "dim", "ring", "ring2", "reku", "warn"})
                for (String bg : new String[] {"bg0", "bg1"}) {
                    double k = kontrast(th.get(text), th.get(bg));
                    assertTrue("Design " + n + " " + text + "/" + bg + " = " + k, k >= 4.5);
                }
        }
        assertEquals(15, n);
        assertTrue(kontrast("#FFFFFF", "#000000") > 20.9);
    }

    @Test
    public void ruhigOhneNetzUndEs5() throws Exception {
        String js = lies(GAL);
        for (String verboten : new String[] {"setInterval", "setTimeout", "requestAnimationFrame", "Math.random", "Date.now", "fetch(",
                "XMLHttpRequest", "http:", "https:", "=>", "let ", "const ", "`", "?.", "??", "shadowBlur"})
            assertFalse("galerie: " + verboten, js.contains(verboten));
        String aus = lies(AUS);
        for (String verboten : new String[] {"=>", "let ", "const ", "`", "?.", "??", "inset:", "gap:6px"})
            assertFalse("auswahl: " + verboten, aus.contains(verboten));
        // die Live-Vorschau läuft ruhig (2 Bilder pro Sekunde) und ruht bei verdeckter Seite
        assertTrue(aus.contains("live.takt = setInterval(tick, 500);") && aus.contains("if (document.hidden) return;"));
    }

    @Test
    public void einhaengestelleAnsichtFuerKameraUndVideo() throws Exception {
        String des = lies(DES);
        assertTrue(des.contains("function ansichtBereich(w, h) { return { x: 0, y: 0, w: w, h: Math.max(0, h - leisteHoehe(w, h)) }; }"));
        assertTrue("Tempo bleibt in der Leiste", des.contains("zeichneAnsichtLeiste(c, w, h, u, ath, d, jetzt);"));
        assertTrue(des.contains("ansicht: function (an) { anz.ansicht = !!an; neu = true; },"));
        assertTrue(des.contains("ansichtBereich: function () { groesse(); return ansichtBereich(w, h); },"));
        // setze() überschreibt die Ansicht nicht (nur die Fahrdaten)
        assertFalse(des.contains("'navi', 'ansicht'"));
        assertTrue(lies("app/src/main/assets/web/local/tacho-ansicht.html").contains("window.tachoInstanz = t;"));
    }

    @Test
    public void namenUndGalerieTexteInFuenfSprachen() throws Exception {
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject j = new JSONObject(lies("app/src/main/assets/web/i18n/" + l + ".json"));
            JSONObject vc = j.getJSONObject("vehicle_control");
            for (String d : TachoAnsicht.GALERIE) assertTrue(l + ": " + d, vc.optString("cluster_design_" + d, "").length() > 1);
            JSONObject g = j.getJSONObject("tacho_galerie");
            for (String k : new String[] {"alle", "analog", "digital", "navi", "ruhig", "live", "vorlage", "vorlage_ok"})
                assertTrue(l + ": " + k, g.optString(k, "").length() > 1);
        }
    }
}
