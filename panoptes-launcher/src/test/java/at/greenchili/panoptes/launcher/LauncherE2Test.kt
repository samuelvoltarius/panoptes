package at.greenchili.panoptes.launcher

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.1.44 (E2 „freier Editor“, Projektinhaber 12.11.: „ja beides freier editor einplanen“): Feinraster, Einrasten/Hilfslinien,
 * Mindestgröße, Überlappen verhindert, Stil je Element, Lesbarkeit, Rückgängig/Wiederholen, Datei Version 2 (alte
 * Version 1 lesbar), Dock/Reiter/Text als Elemente, Bearbeiten nur im Stand.
 */
class LauncherE2Test {
    private fun basis() = if (File("src/main").isDirectory) File(".") else File("panoptes-launcher")
    private fun lies(rel: String) = File(basis(), rel).readText().replace("\r\n", "\n")
    private fun quelle(n: String) = lies("src/main/java/at/greenchili/panoptes/launcher/$n")

    private val l0 = Raster.standard(null)   // Seite 1: Fahrzeug 4×3 links, Musik 2×3 rechts

    private fun leer() = l0.copy(seiten = listOf(emptyList()))

    @Test
    fun feinrasterUndZellen() {
        assertEquals(48, Frei.BREITE); assertEquals(24, Frei.HOEHE)
        val e = l0.seiten[0].first()
        assertArrayEquals(intArrayOf(e.x * 8, e.y * 8, e.b * 8, e.h * 8), Frei.rect(e))
        assertArrayEquals("umfassende Zellen", intArrayOf(1, 0, 2, 1), Frei.zellen(intArrayOf(10, 2, 9, 5)))
        assertEquals(6, Frei.rasten(5, true)); assertEquals(5, Frei.rasten(5, false))
    }

    @Test
    fun setzenMitMindestgroesseRandUndOhneUeberlappen() {
        val l = Frei.hinzufuegen(leer(), 0, ElementTyp.TEXT, 12, 3, text = "Hallo")
        val t = l.seiten[0].single()
        assertEquals(ElementTyp.TEXT, t.typ); assertEquals("Hallo", t.name)
        // zu klein → Mindestgröße (Text 4 × 2), über den Rand → begrenzt
        val k = Frei.setze(l, t.id, 46, 23, 1, 1)
        assertArrayEquals(intArrayOf(44, 22, 4, 2), Frei.rect(k.seiten[0].single()))
        // Kachel frei: Mindestgröße 8 × 6
        val m = Frei.hinzufuegen(l, 0, ElementTyp.WIDGET, 8, 6, kachel = Kachel.UHR)
        val uhr = m.seiten[0].first { it.kachel == Kachel.UHR }
        val klein = Frei.setze(m, uhr.id, Frei.rect(uhr)[0], Frei.rect(uhr)[1], 3, 3)
        assertEquals(8, Frei.rect(klein.seiten[0].first { it.id == uhr.id })[2])
        // Überlappen → unverändert
        assertSame(m, Frei.setze(m, uhr.id, Frei.rect(t)[0], Frei.rect(t)[1], 8, 6))
        // zwei halbe Zellen nebeneinander in einer Zelle sind erlaubt (das alte Zellraster hätte das verboten)
        val a = Frei.hinzufuegen(leer(), 0, ElementTyp.TEXT, 4, 2, text = "A")
        val b = Frei.hinzufuegen(a, 0, ElementTyp.TEXT, 4, 2, text = "B")
        assertEquals(2, b.seiten[0].size)
        assertFalse(Frei.ueberlappt(Frei.rect(b.seiten[0][0]), Frei.rect(b.seiten[0][1])))
        // das Zellraster (alte Funktionen) sieht feine Elemente
        val voll = Frei.setze(b, b.seiten[0][0].id, 0, 0, 4, 2)
        assertFalse(Raster.passt(voll.seiten[0], 0, 0, 1, 1))
    }

