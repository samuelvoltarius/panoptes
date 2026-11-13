package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fixrunde nach E2E 0.1.43b (G1–G5, nur Launcher): Musik-Karte der Vorlage Fahrzeug, Schloss-Plakette der gezeichneten
 * Grundform, Scrollposition der Bearbeiten-Leiste, Klima-Leiste und „Hinzufügen“ mit „Große Bedienung“, Leistentext.
 */
class LauncherFix0143bTest {
    private fun basis() = if (File("src/main").isDirectory) File(".") else File("panoptes-launcher")
    private fun quelle(n: String) = File(basis(), "src/main/java/at/greenchili/panoptes/launcher/$n").readText().replace("\r\n", "\n")
    private val main get() = quelle("MainActivity.kt")

    @Test
    fun g1MusikKarteFlach() {
        assertTrue(MusikKartePlatz.flach(1)); assertFalse(MusikKartePlatz.flach(2)); assertFalse(MusikKartePlatz.flach(3))
        assertTrue("flach ohne Zugriff: nur Titel + Knopf", MusikKartePlatz.kompakt(1, erlaubt = false))
        assertFalse("mit Zugriff normale Karte", MusikKartePlatz.kompakt(1, erlaubt = true))
        assertFalse("große Karte unverändert", MusikKartePlatz.kompakt(2, erlaubt = false))
        // Höhe: Rand + Titel + Knopf passen in eine Rasterzeile auch bei 240 dpi (≈ 100 dp)
        val hoehe = 2 * (MusikKartePlatz.RAND_FLACH - 2) + 18 + 6 + MusikKartePlatz.KNOPF_FLACH
        assertTrue("$hoehe dp", hoehe <= 100)
        val k = main.substringAfter("private fun medienKarte(").substringBefore("private fun ordnerKachel(")
        assertTrue(k.contains("val c = spalte(if (flach) MusikKartePlatz.RAND_FLACH else 20)"))
        assertTrue("Knopf vor die Quellen-Zeile", k.contains("c.addView(zugriff, c.childCount - 1, ui.lp(-1, -2, oben = 6))"))
        assertTrue(k.contains("zugriff.minimumHeight = ui.bd(MusikKartePlatz.KNOPF_FLACH)"))
        assertTrue(k.contains("quelle.visibility = if (kompakt) View.GONE else View.VISIBLE; tasten.visibility = if (kompakt) View.GONE else View.VISIBLE"))
        assertTrue("Hinweis bleibt für die Sprachausgabe", k.contains("zugriff.contentDescription = getString(R.string.medien_zugriff_hinweis)"))
        // Vorlage Fahrzeug hat die flache Musik-Karte (Grund des Befunds)
        val v = File(basis(), "src/main/assets/vorlagen/fahrzeug.json").readText()
        assertTrue(Regex("\"h\"\\s*:\\s*1,\\s*\"kachel\"\\s*:\\s*\"musik\"").containsMatchIn(v))
    }

    @Test
    fun g2PlaketteImmerInDerFlaeche() {
        // Höhenbegrenzte, flache und breite Flächen; Mitte wie AutoAnsicht (h / 2)
        for (f in FahrzeugStart.Form.values()) for ((w, h) in listOf(1200f to 300f, 600f to 200f, 900f to 520f, 400f to 400f, 1600f to 240f)) {
            val s = FahrzeugStart.laenge(w, h)
            val oben = FahrzeugStart.plaketteOben(f, h / 2f, s)
            assertTrue("$f ${w}x$h: Plakette oben $oben", oben >= h * 0.03f)
            assertTrue("$f ${w}x$h: Bodenlicht unten", FahrzeugStart.bodenUnten(h / 2f, s) <= h)
            assertTrue("$f ${w}x$h: Breite", s * 1.22f <= w + 0.5f)
        }
        // vorher (h / 0.62) lag die Plakette bei höhenbegrenzter Fläche außerhalb
        assertTrue(FahrzeugStart.plaketteOben(FahrzeugStart.Form.KOMPAKT, 150f, 300f / 0.62f) < 0f)
    }

