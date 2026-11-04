package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.1.38.5, Befunde am Auto (04.11.): Der Surf stellt die Temperatur in Stufen (nie eine erfundene Gradzahl zeigen),
 * und YouTube/Netflix/CarPlay lassen sich ohne force_resizable_activities nicht teilen (ehrlich sagen, Vollbild nur
 * auf Wunsch).
 */
class LauncherStufeSplitTest {

    @Test
    fun surfStufeStattGrad() {
        assertEquals(1 to 1, Fahrsicherheit.klimaStufe(10))     // Heizen 1 (= SDK 26, früher als „26°“ gezeigt)
        assertEquals(1 to 8, Fahrsicherheit.klimaStufe(17))
        assertEquals(0 to 0, Fahrsicherheit.klimaStufe(9))      // Neutral
        assertEquals(-1 to 8, Fahrsicherheit.klimaStufe(1))
        assertNull(Fahrsicherheit.klimaStufe(0))
        assertNull(Fahrsicherheit.klimaStufe(null))
        val k = LauncherContract.parseSummary("""{"klima":{"sollC":null,"stufe":3,"innenC":19.5,"an":true,"stufenModell":true,"tempStufe":10}}""").klima!!
        assertTrue(k.stufenModell)
        assertEquals(10, k.tempStufe)
        assertNull(k.sollC)
        assertNull(LauncherContract.parseSummary("""{"klima":{"stufenModell":true,"tempStufe":40}}""").klima!!.tempStufe)
        val main = quelle("MainActivity.kt")
        val soll = main.substring(main.indexOf("private fun sollText("), main.indexOf("private fun klimaRueckmeldung("))
        assertTrue("Stufen-Modell zuerst", soll.indexOf("k.stufenModell") < soll.indexOf("k.sollC"))
        assertFalse("Dock zeigt nicht die Innentemperatur als Sollwert", main.contains("?: (k?.innenC?.let"))
    }

    @Test
    fun splitEhrlich() {
        assertEquals(NebenErgebnis.OK, LauncherContract.parseNeben("""{"success":true}"""))
        assertEquals(NebenErgebnis.NICHT_TEILBAR, LauncherContract.parseNeben("""{"success":false,"error":"nicht_teilbar"}"""))
        assertEquals(NebenErgebnis.FEHLER, LauncherContract.parseNeben("""{"success":false,"error":"x"}"""))
        assertEquals(NebenErgebnis.FEHLER, LauncherContract.parseNeben(null))
        assertEquals(mapOf("com.waze" to "ja", "com.byd.youtube" to "nein"),
            LauncherContract.parseTeilbar("""{"pakete":{"com.waze":"ja","com.byd.youtube":"nein","x.y":"vielleicht"}}"""))
        val main = quelle("MainActivity.kt")
        val neben = main.substring(main.indexOf("private fun nebenStarten("), main.indexOf("private fun nebenPunkt("))
        // Vollbild nur über den Knopf „Im Vollbild öffnen“
        assertEquals(1, Regex("starteVollbild\\(").findAll(neben).count())
        assertTrue(neben.contains("setPositiveButton(R.string.im_vollbild_oeffnen)"))
        // eigener, klarer Weg im Langdruck-Menü
        assertEquals(2, Regex("if \\(p != Raster.PANOPTES\\) punkte \\+= nebenPunkt\\(p\\)").findAll(main).count())
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