    @Test
    fun hilfslinienUndEinrasten() {
        val l = Frei.hinzufuegen(leer(), 0, ElementTyp.WIDGET, 16, 8, kachel = Kachel.UHR)   // bei 0,0
        val seite = l.seiten[0]
        val (r, linien) = Frei.hilfslinien(seite, intArrayOf(17, 9, 8, 6), "neu", einrasten = true)
        assertEquals("rastet an die rechte Kante (16)", 16, r[0]); assertEquals("und an die untere (8)", 8, r[1])
        assertEquals(listOf(16), linien.senkrecht); assertEquals(listOf(8), linien.waagrecht)
        val (r2, l2) = Frei.hilfslinien(seite, intArrayOf(17, 9, 8, 6), "neu", einrasten = false)
        assertArrayEquals("aus: nichts verschoben", intArrayOf(17, 9, 8, 6), r2)
        assertTrue(l2.senkrecht.isEmpty() && l2.waagrecht.isEmpty())
    }

    @Test
    fun stilUndLesbarkeit() {
        val l = Frei.hinzufuegen(leer(), 0, ElementTyp.WIDGET, 8, 6, kachel = Kachel.UHR)
        val id = l.seiten[0].single().id
        val s = Frei.stil(l, id, 12, 20, 9, "nicht")
        val e = s.seiten[0].single()
        assertEquals(12, e.ecke); assertNull("unter 30 % verworfen", e.deckung); assertNull(e.unschaerfe); assertNull(e.akzent)
        val g = Frei.stil(l, id, 40, 60, 2, "#b5f23d").seiten[0].single()
        assertEquals(40, g.ecke); assertEquals(60, g.deckung); assertEquals(2, g.unschaerfe); assertEquals("#B5F23D", g.akzent)
        assertEquals(21.0, Lesbarkeit.kontrast(Farbe.rgb(0xFFFFFF), Farbe.rgb(0x000000)), 0.01)
        for (p in listOf(PanoptesStil.DUNKEL, PanoptesStil.HELL)) {
            val d = Lesbarkeit.deckung(p, 30, Lesbarkeit.gruende(p))
            assertTrue("Text lesbar (${p.hell})", Lesbarkeit.gruende(p).all { Lesbarkeit.kontrast(p.onSurface, Lesbarkeit.karte(p, it, d)) >= Lesbarkeit.TEXT })
        }
        val dunkleKarte = Lesbarkeit.karte(PanoptesStil.DUNKEL, PanoptesStil.DUNKEL.background, 80)
        assertFalse("dunkler Akzent auf dunkler Karte zu schwach", Lesbarkeit.akzentLesbar(PanoptesStil.DUNKEL, "#0A1824", dunkleKarte))
        assertTrue(Lesbarkeit.akzentLesbar(PanoptesStil.DUNKEL, "#B5F23D", dunkleKarte))
    }

    @Test
    fun rueckgaengigUndWiederholen() {
        val v = Verlauf(max = 3)
        val a = leer(); val b = Frei.hinzufuegen(a, 0, ElementTyp.TEXT, 4, 2, text = "b"); val c = Frei.hinzufuegen(b, 0, ElementTyp.TEXT, 4, 2, text = "c")
        assertFalse(v.kannZurueck())
        v.merke(a); v.merke(b)
        assertEquals(b, v.zurueck(c)); assertEquals(a, v.zurueck(b)); assertNull(v.zurueck(a))
        assertEquals(b, v.vor(a)); assertTrue(v.kannVor())
        v.merke(b); assertFalse("neue Änderung löscht Wiederholen", v.kannVor())
        repeat(5) { v.merke(Frei.hinzufuegen(a, 0, ElementTyp.TEXT, 4, 2, text = "x$it")) }
        var n = 0; var jetzt = c; while (true) { jetzt = v.zurueck(jetzt) ?: break; n++ }
        assertEquals("höchstens max Schritte", 3, n)
    }

