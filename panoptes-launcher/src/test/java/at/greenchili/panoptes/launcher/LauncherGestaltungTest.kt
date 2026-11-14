package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.1.43.3 (Rang 2): Design-Bausteine – Raster, Radien, Schriftskala, Kontrast, Bewegung. */
class LauncherGestaltungTest {
    private fun basis() = if (File("src/main").isDirectory) File(".") else File("panoptes-launcher")
    private fun quelle(n: String) = File(basis(), "src/main/java/at/greenchili/panoptes/launcher/$n").readText().replace("\r\n", "\n")
    private val paletten = listOf(PanoptesStil.DUNKEL, PanoptesStil.HELL, PanoptesStil.KONTRAST, PanoptesStil.NACHT)

    @Test
    fun rasterUndRadien() {
        for (a in Mass.ABSTAENDE) assertTrue("$a", a == Mass.XS || a % Mass.RASTER == 0)
        assertEquals(3, Mass.RADIEN.size)
        assertEquals(Mass.RADIUS_GROSS, PanoptesStil.RADIUS_KARTE)
        assertEquals(Mass.RADIUS_MITTEL, PanoptesStil.RADIUS_ACT)
        assertEquals(Mass.RADIUS_KLEIN, Mass.radius(10)); assertEquals(Mass.RADIUS_MITTEL, Mass.radius(18))
        assertEquals(Mass.RADIUS_GROSS, Mass.radius(24)); assertEquals(Mass.RADIUS_RUND, Mass.radius(999))
        assertEquals(64, Mass.TIPPFLAECHE)
        assertEquals(Mass.M, Mass.innen(false)); assertEquals(Mass.S, Mass.innen(true))
    }

    @Test
    fun schriftskalaHierarchie() {
        val s = Stufe.values().toList()
        assertTrue("Zahl groß > Zahl > Wert > Text", Stufe.ZAHL_GROSS.sp > Stufe.ZAHL.sp && Stufe.ZAHL.sp > Stufe.WERT.sp && Stufe.WERT.sp > Stufe.TEXT.sp)
        assertTrue("Beschriftung kleiner als Text", Stufe.KLEIN.sp < Stufe.TEXT.sp && Stufe.TITEL.sp < Stufe.TEXT.sp)
        for (z in s.filter { it.ziffern }) { assertTrue(z.sora); assertTrue("halbfett+", z.gewicht >= 600); assertFalse(z.waechst) }
        assertTrue(Stufe.KLEIN.gedaempft && Stufe.TITEL.gross)
        assertTrue(Stufe.TEXT.waechst && Stufe.TITEL.waechst)
        val b = quelle("Bausteine.kt")
        assertTrue("Zahlen gleich breit", b.contains("fontFeatureSettings = \"tnum\""))
        assertTrue(b.contains("fun stufe(s: Stufe, text: String, farbe: Int? = null)"))
    }

    @Test
    fun kontrastMindestens45UndNacht7() {
        for (p in paletten) {
            val ziel = Kontrast.ziel(p)
            assertTrue("leise ${p.background}", Schleier.kontrast(Kontrast.leise(p), p.surface) >= ziel)
            assertTrue("titel ${p.background}", Schleier.kontrast(Kontrast.titel(p), p.surface) >= ziel)
            assertTrue("Text ${p.background}", Schleier.kontrast(p.onSurface, p.surface) >= ziel)
        }
        assertEquals(Kontrast.NACHT, Kontrast.ziel(PanoptesStil.NACHT), 0f)
        assertEquals("Nacht auch mit eigener Akzentfarbe", Kontrast.NACHT, Kontrast.ziel(PanoptesStil.gestaltet("nacht", null, false, "#FF0000")), 0f)
        assertEquals(Kontrast.TAG, Kontrast.ziel(PanoptesStil.DUNKEL), 0f)
        // genügt eine Farbe schon, bleibt sie (Panoptes-Farben bleiben, wo sie reichen)
        assertEquals(PanoptesStil.DUNKEL.onSurfaceVariant, Kontrast.leise(PanoptesStil.DUNKEL))
        // Nacht: gedämpfter Text war 4,9:1, jetzt ≥ 7:1, bleibt aber gedämpfter als der Haupttext
        val n = PanoptesStil.NACHT
        assertTrue(Schleier.kontrast(Kontrast.leise(n), n.surface) < Schleier.kontrast(n.onSurface, n.surface))
        val b = quelle("Bausteine.kt")
        assertTrue(b.contains("else Kontrast.titel(p)") && b.contains("else Kontrast.leise(p)"))
    }

