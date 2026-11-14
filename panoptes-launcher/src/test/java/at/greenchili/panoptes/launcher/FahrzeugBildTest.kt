package at.greenchili.panoptes.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.1.43.4 (Rang 1): Fahrzeugbild (Dreiviertel-Renderings aus dem Modellkatalog, Lackfarbe linear eingerechnet,
 * Ankerpunkte für den Live-Zustand) und eigene gerenderte Szenen. Nur eigene/CC-Modelle mit Quelle, keine KI-Bilder.
 */
class FahrzeugBildTest {
    private fun basis() = if (File("src/main").isDirectory) File(".") else File("panoptes-launcher")
    private fun datei(rel: String) = File(basis(), rel)
    private fun lies(rel: String) = datei(rel).readText().replace("\r\n", "\n")

    private val katalog by lazy { FahrzeugBild.katalogLesen(lies("src/main/assets/fahrzeug/katalog.json")) }
    private val alle get() = katalog.asset.values.toSet()
    private fun bild(modell: String) = katalog.asset[FahrzeugModelle.norm(modell)]
    private val NAZH = "3D: Nazh Design · CC BY 4.0 · verändert"

    @Test
    fun jedesModellHatGrundLackUndPunkteInDerGroesse() {
        assertEquals("nur die fünf guten Modelle (13.11.)", setOf("seagull", "seal", "seal-u", "dolphin", "atto3"), alle)
        var summe = 0L
        for (a in alle) {
            val g = datei("src/main/assets/fahrzeug/${a}_grund.webp")
            val l = datei("src/main/assets/fahrzeug/${a}_lack.webp")
            val p = datei("src/main/assets/fahrzeug/${a}_punkte.json")
            assertTrue(a, g.isFile && l.isFile && p.isFile)
            assertTrue("$a Grundbild höchstens 110 KB", g.length() <= 110 * 1024)
            assertTrue("$a Lack höchstens 40 KB", l.length() <= 40 * 1024)
            summe += g.length() + l.length()
            // WebP-Kopf und Größe 1200 × 675 (VP8X/VP8L/VP8: Breite/Höhe im Kopf)
            val k = g.readBytes().copyOfRange(0, 16)
            assertEquals("RIFF", String(k, 0, 4)); assertEquals("WEBP", String(k, 8, 4))
        }
        assertTrue("alle Fahrzeugbilder zusammen höchstens 500 KB", summe <= 500L * 1024)
        // Nur erwartete Dateien im Ordner (keine fremden Bilder)
        val erlaubt = alle.flatMap { listOf("${it}_grund.webp", "${it}_lack.webp", "${it}_punkte.json") }.toSet() + "QUELLEN.md" + FahrzeugBild.KATALOG +
            katalog.foto.values.map { "${it}_foto.webp" }
        for (f in datei("src/main/assets/fahrzeug").listFiles()!!) assertTrue("unerwartet: ${f.name}", f.name in erlaubt)
    }

    @Test
    fun ankerpunkteVollstaendigUndSeiteRichtig() {
        val noetig = listOf("tuer_vl", "tuer_hl", "tuer_vr", "tuer_hr", "fenster_vl", "fenster_hl", "fenster_vr", "fenster_hr",
            "dach", "haube", "kofferraum", "rad_vl", "rad_hl", "rad_vr", "rad_hr", "mitte")
        for (a in alle) {
            val txt = lies("src/main/assets/fahrzeug/${a}_punkte.json")
            val m = FahrzeugBild.ankerLesen(txt)
            for (n in noetig) assertTrue("$a: $n", m.containsKey(n))
            assertTrue("$a: linke Seite sichtbar", m["tuer_vl"]!!.sichtbar && m["fenster_hl"]!!.sichtbar)
            assertFalse("$a: rechte Seite verdeckt", m["tuer_vr"]!!.sichtbar || m["fenster_hr"]!!.sichtbar)
            // Kamera vorn links: Vorderrad links liegt links vom Hinterrad links und tiefer im Bild
            assertTrue(a, m["rad_vl"]!!.x < m["rad_hl"]!!.x && m["rad_vl"]!!.y > m["rad_hl"]!!.y)
            assertTrue(a, m["dach"]!!.y < m["mitte"]!!.y)
            val o = org.json.JSONObject(txt)
            assertEquals(1200, o.getInt("breite")); assertEquals(675, o.getInt("hoehe"))
        }
        // Unsinn fällt weg
        val m = FahrzeugBild.ankerLesen("""{"punkte":{"a":{"x":0.5,"y":0.5,"sichtbar":true},"b":{"x":1.7,"y":0.2},"c":{"y":0.2}}}""")
        assertEquals(setOf("a"), m.keys)
        assertEquals(16f / 9f, FahrzeugStart.BILD_SEITEN, 0.01f)
    }

