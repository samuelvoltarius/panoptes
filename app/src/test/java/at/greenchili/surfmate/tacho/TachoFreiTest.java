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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.1.44 (E1) Freier Tacho-Editor (Format Version 2): Kern ({@link TachoFrei}) und Oberflaeche
 * ({@code PanoptesTacho.pruefeFrei} in tacho-designs.js) haben dieselben Regeln. Die Faelle in
 * {@code src/test/resources/tacho-frei-faelle.json} hat die Oberflaeche geprueft (node); der Kern muss genau dasselbe liefern.
 */
public class TachoFreiTest {

    private static final String JS = "app/src/main/assets/web/shared/tacho-designs.js";
    private static final String EDITOR = "app/src/main/assets/web/shared/tacho-gestalten.js";
    private static final String[] SPRACHEN = {"de", "en", "fr", "it", "nl"};

    private static String lies(String r) throws Exception { return TachoFaehigkeitTest.lies(r).replace("\r\n", "\n"); }

    private static JSONArray faelle() throws Exception { return new JSONArray(lies("app/src/test/resources/tacho-frei-faelle.json")); }

    /** Inhaltlich gleich (Schluessel, Reihenfolge der Listen, Zahlen als Wert). */
    static boolean gleich(Object a, Object b) {
        if (a instanceof JSONObject && b instanceof JSONObject) {
            JSONObject x = (JSONObject) a, y = (JSONObject) b;
            if (x.length() != y.length()) return false;
            for (java.util.Iterator<String> it = x.keys(); it.hasNext(); ) {
                String k = it.next();
                if (!y.has(k) || !gleich(x.opt(k), y.opt(k))) return false;
            }
            return true;
        }
        if (a instanceof JSONArray && b instanceof JSONArray) {
            JSONArray x = (JSONArray) a, y = (JSONArray) b;
            if (x.length() != y.length()) return false;
            for (int i = 0; i < x.length(); i++) if (!gleich(x.opt(i), y.opt(i))) return false;
            return true;
        }
        if (a instanceof Number && b instanceof Number) return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        return a != null && a.equals(b);
    }

