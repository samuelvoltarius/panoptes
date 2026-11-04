package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAbgleichTest {
    private val aus = HomeWunsch(false, false, false)
    private val ein = HomeWunsch(true, false, true)
    private val behalten = HomeWunsch(true, true, false)

    @Test fun parseLiestDieKernAntwort() {
        assertEquals(ein, HomeAbgleich.parse("{\"gewuenscht\":true,\"behalten\":false,\"frage\":true,\"totmannSekunden\":300}"))
        assertEquals(aus, HomeAbgleich.parse("{}"))
        assertNull(HomeAbgleich.parse("kein json"))
    }

    @Test fun aliasFolgtDemKernWunschNurBeiAbweichung() {
        assertEquals(true, HomeAbgleich.aliasSoll(ein, false))
        assertEquals(false, HomeAbgleich.aliasSoll(aus, true))
        assertNull(HomeAbgleich.aliasSoll(ein, true))
        assertNull(HomeAbgleich.aliasSoll(aus, false))
    }

    @Test fun kernNichtErreichbarAendertNichts() {
        assertNull(HomeAbgleich.aliasSoll(null, true))
        assertNull(HomeAbgleich.aliasSoll(null, false))
        assertFalse(HomeAbgleich.fragen(null, true))
    }

    @Test fun frageNurBeiEinAliasAnUndUnbestaetigt() {
        assertTrue(HomeAbgleich.fragen(ein, true))
        assertFalse("Alias noch aus", HomeAbgleich.fragen(ein, false))
        assertFalse("schon behalten", HomeAbgleich.fragen(behalten, true))
        assertFalse("Schalter aus", HomeAbgleich.fragen(aus, true))
    }
}
