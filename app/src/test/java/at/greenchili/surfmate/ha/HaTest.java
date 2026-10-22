package at.greenchili.surfmate.ha;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.automation.AutomationAction;
import at.greenchili.surfmate.server.ExternGuard;
import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/** Home Assistant (HA_VERTRAG.md): nie VIN/Koordinaten, Zustandsabbildung, Regel A für Regeln, Streams, Erlaubnisliste. */
public class HaTest {

    @Test
    public void telemetrieOhnePositionUndVin() throws Exception {
        JSONObject roh = new JSONObject().put("soc", 81.0).put("lat", 47.8).put("lon", 13.0).put("heading", 90)
                .put("elevation", 430).put("vin", "LGX…").put("vin_hash", "x").put("utc", 123).put("is_parked", true)
                .put("liste", new JSONArray().put(1)).put("gps_fix", 1);
        JSONObject t = HaZustand.telemetrie(roh);
        assertEquals(81.0, t.getDouble("soc"), 0);
        assertTrue(t.getBoolean("is_parked"));
        for (String k : new String[]{"lat", "lon", "heading", "elevation", "vin", "vin_hash", "utc", "liste", "gps_fix"}) {
            assertFalse(k, t.has(k));
        }
        JSONArray f = HaZustand.felder(t);
        assertTrue(f.length() >= 1);
        JSONObject soc = null;
        for (int i = 0; i < f.length(); i++) if ("soc".equals(f.getJSONObject(i).getString("key"))) soc = f.getJSONObject(i);
        assertNotNull(soc);
        assertEquals("sensor", soc.getString("typ"));
        assertTrue(soc.has("erweitert"));
    }

    @Test
    public void waechterGesundheitF1() throws Exception {
        assertEquals("scharf", HaZustand.waechter("scharf", false));
        assertEquals("pausiert", HaZustand.waechter("unterdrueckt", false));
        assertEquals("startet", HaZustand.waechter("startet", false));
        assertEquals("fehler", HaZustand.waechter("startet", true));
        assertEquals("aus", HaZustand.waechter(null, false));
        JSONObject g = HaZustand.gesundheit(new JSONObject().put("ok", true).put("befunde", new JSONArray()));
        assertEquals("ok", g.getString("zustand"));
        g = HaZustand.gesundheit(new JSONObject().put("ok", false).put("befunde", new JSONArray().put("bild")));
        assertEquals("warnung", g.getString("zustand"));
        assertEquals("bild", g.getJSONArray("punkte").getJSONObject(0).getString("id"));
        g = HaZustand.gesundheit(new JSONObject().put("ok", false).put("befunde", new JSONArray().put("a").put("b")));
        assertEquals("fehler", g.getString("zustand"));
        assertTrue(HaZustand.gesundheit(null).isNull("zustand"));
        assertEquals("reicht", HaZustand.f1(new JSONObject().put("aktiv", true).put("stufe", "passt")).getString("stufe"));
        assertEquals("reicht_nicht", HaZustand.f1(new JSONObject().put("aktiv", true).put("stufe", "reicht_nicht")).getString("stufe"));
        JSONObject k = HaZustand.f1(new JSONObject().put("aktiv", false).put("grund", "steht"));
        assertEquals("keine_route", k.getString("stufe"));
        assertEquals("steht", k.getString("grund"));
        assertEquals("unbekannt", HaZustand.f1(null).getString("stufe"));
    }

    @Test
    public void diffNurGeaendertes() throws Exception {
        JSONObject a = new JSONObject().put("telemetrie", new JSONObject().put("soc", 80).put("km", 10))
                .put("waechter", new JSONObject().put("zustand", "aus"));
        JSONObject b = new JSONObject().put("telemetrie", new JSONObject().put("soc", 79).put("km", 10))
                .put("waechter", new JSONObject().put("zustand", "aus"));
        JSONObject d = HaZustand.diff(a, b);
        assertEquals(79, d.getJSONObject("telemetrie").getInt("soc"));
        assertFalse(d.getJSONObject("telemetrie").has("km"));
        assertFalse(d.has("waechter"));
        assertEquals(0, HaZustand.diff(b, b).length());
    }

    static AutomationAction a(String typ, AutomationAction... kinder) {
        AutomationAction x = new AutomationAction(typ, new HashMap<>());
        if (kinder.length > 0) x = x.withChildren(Arrays.asList(kinder), Collections.emptyList());
        return x;
    }

