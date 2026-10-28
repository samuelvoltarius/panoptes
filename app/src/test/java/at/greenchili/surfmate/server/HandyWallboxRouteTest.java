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
import java.util.Arrays;
import java.util.List;

/** 0.1.25 Handy: Recht „wallbox“ (Standard aus) für Start/Stop/Ladestrom, Recht „route“ (Standard an). */
public class HandyWallboxRouteTest {

    final List<JSONObject> befehle = new ArrayList<>();
    final List<String> telegram = new ArrayList<>();
    final List<String> routen = new ArrayList<>();
    JSONObject antwort;
    int status = 200;
    long jetzt = 1_800_000_000_000L;
    Kopplungen kopplungen;
    final JSONArray[] gespeichert = {new JSONArray()};
    HandyApiHandler.Umgebung vorher;

    @Before
    public void auf() throws Exception {
        vorher = HandyApiHandler.umgebung;
        kopplungen = new Kopplungen(new Kopplungen.Speicher() {
            public JSONArray lade() { return gespeichert[0]; }
            public boolean speichere(JSONArray g) { gespeichert[0] = g; return true; }
        });
        HandyApiHandler.kopplungen = kopplungen;
        antwort = new JSONObject().put("ok", true).put("uebernommen", true);
        HandyApiHandler.umgebung = new HandyApiHandler.Umgebung() {
            public VehicleCommandRouter.CommandResult ausfuehren(VehicleCommandRouter.VehicleCommand cmd) { return null; }
            public CloudPowerGate.Decision gate(String befehl) { return CloudPowerGate.evaluate(befehl, 0, jetzt, true, false, jetzt); }
            public int echo(String aktion) { return BydDataCollector.ECHO_UNBEKANNT; }
            public void telegram(String key, Object... args) { telegram.add(key + " " + Arrays.toString(args)); }
            public String pushAnlegen(String e, byte[] p, byte[] a, String l) { return "x"; }
            public void pushEntfernen(String id) { }
            public long jetzt() { return jetzt; }
            public JSONObject route(String kid, JSONObject req, long t) throws Exception {
                routen.add(kid + " " + req.optJSONObject("ziel"));
                return new JSONObject().put("ok", true).put("status", "plant");
            }
            public JSONObject routeStand(String kid) throws Exception {
                return new JSONObject().put("ok", true).put("status", "bereit");
            }
            public JSONObject[] wallbox(JSONObject b) throws Exception {
                befehle.add(b);
                return new JSONObject[]{new JSONObject().put("status", status), antwort};
            }
        };
    }

    @After
    public void ab() {
        HandyApiHandler.umgebung = vorher;
        HandyApiHandler.kopplungen = null;
    }

    JSONObject wb(String kid, JSONObject req) throws Exception {
        return HandyApiHandler.aktion(req.put("aktion", "wallbox"), kid);
    }

    @Test
    public void wallboxStandardAusRouteStandardAn() throws Exception {
        String k = kopplungen.anlegen("Pixel", Kopplungen.ART_HANDY, jetzt);
        assertFalse(kopplungen.hatRecht(k, Kopplungen.RECHT_WALLBOX));
        assertTrue(kopplungen.hatRecht(k, Kopplungen.RECHT_ROUTE));
        assertEquals("nicht_erlaubt", wb(k, new JSONObject().put("was", "start")).getString("grund"));
        assertTrue(befehle.isEmpty());
        // Bestand (vor 0.1.25 gespeicherte Rechte ohne „route“): bekommt das Standard-AN-Recht beim Lesen
        JSONObject alt = gespeichert[0].getJSONObject(0);
        alt.put("rechte", new JSONArray().put("klima")).remove("rechteStand");
        assertTrue(kopplungen.hatRecht(k, Kopplungen.RECHT_ROUTE));
        // selbst gesetzt ohne „route“ → bleibt aus
        kopplungen.rechteSetzen(k, Arrays.asList(Kopplungen.RECHT_KLIMA));
        assertFalse(kopplungen.hatRecht(k, Kopplungen.RECHT_ROUTE));
        assertEquals("nicht_erlaubt", HandyApiHandler.aktion(new JSONObject().put("aktion", "route"), k).getString("grund"));
    }

