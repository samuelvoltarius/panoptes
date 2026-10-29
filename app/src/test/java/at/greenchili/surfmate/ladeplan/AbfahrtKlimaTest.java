package at.greenchili.surfmate.ladeplan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.byd.BydVehicleData;
import at.greenchili.surfmate.byd.routing.CloudPowerGate;
import at.greenchili.surfmate.byd.routing.VehicleCommandRouter;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 0.1.28 (P6) „um 7:30 warm“: kein BYD-Zeitplan; Panoptes löst selbst aus und prüft genau dann, ob das Auto aus ist.
 * Auto an → nur lokal; Auto aus → Gate im Moment des Starts, dann OPENAIR; je Abfahrt höchstens ein Befehl.
 */
public class AbfahrtKlimaTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    static final ZoneId WIEN = ZoneId.of("Europe/Vienna");
    /** Mittwoch 28.10.2026, Abfahrt Werktag 07:30. */
    static final long ABFAHRT = ZonedDateTime.of(2026, 10, 28, 7, 30, 0, 0, WIEN).toInstant().toEpochMilli();

    long jetzt;
    int powerLevel = 0;
    long powerAlterMs = 10_000;
    double soc = 60;
    double aussen = 3;
    CloudPowerGate.Decision gateAntwort;
    VehicleCommandRouter.CommandResult lokalAntwort, cloudAntwort;
    final List<String> lokal = new ArrayList<>(), cloud = new ArrayList<>(), gate = new ArrayList<>();
    File datei;
    AbfahrtKlima a;

    @Before
    public void setUp() throws Exception {
        jetzt = ABFAHRT - 15 * 60_000L;
        datei = new File(tmp.getRoot(), "abfahrt_klima.json");
        gateAntwort = CloudPowerGate.evaluate("climate-remote-on", 0, 1000, true, false, 1000);
        lokalAntwort = VehicleCommandRouter.CommandResult.success(VehicleCommandRouter.Path.SDK, "", 1);
        cloudAntwort = VehicleCommandRouter.CommandResult.success(VehicleCommandRouter.Path.CLOUD, "", 1);
        a = neu();
    }

    AbfahrtKlima neu() {
        return new AbfahrtKlima(c -> {
            lokal.add(beschreibe(c));
            return lokalAntwort;
        }, c -> {
            cloud.add(beschreibe(c));
            return cloudAntwort;
        }, n -> {
            gate.add(n);
            return gateAntwort;
        }, () -> new BydVehicleData.Builder().powerLevel(powerLevel, jetzt - powerAlterMs).socPercent(soc)
                .outsideTempC(aussen).build(), () -> jetzt, datei);
    }

    static String beschreibe(VehicleCommandRouter.VehicleCommand c) {
        if (c instanceof VehicleCommandRouter.ClimateRemoteOnCommand) {
            VehicleCommandRouter.ClimateRemoteOnCommand r = (VehicleCommandRouter.ClimateRemoteOnCommand) c;
            return c.name() + " " + r.tempCelsius + " " + r.dauerMinuten;
        }
        if (c instanceof VehicleCommandRouter.ClimateOnCommand) {
            VehicleCommandRouter.ClimateOnCommand r = (VehicleCommandRouter.ClimateOnCommand) c;
            return c.name() + " " + (int) r.tempCelsius + " " + r.remoteDurationMinutes;
        }
        return c.name();
    }

    static JSONObject einstellungen(boolean an, int vorlauf) throws Exception {
        JSONObject s = LadeplanDienst.standard();
        s.put("vorklima", new JSONObject().put("an", an).put("vorlaufMin", vorlauf));
        s.getJSONObject("abfahrt").getJSONObject("werktag").put("an", true).put("zeit", "07:30").put("ziel", 80);
        return s;
    }

    String takt(JSONObject s) throws Exception {
        JSONObject o = a.takt(s, WIEN, "AT");
        JSONObject l = o.optJSONObject("letztes");
        return l == null ? null : l.getString("ergebnis");
    }

    @Test
    public void standardAusSchaltetNie() throws Exception {
        JSONObject s = LadeplanDienst.standard();
        assertFalse(s.getJSONObject("vorklima").getBoolean("an"));
        assertEquals(15, s.getJSONObject("vorklima").getInt("vorlaufMin"));
        assertNull(takt(s));
        assertTrue(lokal.isEmpty() && cloud.isEmpty() && gate.isEmpty());
    }

    @Test
    public void vorDemStartNichts() throws Exception {
        jetzt = ABFAHRT - 16 * 60_000L;
        JSONObject o = a.takt(einstellungen(true, 15), WIEN, "AT");
        assertEquals(ABFAHRT - 15 * 60_000L, o.getJSONObject("naechste").getLong("start"));
        assertEquals(ABFAHRT, o.getJSONObject("naechste").getLong("abfahrt"));
        assertTrue(gate.isEmpty() && cloud.isEmpty() && lokal.isEmpty());
    }

    @Test
    public void autoAusGateGenauZumStartDannCloudEinmal() throws Exception {
        assertEquals("cloud", takt(einstellungen(true, 15)));
        assertEquals(1, gate.size());
        assertEquals("climate-remote-on", gate.get(0));
        // 3 °C außen → Stufe 12 (Heizen 3) = SDK 28; Dauer 15 min
        assertEquals("[climate-remote-on 28 15]", cloud.toString());
        assertTrue(lokal.isEmpty());
        // nächster Takt: dieselbe Abfahrt nie ein zweites Mal
        jetzt += 60_000;
        takt(einstellungen(true, 15));
        assertEquals(1, cloud.size());
        // auch nicht nach Neustart (Zustand in der Datei)
        a = neu();
        takt(einstellungen(true, 15));
        assertEquals(1, cloud.size());
    }

    @Test
    public void autoAnNurLokalNieCloud() throws Exception {
        powerLevel = 2;
        assertEquals("lokal", takt(einstellungen(true, 15)));
        assertEquals("[climate-on 28 15]", lokal.toString());
        assertTrue("bei Auto an kein Gate und keine Cloud", gate.isEmpty() && cloud.isEmpty());
    }

    @Test
    public void lokalFehlschlagFaelltNichtAufCloud() throws Exception {
        powerLevel = 2;
        lokalAntwort = VehicleCommandRouter.CommandResult.failed(VehicleCommandRouter.Path.SDK, "", 1, null);
        assertEquals("fehler_lokal", takt(einstellungen(true, 15)));
        assertTrue(cloud.isEmpty());
    }

    @Test
    public void gateMeldetAutoAnDannNichts() throws Exception {
        gateAntwort = CloudPowerGate.evaluate("climate-remote-on", 0, 1000, true, true, 1000);
        assertEquals("auto_an", takt(einstellungen(true, 15)));
        assertTrue(cloud.isEmpty());
    }

    @Test
    public void zustandUnklarWartetHoechstensDreiTakte() throws Exception {
        powerAlterMs = 10 * 60_000L;   // zu alt: weder „an“ noch „aus“ belegt
        JSONObject s = einstellungen(true, 15);
        assertNull(takt(s));
        jetzt += 60_000;
        assertNull(takt(s));
        jetzt += 60_000;
        assertEquals("zustand_unklar", takt(s));
        assertTrue(gate.isEmpty() && cloud.isEmpty() && lokal.isEmpty());
    }

    @Test
    public void gateStaleWirdNichtGesendet() throws Exception {
        gateAntwort = CloudPowerGate.evaluate("climate-remote-on", 0, 1000, true, false, 1000 + 999_000);
        JSONObject s = einstellungen(true, 15);
        for (int i = 0; i < 3; i++) {
            takt(s);
            jetzt += 60_000;
        }
        assertTrue(cloud.isEmpty());
        assertEquals(3, gate.size());
    }

    @Test
    public void cloudOhneBestaetigungIstFehlerUndNichtWiederholt() throws Exception {
        cloudAntwort = VehicleCommandRouter.CommandResult.failed(VehicleCommandRouter.Path.CLOUD, "", 1, null);
        assertEquals("fehler_cloud", takt(einstellungen(true, 15)));
        jetzt += 60_000;
        takt(einstellungen(true, 15));
        assertEquals(1, cloud.size());
    }

    @Test
    public void akkuLeerStartetNicht() throws Exception {
        soc = 15;
        assertEquals("akku_niedrig", takt(einstellungen(true, 15)));
        assertTrue(cloud.isEmpty() && lokal.isEmpty());
    }

    @Test
    public void zuSpaetNachNeustart() throws Exception {
        jetzt = ABFAHRT - 2 * 60_000L;
        assertEquals("zu_spaet", takt(einstellungen(true, 15)));
        assertTrue(gate.isEmpty() && cloud.isEmpty());
    }

    @Test
    public void sommerKuehlenUndVorlaufAufgerundet() throws Exception {
        aussen = 30;
        jetzt = ABFAHRT - 10 * 60_000L;
        assertEquals("cloud", takt(einstellungen(true, 10)));
        // über 24 °C → Stufe 6 (Kühlen 3) = SDK 22
        assertEquals("[climate-remote-on 22 10]", cloud.toString());
        assertEquals(30, AbfahrtKlima.cloudDauer(31));
        assertEquals(25, AbfahrtKlima.cloudDauer(21));
    }

    @Test
    public void vorschauSchaltetNie() throws Exception {
        JSONObject o = a.vorschau(einstellungen(true, 15), WIEN, "AT");
        assertTrue(o.getBoolean("an"));
        assertEquals(ABFAHRT - 15 * 60_000L, o.getJSONObject("naechste").getLong("start"));
        assertTrue(gate.isEmpty() && cloud.isEmpty() && lokal.isEmpty());
    }

    @Test
    public void pruefungDerEinstellung() throws Exception {
        assertNull(AbfahrtKlima.pruefe(AbfahrtKlima.standard()));
        assertEquals("vorklima_ungueltig", AbfahrtKlima.pruefe(new JSONObject().put("an", true).put("vorlaufMin", 12)));
        assertEquals("vorklima_ungueltig", AbfahrtKlima.pruefe(new JSONObject().put("an", "ja").put("vorlaufMin", 15)));
        assertEquals("vorklima_ungueltig", AbfahrtKlima.pruefe(null));
    }
}
