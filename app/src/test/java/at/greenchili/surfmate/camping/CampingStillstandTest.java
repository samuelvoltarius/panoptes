package at.greenchili.surfmate.camping;

import static org.junit.Assert.assertEquals;

import at.greenchili.surfmate.byd.BydVehicleData;
import at.greenchili.surfmate.byd.Stillstand;
import at.greenchili.surfmate.monitor.GearMonitor;

import org.junit.Test;

/** 0.1.28.1: derselbe Restwert-Fehler wie beim Updater – Camping hielt ein geparktes Auto für „fährt“. */
public class CampingStillstandTest {

    @Test
    public void restwertBeiAccAusUndPIstStand() {
        BydVehicleData v = new BydVehicleData.Builder().speedKmh(3.0).gearMode(GearMonitor.GEAR_P).build();
        assertEquals("ohne Regel: fährt", CampingRegler.Fahrt.FAEHRT, Camping.fahrt(v, v.speedKmh));
        assertEquals(CampingRegler.Fahrt.STEHT, Camping.fahrt(v, Stillstand.tempo(v.speedKmh, 60_000L, true)));
        assertEquals("ACC an: bleibt fährt", CampingRegler.Fahrt.FAEHRT, Camping.fahrt(v, Stillstand.tempo(v.speedKmh, -1, true)));
    }
}
