package at.greenchili.surfmate.camping;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.byd.BydVehicleData;
import at.greenchili.surfmate.byd.routing.CloudPowerGate;
import at.greenchili.surfmate.byd.routing.VehicleCommandRouter;
import at.greenchili.surfmate.monitor.GearMonitor;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 0.1.16 Stufe 2b: der Heizweg schaltet wirklich – H1 lokal bei Auto an, H2 Cloud bei Auto aus – mit allen
 * Grenzen (nie CLOSEAIR, Gate, Weckungen, Abstand, Weg-2-Sperre, unklarer Zustand = nichts).
 */
public class CampingHeizwegTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long MIN = 60_000L;

    /** Fake-Router: zeichnet Befehle auf, Ergebnis einstellbar. */
    static final class FakeRouter {
        final List<VehicleCommandRouter.VehicleCommand> befehle = new ArrayList<>();
        VehicleCommandRouter.CommandResult antwort =
                VehicleCommandRouter.CommandResult.success(VehicleCommandRouter.Path.SDK, "", 1);
        VehicleCommandRouter.CommandResult apply(VehicleCommandRouter.VehicleCommand c) {
            befehle.add(c);
            return antwort;
        }
        boolean hat(Class<?> k) {
            for (VehicleCommandRouter.VehicleCommand c : befehle) if (k.isInstance(c)) return true;
            return false;
        }
    }

    /** Fake-Heizweg für Camping-Tests: zeichnet auf, Ergebnis einstellbar. */
    static final class Aufzeichner implements Heizweg {
        final List<String> aufrufe = new ArrayList<>();
        Ergebnis einErgebnis = Ergebnis.lokal();
        public Ergebnis ein(int stufe, int geblaese, int dauerMin, CampingRegler.Lage l, boolean cloud) {
            aufrufe.add("ein:" + stufe + ":" + geblaese + ":" + cloud);
            return einErgebnis;
        }
        public Ergebnis aus(CampingRegler.Lage l) { aufrufe.add("aus"); return Ergebnis.lokal(); }
        public JSONObject stand() { return new JSONObject(); }
        public void zuruecksetzen() { aufrufe.add("reset"); }
        public String nachTakt(long jetztMs) { return null; }
    }

    private final FakeRouter router = new FakeRouter();
    private CloudPowerGate.Decision gate = CloudPowerGate.evaluate("climate-remote-on", 0, NOW, true, false, NOW);
    private Boolean weg2 = Boolean.TRUE;

    private RouterHeizweg weg() {
        return new RouterHeizweg(router::apply, n -> gate, () -> weg2, 31);
    }

    private static CampingRegler.Lage lage(int powerLevel, long alterMs, long jetzt) {
        CampingRegler.Lage l = new CampingRegler.Lage();
        l.jetztMs = jetzt;
        l.powerLevel = powerLevel;
        l.powerLevelAlterMs = alterMs;
        l.fahrt = CampingRegler.Fahrt.STEHT;
        return l;
    }

    // ------------------------------------------------------------ RouterHeizweg

    @Test
    public void h1LokalMitGeblaese() {
        Heizweg.Ergebnis e = weg().ein(29, 2, 15, lage(2, 10_000, NOW), false);
        assertEquals(Heizweg.Ergebnis.Art.LOKAL, e.art);
        assertTrue(router.befehle.get(0) instanceof VehicleCommandRouter.ClimateOnCommand);
        assertEquals(29.0, ((VehicleCommandRouter.ClimateOnCommand) router.befehle.get(0)).tempCelsius, 0);
        assertTrue(router.befehle.get(1) instanceof VehicleCommandRouter.ClimateSetFanCommand);
        assertFalse("nie Cloud bei Auto an", router.hat(VehicleCommandRouter.ClimateRemoteOnCommand.class));
    }

    @Test
    public void h1MitCloudPfadIstFehler() {
        router.antwort = VehicleCommandRouter.CommandResult.success(VehicleCommandRouter.Path.CLOUD, "", 1);
        assertEquals(Heizweg.Ergebnis.Art.FEHLER, weg().ein(29, 2, 15, lage(2, 1000, NOW), true).art);
        router.antwort = VehicleCommandRouter.CommandResult.failed(VehicleCommandRouter.Path.SDK, "", 1, null);
        assertEquals(Heizweg.Ergebnis.Art.FEHLER, weg().ein(29, 2, 15, lage(2, 1000, NOW), true).art);
    }

    @Test
    public void ausNurLokalNieCloseair() {
        RouterHeizweg w = weg();
        assertEquals(Heizweg.Ergebnis.Art.LOKAL, w.aus(lage(2, 1000, NOW)).art);
        assertTrue(router.hat(VehicleCommandRouter.ClimateOffCommand.class));
        router.befehle.clear();
        assertEquals(Heizweg.Ergebnis.Art.UEBERSPRUNGEN, w.aus(lage(0, 1000, NOW)).art);
        assertEquals("bei Auto aus wird nichts gesendet", 0, router.befehle.size());
    }

    @Test
    public void unklarerZustandSendetNichts() {
        RouterHeizweg w = weg();
        for (CampingRegler.Lage l : new CampingRegler.Lage[]{lage(1, 1000, NOW), lage(2, 61_000, NOW),
                lage(0, 181_000, NOW), lage(-1, Long.MAX_VALUE, NOW)}) {
            assertEquals(Heizweg.Ergebnis.Art.UEBERSPRUNGEN, w.ein(29, 2, 15, l, true).art);
        }
        assertEquals(0, router.befehle.size());
    }

    @Test
    public void h2NurWennErlaubtUndGateOffen() {
        RouterHeizweg w = weg();
        Heizweg.Ergebnis e = w.ein(33, 3, 15, lage(0, 5000, NOW), false);
        assertEquals("cloud_aus", e.grund);
        gate = CloudPowerGate.evaluate("climate-remote-on", 2, NOW, true, false, NOW);
        e = w.ein(33, 3, 15, lage(0, 5000, NOW), true);
        assertEquals(Heizweg.Ergebnis.Art.UEBERSPRUNGEN, e.art);
        assertTrue(e.grund.startsWith("gate_"));
        assertEquals(0, router.befehle.size());
        gate = CloudPowerGate.evaluate("climate-remote-on", 0, NOW, true, false, NOW);
        e = w.ein(33, 3, 12, lage(0, 5000, NOW), true);
        assertEquals(Heizweg.Ergebnis.Art.CLOUD, e.art);
        VehicleCommandRouter.ClimateRemoteOnCommand c = (VehicleCommandRouter.ClimateRemoteOnCommand) router.befehle.get(0);
        assertEquals(31, c.tempCelsius);
        assertEquals("12 min → Cloud-Stufe 15", 15, c.dauerMinuten);
        assertFalse(router.hat(VehicleCommandRouter.ClimateOnCommand.class));
    }

    @Test
    public void h2AbstandUndHoechstzahl() {
        RouterHeizweg w = weg();
        assertEquals(Heizweg.Ergebnis.Art.CLOUD, w.ein(33, 3, 15, lage(0, 5000, NOW), true).art);
        assertEquals("weck_abstand", w.ein(33, 3, 15, lage(0, 5000, NOW + 30 * MIN), true).grund);
        long t = NOW;
        for (int i = 1; i < RouterHeizweg.MAX_WECKUNGEN; i++) {
            t += 46 * MIN;
            assertEquals(Heizweg.Ergebnis.Art.CLOUD, w.ein(33, 3, 15, lage(0, 5000, t), true).art);
        }
        assertEquals("weck_grenze", w.ein(33, 3, 15, lage(0, 5000, t + 46 * MIN), true).grund);
        w.zuruecksetzen();
        assertEquals(Heizweg.Ergebnis.Art.CLOUD, w.ein(33, 3, 15, lage(0, 5000, t + 92 * MIN), true).art);
    }

    @Test
    public void weg2TotSperrtCloud() {
        RouterHeizweg w = weg();
        w.ein(33, 3, 15, lage(0, 5000, NOW), true);
        assertEquals("zu früh geprüft", null, w.nachTakt(NOW + MIN));
        weg2 = Boolean.FALSE;
        assertEquals("cloud_gesperrt_weg2", w.nachTakt(NOW + 4 * MIN));
        assertEquals("cloud_gesperrt_weg2", w.ein(33, 3, 15, lage(0, 5000, NOW + 50 * MIN), true).grund);
        assertTrue(w.stand().toString().contains("\"cloudGesperrt\":true"));
    }

    @Test
    public void cloudDauerStufen() {
        assertEquals(10, RouterHeizweg.cloudDauer(5));
        assertEquals(15, RouterHeizweg.cloudDauer(15));
        assertEquals(20, RouterHeizweg.cloudDauer(16));
        assertEquals(30, RouterHeizweg.cloudDauer(45));
    }

    // ------------------------------------------------------------ Camping mit echtem Heizweg

    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private final AtomicReference<BydVehicleData> daten = new AtomicReference<>();
    private File ordner;

    @Before
    public void vorbereiten() throws Exception {
        Camping.testZuruecksetzen();
        ordner = tmp.newFolder("surfmate");
        Files.write(new File(ordner, Camping.TABELLE_DATEI).toPath(), CampingKernTest.asset().getBytes(StandardCharsets.UTF_8));
        daten.set(new BydVehicleData.Builder().socPercent(80).remainKwh(34.5).speedKmh(0)
                .gearMode(GearMonitor.GEAR_P).outsideTempC(2).powerLevel(2, System.currentTimeMillis()).build());
    }

    @After
    public void aufraeumen() {
        Camping.testZuruecksetzen();
    }

    @Test
    public void campingSchaltetUeberDenHeizweg() throws Exception {
        Camping.heizwegQuelle = () -> Camping.WEG_AUTO_AN;
        Aufzeichner a = new Aufzeichner();
        Camping.fuerTest(ordner.getAbsolutePath(), daten::get, a, () -> 1);
        assertTrue(Camping.ein("test").getBoolean("ok"));
        assertTrue(a.aufrufe.toString(), a.aufrufe.contains("ein:33:3:false"));   // Vorheizen, Cloud nicht erlaubt
        JSONObject s = Camping.status();
        assertFalse(s.getBoolean("trockenlauf"));
        assertEquals("auto_an", s.getString("heizweg"));
        JSONArray p = s.getJSONArray("protokoll");
        assertFalse(p.getJSONObject(0).getBoolean("trockenlauf"));
        assertEquals("LOKAL", p.getJSONObject(0).getString("ergebnis"));
    }

    @Test
    public void probelaufSchaltetNichts() throws Exception {
        Camping.heizwegQuelle = () -> Camping.WEG_PROBE;
        Aufzeichner a = new Aufzeichner();
        Camping.fuerTest(ordner.getAbsolutePath(), daten::get, a, () -> 1);
        Camping.ein("test");
        assertFalse(a.aufrufe.toString(), a.aufrufe.toString().contains("ein:"));
        assertTrue(Camping.status().getBoolean("trockenlauf"));
    }

    @Test
    public void fehlerPausiertUndMeldet() throws Exception {
        Camping.heizwegQuelle = () -> Camping.WEG_MIT_CLOUD;
        Aufzeichner a = new Aufzeichner();
        a.einErgebnis = Heizweg.Ergebnis.fehler("lokal_failed");
        Camping c = Camping.fuerTest(ordner.getAbsolutePath(), daten::get, a, () -> 1);
        Camping.ein("test");
        assertTrue(a.aufrufe.contains("ein:33:3:true"));
        c.takt();
        assertEquals("abweichung_bestaetigen", Camping.status().getString("sperrGrund"));
        assertTrue(Camping.weiter().getBoolean("ok"));
    }

    @Test
    public void standardIstProbelauf() throws Exception {
        String k = at.greenchili.surfmate.testutil.Quelltext.lies(java.nio.file.Paths.get(
                "src/main/java/at/greenchili/surfmate/einstellungen/Katalog.java"));
        assertTrue(k.contains("weg.standard = \"probelauf\";"));
        String c = at.greenchili.surfmate.testutil.Quelltext.lies(java.nio.file.Paths.get(
                "src/main/java/at/greenchili/surfmate/camping/Camping.java"));
        assertTrue(c.contains("c.optString(\"heizweg\", WEG_PROBE)"));
    }

    @Test
    public void unbekannteEinstellungIstProbe() {
        Camping.heizwegQuelle = () -> "quatsch";
        assertEquals(Camping.WEG_PROBE, Camping.heizwegEinstellung());
        Camping.heizwegQuelle = () -> null;
        assertEquals(Camping.WEG_PROBE, Camping.heizwegEinstellung());
    }
}
