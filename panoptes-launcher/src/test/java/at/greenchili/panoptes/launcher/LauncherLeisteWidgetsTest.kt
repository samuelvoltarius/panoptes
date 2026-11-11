package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.1.41 (LO): Leiste über allen Apps (Punkt 2) und Android-Widgets als Kachel (Punkt 9). */
class LauncherLeisteWidgetsTest {

    private fun quelle(name: String): String {
        val p = "src/main/java/at/greenchili/panoptes/launcher/$name"
        return listOf(File(p), File("panoptes-launcher/$p")).first { it.isFile }.readText()
    }

    @Test
    fun leisteNurMitSchalterRechtNichtUeberLauncherNichtImR() {
        assertTrue(Schnellleiste.zeigen("links", recht = true, launcherVorn = false, gear = "D"))
        assertTrue(Schnellleiste.zeigen("oben", true, false, null))
        assertFalse("Standard aus", Schnellleiste.zeigen("aus", true, false, "P"))
        assertFalse("ohne Recht", Schnellleiste.zeigen("rechts", false, false, "P"))
        assertFalse("über dem Launcher nie", Schnellleiste.zeigen("oben", true, true, "P"))
        assertFalse("Rückfahrkamera frei", Schnellleiste.zeigen("links", true, false, "R"))
        assertFalse("unbekannter Wert", Schnellleiste.zeigen("mitte", true, false, "P"))
    }

    @Test
    fun rechtNurUeberEinstellungsseiteKeinZurueckTrick() {
        val l = quelle("Schnellleiste.kt") + quelle("GeraeteWidgets.kt")
        for (verboten in listOf("appops", "grantbind", "AccessibilityService", "KEYCODE_BACK", "input keyevent", "Runtime.getRuntime"))
            assertFalse(verboten, l.contains(verboten))
        val m = quelle("MainActivity.kt")
        assertTrue(m.contains("Settings.ACTION_MANAGE_OVERLAY_PERMISSION"))
        assertFalse(m.contains("appops"))
        assertTrue(quelle("GeraeteWidgets.kt").contains("AppWidgetManager.ACTION_APPWIDGET_BIND"))
        assertTrue(quelle("GeraeteWidgets.kt").contains("bindAppWidgetIdIfAllowed"))
    }

    @Test
    fun e2eG6KnoepfeGrossSeitlichZuerst() {
        assertTrue(Schnellleiste.KNOPF_DP >= 64)
        assertTrue(Schnellleiste.KNOPF_BREITE_DP >= Schnellleiste.KNOPF_DP)
        assertEquals(Schnellleiste.AUS, Schnellleiste.WAHL.first())
        assertTrue("seitlich zuerst", Schnellleiste.WAHL[1] in listOf(Schnellleiste.LINKS, Schnellleiste.RECHTS))
        assertEquals(Schnellleiste.OBEN, Schnellleiste.WAHL.last())
        val s = quelle("Schnellleiste.kt")
        assertFalse(s.contains("ui.dp(48)")); assertFalse(s.contains("ui.dp(52)"))
        for (d in listOf("values", "values-de", "values-fr", "values-it", "values-nl")) {
            val p = "src/main/res/$d/strings.xml"
            val x = listOf(File(p), File("panoptes-launcher/$p")).first { it.isFile }.readText()
            assertTrue(d, Regex("<string name=\"schnell_oben\">[^<]*\\(").containsMatchIn(x))
        }
    }

    @Test
    fun e2eL1KeinSystemLautstaerkefensterEigeneAnzeige() {
        val s = quelle("Schnellleiste.kt")
        assertFalse("Androids Lautstärkefenster verdeckt die Leiste", s.contains("FLAG_SHOW_UI"))
        assertTrue(s.contains("adjustStreamVolume(AudioManager.STREAM_MUSIC, richtung, 0)"))
        assertTrue("Platz reserviert, Knöpfe springen nicht", s.contains("View.INVISIBLE")); assertFalse(s.contains("View.GONE"))
        assertEquals(1_500L, Schnellleiste.ANZEIGE_MS)
        assertEquals(0, Schnellleiste.prozent(0, 15)); assertEquals(46, Schnellleiste.prozent(7, 15))
        assertEquals(100, Schnellleiste.prozent(15, 15)); assertEquals(100, Schnellleiste.prozent(20, 15))
        assertEquals(0, Schnellleiste.prozent(5, 0))
    }

