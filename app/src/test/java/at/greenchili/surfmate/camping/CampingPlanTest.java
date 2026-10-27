package at.greenchili.surfmate.camping;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.byd.BydVehicleData;
import at.greenchili.surfmate.monitor.GearMonitor;
import at.greenchili.surfmate.server.ExternGuard;
import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;

/** 0.1.17 Camping planbar (Koordination 27.10.2026): /api/camping/plan, geplant/planFehler, Ende-Regel. */
public class CampingPlanTest {

    private static final TimeZone WIEN = TimeZone.getTimeZone("Europe/Vienna");
    private static final long H = 3_600_000L, MIN = 60_000L;

    private static long um(int tag, int h, int m) {
        Calendar c = Calendar.getInstance(WIEN);
        c.clear();
        c.set(2026, Calendar.OCTOBER, tag, h, m, 0);
        return c.getTimeInMillis();
    }

    // ------------------------------------------------------------ CampingPlan (rein)

    @Test
    public void hhmmIstNaechstesVorkommen() {
        long jetzt = um(27, 18, 0);
        CampingPlan.Ergebnis r = CampingPlan.pruefen("21:30", "07:00", jetzt, WIEN);
        assertTrue(r.ok());
        assertEquals(um(27, 21, 30), r.startMs);
        assertEquals("Ende nach dem Start → nächster Tag", um(28, 7, 0), r.endeMs);
        assertEquals("schon vorbei → morgen", um(28, 17, 0), CampingPlan.pruefen("17:00", null, jetzt, WIEN).startMs);
    }

    @Test
    public void isoMitUndOhneOffset() {
        long jetzt = um(27, 18, 0);
        CampingPlan.Ergebnis r = CampingPlan.pruefen("2026-10-27T20:00:00+01:00", "2026-10-28T08:15:00", jetzt, WIEN);
        assertTrue(r.fehler, r.ok());
        assertEquals(um(27, 20, 0), r.startMs);
        assertEquals("ohne Offset = Ortszeit", um(28, 8, 15), r.endeMs);
        assertTrue(CampingPlan.pruefen("2026-10-27T19:00:00Z", null, jetzt, WIEN).ok());
    }

    @Test
    public void ungueltigePlaene() {
        long jetzt = um(27, 18, 0);
        assertEquals("start", CampingPlan.pruefen(null, null, jetzt, WIEN).fehler);
        assertEquals("start_format", CampingPlan.pruefen("morgen", null, jetzt, WIEN).fehler);
        assertEquals("start_format", CampingPlan.pruefen("25:00", null, jetzt, WIEN).fehler);
        assertEquals("start_vergangen", CampingPlan.pruefen("2026-10-27T10:00:00+01:00", null, jetzt, WIEN).fehler);
        assertEquals("start_zu_weit", CampingPlan.pruefen("2026-11-10T10:00:00+01:00", null, jetzt, WIEN).fehler);
        assertEquals("ende_vor_start",
                CampingPlan.pruefen("2026-10-27T21:00:00+01:00", "2026-10-27T20:00:00+01:00", jetzt, WIEN).fehler);
        assertEquals("ende_format", CampingPlan.pruefen("21:00", "bald", jetzt, WIEN).fehler);
    }

    @Test
    public void endeRegel() {
        long seit = um(27, 21, 0);
        // Weckzeit 07:00 + 60 min Nachlauf → 08:00, Höchstdauer 12 h → 09:00: Weckzeit zuerst
        CampingPlan.Ende e = CampingPlan.ende(seit, 0, 7 * 60, 60, 12, WIEN);
        assertEquals(um(28, 8, 0), e.ms);
        assertEquals("weckzeit", e.grund);
        // Höchstdauer 10 h → 07:00 kommt vor 08:00
        e = CampingPlan.ende(seit, 0, 7 * 60, 60, 10, WIEN);
        assertEquals("hoechstdauer", e.grund);
        assertEquals(seit + 10 * H, e.ms);
        // ausdrückliches Plan-Ende geht vor der Weckzeit, aber nicht über die Höchstdauer
        e = CampingPlan.ende(seit, um(28, 6, 30), 7 * 60, 60, 12, WIEN);
        assertEquals("plan", e.grund);
        assertEquals(um(28, 6, 30), e.ms);
        e = CampingPlan.ende(seit, um(28, 11, 0), 7 * 60, 60, 12, WIEN);
        assertEquals("hoechstdauer", e.grund);
        // ohne Weckzeit: nur Höchstdauer
        assertEquals("hoechstdauer", CampingPlan.ende(seit, 0, -1, 60, 12, WIEN).grund);
        assertNull(CampingPlan.ende(seit, 0, -1, 60, 0, WIEN));
    }

    // ------------------------------------------------------------ in Camping

    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private final AtomicReference<BydVehicleData> daten = new AtomicReference<>();
    private File ordner;

