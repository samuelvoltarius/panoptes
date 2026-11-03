package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * „Günstigste in der Nähe“ (03.11.2026): effektiver Preis = Ladung + Hin- und Rückweg, nur mit Preis, nur bis
 * 10 km, Belegung daneben, Rechnung im Gerät. Testorte künstlich (Ursprung 10°/20°).
 */
public class NaviGuenstigsteTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @After
    public void karteZurueck() {
        NaviApiHandler.meineKartenQuelle = () -> {
            JSONObject s = at.greenchili.surfmate.laden.LadenVorlieben.planerStueck(
                    at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig());
            return s == null ? null : s.optJSONArray("karten");
        };
    }

    @Test
    public void rechnungNahTeuerGegenWeitBillig() throws Exception {
        JSONObject r = rechne(""
                + "const st = [\n"
                + "  { name: 'Nah teuer', lat: 10 + km(0.3), lon: 20, preis_adhoc: { preis_kwh: 0.79 }, frei: 0, ladepunkte: 2, status_bekannt: true },\n"
                + "  { name: 'Weit billig', lat: 10 + km(4), lon: 20, preis_adhoc: { preis_kwh: 0.39 }, frei: 2, ladepunkte: 4, status_bekannt: true },\n"
                + "  { name: 'Ohne Preis', lat: 10 + km(0.1), lon: 20 },\n"
                + "  { name: 'Minutentarif', lat: 10 + km(0.2), lon: 20, preis_adhoc: { preis_kwh: null, preis_min: 0.2 } },\n"
                + "  { name: 'Meldefehler', lat: 10 + km(0.4), lon: 20, preis_adhoc: { preis_kwh: 0.01 }, preis_auffaellig: true },\n"
                + "  { name: 'Zu weit', lat: 10 + km(10.5), lon: 20, preis_adhoc: { preis_kwh: 0.2 } }];\n"
                + "out.a = PB.guenstigste(st, 10, 20, { kwh: 30, verbrauch: 16 }).map(x => ({ n: x.s.name, g: x.e.gesamt, e: x.ersparnis, nae: x.istNaechste, frei: x.belegung && x.belegung.frei }));\n"
                // nah etwas teurer, weit etwas billiger: der Weg frisst die Ersparnis
                + "const st2 = [\n"
                + "  { name: 'Nah', lat: 10 + km(0.5), lon: 20, preis_adhoc: { preis_kwh: 0.50 } },\n"
                + "  { name: 'Weit', lat: 10 + km(9), lon: 20, preis_adhoc: { preis_kwh: 0.49 } }];\n"
                + "out.b = PB.guenstigste(st2, 10, 20, { kwh: 30, verbrauch: 16 }).map(x => ({ n: x.s.name, e: x.ersparnis }));\n"
                + "out.leer = PB.guenstigste([st[2], st[3], st[4], st[5]], 10, 20, { kwh: 30 }).length;\n"
                // eigene Karte vor ad hoc, Planer-Betrag (mit Grundgebühr) vor Preis × kWh
                + "out.karte = PB.preisKwh({ preis_meine: { preis_kwh: 0.35, kosten: 11.5, karte: 'X' }, preis_adhoc: { preis_kwh: 0.59 } });\n"
                + "out.e = PB.effektiv({ preis_adhoc: { preis_kwh: 0.5, kosten: 16 } }, 2, 30, 20);\n"
                + "out.rueckfall = PB.guenstigste([st[1]], 10, 20, { kwh: 30, verbrauch: 999 })[0].e.weg;\n");
        JSONArray a = r.getJSONArray("a");
        assertEquals("nur Säulen mit glaubwürdigem kWh-Preis bis 10 km", 2, a.length());
        assertEquals("Weit billig", a.getJSONObject(0).getString("n"));
        assertEquals(30 * 0.39 + 2 * 4 * 1.3 * 0.16 * 0.39, a.getJSONObject(0).getDouble("g"), 0.02);
        assertEquals("Ersparnis gegenüber der nächsten Säule mit Preis",
                (30 * 0.79 + 2 * 0.3 * 1.3 * 0.16 * 0.79) - (30 * 0.39 + 2 * 4 * 1.3 * 0.16 * 0.39),
                a.getJSONObject(0).getDouble("e"), 0.02);
        assertFalse(a.getJSONObject(0).getBoolean("nae"));
        assertEquals("Nah teuer", a.getJSONObject(1).getString("n"));
        assertTrue(a.getJSONObject(1).getBoolean("nae"));
        assertEquals("Belegung kommt mit", 0, a.getJSONObject(1).getInt("frei"));

        JSONArray b = r.getJSONArray("b");
        assertEquals("1 Cent billiger lohnt 17 km Umweg nicht", "Nah", b.getJSONObject(0).getString("n"));
        assertTrue(b.getJSONObject(1).getDouble("e") < 0);

        assertEquals("ohne Preis keine Empfehlung", 0, r.getInt("leer"));
        assertEquals("karte", r.getJSONObject("karte").getString("quelle"));
        assertEquals(0.35, r.getJSONObject("karte").getDouble("preis"), 1e-9);
        assertEquals(16 + 2 * 2 * 1.3 * 0.20 * 0.5, r.getJSONObject("e").getDouble("gesamt"), 1e-9);
        assertEquals("unplausibler Verbrauch → Rückfall 16 kWh/100 km",
                2 * 4 * 1.3 * 0.16 * 0.39, r.getDouble("rueckfall"), 1e-6);
    }

    @Test
    public void anfrageNimmtLademengeUndKartenMerkerMitBleibtGerundet() throws Exception {
        JSONObject r = rechne("out.q = PB.anfrage(10.12345, 20.98765, 10, { kwh: 30, meineKarten: true });\n"
                + "out.ohne = PB.anfrage(10.12345, 20.98765, 10);\n");
        JSONObject q = r.getJSONObject("q");
        assertEquals(10.12, q.getDouble("lat"), 1e-12);
        assertEquals(30, q.getInt("kwh"));
        assertTrue(q.getBoolean("meine_karten"));
        assertFalse(r.getJSONObject("ohne").has("meine_karten"));
        assertEquals(20, r.getJSONObject("ohne").getInt("kwh"));
    }

    @Test
    public void kernSetztKartenNurAufWunschUndSchicktDenMerkerNichtHinaus() throws Exception {
        NaviApiHandler.meineKartenQuelle = () -> new JSONArray().put(3).put(5);
        JSONObject a = new JSONObject(NaviApiHandler.umkreisBody("{\"lat\":10.12,\"lon\":20.99,\"radius\":11,\"meine_karten\":true}"));
        assertEquals("[3,5]", a.getJSONArray("karten").toString());
        assertFalse(a.has("meine_karten"));
        JSONObject b = new JSONObject(NaviApiHandler.umkreisBody("{\"lat\":10.12,\"lon\":20.99,\"radius\":11}"));
        assertFalse("Karte/StoppBelegung ohne Merker: keine Karten", b.has("karten"));
        JSONObject c = new JSONObject(NaviApiHandler.umkreisBody("{\"lat\":10.12,\"lon\":20.99,\"meine_karten\":false}"));
        assertFalse(c.has("karten"));
        assertFalse(c.has("meine_karten"));
        NaviApiHandler.meineKartenQuelle = () -> null;
        JSONObject d = new JSONObject(NaviApiHandler.umkreisBody("{\"lat\":10.12,\"lon\":20.99,\"meine_karten\":true}"));
        assertFalse("ohne eigene Karten nichts", d.has("karten"));
    }

    @Test
    public void verdrahtungEinstellungStatusUndSeite() throws Exception {
        boolean da = false;
        for (at.greenchili.surfmate.einstellungen.Feld f : at.greenchili.surfmate.einstellungen.LadenKatalog.felder()) {
            if ("laden.standardKwh".equals(f.key)) {
                da = true;
                assertEquals(30, ((Number) f.standard).intValue());
            }
        }
        assertTrue(da);
        String http = lies("src/main/java/at/greenchili/surfmate/server/HttpServer.java");
        assertTrue(http.contains("status.put(\"verbrauchKwh100\""));
        String navi = lies("src/main/assets/web/local/navi.html");
        assertTrue(navi.contains("{ kwh: kwh, meineKarten: true })"));
        assertTrue(navi.contains("var g = PB.guenstigste(n.r.stationen, n.c.lat, n.c.lng,"));
        assertTrue(navi.contains("data-nah=\"guenstig\""));
        assertTrue(navi.contains("t('navi.nah_keine'"));
    }

    // ------------------------------------------------------------------ 0.1.36: eigener Preis „Laut Säule“ zuerst

    @After
    public void ladungenZurueck() {
        NaviApiHandler.eigeneLadungenQuelle = () -> null;
    }

    private static JSONObject ladung(long id, String betreiber, boolean dc, double kwh, double betrag, long zeit)
            throws Exception {
        return new JSONObject().put("id", id).put("betreiber", betreiber).put("isDc", dc)
                .put("saeuleKwh", kwh).put("saeuleBetrag", betrag).put("startTime", zeit);
    }

    @Test
    public void kernTraegtEigenenPreisJeBetreiberUndStromartEin() throws Exception {
        JSONArray sitz = new JSONArray()
                .put(ladung(1, "Kraftwerk Nord", true, 20, 9.0, 1000))     // DC alt 0,45
                .put(ladung(2, "Kraftwerk  nord ", true, 20, 7.0, 2000))   // DC jung 0,35 (Name normalisiert)
                .put(ladung(3, "Kraftwerk Nord", false, 10, 2.5, 3000))    // AC 0,25
                .put(new JSONObject().put("id", 4).put("betreiber", "Nutzerpreis").put("isDc", false)
                        .put("preisQuelle", "nutzer").put("electricityRate", 0.31).put("startTime", 500));
        NaviApiHandler.eigeneLadungenQuelle = () -> sitz;
        JSONObject antwort = new JSONObject().put("stationen", new JSONArray()
                .put(new JSONObject().put("name", "A").put("betreiber", "Kraftwerk Nord").put("art", "HPC")
                        .put("preis_adhoc", new JSONObject().put("preis_kwh", 0.69)))
                .put(new JSONObject().put("name", "B").put("betreiber", "kraftwerk nord").put("art", "AC"))
                .put(new JSONObject().put("name", "C").put("betreiber", "Fremd").put("art", "DC")
                        .put("preis_eigen", new JSONObject().put("preis_kwh", 0.01)))   // vom Planer: nie übernehmen
                .put(new JSONObject().put("name", "D").put("betreiber", "Nutzerpreis").put("art", "AC"))
                .put(new JSONObject().put("name", "E").put("betreiber", "Kraftwerk Nord").put("art", "?")));
        JSONObject o = new JSONObject(NaviApiHandler.mitEigenenPreisen(antwort.toString()));
        JSONArray st = o.getJSONArray("stationen");
        assertEquals("DC/HPC: jüngster eigener DC-Preis", 0.35, st.getJSONObject(0).getJSONObject("preis_eigen").getDouble("preis_kwh"), 1e-9);
        assertEquals("saeule", st.getJSONObject(0).getJSONObject("preis_eigen").getString("herkunft"));
        assertEquals("AC getrennt von DC", 0.25, st.getJSONObject(1).getJSONObject("preis_eigen").getDouble("preis_kwh"), 1e-9);
        assertFalse("fremder Betreiber ohne eigenen Preis, Planer-Feld weg", st.getJSONObject(2).has("preis_eigen"));
        assertEquals("selbst gesetzter Preis zählt", 0.31, st.getJSONObject(3).getJSONObject("preis_eigen").getDouble("preis_kwh"), 1e-9);
        assertFalse("Stromart unbekannt → nichts", st.getJSONObject(4).has("preis_eigen"));
        assertFalse("keine Ladungs-ID in der Antwort", o.toString().contains("ladungId"));

        NaviApiHandler.eigeneLadungenQuelle = () -> null;
        assertEquals("ohne Lade-DB unverändert", antwort.toString(), NaviApiHandler.mitEigenenPreisen(antwort.toString()));
        NaviApiHandler.eigeneLadungenQuelle = () -> { throw new IllegalStateException("db"); };
        assertEquals("Fehler → unverändert", antwort.toString(), NaviApiHandler.mitEigenenPreisen(antwort.toString()));
        assertTrue(NaviApiHandler.guenstigsteWunsch("{\"meine_karten\":true}"));
        assertFalse("Karte/StoppBelegung ohne Merker", NaviApiHandler.guenstigsteWunsch("{\"lat\":1}"));
        assertFalse(NaviApiHandler.guenstigsteWunsch("kaputt"));
    }

    @Test
    public void eigenerPreisIstErsterPreisInDerRechnung() throws Exception {
        JSONObject r = rechne(""
                + "out.p = PB.preisKwh({ preis_eigen: { preis_kwh: 0.33 }, preis_meine: { preis_kwh: 0.29, kosten: 9 }, preis_adhoc: { preis_kwh: 0.59 } });\n"
                + "out.auff = PB.preisKwh({ preis_eigen: { preis_kwh: 0.33 }, preis_auffaellig: true, preis_adhoc: { preis_kwh: 0.01 } });\n"
                + "out.unsinn = PB.preisKwh({ preis_eigen: { preis_kwh: 0 }, preis_adhoc: { preis_kwh: 0.59 } });\n"
                + "const st = [\n"
                + "  { name: 'Nah adhoc', lat: 10 + km(0.5), lon: 20, preis_adhoc: { preis_kwh: 0.39 } },\n"
                + "  { name: 'Eigen', lat: 10 + km(1), lon: 20, preis_eigen: { preis_kwh: 0.25 }, preis_adhoc: { preis_kwh: 0.79 } },\n"
                + "  { name: 'Nur eigen', lat: 10 + km(2), lon: 20, preis_eigen: { preis_kwh: 0.45 } }];\n"
                + "out.g = PB.guenstigste(st, 10, 20, { kwh: 30, verbrauch: 16 }).map(x => ({ n: x.s.name, q: x.e.quelle, p: x.e.preis }));\n");
        JSONObject p = r.getJSONObject("p");
        assertEquals("eigen", p.getString("quelle"));
        assertEquals(0.33, p.getDouble("preis"), 1e-9);
        assertTrue("eigen rechnet Preis × kWh, nicht den Planer-Betrag", p.isNull("kosten") || !p.has("kosten"));
        assertEquals("Planer-Meldefehler betrifft den eigenen Preis nicht", "eigen", r.getJSONObject("auff").getString("quelle"));
        assertEquals("ungültiger eigener Preis → Planer", "adhoc", r.getJSONObject("unsinn").getString("quelle"));
        JSONArray g = r.getJSONArray("g");
        assertEquals(3, g.length());
        assertEquals("Eigen", g.getJSONObject(0).getString("n"));
        assertEquals("eigen", g.getJSONObject(0).getString("q"));
        assertEquals("eigener Preis schlägt den ad-hoc-Preis derselben Säule", 0.25, g.getJSONObject(0).getDouble("p"), 1e-9);
        assertEquals("Säule nur mit eigenem Preis wird empfohlen", "Nur eigen", g.getJSONObject(2).getString("n"));
        String navi = lies("src/main/assets/web/local/navi.html");
        assertTrue(navi.contains("x.e.quelle === 'eigen'"));
        assertTrue(navi.contains("t('navi.nah_eigen'"));
        String handler = lies("src/main/java/at/greenchili/surfmate/server/NaviApiHandler.java");
        assertTrue("nur auf Wunsch der Navi-Seite, nie für Kopplungen",
                handler.contains("guenstigsteWunsch(body) && !AuthMiddleware.isExternRequest()"));
    }

    // ------------------------------------------------------------------ Hilfen

    private JSONObject rechne(String code) throws Exception {
        File modul = tmp.newFile("belegung.js");
        Files.write(modul.toPath(), lies("src/main/assets/web/shared/belegung.js").getBytes(StandardCharsets.UTF_8));
        String js = "const PB = require(" + JSONObject.quote(modul.getAbsolutePath()) + ");\n"
                + "const km = (k) => k / 111.195;\nconst out = {};\n" + code
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

    private static String lies(String pfad) throws Exception {
        Path p = Paths.get(pfad);
        if (!Files.exists(p)) p = Paths.get("app").resolve(pfad);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