    @Test
    fun g3LeisteBehaeltLage() {
        Leistenlage.zuruecksetzen(); assertEquals(0, Leistenlage.x)
        Leistenlage.merken(420); assertEquals(420, Leistenlage.x)
        Leistenlage.merken(-5); assertEquals(0, Leistenlage.x)
        Leistenlage.zuruecksetzen()
        val l = main.substringAfter("private fun bearbeitenLeiste(").substringBefore("return z")
        assertTrue(l.contains("Leistenlage.einhaengen(this)"))
        assertTrue("Abstand vor „Fertig“", l.contains("ui.lp(0, -2, 1f, rechts = 14)"))
        assertTrue("weicher Rand statt halb verdecktem Knopf", l.contains("isHorizontalFadingEdgeEnabled = true"))
        assertTrue("Fertig setzt die Lage zurück", l.contains("Leistenlage.zuruecksetzen()"))
    }

    @Test
    fun g4GrosseBedienungKlimaUndHinzufuegen() {
        // 1280 dp (Surf, 160 dpi): normal und groß bleibt die volle Klima-Leiste im Dock
        assertFalse(DockPlatz.kompakt(1280, 1f)); assertFalse(DockPlatz.kompakt(1280, GrosseBedienung.FAKTOR))
        for (bedien in listOf(1f, GrosseBedienung.FAKTOR)) for (breite in listOf(853, 960, 1024, 1280, 1920)) {
            val kompakt = DockPlatz.kompakt(breite, bedien)
            val summe = DockPlatz.fest(bedien) + DockPlatz.klimaBreite(bedien, kompakt) + DockPlatz.favsMin(bedien)
            if (!kompakt) assertTrue("$breite/$bedien", summe <= breite)
        }
        assertTrue(DockPlatz.klimaBreite(GrosseBedienung.FAKTOR, true) < DockPlatz.klimaBreite(GrosseBedienung.FAKTOR, false))
        assertTrue("wächst mit Großer Bedienung", DockPlatz.klimaBreite(GrosseBedienung.FAKTOR, false) > DockPlatz.klimaBreite(1f, false))
        val d = main.substringAfter("private fun dock(").substringBefore("private fun klimaBedienung(")
        assertTrue("feste Breite statt Inhaltsbreite", d.contains("z.addView(klimaBedienung(kompakt, mitKomfort), ui.lp(ui.dp(DockPlatz.klimaBreite(ui.bedien, kompakt)), ui.bd(DockPlatz.KLIMA_HOEHE), rechts = 10))"))
        assertFalse(d.contains("z.addView(klimaBedienung(), ui.lp(-2"))
        assertTrue("Mindestbreite der Temperatur passt zur Rechnung", main.contains("minWidth = ui.dp(64); gravity = Gravity.CENTER; maxLines = 1"))
        // „Hinzufügen“ in der Reiterleiste: wächst mit, keine feste Breite mehr
        assertTrue(main.contains("schublade(Ziel.SCHNELL) }.apply { minimumWidth = ui.bd(104) }, ui.lp(-2, ui.bd(80)))"))
        assertFalse(main.contains("schublade(Ziel.SCHNELL) }, ui.lp(ui.dp(104), ui.dp(80)))"))
    }

    @Test
    fun g5LeistentextNenntAlleKnoepfe() {
        val erwartet = mapOf(
            "values" to listOf("Back", "Last app"), "values-de" to listOf("Zurück", "Letzte App"),
            "values-fr" to listOf("Retour", "Dernière app"), "values-it" to listOf("Indietro", "Ultima app"),
            "values-nl" to listOf("Terug", "Vorige app"))
        for ((ordner, woerter) in erwartet) {
            val x = File(basis(), "src/main/res/$ordner/strings.xml").readText()
            val t = Regex("name=\"schnell_hinweis\">([^<]*)<").find(x)!!.groupValues[1]
            for (w in woerter) assertTrue("$ordner: $w", t.contains(w))
            // gleiche Wörter wie auf den Knöpfen
            for (k in listOf("taste_zurueck", "taste_letzte")) {
                val knopf = Regex("name=\"$k\">([^<]*)<").find(x)!!.groupValues[1]
                assertTrue("$ordner: $knopf", t.contains(knopf))
            }
            for (verboten in listOf("Erprobung", "Testversion", "trial", "beta", "essai", "prova", "proef")) assertFalse("$ordner: $verboten", t.contains(verboten, ignoreCase = true))
            if (ordner == "values-fr") assertFalse("Apostroph escaped", Regex("[^\\\\]'").containsMatchIn(t))
        }
    }
}
