package at.greenchili.panoptes.launcher

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.1.43.6 (E2E H2): die Bitte um ein 3D-Modell bleibt vor hellen Spiegelungen der Nachtszene lesbar. */
class LauncherE2e01436Test {
    private fun basis() = if (File("src/main").isDirectory) File(".") else File("panoptes-launcher")
    private fun quelle(n: String) = File(basis(), "src/main/java/at/greenchili/panoptes/launcher/$n").readText().replace("\r\n", "\n")

    @Test
    fun h2BitteHatFeldUndSchatten() {
        val k = quelle("FahrzeugKacheln.kt")
        assertTrue(k.contains("        Lesbar.feld(bitte, ui)\n        c.addView(bitte, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END"))
        assertTrue(k.contains("v.setShadowLayer(ui.dpf(2f), 0f, ui.dpf(1f), schatten(hell))"))
        // Nacht: dunkles Feld, Tag: helles Feld – jeweils deutlich deckend
        assertTrue(k.contains("if (hell) android.graphics.Color.argb(178, 255, 255, 255) else android.graphics.Color.argb(153, 6, 11, 20)"))
    }
}
