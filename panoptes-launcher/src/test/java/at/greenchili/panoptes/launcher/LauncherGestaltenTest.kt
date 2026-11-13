package at.greenchili.panoptes.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.1.40 Paket L2 „Launcher selbst gestalten“ (eigenes Design, nichts übernommen), Punkte 1–8. */
class LauncherGestaltenTest {

    private fun lies(rel: String): String {
        var f = File(rel); if (!f.exists()) f = File("panoptes-launcher/$rel")
        return f.readText().replace("\r\n", "\n")
    }

    private val m by lazy { lies("src/main/java/at/greenchili/panoptes/launcher/MainActivity.kt") }
    private fun block(von: String, bis: String) = m.substring(m.indexOf(von), m.indexOf(bis, m.indexOf(von)))

    // ---------- 1 Auswahl-Fehler ----------

    @Test
    fun auswahlZeigtDieListeAuchMitHinweis() {
        val a = block("private fun auswahl(", "private fun auswahlKopf(")
        assertFalse("setMessage verdrängt setItems", a.contains("setMessage"))
        assertTrue(a.contains("b.setItems(Auswahl.eintraege(punkte))"))
        assertTrue("Hinweis im Kopf über der Liste", a.contains("b.setCustomTitle(auswahlKopf(titel, fuss))"))
        // Die Einträge sind wirklich da: Größe (3) und Hintergrund (mind. 2) liefern Listenzeilen, in Reihenfolge
        val groesse = LauncherGroesse.WAHL.map { it to {} }
        assertEquals(3, Auswahl.eintraege(groesse).size)
        assertArrayEquals(LauncherGroesse.WAHL.toTypedArray(), Auswahl.eintraege(groesse))
        assertEquals(listOf("panoptes", "cockpit"), Raster.hintergrundWahl(false, false, false))
        assertEquals(listOf("panoptes", "cockpit", "bild", "video", "eigen"), Raster.hintergrundWahl(true, true, true))
        val hg = block("private fun hintergrundWaehlen()", "private fun hgName(")
        assertTrue(hg.contains("Raster.hintergrundWahl(") && hg.contains("fuss = getString(R.string.hg_hinweis)"))
        assertTrue(block("private fun groesseWaehlen()", "private fun hintergrundWaehlen()").contains("fuss = getString(R.string.groesse_hinweis)"))
    }

    // ---------- 2 Designs + Akzent ----------

    @Test
    fun designsUndAkzent() {
        assertEquals("ab Werk wie bisher", PanoptesStil.palette("dark", false), PanoptesStil.gestaltet("auto", "dark", false, null))
        assertEquals(PanoptesStil.HELL, PanoptesStil.gestaltet("hell", "dark", false, null))
        assertEquals(PanoptesStil.KONTRAST, PanoptesStil.gestaltet("kontrast", null, true, null))
        assertEquals(PanoptesStil.NACHT, PanoptesStil.gestaltet("nacht", "light", true, null))
        val gelb = PanoptesStil.gestaltet("dunkel", null, false, "#FFD400")
        assertEquals(0xFFFFD400.toInt(), gelb.aqua)
        assertEquals("nur der Akzent ändert sich", PanoptesStil.DUNKEL.copy(aqua = gelb.aqua), gelb)
        assertNull(PanoptesStil.akzentFarbe("rot"))
        val l = Raster.standard(null)
        assertEquals("auto", l.design); assertNull(l.akzent)
        assertSame("unbekanntes Design ändert nichts", l, Raster.design(l, "fremd"))
        assertEquals("#C792EA", Raster.akzent(l, "#c792ea").akzent)
        assertSame(l, Raster.akzent(l, "lila"))
        assertTrue(m.contains("PanoptesStil.gestaltet(designJetzt(), modus, systemHell(), layout.akzent)"))   // 0.1.41: Tag/Nacht
        assertTrue("ohne Tag/Nacht das gewählte Design", m.contains("if (!an) return layout.design"))
    }

    // ---------- 3 eigenes Hintergrundbild ----------

