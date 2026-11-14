package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** Befunde aus dem E2E-Bericht 0.1.43.4 (G1–G5, H2, H4), nur Launcher. */
class LauncherE2e01434Test {
    private fun basis() = if (File("src/main").isDirectory) File(".") else File("panoptes-launcher")
    private fun quelle(n: String) = File(basis(), "src/main/java/at/greenchili/panoptes/launcher/$n").readText().replace("\r\n", "\n")
    private fun s(json: String) = LauncherContract.parseSummary(json)
    private val sprachen = listOf("values", "values-de", "values-fr", "values-it", "values-nl", "values-es", "values-pt", "values-tr")
    private fun strings(o: String) = File(basis(), "src/main/res/$o/strings.xml").readText()

    @Test
    fun g1KofferraumUndHaubeSindKeineTuer() {
        val k = s("""{"state":{"acc":"on","gear":"D","speedKmh":48,"locked":true},
            "koerper":{"tueren":{"lf":false,"rf":false,"lr":false,"rr":false,"trunk":true,"hood":true},"fenster":{},"dach":false}}""")
        val haupt = HauptPlaketten.liste(FahrzeugStart.zustand(k), k)
        assertFalse("Kofferraum/Haube ergeben keine „Tür offen“", haupt.any { it.art == HauptPlaketten.Art.TUER })
        val reihe = Warnplaketten.reihe(haupt, Warnplaketten.liste(k))
        assertTrue("Verriegelt bleibt in der Reihe", reihe.any { it.haupt?.art == HauptPlaketten.Art.VERRIEGELT })
        assertTrue(reihe.any { it.eigen?.art == Warnplaketten.Art.KOFFERRAUM } && reihe.any { it.eigen?.art == Warnplaketten.Art.HAUBE })
        val tuer = s("""{"state":{"locked":true},"koerper":{"tueren":{"lr":true},"fenster":{},"dach":false}}""")
        assertTrue(HauptPlaketten.liste(FahrzeugStart.zustand(tuer), tuer).any { it.art == HauptPlaketten.Art.TUER })
    }

    @Test
    fun g2NameBrichtNieImWortUm() {
        assertFalse(HauptPlaketten.eigeneZeile(2)); assertTrue(HauptPlaketten.eigeneZeile(3)); assertTrue(HauptPlaketten.eigeneZeile(4))
        val k = quelle("FahrzeugKacheln.kt")
        assertTrue(k.contains("typeface = ui.sora(600); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END"))
        assertTrue(k.contains("setAutoSizeTextTypeUniformWithConfiguration(14, 20, 1"))
        assertTrue(k.contains("val ziel = if (HauptPlaketten.eigeneZeile(teile.size)) zustandZeile else zustand"))
    }

    @Test
    fun g3ZuendungUebersetzt() {
        assertEquals(R.string.acc_an, Zuendung.text("on"))
        assertEquals(R.string.acc_aus, Zuendung.text("off"))
        assertEquals(R.string.ansicht_laedt, Zuendung.text("charging"))
        assertEquals(null, Zuendung.text("x")); assertEquals(null, Zuendung.text(null))
        val m = quelle("MainActivity.kt")
        assertTrue(m.contains("getString(R.string.state_format, accText(s?.acc), s?.gear ?: u)"))
        assertFalse("nie der Rohwert", m.contains("state_format, s?.acc"))
        for (o in sprachen) for (n in listOf("acc_an", "acc_aus")) assertTrue("$o $n", strings(o).contains("name=\"$n\""))
    }

    @Test
    fun g4NamensnennungUebersetzbar() {
        assertEquals("3D: Nazh Design · CC BY 4.0 · modified", Namensnennung.lokal("3D: Nazh Design · CC BY 4.0 · verändert", "modified", "edited"))
        assertEquals("Foto: Ethan Llamas · CC BY-SA 4.0 · düzenlendi", Namensnennung.lokal("Foto: Ethan Llamas · CC BY-SA 4.0 · bearbeitet", "değiştirildi", "düzenlendi"))
        assertEquals("Foto: Quzhouliulian · CC0", Namensnennung.lokal("Foto: Quzhouliulian · CC0", "a", "b"))
        // Jede Namensnennung im Katalog endet bekannt (oder ohne Zusatz) – kein deutscher Rest
        val kat = File(basis(), "src/main/assets/fahrzeug/katalog.json").readText()
        Regex("\"namensnennung\": \"([^\"]*)\"").findAll(kat).forEach {
            val l = Namensnennung.lokal(it.groupValues[1], "X", "Y")
            assertFalse(l, l.contains("verändert") || l.contains("bearbeitet"))
        }
        val k = quelle("FahrzeugKacheln.kt")
        assertEquals(3, Regex("Namensnennung\\.lokal\\(ctx, it\\)").findAll(k).count())
        for (o in sprachen) for (n in listOf("nennung_veraendert", "nennung_bearbeitet")) assertTrue("$o $n", strings(o).contains("name=\"$n\""))
    }

    @Test
    fun g5GrossbuchstabenNachGeraetesprache() {
        val tr = Locale("tr", "TR")
        assertEquals("MENZİL", Gross.text("Menzil", tr))
        assertEquals("MÜZİK", Gross.text("Müzik", tr))
        assertEquals("KİLİT", Gross.text("Kilit", tr))
        assertEquals("MUSIK", Gross.text("Musik", Locale.GERMANY))
        val b = quelle("Bausteine.kt")
        assertFalse("kein .uppercase() ohne Sprache in Bausteinen", b.contains(".uppercase()"))
        assertTrue(b.contains("Gross.text(text, java.util.Locale.getDefault())"))
    }

    @Test
    fun h4H2PanoptesSeiteIstAnleitung() {
        assertEquals("https://byd.serveone.at/panoptes/#installieren", PanoptesSeite.adresse("de"))
        assertEquals("https://byd.serveone.at/panoptes/en/#install", PanoptesSeite.adresse("en"))
        for (l in listOf("fr", "it", "nl", "es", "pt", "tr", null)) assertEquals(PanoptesSeite.DE, PanoptesSeite.adresse(l))
        assertEquals(PanoptesSeite.EN, PanoptesSeite.bitteZiel("https://byd.serveone.at/", "en"))
        assertEquals(PanoptesSeite.DE, PanoptesSeite.bitteZiel("https://byd.serveone.at", "tr"))
        assertEquals("eigene Einsendeseite gilt", "https://byd.serveone.at/einsenden", PanoptesSeite.bitteZiel("https://byd.serveone.at/einsenden", "de"))
        assertFalse(quelle("KernVerbindung.kt").contains("github.com"))
        assertTrue(quelle("FahrzeugKacheln.kt").contains("PanoptesSeite.bitteZiel(it, PanoptesSeite.sprache())"))
    }
}
