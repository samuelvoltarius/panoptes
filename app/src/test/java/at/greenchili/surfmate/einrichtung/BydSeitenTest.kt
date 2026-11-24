package at.greenchili.surfmate.einrichtung

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.1.43.20 BYD-Hilfe-Knöpfe: nur da, wo BYDs Seite wirklich auflöst. */
class BydSeitenTest {

    @Test
    fun actionsWieInDerFundgrube() {
        assertEquals("android.intent.action.BYD_APPSTARTMANAGEMENT", BydSeiten.ACTIONS[BydSeiten.AUTOSTART])
        assertEquals("android.intent.action.BYD_PERMISSION", BydSeiten.ACTIONS[BydSeiten.RECHTE])
        assertEquals("android.intent.action.BYD_VERSION_DETAILS", BydSeiten.ACTIONS[BydSeiten.VERSION])
    }

    @Test
    fun alleDaAlleKnoepfe() {
        val pakete = mapOf(
            "android.intent.action.BYD_APPSTARTMANAGEMENT" to "com.byd.appstartmanagement",
            "android.intent.action.BYD_PERMISSION" to "com.byd.carsettings",
            "android.intent.action.BYD_VERSION_DETAILS" to "com.byd.carsettings",
        )
        assertEquals(listOf("autostart", "rechte", "version"), BydSeiten.sichtbare({ pakete[it] }))
    }

    @Test
    fun activityFehlt_keinKnopf() {
        // resolveActivity == null für alles (z. B. ein DiLink ohne diese Seiten)
        assertTrue(BydSeiten.sichtbare({ null }).isEmpty())
        // nur die Autostart-Seite fehlt
        val ohneAutostart = BydSeiten.sichtbare({ if (it.endsWith("APPSTARTMANAGEMENT")) null else "com.byd.carsettings" })
        assertEquals(listOf("rechte", "version"), ohneAutostart)
    }

    @Test
    fun fremdeAppMitGleicherActionZaehltNicht() {
        assertTrue(BydSeiten.sichtbare({ "com.example.fake" }).isEmpty())
        assertFalse(BydSeiten.istBydPaket("com.bydfake.x"))
        assertTrue(BydSeiten.istBydPaket("com.byd.carsettings"))
    }

    @Test
    fun fehlerBeimAufloesen_keinKnopf() {
        assertTrue(BydSeiten.sichtbare({ throw SecurityException("nein") }).isEmpty())
    }

    @Test
    fun einrichtungZeigtNurAutostartUndRechte() {
        val alle = BydSeiten.sichtbare({ "com.byd.carsettings" }, listOf(BydSeiten.AUTOSTART, BydSeiten.RECHTE))
        assertEquals(listOf("autostart", "rechte"), alle)
    }

    @Test
    fun texteInAllenFuenfSprachen() {
        var res = File("src/main/res")
        if (!res.isDirectory) res = File("app/src/main/res")
        for (ordner in listOf("values", "values-de", "values-fr", "values-it", "values-nl")) {
            val xml = File(res, "$ordner/strings_einrichtung.xml").readText()
            for (n in listOf("einr_byd_seiten_titel", "einr_byd_autostart", "einr_byd_rechte")) {
                assertTrue("$ordner: $n fehlt", xml.contains("name=\"$n\""))
            }
        }
    }

    @Test
    fun autostartIstEineSperrliste_TextSagtSchalterAus() {
        // Fundgrube Teil 2 (byd-apps, DiLink 3) und AutoStartEnabler: AN = Start gesperrt, AUS = erlaubt
        var res = File("src/main/res")
        if (!res.isDirectory) res = File("app/src/main/res")
        val erwartet = mapOf("values" to "OFF", "values-de" to "Schalter AUS", "values-fr" to "ARRÊT",
            "values-it" to "SPENTO", "values-nl" to "UIT")
        for ((ordner, wort) in erwartet) {
            val xml = File(res, "$ordner/strings_einrichtung.xml").readText()
            val zeile = xml.lines().first { it.contains("name=\"einr_byd_seiten_text\"") }
            assertTrue("$ordner: $zeile", zeile.contains(wort))
        }
    }
}
