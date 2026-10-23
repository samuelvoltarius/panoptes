package at.greenchili.surfmate.camping;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.byd.BydVehicleData;
import at.greenchili.surfmate.monitor.GearMonitor;
import at.greenchili.surfmate.server.ExternGuard;
import at.greenchili.surfmate.testutil.Quelltext;

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
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicReference;

/** 0.1.16 Camping 2a: Laufzeit (ein/aus, Fahrt, Trockenlauf, Rückmeldung, Neustart) und Anbindung. */
public class CampingLaufzeitTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private final AtomicReference<BydVehicleData> daten = new AtomicReference<>();
    private File ordner;

    @Before
    public void vorbereiten() throws Exception {
        Camping.testZuruecksetzen();
        ordner = tmp.newFolder("surfmate");
        Files.write(new File(ordner, Camping.TABELLE_DATEI).toPath(),
                CampingKernTest.asset().getBytes(StandardCharsets.UTF_8));
        daten.set(geparkt(80));
    }

    @After
    public void aufraeumen() {
        Camping.testZuruecksetzen();
    }

    private static BydVehicleData geparkt(double soc) {
        return new BydVehicleData.Builder().socPercent(soc).remainKwh(43.1 * soc / 100).speedKmh(0)
                .gearMode(GearMonitor.GEAR_P).outsideTempC(2).powerLevel(2).build();
    }

    private Camping neu() {
        return Camping.fuerTest(ordner.getAbsolutePath(), daten::get, new CampingHeizwegTest.Aufzeichner(), () -> -1);
    }

    @Test
    public void einImStandMitTrockenlaufUndOhneInnentemperatur() throws Exception {
        neu();
        JSONObject r = Camping.ein("test");
        assertTrue(r.toString(), r.getBoolean("ok"));
        assertTrue(Camping.aktiv());
        assertTrue("bewohnt + nichts: Wächter ruht", Camping.blocksArming());
        assertTrue(Camping.ohneAbschreckung());
        JSONObject s = Camping.status();
        assertTrue(s.getBoolean("trockenlauf"));
        assertEquals(JSONObject.NULL, s.get("innenC"));
        assertEquals(2.0, s.getDouble("aussenC"), 0.0);
        JSONArray p = s.getJSONArray("protokoll");
        assertEquals("Vorheizen würde starten", "ein", p.getJSONObject(0).getString("aktion"));
        assertTrue(p.getJSONObject(0).getBoolean("trockenlauf"));
        assertEquals(33, p.getJSONObject(0).getInt("stufe"));
        assertEquals("vorheizen", s.getString("phase"));
    }

    @Test
    public void nichtEinWennFahrtOderUnbekannt() throws Exception {
        neu();
        daten.set(new BydVehicleData.Builder().socPercent(80).speedKmh(30).gearMode(4).build());
        assertEquals("nicht_geparkt", Camping.ein("test").getString("fehler"));
        daten.set(new BydVehicleData.Builder().socPercent(80).speedKmh(0).build());
        assertEquals("fahrzustand_unbekannt", Camping.ein("test").getString("fehler"));
        daten.set(null);
        assertEquals("fahrzustand_unbekannt", Camping.ein("test").getString("fehler"));
        assertFalse(Camping.aktiv());
    }

    @Test
    public void losfahrenBeendetCamping() throws Exception {
        Camping c = neu();
        Camping.ein("test");
        daten.set(new BydVehicleData.Builder().socPercent(80).speedKmh(12).gearMode(4).build());
        c.takt();
        assertFalse(Camping.aktiv());
        assertFalse(Camping.blocksArming());
        assertEquals("ende", Camping.status().getString("phase"));
    }

    @Test
    public void rueckmeldungMitSperre() throws Exception {
        neu();
        assertEquals("camping_aus", Camping.rueckmeldung("zu_kalt").getString("fehler"));
        Camping.ein("test");
        assertEquals("art", Camping.rueckmeldung("heiss").getString("fehler"));
        assertTrue(Camping.rueckmeldung("zu_kalt").getBoolean("uebernommen"));
        JSONObject zweite = Camping.rueckmeldung("zu_kalt");
        assertFalse(zweite.getBoolean("uebernommen"));
        assertEquals("sperre_30_min", zweite.getString("grund"));
        assertTrue("lokal gespeichert", new File(ordner, Camping.LERNEN_DATEI).isFile());
    }

    @Test
    public void zustandUeberlebtNeustart() throws Exception {
        neu();
        Camping.ein("test");
        Camping.testZuruecksetzen();
        neu();
        assertTrue(Camping.aktiv());
        assertEquals("vorheizen", Camping.status().getString("phase"));
        assertTrue(Camping.aus("tipp").getBoolean("ok"));
        assertFalse(Camping.aktiv());
    }

    @Test
    public void ohneKennfeldKeinHeizenAberCamping() throws Exception {
        Files.write(new File(ordner, Camping.TABELLE_DATEI).toPath(), "kaputt".getBytes(StandardCharsets.UTF_8));
        neu();
        assertTrue(Camping.ein("test").getBoolean("ok"));
        JSONObject s = Camping.status();
        assertEquals("kennfeld_fehlt", s.getString("fehler"));
        assertEquals(0, s.getJSONArray("protokoll").length());
    }

    @Test
    public void fahrzustandUndKapazitaet() {
        assertEquals(CampingRegler.Fahrt.UNBEKANNT, Camping.fahrt(null));
        assertEquals(CampingRegler.Fahrt.STEHT, Camping.fahrt(geparkt(50)));
        assertEquals(CampingRegler.Fahrt.FAEHRT,
                Camping.fahrt(new BydVehicleData.Builder().speedKmh(5).gearMode(GearMonitor.GEAR_P).build()));
        assertEquals(CampingRegler.Fahrt.UNBEKANNT,
                Camping.fahrt(new BydVehicleData.Builder().speedKmh(0).build()));
        assertEquals(43.1, Camping.kapazitaetKwh(geparkt(50)), 1e-9);
        assertTrue(Double.isNaN(Camping.kapazitaetKwh(new BydVehicleData.Builder().socPercent(50).build())));
        assertEquals(7 * 60 + 5, Camping.minuten("07:05"));
        assertEquals(-1, Camping.minuten("7.05"));
    }

    @Test
    public void gekoppelteGeraeteDuerfenCamping() {
        assertTrue(ExternGuard.erlaubt("GET", "/api/camping"));
        assertTrue(ExternGuard.erlaubt("POST", "/api/camping"));
        assertTrue(ExternGuard.erlaubt("POST", "/api/camping/rueckmeldung"));
        assertFalse(ExternGuard.erlaubt("POST", "/api/camping/irgendwas"));
    }

    @Test
    public void hakenInWaechterUndDaemon() throws Exception {
        String sd = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/surveillance/ScreenDeterrent.java"));
        assertTrue(sd.contains("if (at.greenchili.surfmate.camping.Camping.ohneAbschreckung()) return;"));
        String cd = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/byd/cloud/BydCloudDeterrent.java"));
        assertTrue(cd.contains("if (at.greenchili.surfmate.camping.Camping.ohneAbschreckung()) return;"));
        String sm = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/surveillance/ShockMonitor.java"));
        assertTrue(sm.contains("!at.greenchili.surfmate.camping.Camping.ohneAbschreckung()"));
        String cam = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/daemon/CameraDaemon.java"));
        assertTrue(cam.contains("|| at.greenchili.surfmate.camping.Camping.blocksArming()) {"));
        assertTrue(cam.contains("startCamping();"));
        String api = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/HttpServer.java"));
        assertTrue(api.contains("CampingApiHandler.handle(method, path, body, out)"));
        String camping = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/camping/Camping.java"));
        assertFalse("Camping selbst greift nie direkt aufs Auto zu (nur über Heizweg/Router)",
                camping.contains("setAcPower") || camping.contains("VehicleCommandRouter") || camping.contains("BydCloud"));
    }
}
