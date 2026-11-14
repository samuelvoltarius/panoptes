package at.greenchili.panoptes.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * 0.1.44 (D1b, 12.11.: „warum nutzen wir nicht die modelle die wir schon haben aus Panoptes?“, „es geht ja nicht
 * nur um meinen Surf sondern um alle“): Zuordnung Modell → echtes Renderbild oder gezeichnete Grundform für JEDES
 * bekannte Modell, nie ein fremdes Modell, kein Absturz bei Unbekanntem; Zustände als Ebene über dem Bild.
 */
class LauncherD1bTest {
    private fun basis() = if (File("src/main").isDirectory) File(".") else File("panoptes-launcher")
    private fun app(rel: String) = File(basis().canonicalFile.parentFile, "app/$rel")
    private fun quelle(n: String) = File(basis(), "src/main/java/at/greenchili/panoptes/launcher/$n").readText().replace("\r\n", "\n")

    @Test
    fun jedesKatalogModellHatBildOderForm() {
        val manifest = JSONObject(app("src/main/assets/web/shared/models/manifest.json").readText()).getJSONArray("models")
        val ids = (0 until manifest.length()).map { manifest.getJSONObject(it).getString("id") }
        assertEquals("Katalog vollständig abgedeckt", ids.toSet(), FahrzeugModelle.KATALOG.toSet())
        for (id in ids) {
            assertTrue("$id hat eine eigene Grundform in der Tabelle", FahrzeugModelle.norm(id) in FahrzeugModelle.FORM)
        }
        // Grundformen der Modelle, bei denen die alte Stichwortsuche falsch lag
        assertEquals(FahrzeugStart.Form.SUV, FahrzeugModelle.form("seal-u"))
        assertEquals(FahrzeugStart.Form.SUV, FahrzeugModelle.form("seal-u-dmi"))
        assertEquals(FahrzeugStart.Form.SUV, FahrzeugModelle.form("m6"))
        assertEquals(FahrzeugStart.Form.SUV, FahrzeugModelle.form("shark"))
        assertEquals(FahrzeugStart.Form.LIMOUSINE, FahrzeugModelle.form("destroyer"))
        assertEquals(FahrzeugStart.Form.LIMOUSINE, FahrzeugModelle.form("seal6-gt"))
        assertEquals(FahrzeugStart.Form.LIMOUSINE, FahrzeugModelle.form("Seal"))
        assertEquals(FahrzeugStart.Form.KOMPAKT, FahrzeugModelle.form("seagull"))
        assertEquals(FahrzeugStart.Form.KOMPAKT, FahrzeugModelle.form("dolphin-surf"))
        assertEquals(FahrzeugModelle.form("seal-u"), FahrzeugStart.formAusModell("seal-u"))
    }

    @Test
    fun echtesBildNurWoEsEinesGibt() {
        // gleiche Liste wie VehicleArt im Kern, ohne dessen neutralen Rückfall
        val art = app("src/main/java/at/greenchili/surfmate/ui/vehicle/VehicleArt.kt").readText()
        val zeilen = Regex("^\\s*((?:\"[a-z0-9]+\",?\\s*)+)->\\s*R\\.drawable\\.vehicle_(\\w+)", RegexOption.MULTILINE).findAll(art)
        val mitBild = zeilen.filter { it.groupValues[2] != "fallback" }.flatMap { Regex("\"([a-z0-9]+)\"").findAll(it.groupValues[1]).map { m -> m.groupValues[1] } }.toSet()
        assertEquals(mitBild, FahrzeugModelle.MIT_BILD)
        for (id in listOf("seal", "seal-u", "seal-u-dmi", "dolphin", "atto3", "seagull", "Dolphin Surf")) assertTrue(id, FahrzeugModelle.hatBild(id))
        // nie ein fremdes Modell: ohne eigenes Bild gezeichnete Form
        for (id in listOf("sealion7", "shark", "atto3-evo", "atto2", "han", "tang", "m6", "seal6-gt", "seal6-dmi-touring", "destroyer"))
            assertFalse(id, FahrzeugModelle.hatBild(id))
        assertEquals("3D: Nazh Design · CC BY 4.0 · verändert", FahrzeugModelle.namensnennung("seal"))
        assertNull(FahrzeugModelle.namensnennung("han"))
        // Unbekanntes: kein Absturz, neutrale Form, kein Bild
        for (id in listOf(null, "", "xyz", "ÄÖÜ-123", "  ")) {
            assertFalse(FahrzeugModelle.hatBild(id))
            assertEquals(FahrzeugStart.Form.KOMPAKT, FahrzeugModelle.form(id))
        }
    }

    @Test
    fun ebenenUeberDemBild() {
        val z = FahrzeugStart.Zustand(mapOf("lf" to true, "trunk" to true), mapOf("vl" to 40, "vr" to 0), dach = false, verriegelt = false, laedt = true)
        assertEquals(listOf("schloss", "tuer", "klappe", "fenster", "laden"), FahrzeugStart.symbole(z))
        assertEquals("nur das Schloss, wenn nichts offen ist", listOf("schloss"), FahrzeugStart.symbole(FahrzeugStart.Zustand()))
        assertEquals(listOf("schloss", "dach"), FahrzeugStart.symbole(FahrzeugStart.Zustand(fenster = mapOf("dach" to 50), dach = true)))
        val r = FahrzeugStart.bildRahmen(1200f, 540f)
        assertTrue(r[0] >= 0 && r[1] > 0 && r[0] + r[2] <= 1200f && r[1] + r[3] <= 540f)
        assertEquals(600f / 338f, r[2] / r[3], 0.01f)
    }

