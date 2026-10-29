package at.greenchili.surfmate.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.After;
import org.junit.Test;

/** 0.1.28 Rückkehr-Karte: der letzte Wächter-Stopp bleibt über ACC an hinaus lesbar, aber nur für die Parkphase danach. */
public class GuardStopMerkerTest {

    @After
    public void aufraeumen() {
        GuardStopMonitor.merkeStopp(null, 0);
    }

    @Test
    public void stoppNurSeitBeginnDerParkphase() {
        assertNull(GuardStopMonitor.stoppGrundSeit(0));
        GuardStopMonitor.merkeStopp(GuardStopPolicy.REASON_SOC, 5_000);
        assertEquals(GuardStopPolicy.REASON_SOC, GuardStopMonitor.stoppGrundSeit(4_000));
        assertEquals(GuardStopPolicy.REASON_SOC, GuardStopMonitor.stoppGrundSeit(5_000));
        assertNull("Stopp vor dieser Parkphase zählt nicht", GuardStopMonitor.stoppGrundSeit(6_000));
    }
}
