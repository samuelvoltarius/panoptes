package at.greenchili.panoptes.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * 0.1.44 (D1 „Fahrzeug-Startseite“, 12.11.): Vorlage „Fahrzeug“, Fahrstatus-Karte, eigene Fahrzeug-Ansicht
 * (Kompakt/SUV/Limousine), Fenster-Schnellknöpfe nur im Stand mit Bestätigung über den engen Kern-Weg, Szenen.
 */
class LauncherD1Test {
    private fun basis() = if (File("src/main").isDirectory) File(".") else File("panoptes-launcher")
    private fun lies(rel: String) = File(basis(), rel).readText().replace("\r\n", "\n")
    private fun quelle(n: String) = lies("src/main/java/at/greenchili/panoptes/launcher/$n")

    @Test
    fun grundformAusModellOderWahl() {
        assertEquals(FahrzeugStart.Form.KOMPAKT, FahrzeugStart.formAusModell("dolphin_surf"))
        assertEquals(FahrzeugStart.Form.KOMPAKT, FahrzeugStart.formAusModell(null))
        assertEquals(FahrzeugStart.Form.SUV, FahrzeugStart.formAusModell("seal_u_dmi"))
        assertEquals(FahrzeugStart.Form.SUV, FahrzeugStart.formAusModell("atto3"))
        assertEquals(FahrzeugStart.Form.LIMOUSINE, FahrzeugStart.formAusModell("seal"))
        assertEquals(FahrzeugStart.Form.SUV, FahrzeugStart.form("suv", "dolphin"))
        assertEquals(FahrzeugStart.Form.LIMOUSINE, FahrzeugStart.form("auto", "seal"))
        assertEquals(listOf("auto", "kompakt", "suv", "limousine"), FahrzeugStart.FORMEN)
    }

    @Test
    fun statusNurEchteWerte() {
        assertEquals(0, FahrzeugStart.gangIndex("P")); assertEquals(3, FahrzeugStart.gangIndex("D"))
        assertEquals(-1, FahrzeugStart.gangIndex(null))
        assertNull(FahrzeugStart.ringAnteil(null)); assertNull(FahrzeugStart.ringAnteil(-1.0))
        assertEquals(0.5f, FahrzeugStart.ringAnteil(90.0)!!, 0.001f)
        assertEquals(1f, FahrzeugStart.ringAnteil(250.0)!!, 0.001f)
        assertNull(FahrzeugStart.akkuAnteil(null)); assertNull(FahrzeugStart.akkuAnteil(130.0))
        assertEquals(0.78f, FahrzeugStart.akkuAnteil(78.0)!!, 0.001f)
        assertEquals("—", FahrzeugStart.druck(null)); assertEquals("—", FahrzeugStart.druck(0.0))
        assertEquals("2,4", FahrzeugStart.druck(2.43, Locale.GERMANY))
    }

    private fun koerper(json: String) = LauncherContract.koerper(JSONObject(json))

    @Test
    fun kernKoerperUndKnoepfe() {
        val k = koerper("""{"tueren":{"lf":true,"rf":false,"x":true,"rr":"ja"},"fenster":{"vl":0,"vr":40,"hl":101,"hr":-1,"dach":12},"dach":true}""")
        assertEquals(mapOf("lf" to true, "rf" to false), k.tueren)
        assertEquals(mapOf("vl" to 0, "vr" to 40, "dach" to 12), k.fenster)
        assertTrue(k.dach)
        assertEquals("nur bekannte Fenster, Dach nur wenn vorhanden", listOf("vl", "vr", "dach"), FahrzeugStart.knoepfe(k))
        val ohneDach = koerper("""{"fenster":{"vl":0,"vr":0,"hl":0,"hr":0},"dach":false}""")
        assertEquals(listOf("vl", "vr", "hl", "hr"), FahrzeugStart.knoepfe(ohneDach))
        assertFalse(koerper("""{"fenster":{},"dach":true}""").dach)
        assertEquals(emptyList<String>(), FahrzeugStart.knoepfe(null))
        assertEquals("zu", FahrzeugStart.vorschlag(40)); assertEquals("auf", FahrzeugStart.vorschlag(0)); assertEquals("auf", FahrzeugStart.vorschlag(null))
        // Summary ohne „koerper“ (älterer Kern) bleibt null
        assertNull(LauncherContract.parseSummary("{}").koerper)
        assertNotNull(LauncherContract.parseSummary("""{"koerper":{"tueren":{},"fenster":{},"dach":false}}""").koerper)
    }

