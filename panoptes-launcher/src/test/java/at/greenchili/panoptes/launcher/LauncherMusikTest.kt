package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.1.41 (LM, Projektinhaber „1-10 gleich einbauen“, Befund CARPLAY_MUSIK_0141.md Abschnitt 4): Musik-Kachel steuert auch
 * CarPlay. Vertrag: Telecom-Sitzung nie, spielend > CarPlay/Android Auto > Audiofokus-Paket > Rest, Steuern über
 * TransportControls; ohne Benachrichtigungszugriff Hinweis + Knopf zur Android-Seite; „Musik beim Start
 * weiterspielen“ ab Werk aus, nur play() auf der letzten Sitzung, sonst nur deren App öffnen.
 */
class LauncherMusikTest {

    /** Fake-Sitzung wie MediaController: Paket + PlaybackState-Zustand (3 = PLAYING, 2 = PAUSED). */
    private data class S(val paket: String, val zustand: Int?)

    private val telecom = S("com.android.server.telecom", null)
    private val carplay = S(Raster.CARPLAY, 2)
    private val aa = S(Raster.ANDROID_AUTO, 2)
    private val spotify = S("com.spotify.music", 2)
    private val radio = S("com.byd.radio", 2)

    private fun w(l: List<S>, fokus: String? = null) = MedienWahl.waehle(l, { it.paket }, { it.zustand }, fokus)

    @Test
    fun telecomNie() {
        assertNull(w(listOf(telecom)))                                   // Befund: einzige Sitzung ohne iPhone
        assertEquals(spotify, w(listOf(telecom, spotify)))               // früher: erste = Telecom
        assertNull(w(listOf(telecom.copy(zustand = 3))))
    }

    @Test
    fun reihenfolge() {
        val spielt = spotify.copy(zustand = 3)
        assertEquals(spielt, w(listOf(telecom, carplay, spielt)))        // 1. spielend
        assertEquals(carplay, w(listOf(telecom, spotify, aa, carplay)))  // 2. CarPlay vor AA vor Rest
        assertEquals(aa, w(listOf(radio, aa)))
        assertEquals(radio, w(listOf(spotify, radio), fokus = "com.byd.radio"))   // 3. Audiofokus-Paket
        assertEquals(spotify, w(listOf(spotify, radio), fokus = "weg.paket"))     // 4. Rest
        assertEquals(carplay, w(listOf(radio, carplay), fokus = "com.byd.radio")) // Projektion vor Fokus
        assertNull(w(emptyList()))
    }

    @Test
    fun weiterspielenAbWerkAusUndNurEinmal() {
        val m = quelle("MusikStart.kt")
        assertTrue(m.contains("getBoolean(KEY_AN, false)"))
        assertTrue(m.contains("if (geprueft) return"))
        val leer = mapOf<String, Boolean>()
        assertEquals(Weiterspielen.Aktion.Nichts, Weiterspielen.entscheiden(false, "com.spotify.music", mapOf("com.spotify.music" to false), 1, true))
        assertEquals(Weiterspielen.Aktion.Nichts, Weiterspielen.entscheiden(true, null, leer, 3, true))
    }

    @Test
    fun weiterspielenEntscheidung() {
        val sp = "com.spotify.music"
        assertEquals(Weiterspielen.Aktion.Spielen(sp), Weiterspielen.entscheiden(true, sp, mapOf(sp to false, "com.android.server.telecom" to false), 1, true))
        // spielt schon etwas → nichts (auch nicht die letzte App)
        assertEquals(Weiterspielen.Aktion.Nichts, Weiterspielen.entscheiden(true, sp, mapOf("com.byd.radio" to true, sp to false), 1, true))
        // Telecom „spielt“ zählt nicht
        assertEquals(Weiterspielen.Aktion.Spielen(sp), Weiterspielen.entscheiden(true, sp, mapOf("com.android.server.telecom" to true, sp to false), 1, true))
        // keine Sitzung: erst warten, beim letzten Versuch nur die App öffnen; App weg → nichts
        assertEquals(Weiterspielen.Aktion.Warten, Weiterspielen.entscheiden(true, Raster.CARPLAY, emptyMap(), 1, true))
        assertEquals(Weiterspielen.Aktion.Oeffnen(Raster.CARPLAY), Weiterspielen.entscheiden(true, Raster.CARPLAY, emptyMap(), Weiterspielen.VERSUCHE, true))
        assertEquals(Weiterspielen.Aktion.Nichts, Weiterspielen.entscheiden(true, Raster.CARPLAY, emptyMap(), Weiterspielen.VERSUCHE, false))
        // gemerkt wird nur ein echtes, spielendes Paket
        assertEquals(sp, Weiterspielen.merken(sp, true))
        assertNull(Weiterspielen.merken(sp, false))
        assertNull(Weiterspielen.merken("com.android.server.telecom", true))
        assertNull(Weiterspielen.merken(null, true))
    }

    private fun basis() = if (File("src/main").isDirectory) File(".") else File("panoptes-launcher")
    private fun quelle(name: String) =
        File(basis(), "src/main/java/at/greenchili/panoptes/launcher/$name").readText().replace("\r\n", "\n")

    /** Quelltext-Vertrag: Auswahl über MedienWahl, Steuern über TransportControls, Zugriff nur über die Android-Seite. */
    @Test
    fun vertrag() {
        val med = quelle("Medien.kt")
        assertTrue(med.contains("MedienWahl.waehle(sitzungen(ctx)"))
        assertFalse("nicht mehr „die erste“", med.contains("?: liste.firstOrNull()"))
        assertTrue(med.contains("transportControls?.play()"))
        val m = quelle("MainActivity.kt")
        assertTrue(m.contains("MedienQuelle.aktuell(this@MainActivity, MusikStart.letztes(this@MainActivity))"))
        assertTrue(m.contains("MusikStart.beimStart(this, main"))
        assertTrue(m.contains("val tc = c.transportControls"))
        assertTrue(m.contains("R.string.medien_zugriff_hinweis"))
        assertTrue(m.contains("ui.act(R.drawable.ic_pn_settings, getString(R.string.medien_zugriff_erlauben)) { zugriffSeite() }"))
        assertTrue(m.contains("startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))"))
        assertTrue(m.contains("schalter(R.string.musik_weiterspielen, MusikStart.an(this))"))
        // nie selbst erteilen
        for (f in listOf(m, med, quelle("MusikStart.kt")))
            assertFalse(f.contains("enabled_notification_listeners\",") || f.contains("cmd notification") || f.contains("allow_listener"))
        for (o in listOf("values", "values-de", "values-fr", "values-it", "values-nl")) {
            val s = File(basis(), "src/main/res/$o/strings.xml").readText()
            for (k in listOf("medien_zugriff_hinweis", "medien_zugriff_erlauben", "musik_weiterspielen"))
                assertTrue("$o: $k", s.contains("name=\"$k\""))
        }
    }
}
