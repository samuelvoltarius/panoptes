package at.greenchili.surfmate.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 0.1.43.21 Teil B: Frischegrenze passt zum Stand-Takt (~90 s), nach dem Kern-Start „noch kein Wert“ statt 0.0 V. */
public class BatteryMonitorWertTest {

    @Test
    public void frischegrenzeUeberDemStandTakt() {
        assertEquals(150_000L, BatteryMonitor.BATTERY_VOLTAGE_STALE_MS);
        long t = 1_000_000L;
        // Der Fall vom 24.11.: v12AlterMs 76175 gilt jetzt als frisch
        assertTrue(BatteryMonitor.isVoltageFresh(12.8, t, t + 76_175L));
        // Ein verpasster Stand-Takt (2 × 90 s) gilt als veraltet
        assertFalse(BatteryMonitor.isVoltageFresh(12.8, t, t + 180_000L));
    }

    @Test
    public void nachKernStartNochKeinWert() {
        assertEquals("noch_kein_wert", BatteryMonitor.wertStatus(false, 0L));
        assertEquals("frisch", BatteryMonitor.wertStatus(true, 5L));
        assertEquals("veraltet", BatteryMonitor.wertStatus(false, 5L));

        String start = BatteryMonitor.logZeile(0.0, 0L, "UNKNOWN", 0.0);
        assertEquals("Battery: noch kein Wert", start);
        assertFalse(start.contains("0.0V"));
        assertEquals("Battery: noch kein Wert (SOC: 40.0%)", BatteryMonitor.logZeile(0.0, 0L, "UNKNOWN", 40.0));
        assertEquals("Battery updated: 12.6V (NORMAL), SOC: 40.0%",
                BatteryMonitor.logZeile(12.6, 5L, "NORMAL", 40.0));
    }
}