    @Test
    fun fensterNurImStandUndNurDieFuenf() {
        assertTrue(FahrzeugStart.darfFenster("P", "on", 0.0))
        assertTrue(FahrzeugStart.darfFenster(null, "off", null))
        assertFalse(FahrzeugStart.darfFenster("D", "on", 0.0))
        assertFalse(FahrzeugStart.darfFenster("P", "on", 12.0))
        assertFalse("Gang unbekannt bei Zündung an", FahrzeugStart.darfFenster(null, "on", null))
        assertEquals("""{"fenster":"vl","ziel":"auf"}""", LauncherContract.fensterBody("vl", "auf"))
        for ((f, z) in listOf("hupe" to "auf", "vl" to "50", "alle" to "zu", "licht" to "an"))
            assertTrue("$f/$z", runCatching { LauncherContract.fensterBody(f, z) }.isFailure)
        assertEquals(FensterAntwort(false, "fahrt"), LauncherContract.parseFenster("""{"ok":false,"grund":"fahrt"}"""))
        assertEquals(FensterAntwort(true, null), LauncherContract.parseFenster("""{"ok":true}"""))
        assertEquals(FensterAntwort(false, "fehler"), LauncherContract.parseFenster("kaputt"))
    }

    @Test
    fun ansichtGeometrieUndZustand() {
        for (f in FahrzeugStart.Form.values()) {
            val p = FahrzeugStart.profil(f)
            val alle = p.umriss + p.fenster
            assertTrue("$f in 0..1", alle.all { it in 0f..1f })
            assertTrue("$f: Räder vorn/hinten", p.radHinten < p.tuerHinten + 0.1f && p.radVorn > p.tuerVorn)
            assertTrue("$f: B-Säule zwischen den Türen", p.bSaeule in p.tuerHinten..p.tuerVorn)
            val g = FahrzeugStart.glatt(p.umriss)
            assertEquals(p.umriss.size * 8, g.size)
        }
        // die drei Formen unterscheiden sich wirklich: SUV höher, Limousine flacher als Kompakt
        fun dach(f: FahrzeugStart.Form) = FahrzeugStart.profil(f).umriss.filterIndexed { i, _ -> i % 2 == 1 }.maxOrNull()!!
        assertTrue(dach(FahrzeugStart.Form.SUV) > dach(FahrzeugStart.Form.KOMPAKT))
        assertTrue(dach(FahrzeugStart.Form.LIMOUSINE) < dach(FahrzeugStart.Form.KOMPAKT))
        // Beschneiden: Quadrat an x = 0,5 halbiert
        val q = listOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        val links = FahrzeugStart.schneide(q, 0.5f, true, true)
        assertTrue(links.filterIndexed { i, _ -> i % 2 == 0 }.all { it <= 0.5f })
        assertEquals(8, links.size)
        assertEquals(emptyList<Float>(), FahrzeugStart.schneide(q, -1f, true, true))
        val z = FahrzeugStart.Zustand(tuerOffen = mapOf("lf" to true, "trunk" to false), fenster = mapOf("vr" to 40, "vl" to 0), laedt = true)
        fun rolle(n: String) = FahrzeugStart.rolle(n, z)
        assertEquals(FahrzeugStart.Rolle.KLAPPE_OFFEN, rolle("lf"))
        assertEquals(FahrzeugStart.Rolle.KLAPPE, rolle("trunk"))
        assertEquals(FahrzeugStart.Rolle.KLAPPE, rolle("rf"))
        assertEquals(FahrzeugStart.Rolle.FENSTER_OFFEN, rolle("vr"))
        assertEquals(FahrzeugStart.Rolle.FENSTER_ZU, rolle("vl"))
        assertEquals("unbekannt nie als zu", FahrzeugStart.Rolle.UNBEKANNT, rolle("hl"))
        assertEquals(FahrzeugStart.Rolle.LADEN_AN, rolle("ladeklappe"))
        // Zustand aus dem Summary: Laden aus charging.active oder acc
        val s = LauncherContract.parseSummary("""{"state":{"acc":"charging","locked":true},"koerper":{"tueren":{"rr":true},"fenster":{"vl":5},"dach":false}}""")
        val zs = FahrzeugStart.zustand(s)
        assertTrue(zs.laedt); assertEquals(true, zs.verriegelt); assertEquals(mapOf("rr" to true), zs.tuerOffen)
        assertEquals(FahrzeugStart.Zustand(), FahrzeugStart.zustand(null))
        assertEquals(Farbe.rgb(0x8A1F2B), FahrzeugStart.lack("#8A1F2B", PanoptesStil.DUNKEL))
        assertEquals(FahrzeugStart.lack(null, PanoptesStil.DUNKEL), FahrzeugStart.lack("rot", PanoptesStil.DUNKEL))
    }

