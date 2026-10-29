package at.greenchili.surfmate.monitor;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 0.1.28.1: „ACC aus seit“ nur ab einer echten Aus-Flanke und nur autoritativ. */
public class AccAusSeitTest {

    @Test
    public void flanke() {
        assertEquals("aus, bisher unbekannt → jetzt", 5000L, AccMonitor.naechstesAusSeit(0L, false, 5000L));
        assertEquals("bleibt aus → alter Zeitpunkt", 5000L, AccMonitor.naechstesAusSeit(5000L, false, 9000L));
        assertEquals("an → 0", 0L, AccMonitor.naechstesAusSeit(5000L, true, 9000L));
    }

    @Test
    public void dauer() {
        assertEquals(4000L, AccMonitor.ausSeit(true, false, 5000L, 9000L));
        assertEquals("nicht autoritativ", -1L, AccMonitor.ausSeit(false, false, 5000L, 9000L));
        assertEquals("an", -1L, AccMonitor.ausSeit(true, true, 5000L, 9000L));
        assertEquals("nie aus gewesen", -1L, AccMonitor.ausSeit(true, false, 0L, 9000L));
    }
}
