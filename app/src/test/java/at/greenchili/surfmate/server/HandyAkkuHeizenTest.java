package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import java.util.Collections;
import java.util.List;

/**
 * 0.1.26 Handy-Aktionen „akku_heizen“ (Recht wie klima, nur Cloud über Router + Gate, ehrliche Gründe, kein Echo) und
 * „heizen_stoppen“ (Klima UND Akku aus, immer beide).
 */
public class HandyAkkuHeizenTest {

    final List<VehicleCommandRouter.VehicleCommand> befehle = new ArrayList<>();
    final List<String> echoFragen = new ArrayList<>();
    VehicleCommandRouter.CommandResult ergebnis;
    final java.util.Map<String, VehicleCommandRouter.CommandResult> jeBefehl = new java.util.HashMap<>();
    boolean autoAus = true;
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
        ergebnis = VehicleCommandRouter.CommandResult.success(VehicleCommandRouter.Path.CLOUD, "ok", 5);
        HandyApiHandler.umgebung = new HandyApiHandler.Umgebung() {
            public VehicleCommandRouter.CommandResult ausfuehren(VehicleCommandRouter.VehicleCommand cmd) {
                befehle.add(cmd);
                return jeBefehl.containsKey(cmd.name()) ? jeBefehl.get(cmd.name()) : ergebnis;
            }
            public CloudPowerGate.Decision gate(String befehl) {
                return CloudPowerGate.evaluate(befehl, autoAus ? 0 : 2, jetzt - 1_000, true, !autoAus, jetzt);
            }
            public int echo(String aktion) { echoFragen.add(aktion); return BydDataCollector.ECHO_UNBEKANNT; }
            public void telegram(String key, Object... args) { }
            public String pushAnlegen(String e, byte[] p, byte[] a, String l) { return "x"; }
            public void pushEntfernen(String id) { }
            public long jetzt() { return jetzt; }
        };
    }

    @After
    public void ab() {
        HandyApiHandler.umgebung = vorher;
        HandyApiHandler.kopplungen = null;
    }

    JSONObject akku(String kid, Object an) throws Exception {
        return HandyApiHandler.aktion(new JSONObject().put("aktion", "akku_heizen").put("an", an), kid);
    }

    @Test
    public void anUndAusUeberDieCloud() throws Exception {
        String k = kopplungen.anlegen("Pixel", Kopplungen.ART_HANDY, jetzt);
        JSONObject r = akku(k, true);
        assertTrue(r.toString(), r.getBoolean("ok"));
        assertEquals("cloud", r.getString("weg"));
        assertFalse("keine lokale Rückmeldung", r.getBoolean("bestaetigt"));
        assertTrue(r.getBoolean("an"));
        VehicleCommandRouter.BatteryHeatCommand c = (VehicleCommandRouter.BatteryHeatCommand) befehle.get(0);
        assertTrue(c.enabled);
        assertEquals("battery-heat", c.name());
        assertEquals("nur Cloud, also immer hinter dem Gate", VehicleCommandRouter.RoutePreference.CLOUD_ONLY,
                c.defaultPreference());

        r = akku(k, false);
        assertTrue(r.getBoolean("ok"));
        assertFalse(r.getBoolean("an"));
        assertFalse(((VehicleCommandRouter.BatteryHeatCommand) befehle.get(1)).enabled);
        assertEquals("[akku_heizen, akku_heizen]", echoFragen.toString());
    }

    @Test
    public void werteUndRecht() throws Exception {
        String k = kopplungen.anlegen("Pixel", Kopplungen.ART_HANDY, jetzt);
        for (Object f : new Object[]{"true", 1, 0, JSONObject.NULL}) {
            assertEquals(String.valueOf(f), "werte", akku(k, f).getString("grund"));
        }
        assertEquals("ohne an", "werte",
                HandyApiHandler.aktion(new JSONObject().put("aktion", "akku_heizen"), k).getString("grund"));
        assertTrue(befehle.isEmpty());
        kopplungen.rechteSetzen(k, Collections.singletonList(Kopplungen.RECHT_ZUSPERREN));
        assertEquals("Recht wie klima", "nicht_erlaubt", akku(k, true).getString("grund"));
        String cockpit = kopplungen.anlegen("Cockpit", "cockpit", jetzt);
        assertEquals("nicht_erlaubt", akku(cockpit, true).getString("grund"));
        assertTrue(befehle.isEmpty());
    }

    @Test
    public void ehrlicheGruende() throws Exception {
        String k = kopplungen.anlegen("Pixel", Kopplungen.ART_HANDY, jetzt);
        // Auto läuft → Gate sperrt die Cloud → auto_laeuft
        autoAus = false;
        ergebnis = VehicleCommandRouter.CommandResult.blocked("gesperrt");
        JSONObject r = akku(k, true);
        assertEquals(r.toString(), "auto_laeuft", r.getString("grund"));
        assertEquals(CloudPowerGate.REASON_CAR_ON, r.getString("gate"));
        // Auto aus, keine BYD-Cloud
        autoAus = true;
        ergebnis = VehicleCommandRouter.CommandResult.cloudNichtEingerichtet("Richte die BYD-Cloud ein", 1);
        r = akku(k, true);
        assertEquals(VehicleCommandRouter.CommandResult.GRUND_CLOUD_NICHT_EINGERICHTET, r.getString("grund"));
        assertEquals("/byd-cloud.html", r.getString("einrichten"));
    }

    @Test
    public void ratenlimitGemeinsamMitDenAnderenAktionen() throws Exception {
        String k = kopplungen.anlegen("Pixel", Kopplungen.ART_HANDY, jetzt);
        int ok = 0;
        String letzter = "";
        for (int i = 0; i < 40; i++) {
            JSONObject r = akku(k, i % 2 == 0);
            if (r.optBoolean("ok")) ok++;
            else letzter = r.getString("grund");
        }
        assertTrue("begrenzt: " + ok, ok < 40);
        assertEquals("ratenlimit", letzter);
    }

    JSONObject stopp(String kid) throws Exception {
        return HandyApiHandler.aktion(new JSONObject().put("aktion", "heizen_stoppen"), kid);
    }

    @Test
    public void heizenStoppenSchicktImmerBeide() throws Exception {
        String k = kopplungen.anlegen("Pixel", Kopplungen.ART_HANDY, jetzt);
        JSONObject r = stopp(k);
        assertTrue(r.toString(), r.getBoolean("ok"));
        assertEquals(2, befehle.size());
        assertTrue(befehle.get(0) instanceof VehicleCommandRouter.ClimateOffCommand);
        assertFalse(((VehicleCommandRouter.BatteryHeatCommand) befehle.get(1)).enabled);
        assertTrue(r.getJSONObject("klima").getBoolean("ok"));
        assertTrue(r.getJSONObject("akku").getBoolean("ok"));
        assertEquals("[klima_aus, akku_heizen]", echoFragen.toString());
    }

    @Test
    public void heizenStoppenBeiLaufendemAutoKlimaLokalAkkuGesperrt() throws Exception {
        String k = kopplungen.anlegen("Pixel", Kopplungen.ART_HANDY, jetzt);
        autoAus = false;
        jeBefehl.put("battery-heat", VehicleCommandRouter.CommandResult.blocked("gesperrt"));
        ergebnis = VehicleCommandRouter.CommandResult.success(VehicleCommandRouter.Path.SDK, "ok", 5);
        JSONObject r = stopp(k);
        assertTrue("Klima lokal aus genügt", r.getBoolean("ok"));
        assertEquals("lokal", r.getJSONObject("klima").getString("weg"));
        assertEquals("auto_laeuft", r.getJSONObject("akku").getString("grund"));
    }

    @Test
    public void heizenStoppenOhneCloudEhrlich() throws Exception {
        String k = kopplungen.anlegen("Pixel", Kopplungen.ART_HANDY, jetzt);
        ergebnis = VehicleCommandRouter.CommandResult.cloudNichtEingerichtet("Richte die BYD-Cloud ein", 1);
        JSONObject r = stopp(k);
        assertFalse(r.getBoolean("ok"));
        assertEquals(VehicleCommandRouter.CommandResult.GRUND_CLOUD_NICHT_EINGERICHTET, r.getString("grund"));
        assertEquals("/byd-cloud.html", r.getString("einrichten"));
        assertEquals(2, befehle.size());
        // Recht klima
        befehle.clear();
        kopplungen.rechteSetzen(k, Collections.singletonList(Kopplungen.RECHT_ZUSPERREN));
        assertEquals("nicht_erlaubt", stopp(k).getString("grund"));
        assertTrue(befehle.isEmpty());
    }

    @Test
    public void klimaAnMitStufeOderSdkWert() throws Exception {
        // 0.1.26: stufe 1–17 (SDK = Stufe + 16) oder weiter tempC 17–33
        String k = kopplungen.anlegen("Pixel", Kopplungen.ART_HANDY, jetzt);
        JSONObject r = HandyApiHandler.aktion(new JSONObject().put("aktion", "klima_an").put("stufe", 10), k);
        assertTrue(r.toString(), r.getBoolean("ok"));
        assertEquals(26.0, ((VehicleCommandRouter.ClimateOnCommand) befehle.get(0)).tempCelsius, 0.0);
        HandyApiHandler.aktion(new JSONObject().put("aktion", "klima_an").put("stufe", 1), k);
        HandyApiHandler.aktion(new JSONObject().put("aktion", "klima_an").put("stufe", 17), k);
        HandyApiHandler.aktion(new JSONObject().put("aktion", "klima_an").put("tempC", 27), k);
        assertEquals(17.0, ((VehicleCommandRouter.ClimateOnCommand) befehle.get(1)).tempCelsius, 0.0);
        assertEquals(33.0, ((VehicleCommandRouter.ClimateOnCommand) befehle.get(2)).tempCelsius, 0.0);
        assertEquals(27.0, ((VehicleCommandRouter.ClimateOnCommand) befehle.get(3)).tempCelsius, 0.0);
        String k2 = kopplungen.anlegen("Pixel 2", Kopplungen.ART_HANDY, jetzt);   // eigenes Ratenlimit
        for (Object f : new Object[]{0, 18, "10", 9.5}) {
            assertEquals(String.valueOf(f), "werte",
                    HandyApiHandler.aktion(new JSONObject().put("aktion", "klima_an").put("stufe", f), k2).getString("grund"));
        }
        assertEquals(4, befehle.size());
    }

    @Test
    public void stufeGewinntUndCapabilitiesMelden() throws Exception {
        // 0.1.26 Vertrag: kommen stufe und tempC, gewinnt stufe; /api/system/capabilities meldet klima.stufe = 1
        String k = kopplungen.anlegen("Pixel", Kopplungen.ART_HANDY, jetzt);
        HandyApiHandler.aktion(new JSONObject().put("aktion", "klima_an").put("stufe", 10).put("tempC", 30), k);
        assertEquals(26.0, ((VehicleCommandRouter.ClimateOnCommand) befehle.get(0)).tempCelsius, 0.0);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        assertTrue(SystemApiHandler.handle("GET", "/api/system/capabilities", null, out));
        String r = new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        JSONObject c = new JSONObject(r.substring(r.indexOf('{')));
        assertEquals(r, 1, c.getJSONObject("klima").getInt("stufe"));
    }
}
