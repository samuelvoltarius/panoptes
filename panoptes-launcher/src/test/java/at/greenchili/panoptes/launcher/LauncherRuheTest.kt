package at.greenchili.panoptes.launcher

import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

/** 0.1.38.8: Launcher ruht bei Display aus, ohne Fokus kein Sekundentakt, Takt zeichnet nur bei Änderung. */
class LauncherRuheTest {
    @Test fun displayZustaende() {
        assertFalse(Ruhe.displayAn(Display.STATE_OFF))
        assertFalse(Ruhe.displayAn(Display.STATE_DOZE))
        assertFalse(Ruhe.displayAn(Display.STATE_DOZE_SUSPEND))
        assertTrue(Ruhe.displayAn(Display.STATE_ON))
        assertTrue("unbekannt = an", Ruhe.displayAn(null))
        assertTrue(Ruhe.displayAn(Display.STATE_UNKNOWN))
    }

    @Test fun abfrageUndTakt() {
        assertTrue(Ruhe.abfrageLaeuft(gestoppt = false, androidDisplayAn = true))
        assertFalse(Ruhe.abfrageLaeuft(gestoppt = false, androidDisplayAn = false))
        assertFalse(Ruhe.abfrageLaeuft(gestoppt = true, androidDisplayAn = true))
        assertTrue(Ruhe.taktLaeuft(gestoppt = false, bildAn = true, fokus = true))
        assertFalse("ohne Fokus kein Sekundentakt", Ruhe.taktLaeuft(gestoppt = false, bildAn = true, fokus = false))
        assertFalse(Ruhe.taktLaeuft(gestoppt = false, bildAn = false, fokus = true))
    }

    @Test fun zuendungAusIstBildAus() {
        // BYD: Panel aus bei Zündung aus, Android meldet weiter Display an (Surf 04.11.)
        assertFalse(Ruhe.bildAn(androidDisplayAn = true, acc = "off"))
        assertTrue(Ruhe.bildAn(androidDisplayAn = true, acc = "on"))
        assertTrue("Laden: Zündung unbekannt, nicht raten", Ruhe.bildAn(androidDisplayAn = true, acc = "charging"))
        assertTrue("älterer Kern ohne acc", Ruhe.bildAn(androidDisplayAn = true, acc = null))
        assertFalse(Ruhe.bildAn(androidDisplayAn = false, acc = "on"))
        assertEquals(5_000L, Ruhe.abfrageMs(bildAn = true))
        assertEquals("in Ruhe seltener, aber nicht aus (Einschalten bemerken)", 10_000L, Ruhe.abfrageMs(bildAn = false))
    }

    @Test fun taktSchluesselAendertSichNurBeiNeuemInhalt() {
        val a = Ruhe.taktSchluessel(null, null, null, null, null, null, null, 120_000L)
        assertEquals("gleiche Minute, nichts spielt", a, Ruhe.taktSchluessel(null, null, null, null, null, null, null, 179_999L))
        assertNotEquals("neue Minute", a, Ruhe.taktSchluessel(null, null, null, null, null, null, null, 180_000L))
        val m = Ruhe.taktSchluessel("x.y", "T", "K", true, 10_400L, 200_000L, 7, 120_000L)
        assertEquals(m, Ruhe.taktSchluessel("x.y", "T", "K", true, 10_900L, 200_000L, 7, 121_000L))
        assertNotEquals("Position eine Sekunde weiter", m, Ruhe.taktSchluessel("x.y", "T", "K", true, 11_000L, 200_000L, 7, 121_000L))
    }

    @Test fun mainActivityHaeltBeiDisplayAusAn() {
        val main = String(Files.readAllBytes(Paths.get("src/main/java/at/greenchili/panoptes/launcher/MainActivity.kt")), Charsets.UTF_8)
        assertTrue(main.contains("registerDisplayListener(displayHoerer, main)"))
        assertTrue(main.contains("unregisterDisplayListener(displayHoerer)"))
        assertTrue(main.contains("override fun onWindowFocusChanged(hasFocus: Boolean)"))
        assertTrue(main.contains("if (Ruhe.abfrageLaeuft(stopped, displayAn)) main.postDelayed(this, Ruhe.abfrageMs(bildAn))"))
        assertTrue(main.contains("if (Ruhe.taktLaeuft(stopped, bildAn, fokus)) main.postDelayed(this, 1_000)"))
        assertTrue(main.contains("if (stand != taktStand)"))
        assertTrue(main.contains("if (bildAn != warBildAn) zustandAnwenden()"))
        assertTrue("Video nie bei Display aus", main.contains("if (bildAn && !stopped && layout.hintergrund == \"video\""))
    }
}
