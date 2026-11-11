package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.1.40 Nachbesserung nach E2E (E2E_0140.md): M1 Schleier, M2 Design bei Cockpit, 240 dpi Ersatzleiste, G2/G3/G4. */
class LauncherE2eNachbesserungTest {

    private fun lies(rel: String): String {
        var f = File(rel); if (!f.exists()) f = File("panoptes-launcher/$rel")
        return f.readText().replace("\r\n", "\n")
    }

    private val quelle = "src/main/java/at/greenchili/panoptes/launcher/"
    private val m by lazy { lies(quelle + "MainActivity.kt") }
    private val leiste by lazy { lies(quelle + "StatusLeiste.kt") }
    private fun block(von: String, bis: String) = m.substring(m.indexOf(von), m.indexOf(bis, m.indexOf(von)))

    private val dunkel = 0xFF0A1622.toInt()
    private val hell = 0xFFF2F5F8.toInt()

    // ---------- M1 Schleier ----------

    @Test
    fun schleierRichtetSichNachBildhelligkeit() {
        assertEquals(1f, Schleier.helligkeit(0xFFFFFFFF.toInt()), 0.001f)
        assertEquals(0f, Schleier.helligkeit(0xFF000000.toInt()), 0.001f)
        assertEquals(0.5f, Schleier.mittel(IntArray(0)), 0.001f)
        val weiss = Schleier.deckung(1f, dunkel, Schleier.AUTO)
        val schwarz = Schleier.deckung(0f, dunkel, Schleier.AUTO)
        assertTrue("helles Bild bei dunklem Design → dichter", weiss > 0.7f && weiss <= Schleier.MAX)
        assertTrue("passendes Bild → nie unter MIN", schwarz >= Schleier.MIN && schwarz < weiss)
        assertTrue("Hell-Design: dunkles Bild → dichter", Schleier.deckung(0f, hell, null) > Schleier.deckung(1f, hell, null))
        // 0.1.41 (N1): „Schwach“ bei hellem Bild auf dunklem Design nicht mehr nur MIN, sondern mindestens SCHWACH_MIN_HELL
        assertEquals(Schleier.SCHWACH_MIN_HELL, Schleier.deckung(1f, dunkel, Schleier.SCHWACH), 0.001f)
        assertTrue(Schleier.deckung(0f, dunkel, Schleier.STARK) >= Schleier.STARK_DECKUNG)
        val f = Schleier.farbe(dunkel, 0.5f)
        assertEquals("Seitenfarbe bleibt", dunkel and 0xFFFFFF, f and 0xFFFFFF)
        assertEquals(128, (f ushr 24))
    }

    @Test
    fun schleierLiegtUeberDemEigenenBildUndIstWaehlbar() {
        val a = block("private fun baueAlles()", "private fun zeigeHinweis(")
        assertTrue("Schleier direkt über dem Bild", a.indexOf("root.addView(schleier") > a.indexOf("root.addView(bild"))
        assertTrue(a.indexOf("root.addView(schleier") < a.indexOf("root.addView(video"))
        val h = block("private fun hintergrundAnwenden()", "private fun dekodieren(")
        assertTrue(h.contains("Schleier.bildHelligkeit(it)"))
        assertTrue(h.contains("Schleier.deckung(hell, ui.p.seiteBis, Schleier.wahl(this), ui.p.onSurfaceVariant)"))
        assertTrue("ohne eigenes Bild kein Schleier", h.contains("schleier.visibility = View.GONE"))
        val e = block("private fun einstellungen(", "private fun groesseName(")
        assertTrue("Wahl nur bei eigenem Bild, unter Erweitert", e.contains("if (layout.hintergrund == \"eigen\")") && e.contains("schleierWaehlen()"))
        // nie exportiert: kein Feld im Layout-Export
        assertFalse(lies(quelle + "StartLayout.kt").contains("schleier"))
    }

    // ---------- M2 Design bei Cockpit ----------

    @Test
    fun designBeiCockpitGesperrtMitHinweis() {
        val e = block("private fun einstellungen(", "private fun groesseName(")
        assertTrue(e.contains("val cockpit = layout.hintergrund == \"cockpit\""))
        assertEquals("Design, Akzent und (0.1.41) Tag/Nacht zeigen den Hinweis", 3, Regex("if \\(cockpit\\) zeigeHinweis\\(getString\\(R.string.design_cockpit_hinweis\\)\\)").findAll(e).count())
        assertTrue("Palette bleibt bei Cockpit dunkel", m.contains("if (cockpit) PanoptesStil.DUNKEL else"))
    }

    // ---------- 240 dpi Ersatzleiste + G4 ----------

    @Test
    fun ersatzleisteZeigtNurSymboleWennEsEngWird() {
        assertTrue(KartenStatus.nurSymbole(101f, 100))
        assertFalse(KartenStatus.nurSymbole(100f, 100))
        assertTrue("Beschriftung mit eigener wrap-Breite", leiste.contains("layoutParams = LinearLayout.LayoutParams(-2, -2)"))
        assertTrue(leiste.contains("beschriftungen.forEach { it.visibility = View.GONE }"))
        assertTrue("Symbol-Knopf bleibt benannt", leiste.contains("contentDescription = ctx.getString(label)"))
    }

    @Test
    fun ersatzleisteFolgtDerStatusspalte() {
        assertEquals("86 % · 312 km · P", KartenStatus.leistenZeile(Raster.SPALTE_STANDARD, 86.0, 312.0, "P", "12:30"))
        assertEquals("86 % · 12:30", KartenStatus.leistenZeile(listOf("akku", "uhr"), 86.0, 312.0, "P", "12:30"))
        assertEquals("12:30", KartenStatus.leistenZeile(listOf("uhr"), 86.0, 312.0, "P", "12:30"))
        assertEquals("—", KartenStatus.leistenZeile(emptyList(), 86.0, 312.0, "P", "12:30"))
        assertTrue(leiste.contains("if (\"musik\" in spalte) knopf("))
        assertTrue(leiste.contains("if (\"kamera\" in spalte) knopf("))
        assertTrue("Start immer", leiste.contains("\n        knopf(R.drawable.ic_pn_status, R.string.leiste_start)"))
        assertTrue(m.contains("}, layout.spalte).also { leiste = it }"))
    }

    // ---------- G2 / G3 ----------

    @Test
    fun volleSeiteLegtKachelAufNeuerSeiteAn() {
        val k = block("private fun kachelHinzufuegen()", "private fun seiteEntfernenFragen()")
        assertTrue(k.contains("Raster.seiteHinzufuegen(layout)"))
        assertTrue(k.contains("seite = aufNeuer.seiten.size - 1"))
        assertTrue(k.contains("R.string.kachel_neue_seite") && k.contains("R.string.kein_platz"))
        assertFalse("Kamera-Kachel: Beschriftung oben", block("private fun kameraKachel(", "private fun uhrKachel(").contains("CENTER_VERTICAL }\n"))
    }

    // ---------- 5 Sprachen ----------

    @Test
    fun neueTexteInFuenfSprachen() {
        val namen = listOf("abdunkelung", "abdunkelung_auto", "abdunkelung_schwach", "abdunkelung_stark", "abdunkelung_hinweis",
            "design_cockpit", "design_cockpit_hinweis", "kachel_neue_seite")
        for (d in listOf("values", "values-de", "values-fr", "values-it", "values-nl")) {
            val s = lies("src/main/res/$d/strings.xml")
            for (n in namen) assertTrue("$d/$n", s.contains("<string name=\"$n\">"))
        }
    }
}