    @Test
    fun eigenesBildBegrenztUndVerkleinert() {
        assertEquals(1920 to 1080, EigenesBild.zielGroesse(3840, 2160))
        assertEquals(1080 to 1920, EigenesBild.zielGroesse(2160, 3840))
        assertEquals("nie vergrößert", 800 to 600, EigenesBild.zielGroesse(800, 600))
        assertEquals(1, EigenesBild.teiler(1920, 1080))
        assertEquals(2, EigenesBild.teiler(4000, 3000))
        assertEquals(4, EigenesBild.teiler(8000, 6000))
        assertEquals(30L * 1024 * 1024, EigenesBild.MAX_BYTES)
        val b = lies("src/main/java/at/greenchili/panoptes/launcher/EigenesBild.kt")
        assertTrue("Obergrenze beim Lesen", b.contains("if (gesamt > MAX_BYTES) return false"))
        assertTrue(m.contains("Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(\"image/*\")"))
        assertTrue(m.contains("EigenesBild.uebernehmen(contentResolver, uri, filesDir)") && m.contains("aendern(Raster.hintergrund(layout, \"eigen\"))"))
        assertTrue("eigen" in Raster.HINTERGRUENDE)
    }

    // ---------- 4 Kachelgröße + Rasterdichte ----------

    @Test
    fun kachelStufenUndAbstand() {
        val l = Raster.standard(null)          // Seite 2: Akku 2×2 bei (0,0), Navi 2×1, Wächter 2×1, Uhr 2×1
        val akku = l.seiten[1].first { it.kachel == Kachel.AKKU }
        assertEquals("mittel", Raster.stufeVon(akku))
        val klein = Raster.kachelStufe(l, akku.id, "klein")
        assertEquals(2 to 1, klein.seiten[1].first { it.id == akku.id }.let { it.b to it.h })
        val gross = Raster.kachelStufe(klein, akku.id, "gross")
        assertSame("kein Platz für groß → unverändert", klein, gross)
        val uhr = l.seiten[1].first { it.kachel == Kachel.UHR }
        val uhrMittel = Raster.kachelStufe(l, uhr.id, "mittel").seiten[1].first { it.id == uhr.id }
        assertEquals("mittel", Raster.stufeVon(uhrMittel))
        assertEquals(listOf(6, 14, 24), Raster.ABSTAENDE.map { Raster.abstandDp(it) })
        assertTrue(m.contains("FreiLayout(this, ui.dp(Raster.abstandDp(layout.abstand)))"))   // 0.1.44 (E2): Feinraster, Abstand wie bisher
        assertTrue(m.contains("Raster.kachelStufe(layout, e.id, st)"))
    }

    // ---------- 5 Statusspalte selbst zusammenstellen ----------

    @Test
    fun statusspalteZusammenstellen() {
        assertEquals(Raster.SPALTE_STANDARD, Raster.standard(null).spalte)
        assertFalse("Uhr ab Werk aus (nichts still geändert)", "uhr" in Raster.SPALTE_STANDARD)
        val l = Raster.spalte(Raster.standard(null), listOf("uhr", "akku", "unsinn"))
        assertEquals(listOf("akku", "uhr"), l.spalte)
        val sp = block("private fun statusSpalte()", "private fun naviWahl()")
        for (t in Raster.SPALTE_TEILE) assertTrue(t, sp.contains("\"$t\" in t"))
        assertTrue("Start immer da", sp.contains("getString(R.string.leiste_start)) { splitVerlassen() }"))
        assertTrue(m.contains(".setMultiChoiceItems(namen, an)"))
    }

    // ---------- 6 neue Kacheln ----------

    @Test
    fun neueKachelnNurLesenBefehleInPanoptes() {
        assertTrue(Kachel.values().map { it.name }.containsAll(listOf("SCHLOSS", "LADEN", "KAMERA")))
        val s = LauncherContract.parseSummary("""{"state":{"locked":true,"gear":"P"}}""")
        assertEquals(true, s.locked)
        assertNull(LauncherContract.parseSummary("""{"state":{"locked":null}}""").locked)
        assertNull(LauncherContract.parseSummary("""{}""").locked)
        val frage = block("private fun schlossFrage()", "private fun ladenKachel(")
        assertTrue("Fahrsperre", frage.contains("if (!darfBedienen()) return"))
        assertTrue("Rückfrage, dann Panoptes", frage.contains("setPositiveButton(R.string.in_panoptes) { _, _ -> oeffnePanoptes(\"vehicle\") }"))
        assertFalse("kein Fahrzeugbefehl aus dem Launcher", m.contains("/api/launcher/v1/vehicle"))
        assertTrue("Live-Kamera öffnet die Panoptes-Liveansicht", m.contains("Kachel.KAMERA -> v.setOnClickListener { liveAnsicht() }"))
        val c = lies("src/main/java/at/greenchili/panoptes/launcher/PanoptesClient.kt")
        assertFalse(c.contains("vehicle") || c.contains("lock"))
    }

