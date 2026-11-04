package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.1.38.7 (Handy-App-Befund): `fern.stufe` ist die Balkenstufe 1–17 (Mitte 9). Im Launcher-Text steht der Abstand zur Mitte:
 * Stufe 12 heißt „Heizen Stufe 3“, nicht „Heizen Stufe 12“.
 */
class LauncherFernStufeTest {

    @Test
    fun stufeZwoelfIstHeizenDrei() {
        assertEquals(1 to 3, Fahrsicherheit.klimaStufe(12))      // 1 = Heizen
        assertEquals(1 to 1, Fahrsicherheit.klimaStufe(10))
        assertEquals(1 to 8, Fahrsicherheit.klimaStufe(17))
        assertEquals(-1 to 3, Fahrsicherheit.klimaStufe(6))      // -1 = Kühlen
        assertEquals(-1 to 8, Fahrsicherheit.klimaStufe(1))
        assertEquals(0 to 0, Fahrsicherheit.klimaStufe(9))       // Neutral
        assertNull(Fahrsicherheit.klimaStufe(null))
        assertNull(Fahrsicherheit.klimaStufe(0))
        assertNull(Fahrsicherheit.klimaStufe(18))
    }

    @Test
    fun launcherTextNimmtDenAbstandZurMitteNichtDieRoheStufe() {
        val m = File("src/main/java/at/greenchili/panoptes/launcher/MainActivity.kt").readText().replace("\r\n", "\n")
        val f = m.substring(m.indexOf("private fun fernText("), m.indexOf("/** Rückmeldung nach einem Klima-Tipp"))
        assertTrue(f.contains("Fahrsicherheit.klimaStufe(k.fernStufe)"))
        assertTrue(f.contains("getString(R.string.fern_art_heizen, ks.second)"))
        assertTrue(f.contains("getString(R.string.fern_art_kuehlen, ks.second)"))
        assertFalse("nicht mehr die rohe Balkenstufe", f.contains("fern_art_heizen, k.fernStufe"))
        assertFalse(f.contains("fern_art_kuehlen, k.fernStufe"))
    }

    @Test
    fun kernLiefertDieBalkenstufe() {
        val k = LauncherContract.parseSummary(
            """{"klima":{"stufe":2,"fern":{"aktiv":true,"gestartet":1800000000000,"bis":1800000600000,"stufe":12,"art":"heizen"}}}""").klima!!
        assertEquals(12, k.fernStufe)      // Balkenstufe, die Umrechnung macht die Anzeige
        assertEquals("heizen", k.fernArt)
    }
}
