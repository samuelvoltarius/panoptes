package at.greenchili.surfmate.updater

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 0.1.38.10 Stufe −1: das Tor hält Daemon-Starts an, solange ein Hard-Reset erwartet wird oder läuft. */
class HardResetTorTest {
    @Before @After fun zurueck() { HardResetTor.zuruecksetzen() }

    @Test
    fun offenWennKeinHardResetErwartetWird() {
        assertTrue(HardResetTor.frei(0L))
        assertTrue(HardResetTor.frei(1_000_000L))
    }

    @Test
    fun zuBisZumEndeOderZeitlimit() {
        HardResetTor.erwarten(1_000L)
        assertFalse(HardResetTor.frei(1_000L))
        assertFalse(HardResetTor.frei(1_000L + HardResetTor.ZEITLIMIT_MS - 1))
        assertTrue("Zeitlimit öffnet", HardResetTor.frei(1_000L + HardResetTor.ZEITLIMIT_MS))
        HardResetTor.beenden()
        assertTrue(HardResetTor.frei(2_000L))
    }

    @Test
    fun erwartenIstIdempotentDieErsteZeitZaehlt() {
        HardResetTor.erwarten(1_000L)
        HardResetTor.erwarten(15_000L)   // z. B. Application, dann MainActivity
        assertTrue("zählt ab der ersten Erwartung", HardResetTor.frei(1_000L + HardResetTor.ZEITLIMIT_MS))
    }

    @Test
    fun zeitlimitIstKuerzerAlsDerMainActivityZeitablauf() {
        assertEquals(20_000L, HardResetTor.ZEITLIMIT_MS)
    }
}
