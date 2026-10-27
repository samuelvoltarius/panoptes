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

/** 0.1.21 Handy-Aktion „sitzheizung“: Recht wie klima, Router wie das Klima, Echo, ehrliche Gründe, Ratenlimit. */
public class HandySitzheizungTest {

    final List<VehicleCommandRouter.VehicleCommand> befehle = new ArrayList<>();
    final List<String> echoFragen = new ArrayList<>();
    VehicleCommandRouter.CommandResult ergebnis;
    boolean autoAus = true;
    int echo = BydDataCollector.ECHO_OK;
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
        ergebnis = VehicleCommandRouter.CommandResult.success(VehicleCommandRouter.Path.SDK, "ok", 5);
        HandyApiHandler.umgebung = new HandyApiHandler.Umgebung() {
            public VehicleCommandRouter.CommandResult ausfuehren(VehicleCommandRouter.VehicleCommand cmd) {
                befehle.add(cmd);
                return ergebnis;
            }
            public CloudPowerGate.Decision gate(String befehl) {
                return CloudPowerGate.evaluate(befehl, autoAus ? 0 : 2, jetzt - 1_000, true, !autoAus, jetzt);
            }
            public int echo(String aktion) { return BydDataCollector.ECHO_UNBEKANNT; }
            public int echoSitz(int position, int stufe) { echoFragen.add(position + ":" + stufe); return echo; }
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

    JSONObject sitz(String kid, String sitz, Object stufe) throws Exception {
        return HandyApiHandler.aktion(new JSONObject().put("aktion", "sitzheizung").put("sitz", sitz).put("stufe", stufe), kid);
    }

    @Test
    public void fahrerUndBeifahrerUeberDenRouterMitEcho() throws Exception {
        String k = kopplungen.anlegen("Pixel", Kopplungen.ART_HANDY, jetzt);
        JSONObject r = sitz(k, "fahrer", 2);
        assertTrue(r.toString(), r.getBoolean("ok"));
        assertEquals("lokal", r.getString("weg"));
        assertTrue(r.getBoolean("bestaetigt"));
        assertEquals("fahrer", r.getString("sitz"));
        assertEquals(2, r.getInt("stufe"));
        VehicleCommandRouter.SeatHeatCommand c = (VehicleCommandRouter.SeatHeatCommand) befehle.get(0);
        assertEquals(1, c.position);
        assertEquals(2, c.level);
        assertEquals("seat-heat", c.name());
        assertTrue("Cloud nur über das Gate, nie bei wachem Auto", c.localOnlyWhenAwake());

        ergebnis = VehicleCommandRouter.CommandResult.success(VehicleCommandRouter.Path.CLOUD, "ok", 5);
        echo = BydDataCollector.ECHO_UNBEKANNT;
        r = sitz(k, "beifahrer", 0);
        assertTrue(r.getBoolean("ok"));
        assertEquals("cloud", r.getString("weg"));
        assertFalse("ohne Echo nicht bestätigt", r.getBoolean("bestaetigt"));
        assertEquals(2, ((VehicleCommandRouter.SeatHeatCommand) befehle.get(1)).position);
        assertEquals("[1:2, 2:0]", echoFragen.toString());
    }

    @Test
    public void werteUndRecht() throws Exception {
        String k = kopplungen.anlegen("Pixel", Kopplungen.ART_HANDY, jetzt);
        for (Object[] f : new Object[][]{{"hinten", 1}, {"fahrer", 3}, {"fahrer", -1}, {"fahrer", "2"}, {"fahrer", 1.5}, {"", 1}}) {
            assertEquals(f[0] + "/" + f[1], "werte", sitz(k, (String) f[0], f[1]).getString("grund"));
        }
        assertTrue(befehle.isEmpty());
        kopplungen.rechteSetzen(k, Collections.singletonList(Kopplungen.RECHT_ZUSPERREN));
        assertEquals("Recht wie klima", "nicht_erlaubt", sitz(k, "fahrer", 1).getString("grund"));
        String cockpit = kopplungen.anlegen("Cockpit", "cockpit", jetzt);
        assertEquals("nicht_erlaubt", sitz(cockpit, "fahrer", 1).getString("grund"));
        assertTrue(befehle.isEmpty());
    }

    @Test
    public void ehrlicheGruende() throws Exception {
        String k = kopplungen.anlegen("Pixel", Kopplungen.ART_HANDY, jetzt);
        // Cloud-Weg vom Gate gesperrt, Auto läuft → auto_laeuft statt allgemein „gate“
        autoAus = false;
        ergebnis = VehicleCommandRouter.CommandResult.blocked("gesperrt");
        JSONObject r = sitz(k, "fahrer", 1);
        assertEquals(r.toString(), "auto_laeuft", r.getString("grund"));
        assertEquals(CloudPowerGate.REASON_CAR_ON, r.getString("gate"));
        // Auto aus, keine BYD-Cloud
        autoAus = true;
        ergebnis = VehicleCommandRouter.CommandResult.cloudNichtEingerichtet("Richte die BYD-Cloud ein", 1);
        r = sitz(k, "fahrer", 1);
        assertEquals(VehicleCommandRouter.CommandResult.GRUND_CLOUD_NICHT_EINGERICHTET, r.getString("grund"));
        assertEquals("/byd-cloud.html", r.getString("einrichten"));
        // lokal fehlgeschlagen bei wachem Auto
        ergebnis = VehicleCommandRouter.CommandResult.failed(VehicleCommandRouter.Path.SDK, "x", 1, null);
        assertEquals("fehlgeschlagen", sitz(k, "fahrer", 1).getString("grund"));
    }

    @Test
    public void ratenlimitGemeinsamMitDenAnderenAktionen() throws Exception {
        String k = kopplungen.anlegen("Pixel", Kopplungen.ART_HANDY, jetzt);
        int ok = 0;
        String letzter = "";
        for (int i = 0; i < 40; i++) {
            JSONObject r = sitz(k, "fahrer", i % 3);
            if (r.optBoolean("ok")) ok++;
            else letzter = r.getString("grund");
        }
        assertTrue("begrenzt: " + ok, ok < 40);
        assertEquals("ratenlimit", letzter);
    }
}