    @Test
    fun lackLinearEingerechnetAlphaBleibt() {
        val grund = intArrayOf(0x80102030.toInt(), 0xff000000.toInt(), 0x00000000, 0xff404040.toInt())
        val lack = intArrayOf(0xff000000.toInt(), 0xffffffff.toInt(), 0xff000000.toInt(), 0xff808080.toInt())
        // ohne Lackanteil: unverändert; Alpha immer aus dem Grund
        val weiss = FahrzeugBild.mische(grund, lack, 0xffffff)
        assertEquals(grund[0], weiss[0]); assertEquals(grund[2], weiss[2])
        assertEquals(0xffffffff.toInt(), weiss[1])
        val rot = FahrzeugBild.mische(grund, lack, 0xb0202a)
        // voller Lack auf Schwarz = Lackfarbe (±1 durch die 4096er-Tabelle)
        for ((schieb, soll) in listOf(16 to 0xb0, 8 to 0x20, 0 to 0x2a))
            assertTrue("Kanal $schieb", Math.abs(((rot[1] shr schieb) and 0xff) - soll) <= 1)
        // linear: Grund 0x40 + halber Lack × Rot → Rot deutlich, Grün/Blau nur wenig über 0x40
        val r = (rot[3] shr 16) and 0xff; val g = (rot[3] shr 8) and 0xff
        assertTrue("r=$r g=$g", r in 0x64..0x74 && g in 0x40..0x48)
        assertEquals(0xff, (rot[3] ushr 24) and 0xff)
    }

    @Test
    fun namensnennungNurFuerCcByUndZustandAnDenAnkern() {
        for (a in alle) assertEquals(a, NAZH, katalog.nennung[a])
        assertEquals("seagull", bild("Dolphin Surf")); assertEquals("seagull", bild("dolphin-surf"))
        assertNull(bild("shark"))
        assertEquals("seal-u", bild("Seal U"))
        // Nur die fünf guten Modelle haben ein Bild; alle Eigenbauten fallen auf die bisherige Darstellung zurück
        for (k in listOf("Dolphin Surf", "seagull", "seal", "Seal U", "dolphin", "atto3")) assertTrue(k, bild(k) != null)
        for (k in listOf("seal_u_dmi", "sealion7", "atto3-evo", "atto2", "han", "tang", "m6", "destroyer"))
            assertNull(k, bild(k))
        // Kaputter oder fehlender Katalog → kein Bild (bisherige Darstellung), nie ein Absturz
        assertTrue(FahrzeugBild.katalogLesen("kaputt").asset.isEmpty())
        assertTrue(FahrzeugBild.katalogLesen("{}").asset.isEmpty())
        // Alias nur auf vorhandene Modelle; CC0 ohne Namensnennung
        val k0 = FahrzeugBild.katalogLesen("""{"modelle":{"han":{"namensnennung":null}},"alias":{"tang":"tang","han ev":"han"}}""")
        assertEquals(mapOf("han" to "han", "hanev" to "han"), k0.asset); assertTrue(k0.nennung.isEmpty())
        val z = FahrzeugStart.Zustand(tuerOffen = mapOf("lf" to true, "rr" to true, "trunk" to false),
            fenster = mapOf("vl" to 60, "hr" to 0, FahrzeugStart.DACH to 80), dach = false)
        assertEquals(setOf("tuer_vl", "tuer_hr", "fenster_vl"), FahrzeugBild.offeneAnker(z).toSet())
        val mitDach = z.copy(dach = true)
        assertTrue("dach" in FahrzeugBild.offeneAnker(mitDach))
        // eingebaut: zuerst eigenes Bild, sonst Kern-Renderbild, Zustand an den Ankern
        val k = lies("src/main/java/at/greenchili/panoptes/launcher/FahrzeugKacheln.kt")
        assertTrue(k.contains("FahrzeugBild.holen(ctx, m!!, FahrzeugStart.lack(lack(), ui.p))"))
        assertTrue(k.contains("FahrzeugBild.namensnennung(ctx, m)"))
        assertTrue(k.indexOf("FahrzeugBild.hat(ctx, m)") < k.indexOf("FahrzeugModelle.hatBild(m)"))
        assertTrue(k.contains("anker?.let { FahrzeugBild.zustandZeichnen(l, z, it, ui.p, r) }"))
    }

