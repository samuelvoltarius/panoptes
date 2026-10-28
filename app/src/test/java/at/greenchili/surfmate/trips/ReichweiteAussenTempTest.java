package at.greenchili.surfmate.trips;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 0.1.26.1 (Alfreds Auto 28.10. 22:56, geparkt, draußen ~10 °C): /api/trips/range nahm den Topf „city_hot_high“.
 * Deploy-Auszug: Snapshot ohne Außentemperatur (geparkt), HA-Außensensor 10,0 seit 22:08, letzte Fahrt 11 °C.
 * Der Endpunkt fiel auf den Rohwert von getOutCarTemperature zurück (Deploy 23:19, Zündung aus: „195“) – OHNE die
 * Prüfung −50…60, die TripDetector
 * und der Collector haben. 195 > 25 → „hot“.
 */
public class ReichweiteAussenTempTest {

    @Test
    public void fall2256GeparktOhneSnapshot() {
        // vorher: extTemp = Rohwert 195 (am Auto gelesen) → „hot“
        assertEquals("hot", RangeEstimator.computeBucketKey(0, 195, 85).split("_")[1]);
        // jetzt: unplausibler Rohwert zählt nicht, die letzte Fahrt (11 °C, 20:08) gilt
        Integer t = TripApiHandler.aussenTemp(Double.NaN, 195, 11);
        assertEquals(Integer.valueOf(11), t);
        assertEquals("11 °C ist mild", "mild", RangeEstimator.computeBucketKey(0, t, 85).split("_")[1]);
    }

    @Test
    public void reihenfolgeUndGrenzen() {
        assertEquals("Snapshot zuerst", Integer.valueOf(10), TripApiHandler.aussenTemp(10.2, 30, 11));
        assertEquals("unplausibler Snapshot → Rohwert", Integer.valueOf(9), TripApiHandler.aussenTemp(255, 9, 11));
        assertEquals("Rohwert 0 = nicht gelesen (wie im Collector)", Integer.valueOf(11), TripApiHandler.aussenTemp(Double.NaN, 0, 11));
        assertNull("nichts da: unbekannt, nicht raten", TripApiHandler.aussenTemp(Double.NaN, null, null));
        assertNull(TripApiHandler.aussenTemp(Double.NaN, -128, null));
        assertEquals(6L * 3600_000L, TripApiHandler.LETZTE_FAHRT_TEMP_MS);
    }

    @Test
    public void wetterAlsRueckfallMitQuelle() {
        String[] q = new String[1];
        assertEquals(Integer.valueOf(10), TripApiHandler.aussenTemp(10.4, null, 8.0, 11, q));
        assertEquals("auto", q[0]);
        // Fall 22:56 mit Einwilligung und frischem Wetter: 195 vom Auto zählt nicht, Wetter vor letzter Fahrt
        assertEquals(Integer.valueOf(8), TripApiHandler.aussenTemp(Double.NaN, 195, 8.2, 11, q));
        assertEquals("wetter", q[0]);
        // ohne Einwilligung bzw. älter als 1 h liefert der Zwischenspeicher NaN → letzte Fahrt
        assertEquals(Integer.valueOf(11), TripApiHandler.aussenTemp(Double.NaN, 195, Double.NaN, 11, q));
        assertEquals("fahrt", q[0]);
        assertNull(TripApiHandler.aussenTemp(Double.NaN, 195, Double.NaN, null, q));
        assertNull(q[0]);
    }

    @Test
    public void verdrahtung() throws Exception {
        String h = new String(Files.readAllBytes(quelle("assets/web/local/index.html")), "UTF-8");
        String s = new String(Files.readAllBytes(quelle("java/at/greenchili/surfmate/trips/TripApiHandler.java")), "UTF-8");
        assertTrue("Rohwert nicht mehr direkt in extTemp", !s.contains("extTemp = (Integer) getTemp.invoke(instrumentDevice);"));
        assertTrue(s.contains("Integer aussen = aussenTemp(snapshotTemp, legacyTemp, wetter, letzteFahrt, quelle);"));
        assertTrue("Wetter nur mit Einwilligung", s.contains("if (at.greenchili.surfmate.weather.WeatherTemperature.onlineErlaubt()) {"));
        assertTrue("keine neue Abfrage von hier", !s.contains("WeatherTemperature.fetchNow") && !s.contains("WeatherTemperature.refreshAsync"));
        assertTrue("Quelle in der Anzeige", h.contains("home.range_quelle_wetter"));
        assertTrue(s.contains("estimate.tempBekannt = aussen != null;"));
        assertTrue("Anzeige REGEL DAU", h.contains("home.range_heute") && h.contains("home.range_auto_klein"));
        assertTrue("keine Spanne ohne Rückprüfung", h.contains("r.bandAusRueckpruefung"));
    }

    private static java.nio.file.Path quelle(String rel) {
        java.nio.file.Path p = Paths.get("src/main/" + rel);
        return Files.isRegularFile(p) ? p : Paths.get("app/src/main/" + rel);
    }
}
