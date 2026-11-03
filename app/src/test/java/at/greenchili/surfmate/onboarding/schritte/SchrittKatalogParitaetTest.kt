package at.greenchili.surfmate.onboarding.schritte

import at.greenchili.surfmate.server.EinrichtungApiHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SchrittKatalogParitaetTest {
    @Test
    fun `server und native UI kennen dieselben Schritte in derselben Reihenfolge`() {
        assertEquals(EinrichtungApiHandler.SCHRITTE, SchrittKatalog.IDS)
    }

    @Test
    fun `zukuenftige unbekannte Schritt-ID ist kein Fertig-Schritt`() {
        assertFalse(SchrittKatalog.kennt("zukuenftiger_kern_schritt"))
    }
}
