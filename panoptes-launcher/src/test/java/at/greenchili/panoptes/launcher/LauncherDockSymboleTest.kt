package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.1.43.3 Rang 3 „Dock und Symbole“: Beschriftung unter jedem Dock-Symbol, eine einheitliche eigene Symbolfamilie,
 * feste Leiste Klima/Sitz/Luft im Launcher (Sitz und Umluft über den vorhandenen Kern-Weg, nur wenn lesbar und Platz da ist).
 */
class LauncherDockSymboleTest {

    private fun basis() = if (File("src/main").isDirectory) File(".") else File("panoptes-launcher")
    private fun quelle(n: String) = File(basis(), "src/main/java/at/greenchili/panoptes/launcher/$n").readText().replace("\r\n", "\n")
    private val main by lazy { quelle("MainActivity.kt") }
    private fun dock() = main.substringAfter("private fun dock(").substringBefore("private fun klimaBedienung(")

    @Test
    fun jedesDockSymbolHatBeschriftung() {
        val i = main.substringAfter("private fun dockIcon(").substringBefore("private fun appKnopf(")
        assertTrue("Beschriftung unter dem Symbol", i.contains("addView(dockBeschriftung(kurz), LinearLayout.LayoutParams(-1, -2))"))
        assertTrue("Vorlesetext bleibt der lange Name", i.contains("contentDescription = label"))
        // 0.1.43.3 (G1): eine Zeile; lange Namen werden kleiner (nie unter 10 sp) statt mit „…“ abgeschnitten
        assertTrue("eine Zeile", i.contains("maxLines = 1"))
        assertTrue("schrumpft statt abzuschneiden", i.contains("setAutoSizeTextTypeUniformWithConfiguration(10, Math.round(DockPlatz.SCHRIFT * ui.bedien)"))
        assertTrue("leise Farbe, bei Großer Bedienung kontrastreich", i.contains("ui.leise(text, DockPlatz.SCHRIFT)"))
        val d = dock()
        assertTrue("Apps im Dock mit Namen", d.contains("appKnopf(p, mitName = true, gross = Math.round(40 * ui.bedien), dock = true)"))
        assertFalse(d.contains("mitName = false"))
        assertTrue("BYD-Start mit Kurzwort", d.contains("kurz = getString(R.string.dock_byd)"))
        assertTrue(main.contains("spalte.addView(dock(), ui.lp(-1, ui.bd(DockPlatz.HOEHE)"))
        assertTrue("Platz für Symbol + Schrift", DockPlatz.HOEHE >= DockPlatz.SYMBOL + 36)
    }

    @Test
    fun festeLeisteSitzUndLuft() {
        val k = main.substringAfter("private fun klimaBedienung(").substringBefore("private fun klimaSenden(")
        // nur vorhandener Kern-Weg, dieselben Aktionen wie die Komfort-Kacheln
        assertTrue(k.contains("komfortKnopf(R.drawable.ic_pn_sitz, R.string.komfort_sitz_fahrer, \"sitz_fahrer\", sitzWort)"))
        assertTrue(k.contains("komfortKnopf(R.drawable.ic_pn_umluft, R.string.komfort_umluft, \"umluft\", luftWort)"))
        assertTrue(k.contains("setOnClickListener { klimaSenden(aktion) }"))
        assertTrue("sitz_fahrer" in LauncherContract.KOMFORT_AKTIONEN && "umluft" in LauncherContract.KOMFORT_AKTIONEN)
        // unlesbar = kein Knopf, keine Lücke
        assertTrue(k.contains("sitz.visibility = if (sStufe != null) View.VISIBLE else View.GONE"))
        assertTrue(k.contains("luft.visibility = if (umluft != null) View.VISIBLE else View.GONE"))
        assertTrue(k.contains("DockPlatz.komfortBreite(ui.bedien, n)"))
        // Regel A+: keine Hupe/Blinker/Licht/Alarm, keine Türen/Fenster in der Leiste
        for (v in listOf("hupe", "blink", "licht", "alarm", "fenster", "tuer", "schloss", "kofferraum")) assertFalse(v, k.lowercase().contains("\"$v"))
    }

    @Test
    fun platzregelnMitKomfort() {
        assertEquals(0, DockPlatz.komfortBreite(1f, 0))
        assertEquals(2 * DockPlatz.KOMFORT, DockPlatz.komfortBreite(1f, 2))
        assertEquals(DockPlatz.komfortBreite(1f, 2), DockPlatz.komfortBreite(1f, 5))
        // Surf 1280 dp: Sitz und Luft passen normal
        assertTrue(DockPlatz.komfortPasst(1280, 1f))
        for (bedien in listOf(1f, GrosseBedienung.FAKTOR)) for (breite in listOf(853, 960, 1024, 1280, 1920)) {
            if (DockPlatz.komfortPasst(breite, bedien)) {
                assertFalse("Komfort nur mit voller Leiste $breite/$bedien", DockPlatz.kompakt(breite, bedien))
                val summe = DockPlatz.fest(bedien) + DockPlatz.klimaBreite(bedien, false) + DockPlatz.komfortBreite(bedien, 2) + DockPlatz.favsMin(bedien)
                assertTrue("$breite/$bedien", summe <= breite)
            }
        }
        assertFalse("schmal: Sitz/Luft fallen zuerst weg", DockPlatz.komfortPasst(853, 1f))
    }

    @Test
    fun eigeneSymboleEinheitlich() {
        val d = File(basis(), "src/main/res/drawable")
        val neu = listOf("ic_pn_sitz", "ic_pn_umluft", "ic_pn_lenkrad", "ic_pn_entfrosten", "ic_pn_mikro")
        for (n in neu) assertTrue(n, File(d, "$n.xml").isFile)
        d.listFiles()!!.filter { it.name.startsWith("ic_pn_") && it.name != "ic_pn_auto_seite.xml" }.forEach { f ->
            val x = f.readText()
            assertTrue("${f.name}: 24er Raster", x.contains("android:viewportWidth=\"24\""))
            assertFalse("${f.name}: Strich statt Fläche", x.contains("android:fillColor=\"#FFFFFFFF\""))
            assertTrue("${f.name}: Strich 1.8", x.contains("android:strokeWidth=\"1.8\""))
            assertTrue("${f.name}: runde Enden", x.contains("android:strokeLineCap=\"round\""))
        }
        // Komfort-Feld nutzt die eigenen Symbole statt Thermometer/Lüfter doppelt
        assertTrue(main.contains("kachel(R.drawable.ic_pn_sitz, R.string.komfort_sitz_fahrer"))
        assertTrue(main.contains("kachel(R.drawable.ic_pn_lenkrad, R.string.komfort_lenkrad"))
        assertTrue(main.contains("kachel(R.drawable.ic_pn_umluft, R.string.komfort_umluft,"))
        assertTrue(main.contains("paar(R.string.komfort_entfrosten_vorn, R.drawable.ic_pn_entfrosten"))
        assertFalse(main.contains("kachel(R.drawable.ic_pn_thermo, R.string.komfort_sitz"))
    }

    @Test
    fun texteInFuenfSprachen() {
        val res = File(basis(), "src/main/res")
        for (d in listOf("values", "values-de", "values-fr", "values-it", "values-nl")) {
            val x = File(res, "$d/strings.xml").readText()
            for (k in listOf("dock_byd", "dock_sitz", "dock_luft")) assertTrue("$d $k", x.contains("name=\"$k\""))
            assertTrue("$d dock_sitz_stufe mit Zahl", Regex("name=\"dock_sitz_stufe\">[^<]*%1\\\$d<").containsMatchIn(x))
        }
    }
}
