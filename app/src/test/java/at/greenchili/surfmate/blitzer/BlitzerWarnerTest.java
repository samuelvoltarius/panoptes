package at.greenchili.surfmate.blitzer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.server.ExternGuard;
import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Blitzer-Warnung: nur AT, Testschalter 24 h, Kurs ±30°, Ruhegrenze, Datensatz. */
public class BlitzerWarnerTest {

    static final long T = 1_000_000_000L;
    static final double NORD = 0.0;

    /** Blitzer ~445 m nördlich von (lat, lon). */
    static BlitzerWarner.Blitzer vorne(String land, double lat, double lon, int vmax) {
        return new BlitzerWarner.Blitzer(land + ":1", land, lat + 0.0040, lon, vmax);
    }

    @Test
    public void austriaWarnsWithDistanceAndLimit() {
        double lat = 48.30, lon = 14.28;   // Linz
        BlitzerWarner w = new BlitzerWarner();
        BlitzerWarner.Warnung x = w.pruefe(T, lat, lon, NORD, 90, false,
                Collections.singletonList(vorne("at", lat, lon, 80)));
        assertNotNull(x);
        assertEquals(450, x.meter);
        assertEquals(80, x.blitzer.maxspeed);
    }

    @Test
    public void germanyIsOffEvenWithAustrianData() {
        double lat = 48.14, lon = 11.58;   // München
        BlitzerWarner w = new BlitzerWarner();
        assertNull(w.pruefe(T, lat, lon, NORD, 90, false, Collections.singletonList(vorne("de", lat, lon, 50))));
        assertNull(w.pruefe(T, lat, lon, NORD, 90, false, Collections.singletonList(vorne("at", lat, lon, 50))));
        assertFalse(BlitzerWarner.erlaubt(lat, lon, false));
    }

    @Test
    public void onlyAustrianCamerasCountInAustria() {
        double lat = 48.30, lon = 14.28;
        assertNull("deutscher Datensatz zählt in AT ohne Test nicht", new BlitzerWarner().pruefe(T, lat, lon, NORD, 90, false,
                Collections.singletonList(vorne("de", lat, lon, 50))));
    }

    @Test
    public void testSwitchAllCountriesAndExpires() {
        double lat = 48.14, lon = 11.58;
        long bis = T + 24 * 3600_000L;
        assertTrue(BlitzerWarner.testAktiv(bis, T));
        assertNotNull(new BlitzerWarner().pruefe(T, lat, lon, NORD, 90, BlitzerWarner.testAktiv(bis, T),
                Collections.singletonList(vorne("de", lat, lon, 50))));
        assertFalse("abgelaufen", BlitzerWarner.testAktiv(bis, bis));
        assertFalse("nicht gesetzt", BlitzerWarner.testAktiv(0, T));
        assertFalse("länger als 24 h zählt nicht (von Hand verstellt)", BlitzerWarner.testAktiv(T + 30 * 3600_000L, T));
        assertNull(new BlitzerWarner().pruefe(T, lat, lon, NORD, 90, BlitzerWarner.testAktiv(bis, bis + 1),
                Collections.singletonList(vorne("de", lat, lon, 50))));
    }

    @Test
    public void courseFilterPlusMinus30Degrees() {
        double lat = 48.30, lon = 14.28;
        List<BlitzerWarner.Blitzer> b = Collections.singletonList(vorne("at", lat, lon, 50));
        assertNotNull("25° daneben geht", new BlitzerWarner().pruefe(T, lat, lon, 25, 90, false, b));
        assertNotNull("335° geht", new BlitzerWarner().pruefe(T, lat, lon, 335, 90, false, b));
        assertNull("40° daneben nicht", new BlitzerWarner().pruefe(T, lat, lon, 40, 90, false, b));
        assertNull("hinter mir nicht", new BlitzerWarner().pruefe(T, lat, lon, 180, 90, false, b));
        assertNull("Kurs unbekannt", new BlitzerWarner().pruefe(T, lat, lon, -1, 90, false, b));
    }

