package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.1.44 (P1 „Bedienung 50+“): Zurück/Letzte App, Große Bedienung, geführte Einrichtung mit Selbsttest. */
class LauncherBedienungTest {

    private fun datei(rel: String): String =
        listOf(File(rel), File("panoptes-launcher/$rel")).first { it.isFile }.readText()

    private fun quelle(name: String) = datei("src/main/java/at/greenchili/panoptes/launcher/$name")

    @Test
    fun wegHilfeVorKernSonstKeiner() {
        assertEquals(Tasten.Weg.HILFE, Tasten.weg(hilfeAn = true, kernDa = true))
        assertEquals(Tasten.Weg.HILFE, Tasten.weg(hilfeAn = true, kernDa = false))
        assertEquals(Tasten.Weg.DAEMON, Tasten.weg(hilfeAn = false, kernDa = true))
        assertEquals(Tasten.Weg.KEINER, Tasten.weg(hilfeAn = false, kernDa = false))
    }

    @Test
    fun verlaufLetzteApp() {
        val ign = setOf("at.greenchili.panoptes.launcher", "com.byd.launcher")
        var l = emptyList<String>()
        for (p in listOf("a.navi", "com.byd.launcher", "b.musik", "b.musik", null, "", "at.greenchili.panoptes.launcher", "c.radio"))
            l = AppVerlauf.merke(l, p, ign)
        assertEquals(listOf("a.navi", "b.musik", "c.radio"), l)
        assertEquals("aus der aktuellen App zur vorigen", "b.musik", AppVerlauf.vorige(l, "c.radio"))
        assertEquals("vom Launcher aus zur zuletzt benutzten", "c.radio", AppVerlauf.vorige(l, "at.greenchili.panoptes.launcher"))
        assertEquals("erneut geöffnet rückt nach oben", listOf("b.musik", "c.radio", "a.navi"), AppVerlauf.merke(l, "a.navi", ign))
        assertNull(AppVerlauf.vorige(emptyList(), "x"))
        assertNull(AppVerlauf.vorige(listOf("x"), "x"))
        var lang = emptyList<String>()
        for (i in 1..20) lang = AppVerlauf.merke(lang, "app$i", emptySet())
        assertEquals(AppVerlauf.MAX, lang.size)
    }

    @Test
    fun grosseBedienungFaktor() {
        assertEquals(1f, GrosseBedienung.faktor(false))
        assertEquals(1.25f, GrosseBedienung.faktor(true))
        val b = quelle("Bausteine.kt")
        assertTrue(b.contains("fun bd(v: Int) = dp(Math.round(v * bedien))"))
        assertTrue("Schrift wächst", b.contains("textSize = sp * bedien"))
        assertTrue("kontrastreiche Beschriftung", b.contains("if (kontrast) p.onSurface else p.onSurfaceVariant"))
        assertTrue("große Zahlen in Kacheln bleiben", Regex("fun zahl\\(text: String, sp: Float\\) = TextView\\(ctx\\)\\.apply \\{\\s+this\\.text = text; textSize = sp;").containsMatchIn(b))
        val m = quelle("MainActivity.kt")
        assertTrue(m.contains("Ui(this, pal, cockpit, GrosseBedienung.faktor(this))"))
        assertTrue("Dock wächst", m.contains("spalte.addView(dock(), ui.lp(-1, ui.bd(DockPlatz.HOEHE)"))
        assertTrue("Kachelraster unabhängig", m.contains("FreiLayout(this, ui.dp(Raster.abstandDp(layout.abstand)))"))
        val s = quelle("Schnellleiste.kt")
        assertTrue("Leiste wächst", s.contains("val f = GrosseBedienung.faktor(ctx)") && s.contains("m(KNOPF_DP)"))
    }

    @Test
    fun selbsttestRegeln() {
        assertFalse(Selbsttest.hausZaehlt(0L, 1000L))
        assertTrue(Selbsttest.hausZaehlt(1000L, 1000L + Selbsttest.HAUS_FRIST_MS))
        assertFalse(Selbsttest.hausZaehlt(1000L, 1001L + Selbsttest.HAUS_FRIST_MS))
        assertTrue(Selbsttest.hausAbgelaufen(1000L, 1001L + Selbsttest.HAUS_FRIST_MS))
        assertFalse(Selbsttest.hausAbgelaufen(0L, 10_000_000L))
        assertEquals(emptyList<Selbsttest.Meldung>(), Selbsttest.meldungen(null, null))
        assertEquals(listOf(Selbsttest.Meldung.HAUS_OK, Selbsttest.Meldung.ZURUECK_FEHL), Selbsttest.meldungen(true, false))
        assertEquals(listOf(Selbsttest.Meldung.HAUS_FEHL), Selbsttest.meldungen(false, null))
        assertTrue(Selbsttest.allesGut(true, true)); assertFalse(Selbsttest.allesGut(true, null))
        // Ablauf: warten → Haus kommt an → einmal gezählt
        Selbsttest.hausWarten(5_000L)
        assertTrue(Selbsttest.hausAngekommen(6_000L))
        assertEquals(true, Selbsttest.hausOk)
        assertFalse("nur einmal", Selbsttest.hausAngekommen(7_000L))
    }

