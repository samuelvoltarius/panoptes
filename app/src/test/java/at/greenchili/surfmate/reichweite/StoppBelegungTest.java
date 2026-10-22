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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** F1: Live-Belegung des nächsten Stopps (Vertrag chargers-live v2, POST umkreis). */
public class StoppBelegungTest {

    static final double SLAT = 48.2010, SLON = 14.3020;

    static String station(String name, double lat, double lon, double kw, int frei, int lp, boolean bekannt, int belegt) {
        return "{\"name\":\"" + name + "\",\"lat\":" + lat + ",\"lon\":" + lon + ",\"kw\":" + kw + ",\"frei\":" + frei
                + ",\"ladepunkte\":" + lp + ",\"status_bekannt\":" + bekannt + ",\"belegt_n\":" + belegt
                + ",\"defekt_n\":0,\"live_alter_s\":30}";
    }

    @Test
    public void requestIsAroundTheStopRoundedAndCoversRounding() throws Exception {
        JSONObject a = StoppBelegung.anfrage(SLAT, SLON);
        assertEquals(48.2, a.getDouble("lat"), 1e-9);
        assertEquals(14.3, a.getDouble("lon"), 1e-9);
        assertEquals(200, a.getInt("limit"));
        // schlimmster Rundungsversatz (0,025° in beiden Richtungen) + 2 km liegt im Radius
        double versatz = RangeWatch.distKm(48.0, 14.0, 48.025, 14.025);
        assertTrue(versatz + 2.0 <= a.getDouble("radius"));
    }

    @Test
    public void fullStopWithNearestFreeAlternative() throws Exception {
        JSONObject res = new JSONObject("{\"stationen\":["
                + station("Ionity Ansfelden", SLAT + 0.0003, SLON, 350, 0, 6, true, 6) + ","
                + station("Langsam AC", SLAT + 0.01, SLON, 11, 2, 2, true, 0) + ","
                + station("Smatrics Haid", SLAT + 0.02, SLON, 150, 1, 4, true, 3) + ","
                + station("Fern", SLAT + 0.2, SLON, 300, 4, 4, true, 0) + "]}");
        StoppBelegung b = StoppBelegung.auswerten(res, SLAT, SLON, 175);
        assertTrue(b.gefunden);
        assertTrue(b.voll);
        assertEquals(6, b.belegt);
        assertNull("keine Ausweich-Säule mit ≥ 175 kW in 5 km", b.ausweichName);
        StoppBelegung c = StoppBelegung.auswerten(res, SLAT, SLON, 100);
        assertEquals("Smatrics Haid", c.ausweichName);
        assertEquals(2.2, c.ausweichKm, 0.1);
        JSONObject j = c.alsJson();
        assertTrue(j.getBoolean("voll"));
        assertEquals("Smatrics Haid", j.getJSONObject("ausweich").getString("name"));
    }

    @Test
    public void unknownStatusOrFarStationIsNeverFull() throws Exception {
        JSONObject unbekannt = new JSONObject("{\"stationen\":["
                + station("Ionity", SLAT, SLON, 350, 0, 6, false, 0) + "]}");
        StoppBelegung u = StoppBelegung.auswerten(unbekannt, SLAT, SLON, 100);
        assertTrue(u.gefunden);
        assertFalse("ohne Live-Status nie „voll“", u.voll);
        JSONObject weit = new JSONObject("{\"stationen\":["
                + station("Andere", SLAT + 0.005, SLON, 350, 0, 6, true, 6) + "]}");
        assertFalse("> 150 m: nicht der Planstopp", StoppBelegung.auswerten(weit, SLAT, SLON, 100).gefunden);
        assertFalse(StoppBelegung.auswerten(null, SLAT, SLON, 100).gefunden);
    }

    @Test
    public void fullOnlyCountsForThatStop() {
        double lat = 47.8;
        double kmProGrad = RangeWatch.distKm(lat, 13.0, lat, 14.0);
        List<double[]> linie = new ArrayList<>();
        for (int km = 0; km <= 200; km += 5) linie.add(new double[] {lat, 13.0 + km / kmProGrad});
        RangeWatch.Plan p = new RangeWatch.Plan(linie, Arrays.asList(
                new RangeWatch.Stopp("A", 80, 40, 80), new RangeWatch.Stopp("B", 150, 40, 80)), 90, 50, 10, "Ziel");
        RangeWatch w = new RangeWatch(p);
        RangeWatch.Lage l = w.messung(0, lat, 13.0 + 50 / kmProGrad, p.soll(50), 15, "A");
        assertEquals(RangeWatch.Stufe.REICHT_NICHT, l.stufe);
        RangeWatch w2 = new RangeWatch(p);
        RangeWatch.Lage l2 = w2.messung(0, lat, 13.0 + 100 / kmProGrad, p.soll(100), 15, "A");
        assertEquals("B", l2.naechsterName);
        assertEquals("A ist vorbei: „voll“ gilt nicht für B", RangeWatch.Stufe.PASST, l2.stufe);
    }

    @Test
    public void wiringNeverSendsCarPosition() throws Exception {
        String d = new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/reichweite/RangeWatchDienst.java")), StandardCharsets.UTF_8);
        int s = d.indexOf("private void belegungHolen(");
        String body = d.substring(s, d.indexOf("private void stoppMelden(", s));
        assertTrue(body.contains("StoppBelegung.anfrage(ziel.lat, ziel.lon)"));
        assertTrue("öffentlich, ohne Schlüssel", body.contains("http(\"POST\", \"umkreis\", null,"));
        assertFalse("nie die Position des Autos", body.contains("getLatitude") || body.contains("g.get"));
        assertTrue(body.contains("l.naechsterKm > BELEGUNG_AB_KM"));
    }
}