    @Test
    fun e2eG7WidgetBekommtKachelgroesse() {
        assertEquals(null, GeraeteWidgets.kachelDp(0, 100, 2f))
        assertEquals(null, GeraeteWidgets.kachelDp(100, 100, 0f))
        assertEquals(240 to 120, GeraeteWidgets.kachelDp(480, 240, 2f))
        assertEquals(240 to 120, GeraeteWidgets.kachelDp(360, 180, 1.5f))
        val g = quelle("GeraeteWidgets.kt")
        assertTrue(g.contains("updateAppWidgetSize")); assertTrue(g.contains("addOnLayoutChangeListener"))
        assertTrue(quelle("MainActivity.kt").contains("box.addView(inhalt, FrameLayout.LayoutParams(-1, -1))"))
    }

    @Test
    fun widgetGroesseAusMindestmass() {
        assertEquals(1 to 1, GeraeteWidgets.groesse(40, 40).first())
        assertEquals(3 to 2, GeraeteWidgets.groesse(300, 200).first())
        assertEquals(4 to 3, GeraeteWidgets.groesse(900, 900).first())
        assertTrue(GeraeteWidgets.groesse(250, 40).all { it in Raster.GROESSEN })
        assertTrue(GeraeteWidgets.gueltig(5)); assertFalse(GeraeteWidgets.gueltig(0)); assertFalse(GeraeteWidgets.gueltig(null))
    }

    @Test
    fun mehrereWidgetsMitNummerUeberlebenJson() {
        val leer = Raster.seiteHinzufuegen(Raster.standard(null))
        val s = leer.seiten.size - 1
        val a = Raster.geraeteWidgetHinzufuegen(leer, s, 11, listOf(2 to 1))
        val b = Raster.geraeteWidgetHinzufuegen(a, s, 12, listOf(2 to 1))
        assertSame("gleiche Nummer nur einmal", b, Raster.geraeteWidgetHinzufuegen(b, s, 12, listOf(2 to 1)))
        assertSame("Nummer 0 nie", b, Raster.geraeteWidgetHinzufuegen(b, s, 0, listOf(2 to 1)))
        val zurueck = Raster.fromJson(Raster.toJson(b))!!
        val w = zurueck.seiten.flatten().filter { it.kachel == Kachel.GERAETEWIDGET }.map { it.wid }
        assertEquals(listOf(11, 12), w)
        // ohne wid fällt die Kachel beim Laden weg
        val roh = org.json.JSONObject(Raster.toJson(b))
        val seiten = roh.getJSONArray("seiten")
        for (i in 0 until seiten.length()) {
            val a2 = seiten.getJSONArray(i)
            for (j in 0 until a2.length()) a2.getJSONObject(j).let { if (it.optInt("wid") == 11) it.remove("wid") }
        }
        val ohne = roh.toString()
        assertEquals(listOf(12), Raster.fromJson(ohne)!!.seiten.flatten().filter { it.kachel == Kachel.GERAETEWIDGET }.map { it.wid })
    }

    @Test
    fun texteInFuenfSprachen() {
        for (d in listOf("values", "values-de", "values-fr", "values-it", "values-nl")) {
            val p = "src/main/res/$d/strings.xml"
            val x = listOf(File(p), File("panoptes-launcher/$p")).first { it.isFile }.readText()
            for (k in listOf("widget_geraet", "widget_geraet_hinzu", "widget_geraet_keine", "widget_geraet_fehlt", "schnell_titel",
                    "schnell_oben", "schnell_links", "schnell_rechts", "schnell_haus", "schnell_leiser", "schnell_lauter",
                    "schnell_hinweis", "schnell_recht_text"))
                assertTrue("$d $k", x.contains("<string name=\"$k\">"))
        }
    }
}
