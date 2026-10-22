package at.greenchili.surfmate.reichweite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** F1 Laufzeit: Plan aus dem Planer-Vertrag, Antwort von /api/navi/unterwegs, Verdrahtung. */
public class RangeWatchDienstTest {

    /** Beispiel nach API_FUER_SURFMATE.md §3 (auto-abholen / meine-route). */
    static final String PLAN = "{\"titel\":\"Salzburg → Wien\",\"start\":{\"lat\":47.8,\"lon\":13.04,\"name\":\"Salzburg\"},"
            + "\"ziel\":{\"lat\":48.2,\"lon\":16.37,\"name\":\"Wien\"},\"km\":302.7,\"minuten\":161,"
            + "\"soc_start\":80,\"soc_ankunft\":18,\"fahrzeug\":\"Dolphin Surf\",\"abfahrt\":\"\","
            + "\"stopps\":[{\"name\":\"Ionity Ansfelden\",\"lat\":48.2,\"lon\":14.3,\"km\":120.4,\"kw\":350,\"cpo\":\"Ionity\","
            + "\"soc_an\":24,\"soc_ab\":70,\"dauer_min\":25,\"kwh\":19.8,\"preis\":12.1}],"
            + "\"geometrie\":[[47.8,13.04],[48.0,13.8],[48.2,14.3],[48.2,15.3],[48.2,16.37]],"
            + "\"erstellt\":\"2026-10-21T08:00:00Z\",\"schon_abgeholt\":false,\"auto\":null}";

    @Test
    public void readsPlannerPlan() throws Exception {
        RangeWatch.Plan p = PlanLeser.lies(new JSONObject(PLAN), Double.NaN);
        assertEquals(80, p.socStart, 0);
        assertEquals(18, p.socAnkunft, 0);
        assertEquals(1, p.stopps.size());
        assertEquals("Ionity Ansfelden", p.stopps.get(0).name);
        assertEquals("Wien", p.zielName);
        assertEquals(PlanLeser.RESERVE_STANDARD, p.reserve, 0);
        assertTrue(p.zielKm > 200);
        assertEquals("2026-10-21T08:00:00Z|Salzburg → Wien", PlanLeser.kennung(new JSONObject(PLAN)));
    }

    @Test
    public void reserveOverrideAndBadPlans() throws Exception {
        assertEquals(15, PlanLeser.lies(new JSONObject(PLAN), 15).reserve, 0);
        assertEquals("0 = Planwert", 10, PlanLeser.lies(new JSONObject(PLAN), 0).reserve, 0);
        assertNull(PlanLeser.lies(null, Double.NaN));
        assertNull(PlanLeser.lies(new JSONObject("{\"geometrie\":[[1,2]],\"soc_start\":80,\"soc_ankunft\":20}"), Double.NaN));
        assertNull(PlanLeser.lies(new JSONObject("{\"geometrie\":[[1,2],[1,3]],\"soc_ankunft\":20}"), Double.NaN));
    }

    @Test
    public void answerFormatForTheWebField() throws Exception {
        RangeWatch.Lage l = new RangeWatch.Lage();
        l.aktiv = true;
        l.stufe = RangeWatch.Stufe.REICHT_NICHT;
        l.prognoseProzent = 7.26; l.sollProzent = 40.04; l.istProzent = 33; l.delta = -7.04;
        l.kmGefahren = 88.88; l.faktor = 1.2345; l.reserveWirksam = 13;
        l.naechsterName = "Ionity Ansfelden"; l.naechsterKm = 31.51; l.naechsterIstZiel = false;
        JSONObject o = RangeWatchDienst.antwort(l);
        assertTrue(o.getBoolean("aktiv"));
        assertEquals("reicht_nicht", o.getString("stufe"));
        assertEquals(7.3, o.getDouble("prognoseProzent"), 0);
        assertEquals(40.0, o.getDouble("sollProzent"), 0);
        assertEquals(33.0, o.getDouble("istProzent"), 0);
        assertEquals(88.9, o.getDouble("kmGefahren"), 0);
        assertEquals(1.235, o.getDouble("faktor"), 0);
        assertEquals("Ionity Ansfelden", o.getJSONObject("naechsterStopp").getString("name"));
        assertEquals(31.5, o.getJSONObject("naechsterStopp").getDouble("km"), 0);
        assertFalse(o.has("lat"));
        assertEquals(0, o.getJSONArray("verlauf").length());
        assertFalse(RangeWatchDienst.antwort(new RangeWatch.Lage()).getBoolean("aktiv"));
        assertEquals("nicht_auf_route", RangeWatchDienst.antwort(new RangeWatch.Lage()).getString("grund"));
    }

    @Test
    public void wiringAndNoPositionToPlanner() throws Exception {
        String d = new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/reichweite/RangeWatchDienst.java")), StandardCharsets.UTF_8);
        assertTrue("Schlüssel nur im Header", d.contains("c.setRequestProperty(token[0], token[1])"));
        int melden = d.indexOf("private void stoppMelden(");
        String body = d.substring(melden, d.indexOf("static JSONObject antwort(", melden));
        assertFalse("keine Position zum Planer", body.contains("getLatitude") || body.contains("\"lat\""));
        String navi = new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/server/NaviApiHandler.java")), StandardCharsets.UTF_8);
        assertTrue(navi.contains("path.equals(\"/api/navi/unterwegs\")"));
        String cam = new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/daemon/CameraDaemon.java")), StandardCharsets.UTF_8);
        assertTrue(cam.contains("RangeWatchDienst.start();"));
    }
}