    @Before
    public void vorbereiten() throws Exception {
        Camping.testZuruecksetzen();
        ordner = tmp.newFolder("surfmate");
        Files.write(new File(ordner, Camping.TABELLE_DATEI).toPath(), CampingKernTest.asset().getBytes(StandardCharsets.UTF_8));
        daten.set(new BydVehicleData.Builder().socPercent(80).remainKwh(34.5).speedKmh(0)
                .gearMode(GearMonitor.GEAR_P).outsideTempC(5).powerLevel(2, System.currentTimeMillis()).build());
    }

    @After
    public void aufraeumen() {
        Camping.testZuruecksetzen();
    }

    private Camping neu() {
        return Camping.fuerTest(ordner.getAbsolutePath(), daten::get, new CampingHeizwegTest.Aufzeichner(), () -> -1);
    }

    @Test
    public void planSetzenStatusUndLoeschen() throws Exception {
        neu();
        assertEquals(JSONObject.NULL, Camping.status().get("geplant"));
        JSONObject r = Camping.plan(new JSONObject().put("start", "23:59").put("ende", JSONObject.NULL));
        assertTrue(r.toString(), r.getBoolean("ok"));
        JSONObject g = Camping.status().getJSONObject("geplant");
        assertTrue(g.getLong("start") > System.currentTimeMillis());
        assertEquals(JSONObject.NULL, g.get("ende"));
        assertEquals(JSONObject.NULL, Camping.status().get("planFehler"));
        assertEquals("start_format", Camping.plan(new JSONObject().put("start", "x")).getString("fehler"));
        assertTrue(Camping.plan(new JSONObject().put("loeschen", true)).getBoolean("ok"));
        assertEquals(JSONObject.NULL, Camping.status().get("geplant"));
    }

    @Test
    public void faelligerPlanStartetUndUeberlebtNeustart() throws Exception {
        Camping c = neu();
        c.planStartMs = System.currentTimeMillis() - MIN;
        c.planEndeMs = System.currentTimeMillis() + 5 * H;
        c.takt();
        assertTrue(Camping.aktiv());
        assertEquals(JSONObject.NULL, Camping.status().get("geplant"));
        assertEquals("plan", Camping.status().getString("endeGrund"));
        // Neustart der Haupteinheit
        Camping.testZuruecksetzen();
        neu();
        assertTrue(Camping.aktiv());
        assertEquals("plan", Camping.status().getString("endeGrund"));
    }

    @Test
    public void planUeberlebtNeustartVorDemStart() throws Exception {
        neu();
        Camping.plan(new JSONObject().put("start", "23:59"));
        long start = Camping.status().getJSONObject("geplant").getLong("start");
        Camping.testZuruecksetzen();
        neu();
        assertEquals(start, Camping.status().getJSONObject("geplant").getLong("start"));
    }

    @Test
    public void planMitFehlerStartetNichtUndMerktGrund() throws Exception {
        Camping c = neu();
        daten.set(null);   // Fahrzustand unbekannt
        c.planStartMs = System.currentTimeMillis() - MIN;
        c.takt();
        assertFalse(Camping.aktiv());
        JSONObject f = Camping.status().getJSONObject("planFehler");
        assertEquals("fahrzustand_unbekannt", f.getString("grund"));
        assertEquals(JSONObject.NULL, Camping.status().get("geplant"));
    }

    @Test
    public void verpassterPlanStartetNicht() throws Exception {
        Camping c = neu();
        c.planStartMs = System.currentTimeMillis() - 31 * MIN;
        c.takt();
        assertFalse(Camping.aktiv());
        assertEquals("verpasst", Camping.status().getJSONObject("planFehler").getString("grund"));
    }

    @Test
    public void endeRegelBeendetCamping() throws Exception {
        Camping c = neu();
        assertTrue(Camping.ein("test").getBoolean("ok"));
        assertEquals("hoechstdauer", Camping.status().getString("endeGrund"));
        assertEquals("plan nur, wenn Camping aus ist", "camping_laeuft",
                Camping.plan(new JSONObject().put("start", "23:59")).getString("fehler"));
        long jetzt = System.currentTimeMillis();
        c.uhr = () -> jetzt + 12 * H + MIN;
        c.takt();
        assertFalse(Camping.aktiv());
    }

    @Test
    public void ausschaltenMitEchterLage() throws Exception {
        String q = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/camping/Camping.java"));
        assertTrue(q.contains("CampingRegler.Entscheidung d = regler.ausschalten(grund, l.jetztMs);\n            verarbeiten(d, l);"));
        assertFalse(q.contains("verarbeiten(d, null);"));
    }

    @Test
    public void kopplungDarfPlanen() {
        assertTrue(ExternGuard.erlaubt("POST", "/api/camping/plan"));
    }
}
