package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.1.43.12 (E2E_014311 H1): Mit „Große Bedienung“ und einstelligem Tempo („0“) wurde „km/h“ unter der Zahl zu „km/“
 * abgeschnitten. Die Einheit hing ohne eigene Parameter in der senkrechten Tempo-Spalte, bekam damit MATCH_PARENT und
 * nur die Breite der Zahl. „Große Bedienung“ vergrößert Text ([Ui.text]), große Zahlen ([Ui.zahl]) aber nicht.
 * Jetzt ist auch die Einheit WRAP_CONTENT: Die Spalte richtet sich nach max(Zahl, Einheit).
 */
class LauncherKmh014312Test {
    private fun basis() = if (File("src/main").isDirectory) File(".") else File("panoptes-launcher")
    private val m by lazy { File(basis(), "src/main/java/at/greenchili/panoptes/launcher/MainActivity.kt").readText().replace("\r\n", "\n") }
    private val feld by lazy { m.substring(m.indexOf("private fun fahrzeugFeld()"), m.indexOf("private fun fahrzeug3d(")) }

    @Test
    fun einheitHatEigeneBreite() {
        assertTrue("km/h mit WRAP_CONTENT", feld.contains("tempoBox.addView(ui.leise(\"km/h\", 15f), LinearLayout.LayoutParams(-2, -2))"))
        assertFalse("nicht mehr ohne Parameter", feld.contains("tempoBox.addView(ui.leise(\"km/h\", 15f))"))
        assertTrue("Zahl weiter WRAP_CONTENT", feld.contains("tempoBox.addView(tempo, LinearLayout.LayoutParams(-2, -2))"))
    }

    @Test
    fun jedesKindDerTempoSpalteHatEigeneParameter() {
        // Kein Kind der Spalte darf MATCH_PARENT erben, sonst gilt wieder nur die Breite eines anderen Kindes.
        val aufrufe = Regex("""tempoBox\.addView\((.*)\)\s*(//.*)?$""", RegexOption.MULTILINE).findAll(feld).map { it.groupValues[1] }.toList()
        assertEquals("Zahl und Einheit", 2, aufrufe.size)
        aufrufe.forEach { assertTrue(it, it.endsWith("LinearLayout.LayoutParams(-2, -2)")) }
    }

    @Test
    fun buehneUndNennungUnveraendert() {
        // 0.1.43.11 bleibt: Bühne endet über der gemessenen Zeile Gang/Tempo/Akku, Nennung unter dem Bild
        assertTrue(feld.contains("unten.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> buehneUeber(buehne, v.height) }"))
        assertTrue(feld.contains("buehne.addView(nennung, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })"))
        assertTrue(feld.contains("unten.addView(tempoBox, ui.lp(rechts = 16))"))
    }
}