    @Test
    fun bewegungKurzUndAbschaltbar() {
        assertTrue(Bewegung.KURZ in 150L..250L && Bewegung.MITTEL in 150L..250L)
        assertEquals(220L, Bewegung.dauer(220L, weniger = false))
        assertEquals("nie länger als MAX", Bewegung.MAX, Bewegung.dauer(2_000L, false))
        assertEquals("Weniger Bewegung", 0L, Bewegung.dauer(220L, weniger = true))
        assertEquals("Android Animationen aus", 0L, Bewegung.dauer(220L, false, systemSkala = 0f))
        assertFalse("kein Dauer-Loop", quelle("Gestaltung.kt").contains("INFINITE"))
    }

    @Test
    fun angewendetRadienBewegungLeereWerte() {
        val m = quelle("MainActivity.kt")
        val ohneDock = m.substring(0, m.indexOf("    private fun dock(): View {")) +
            m.substring(m.indexOf("    private fun klimaSenden("), m.indexOf("    private fun dockIcon(")) + m.substring(m.indexOf("    private fun appKnopf("))
        for (r in listOf("10f", "16f", "18f", "20f", "24f", "999f")) assertFalse("Radius $r", ohneDock.contains("cornerRadius = ui.dpf($r)"))
        for (d in listOf("Schnellleiste.kt", "FahrzeugKacheln.kt", "Einrichtung.kt"))
            for (r in listOf("10f", "16f", "18f", "20f", "24f")) assertFalse("$d $r", quelle(d).contains("cornerRadius = ui.dpf($r)"))
        assertFalse("Seitenwechsel ohne feste Dauer", m.contains("setDuration(240)"))
        assertTrue(m.contains("val gleiten = Bewegung.dauer(this, Bewegung.MITTEL)") && m.contains("if (richtung != 0 && gleiten > 0)"))
        assertTrue("Fenster blendet ein", m.contains("val ein = Bewegung.dauer(this, Bewegung.KURZ)"))
        val mehr = m.substring(m.indexOf("private fun einstellungen(erweitert: Boolean = false)"), m.indexOf("private fun groesseName("))
        assertTrue("Schalter unter Mehr", mehr.substring(mehr.indexOf("} else {")).contains("R.string.weniger_bewegung, Bewegung.weniger(this)"))
        assertTrue(quelle("Bausteine.kt").contains("LeerWert.ruhig(this, p.onSurface, leiseFarbe)"))
        assertTrue(LeerWert.leer("—") && LeerWert.leer(" ") && LeerWert.leer(null)); assertFalse(LeerWert.leer("42 %"))
        for (d in listOf("values", "values-de", "values-fr", "values-it", "values-nl")) {
            val x = File(basis(), "src/main/res/$d/strings.xml").readText()
            for (n in listOf("weniger_bewegung", "weniger_bewegung_hinweis")) {
                val t = Regex("name=\"$n\">([^<]*)<").find(x)?.groupValues?.get(1)
                assertTrue("$d $n", t != null && !Regex("(^|[^\\\\])'").containsMatchIn(t))
            }
        }
    }

    // ---------- 0.1.43.3 L3 (Projektinhaber 13.11. „launcher passt“) ----------

    /** Glas über einem Grund zusammengesetzt (ohne Android). */
    private fun ueber(vorne: Int, grund: Int): Int {
        val a = ((vorne ushr 24) and 0xFF) / 255f
        fun k(sh: Int) = Math.round(((vorne ushr sh) and 0xFF) * a + ((grund ushr sh) and 0xFF) * (1 - a))
        return (0xFF shl 24) or (k(16) shl 16) or (k(8) shl 8) or k(0)
    }

