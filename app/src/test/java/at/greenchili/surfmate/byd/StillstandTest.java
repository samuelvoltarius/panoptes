package at.greenchili.surfmate.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.monitor.GearMonitor;

import org.junit.Test;

/**
 * 0.1.28.1 (Befund Deploy (2), 0.1.27 am Auto): Updater sagte „fährt“ im Stand, weil der Tempo-Restwert der letzten
 * Fahrt im Schnappschuss stehen blieb (DiLink 3). Alter Wert bei ACC aus + P zählt nicht; frisches Tempo sperrt weiter.
 */
public class StillstandTest {

    @Test
    public void accAusUndPNachZehnSekundenGiltAlsStillstand() {
        assertEquals(0.0, Stillstand.tempo(1.2, Stillstand.ALT_MS, true), 0);
        assertEquals(0.0, Stillstand.tempo(37, 3_600_000L, true), 0);
        assertEquals("auch ein NaN-Rest", 0.0, Stillstand.tempo(Double.NaN, 60_000L, true), 0);
    }

    @Test
    public void sonstBleibtDerWert() {
        assertEquals("ACC gerade erst aus", 1.2, Stillstand.tempo(1.2, Stillstand.ALT_MS - 1, true), 0);
        assertEquals("ACC an oder unbekannt", 1.2, Stillstand.tempo(1.2, -1, true), 0);
        assertEquals("nicht P", 1.2, Stillstand.tempo(1.2, 60_000L, false), 0);
        assertTrue(Double.isNaN(Stillstand.tempo(Double.NaN, -1, true)));
    }

    @Test
    public void gangAusSchnappschussWennKeinLiveGang() {
        assertTrue(Stillstand.gangP(new BydVehicleData.Builder().gearMode(GearMonitor.GEAR_P).build()));
        assertEquals(false, Stillstand.gangP(new BydVehicleData.Builder().gearMode(GearMonitor.GEAR_D).build()));
        assertEquals(false, Stillstand.gangP(null));
    }
}