    @Test
    fun einrichtungAllesZurueckAufJederSeite() {
        val e = quelle("Einrichtung.kt")
        val zeigen = e.substring(e.indexOf("private fun zeigen()"), e.indexOf("private fun ergebnisZeilen"))
        val nachWhen = zeigen.substring(zeigen.lastIndexOf("Selbsttest.Schritt.ERGEBNIS ->"))
        assertTrue("„Alles zurück auf BYD“ steht außerhalb der Schritt-Auswahl", nachWhen.contains("allesZurueckFragen()"))
        assertTrue("Rückweg auch lokal (Alias aus) und Leiste aus", e.contains("HomeSchalter.setze(this, false)") && e.contains("Schnellleiste.setzen(this, Schnellleiste.AUS)"))
        assertTrue("Kern-Rückweg", e.contains("client.homeAus()"))
        assertTrue("Meldung bei Misserfolg", e.contains("R.string.einr_haus_fehl") && e.contains("R.string.einr_zurueck_fehl"))
        for (b in listOf("bild_einr_start", "bild_einr_haus", "bild_einr_zurueck", "bild_einr_byd")) {
            assertTrue(b, e.contains("R.drawable.$b"))
            assertTrue(b, datei("src/main/res/drawable/$b.xml").contains("<vector"))
        }
        val m = quelle("MainActivity.kt")
        assertTrue("Haus-Taste meldet sich beim Selbsttest", m.contains("override fun onNewIntent") && m.contains("Selbsttest.hausAngekommen("))
    }

    @Test
    fun rechteNurDurchDenNutzerKeinTrick() {
        val h = quelle("Bedienhilfe.kt") + quelle("Tasten.kt") + quelle("Einrichtung.kt") + quelle("GrosseBedienung.kt")
        for (verboten in listOf("appops", "force_resizable", "Settings.Secure", "enabled_accessibility_services",
                "Runtime.getRuntime", "input keyevent", "dispatchGesture", "getRootInActiveWindow"))
            assertFalse(verboten, h.contains(verboten))
        assertTrue(quelle("Bedienhilfe.kt").contains("Settings.ACTION_ACCESSIBILITY_SETTINGS"))
        val x = datei("src/main/res/xml/bedienhilfe.xml")
        assertTrue(x.contains("android:canRetrieveWindowContent=\"false\""))
        assertTrue(x.contains("android:accessibilityEventTypes=\"typeWindowStateChanged\""))
        assertFalse(x.contains("canRequestFilterKeyEvents") || x.contains("canPerformGestures"))
        val man = datei("src/main/AndroidManifest.xml")
        assertTrue(man.contains("android.permission.BIND_ACCESSIBILITY_SERVICE"))
        assertTrue(Regex("\\.EinrichtungActivity\"\\s+android:exported=\"false\"").containsMatchIn(man))
        assertTrue(Regex("\\.ZurueckTestActivity\"\\s+android:exported=\"false\"").containsMatchIn(man))
        val s = quelle("Schnellleiste.kt")
        assertTrue("Knöpfe nur mit Weg", s.contains("if (Tasten.wegJetzt() != Tasten.Weg.KEINER)"))
        assertTrue(quelle("PanoptesClient.kt").contains("\"/api/launcher/v1/taste\""))
    }

    @Test
    fun texteIn5Sprachen() {
        val keys = listOf("taste_zurueck", "taste_letzte", "taste_fehlt", "taste_fehler", "hilfe_name", "hilfe_beschreibung",
            "hilfe_einstellung", "hilfe_erklaerung", "gross_titel", "gross_hinweis", "einr_titel", "einr_s1_titel", "einr_s1_text",
            "einr_einschalten", "einr_weiter", "einr_s2_titel", "einr_s2_text", "einr_ging_nicht", "einr_s3_titel", "einr_s3_text",
            "einr_testen", "einr_s4_titel", "einr_s4_text", "einr_haus_ok", "einr_haus_fehl", "einr_zurueck_ok", "einr_zurueck_fehl",
            "einr_test_seite", "einr_alles_byd", "einr_alles_byd_frage", "einr_alles_byd_ok", "einr_alles_byd_lokal",
            "einr_fehler_kern", "einr_fertig", "einr_nochmal")
        for (d in listOf("values", "values-de", "values-fr", "values-it", "values-nl")) {
            val x = datei("src/main/res/$d/strings.xml")
            for (k in keys) assertTrue("$d/$k", Regex("<string name=\"$k\">[^<]+</string>").containsMatchIn(x))
        }
    }
}