    @Test
    public void quietLimitPerCameraSpeedAndRange() {
        double lat = 48.30, lon = 14.28;
        List<BlitzerWarner.Blitzer> b = Collections.singletonList(vorne("at", lat, lon, 50));
        BlitzerWarner w = new BlitzerWarner();
        assertNotNull(w.pruefe(T, lat, lon, NORD, 90, false, b));
        assertNull("Ruhegrenze", w.pruefe(T + 5_000, lat, lon, NORD, 90, false, b));
        assertNotNull("nach 10 min wieder", w.pruefe(T + BlitzerWarner.RUHE_MS, lat, lon, NORD, 90, false, b));
        assertNull("Schritttempo", new BlitzerWarner().pruefe(T, lat, lon, NORD, 10, false, b));
        assertNull("bei 30 km/h nur 300 m Vorlauf", new BlitzerWarner().pruefe(T, lat, lon, NORD, 30, false, b));
        BlitzerWarner.Blitzer weit = new BlitzerWarner.Blitzer("at:w", "at", lat + 0.02, lon, 0);
        assertNull("2 km zu weit", new BlitzerWarner().pruefe(T, lat, lon, NORD, 130, false, Collections.singletonList(weit)));
        BlitzerWarner.Blitzer nah = new BlitzerWarner.Blitzer("at:n", "at", lat + 0.0018, lon, 0);
        BlitzerWarner.Warnung x = new BlitzerWarner().pruefe(T, lat, lon, NORD, 90, false, Arrays.asList(vorne("at", lat, lon, 50), nah));
        assertEquals("der nächste zuerst", "at:n", x.blitzer.id);
    }

    @Test
    public void borderPolygon() {
        double[][] at = {{48.21, 16.37}, {48.20, 15.62}, {47.27, 11.39}, {47.07, 15.44}, {46.62, 14.31}, {48.31, 14.29},
                {47.50, 9.75}, {48.26, 13.04}, {48.457, 13.432}, {46.97, 10.19}, {47.58, 12.17}};
        double[][] aus = {{48.14, 11.58}, {47.57, 10.70}, {47.71, 10.31}, {47.86, 12.12}, {48.57, 13.46}, {47.49, 11.09},
                {48.15, 17.11}, {46.50, 11.35}, {46.05, 14.51}, {47.37, 8.54}, {47.14, 9.52}, {47.68, 16.59},
                {47.66, 9.48}, {46.94, 10.36}, {48.27, 13.02}, {46.51, 13.58}, {46.55, 15.65}, {48.86, 16.05}};
        for (double[] p : at) assertTrue(p[0] + "," + p[1], BlitzerWarner.inOesterreich(p[0], p[1]));
        for (double[] p : aus) assertFalse(p[0] + "," + p[1], BlitzerWarner.inOesterreich(p[0], p[1]));
    }

    @Test
    public void readsDataset() {
        String json = "{\"land\":\"at\",\"stand\":\"2026-10-21\",\"quelle\":\"OSM\",\"punkte\":[[48.3,14.28,80],[48.4,14.33],[999,1,50],[48.2,14.2,-5]]}";
        List<BlitzerWarner.Blitzer> l = BlitzerDaten.lies(json, "at");
        assertEquals(3, l.size());
        assertEquals(80, l.get(0).maxspeed);
        assertEquals(0, l.get(1).maxspeed);
        assertEquals("at:4830000:1428000", l.get(0).id);
        assertNull("falsches Land", BlitzerDaten.lies(json, "de"));
        assertNull(BlitzerDaten.lies("kaputt", "at"));
    }

    @Test
    public void wiringTestSwitchLoginOnlyAndTexts() throws Exception {
        assertTrue(ExternGuard.blocks("GET", "/api/test/blitzer"));
        assertTrue(ExternGuard.blocks("PUT", "/api/test/blitzer"));
        String eh = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/EinstellungenApiHandler.java"));
        int g = eh.indexOf("pathOnly.equals(\"/api/test/blitzer\") && method.equals(\"GET\")");
        int p = eh.indexOf("pathOnly.equals(\"/api/test/blitzer\") && (method.equals(\"PUT\")");
        assertTrue(g > 0 && p > 0);
        assertTrue(eh.substring(g, g + 200).contains("if (AuthMiddleware.isExternRequest()) return verboten(out);"));
        assertTrue(eh.substring(p, p + 200).contains("if (AuthMiddleware.isExternRequest()) return verboten(out);"));
        String kat = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/einstellungen/Katalog.java"));
        assertFalse("Testschalter nicht im Schema", kat.contains("speedcam.testBis"));
        // 0.1.9.1: speedcam.ansage geht in speedcam.ton auf (Standard aus)
        assertTrue(kat.contains("blitzTon.standard = \"aus\""));
        String d = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/blitzer/BlitzerDienst.java"));
        assertTrue("Ton nur mit Schalter", d.contains("if (TON_ANSAGE.equals(ton))"));
        assertFalse("keine Position an den Server", d.contains("lat=") || d.contains("?lat"));
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject n = new JSONObject(Quelltext.lies(Paths.get("src/main/assets/server-i18n/" + l + ".json")))
                    .getJSONObject("notifications");
            assertTrue(l, n.getString("blitzer_titel").contains("{0}"));
            assertTrue(l, n.getString("blitzer_tempo").contains("{0}"));
        }
    }
}
