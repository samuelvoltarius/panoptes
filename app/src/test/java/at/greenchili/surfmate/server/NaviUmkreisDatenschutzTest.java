package at.greenchili.surfmate.server;

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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Datenschutz 03.11.2026: Die Umkreissuche schickt die Autoposition nur auf 2 Nachkommastellen (~1 km) an den
 * Planer, Radius +1 km, die Feinsortierung macht das Gerät. Die nächsten Säulen müssen trotzdem stimmen.
 *
 * <p>Testorte sind künstlich (Ursprung 10°/20°), keine echten Orte.
 */
public class NaviUmkreisDatenschutzTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // ------------------------------------------------------------------ Weiterleitung im Kern

    @Test
    public void genaueAnfrageWirdGerundetUndRadiusVergroessert() throws Exception {
        JSONObject b = new JSONObject(NaviApiHandler.umkreisBody(
                "{\"lat\":10.12345,\"lon\":20.98765,\"radius\":10,\"kwh\":20}"));
        assertEquals(10.12, b.getDouble("lat"), 1e-12);
        assertEquals(20.99, b.getDouble("lon"), 1e-12);
        assertEquals(11.0, b.getDouble("radius"), 1e-12);
        assertEquals(20, b.getInt("kwh"));
        assertFalse(NaviApiHandler.umkreisBody("{\"lat\":10.12345,\"lon\":20.98765}").contains("12345"));
    }

    @Test
    public void schonGerundeteAnfragenBleibenUnveraendert() throws Exception {
        // Navi neu (0,01°, Radius 11), Karte und StoppBelegung (0,05°)
        for (String in : new String[]{"{\"lat\":10.12,\"lon\":20.99,\"radius\":11,\"limit\":200}",
                "{\"lat\":10.05,\"lon\":20.95,\"radius\":6,\"limit\":200}",
                "{\"lat\":10,\"lon\":20,\"radius\":12}"}) {
            JSONObject a = new JSONObject(in), b = new JSONObject(NaviApiHandler.umkreisBody(in));
            assertEquals(in, a.getDouble("lat"), b.getDouble("lat"), 1e-12);
            assertEquals(in, a.getDouble("lon"), b.getDouble("lon"), 1e-12);
            assertEquals(in, a.getDouble("radius"), b.getDouble("radius"), 1e-12);
        }
    }

    @Test
    public void ohneRadiusUndGrenzen() throws Exception {
        assertEquals(11.0, new JSONObject(NaviApiHandler.umkreisBody("{\"lat\":10.123,\"lon\":20.456}"))
                .getDouble("radius"), 1e-12);
        assertEquals(NaviApiHandler.UMKREIS_MAX_RADIUS_KM, new JSONObject(NaviApiHandler.umkreisBody(
                "{\"lat\":10.123,\"lon\":20.456,\"radius\":500}")).getDouble("radius"), 1e-12);
        assertEquals("kaputt", NaviApiHandler.umkreisBody("kaputt"));
        assertEquals("{\"q\":\"x\"}", NaviApiHandler.umkreisBody("{\"q\":\"x\"}"));
    }

    @Test
    public void handlerRundetNurUmkreis() throws Exception {
        String h = lies("src/main/java/at/greenchili/surfmate/server/NaviApiHandler.java");
        assertTrue(h.contains(": umkreis ? umkreisBody(body) : body;"));
    }

    // ------------------------------------------------------------------ navi.html

    @Test
    public void naviSchicktNurGerundetUndSortiertLokal() throws Exception {
        String navi = lies("src/main/assets/web/local/navi.html");
        int a = navi.indexOf("nearby: function ()");
        String teil = navi.substring(a, navi.indexOf("init: function ()", a));
        assertTrue(teil.contains("PB.holen(function (body) { return planer('/api/navi/umkreis', body); }, c.lat, c.lng, 10, 60,"));
        assertTrue(teil.contains("r.stationen.forEach(function (s) {"));
        assertFalse("keine 4 Nachkommastellen mehr", teil.contains("toFixed(4)"));
        assertTrue(navi.contains("<script src=\"../shared/belegung.js?v=2\"></script>"));
        String js = lies("src/main/assets/web/shared/belegung.js");
        assertTrue(js.contains("var RUNDUNG_KM = 1;"));
        assertTrue(js.contains("function runden2(x) { return Math.round(x * 100) / 100; }"));
    }

    @Test
    public void belegungNurMitDatenUndMitStand() throws Exception {
        String navi = lies("src/main/assets/web/local/navi.html");
        // Stoppliste: nur mit Belegung, mit Zeitstempel
        assertTrue(navi.contains("var bl = PB.belegung(s);"));
        assertTrue(navi.contains("(bl ? '<small class=\"n-occ "));
        assertTrue(navi.contains("standHtml(PB.standMs(s.frei_stand_min == null ? null : s.frei_stand_min * 60, self.planEmpfangen))"));
        // Ladestopp unterwegs
        assertTrue(navi.contains("var stand = standHtml(PB.standMs(b.standVorS, this.empfangen));"));
        assertTrue(navi.contains("if (!b || !b.gefunden || !b.statusBekannt) return '';"));
        // Pflicht-Namensnennung, nur wenn etwas angezeigt wird
        assertTrue(navi.contains("d.stopps.some(function (st) { return PB.belegung(st.saeule); })"));
    }

    // ------------------------------------------------------------------ belegung.js mit node (wenn vorhanden)

    /**
     * Simuliert den Planer: Stationen im Radius um den GERUNDETEN Punkt, nach „Preis“ (zufällig) sortiert, höchstens
     * limit. Danach muss die lokale Auswahl genau die 60 nächsten Säulen zur genauen Position liefern – auch im
     * schlimmsten Rundungsfall (x,xx5) und mit Säulen knapp innerhalb von 10 km.
     */
    @Test
    public void naechsteSaeulenStimmenTrotzRundung() throws Exception {
        String js = lies("src/main/assets/web/shared/belegung.js");
        File modul = tmp.newFile("belegung.js");
        Files.write(modul.toPath(), js.getBytes(StandardCharsets.UTF_8));
        String harness = ""
                + "const PB = require(" + JSONObject.quote(modul.getAbsolutePath()) + ");\n"
                + "let seed = 7; function rnd() { seed = (seed * 16807) % 2147483647; return (seed - 1) / 2147483646; }\n"
                + "const out = [];\n"
                + "const autos = [[10.005, 20.005], [10.12345, 20.98765], [9.99999, 19.99501], [10.0449, 20.0051]];\n"
                + "for (const [lat, lon] of autos) {\n"
                + "  const st = [];\n"
                + "  for (let i = 0; i < 150; i++) {\n"
                + "    const w = rnd() * 2 * Math.PI, d = Math.sqrt(rnd()) * 14;\n"
                + "    st.push({ id: i, name: 'S' + i, lat: lat + d * Math.sin(w) / 111.2, lon: lon + d * Math.cos(w) / (111.2 * Math.cos(lat * Math.PI / 180)),\n"
                + "              preis: rnd(), frei: i % 3, ladepunkte: 2, status_bekannt: i % 5 !== 0, live_alter_s: 90 });\n"
                + "  }\n"
                // Grenzfall: knapp innerhalb 10 km, in Richtung weg vom gerundeten Mittelpunkt
                + "  st.push({ id: 'rand', name: 'Rand', lat: lat + 9.95 / 111.2 * Math.sign(lat - Math.round(lat * 100) / 100 || 1), lon: lon, frei: 1, ladepunkte: 2 });\n"
                + "  const q = PB.anfrage(lat, lon, 10);\n"
                + "  const ok = Math.abs(q.lat * 100 - Math.round(q.lat * 100)) < 1e-9 && Math.abs(q.lon * 100 - Math.round(q.lon * 100)) < 1e-9;\n"
                + "  const planer = st.filter(s => PB.distKm(q.lat, q.lon, s.lat, s.lon) <= q.radius)\n"
                + "                   .sort((a, b) => (a.preis || 0) - (b.preis || 0)).slice(0, q.limit);\n"
                + "  const lokal = PB.naechste(planer, lat, lon, 10, 60).map(s => s.id);\n"
                + "  const wahr = st.map(s => ({ id: s.id, km: PB.distKm(lat, lon, s.lat, s.lon) })).filter(x => x.km <= 10)\n"
                + "                 .sort((a, b) => a.km - b.km).slice(0, 60).map(x => x.id);\n"
                + "  const alleImRadius = st.filter(s => PB.distKm(lat, lon, s.lat, s.lon) <= 10).length;\n"
                + "  const vollLokal = PB.naechste(planer, lat, lon, 10, 1000).map(s => s.id).sort().join();\n"
                + "  const vollWahr = st.filter(s => PB.distKm(lat, lon, s.lat, s.lon) <= 10).map(s => s.id).sort().join();\n"
                + "  out.push({ ok, radius: q.radius, limit: q.limit, gleich: JSON.stringify(lokal) === JSON.stringify(wahr),\n"
                + "             randDabei: lokal.indexOf('rand') >= 0 || wahr.indexOf('rand') < 0, n: lokal.length, imPlaner: planer.length, alleImRadius,\n"
                + "             vollGleich: vollLokal === vollWahr, randIn10: vollWahr.split(',').indexOf('rand') >= 0 });\n"
                + "}\n"
                + "const b = PB.belegung({ frei: null, ladepunkte: 4 }), c = PB.belegung({ frei: 2, ladepunkte: 4 });\n"
                + "const d = PB.belegung({ frei: 2, ladepunkte: 4, status_bekannt: false }), e = PB.belegung({ frei: 0, ladepunkte: 0 });\n"
                + "const t = (k, f, v) => f.replace(/\\{(\\w+)\\}/g, (_, x) => v[x]);\n"
                + "const jetzt = 1e12;\n"
                + "console.log(JSON.stringify({ out, ohne: b, mit: c, unbekannt: d, ohnePunkte: e,\n"
                + "  standNull: PB.standMs(null, jetzt), standLeer: PB.standMs(undefined, jetzt),\n"
                + "  stand: PB.standText(PB.standMs(90, jetzt), t, jetzt + 30000), jetztText: PB.standText(jetzt - 20000, t, jetzt),\n"
                + "  stunden: PB.standText(jetzt - 3 * 3600e3, t, jetzt), leer: PB.standText(null, t, jetzt) }));\n";
        File h = tmp.newFile("harness.js");
        Files.write(h.toPath(), harness.getBytes(StandardCharsets.UTF_8));
        String res = node(h);
        assumeTrue("node nicht vorhanden", res != null);
        JSONObject r = new JSONObject(res.trim());
        JSONArray out = r.getJSONArray("out");
        assertEquals(4, out.length());
        for (int i = 0; i < out.length(); i++) {
            JSONObject o = out.getJSONObject(i);
            assertTrue("2 Nachkommastellen " + o, o.getBoolean("ok"));
            assertEquals(11.0, o.getDouble("radius"), 1e-12);
            assertEquals(200, o.getInt("limit"));
            assertTrue("nicht abgeschnitten " + o, o.getInt("imPlaner") < 200);
            assertTrue("dieselben 60 nächsten Säulen in derselben Reihenfolge " + o, o.getBoolean("gleich"));
            assertTrue("Säule knapp in 10 km bleibt " + o, o.getBoolean("randDabei"));
            assertTrue("alle Säulen im 10-km-Kreis kommen an " + o, o.getBoolean("vollGleich"));
            assertTrue("Grenzfall wirklich in 10 km " + o, o.getBoolean("randIn10"));
            assertEquals(Math.min(60, o.getInt("alleImRadius")), o.getInt("n"));
        }
        assertTrue("ohne Daten nichts", r.isNull("ohne"));
        assertTrue(r.isNull("unbekannt"));
        assertTrue(r.isNull("ohnePunkte"));
        assertEquals(2, r.getJSONObject("mit").getInt("frei"));
        assertTrue(r.isNull("standNull"));
        assertTrue(r.isNull("standLeer"));
        assertEquals("Stand vor 2 min", r.getString("stand"));
        assertEquals("Stand gerade eben", r.getString("jetztText"));
        assertEquals("Stand vor 3 h", r.getString("stunden"));
        assertEquals("", r.getString("leer"));
    }

    /**
     * Dichte Stadt: Der Planer dünnt bei 200 aus (standorte_im_umkreis größer). Dann fragt das Gerät enger nach
     * (Radius aus der Dichte, +1 km, immer gerundet, höchstens 3 Anfragen) und die nächsten 60 stimmen wieder.
     * Fehler bei einer Nachfrage: was da ist, zählt.
     */
    @Test
    public void dichteStadtFragtEngerNachUndBleibtGerundet() throws Exception {
        String js = lies("src/main/assets/web/shared/belegung.js");
        File modul = tmp.newFile("belegung2.js");
        Files.write(modul.toPath(), js.getBytes(StandardCharsets.UTF_8));
        String harness = ""
                + "const PB = require(" + JSONObject.quote(modul.getAbsolutePath()) + ");\n"
                + "let seed = 11; function rnd() { seed = (seed * 16807) % 2147483647; return (seed - 1) / 2147483646; }\n"
                + "const lat = 10.0049, lon = 20.0049, st = [];\n"
                + "for (let i = 0; i < 1000; i++) {\n"
                + "  const w = rnd() * 2 * Math.PI, d = Math.sqrt(rnd()) * 12;\n"
                + "  st.push({ name: 'S' + i, lat: lat + d * Math.sin(w) / 111.2, lon: lon + d * Math.cos(w) / (111.2 * Math.cos(lat * Math.PI / 180)), preis: rnd() });\n"
                + "}\n"
                + "const bodies = [];\n"
                + "const planer = (fehler) => (b) => { bodies.push(b); if (fehler && bodies.length === fehler) return Promise.reject(new Error('x'));\n"
                + "  const im = st.filter(s => PB.distKm(b.lat, b.lon, s.lat, s.lon) <= b.radius);\n"
                + "  return Promise.resolve({ standorte_im_umkreis: im.length,\n"
                + "    stationen: im.sort((x, y) => x.preis - y.preis).slice(0, b.limit).map(s => Object.assign({ id: Math.random() }, s)) }); };\n"
                + "const wahr = st.map(s => ({ n: s.name, km: PB.distKm(lat, lon, s.lat, s.lon) })).filter(x => x.km <= 10).sort((a, b) => a.km - b.km).slice(0, 60).map(x => x.n);\n"
                + "(async () => {\n"
                + "  const r = await PB.holen(planer(0), lat, lon, 10, 60);\n"
                + "  const ersteBodies = bodies.slice();\n"
                + "  bodies.length = 0;\n"
                + "  const r2 = await PB.holen(planer(2), lat, lon, 10, 60);\n"
                + "  const nurEine = []; await PB.holen((b) => { nurEine.push(b); return Promise.resolve({ stationen: [] }); }, lat, lon, 10, 60);\n"
                + "  console.log(JSON.stringify({ gleich: JSON.stringify(r.stationen.map(s => s.name)) === JSON.stringify(wahr),\n"
                + "    radien: ersteBodies.map(b => b.radius), gerundet: ersteBodies.every(b => b.lat === 10 && b.lon === 20),\n"
                + "    mitFehler: r2.stationen.length, anfragenMitFehler: bodies.length, duenn: nurEine.length }));\n"
                + "})();\n";
        File h = tmp.newFile("harness2.js");
        Files.write(h.toPath(), harness.getBytes(StandardCharsets.UTF_8));
        String res = node(h);
        assumeTrue("node nicht vorhanden", res != null);
        JSONObject r = new JSONObject(res.trim());
        JSONArray radien = r.getJSONArray("radien");
        assertEquals(11.0, radien.getDouble(0), 1e-9);
        assertTrue("höchstens 3 Anfragen " + radien, radien.length() >= 2 && radien.length() <= 3);
        for (int i = 1; i < radien.length(); i++) {
            assertTrue("enger " + radien, radien.getDouble(i) < radien.getDouble(i - 1) && radien.getDouble(i) >= 2);
        }
        assertTrue("alle Anfragen gerundet", r.getBoolean("gerundet"));
        assertTrue("die 60 nächsten stimmen trotz Ausdünnen", r.getBoolean("gleich"));
        assertEquals("Fehler bei der Nachfrage: Ergebnis aus der ersten Antwort", 60, r.getInt("mitFehler"));
        assertEquals(2, r.getInt("anfragenMitFehler"));
        assertEquals("nicht abgeschnitten: genau eine Anfrage", 1, r.getInt("duenn"));
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
            return null;   // kein node auf diesem Rechner
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
