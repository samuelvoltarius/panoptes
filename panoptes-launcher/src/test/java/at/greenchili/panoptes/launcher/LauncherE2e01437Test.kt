package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.1.43.7 (Paket E, E2E_01436b H2): alle Hinweiszeilen unter dem Fahrzeugbild auf demselben lesbaren Feld. */
class LauncherE2e01437Test {
    private fun basis() = if (File("src/main").isDirectory) File(".") else File("panoptes-launcher")
    private fun quelle(n: String) = File(basis(), "src/main/java/at/greenchili/panoptes/launcher/$n").readText().replace("\r\n", "\n")

    @Test
    fun hinweiszeilenUnterDemBildEinheitlich() {
        val k = quelle("FahrzeugKacheln.kt")
        val a = k.substring(k.indexOf("    fun ansicht(): View {"), k.indexOf("    // ---------- 0.1.43.4 (Rang 4): Fahrzeug antippen"))
        assertTrue("Namensnennung", a.contains("Lesbar.zeile(c, nennung, ui, Gravity.END)"))
        assertTrue("3D-Bitte", a.contains("Lesbar.zeile(c, bitte, ui, Gravity.END)"))
        assertTrue("Fensterstand nicht lesbar", a.contains("Lesbar.zeile(c, leer, ui, Gravity.CENTER_HORIZONTAL, oben = 4)"))
        assertEquals("genau drei Hinweiszeilen, alle über Lesbar.zeile", 3, Regex("Lesbar\\.zeile\\(").findAll(a).count())
        for (v in listOf("nennung", "bitte", "leer")) assertFalse("$v ohne eigenes Feld eingehängt", a.contains("c.addView($v,"))
        assertTrue("Fensterstand-Zeile ist der Text fenster_unbekannt",
            a.contains("val leer = ui.leise(ctx.getString(R.string.fenster_unbekannt), 14f)"))
        // dasselbe Feld wie die 3D-Zeile: Hintergrund, Innenabstand, Schatten
        assertTrue(k.contains("    fun zeile(c: LinearLayout, v: TextView, ui: Ui, lage: Int, oben: Int = 2) {\n        feld(v, ui)\n"))
    }
}