    @Test
    public void startStopStromUeberDenBegrenztenWegMitEchoUndTelegram() throws Exception {
        String k = kopplungen.anlegen("Pixel", Kopplungen.ART_HANDY, jetzt);
        kopplungen.rechteSetzen(k, Arrays.asList(Kopplungen.RECHT_KLIMA, Kopplungen.RECHT_WALLBOX));
        JSONObject r = wb(k, new JSONObject().put("was", "start"));
        assertTrue(r.toString(), r.getBoolean("ok"));
        assertTrue(r.getBoolean("bestaetigt"));
        assertEquals("modus", befehle.get(0).getString("aktion"));
        assertEquals("sofort", befehle.get(0).getString("wert"));
        assertEquals("aus", (wb(k, new JSONObject().put("was", "stop")) != null ? befehle.get(1) : null).getString("wert"));
        antwort = new JSONObject().put("ok", true).put("uebernommen", false);
        r = wb(k, new JSONObject().put("was", "strom").put("strom", 10).put("ladepunkt", "lp1"));
        assertFalse("ohne Echo nicht bestätigt", r.getBoolean("bestaetigt"));
        assertEquals(10, r.getInt("strom"));
        assertEquals("lp1", befehle.get(2).getString("ladepunkt"));
        assertEquals("jede Änderung von außen meldet Telegram", 3, telegram.size());
        assertTrue(telegram.get(2).startsWith("handy.wallbox [Pixel, strom 10 A]"));
        // ungültige Werte gehen gar nicht erst raus
        for (JSONObject x : new JSONObject[]{new JSONObject().put("was", "laden"), new JSONObject().put("was", "strom").put("strom", "10"),
                new JSONObject().put("was", "strom").put("strom", 10.5)}) {
            assertEquals(x.toString(), "werte", wb(k, x).getString("grund"));
        }
        assertEquals(3, befehle.size());
        // Fehler aus dem Wallbox-Dienst ehrlich weitergeben (z. B. Fahrsperre, nicht freigegeben, Stufe)
        // (eigene Kopplung: auch ungültige Versuche zählen beim gemeinsamen Ratenlimit mit)
        String k2 = kopplungen.anlegen("Pixel-F", Kopplungen.ART_HANDY, jetzt);
        kopplungen.rechteSetzen(k2, Arrays.asList(Kopplungen.RECHT_WALLBOX));
        antwort = new JSONObject().put("ok", false).put("grund", "fahrt");
        r = wb(k2, new JSONObject().put("was", "start"));
        assertEquals("fahrt", r.getString("grund"));
        assertEquals(3, telegram.size());
    }

    @Test
    public void ratenlimitGemeinsamUndRouteEhrlichNochNichtFrei() throws Exception {
        String k = kopplungen.anlegen("Pixel2", Kopplungen.ART_HANDY, jetzt);
        kopplungen.rechteSetzen(k, Arrays.asList(Kopplungen.RECHT_WALLBOX, Kopplungen.RECHT_ROUTE));
        String letzter = "";
        int ok = 0;
        for (int i = 0; i < 20; i++) {
            JSONObject r = wb(k, new JSONObject().put("was", "stop"));
            if (r.optBoolean("ok")) ok++;
            else letzter = r.getString("grund");
        }
        assertTrue(ok < 20);
        assertEquals("ratenlimit", letzter);
        String k2 = kopplungen.anlegen("Pixel3", Kopplungen.ART_HANDY, jetzt);
        JSONObject r = HandyApiHandler.aktion(new JSONObject().put("aktion", "route")
                .put("ziel", new JSONObject().put("lat", 48.2).put("lon", 16.37)), k2);
        assertEquals("plant", r.getString("status"));
        assertEquals(1, routen.size());
        // Stand abfragen zählt nicht zum Ratenlimit
        for (int i = 0; i < 20; i++) {
            assertEquals("bereit", HandyApiHandler.aktion(new JSONObject().put("aktion", "route_stand"), k2).getString("status"));
        }
    }

    @Test
    public void quelltextUndOberflaeche() throws Exception {
        String w = WebHandyGuardTest.quelle("java/at/greenchili/surfmate/wallbox/WallboxDienst.java");
        assertTrue("begrenzter Weg mit Herkunft handy", w.contains("return steuernBegrenzt(b, HERKUNFT_HANDY);"));
        String sys = WebHandyGuardTest.quelle("assets/web/local/system.html");
        assertTrue(sys.contains("'kamera', 'wallbox', 'route'"));
        assertTrue("Rückfrage beim Einschalten", sys.contains("system.handy_wallbox_confirm"));
        assertTrue("Häkchen beim Koppeln, Standard aus", sys.contains("<input type=\"checkbox\" data-wb=") && !sys.contains("data-wb=\"' + self.esc(a.ref) + '\" checked"));
        for (String l : new String[]{"de", "en", "fr", "it", "nl"}) {
            JSONObject d = new JSONObject(WebHandyGuardTest.quelle("assets/server-i18n/" + l + ".json"));
            assertTrue(l, d.getJSONObject("telegram").getJSONObject("handy").getString("wallbox").contains("{1}"));
        }
    }
}