    @Test
    public void regelnNurPanoptesIntern() {
        List<AutomationAction> leer = Collections.emptyList();
        assertNull(HaRegeln.grund(Arrays.asList(a("notification"), a("pause"), a("mqttPublish")), leer));
        assertEquals("fahrzeugaktion", HaRegeln.grund(Arrays.asList(a("notification"), a("vehicle-control")), leer));
        assertEquals("fahrzeugaktion", HaRegeln.grund(Collections.singletonList(a("if", a("notification"), a("radio"))), leer));
        assertEquals("fahrzeugaktion", HaRegeln.grund(leer, Collections.singletonList(a("vehicle-control"))));
        assertEquals("unsicher", HaRegeln.grund(Collections.singletonList(a("shell")), leer));
        assertEquals("unsicher", HaRegeln.grund(Collections.singletonList(a("api")), leer));
        assertEquals("unsicher", HaRegeln.grund(Collections.singletonList(a("actionGroup")), leer));
        assertEquals("unsicher", HaRegeln.grund(Collections.singletonList(a("brandneu")), leer));
        assertEquals("unsicher", HaRegeln.grund(Collections.singletonList(a("loop", a("automationControl"))), leer));
    }

    @Test
    public void streamsBegrenztUndWiderrufSchliesst() throws Exception {
        HaEreignisse.Abo x1 = HaEreignisse.abonnieren("k-1");
        HaEreignisse.Abo x2 = HaEreignisse.abonnieren("k-1");
        assertNotNull(x1);
        assertNotNull(x2);
        assertNull(HaEreignisse.abonnieren("k-1"));       // höchstens 2 je Kopplung
        HaEreignisse.Abo y = HaEreignisse.abonnieren("k-2");
        HaEreignisse.melde("ankunft", new JSONObject().put("ort", "Zuhause").put("zeit", 1));
        String[] e = y.naechstes(100);
        assertEquals("ankunft", e[0]);
        assertFalse(e[1].contains("lat"));
        HaEreignisse.schliessen("k-1");
        assertTrue(x1.geschlossen());
        assertTrue(x2.geschlossen());
        assertFalse(y.geschlossen());
        assertNotNull(HaEreignisse.abonnieren("k-1"));    // wieder frei
        HaEreignisse.schliessen("k-1");
        HaEreignisse.abmelden(y);
        assertEquals(0, HaEreignisse.anzahl());
    }

    @Test
    public void waechterEreignisArt() throws Exception {
        assertEquals("person", HaEreignisse.artAus("surveillance.motion", new JSONObject().put("personCount", 1)));
        assertEquals("bewegung", HaEreignisse.artAus("surveillance.motion", new JSONObject()));
        assertEquals("bewegung", HaEreignisse.artAus("surveillance.proximity", null));
        assertNull(HaEreignisse.artAus("surveillance.motion.critical", null));   // Stufen-Kopie nicht doppelt
        assertNull(HaEreignisse.artAus("charging.done", null));
        assertTrue(HaDienst.durchlassen("t-art", 1_000_000));
        assertFalse(HaDienst.durchlassen("t-art", 1_005_000));
        assertTrue(HaDienst.durchlassen("t-art", 1_030_000));
    }

    @Test
    public void erlaubnislisteGenau() {
        assertTrue(ExternGuard.erlaubt("GET", "/api/ha/v1/zustand"));
        assertTrue(ExternGuard.erlaubt("GET", "/api/ha/v1/regeln"));
        assertTrue(ExternGuard.erlaubt("GET", "/api/ha/v1/ereignisse"));
        assertTrue(ExternGuard.erlaubt("POST", "/api/ha/v1/aktion"));
        assertFalse(ExternGuard.erlaubt("POST", "/api/ha/v1/zustand"));
        assertFalse(ExternGuard.erlaubt("GET", "/api/ha/v1/aktion"));
        assertFalse(ExternGuard.erlaubt("POST", "/api/ha/v1/irgendwas"));
    }

    /** Aktion: harte Erlaubnisliste im Server, ziel nie ins Navi, Regel mit Fahrzeugaktion 403. */
    @Test
    public void aktionVertrag() throws Exception {
        String h = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/HaApiHandler.java"));
        assertTrue(h.contains("case \"waechter\""));
        assertTrue(h.contains("case \"ruhezeit\""));
        assertTrue(h.contains("case \"ziel\""));
        assertTrue(h.contains("case \"regel\""));
        assertTrue(h.contains("return fehler(400, \"unbekannt\")"));
        assertTrue(h.contains("if (g != null) return fehler(403, g)"));
        assertFalse(h.contains("VehicleCommandRouter"));
        assertFalse(h.contains("/api/vehicle"));
        // Selbst-Widerruf nur per Bearer, nie per Cookie; Login ohne kid → 400
        String a = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/AuthApiHandler.java"));
        assertTrue(a.contains("\"/auth/kopplung/selbst-widerrufen\""));
        assertTrue(a.contains("HttpResponse.sendJson(out, 401"));
        assertTrue(a.contains("\"keine_kopplung\""));
        // Widerruf schließt Streams
        String k = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/auth/Kopplungen.java"));
        assertTrue(k.contains("HaEreignisse.schliessen(id)"));
    }

    @Test
    public void zustandOhneKoordinatenAusserFreigabe() throws Exception {
        String h = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/HaApiHandler.java"));
        assertTrue(h.contains("positionGewuenscht && positionErlaubt()"));
        assertTrue(h.contains("HaZustand.telemetrie(roh)"));
    }
}
