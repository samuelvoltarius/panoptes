package at.greenchili.surfmate.byd;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 0.1.22 Gurtsensor-Log-Spam: nur bei Änderung (frühestens 30 s), sonst höchstens alle 30 min. */
public class GurtLogEntprellungTest {

    @Test
    public void nurBeiAenderungSonstSelten() {
        long t = 1_000_000_000L;
        assertTrue("erste Zeile", BydDataCollector.gurtLogFaellig("1|1", "", t, 0));
        assertFalse("gleich nach 30 s: nichts", BydDataCollector.gurtLogFaellig("1|1", "1|1", t + 31_000, t));
        assertFalse("gleich nach 29 min: nichts", BydDataCollector.gurtLogFaellig("1|1", "1|1", t + 29 * 60_000, t));
        assertTrue("Lebenszeichen nach 30 min", BydDataCollector.gurtLogFaellig("1|1", "1|1", t + 30 * 60_000, t));
        assertTrue("Änderung nach 31 s", BydDataCollector.gurtLogFaellig("0|1", "1|1", t + 31_000, t));
        assertFalse("Änderung, aber Flattern binnen 30 s", BydDataCollector.gurtLogFaellig("0|1", "1|1", t + 5_000, t));
    }
}