    /**
     * Vorschaubilder (SVG) aus derselben Zeichenlogik wie im Launcher ([Szenen], [FahrzeugStart.zeichne] über
     * [SvgLeinwand]); die Karten sind in der Zusammenstellung nachgestellt (Glas wie [FahrzeugKacheln.glas]).
     */
    @Test
    fun vorschauSvg() {
        val ordner = File(basis(), "build/d1-vorschau").apply { mkdirs() }
        val p = PanoptesStil.DUNKEL
        val zustaende = mapOf(
            "kompakt" to (FahrzeugStart.Form.KOMPAKT to FahrzeugStart.Zustand(mapOf("lf" to true), mapOf("vl" to 0, "vr" to 60, "hl" to 0, "hr" to 0), false, false, false)),
            "suv" to (FahrzeugStart.Form.SUV to FahrzeugStart.Zustand(emptyMap(), mapOf("vl" to 0, "vr" to 0, "hl" to 0, "hr" to 0, "dach" to 30), true, true, true)),
            "limousine" to (FahrzeugStart.Form.LIMOUSINE to FahrzeugStart.Zustand(mapOf("trunk" to true), mapOf("vl" to 0, "vr" to 0), false, true, false)))
        for ((n, fz) in zustaende) {
            val l = SvgLeinwand(900, 520)
            l.rechteck(0f, 0f, 900f, 520f, 0f, Voll(Farbe.dunkler(p.background, 0.5f)))
            FahrzeugStart.zeichne(l, fz.first, fz.second, p, FahrzeugStart.lack(if (n == "suv") "#8A1F2B" else null, p), 450f, 260f, FahrzeugStart.laenge(900f, 520f))
            val svg = l.svg()
            assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"))
            for (verboten in listOf("<image", "href", "<text", "base64")) assertFalse("$n: $verboten", svg.contains(verboten))
            File(ordner, "ansicht_$n.svg").writeText(svg)
        }
        for (nr in Szenen.ERSTE..Szenen.LETZTE) for ((pn, pal) in listOf("dunkel" to PanoptesStil.DUNKEL, "hell" to PanoptesStil.HELL)) {
            val l = SvgLeinwand(1920, 760)
            Szenen.zeichne(nr, l, 1920f, 760f, pal)
            val svg = l.svg()
            assertFalse("Szene $nr ohne Bilddatei", svg.contains("<image") || svg.contains("href"))
            assertEquals("immer gleich", svg, SvgLeinwand(1920, 760).also { Szenen.zeichne(nr, it, 1920f, 760f, pal) }.svg())
            File(ordner, "szene_${nr}_$pn.svg").writeText(svg)
        }
        // Zusammenstellung „Fahrzeug-Startseite“ (1920 × 720, wie der Mittelbildschirm ohne Systemleisten)
        for ((nr, name) in listOf(11 to "nachtstrasse", 12 to "showroom")) {
            val l = SvgLeinwand(1920, 720)
            Szenen.zeichne(nr, l, 1920f, 720f, p)
            val glas = Linear(0f, 0f, 0f, 720f, intArrayOf(Farbe.alpha(Farbe.rgb(0x0E1E2B), FahrzeugKacheln.glasDeckung(false)), Farbe.alpha(Farbe.rgb(0x0A1824), FahrzeugKacheln.glasDeckung(false) - 20)), floatArrayOf(0f, 1f))
            fun karte(x: Float, y: Float, b: Float, h: Float) { l.rechteck(x + 4, y + 10, b, h, 26f, Voll(Farbe.argb(70, 0, 0, 0))); l.rechteck(x, y, b, h, 26f, glas); l.rahmen(x, y, b, h, 26f, 1.5f, Farbe.argb(38, 255, 255, 255)) }
            fun text(x: Float, y: Float, t: String, gr: Int, farbe: Int, gewicht: Int = 600) = l.roh("<text x=\"$x\" y=\"$y\" font-family=\"Segoe UI, sans-serif\" font-size=\"$gr\" font-weight=\"$gewicht\" fill=\"${Farbe.css(farbe)}\">$t</text>")
            karte(28f, 64f, 600f, 360f); karte(28f, 440f, 600f, 150f)
            // Statuszeile oben
            text(36f, 40f, "14:32", 26, p.onSurface, 700); text(1700f, 40f, "Wächter an", 20, p.onSurfaceVariant)
            // Fahrstatus: Ring, Gang, Reichweite, Akku, Reifen
            l.ellipseRahmen(150f, 210f, 92f, 92f, 14f, Voll(p.line)); l.streckenzug(floatArrayOf(85f, 275f, 62f, 230f, 60f, 180f, 80f, 140f, 120f, 120f), 14f, p.aqua)
            text(112f, 222f, "0", 54, p.onSurface); text(126f, 252f, "km/h", 16, p.onSurfaceVariant)
            listOf("P", "R", "N", "D").forEachIndexed { i, g -> if (i == 0) l.rechteck(274f, 108f, 44f, 38f, 12f, Voll(p.aqua)); text(286f + i * 52f, 137f, g, 24, if (i == 0) p.onPrimary else p.onSurfaceVariant, 700) }
            text(274f, 214f, "312 km", 52, p.onSurface, 600); text(276f, 240f, "Reichweite", 16, p.onSurfaceVariant)
            l.rechteck(276f, 266f, 250f, 12f, 6f, Voll(p.line)); l.rechteck(276f, 266f, 195f, 12f, 6f, Voll(p.lime)); text(540f, 279f, "78 %", 20, p.onSurface, 700)
            listOf("VL 2,4", "VR 2,4", "HL 2,5", "HR 2,5").forEachIndexed { i, t -> text(52f + i * 140f, 396f, t, 18, p.onSurface, 600) }
            // Musik
            l.rechteck(52f, 462f, 80f, 80f, 16f, Linear(52f, 462f, 132f, 542f, intArrayOf(p.aqua, Farbe.dunkler(p.aqua, 0.6f)), floatArrayOf(0f, 1f)))
            text(152f, 490f, "Eigene Musik", 16, p.onSurfaceVariant); text(152f, 522f, "Titel", 26, p.onSurface, 700)
            listOf(380f, 460f, 540f).forEachIndexed { i, x -> l.ellipse(x, 520f, if (i == 1) 30f else 24f, if (i == 1) 30f else 24f, Voll(if (i == 1) p.lime else Farbe.argb(40, 255, 255, 255))) }
            // Fahrzeug als Blickfang
            val z = FahrzeugStart.Zustand(emptyMap(), mapOf("vl" to 0, "vr" to 35, "hl" to 0, "hr" to 0), false, true, nr == 11)
            text(700f, 96f, "Dolphin Surf", 30, p.onSurface, 600); text(1500f, 96f, if (nr == 11) "Verriegelt · Lädt" else "Verriegelt", 20, p.onSurfaceVariant)
            FahrzeugStart.zeichne(l, FahrzeugStart.Form.KOMPAKT, z, p, FahrzeugStart.lack(null, p), 1250f, 296f, FahrzeugStart.laenge(1250f, 470f))
            listOf("VL 0 %", "VR 35 %", "HL 0 %", "HR 0 %").forEachIndexed { i, t ->
                val x = 700f + i * 300f; l.rechteck(x, 530f, 280f, 60f, 30f, glas); l.rahmen(x, 530f, 280f, 60f, 30f, 1.5f, Farbe.argb(38, 255, 255, 255))
                text(x + 96f, 568f, t, 20, if (i == 1) p.warning else p.onSurface, 600)
            }
            // Pillen-Leiste mit Reitern und Dock
            l.rechteck(28f, 616f, 1864f, 88f, 44f, glas); l.rahmen(28f, 616f, 1864f, 88f, 44f, 1.5f, Farbe.argb(38, 255, 255, 255))
            l.rechteck(44f, 626f, 150f, 68f, 34f, Voll(Farbe.alpha(p.aqua, 60))); l.rahmen(44f, 626f, 150f, 68f, 34f, 2f, p.aqua)
            listOf("Status", "Karte", "Steuerung", "Apps", "Einstellungen").forEachIndexed { i, t -> text(70f + i * 170f, 668f, t, 20, if (i == 0) p.onSurface else p.onSurfaceVariant, 600) }
            for (i in 0..5) l.ellipse(1300f + i * 92f, 660f, 30f, 30f, Voll(Farbe.alpha(if (i == 0) p.aqua else p.onSurfaceVariant, 120)))
            File(ordner, "startseite_$name.svg").writeText(l.svg())
        }
    }