    @Test
    fun dateiVersion2UndAlteDateien() {
        var l = Frei.hinzufuegen(leer(), 0, ElementTyp.TEXT, 12, 3, text = "Mein Auto")
        l = Frei.hinzufuegen(l, 0, ElementTyp.WIDGET, Frei.LEISTE.first, Frei.LEISTE.second, kachel = Kachel.DOCKLEISTE)
        val t = l.seiten[0].first { it.typ == ElementTyp.TEXT }
        l = Frei.stil(l, t.id, 8, 70, 1, "#FFC266")
        val json = Raster.toJson(l)
        val o = JSONObject(json)
        assertEquals("panoptes-launcher-layout", o.getString("format")); assertEquals(2, o.getInt("version"))
        val zurueck = Raster.fromJson(json)!!
        assertEquals(l.seiten, zurueck.seiten)
        // nur Layout und Farben: keine Orte, keine Geheimnisse
        for (w in listOf("lat", "lon", "token", "passwort", "pin\"", "vin")) assertFalse(w, json.lowercase().contains(w))
        // alte Datei (Version 1, nur Zellen) wird übernommen
        val alt = Raster.toJson(l0).replace("\"version\": 2", "\"version\": 1")
        assertEquals(l0.seiten, Raster.fromJson(alt)!!.seiten)
        // Text ohne feine Lage und Kachel in freier Größe ohne feine Lage fallen weg; unbekannte Version = null
        val kaputt = JSONObject(json)
        kaputt.getJSONArray("seiten").getJSONArray(0).getJSONObject(0).remove("fx")
        assertEquals(1, Raster.fromJson(kaputt.toString())!!.seiten[0].size)
        assertNull(Raster.fromJson(json.replace("\"version\": 2", "\"version\": 3")))
        // Leisten nur einmal
        assertSame(l, Frei.hinzufuegen(l, 0, ElementTyp.WIDGET, 48, 5, kachel = Kachel.DOCKLEISTE))
        // Verschieben im Zellraster gibt die Zellen zurück (feine Lage gelöscht)
        val v = Raster.verschieben(l0, l0.seiten[0][1].id, 0, 4, 0)
        assertNull(v.seiten[0].first { it.id == l0.seiten[0][1].id }.fx)
    }

