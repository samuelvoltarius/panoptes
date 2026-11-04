package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.1.38.5 („nicht erst in Panoptes rein … sofort geht“, „Temperatureinstellung geht nicht, öffnet die
 * Main-App“): Klima, Navi, Fahrzeug, Akku und Wächter wirken direkt im Launcher. Panoptes nur über „Mehr in Panoptes“.
 */
class LauncherDirektTest {

    @Test
    fun naviZielReihenfolge() {
        val installiert = setOf("com.waze", "net.osmand")
        val ok = { p: String -> p in installiert }
        assertEquals("net.osmand", Fahrsicherheit.naviZiel("net.osmand", "com.waze", listOf("com.waze"), ok))
        assertEquals("com.waze", Fahrsicherheit.naviZiel("de.weg", "com.waze", listOf("net.osmand"), ok))
        assertEquals("net.osmand", Fahrsicherheit.naviZiel(null, "com.google.android.apps.maps", listOf("com.google.android.apps.maps", "net.osmand"), ok))
        assertNull(Fahrsicherheit.naviZiel(null, null, listOf("com.sygic.aura"), ok))
    }

    @Test
    fun naviAppAusDemKern() {
        val s = LauncherContract.parseSummary("""{"naviApp":{"gewaehlt":"com.waze","bekannt":["com.waze","net.osmand","../böse"]}}""")
        assertEquals("com.waze", s.naviGewaehlt)
        assertEquals(listOf("com.waze", "net.osmand"), s.naviBekannt)
        val leer = LauncherContract.parseSummary("""{"naviApp":{"gewaehlt":null,"bekannt":[]}}""")
        assertNull(leer.naviGewaehlt)
        assertTrue(LauncherContract.parseSummary("{}").naviBekannt.isEmpty())
    }

    @Test
    fun klimaWirktDirektUndGross() {
        val main = quelle("MainActivity.kt")
        val bedienung = main.substring(main.indexOf("private fun klimaBedienung()"), main.indexOf("private fun klimaSenden("))
        assertFalse("Tipp auf den Wert öffnet nicht Panoptes", bedienung.contains("oeffnePanoptes"))
        assertTrue(bedienung.contains("setOnClickListener { klimaFeld() }"))
        assertTrue(Regex("""const val DOCK_KNOPF = (\d+)""").find(main)!!.groupValues[1].toInt() >= 64)
        assertTrue(Regex("""const val GROSS_KNOPF = (\d+)""").find(main)!!.groupValues[1].toInt() >= 64)
        assertFalse(bedienung.contains("ui.dp(48)"))
        // Rückmeldung nach jedem Tipp: neuer Wert oder Grund
        val senden = main.substring(main.indexOf("private fun klimaSenden("), main.indexOf("private fun klimaGrund("))
        assertTrue(senden.contains("klimaRueckmeldung(aktion, antwort.klima)"))
        assertTrue(senden.contains("klimaGrund(antwort.grund)"))
        // Rückmeldung liegt über dem Klima-Feld (sonst unsichtbar, Befund Emulator)
        assertTrue(main.contains("hinweis.bringToFront()"))
        // Klima-Feld: nur die sechs Aktionen des Kern-Wegs
        val feld = main.substring(main.indexOf("private fun klimaFeld()"), main.indexOf("private var klimaFeldZeichnen"))
        Regex("""klimaSenden\("([a-z_]+)"""").findAll(feld).forEach { assertTrue(it.value, it.groupValues[1] in LauncherContract.KLIMA_AKTIONEN) }
        listOf("temp_runter", "temp_hoch", "luft_runter", "luft_hoch").forEach { assertTrue(it, feld.contains("\"$it\"")) }
    }

    @Test
    fun kachelnUndReiterOhnePanoptes() {
        val main = quelle("MainActivity.kt")
        val widget = main.substring(main.indexOf("private fun widget(e: Element)"), main.indexOf("private fun spalte("))
        assertFalse(widget.contains("oeffnePanoptes"))
        listOf("fahrzeugInfo()", "akkuInfo()", "starteNavi()", "waechterInfo()").forEach { assertTrue(it, widget.contains(it)) }
        val reiter = main.substring(main.indexOf("private fun reiterZeile()"), main.indexOf("private fun reiterZeile()") + 2500)
        assertTrue(reiter.contains("{ starteNavi() }"))
        assertTrue(reiter.contains("{ klimaFeld() }"))
        // Panoptes nur noch über „Mehr in Panoptes“ (Info-/Klima-Feld) und die Navi-Notlösung ohne Navi-App
        val info = main.substring(main.indexOf("private fun infoFeld("), main.indexOf("private fun fahrzeugInfo()"))
        assertTrue(info.contains("R.string.mehr_in_panoptes"))
        // Tabu bleibt tabu
        listOf("hupe", "flash", "find", "lock\"", "unlock", "alarm").forEach { assertFalse(it, main.lowercase().contains("klimasenden(\"$it")) }
    }

    private fun quelle(name: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            for (rel in listOf("src/main/java/at/greenchili/panoptes/launcher/$name", "panoptes-launcher/src/main/java/at/greenchili/panoptes/launcher/$name")) {
                val f = File(dir, rel); if (f.isFile) return f.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("nicht gefunden: $name")
    }
}
