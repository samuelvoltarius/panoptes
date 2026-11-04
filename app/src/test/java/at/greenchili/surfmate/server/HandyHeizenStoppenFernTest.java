package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.auth.Kopplungen;
import at.greenchili.surfmate.byd.BydDataCollector;
import at.greenchili.surfmate.byd.routing.CloudPowerGate;
import at.greenchili.surfmate.byd.routing.VehicleCommandRouter;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.1.38.8: „Heizen stoppen“ der Handy-App geht bei laufender Fern-Sitzung über die Cloud ({@code ClimateRemoteOffCommand},
 * CLOSEAIR), sonst wie bisher lokal zuerst. Es wird nur eine Attrappe des Routers befragt; nichts geht ans Auto.
 */
public class HandyHeizenStoppenFernTest {

    final List<VehicleCommandRouter.VehicleCommand> befehle = new ArrayList<>();
    boolean fernAktiv, schlaeft;
    long jetzt = 1_800_000_000_000L;
    Kopplungen kopplungen;
    final JSONArray[] gespeichert = {new JSONArray()};
    HandyApiHandler.Umgebung vorher;

    @Before
    public void auf() {
        vorher = HandyApiHandler.umgebung;
        kopplungen = new Kopplungen(new Kopplungen.Speicher() {
            public JSONArray lade() { return gespeichert[0]; }
            public boolean speichere(JSONArray g) { gespeichert[0] = g; return true; }
        });
        HandyApiHandler.kopplungen = kopplungen;
        HandyApiHandler.umgebung = new HandyApiHandler.Umgebung() {
            public VehicleCommandRouter.CommandResult ausfuehren(VehicleCommandRouter.VehicleCommand cmd) {
                befehle.add(cmd);
                return VehicleCommandRouter.CommandResult.success(VehicleCommandRouter.Path.CLOUD, "ok", 5);
            }
            public CloudPowerGate.Decision gate(String befehl) {
                return CloudPowerGate.evaluate(befehl, 0, jetzt - 1_000, true, false, jetzt);
            }
            public int echo(String aktion) { return BydDataCollector.ECHO_UNBEKANNT; }
            public void telegram(String key, Object... args) { }
            public String pushAnlegen(String e, byte[] p, byte[] a, String l) { return "x"; }
            public void pushEntfernen(String id) { }
            public long jetzt() { return jetzt; }
            @Override public boolean fernKlimaAktiv() { return fernAktiv; }
            @Override public boolean autoSchlaeft() { return schlaeft; }
        };
    }

    @After
    public void ab() {
        HandyApiHandler.umgebung = vorher;
        HandyApiHandler.kopplungen = null;
    }

    @Test
    public void ohneFernSitzungLokalZuerstWieBisher() throws Exception {
        String k = kopplungen.anlegen("Pixel", Kopplungen.ART_HANDY, jetzt);
        JSONObject r = HandyApiHandler.aktion(new JSONObject().put("aktion", "heizen_stoppen"), k);
        assertTrue(r.toString(), r.getBoolean("ok"));
        assertTrue(befehle.get(0) instanceof VehicleCommandRouter.ClimateOffCommand);
        assertTrue(befehle.get(1) instanceof VehicleCommandRouter.BatteryHeatCommand);
    }

    @Test
    public void schlaftOhneFernSitzungKeinKlimaBefehl() throws Exception {
        schlaeft = true;
        String k = kopplungen.anlegen("Pixel", Kopplungen.ART_HANDY, jetzt);
        JSONObject r = HandyApiHandler.aktion(new JSONObject().put("aktion", "heizen_stoppen"), k);
        for (VehicleCommandRouter.VehicleCommand c : befehle) {
            assertTrue("kein Klima-Befehl an das schlafende Auto: " + c.name(), c instanceof VehicleCommandRouter.BatteryHeatCommand);
        }
        assertEquals("nichts_zu_stoppen", r.getJSONObject("klima").getString("grund"));
    }

    @Test
    public void schlaftMitFernSitzungUeberDieCloud() throws Exception {
        schlaeft = true;
        fernAktiv = true;
        String k = kopplungen.anlegen("Pixel", Kopplungen.ART_HANDY, jetzt);
        HandyApiHandler.aktion(new JSONObject().put("aktion", "heizen_stoppen"), k);
        assertTrue(befehle.get(0) instanceof VehicleCommandRouter.ClimateRemoteOffCommand);
    }

    @Test
    public void mitFernSitzungUeberDieCloud() throws Exception {
        fernAktiv = true;
        String k = kopplungen.anlegen("Pixel", Kopplungen.ART_HANDY, jetzt);
        JSONObject r = HandyApiHandler.aktion(new JSONObject().put("aktion", "heizen_stoppen"), k);
        assertTrue(r.toString(), r.getBoolean("ok"));
        assertEquals(2, befehle.size());
        assertTrue("Fern-Stopp = CLOSEAIR über die Cloud", befehle.get(0) instanceof VehicleCommandRouter.ClimateRemoteOffCommand);
        assertTrue(befehle.get(1) instanceof VehicleCommandRouter.BatteryHeatCommand);
        assertEquals("cloud", r.getJSONObject("klima").getString("weg"));
    }
}
