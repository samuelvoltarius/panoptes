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
import java.util.Arrays;
import java.util.List;

/** 0.1.38.6: {@code plan_vorschau} braucht das Recht „route“ (kein neues Recht), Stand zählt nicht zum Ratenlimit. */
public class HandyPlanVorschauRechtTest {

    final List<String> vorschauen = new ArrayList<>();
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
            public VehicleCommandRouter.CommandResult ausfuehren(VehicleCommandRouter.VehicleCommand cmd) { return null; }
            public CloudPowerGate.Decision gate(String befehl) { return CloudPowerGate.evaluate(befehl, 0, jetzt, true, false, jetzt); }
            public int echo(String aktion) { return BydDataCollector.ECHO_UNBEKANNT; }
            public void telegram(String key, Object... args) { }
            public String pushAnlegen(String e, byte[] p, byte[] a, String l) { return "x"; }
            public void pushEntfernen(String id) { }
            public long jetzt() { return jetzt; }
            public JSONObject planVorschau(String kid, JSONObject req, long t) throws Exception {
                vorschauen.add(kid + " " + req.optJSONObject("ziel"));
                return new JSONObject().put("ok", true).put("status", "plant");
            }
            public JSONObject planVorschauStand(String kid) throws Exception {
                return new JSONObject().put("ok", true).put("status", "bereit");
            }
        };
    }

    @After
    public void ab() {
        HandyApiHandler.umgebung = vorher;
        HandyApiHandler.kopplungen = null;
    }

    @Test
    public void gleichesRechtWieDieRouteKeinNeues() throws Exception {
        String k = kopplungen.anlegen("Pixel", Kopplungen.ART_HANDY, jetzt);
        JSONObject req = new JSONObject().put("ziel", new JSONObject().put("lat", 52.5).put("lon", 13.4));
        assertEquals("plant", HandyApiHandler.aktion(new JSONObject(req.toString()).put("aktion", "plan_vorschau"), k).getString("status"));
        assertEquals(1, vorschauen.size());
        kopplungen.rechteSetzen(k, Arrays.asList(Kopplungen.RECHT_KLIMA));          // „route“ weg
        assertEquals("nicht_erlaubt", HandyApiHandler.aktion(new JSONObject(req.toString()).put("aktion", "plan_vorschau"), k).getString("grund"));
        assertEquals("nicht_erlaubt", HandyApiHandler.aktion(new JSONObject().put("aktion", "plan_vorschau_stand"), k).getString("grund"));
        assertEquals(1, vorschauen.size());
    }

    @Test
    public void standZaehltNichtZumRatenlimitUndBefehlsLogKenntBeide() throws Exception {
        String k = kopplungen.anlegen("Pixel", Kopplungen.ART_HANDY, jetzt);
        for (int i = 0; i < 30; i++) {
            assertEquals("bereit", HandyApiHandler.aktion(new JSONObject().put("aktion", "plan_vorschau_stand"), k).getString("status"));
        }
        assertTrue(BefehlsLog.AKTIONEN.contains("plan_vorschau"));
        assertTrue(BefehlsLog.AKTIONEN.contains("plan_vorschau_stand"));
    }
}