    @Test
    fun vertrag() {
        val k = quelle("FahrzeugKacheln.kt")
        assertTrue(k.contains("if (FahrzeugStart.formWahl(ctx) == FahrzeugStart.AUTO && FahrzeugModelle.hatBild(m))"))
        assertTrue(k.contains("Renderbilder.holen(m!!)"))
        assertTrue(k.contains("nennung.text = FahrzeugModelle.namensnennung(m)"))
        // kein 3D im Launcher (WebView/WebGL auf DiLink 3 zu schwer, Begründung im Bericht)
        for (verboten in listOf("WebView", "GLSurfaceView", ".glb")) assertFalse(verboten, k.contains(verboten) || quelle("FahrzeugModelle.kt").contains(verboten))
    }

    /** Vorschaubilder mit den echten Renderbildern (eingebettet) und der gezeichneten Form für Modelle ohne Bild. */
    @Test
    fun vorschau() {
        val ordner = File(basis(), "build/d1b-vorschau").apply { mkdirs() }
        val p = PanoptesStil.DUNKEL
        fun bild(n: String) = "data:image/webp;base64," + Base64.getEncoder().encodeToString(app("src/main/res/drawable-nodpi/vehicle_$n.webp").readBytes())
        fun seite(name: String, szene: Int, titel: String, inhalt: (SvgLeinwand) -> Unit) {
            val l = SvgLeinwand(1920, 720)
            Szenen.zeichne(szene, l, 1920f, 720f, p)
            val glas = Voll(Farbe.alpha(Farbe.rgb(0x0A1824), FahrzeugKacheln.glasDeckung(false)))
            fun text(x: Float, y: Float, t: String, gr: Int, f: Int, w: Int = 600) = l.roh("<text x=\"$x\" y=\"$y\" font-family=\"Segoe UI, sans-serif\" font-size=\"$gr\" font-weight=\"$w\" fill=\"${Farbe.css(f)}\">$t</text>")
            l.rechteck(28f, 64f, 600f, 360f, 26f, glas); l.rahmen(28f, 64f, 600f, 360f, 26f, 1.5f, Farbe.argb(38, 255, 255, 255))
            l.rechteck(28f, 440f, 600f, 150f, 26f, glas); l.rahmen(28f, 440f, 600f, 150f, 26f, 1.5f, Farbe.argb(38, 255, 255, 255))
            text(52f, 110f, "FAHRSTATUS", 16, p.outline); text(274f, 214f, "312 km", 52, p.onSurface); text(52f, 486f, "MUSIK", 16, p.outline)
            text(700f, 96f, titel, 30, p.onSurface)
            inhalt(l)
            l.rechteck(28f, 616f, 1864f, 88f, 44f, glas); l.rahmen(28f, 616f, 1864f, 88f, 44f, 1.5f, Farbe.argb(38, 255, 255, 255))
            listOf("Status", "Karte", "Steuerung", "Apps", "Einstellungen").forEachIndexed { i, t -> text(70f + i * 170f, 668f, t, 20, if (i == 0) p.onSurface else p.onSurfaceVariant) }
            File(ordner, "$name.svg").writeText(l.svg())
        }
        fun mitBild(l: SvgLeinwand, n: String, z: FahrzeugStart.Zustand) {
            val r = FahrzeugStart.bildRahmen(1200f, 440f).let { floatArrayOf(it[0] + 680f, it[1] + 100f, it[2], it[3]) }
            FahrzeugStart.bodenlicht(l, z, p, r)
            l.roh("<image x=\"${r[0]}\" y=\"${r[1]}\" width=\"${r[2]}\" height=\"${r[3]}\" href=\"${bild(n)}\"/>")
            FahrzeugStart.symboleZeichnen(l, z, p, r)
            l.roh("<text x=\"${r[0] + r[2]}\" y=\"${r[1] + r[3] + 22}\" text-anchor=\"end\" font-family=\"Segoe UI, sans-serif\" font-size=\"13\" fill=\"${Farbe.css(p.onSurfaceVariant)}\">${FahrzeugModelle.namensnennung(n)}</text>")
        }
        seite("d1b_surf_laedt", 11, "BYD Dolphin Surf") { mitBild(it, "seagull", FahrzeugStart.Zustand(emptyMap(), mapOf("vl" to 35, "vr" to 0, "hl" to 0, "hr" to 0), false, true, true)) }
        seite("d1b_seal_offen", 12, "BYD Seal") { mitBild(it, "seal", FahrzeugStart.Zustand(mapOf("lf" to true), mapOf("vl" to 0, "vr" to 0), false, false, false)) }
        // ohne eigenes Bild: Grundform mit echter Lackfarbe
        val l = SvgLeinwand(1920, 520)
        l.rechteck(0f, 0f, 1920f, 520f, 0f, Voll(Farbe.dunkler(p.background, 0.5f)))
        listOf(Triple("sealion7", "#2F4A6B", 320f), Triple("han", "#7A1E22", 960f), Triple("m6", "#C9D1D6", 1600f)).forEach { (id, lack, x) ->
            FahrzeugStart.zeichne(l, FahrzeugModelle.form(id), FahrzeugStart.Zustand(verriegelt = true), p, Farbe.hex(lack)!!, x, 250f, 560f)
            l.roh("<text x=\"$x\" y=\"500\" text-anchor=\"middle\" font-family=\"Segoe UI, sans-serif\" font-size=\"22\" fill=\"${Farbe.css(p.onSurface)}\">$id → ${FahrzeugModelle.form(id).name.lowercase()}</text>")
        }
        File(ordner, "d1b_ohne_bild_formen.svg").writeText(l.svg())
    }
}
