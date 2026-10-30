package at.greenchili.surfmate.navmap.nav

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.1.32.1 Regel Nr. 2: Kartensuche sendet nur grob, Schalter deckt alle Wege ab. */
class KartenSuchePrivatTest {

    @Test
    fun positionNurZweiStellen() {
        assertEquals(52.52, KartenSuchePrivat.rund(52.5249), 0.0)
        assertEquals(-3.7, KartenSuchePrivat.rund(-3.7038), 0.0)
    }

    @Test
    fun ausschnittNachAussenGerundet() {
        assertArrayEquals(doubleArrayOf(52.51, 13.40, 52.53, 13.42),
            KartenSuchePrivat.ausschnitt(52.5149, 13.4001, 52.5201, 13.4101), 1e-9)
    }

    private fun quelle(name: String): String {
        val p = "src/main/java/at/greenchili/surfmate/navmap/nav/$name"
        return (listOf(File(p), File("app/$p")).first { it.isFile }).readText()
    }

    @Test
    fun alleWegeHaengenAmSchalterUndSendenGrob() {
        val g = quelle("ForwardGeocoder.kt")
        assertEquals("search, autocomplete, reverse", 3, Regex("KartenSuchePrivat\\.erlaubt\\(\\)").findAll(g).count())
        assertFalse(g.contains(".append(focusLat)"))
        assertFalse(g.contains(".append(focusLng)"))
        val o = quelle("OverpassPoiClient.kt")
        assertTrue(o.contains("KartenSuchePrivat.erlaubt()"))
        assertTrue(o.contains("buildQuery(grobS, grobW, grobN, grobO, kinds)"))
    }
}