    /** Zwei Vorschaubilder (SVG) aus den echten Regeln: Bearbeiten mit Hilfslinien und eine frei gebaute Seite. */
    @Test
    fun vorschau() {
        val ordner = File(basis(), "build/e2-vorschau").apply { mkdirs() }
        val p = PanoptesStil.DUNKEL
        var l = leer()
        l = Frei.hinzufuegen(l, 0, ElementTyp.WIDGET, 18, 13, kachel = Kachel.FAHRSTATUS)
        l = Frei.hinzufuegen(l, 0, ElementTyp.WIDGET, 18, 9, kachel = Kachel.MUSIK)
        l = Frei.hinzufuegen(l, 0, ElementTyp.TEXT, 14, 3, text = "Guten Morgen")
        val ids = l.seiten[0].associate { (it.kachel?.name ?: "TEXT") to it.id }
        l = Frei.setze(l, ids.getValue("FAHRSTATUS"), 0, 3, 18, 12)
        l = Frei.setze(l, ids.getValue("MUSIK"), 0, 15, 18, 9)
        l = Frei.setze(l, ids.getValue("TEXT"), 0, 0, 14, 3)
        l = Frei.hinzufuegen(l, 0, ElementTyp.WIDGET, 28, 20, kachel = Kachel.AUTOANSICHT)
        l = Frei.setze(l, l.seiten[0].first { it.kachel == Kachel.AUTOANSICHT }.id, 22, 0, 26, 15)
        l = Frei.stil(l, ids.getValue("FAHRSTATUS"), 40, 70, 2, "#B5F23D")
        l = Frei.stil(l, ids.getValue("MUSIK"), 12, 90, 0, null)
        assertEquals(4, l.seiten[0].size)
        for ((name, bearbeiten) in listOf("frei_gebaut" to false, "bearbeiten_hilfslinien" to true)) {
            val w = 1920f; val h = 720f; val ux = (w - 40f) / 48f; val uy = (h - 40f) / 24f
            val lw = SvgLeinwand(1920, 720)
            Szenen.zeichne(12, lw, w, h, p)
            fun x(u: Int) = 20f + u * ux
            fun y(u: Int) = 20f + u * uy
            if (bearbeiten) { var k = 0; while (k <= 48) { lw.linie(x(k), 20f, x(k), h - 20f, 1f, Farbe.alpha(p.onSurfaceVariant, 40)); k += 2 }; k = 0; while (k <= 24) { lw.linie(20f, y(k), w - 20f, y(k), 1f, Farbe.alpha(p.onSurfaceVariant, 40)); k += 2 } }
            for (e in l.seiten[0]) {
                val r = Frei.rect(e)
                val bx = x(r[0]) + 6; val by = y(r[1]) + 6; val bb = r[2] * ux - 12; val bh = r[3] * uy - 12
                if (e.typ == ElementTyp.TEXT) { lw.roh("<text x=\"$bx\" y=\"${by + bh * 0.75f}\" font-family=\"Segoe UI, sans-serif\" font-size=\"${(bh * 0.7f).toInt()}\" font-weight=\"600\" fill=\"${Farbe.css(Farbe.hex(e.akzent) ?: p.onSurface)}\">${e.name}</text>"); continue }
                if (e.kachel == Kachel.AUTOANSICHT) {
                    FahrzeugStart.zeichne(lw, FahrzeugStart.Form.KOMPAKT, FahrzeugStart.Zustand(emptyMap(), mapOf("vl" to 0), false, true, false), p,
                        FahrzeugStart.lack("#C9D1D6", p), bx + bb / 2, by + bh * 0.48f, FahrzeugStart.laenge(bb, bh))
                } else {
                    val d = Lesbarkeit.deckung(p, (e.deckung ?: 60) + (e.unschaerfe ?: 0) * 8, Lesbarkeit.gruende(p))
                    lw.rechteck(bx, by, bb, bh, (e.ecke ?: 26).toFloat(), Voll(Farbe.alpha(Farbe.rgb(0x0A1824), d * 255 / 100)))
                    lw.rahmen(bx, by, bb, bh, (e.ecke ?: 26).toFloat(), if (e.akzent != null) 2.5f else 1.5f, Farbe.hex(e.akzent) ?: Farbe.argb(38, 255, 255, 255))
                    lw.roh("<text x=\"${bx + 24}\" y=\"${by + 40}\" font-family=\"Segoe UI, sans-serif\" font-size=\"18\" font-weight=\"600\" fill=\"${Farbe.css(p.outline)}\">${if (e.kachel == Kachel.MUSIK) "MUSIK" else "FAHRSTATUS"}</text>")
                }
                if (bearbeiten) {
                    lw.rahmen(bx, by, bb, bh, (e.ecke ?: 26).toFloat(), 3f, p.lime)
                    lw.ellipse(bx + bb - 18, by + bh - 18, 22f, 22f, Voll(p.lime)); lw.ellipse(bx + bb - 18, by + 18, 22f, 22f, Voll(p.lime))
                }
            }
            if (bearbeiten) {
                // ein Element wird gerade gezogen: Hilfslinien aus Frei.hilfslinien
                val zieht = intArrayOf(23, 17, 10, 6)   // freier Bereich unter der Fahrzeug-Ansicht
                val (r, linien) = Frei.hilfslinien(l.seiten[0], zieht, "x", true)
                linien.senkrecht.forEach { k -> lw.linie(x(k), 20f, x(k), h - 20f, 2.5f, p.lime) }
                linien.waagrecht.forEach { k -> lw.linie(20f, y(k), w - 20f, y(k), 2.5f, p.lime) }
                lw.rechteck(x(r[0]) + 6, y(r[1]) + 6, r[2] * ux - 12, r[3] * uy - 12, 20f, Voll(Farbe.alpha(p.aqua, 90)))
                lw.rahmen(x(r[0]) + 6, y(r[1]) + 6, r[2] * ux - 12, r[3] * uy - 12, 20f, 3f, p.lime)
                assertTrue("Hilfslinie getroffen", linien.senkrecht.isNotEmpty() || linien.waagrecht.isNotEmpty())
            }
            File(ordner, "$name.svg").writeText(lw.svg())
        }
    }