    @Test
    fun l3SchriftstufenUndPlakette() {
        assertEquals(44f, Stufe.ZAHL_GROSS.sp, 0f); assertEquals(30f, Stufe.ZAHL.sp, 0f)
        val b = Stufe.BESCHRIFTUNG
        assertTrue("Beschriftung: Großbuchstaben, weit gesperrt, gedämpft, Manrope 600", b.gross && b.gedaempft && !b.sora && b.gewicht == 600 && b.sperrung >= 0.14f)
        assertTrue("Plakette lesbar (≥ 14 sp), kleiner als Werte", Stufe.PLAKETTE.sp >= 14f && Stufe.PLAKETTE.sp < Stufe.WERT.sp)
        val q = quelle("Bausteine.kt")
        assertTrue(q.contains("fun plakette(text: String, ton: Signal.Ton, symbol: Int? = null)"))
        assertTrue("Plakette mindestens 40 dp hoch", q.contains("minimumHeight = bd(40)"))
        assertTrue(q.contains("fun glas(r: Int = Mass.RADIUS_GROSS): Drawable = GlasFlaeche("))
    }

    @Test
    fun l3GlasDichtUndLesbar() {
        // keine Unschärfe auf Android 10 → dichter als der Entwurf (.58 / .62)
        assertTrue(((Glas.fuellung(false) ushr 24) and 0xFF) >= 190)
        assertTrue(((Glas.fuellung(true) ushr 24) and 0xFF) >= 220)
        // Schrift auf Glas über dem hellsten und dunkelsten Szenengrund: mindestens 4,5:1
        for (p in listOf(PanoptesStil.DUNKEL, PanoptesStil.HELL)) for (grund in listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), p.aqua or (0xFF shl 24))) {
            val g = ueber(Glas.fuellung(p.hell), grund)
            assertTrue("Text ${p.hell} auf ${Integer.toHexString(grund)}", Schleier.kontrast(p.onSurface, g) >= Kontrast.TAG)
        }
        assertEquals(1, Glas.schatten(false).coerceAtMost(1)); assertTrue(Glas.schatten(false) in 4..16)
    }

    @Test
    fun l3SignalfarbenKontrast() {
        for (p in paletten) {
            assertTrue("Warnung ${p.background}", Schleier.kontrast(Signal.warnung(p), p.surface) >= Kontrast.TAG)
            assertTrue("OK ${p.background}", Schleier.kontrast(Signal.ok(p), p.surface) >= Kontrast.TAG)
        }
        assertEquals("L3-Orange nur für Warnungen", 0xFFFFB454.toInt(), Signal.warnung(PanoptesStil.DUNKEL))
        assertEquals(PanoptesStil.NACHT.warning, Signal.farbe(Signal.Ton.WARNUNG, PanoptesStil.NACHT))
        assertEquals("Akzent bleibt die Design-/eigene Akzentfarbe", PanoptesStil.DUNKEL.aqua, Signal.farbe(Signal.Ton.INFO, PanoptesStil.DUNKEL))
    }

    @Test
    fun l3SzenenwechselNurBeiD() {
        assertEquals(SzenenWechsel.FAHRT, SzenenWechsel.nummer(12, "D"))
        assertEquals(12, SzenenWechsel.nummer(12, "P")); assertEquals(12, SzenenWechsel.nummer(12, "R"))
        assertEquals("Gang unbekannt: Stand-Szene, nie raten", 12, SzenenWechsel.nummer(12, null))
        assertEquals("Motiv ohne Szene bleibt", 3, SzenenWechsel.nummer(3, "D"))
        assertEquals(null, SzenenWechsel.nummer(null, "D"))
        assertTrue(Szenen.ist(SzenenWechsel.FAHRT))
        val m = quelle("MainActivity.kt")
        val w = m.substring(m.indexOf("private fun szeneWechseln()"), m.indexOf("private fun dekodieren("))
        assertTrue("Übergang über Bewegung, abschaltbar", w.contains("val dauer = Bewegung.dauer(this, Bewegung.MITTEL).toInt()") && w.contains("if (dauer <= 0 || alt == null) { root.background = neu; return }"))
        assertTrue(m.substringAfter("private fun hintergrundAnwenden() {").trimStart().startsWith("szeneWechseln()"))
    }

    @Test
    fun l3PlakettenNurAusKernwerten() {
        fun s(json: String) = LauncherContract.parseSummary(json)
        val a = s("""{"state":{"locked":true},"charging":{"active":true,"kw":7.4},"koerper":{"fenster":{"vl":0,"vr":40}}}""")
        val l = HauptPlaketten.liste(FahrzeugStart.zustand(a), a)
        assertEquals(listOf(HauptPlaketten.Art.VERRIEGELT, HauptPlaketten.Art.FENSTER, HauptPlaketten.Art.LAEDT), l.map { it.art })
        assertEquals(Signal.Ton.OK, l[0].ton); assertEquals("Fenster nur abgestellt als Warnung", Signal.Ton.INFO, l[1].ton); assertEquals(7.4, l[2].kw!!, 0.0)
        val ab = s("""{"state":{"acc":"off","speedKmh":0,"locked":true},"koerper":{"fenster":{"vr":40}}}""")
        assertEquals(Signal.Ton.WARNUNG, HauptPlaketten.liste(FahrzeugStart.zustand(ab), ab)[1].ton)
        val leer = s("""{"state":{}}""")
        assertTrue("unbekannt = keine Plakette", HauptPlaketten.liste(FahrzeugStart.zustand(leer), leer).isEmpty())
        val offen = s("""{"state":{"acc":"off","speedKmh":0,"locked":false}}""")
        assertEquals(Signal.Ton.WARNUNG, HauptPlaketten.liste(FahrzeugStart.zustand(offen), offen).single().ton)
        val fahrt = s("""{"state":{"acc":"on","speedKmh":48,"locked":false}}""")
        assertTrue("Entriegelt warnt nicht in Fahrt", HauptPlaketten.liste(FahrzeugStart.zustand(fahrt), fahrt).isEmpty())
        // Regel A+: nichts, was nach Licht/Hupe aussieht, keine Bedienung
        val q = quelle("FahrzeugKacheln.kt")
        val h = q.substring(q.indexOf("object HauptPlaketten"))
        for (v in listOf("flash", "horn", "setOnClickListener", "client.")) assertFalse(v, h.contains(v))
    }

    @Test
    fun appNameNieDerPaketname() {
        assertEquals("Spotify", AppName.anzeige("com.spotify.music", "Spotify", "App"))
        assertEquals("Panoptes", AppName.anzeige(Raster.PANOPTES, null, "App"))
        assertEquals("Panoptes", AppName.anzeige(Raster.PANOPTES, Raster.PANOPTES, "App"))
        assertEquals("App", AppName.anzeige("com.example.x", " ", "App"))
        assertEquals("App", AppName.anzeige("com.example.x", "com.example.x", "App"))
        val m = quelle("MainActivity.kt")
        for (v in listOf("a?.name ?: paket", "?.name ?: p\n")) assertFalse(v, m.contains(v))
        assertFalse(quelle("AppQuelle.kt").contains("?.takeIf { it.isNotBlank() } ?: paket"))
    }

    @Test
    fun l3BefundeG1H2() {
        val m = quelle("MainActivity.kt")
        // G1: App-Symbol im Dock im gleichen Kasten wie die anderen Symbole → gleiche Grundlinie der Namen
        assertTrue(m.contains("if (dock) addView(FrameLayout(this@MainActivity).apply { addView(symbol, FrameLayout.LayoutParams(ui.dp(gross), ui.dp(gross), Gravity.CENTER)) },"))
        assertTrue(m.contains("LinearLayout.LayoutParams(ui.bd(DockPlatz.SYMBOL), ui.bd(DockPlatz.SYMBOL)))"))
        // H2: „Sprechen“ in der Statusleiste statt eines Knopfs rechts mittig über der Musik-Karte
        val k = quelle("SprachKnopf.kt")
        assertFalse("nicht mehr rechts mittig", k.contains("Gravity.END or Gravity.CENTER_VERTICAL"))
        assertTrue(k.contains("if (platz != null) platz.addView(k, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL))"))
        assertTrue(m.contains("sprachPlatz = FrameLayout(this).also { z.addView(it, ui.lp(rechts = 14)) }"))
        // Tempolimit nur belegt und frisch (Tempolimit.anzeige), Fenster-Pillen in Fahrt gedimmt
        val f = quelle("FahrzeugKacheln.kt")
        assertTrue(f.contains("val lim = Tempolimit.anzeige(s?.tempolimit, online())"))
        assertTrue(f.contains("v.alpha = if (FahrzeugStart.darfFenster(s?.gear, s?.acc, s?.speedKmh)) 1f else 0.45f"))
        for (d in listOf("values", "values-de", "values-fr", "values-it", "values-nl")) {
            val x = File(basis(), "src/main/res/$d/strings.xml").readText()
            for (n in listOf("fs_tempolimit", "ansicht_fenster_offen")) assertTrue("$d $n", x.contains("name=\"$n\">"))
        }
    }
}