    @Test
    fun vertrag() {
        val m = quelle("MainActivity.kt")
        assertTrue(m.contains("Kachel.FAHRSTATUS -> fahrzeugStart.status(gross)"))
        assertTrue(m.contains("Kachel.AUTOANSICHT -> fahrzeugStart.ansicht()"))
        assertTrue(m.contains("val a = client.fenster(f, ziel); main.post { fertig(a) }"))
        assertTrue(m.contains("R.string.motiv_11, R.string.motiv_12, R.string.motiv_13, R.string.motiv_14"))
        assertTrue(m.contains("Vorlagen.FAHRZEUG -> R.string.vorlage_fahrzeug;"))
        val k = quelle("FahrzeugKacheln.kt")
        // Bestätigung vor jedem Senden, Stand-Prüfung vor der Frage und noch einmal vor dem Senden
        val tipp = k.substringAfter("fun fensterTipp(f: String)").substringBefore("private fun senden(")
        assertTrue(tipp.indexOf("darfFenster") in 0 until tipp.indexOf("AlertDialog.Builder"))
        assertFalse("Tipp sendet nie selbst", tipp.contains("fensterSenden("))
        val senden = k.substringAfter("private fun senden(").substringBefore("/** Tempo als")
        assertTrue(senden.indexOf("darfFenster") in 0 until senden.indexOf("fensterSenden("))
        // kein anderer Fahrzeugweg aus den neuen Dateien
        for (n in listOf("FahrzeugKacheln.kt", "FahrzeugStart.kt")) {
            val q = quelle(n)
            for (verboten in listOf("/api/vehicle", "client.", "klimaBody", "KOMFORT_AKTIONEN", "horn", "flash", "find-car", "R.drawable.ic_pn_horn"))
                assertFalse("$n: $verboten", q.contains(verboten))
        }
        val c = quelle("PanoptesClient.kt")
        assertTrue(c.contains("\"/api/launcher/v1/fenster\", LauncherContract.fensterBody(fenster, ziel)"))
        for (o in listOf("values", "values-de", "values-fr", "values-it", "values-nl")) {
            val s = lies("src/main/res/$o/strings.xml")
            for (key in listOf("widget_fahrstatus", "widget_autoansicht", "vorlage_fahrzeug", "vorlage_fahrzeug_text", "motiv_11", "motiv_12", "motiv_13", "motiv_14",
                    "fs_reichweite", "fs_akku", "fenster_vl", "fenster_dach", "fenster_jetzt", "fenster_nur_stand", "fenster_gesendet",
                    "fenster_ging_nicht", "ansicht_verriegelt", "ansicht_laedt", "fahrzeug_form", "form_auto", "form_limousine"))
                assertTrue("$o: $key", s.contains("name=\"$key\""))
        }
    }
}
