package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.1.43.11: Die Pflichtangabe „3D: Nazh Design · CC BY 4.0 · verändert“ lag auf der Status-Karte halb über Akku und „86%“.
 * Sie hing unten rechts an der 3D-Fläche, deren Unterkante fest 96 dp über dem Kartenrand lag – die Zeile Gang/Tempo/Akku
 * ist aber höher. Jetzt: Bild und Namensnennung untereinander in einer Bühne, deren Unterkante der echten Höhe der Zeile folgt.
 */
class LauncherNennung014311Test {
    private fun basis() = if (File("src/main").isDirectory) File(".") else File("panoptes-launcher")
    private val m by lazy { File(basis(), "src/main/java/at/greenchili/panoptes/launcher/MainActivity.kt").readText().replace("\r\n", "\n") }
    private val feld by lazy { m.substring(m.indexOf("private fun fahrzeugFeld()"), m.indexOf("private fun fahrzeug3d(")) }

    @Test
    fun nennungLiegtNichtMehrUeberDemBild() {
        assertFalse("nicht mehr als Ebene über Bild/3D", feld.contains("mitte.addView(nennung"))
        assertTrue("unter dem Bild, rechts", feld.contains("buehne.addView(nennung, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })"))
        assertTrue("Bild füllt den Rest der Bühne", feld.contains("buehne.addView(mitte, LinearLayout.LayoutParams(-1, 0, 1f))"))
    }

    @Test
    fun buehneEndetUeberGangTempoAkku() {
        assertTrue(feld.contains("unten.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> buehneUeber(buehne, v.height) }"))
        assertTrue(m.contains("val soll = untenHoehe + ui.dp(4)"))
        // Gang, Tempo, Tempolimit und Akku hängen alle in „unten“
        for (k in listOf("unten.addView(gaenge", "unten.addView(tempoBox", "unten.addView(tempoSchild", "unten.addView(akkuBox"))
            assertTrue(k, feld.contains(k))
        assertEquals("genau eine Namensnennung auf der Karte", 1, Regex("standNennung = ").findAll(m).count())
    }
}