    @Test
    fun vertrag() {
        val m = quelle("MainActivity.kt")
        assertTrue(m.contains("val r = FreiLayout(this, ui.dp(Raster.abstandDp(layout.abstand)))"))
        assertTrue(m.contains("fang.setOnTouchListener(freiEditor.ziehen(box, e) { kachelMenue(e) })"))
        assertTrue(m.contains("if (bearbeiten && merken) verlauf.merke(layout)"))
        assertTrue(m.contains("bearbeitenSperren()   // 0.1.44 (E2): Bearbeiten nur im Stand"))
        val sperre = m.substringAfter("private fun bearbeitenSperren()").substringBefore("private fun darfBedienen(")
        assertTrue(sperre.contains("Fahrsicherheit.bearbeitenErlaubt(s?.gear, s?.acc, s?.speedKmh)"))
        assertTrue(sperre.contains("bearbeiten = false"))
        assertTrue(m.contains("!Raster.hatWidget(layout, Kachel.REITERLEISTE)"))
        assertTrue(m.contains("if (!Raster.hatWidget(layout, Kachel.DOCKLEISTE)) spalte.addView(dock()"))
        assertTrue(m.contains("k(R.drawable.ic_pn_home, R.string.vorlage_zuruecksetzen) { vorlageWaehlen() }"))
        // Bearbeiten-Modus nur nach darfBedienen (Stand)
        val lang = m.substringAfter("private fun langerDruck(e: Element)").substringBefore("bearbeiten = true")
        assertTrue(lang.contains("if (!darfBedienen()) return"))
        assertTrue(quelle("FreiAnsicht.kt").contains("Lesbarkeit.deckung("))
        val web = File(basis().parentFile ?: File(".."), "app/src/main/assets/web/local/launcher.html").let { if (it.exists()) it else File("../app/src/main/assets/web/local/launcher.html") }.readText()
        assertTrue(web.contains("delete f.e.fx; delete f.e.fy; delete f.e.fb; delete f.e.fh;"))
        assertTrue(web.contains("KACHELN.push('dockleiste', 'reiterleiste');"))
        for (o in listOf("values", "values-de", "values-fr", "values-it", "values-nl")) {
            val s = lies("src/main/res/$o/strings.xml")
            for (key in listOf("rueckgaengig", "wiederholen", "einrasten_an", "einrasten_aus", "text_hinzu", "vorlage_zuruecksetzen", "bearbeiten_gesperrt",
                    "widget_dockleiste", "widget_reiterleiste", "stil_titel", "stil_ecken", "stil_deckung", "stil_unschaerfe", "stil_akzent",
                    "stil_wie_design", "stil_lesbar", "stil_akzent_schwach", "stil_unschaerfe_hinweis"))
                assertTrue("$o: $key", s.contains("name=\"$key\""))
        }
        assertNotNull(Raster.fromJson(lies("src/main/assets/vorlagen/fahrzeug.json")))
        assertNotSame(Kachel.DOCKLEISTE, Kachel.REITERLEISTE)
    }
}
