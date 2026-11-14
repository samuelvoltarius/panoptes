package at.greenchili.panoptes.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.1.44 (L1 „Live-Umgebung“, 12.11.): Skizze um das eigene Auto aus der Kern-Erkennung. Kein Kamerabild,
 * keine Warnfarbe, ab Werk aus; ohne frisches Ergebnis keine Objekte.
 */
class LauncherL1Test {
    private fun basis() = if (File("src/main").isDirectory) File(".") else File("panoptes-launcher")
    private fun lies(rel: String) = File(basis(), rel).readText().replace("\r\n", "\n")
    private fun quelle(n: String) = lies("src/main/java/at/greenchili/panoptes/launcher/$n")

    private val live = """{"an":true,"status":"live","hz":4.6,"objekte":[
        {"seite":"front","quer":0,"zone":"nah","typ":"auto"},
        {"seite":"rear","quer":1,"zone":"mittel","typ":"lkw"},
        {"seite":"left","quer":-1,"zone":"fern","typ":"person"},
        {"seite":"right","quer":0,"zone":"nah","typ":"rad"},
        {"seite":"oben","quer":0,"zone":"nah","typ":"auto"},
        {"seite":"front","quer":7,"zone":"nah","typ":"ufo"}]}"""

    @Test
    fun antwortLesenUnbekanntesFaelltWeg() {
        val s = Umgebung.parse(live)!!
        assertTrue(s.an)
        assertEquals("live", s.status)
        assertEquals(4, s.objekte.size)
        assertEquals(Umgebung.Objekt("front", 0, "nah", "auto"), s.objekte[0])
        assertNull(Umgebung.parse("kein json"))
        val alt = Umgebung.parse("""{"an":true,"status":"keine_erkennung","objekte":[{"seite":"front","quer":0,"zone":"nah","typ":"auto"}]}""")!!
        assertTrue("ohne frisches Ergebnis keine Objekte", alt.objekte.isEmpty())
        assertFalse(Umgebung.zeigtObjekte(alt))
        assertEquals("unbekannter Status = keine Erkennung", "keine_erkennung", Umgebung.parse("""{"an":true,"status":"frei"}""")!!.status)
        assertEquals("""{"an":true}""", Umgebung.schaltenBody(true))
    }

    @Test
    fun lageUmDasAuto() {
        assertArrayEquals(floatArrayOf(0f, -3.2f), Umgebung.lage(Umgebung.Objekt("front", 0, "nah", "auto")), 0.001f)
        assertArrayEquals(floatArrayOf(-1.4f, 4.6f), Umgebung.lage(Umgebung.Objekt("rear", 1, "mittel", "auto")), 0.001f)
        assertArrayEquals("rechts: Bild-links = vorne", floatArrayOf(2.1f, -1.4f), Umgebung.lage(Umgebung.Objekt("right", -1, "nah", "auto")), 0.001f)
        assertArrayEquals("links: Bild-links = hinten", floatArrayOf(-4.9f, 1.4f), Umgebung.lage(Umgebung.Objekt("left", -1, "fern", "auto")), 0.001f)
    }

    @Test
    fun skizzeOhneBildOhneWarnfarbe() {
        val ordner = File(basis(), "build/l1-vorschau").apply { mkdirs() }
        for ((n, p) in listOf("dunkel" to PanoptesStil.DUNKEL, "hell" to PanoptesStil.HELL)) {
            val l = SvgLeinwand(640, 640)
            l.rechteck(0f, 0f, 640f, 640f, 0f, Voll(p.surface))
            Umgebung.zeichne(l, Umgebung.parse(live), p, 640f, 640f)
            val svg = l.svg()
            for (v in listOf("<image", "href", "base64")) assertFalse(v, svg.contains(v))
            assertFalse("keine Warnfarbe", svg.contains(Farbe.css(p.danger)) || svg.contains(Farbe.css(p.warning)))
            File(ordner, "umgebung_live_$n.svg").writeText(svg)
            val leer = SvgLeinwand(640, 640).also { Umgebung.zeichne(it, Umgebung.parse("""{"an":true,"status":"keine_erkennung"}"""), p, 640f, 640f) }.svg()
            assertTrue("ohne Erkennung weniger gezeichnet (nur Ringe und gedimmtes Auto)", leer.length < svg.length)
            assertFalse("Auto gedimmt, nicht in Aktivfarbe", leer.contains(Farbe.css(p.aqua)))
            File(ordner, "umgebung_keine_$n.svg").writeText(leer)
        }
    }

    @Test
    fun statusTexteInFuenfSprachen() {
        val keys = listOf("widget_umgebung", "umgebung_aus", "umgebung_einschalten", "umgebung_ausschalten", "umgebung_startet",
            "umgebung_live", "umgebung_verzoegert", "umgebung_keine", "umgebung_leistung", "umgebung_warm", "umgebung_modell")
        for (d in listOf("values", "values-de", "values-fr", "values-it", "values-nl")) {
            val x = lies("src/main/res/$d/strings.xml")
            for (k in keys) assertTrue("$d/$k", x.contains("name=\"$k\""))
            assertTrue("$d: Platzhalter", Regex("name=\"umgebung_verzoegert\">[^<]*%1\\\$s").containsMatchIn(x))
        }
        val de = lies("src/main/res/values-de/strings.xml")
        assertTrue(de.contains("ersetzt keine Assistenzsysteme"))
        assertEquals(R.string.umgebung_keine, Umgebung.statusText(null))
        assertEquals(R.string.umgebung_leistung, Umgebung.statusText(Umgebung.Stand(true, "leistung", 1.0, emptyList())))
    }

    @Test
    fun eingehaengtAbWerkAusUndNurSichtbarAbgefragt() {
        assertTrue(Kachel.values().contains(Kachel.UMGEBUNG))
        val m = quelle("MainActivity.kt")
        assertTrue(m.contains("Kachel.UMGEBUNG -> UmgebungKachel(this, ui, client)"))
        assertTrue(m.contains("Kachel.UMGEBUNG -> R.string.widget_umgebung"))
        val u = quelle("Umgebung.kt")
        assertTrue(u.contains("override fun onWindowVisibilityChanged(v: Int)"))
        assertTrue(u.contains("override fun onDetachedFromWindow() { stoppen()"))
        for (v in listOf("Camera", "SurfaceView", "TextureView", "VideoView", "MediaPlayer", "Vibrator", "ToneGenerator", "FileOutputStream"))
            assertFalse(v, u.contains(v))
        assertEquals(250L, Umgebung.TAKT_AN_MS)
        val vorlagen = File(basis(), "src/main/assets/vorlagen").listFiles()!!.filter { it.name.endsWith(".json") }
        for (f in vorlagen) assertFalse("ab Werk in keiner Vorlage: ${f.name}", f.readText().contains("\"umgebung\""))
        val kern = File(basis(), "../app/src/main/java/at/greenchili/surfmate/server/LauncherLayoutPruefung.java").readText()
        assertTrue(kern.contains("\"umgebung\""))
    }
}
