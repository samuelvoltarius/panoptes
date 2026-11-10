package at.greenchili.surfmate.trips;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.monitor.GearMonitor;

import org.junit.Test;

/** 0.1.38.14: Fahrtstart unabhängig davon, ob der Gang vor oder nach Zündung an kommt (gangFrisch). */
public class FahrtNachholenTest {

    @Test
    public void nurImLeerlaufMitFrischemFahrgang() {
        assertTrue(TripDetector.nachholen(TripDetector.State.IDLE, GearMonitor.GEAR_D));
        assertTrue(TripDetector.nachholen(TripDetector.State.IDLE, GearMonitor.GEAR_R));
        assertFalse(TripDetector.nachholen(TripDetector.State.IDLE, GearMonitor.GEAR_P));
        assertFalse("nicht frisch", TripDetector.nachholen(TripDetector.State.IDLE,
                at.greenchili.surfmate.byd.BydVehicleData.UNAVAILABLE));
        assertFalse(TripDetector.nachholen(TripDetector.State.ACTIVE, GearMonitor.GEAR_D));
    }

    @Test
    public void fahrgangOhneFrische() {
        for (int g : new int[] {GearMonitor.GEAR_D, GearMonitor.GEAR_R, GearMonitor.GEAR_N, GearMonitor.GEAR_M,
                GearMonitor.GEAR_S}) {
            assertTrue(TripDetector.istFahrgang(g));
        }
        assertFalse(TripDetector.istFahrgang(GearMonitor.GEAR_P));
    }
}
