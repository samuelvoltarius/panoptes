package at.greenchili.panoptes.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.1.44 (P3, LUECKEN_0142 Punkte 5 und 6) Vertrag:
 * - Tempolimit nur belegt (Quelle osm), frisch und bei erreichbarem Kern; ohne Wert nichts; leise Warnung nur, wenn
 *   eingeschaltet (ab Werk aus), nie ohne Tempo oder Limit.
 * - Kern-Aus: alte Werte mit „zuletzt vor X min“, Live-Werte (Tempo, Gang, Limit, Ladeleistung) nie alt.
 * - Messpunkte: Start/Haus bis sichtbar und bis erste Werte, Kern-Wiederkehr, je einmal.
 */
class LauncherTempoMessenTest {

    private fun summary(extra: String = ""): LauncherSummary = LauncherContract.parseSummary(
        """{"state":{"speedKmh":57,"gear":"D","acc":"on"},"range":{"socPct":70,"totalKm":250},
            "charging":{"active":true,"kw":11,"targetPct":80,"etaMin":30}$extra}""")

    @Test
    fun limitNurBelegtUndFrisch() {
        val s = summary(""","tempolimit":{"kmh":50,"quelle":"osm","alterMs":2000},"tempoWarnung":true""")
        assertEquals(50, s.tempolimit?.kmh)
        assertTrue(s.tempoWarnung)
        assertEquals(50, Tempolimit.anzeige(s.tempolimit, true))
        assertNull("Kern weg: nichts", Tempolimit.anzeige(s.tempolimit, false))
        assertNull("zu alt", Tempolimit.anzeige(s.tempolimit!!.copy(alterMs = Tempolimit.FRISCH_MS + 1), true))
        assertNull("fremde Quelle", Tempolimit.parse(JSONObject("""{"kmh":50,"quelle":"geraten","alterMs":0}""")))
        assertNull("unsinnig", Tempolimit.parse(JSONObject("""{"kmh":0,"quelle":"osm","alterMs":0}""")))
        assertNull("ohne Feld", summary().tempolimit)
        assertFalse("Warnung ab Werk aus", summary().tempoWarnung)
        assertNull(LauncherContract.parseSummary("""{"tempolimit":null}""").tempolimit)
    }

    @Test
    fun warnungLeiseMitSpielraum() {
        assertEquals(3, Tempolimit.toleranz(30))
        assertEquals(3, Tempolimit.toleranz(50))
        assertEquals(5, Tempolimit.toleranz(100))
        assertEquals(7, Tempolimit.toleranz(130))
        assertFalse(Tempolimit.ueber(52.0, 50))
        assertTrue(Tempolimit.ueber(53.0, 50))
        assertFalse("ohne Limit nie", Tempolimit.ueber(200.0, null))
        assertFalse("ohne Tempo nie", Tempolimit.ueber(null, 50))
    }

    @Test
    fun kernAusZeigtAlterNieFrisch() {
        val m = Frische.Merker()
        val s = summary(""","tempolimit":{"kmh":50,"quelle":"osm","alterMs":0}""")
        assertNull(m.alt(0))
        m.gut(s, 1_000_000L)
        val alt = m.alt(1_000_000L + 3 * 60_000L)!!
        assertEquals(70.0, alt.socPct!!, 0.0)
        assertNull("Tempo nie alt", alt.speedKmh)
        assertNull("Gang nie alt", alt.gear)
        assertNull("Limit nie alt", alt.tempolimit)
        assertNull("Ladeleistung nie alt", alt.charging?.kw)
        assertEquals(3L, Frische.minuten(1_000_000L, 1_000_000L + 3 * 60_000L + 59_000L))
        assertNull("viel zu alt: wie bisher —", m.alt(1_000_000L + Frische.HOECHSTENS_MS + 1))
    }

    @Test
    fun messpunkteJeEinmal() {
        val u = Messpunkte.Uhr()
        u.start(1000)
        assertEquals("PANOPTES_MESSUNG art=start_bis_sichtbar ms=400", u.sichtbar(1400))
        assertNull("nur einmal", u.sichtbar(1500))
        assertEquals(listOf("PANOPTES_MESSUNG art=start_bis_werte ms=900"), u.daten(true, 1900))
        assertTrue(u.daten(true, 2000).isEmpty())
        assertTrue(u.daten(false, 5000).isEmpty())
        assertTrue(u.daten(false, 6000).isEmpty())
        assertEquals(listOf("PANOPTES_MESSUNG art=kern_wieder ms=4000"), u.daten(true, 9000))
        u.haus(20_000)
        assertEquals("PANOPTES_MESSUNG art=haus_bis_sichtbar ms=150", u.sichtbar(20_150))
        assertEquals(listOf("PANOPTES_MESSUNG art=haus_bis_werte ms=600"), u.daten(true, 20_600))
    }

    private fun quelle(name: String): String {
        val f = listOf(File("src/main/java/at/greenchili/panoptes/launcher/$name"),
            File("panoptes-launcher/src/main/java/at/greenchili/panoptes/launcher/$name")).first { it.exists() }
        return f.readText().replace("\r\n", "\n")
    }

    @Test
    fun eingehaengtUndTexte() {
        val m = quelle("MainActivity.kt")
        assertTrue(m.contains("summary = if (ok) s else frische.alt(jetzt)"))
        assertTrue(m.contains("messUhr.daten(ok, jetzt).forEach { Messpunkte.log(it) }"))
        assertTrue(m.contains("Messpunkte.beiSichtbar(window.decorView)"))
        assertTrue(m.contains("Tempolimit.setze(tempoSchild, tempo, s, online, ui.p.onSurface, ui.p.warning)"))
        assertTrue(m.contains("frische.hinweis(this@MainActivity, online, System.currentTimeMillis())"))
        assertTrue(quelle("ZusatzKacheln.kt").contains("Tempolimit.setze(schild, zahl, s, true, ui.p.onSurface, ui.p.warning)"))
        for (d in listOf("values", "values-de", "values-fr", "values-it", "values-nl")) {
            val f = listOf(File("src/main/res/$d/strings.xml"), File("panoptes-launcher/src/main/res/$d/strings.xml")).first { it.exists() }
            val x = f.readText()
            for (k in listOf("tempolimit_beschreibung", "zuletzt_gerade", "zuletzt_vor_min")) assertTrue("$d $k", x.contains("name=\"$k\""))
            assertTrue(d, Regex("name=\"zuletzt_vor_min\">[^<]*%1\\\$d").containsMatchIn(x))
        }
    }
}