    @Test
    fun szenenEigenGerendertTagUndNacht() {
        assertEquals(20, Motive.ANZAHL)
        for (nr in 15..20) { assertTrue(SzenenBilder.ist(nr)); assertTrue(Szenen.ist(nr)) }
        assertFalse(SzenenBilder.ist(14))
        var summe = 0L
        for (nr in 15..20) for (hell in listOf(true, false)) {
            val f = datei("src/main/assets/" + SzenenBilder.datei(nr, hell))
            assertTrue(f.path, f.isFile)
            assertTrue("${f.name} höchstens 350 KB", f.length() <= 350 * 1024)
            summe += f.length()
        }
        assertTrue("alle Szenen zusammen höchstens 3 MB", summe <= 3L * 1024 * 1024)
        // cover-Ausschnitt: breites Bild in schmale Fläche → mittig; hohes Bild → eher unten
        assertArrayEquals(intArrayOf(400, 0, 1200, 1000), SzenenBilder.ausschnitt(1600, 1000, 800f, 1000f))
        val a = SzenenBilder.ausschnitt(1600, 1000, 1600f, 600f)
        assertEquals(1600, a[2]); assertEquals(600, a[3] - a[1]); assertEquals(280, a[1])
        for (d in listOf("values", "values-de", "values-fr", "values-it", "values-nl")) {
            val x = lies("src/main/res/$d/strings.xml")
            for (i in 15..20) assertTrue("$d motiv_$i", x.contains("name=\"motiv_$i\""))
        }
        val m = lies("src/main/java/at/greenchili/panoptes/launcher/MainActivity.kt")
        assertTrue(m.contains("SzenenBilder.anmelden(this)"))
        assertTrue(m.contains("R.string.motiv_20)"))
    }

    /** Eine Modellquelle: katalog.json ist genau der Auszug aus dem Panoptes-Modellkatalog (gleiche GLB, gleiche Lizenz). */
    @Test
    fun eineModellquelleMitPanoptes() {
        val manifest = org.json.JSONObject(File(basis(), "../app/src/main/assets/web/shared/models/manifest.json").readText())
        val roh = org.json.JSONObject(lies("src/main/assets/fahrzeug/katalog.json"))
        assertEquals(manifest.getInt("version"), roh.getInt("manifestVersion"))
        val modelle = roh.getJSONObject("modelle")
        val top = HashSet<String>()
        val liste = manifest.getJSONArray("models")
        for (i in 0 until liste.length()) {
            val m = liste.getJSONObject(i)
            if (!m.optBoolean("top", false) || !m.optBoolean("verfuegbar", true)) continue
            val id = m.getString("id"); top += id
            val e = modelle.getJSONObject(id)
            assertEquals("$id: dieselbe GLB wie Panoptes", m.getString("sha256"), e.getString("sha256"))
            assertEquals(id, m.getString("license"), e.getString("lizenz"))
            assertEquals(id, m.getString("license").startsWith("CC-BY"), !e.isNull("namensnennung"))
            assertTrue("$id: Datei im Katalog", m.getString("file") == "$id.glb")
        }
        assertEquals("katalog.json = Manifest-Einträge mit top", top, alle)
        // Fotos: genau die Einträge mit "photo" ohne top, gleiche SHA-256; Link für die Bitte aus dem Manifest
        val fotos = roh.optJSONObject("fotos") ?: org.json.JSONObject()
        val mitFoto = HashSet<String>()
        for (i in 0 until liste.length()) {
            val m = liste.getJSONObject(i)
            val f = m.optJSONObject("photo") ?: continue
            if (m.getString("id") in top) continue
            mitFoto += m.getString("id")
            assertEquals(f.getString("sha256"), fotos.getJSONObject(m.getString("id")).getString("sha256"))
        }
        assertEquals(mitFoto, fotos.keys().asSequence().toSet())
        assertEquals(manifest.getString("modellBitteUrl"), roh.getString("bitteUrl"))
        val w = File(basis(), "../tools/launcher_fahrzeugbilder.py").readText()
        assertTrue(w.contains("m.get(\"top\") is True") && w.contains("sha(p + \".teil\") != m[\"sha256\"]"))
    }

