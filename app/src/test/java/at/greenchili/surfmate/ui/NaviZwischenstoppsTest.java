package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/**
 * 0.1.38.10 (Paket N1): Zwischenstopps in navi.html. Liste Start → Z1…Z5 → Ziel mit ↑/↓/× (kein Ziehen), Aufenthalt und
 * „hier laden“, `via` wie der Planer es liest (planer.py via_lesen), Etappen aus `zwischenziele` + `stopps` in
 * Fahrreihenfolge (wie plan_vertrag._etappen). Während der Fahrt am Display keine Suche und kein Kartentipp.
 * Testorte künstlich (Ursprung 10°/20°).
 */
public class NaviZwischenstoppsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static File basis() {
        return new File("src/main/assets").isDirectory() ? new File(".") : new File("app");
    }

    private static String lies(String pfad) throws Exception {
        return new String(Files.readAllBytes(new File(basis(), pfad).toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    public void listeHoechstensFuenfMitHochRunterWeg() throws Exception {
        JSONObject r = rechne(
                "const L = [];\n"
                + "for (let i = 0; i < 6; i++) out['h' + i] = PE.hinzu(L, { name: 'O' + i, lat: 10 + i / 100, lng: 20 });\n"
                + "out.n = L.length;\n"
                + "out.kaputt = PE.hinzu([], { name: 'x', lat: 'a', lng: 20 });\n"
                + "out.hoch0 = PE.schieben(L, 0, -1);\n"
                + "out.runter4 = PE.schieben(L, 4, 1);\n"
                + "PE.schieben(L, 2, -1); out.nachHoch = L.map(s => s.name).join(',');\n"
                + "PE.weg(L, 0); out.nachWeg = L.map(s => s.name).join(',');\n"
                + "out.auf = [0, 15, 30, 60, 120].map(PE.naechsterAufenthalt).join(',');\n"
                + "out.max = PE.MAX;\n");
        assertTrue(r.getBoolean("h0") && r.getBoolean("h4"));
        assertFalse("sechster Stopp abgelehnt", r.getBoolean("h5"));
        assertEquals(5, r.getInt("n"));
        assertEquals("wie planer.py MAX_VIA", 5, r.getInt("max"));
        assertFalse(r.getBoolean("kaputt"));
        assertFalse(r.getBoolean("hoch0"));
        assertFalse(r.getBoolean("runter4"));
        assertEquals("O0,O2,O1,O3,O4", r.getString("nachHoch"));
        assertEquals("O2,O1,O3,O4", r.getString("nachWeg"));
        assertEquals("15,30,60,120,0", r.getString("auf"));
    }

    @Test
    public void viaWieDerPlanerEsLiest() throws Exception {
        JSONObject r = rechne(
                "const L = PE.ausListe([{ name: 'A', lat: 10.1234567, lon: 20.7654321, aufenthalt_min: 30, laden: true },"
                + " { name: 'B', lat: 10.2, lng: 20.2 }, { name: 'kaputt' }]);\n"
                + "out.via = PE.viaBody(L);\n");
        JSONArray via = r.getJSONArray("via");
        assertEquals("ungültiger Eintrag fällt weg", 2, via.length());
        JSONObject a = via.getJSONObject(0);
        assertEquals(10.12346, a.getDouble("lat"), 1e-9);
        assertEquals("lon, nicht lng (planer.py via_lesen)", 20.76543, a.getDouble("lon"), 1e-9);
        assertEquals("A", a.getString("name"));
        assertEquals(30, a.getInt("aufenthalt_min"));
        assertTrue("laden: true = AC (via_laden_lesen)", a.getBoolean("laden"));
        assertFalse("ohne laden kein Feld", via.getJSONObject(1).has("laden"));
        assertEquals(0, via.getJSONObject(1).getInt("aufenthalt_min"));
        assertEquals("nur bekannte Schlüssel", 4, via.getJSONObject(1).length());
    }

    @Test
    public void etappenInFahrreihenfolgeMitAkku() throws Exception {
        JSONObject r = rechne(
                "const d = { route: { km: 300 }, soc_ankunft: 22,\n"
                + "  zwischenziele: [ { nr: 1, name: 'Z-a', lat: 10.5, lon: 20, km_auf_route: 80, soc_ankunft: 55, aufenthalt_min: 30 },\n"
                + "                   { nr: 2, name: 'Z-b', lat: 11, lon: 20, km_auf_route: 200, soc_ankunft: 40, soc_abfahrt: 70, aufenthalt_min: 60, laden: { art: 'AC' } } ],\n"
                + "  stopps: [ { saeule: { name: 'L-a', lat: 10.8, lon: 20, km_auf_route: 150 }, soc_vorher: 18, soc_nachher: 60, ladedauer_min: 20 },\n"
                + "            { saeule: { name: 'L-gleich', lat: 11, lon: 20, km_auf_route: 200 }, soc_vorher: 40, soc_nachher: 70 } ] };\n"
                + "const e = PE.etappen(d);\n"
                + "out.arten = e.map(p => p.art + ':' + p.name).join(',');\n"
                + "out.km = e.map(p => p.etappe_km).join(',');\n"
                + "out.akku = e.map(p => p.akku).join(',');\n"
                + "out.weiterZb = e[2].weiter;\n"
                + "out.ohne = PE.etappen({ route: { km: 10 }, stopps: [], zwischenziele: [] }).length;\n"
                + "out.nichts = PE.etappen(null).length;\n");
        assertEquals("gleiche Stelle: erst Zwischenziel, dann laden (plan_vertrag._etappen)",
                "z:Z-a,l:L-a,z:Z-b,l:L-gleich,ziel:", r.getString("arten"));
        assertEquals("80,70,50,0,100", r.getString("km"));
        assertEquals("55,18,40,40,22", r.getString("akku"));
        assertEquals(70, r.getInt("weiterZb"));
        assertEquals("ohne Zwischenziele bleibt die bisherige Ladestopp-Liste", 0, r.getInt("ohne"));
        assertEquals(0, r.getInt("nichts"));
    }

    @Test
    public void naviSeiteBedientDieListeOhneZiehen() throws Exception {
        String navi = lies("src/main/assets/web/local/navi.html");
        assertTrue(navi.contains("<script src=\"../shared/navi-etappen.js?v=1\"></script>"));
        assertTrue(navi.contains("<div class=\"n-via\" id=\"nVia\"></div>"));
        for (String v : new String[] {"neu", "abbruch", "halt", "laden", "hoch", "runter", "weg"}) {
            assertTrue("Knopf " + v, navi.contains("data-v=\"" + v + "\""));
        }
        assertFalse("kein Drag-and-Drop", navi.contains("draggable") || navi.contains("dragstart") || navi.contains("ondrop"));
        // Fahrt: Suche und Kartentipp gesperrt, Favoriten und Säulen erlaubt
        assertTrue(navi.contains("function fahrt() { return document.documentElement.getAttribute('data-fahrt') === '1'; }"));
        assertTrue(navi.contains("if ((quelle === 'suche' || quelle === 'karte') && fahrt()) {"));
        assertTrue(navi.contains("self.pick({ name: o.kurz || o.name, full: o.name, lat: o.lat, lng: o.lon }, 'suche');"));
        assertTrue(navi.contains("lat: ll.lat, lng: ll.lng }, 'karte');"));
        assertTrue(navi.contains("lat: s.lat, lng: s.lon }, 'saeule');"));
        assertTrue(navi.contains("N.pick({ name: o.name, full: o.name, lat: o.lat, lng: o.lon }, 'fav');"));
        // nur die neueste Planung zählt, wenn die Liste während des Rechnens geändert wird
        assertTrue(navi.contains("var gen = ++this.planGen;"));
        assertTrue(navi.contains("if (self.dest !== dest || gen !== self.planGen) return;"));
        // ins Navi geht weiter nur ein Ziel, nach Tipp und Rückfrage (send → confirm)
        assertTrue(navi.contains("s.onclick = function () { self.send(e0.name || self.dest.name, e0.lat, e0.lon, s); };"));
        int a = navi.indexOf("send: function (name, lat, lng, btn) {");
        assertTrue(a > 0 && navi.indexOf("frage(", a) < navi.indexOf("post('/api/telenav/navigate'", a));
        assertFalse("die Liste wird nicht gespeichert", navi.contains("pn.navi.via"));
    }

    @Test
    public void displayTexteOhneAnsAutoSprache() throws Exception {
        String navi = lies("src/main/assets/web/local/navi.html");
        assertTrue(navi.contains("imAuto() ? t('navi.pr_explain_display',"));
        assertTrue(navi.contains("imAuto() ? t('navi.fg_text2_display',"));
        assertTrue(navi.contains("imAuto() ? t('navi.send_first_display', 'Erste Etappe starten')"));
    }

    @Test
    public void texteInFuenfSprachen() throws Exception {
        String[] keys = {"via_titel", "via_neu", "via_abbruch", "via_wie", "via_wie_fahrt", "via_fahrt", "via_max", "via_hinzu",
                "via_start", "via_ziel", "via_kein_ziel", "via_halt", "via_halt0", "via_halt_titel", "via_laden", "via_hoch",
                "via_runter", "via_weg", "via_hilfe", "et_titel", "et_km", "et_an", "et_halt", "et_weiter", "et_ein_ziel",
                "send_first", "send_first_display", "pr_explain_display", "fg_text2_display"};
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject n = new JSONObject(lies("src/main/assets/web/i18n/" + l + ".json")).getJSONObject("navi");
            for (String k : keys) assertFalse(l + " navi." + k, n.optString(k, "").isEmpty());
            assertTrue(l, n.getString("via_max").contains("{n}"));
            assertTrue(l, n.getString("via_hinzu").contains("{name}") && n.getString("via_hinzu").contains("{n}"));
            assertTrue(l, n.getString("via_halt").contains("{m}") && n.getString("et_halt").contains("{m}"));
            assertTrue(l, n.getString("et_an").contains("{p}") && n.getString("et_weiter").contains("{p}"));
            assertTrue(l, n.getString("et_km").contains("{km}") && n.getString("via_ziel").contains("{name}"));
            assertFalse(l + " am Display nicht dieselbe Erklärung wie am Handy",
                    n.getString("pr_explain_display").equals(n.getString("pr_explain")));
            assertTrue(l + " Display-Text nennt den Knopf „Starten“",
                    n.getString("fg_text2_display").contains(n.getString("send_display")));
        }
    }

    // ------------------------------------------------------------------ Hilfen

    private JSONObject rechne(String code) throws Exception {
        File modul = tmp.newFile("navi-etappen.js");
        Files.write(modul.toPath(), lies("src/main/assets/web/shared/navi-etappen.js").getBytes(StandardCharsets.UTF_8));
        String js = "const PE = require(" + JSONObject.quote(modul.getAbsolutePath()) + ");\nconst out = {};\n" + code
                + "console.log(JSON.stringify(out));\n";
        File h = tmp.newFile("h.js");
        Files.write(h.toPath(), js.getBytes(StandardCharsets.UTF_8));
        String res = node(h);
        assumeTrue("node nicht vorhanden", res != null);
        return new JSONObject(res.trim());
    }

    private static String node(File script) {
        try {
            Process p = new ProcessBuilder("node", script.getAbsolutePath()).redirectErrorStream(true).start();
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            String s = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.exitValue() != 0) throw new AssertionError("node: " + s);
            return s;
        } catch (java.io.IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