    private static List<String> liste(JSONArray a) throws Exception {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) l.add(a.getString(i));
        return l;
    }

    // ------------------------------------------------------------ Kern = Oberflaeche

    @Test
    public void kernPrueftWieDieOberflaeche() throws Exception {
        JSONArray f = faelle();
        assertTrue(f.length() >= 18);
        for (int i = 0; i < f.length(); i++) {
            JSONObject fall = f.getJSONObject(i);
            String name = fall.getString("name");
            TachoEigen.Ergebnis e = TachoEigen.pruefen(fall.getJSONObject("ein"));
            assertNotNull(name, e);
            assertTrue(name + "\nKern: " + e.eigen + "\nSeite: " + fall.getJSONObject("aus"), gleich(e.eigen, fall.getJSONObject("aus")));
            assertEquals(name, liste(fall.getJSONArray("warnungen")), e.warnungen);
        }
    }

    private static List<String> jsListe(String js, String name) {
        Matcher m = Pattern.compile("\\b" + name + ": \\[([^\\]]*)\\]").matcher(js);
        assertTrue(name, m.find());
        List<String> l = new ArrayList<>();
        Matcher w = Pattern.compile("'([a-z_]+)'").matcher(m.group(1));
        while (w.find()) l.add(w.group(1));
        return l;
    }

    @Test
    public void dieselbenListenUndGrenzen() throws Exception {
        String js = lies(JS);
        assertEquals(TachoFrei.TYPEN, jsListe(js, "TYPEN"));
        assertEquals(TachoFrei.FORMEN, jsListe(js, "FORMEN"));
        assertEquals(TachoFrei.RUND_WERTE, jsListe(js, "RUND_WERTE"));
        assertEquals(TachoFrei.ZAHL_WERTE, jsListe(js, "ZAHL_WERTE"));
        assertEquals(TachoFrei.BALKEN_WERTE, jsListe(js, "BALKEN_WERTE"));
        assertEquals(TachoFrei.ZEIGERFORMEN, jsListe(js, "ZEIGERFORMEN"));
        assertEquals(TachoFrei.RICHTUNGEN, jsListe(js, "RICHTUNGEN"));
        assertEquals(TachoFrei.AUSRICHTUNGEN, jsListe(js, "AUSRICHTUNGEN"));
        assertEquals(TachoFrei.HG_ARTEN, jsListe(js, "HG_ARTEN"));
        String[][] zahlen = {{"VERSION_FREI", "" + TachoFrei.VERSION}, {"BREITE", "" + TachoFrei.BREITE}, {"HOEHE", "" + TachoFrei.HOEHE},
            {"MIN_GROESSE", "" + TachoFrei.MIN_GROESSE}, {"MAX_ELEMENTE", "" + TachoFrei.MAX_ELEMENTE}, {"MAX_BILD", "" + TachoFrei.MAX_BILD},
            {"SCHLEIER", "" + TachoFrei.SCHLEIER}, {"TEMPO_MIN_HOEHE", "" + TachoFrei.TEMPO_MIN_HOEHE},
            {"TEMPO_MIN_BREITE", "" + TachoFrei.TEMPO_MIN_BREITE}, {"MAX_ZEICHEN", "" + TachoEigen.MAX_ZEICHEN}};
        for (String[] z : zahlen) assertTrue(z[0], js.contains("        " + z[0] + ": " + z[1] + ","));
        // Format des Fahrerdisplays (Mittelbereich 50 % × 60 % von 1920 × 720 = 960 × 432 ≈ 1000 × 450)
        int[] f = TachoAnsicht.inhalt(1920, 720);
        assertEquals((double) f[2] / f[3], (double) TachoFrei.BREITE / TachoFrei.HOEHE, 0.01);
    }

    // ------------------------------------------------------------ Sicherheitsregeln

    @Test
    public void tempoGenauEinmalUndGrossGenug() throws Exception {
        JSONArray f = faelle();
        for (int i = 0; i < f.length(); i++) {
            JSONObject aus = f.getJSONObject(i).getJSONObject("aus");
            JSONArray el = aus.getJSONArray("elemente");
            int tempo = 0;
            for (int j = 0; j < el.length(); j++) {
                JSONObject e = el.getJSONObject(j);
                if (!"tempo".equals(e.optString("wert"))) continue;
                tempo++;
                assertTrue(f.getJSONObject(i).getString("name"), "rund".equals(e.getString("typ")) || "zahl".equals(e.getString("typ")));
                assertTrue(e.getInt("h") >= TachoFrei.TEMPO_MIN_HOEHE && e.getInt("b") >= TachoFrei.TEMPO_MIN_BREITE);
            }
            assertEquals(f.getJSONObject(i).getString("name"), 1, tempo);
        }
        // leer, nichts, kaputt: trotzdem sichtbares Tempo
        for (JSONObject roh : new JSONObject[] {v2(new JSONArray()), v2(null), v2(new JSONArray().put("x").put(5))}) {
            TachoEigen.Ergebnis e = TachoEigen.pruefen(roh);
            assertEquals("tempo", e.eigen.getJSONArray("elemente").getJSONObject(0).getString("wert"));
            assertTrue(e.warnungen.contains("tempo_pflicht"));
        }
    }

    private static JSONObject v2(JSONArray el) throws Exception {
        JSONObject o = new JSONObject().put("format", TachoEigen.FORMAT).put("version", 2);
        if (el != null) o.put("elemente", el);
        return o;
    }

    @Test
    public void kontrastJeElementUndNichtsAusserhalb() throws Exception {
        JSONArray f = faelle();
        for (int i = 0; i < f.length(); i++) {
            String name = f.getJSONObject(i).getString("name");
            JSONObject aus = f.getJSONObject(i).getJSONObject("aus");
            JSONArray ea = aus.getJSONArray("elemente");
            List<JSONObject> el = new ArrayList<>();
            for (int j = 0; j < ea.length(); j++) el.add(ea.getJSONObject(j));
            assertTrue(el.size() <= TachoFrei.MAX_ELEMENTE);
            for (int j = 0; j < el.size(); j++) {
                JSONObject e = el.get(j);
                assertTrue(name, e.getInt("x") >= 0 && e.getInt("y") >= 0 && e.getInt("b") >= TachoFrei.MIN_GROESSE && e.getInt("h") >= TachoFrei.MIN_GROESSE);
                assertTrue(name, e.getInt("x") + e.getInt("b") <= TachoFrei.BREITE && e.getInt("y") + e.getInt("h") <= TachoFrei.HOEHE);
                if (!TachoFrei.info(e.getString("typ"))) continue;
                String farbe = e.getString("balken".equals(e.getString("typ")) ? "akzent" : "farbe");
                double k = TachoFrei.minKontrast(farbe, TachoFrei.dahinter(el, j, aus.getJSONObject("hintergrund"), aus.has("bild")));
                assertTrue(name + " Element " + j + ": " + k, k >= TachoEigen.MIN_KONTRAST);
            }
        }
        assertEquals("#808080", TachoFrei.mische("#000000", "#FFFFFF", 0.5));
        assertEquals("#0E1620", TachoFrei.mische("#0E1620", "#FFFFFF", -1));
    }

    @Test
    public void bildNurKleinOhnePfadUndMitSchleier() throws Exception {
        String png = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
        assertTrue(TachoFrei.bildOk(png));
        for (String falsch : new String[] {"file:///sdcard/a.png", "content://media/1", "http://x/a.png", "/storage/emulated/0/a.jpg",
                "data:image/svg+xml;base64,PHN2Zz4=", "data:image/png;base64,/9j/4AAQ", "data:image/png,iVBORw0KGgo", "data:image/png;base64,***"}) {
            assertFalse(falsch, TachoFrei.bildOk(falsch));
        }
        StringBuilder gross = new StringBuilder(png);
        while (gross.length() <= TachoFrei.MAX_BILD) gross.insert(png.indexOf(',') + 1, "AAAA");
        assertFalse("zu gross", TachoFrei.bildOk(gross.toString()));
        assertEquals(0.7, TachoFrei.SCHLEIER, 0);
        String js = lies(JS);
        assertTrue("Schleier immer über dem Bild", js.contains("c.globalAlpha = E.SCHLEIER;\n                hgFuellen(c, 0, 0, E.BREITE, E.HOEHE, e.hintergrund);"));
        String ed = lies(EDITOR);
        assertTrue("Editor verkleinert vor dem Speichern", ed.contains("if (s.length <= R.MAX_BILD) daten = s;"));
        assertTrue(ed.contains("bildDatei.accept = 'image/png,image/jpeg';"));
    }

    @Test
    public void nichtsBlinktUndUnbekanntesFaelltWeg() throws Exception {
        JSONObject a = faelle().getJSONObject(3);
        assertEquals("unbekanntes_faellt_weg", a.getString("name"));
        String text = TachoEigen.pruefen(a.getJSONObject("ein")).eigen.toString();
        for (String v : new String[] {"sdcard", "content:", "geheim", "47.8", "token", "pfad", "lat", "blinken", "takt", "url", "http", "video", "passwort", "muster"})
            assertFalse(v, text.contains(v));
        // Der Renderer der Version 2 kennt keine Zeit ausser der Uhrzeit-Anzeige: kein Blinken, kein Pulsieren.
        String js = lies(JS);
        String frei = js.substring(js.indexOf("// ---- Zeichnen (Version 2)"), js.indexOf("function freiThema("))
                .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");   // nur Code, ohne Kommentare
        for (String v : new String[] {"Date.now", "setInterval", "setTimeout", "blink", "puls", "getMilliseconds", "getSeconds"})
            assertFalse(v, frei.contains(v));
    }

    // ------------------------------------------------------------ Datei, Version 1, Transport

    @Test
    public void version1BleibtLadbarUndGrenzen() throws Exception {
        TachoEigen.Ergebnis v1 = TachoEigen.pruefenText(TachoEigen.standard().toString());
        assertNotNull(v1);
        assertEquals(1, v1.eigen.getInt("version"));
        assertTrue(v1.eigen.has("plaetze"));
        JSONObject leer = faelle().getJSONObject(0).getJSONObject("aus");
        TachoEigen.Ergebnis v2 = TachoEigen.pruefenText(leer.toString());
        assertNotNull(v2);
        assertTrue(gleich(v2.eigen, leer));
        assertNull(TachoEigen.pruefenText(new JSONObject(leer.toString()).put("version", 3).toString()));
        assertNull("Version als Text", TachoEigen.pruefenText(new JSONObject(leer.toString()).put("version", "2").toString()));
        assertEquals(40000, TachoEigen.MAX_ZEICHEN);
        assertTrue("Bild + 40 Elemente passen", TachoFrei.MAX_BILD + TachoFrei.MAX_ELEMENTE * 300 < TachoEigen.MAX_ZEICHEN);
        // Seite: nur echte Dateien, Version 1 oder 2; Version 1 wird beim Laden in Elemente zerlegt
        String ed = lies(EDITOR);
        assertTrue(ed.contains("(o.version !== R.VERSION && o.version !== R.VERSION_FREI)) return null;"));
        assertTrue(ed.contains("var z = T.zuElementen(g.eigen, 'eigen');"));
    }

    @Test
    public void tachoHoltDasDesignNurBeiAenderung() throws Exception {
        String a = lies("app/src/main/java/at/greenchili/surfmate/tacho/TachoAnsicht.java");
        assertTrue(a.contains("r.put(\"eigenStand\", stand);"));
        assertTrue(a.contains("if (cmd == null || !stand.equals(cmd.optString(\"eigenStand\", \"\"))) r.put(\"eigen\", e);"));
        String act = lies("app/src/main/java/at/greenchili/surfmate/tacho/TachoAnsichtActivity.kt");
        assertTrue(act.contains("if (eigenStand.isNotEmpty()) c.put(\"eigenStand\", eigenStand)"));
        assertTrue(act.contains("if (a.has(\"eigen\")) eigenStand = a.optString(\"eigenStand\", \"\")"));
        String e = lies("app/src/main/java/at/greenchili/surfmate/tacho/TachoEigen.java");
        assertTrue("Pruefung nur bei neuem Text", e.contains("if (!s.equals(letzterText)) {"));
        JSONObject d = faelle().getJSONObject(1).getJSONObject("aus");
        assertEquals(TachoEigen.stand(d), TachoEigen.stand(new JSONObject(d.toString())));
        assertFalse(TachoEigen.stand(d).equals(TachoEigen.stand(TachoEigen.standard())));
        assertTrue(lies(JS).contains("if (dsg === EIGEN && d.eigen && d.eigen.version === E.VERSION_FREI) { zeichneFrei(c, w, h, d.eigen, d, jetzt); return; }"));
    }

    // ------------------------------------------------------------ Editor

    @Test
    public void editorFreiMitFingerRasterHilfslinienUndRueckgaengig() throws Exception {
        String ed = lies(EDITOR);
        assertTrue("Ziehen mit dem Finger", ed.contains("deck.addEventListener('pointerdown'") && ed.contains("deck.addEventListener('pointermove'"));
        assertTrue("grosser Griff", ed.contains("width:56px;height:56px;"));
        assertTrue("Fläche ohne Scrollen beim Ziehen", ed.contains("touch-action:none;"));
        assertTrue("Raster abschaltbar", ed.contains("rasterCb.onchange = function () { rasterAn = rasterCb.checked; };"));
        assertTrue("Hilfslinien", ed.contains("lin.style.display = 'block';"));
        assertTrue("Rückgängig/Wiederholen", ed.contains("function schritt(von, nach) {") && ed.contains("schritt(vorListe, rueckListe)"));
        assertTrue("Ebene vorne/hinten", ed.contains("function () { ebene(1); }") && ed.contains("function () { ebene(-1); }"));
        assertTrue("Fahrsperre auch für die Fläche", ed.contains("deck.style.pointerEvents = zu ? 'none' : '';")
                && ed.contains("if (!stand.imStand) { sperren(); return; }"));
        assertTrue("Tempo bleibt", ed.contains("if (istTempo(e.elemente[wahl])) {"));
        assertTrue("Start leer", ed.contains("e = T.leerFrei();"));
        // Alle verlangten Elemente sind hinzufügbar
        for (String n : new String[] {"n_rund", "n_zahl", "n_balken_w", "n_balken_s", "n_gang", "n_navi", "n_uhr", "n_akku", "n_reichweite",
                "n_kw", "n_temperatur", "n_kmstand", "n_ring", "n_linie", "n_flaeche", "n_verlauf"}) {
            assertTrue(n, ed.contains("['" + n + "', "));
            for (String l : SPRACHEN)
                assertTrue(l + ": " + n, new JSONObject(lies("app/src/main/assets/web/i18n/" + l + ".json"))
                        .getJSONObject("tacho_gestalten").optString(n, "").length() > 0);
        }
        for (String v : new String[] {"?.", "??", "=>", "inset:", "gap:", "`", "let ", "const "}) {
            assertFalse("ES5 Editor: " + v, ed.contains(v));
            assertFalse("ES5 Renderer: " + v, lies(JS).contains(v));
        }
    }

    @Test
    public void ehrlichInOberflaecheUndDoku() throws Exception {
        for (String l : SPRACHEN) {
            String s = new JSONObject(lies("app/src/main/assets/web/i18n/" + l + ".json")).getJSONObject("tacho_gestalten").getString("nur_projektion");
            assertTrue(l, s.contains("Dolphin Surf"));
        }
        assertTrue(lies(EDITOR).contains("t('tacho_gestalten.nur_projektion',"));
        String doku = lies("docs/TACHO_GESTALTEN.md");
        assertTrue(doku.contains("Freier Editor (0.1.44, E1)"));
        assertTrue(doku.contains("Beim Surf (Single-OS) nur als Vorschau"));
        String designs = lies(JS);
        assertTrue("Galerie-Design mit eigener Zeichnung: ehrlicher Hinweis", designs.contains("var eigeneZeichnung = !!EXTRA[dsg] || dsg === 'horizont' || dsg === 'route';"));
    }
}
