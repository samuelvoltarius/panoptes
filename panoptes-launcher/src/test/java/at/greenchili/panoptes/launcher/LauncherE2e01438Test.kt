package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.1.43.8 (Paket H):
 * - H4 (E2E_01437): Schloss-Symbol über dem Auto im hellen Design kaum sichtbar (dunkles Symbol auf dunklem Kreis).
 *   Jetzt: Kontrast Symbol ↔ Plakettengrund ≥ 3:1 in jedem Design, Tag und Nacht, für jede Akzentfarbe.
 * - G1 / Alt-H2 (E2E_01433): seit der Designrunde 0.1.43.4 behoben; hier gegen Rückfall festgehalten.
 */
class LauncherE2e01438Test {
    private fun basis() = if (File("src/main").isDirectory) File(".") else File("panoptes-launcher")
    private fun quelle(n: String) = File(basis(), "src/main/java/at/greenchili/panoptes/launcher/$n").readText().replace("\r\n", "\n")

    private val weiss = Farbe.rgb(0xFFFFFF)
    private val schwarz = Farbe.rgb(0x000000)

    /** Plakettengrund über hellem (Tag, helles Foto) und dunklem Hintergrund (Nacht). */
    private fun gruende() = listOf(Farbe.ueber(FahrzeugStart.PLAKETTE_GRUND, weiss), Farbe.ueber(FahrzeugStart.PLAKETTE_GRUND, schwarz))

    private fun paletten(): List<Pair<String, Palette>> {
        val l = mutableListOf("DUNKEL" to PanoptesStil.DUNKEL, "HELL" to PanoptesStil.HELL, "KONTRAST" to PanoptesStil.KONTRAST, "NACHT" to PanoptesStil.NACHT)
        // eigene Akzentfarben, auch sehr dunkle, im hellen und dunklen Design
        for (a in listOf("#102030", "#00758A", "#7A1F00", "#FFD400", "#3D2B8F")) {
            l += "HELL+$a" to PanoptesStil.HELL.copy(aqua = Farbe.hex(a)!!)
            l += "DUNKEL+$a" to PanoptesStil.DUNKEL.copy(aqua = Farbe.hex(a)!!)
        }
        return l
    }

    @Test
    fun kontrastRechnungNachWcag() {
        assertEquals(21.0, Farbe.kontrast(weiss, schwarz), 0.01)
        assertEquals(1.0, Farbe.kontrast(weiss, weiss), 0.001)
        val halb = Farbe.ueber(Farbe.argb(128, 255, 255, 255), schwarz)
        assertEquals(255, Farbe.a(halb))
        assertEquals(128, Farbe.r(halb))
    }

    @Test
    fun schlossUeberallMindestensDreiZuEins() {
        val fehler = mutableListOf<String>()
        for ((name, p) in paletten()) for (v in listOf(true, false, null)) for (g in gruende()) {
            val k = Farbe.kontrast(FahrzeugStart.schlossFarbe(v, p), g)
            if (k < 3.0) fehler += "$name verriegelt=$v: %.2f".format(k)
        }
        assertTrue(fehler.joinToString("\n"), fehler.isEmpty())
    }

    @Test
    fun andereSymboleAufDerPlaketteEbenso() {
        for ((name, p) in paletten()) for (c in listOf(p.warning, p.lime)) for (g in gruende()) {
            val k = Farbe.kontrast(FahrzeugStart.plakettenFarbe(c), g)
            assertTrue("$name: %.2f".format(k), k >= 3.0)
        }
    }

    @Test
    fun dunklesDesignBleibtWieEs() {
        val p = PanoptesStil.DUNKEL
        assertEquals("Akzent nachts unverändert", Farbe.alpha(p.aqua, 255), FahrzeugStart.schlossFarbe(true, p))
        assertEquals("Warnung nachts unverändert", Farbe.alpha(p.warning, 255), FahrzeugStart.schlossFarbe(false, p))
        assertTrue("hell: Akzent wird aufgehellt", FahrzeugStart.schlossFarbe(true, PanoptesStil.HELL) != PanoptesStil.HELL.aqua)
    }

    @Test
    fun beideZeichenwegeNutzenDiePlakettenfarbe() {
        val s = quelle("FahrzeugStart.kt")
        assertFalse("kein onSurface mit Deckkraft 110 mehr", s.contains("Farbe.alpha(p.onSurface, 110)"))
        assertTrue("Ansicht mit Bild", s.contains("\"schloss\" -> schlossFarbe(z.verriegelt, p)"))
        assertTrue("gezeichnete Seitenansicht", s.contains("val sf = schlossFarbe(z.verriegelt, p)"))
        assertEquals("beide Kreise auf demselben dunklen Grund", 2, Regex("Voll\\(PLAKETTE_GRUND\\)").findAll(s).count())
    }

    @Test
    fun dockUndSprechenBleibenBehoben() {
        val m = quelle("MainActivity.kt")
        // G1: lange Namen werden kleiner statt abgeschnitten; App-Symbol im selben Kasten (gleiche Grundlinie)
        assertTrue(m.contains("setAutoSizeTextTypeUniformWithConfiguration(10, Math.round(DockPlatz.SCHRIFT * ui.bedien), 1,"))
        assertTrue(m.contains("if (dock) addView(FrameLayout(this@MainActivity).apply { addView(symbol, FrameLayout.LayoutParams(ui.dp(gross), ui.dp(gross), Gravity.CENTER)) },"))
        // Alt-H2: „Sprechen“ sitzt in der Statusleiste, nicht mehr über der Musik-Karte
        assertTrue(m.contains("sprachKnopf.einhaengen(root, ui, sprachPlatz)"))
    }
}