    /** Projektinhaber 13.11.: Modell ohne gutes 3D → frei lizenziertes Foto mit Namensnennung und Bitte um ein 3D-Modell. */
    @Test
    fun fotoStattZeichnungPlatzhalter() {
        val k = FahrzeugBild.katalogLesen("""{
            "modelle": {"seal": {"namensnennung": "3D: Nazh Design · CC BY 4.0 · verändert"}},
            "fotos": {"han": {"namensnennung": "Foto: Max Muster · CC BY-SA 4.0"},
                      "seal": {"namensnennung": "Foto: X · CC BY 4.0"},
                      "tang": {"namensnennung": null}},
            "alias": {"han ev": "han"},
            "bitteUrl": "https://byd.serveone.at/"}""")
        assertEquals("han", k.foto["han"]); assertEquals("han", k.foto["hanev"])
        assertEquals("Foto: Max Muster · CC BY-SA 4.0", k.fotoNennung["han"])
        assertNull("Rendering geht vor dem Foto", k.foto["seal"]); assertEquals("seal", k.asset["seal"])
        assertNull("Foto ohne Namensnennung nie", k.foto["tang"])
        assertEquals("https://byd.serveone.at/", k.bitteUrl)
        assertNull("nur https", FahrzeugBild.katalogLesen("""{"bitteUrl":"http://x"}""").bitteUrl)
        // Einbau: Foto vor dem alten Kern-Renderbild, ohne Leuchtpunkte und Bodenlicht, Plaketten bleiben
        val kk = lies("src/main/java/at/greenchili/panoptes/launcher/FahrzeugKacheln.kt")
        assertTrue(kk.indexOf("FahrzeugBild.hat(ctx, m)") < kk.indexOf("FahrzeugBild.hatFoto(ctx, m)"))
        assertTrue(kk.indexOf("FahrzeugBild.hatFoto(ctx, m)") < kk.indexOf("FahrzeugModelle.hatBild(m)"))
        assertTrue(kk.contains("bild.setzeBild(bmp, null, foto = true)"))
        assertTrue(kk.contains("if (!foto) FahrzeugStart.bodenlicht(l, z, ui.p, r)"))
        assertTrue(kk.contains("if (!foto) anker?.let { FahrzeugBild.zustandZeichnen(l, z, it, ui.p, r) }"))
        assertTrue(kk.indexOf("FahrzeugStart.symboleZeichnen(l, z, ui.p, r)") > kk.indexOf("if (!foto) anker"))
        assertTrue(kk.contains("R.string.fahrzeug_modell_bitte"))
        // Bitte in 5 Sprachen, Du-Form
        val du = mapOf("values" to "Do you have a 3D model", "values-de" to "Hast du ein 3D-Modell von diesem Auto",
            "values-fr" to "Tu as un modèle 3D", "values-it" to "Hai un modello 3D", "values-nl" to "Heb je een 3D-model")
        for ((d, w) in du) {
            val x = lies("src/main/res/$d/strings.xml")
            val z = x.substring(x.indexOf("name=\"fahrzeug_modell_bitte\""))
            assertTrue("$d: $w", z.substring(0, z.indexOf("</string>")).contains(w))
        }
        // Werkzeug: Lizenzen streng (keine NC/ND), Urheber und Quelle Pflicht, SHA-256 Pflicht
        val w = File(basis(), "../tools/launcher_fahrzeugbilder.py").readText()
        assertTrue(w.contains("CC-(BY|BY-SA)-") && w.contains("ohne Urheber oder Quelle") && w.contains("SHA-256 des Fotos"))
    }

