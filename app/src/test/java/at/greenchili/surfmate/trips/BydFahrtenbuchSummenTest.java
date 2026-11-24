package at.greenchili.surfmate.trips;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** 0.1.43.21: an Home Assistant nur Summen aus dem BYD-Fahrtenbuch, nie einzelne Fahrten. */
public class BydFahrtenbuchSummenTest {

    @Test
    public void nurSummenImZeitraum() throws Exception {
        List<BydFahrtenbuch.Fahrt> f = Arrays.asList(
                new BydFahrtenbuch.Fahrt(1, 1_000, 2_000, 1, 10.0, 1.5),
                new BydFahrtenbuch.Fahrt(2, 5_000, 6_000, 1, 30.0, 4.5),
                new BydFahrtenbuch.Fahrt(3, 9_000, 9_500, 1, 0.0, -0.2));
        JSONObject s = BydFahrtenbuch.summen(f, 1_000, 9_000);
        assertEquals(2, s.getInt("fahrten"));
        assertEquals(40.0, s.getDouble("km"), 1e-9);
        assertEquals(6.0, s.getDouble("kwh"), 1e-9);
        assertEquals(15.0, s.getDouble("kwh_je_100km"), 1e-9);
        assertEquals("nur diese vier Felder", 4, s.length());
        assertFalse(s.toString().contains("5000"));
    }

    @Test
    public void leerOderZuKurz() throws Exception {
        assertTrue(BydFahrtenbuch.summen(null, 0, 1).isNull("kwh_je_100km"));
        JSONObject s = BydFahrtenbuch.summen(Arrays.asList(new BydFahrtenbuch.Fahrt(1, 10, 20, 1, 0.4, 0.1)), 0, 100);
        assertEquals(1, s.getInt("fahrten"));
        assertTrue("unter 1 km kein Verbrauch", s.isNull("kwh_je_100km"));
    }
}
