package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 0.1.38.6 Komfort im Launcher (04.11.: „Sitzheizung steuern und Klimaanlage und so weiter“): nur feste Namen über
 * den engen Kern-Weg `klima`, große Kacheln, Kachel nur bei lesbarem Zustand, nichts aus der Tabu-Liste.
 */
class LauncherKomfortTest {

    private fun main() = File("src/main/java/at/greenchili/panoptes/launcher/MainActivity.kt").readText().replace("\r\n", "\n")

    @Test
    fun festeNamenUndKeinTabu() {
        assertEquals(14, LauncherContract.KOMFORT_AKTIONEN.size)
        assertEquals(6, LauncherContract.KLIMA_AKTIONEN.size)
        assertEquals("""{"aktion":"sitz_fahrer"}""", LauncherContract.klimaBody("sitz_fahrer"))
        assertEquals("""{"aktion":"entfrosten_vorn_an"}""", LauncherContract.klimaBody("entfrosten_vorn_an"))
        for (boese in listOf("hupe", "horn", "flash", "licht", "blinker", "aufsperren", "unlock", "lock", "find", "alarm",
            "fenster", "window", "dach", "kofferraum", "trunk", "spiegel", "sitzspeicher", "seat_memory", "")) {
            try {
                LauncherContract.klimaBody(boese)
                fail(boese)
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun jedeGesendeteKomfortAktionIstErlaubt() {
        val m = main()
        val feld = m.substring(m.indexOf("private fun komfortFeld()"), m.indexOf("private fun stufeKlartext("))
        val namen = Regex("""klimaSenden\(\s*"([a-z_]+)"""").findAll(feld).map { it.groupValues[1] }.toSet() +
            Regex(""""([a-z_]+_(?:an|aus))"""").findAll(feld).map { it.groupValues[1] }.toSet() +
            Regex("""kachel\([^\n]*"([a-z_]+)"\)""").findAll(feld).map { it.groupValues[1] }.toSet()
        assertTrue(namen.toString(), namen.isNotEmpty())
        namen.forEach { assertTrue(it, it in LauncherContract.KOMFORT_AKTIONEN) }
        // Jede Komfort-Aktion hat eine Bedienfläche
        LauncherContract.KOMFORT_AKTIONEN.forEach { assertTrue(it, feld.contains("\"$it\"")) }
        // Sendet nur über den vorhandenen Weg, keine zweite Leitung zum Auto
        assertFalse(feld.contains("client."))
        assertFalse(feld.contains("oeffnePanoptes"))
    }

    @Test
    fun kachelNurBeiLesbaremZustand() {
        val m = main()
        val feld = m.substring(m.indexOf("private fun komfortFeld()"), m.indexOf("private fun stufeKlartext("))
        listOf("sitzFahrer", "sitzBeifahrer", "lueftungFahrer", "lueftungBeifahrer", "lenkrad", "umluft").forEach {
            assertTrue(it, feld.contains("k?.$it?.let"))
        }
        // große Flächen (REGEL 50+): Kacheln und An/Aus mindestens GROSS_KNOPF (>= 64)
        assertTrue(feld.contains("minimumHeight = ui.dp(GROSS_KNOPF)"))
        assertTrue(feld.contains("ui.dp(GROSS_KNOPF), ui.dp(GROSS_KNOPF)"))
        assertTrue(Regex("""const val GROSS_KNOPF = (\d+)""").find(m)!!.groupValues[1].toInt() >= 64)
        // Rückmeldung je Tipp über denselben Weg wie das Klima
        val senden = m.substring(m.indexOf("private fun klimaSenden("), m.indexOf("private fun klimaGrund("))
        assertTrue(senden.contains("klimaRueckmeldung(aktion, antwort.klima)"))
        assertTrue(senden.contains("klimaGrund(antwort.grund)"))
        assertTrue(m.contains("private fun komfortFeld()") && m.contains("komfortFeld() }"))
    }

    @Test
    fun antwortLiestKomfortstand() {
        val a = LauncherContract.parseKlima(
            """{"ok":true,"aktion":"sitz_fahrer","klima":{"sollC":null,"stufe":3,"innenC":null,"an":true,
               "sitzFahrer":2,"sitzBeifahrer":0,"lueftungFahrer":null,"lueftungBeifahrer":7,"lenkrad":true,"umluft":false}}""")
        assertTrue(a.ok)
        val k = a.klima!!
        assertEquals(2, k.sitzFahrer)
        assertEquals(0, k.sitzBeifahrer)
        assertNull(k.lueftungFahrer)          // nicht lesbar: keine Kachel
        assertNull(k.lueftungBeifahrer)       // Wert außerhalb 0..2: verworfen
        assertEquals(true, k.lenkrad)
        assertEquals(false, k.umluft)
        val alt = LauncherContract.parseKlima("""{"ok":true,"klima":{"sollC":21.0,"stufe":2,"innenC":null,"an":true}}""").klima!!
        assertNull(alt.sitzFahrer)
        assertNull(alt.lenkrad)
    }

    @Test
    fun textenInAllenSprachenUndVersion() {
        val namen = listOf("komfort_oeffnen", "komfort_titel", "komfort_an", "komfort_aus", "komfort_auto", "komfort_entfrosten_vorn",
            "komfort_entfrosten_hinten", "komfort_nur_geblaese", "komfort_sitz_fahrer", "komfort_sitz_beifahrer", "komfort_lueftung_fahrer",
            "komfort_lueftung_beifahrer", "komfort_lenkrad", "komfort_umluft", "komfort_stufe_1", "komfort_stufe_2", "komfort_umluft_ein",
            "komfort_frischluft", "komfort_neu", "komfort_nichts_lesbar")
        for (sprache in listOf("", "-de", "-fr", "-it", "-nl")) {
            val s = File("src/main/res/values$sprache/strings.xml").readText()
            namen.forEach { assertTrue("$sprache $it", s.contains("name=\"$it\"")) }
        }
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("versionCode = 6") && gradle.contains("versionName = \"0.1.38.6\""))
    }

    @Test
    fun unbekanntesModellKeineTemperaturBedienung() {
        val k = LauncherContract.parseKlima("""{"ok":true,"klima":{"sollC":null,"stufe":3,"innenC":null,"an":true,"tempArt":"unbekannt"}}""").klima!!
        assertEquals("unbekannt", k.tempArt)
        assertEquals("stufen", LauncherContract.parseKlima("""{"ok":true,"klima":{"tempArt":"stufen","stufe":1}}""").klima!!.tempArt)
        assertEquals("grad", LauncherContract.parseKlima("""{"ok":true,"klima":{"tempArt":"grad","stufe":1}}""").klima!!.tempArt)
        assertNull(LauncherContract.parseKlima("""{"ok":true,"klima":{"tempArt":"quatsch","stufe":1}}""").klima!!.tempArt)
        assertNull(LauncherContract.parseKlima("""{"ok":true,"klima":{"stufe":1}}""").klima!!.tempArt)   // älterer Kern
        val m = main()
        val senden = m.substring(m.indexOf("private fun klimaSenden("), m.indexOf("private fun klimaGrund("))
        // Sperre sitzt vor dem Senden, mit Hinweis statt stillem Befehl
        assertTrue(senden.indexOf("tempArt == \"unbekannt\"") in 0 until senden.indexOf("client.klima(aktion)"))
        assertTrue(senden.contains("R.string.klima_temp_unbekannt"))
        assertTrue(m.contains("tempKnoepfe.forEach { it.alpha = if (k?.tempArt == \"unbekannt\") 0.35f else 1f }"))
        for (sprache in listOf("", "-de", "-fr", "-it", "-nl")) {
            assertTrue(sprache, File("src/main/res/values$sprache/strings.xml").readText().contains("name=\"klima_temp_unbekannt\""))
        }
    }
}