    // ---------- 7 Export/Import ----------

    @Test
    fun designWandertMitDerDateiOhneGeheimnisse() {
        val l = Raster.spalte(Raster.abstand(Raster.akzent(Raster.design(Raster.standard(null), "kontrast"), "#FF8A6B"), "eng"), listOf("uhr", "gang"))
        val json = Raster.toJson(l)
        val zurueck = Raster.fromJson(json)!!
        assertEquals(l, zurueck)
        for (verboten in listOf("lat", "lon", "token", "passwort", "password", "pin", "vin", "key")) {
            assertFalse(verboten, Regex("\"$verboten\"", RegexOption.IGNORE_CASE).containsMatchIn(json))
        }
        assertFalse("das eigene Bild bleibt auf dem Gerät", json.contains(EigenesBild.DATEI))
        val alt = Raster.fromJson(json.replace("\"kontrast\"", "\"unbekannt\"").replace("#FF8A6B", "rot"))!!
        assertEquals("auto", alt.design); assertNull(alt.akzent)
        val ohne = Raster.fromJson(Raster.toJson(Raster.standard(null)).replace(Regex(",\\s*\"spalte\": \\[[^\\]]*]"), ""))!!
        assertEquals("ältere Datei ohne Design: Standard", Raster.SPALTE_STANDARD, ohne.spalte)
    }

    // ---------- „Erweitert“, Texte, Folgepunkte ----------

    @Test
    fun allesUnterErweitertUndInFuenfSprachen() {
        val e = block("private fun einstellungen(erweitert: Boolean = false)", "private fun groesseName(")
        val oben = e.substring(0, e.indexOf("} else {"))
        val unten = e.substring(e.indexOf("} else {"))
        for (f in listOf("designWaehlen()", "akzentWaehlen()", "bildWaehlen()", "abstandWaehlen()", "spalteWaehlen()")) {
            assertFalse("oben einfach: $f", oben.contains(f)); assertTrue("unter Erweitert: $f", unten.contains("if (darfBedienen()) $f"))
        }
        val neu = listOf("design", "design_auto", "design_kontrast", "design_nacht", "akzent", "akzent_weiss", "hg_eigen", "bild_waehlen",
            "bild_fehler", "keine_dateiwahl", "abstand", "abstand_weit", "spalte_titel", "spalte_uhr", "stufe_klein", "stufe_gross",
            "widget_schloss", "widget_laden", "widget_kamera", "schloss_frage", "in_panoptes", "laden_ziel", "kamera_tipp")
        for (d in listOf("values", "values-de", "values-fr", "values-it", "values-nl")) {
            val s = lies("src/main/res/$d/strings.xml")
            for (k in neu) assertTrue("$d $k", s.contains("name=\"$k\""))
        }
    }

    @Test
    fun folgepunkte() {
        val manifest = lies("src/main/AndroidManifest.xml")
        assertTrue("Dichtewechsel ohne Neustart", manifest.contains("|density|"))
        assertFalse(manifest.contains("android:debuggable=\"true\""))
        val leiste = lies("src/main/java/at/greenchili/panoptes/launcher/StatusLeiste.kt")
        assertTrue("Kamera-Knopf bei 240 dpi nicht abgeschnitten", leiste.contains("setSingleLine(); ellipsize = null") && leiste.contains("ui.lp(-2, ui.dp(48), rechts = 8)"))
        assertNotEquals(PanoptesStil.KONTRAST.onSurface, PanoptesStil.KONTRAST.background)
    }
}