    /** Die gelieferten Fotos (Commons, 13.11.): Lizenz erlaubt, Namensnennung, unkenntlich gemacht, in beiden Listen. */
    @Test
    fun gelieferteFotosMitLizenzUndNennung() {
        assertEquals(setOf("sealion7", "shark", "seal-u-dmi", "atto2", "tang", "m6", "seal6-gt"), katalog.foto.values.toSet())
        val q = lies("src/main/assets/fahrzeug/QUELLEN.md")
        val lizenz = File(basis(), "../app/src/main/assets/licenses/MODELLE_LIZENZEN.md").readText()
        val roh = org.json.JSONObject(lies("src/main/assets/fahrzeug/katalog.json")).getJSONObject("fotos")
        var summe = 0L
        for (id in katalog.foto.values.toSet()) {
            val f = datei("src/main/assets/fahrzeug/${id}_foto.webp")
            assertTrue(id, f.isFile); summe += f.length()
            val k = f.readBytes().copyOfRange(0, 16)
            assertEquals("RIFF", String(k, 0, 4)); assertEquals("WEBP", String(k, 8, 4))
            val e = roh.getJSONObject(id)
            assertTrue(id, e.getString("lizenz") in setOf("CC-BY-SA-4.0", "CC0-1.0"))
            val n = katalog.fotoNennung[id]!!
            assertTrue(n, n.startsWith("Foto: " + e.getString("urheber") + " · "))
            if (e.getString("lizenz").startsWith("CC-BY")) assertTrue("$id: bearbeitet vermerkt", n.endsWith("· bearbeitet"))
            assertTrue("$id in QUELLEN.md", q.contains("`${id}_foto.webp`") && q.contains(e.getString("quelle")))
            assertTrue("$id in MODELLE_LIZENZEN.md", lizenz.contains("`${id}_foto.webp`"))
        }
        assertTrue("Fotos zusammen höchstens 1,2 MB", summe <= 1_200L * 1024)
        // Kennzeichen/Schilder unkenntlich: im Manifest je Foto vermerkt
        val manifest = org.json.JSONObject(File(basis(), "../app/src/main/assets/web/shared/models/manifest.json").readText())
        val liste = manifest.getJSONArray("models")
        for (i in 0 until liste.length()) {
            val m = liste.getJSONObject(i); val p = m.optJSONObject("photo") ?: continue
            if (m.getString("id") in setOf("seal-u-dmi", "atto2", "seal6-gt", "shark", "tang", "m6"))
                assertTrue(m.getString("id"), p.getJSONArray("verdecken").length() > 0)
        }
        assertTrue(q.contains("ebenfalls unter CC BY-SA 4.0"))
    }

    @Test
    fun quellenUndWerkzeugBelegt() {
        val q = lies("src/main/assets/fahrzeug/QUELLEN.md")
        assertTrue(q.contains("CC BY 4.0") && q.contains("Nur diese fünf Modelle") && q.contains("keine KI"))
        val lizenz = File(basis(), "../app/src/main/assets/licenses/MODELLE_LIZENZEN.md").readText()
        assertTrue(lizenz.contains("Launcher-Fahrzeugbild (0.1.43.4)"))
        for (t in listOf("tools/render_launcher_fahrzeug.py", "tools/render_launcher_szenen.py", "tools/launcher_bilder_bauen.py",
                "tools/launcher_fahrzeugbilder.py"))
            assertTrue(t, File(basis(), "../$t").isFile)
    }
}
