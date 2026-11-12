package at.greenchili.surfmate.tacho;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.1.41 „Tacho selbst gestalten“: Vertrag fuer Import-Pruefung (feste Erlaubnislisten), Pflichtfeld Tempo,
 * Mindestkontrast, Fahrsperre, Einstieg unter „Erweitert“ und 5 Sprachen. Kern (TachoEigen) und Oberflaeche
 * (tacho-designs.js) muessen dieselben Regeln haben.
 */
public class TachoGestaltenTest {

    private static final String JS = "app/src/main/assets/web/shared/tacho-designs.js";
    private static final String EDITOR = "app/src/main/assets/web/shared/tacho-gestalten.js";
    private static final String DISPLAY = "app/src/main/assets/web/local/display.html";

    private static JSONObject gut() throws Exception {
        return TachoEigen.standard();
    }

    private static List<String> schluessel(JSONObject o) {
        List<String> l = new ArrayList<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) l.add(it.next());
        java.util.Collections.sort(l);
        return l;
    }

    // ------------------------------------------------------------ Import-Pruefung

    @Test
    public void importNurAusFestenErlaubnislisten() throws Exception {
        JSONObject roh = gut();
        roh.put("pfad", "/sdcard/x.png").put("lat", 47.8).put("token", "geheim").put("hintergrundBild", "content://x");
        roh.getJSONObject("farben").put("rahmen", "#FF0000").put("ziffern", "weiss");
        roh.getJSONObject("plaetze").put("links", "passwort").put("ganz_unten", "akku").put("oben_links", "AKKU ");
        roh.put("schrift", "Comic Sans").put("zeiger", "laser").put("basis", "fremd");

        TachoEigen.Ergebnis e = TachoEigen.pruefen(roh);
        assertNotNull(e);
        assertEquals(Arrays.asList("basis", "farben", "format", "plaetze", "schrift", "version", "zeiger"), schluessel(e.eigen));
        assertEquals(Arrays.asList("akzent", "hintergrund", "zeiger", "ziffern"), schluessel(e.eigen.getJSONObject("farben")));
        List<String> plaetze = new ArrayList<>(TachoEigen.GROSS);
        plaetze.addAll(TachoEigen.KLEIN);
        java.util.Collections.sort(plaetze);
        assertEquals(plaetze, schluessel(e.eigen.getJSONObject("plaetze")));
        assertEquals("unbekannte Anzeige faellt weg", "leer", e.eigen.getJSONObject("plaetze").getString("links"));
        assertEquals("Gross-/Kleinschreibung und Leerzeichen egal", "akku", e.eigen.getJSONObject("plaetze").getString("oben_links"));
        assertEquals("akku nur einmal", "leer", e.eigen.getJSONObject("plaetze").getString("unten_links"));
        assertEquals("schmal", e.eigen.getString("schrift"));
        assertEquals("balken", e.eigen.getString("zeiger"));
        assertEquals("klar", e.eigen.getString("basis"));
        assertEquals("#EEF4F8", e.eigen.getJSONObject("farben").getString("ziffern"));
        assertTrue(e.warnungen.contains("farbe_ungueltig"));
        String text = e.eigen.toString();
        for (String verboten : new String[] {"sdcard", "content:", "geheim", "47.8", "token", "pfad", "lat", "rahmen", "passwort"}) {
            assertFalse(verboten, text.contains(verboten));
        }
    }

    @Test
    public void falscheOderZuGrosseDateiWirdAbgelehnt() throws Exception {
        assertNull(TachoEigen.pruefenText(null));
        assertNull(TachoEigen.pruefenText("kein json"));
        assertNull(TachoEigen.pruefenText("[1,2]"));
        assertNull("fremdes Format", TachoEigen.pruefenText(gut().put("format", "panoptes-launcher-layout").toString()));
        assertNull("andere Version", TachoEigen.pruefenText(gut().put("version", 2).toString()));
        StringBuilder gross = new StringBuilder(gut().toString());
        gross.insert(1, "\"x\":\"" + new String(new char[TachoEigen.MAX_ZEICHEN]).replace('\0', 'a') + "\",");
        assertNull("zu gross", TachoEigen.pruefenText(gross.toString()));
        TachoEigen.Ergebnis hin = TachoEigen.pruefenText(gut().toString());
        assertNotNull(hin);
        assertTrue(hin.warnungen.isEmpty());
        assertEquals("hin und zurueck gleich", gut().toString(), hin.eigen.toString());
    }

    @Test
    public void farbenNurAlsRrggbb() throws Exception {
        for (String falsch : new String[] {"red", "#FFF", "#GGGGGG", "url(x)", "#12345678", "rgb(1,2,3)", ""}) {
            JSONObject roh = gut();
            roh.getJSONObject("farben").put("zeiger", falsch);
            assertEquals(falsch, "#B5F23D", TachoEigen.pruefen(roh).eigen.getJSONObject("farben").getString("zeiger"));
        }
        JSONObject roh = gut();
        roh.getJSONObject("farben").put("zeiger", "#ff00aa");
        assertEquals("#FF00AA", TachoEigen.pruefen(roh).eigen.getJSONObject("farben").getString("zeiger"));
    }

    // ------------------------------------------------------------ Pflichtfeld Tempo

    @Test
    public void tempoIstPflichtUndStehtImmerGross() throws Exception {
        JSONObject ohne = gut();
        ohne.getJSONObject("plaetze").put("rechts", "akku").put("unten_links", "leer");
        TachoEigen.Ergebnis e = TachoEigen.pruefen(ohne);
        assertEquals("tempo", e.eigen.getJSONObject("plaetze").getString("rechts"));
        assertTrue(e.warnungen.contains("tempo_pflicht"));

        JSONObject klein = gut();
        klein.getJSONObject("plaetze").put("rechts", "leer").put("oben_mitte", "tempo").put("unten_mitte", "navi");
        e = TachoEigen.pruefen(klein);
        JSONObject p = e.eigen.getJSONObject("plaetze");
        assertEquals("Tempo nie auf kleinem Platz", "leer", p.getString("oben_mitte"));
        assertEquals("Navi nie auf kleinem Platz", "leer", p.getString("unten_mitte"));
        assertEquals("tempo", p.getString("rechts"));
        assertTrue(e.warnungen.contains("tempo_pflicht"));

        JSONObject mitte = gut();
        mitte.getJSONObject("plaetze").put("rechts", "akku").put("mitte", "tempo").put("unten_links", "leer");
        e = TachoEigen.pruefen(mitte);
        assertEquals("tempo", e.eigen.getJSONObject("plaetze").getString("mitte"));
        assertFalse(e.warnungen.contains("tempo_pflicht"));

        // Keine Plaetze, kein Objekt: trotzdem sichtbares Tempo.
        e = TachoEigen.pruefen(new JSONObject());
        assertEquals("tempo", e.eigen.getJSONObject("plaetze").getString("rechts"));
        int tempo = 0;
        for (String k : schluessel(e.eigen.getJSONObject("plaetze")))
            if ("tempo".equals(e.eigen.getJSONObject("plaetze").getString(k))) tempo++;
        assertEquals(1, tempo);
    }

    // ------------------------------------------------------------ Kontrast

    @Test
    public void mindestkontrastFuerTempoUndGang() throws Exception {
        assertEquals(21.0, TachoEigen.kontrast("#000000", "#FFFFFF"), 1e-9);
        assertEquals(1.0, TachoEigen.kontrast("#777777", "#777777"), 1e-9);
        assertEquals(1.0, TachoEigen.kontrast("weiss", "#000000"), 1e-9);
        assertEquals(4.5, TachoEigen.MIN_KONTRAST, 1e-9);
        assertTrue(TachoEigen.kontrast("#767676", "#FFFFFF") >= 4.5);   // bekannter Grenzwert
        assertTrue(TachoEigen.kontrast("#777777", "#FFFFFF") < 4.5);

        JSONObject schlecht = gut();
        schlecht.getJSONObject("farben").put("hintergrund", "#FFFFFF").put("ziffern", "#EEEEEE").put("zeiger", "#FF0000");
        TachoEigen.Ergebnis e = TachoEigen.pruefen(schlecht);
        assertTrue(e.warnungen.contains("kontrast"));
        assertEquals("zurueck zum Standard", TachoEigen.standard().getJSONObject("farben").toString(),
                e.eigen.getJSONObject("farben").toString());

        JSONObject hell = gut();
        hell.getJSONObject("farben").put("hintergrund", "#FFFFFF").put("ziffern", "#111111");
        e = TachoEigen.pruefen(hell);
        assertFalse(e.warnungen.contains("kontrast"));
        assertEquals("#FFFFFF", e.eigen.getJSONObject("farben").getString("hintergrund"));
        assertTrue("Standard selbst ist lesbar", TachoEigen.kontrast("#EEF4F8", "#0E1620") >= 4.5);
    }

    @Test
    public void rendererZeichnetGangUndTempoInDerGepruefenZiffernfarbe() throws Exception {
        String js = TachoFaehigkeitTest.lies(JS);
        assertTrue(js.contains("if (kontrast(f.ziffern, f.hintergrund) < E.MIN_KONTRAST) {"));
        assertTrue("Gang klein in Ziffernfarbe", js.contains("v.gang || a === 'uhr' ? th.fg : th.dim"));
        assertTrue("fg = Ziffern", js.contains("bg0: f.hintergrund, bg1: f.hintergrund, fg: f.ziffern,"));
        assertTrue("Design wird vor dem Zeichnen geprueft", js.contains("if (d.eigen !== undefined) anz.eigen = geprueft(d.eigen);"));
    }

    // ------------------------------------------------------------ Kern und Oberflaeche: dieselben Listen

    private static List<String> jsListe(String js, String name) {
        Matcher m = Pattern.compile(name + ": \\[([^\\]]*)\\]").matcher(js);
        assertTrue(name, m.find());
        List<String> l = new ArrayList<>();
        Matcher w = Pattern.compile("'([a-z_]+)'").matcher(m.group(1));
        while (w.find()) l.add(w.group(1));
        return l;
    }

    @Test
    public void kernUndOberflaecheHabenDieselbenRegeln() throws Exception {
        String js = TachoFaehigkeitTest.lies(JS);
        assertEquals(TachoEigen.FARBEN, jsListe(js, "FARBEN"));
        assertEquals(TachoEigen.SCHRIFTEN, jsListe(js, "SCHRIFTEN"));
        assertEquals(TachoEigen.ZEIGER, jsListe(js, "ZEIGER"));
        assertEquals(TachoEigen.GROSS, jsListe(js, "GROSS"));
        assertEquals(TachoEigen.KLEIN, jsListe(js, "KLEIN"));
        assertEquals(TachoEigen.ANZEIGEN, jsListe(js, "ANZEIGEN"));
        assertEquals(TachoEigen.NUR_GROSS, jsListe(js, "NUR_GROSS"));
        assertTrue(js.contains("FORMAT: '" + TachoEigen.FORMAT + "'"));
        assertTrue(js.contains("MIN_KONTRAST: 4.5"));
        assertTrue(js.contains("MAX_ZEICHEN: " + TachoEigen.MAX_ZEICHEN));
        // Nur Anzeigen mit Datenquelle: jede Anzeige ausser navi/uhr/leer kommt aus TachoAnsicht.antwort.
        JSONObject a = TachoAnsicht.antwort(true, 50, 70, 300, "eigen", -3, 4, 14, 18342);
        for (String k : new String[] {"kmh", "soc", "reichweiteKm", "kw", "gang", "aussenC", "kmStand"}) assertTrue(k, a.has(k));
        assertEquals("eigen", a.getString("design"));
    }

    // ------------------------------------------------------------ Auswahl, Fahrsperre, Einstieg

    @Test
    public void eigenesDesignInDerAuswahlUndAmTacho() throws Exception {
        assertEquals("eigen", TachoAnsicht.design("Eigen"));
        // 0.1.42 (T2): 5 + 15 Galerie-Designs
        assertEquals(20, TachoAnsicht.DESIGNS.size());
        assertEquals("design", TachoAuswahl.ziel("design:eigen")[0]);
        JSONObject st = new JSONObject().put("aktiv", JSONObject.NULL).put("angeboten", true).put("apps", new JSONArray());
        assertEquals(20, TachoAuswahl.liste(st, "klar", false).getJSONArray("eintraege").length());
        JSONArray mit = TachoAuswahl.liste(st, "klar", true).getJSONArray("eintraege");
        assertEquals(21, mit.length());
        assertEquals("design:eigen", mit.getJSONObject(20).getString("id"));
        assertEquals("Eigenes", mit.getJSONObject(20).getString("name"));
        String act = TachoFaehigkeitTest.lies("app/src/main/java/at/greenchili/surfmate/tacho/TachoAnsichtActivity.kt");
        assertTrue(act.contains("\"kmStand\", \"design\", \"eigen\")) {"));
        String ansicht = TachoFaehigkeitTest.lies("app/src/main/java/at/greenchili/surfmate/tacho/TachoAnsicht.java");
        assertTrue("am Tacho nur das gepruefte Design", ansicht.contains("JSONObject e = TachoEigen.gespeichert();"));
    }

    @Test
    public void speichernNurImStand() throws Exception {
        String api = TachoFaehigkeitTest.lies("app/src/main/java/at/greenchili/surfmate/server/TachoApiHandler.java")
                .replaceAll("\\s+", " ");
        int put = api.indexOf("if (p.equals(\"/api/tacho/eigen\") && (method.equals(\"PUT\") || method.equals(\"POST\"))) {");
        assertTrue(put > 0);
        String block = api.substring(put, api.indexOf("return true; }", api.indexOf("TachoEigen.speichern", put)));
        assertTrue("Fahrsperre zuerst", block.indexOf("bewegungsGrund()") < block.indexOf("TachoEigen.speichern"));
        assertTrue(block.contains(".put(\"grund\", \"nur_im_stand\")"));
        assertTrue("immer gepruft", block.contains("TachoEigen.pruefen(in.optJSONObject(\"eigen\"))"));
        assertTrue(TachoFaehigkeitTest.lies("app/src/main/java/at/greenchili/surfmate/server/WebHandyGuard.java")
                .contains("\"/api/tacho/eigen\","));
        String ed = TachoFaehigkeitTest.lies(EDITOR);
        assertTrue("Editor sperrt in Fahrt", ed.contains("if (!stand.imStand) { sperren(); return; }"));
        assertTrue(ed.contains("steuer.forEach(function (x) { x.disabled = zu;"));
        assertTrue("ohne Kern gesperrt", ed.contains("function () { stand.imStand = false; sperren(); }"));
    }

    @Test
    public void einstiegUnterErweitertMitGrossenKnoepfenUndFuenfSprachen() throws Exception {
        String html = TachoFaehigkeitTest.lies(DISPLAY);
        int erw = html.indexOf("<details id=\"tachoErweitert\"");
        assertTrue(erw > 0);
        assertTrue(html.indexOf("data-i18n=\"tacho_gestalten.erweitert\">Erweitert<", erw) > erw);
        assertTrue(html.indexOf("<div id=\"tachoGestalten\"></div>", erw) > erw);
        assertTrue(html.contains("<script src=\"../shared/tacho-gestalten.js?v=3\"></script>"));
        String ed = TachoFaehigkeitTest.lies(EDITOR);
        assertTrue(ed.contains("t('tacho_gestalten.knopf', 'Tacho gestalten')"));
        assertTrue("grosse Knoepfe (50+)", ed.contains("min-height:56px;padding:10px 16px;font-size:17px;"));
        for (String verboten : new String[] {"?.", "??", "=>", "inset:", "`", "let ", "const "}) {
            assertFalse("ES5: " + verboten, ed.contains(verboten));
        }

        List<String> keys = new ArrayList<>();
        Matcher m = Pattern.compile("tacho_gestalten\\.([a-z_]+)").matcher(ed + html);
        while (m.find()) if (!keys.contains(m.group(1))) keys.add(m.group(1));
        assertTrue(keys.size() > 40);
        for (String lang : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject j = new JSONObject(TachoFaehigkeitTest.lies("app/src/main/assets/web/i18n/" + lang + ".json"));
            JSONObject tg = j.getJSONObject("tacho_gestalten");
            for (String k : keys) {
                if ("a_".equals(k)) continue;   // Praefix fuer die Anzeigenamen
                assertTrue(lang + ": " + k, tg.optString(k, "").length() > 0);
            }
            for (String a : TachoEigen.ANZEIGEN) assertTrue(lang + ": a_" + a, tg.optString("a_" + a, "").length() > 0);
            assertTrue(lang, j.getJSONObject("vehicle_control").optString("cluster_design_eigen", "").length() > 0);
        }
    }
}
