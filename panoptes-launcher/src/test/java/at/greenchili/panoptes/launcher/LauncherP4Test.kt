package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.1.44 (P4): Haus-Kachel (Home Assistant, NUR LESEN) und Fahrerprofile. Reine Regeln, Verträge (kein Schaltweg,
 * keine Zugangsdaten, Sitz nur lesen), Texte in fünf Sprachen, Layout-Prüfung im Kern kennt die neuen Kacheln.
 */
class LauncherP4Test {

    private fun quelle(name: String): String {
        val a = File("src/main/java/at/greenchili/panoptes/launcher/$name")
        val f = if (a.isFile) a else File("panoptes-launcher/src/main/java/at/greenchili/panoptes/launcher/$name")
        return f.readText()
    }

    // ---------- Haus ----------

    @Test
    fun auswahlHoechstensSechsOhneDoppelteUndNurGueltige() {
        val roh = "light.a,light.b,light.a,xx,light.c,light.d,light.e,light.f,light.g,Licht;rm"
        val l = Haus.auswahlLesen(roh)
        assertEquals(listOf("light.a", "light.b", "light.c", "light.d", "light.e", "light.f"), l)
        assertEquals(l, Haus.auswahlLesen(Haus.auswahlSchreiben(l)))
        assertTrue(Haus.auswahlLesen(null).isEmpty())
        assertEquals("siebtes wird nicht aufgenommen", l, Haus.umschalten(l, "light.g"))
        assertEquals(l - "light.b", Haus.umschalten(l, "light.b"))
        assertEquals(listOf("light.a", "cover.tor"), Haus.umschalten(listOf("light.a"), "cover.tor"))
    }

    @Test
    fun zustandWorteUndWarnung() {
        assertEquals(Haus.Wort.OFFEN, Haus.wort("tor", "open"))
        assertEquals(Haus.Wort.ZU, Haus.wort("tor", "closed"))
        assertEquals(Haus.Wort.ENTRIEGELT, Haus.wort("schloss", "unlocked"))
        assertEquals(Haus.Wort.VERRIEGELT, Haus.wort("schloss", "locked"))
        assertEquals(Haus.Wort.AN, Haus.wort("licht", "on"))
        assertEquals(Haus.Wort.AN, Haus.wort("klima", "heat"))
        assertEquals(Haus.Wort.AUS, Haus.wort("schalter", "off"))
        for (z in listOf("unavailable", "unknown", "", null, "irgendwas")) assertEquals(Haus.Wort.UNBEKANNT, Haus.wort("licht", z))
        assertTrue(Haus.warnung("tor", Haus.Wort.OFFEN))
        assertTrue(Haus.warnung("schloss", Haus.Wort.ENTRIEGELT))
        assertFalse(Haus.warnung("licht", Haus.Wort.AN))
        assertFalse(Haus.warnung("tor", Haus.Wort.ZU))
    }

    @Test
    fun parseUndZeilenInReihenfolgeDerWahlFehlendesAlsStrich() {
        val json = """{"ok":true,"zeit":1,"alterSek":400,"fehlt":["light.weg"],"geraete":[
            {"entity":"light.flur","titel":"Flur","art":"licht","zustand":"on"},
            {"entity":"cover.garage","titel":"Garage","raum":"Haus","art":"tor","zustand":"open"},
            {"entity":"bad entity","titel":"x","art":"licht"}]}"""
        val s = Haus.parse(json)!!
        assertEquals(2, s.geraete.size)
        assertEquals(400L, s.alterSek)
        val z = Haus.zeilen(s, listOf("cover.garage", "light.weg", "light.flur"))
        assertEquals(listOf("Garage", "weg", "Flur"), z.map { it.titel })
        assertEquals(Haus.Wort.OFFEN, z[0].wort); assertTrue(z[0].warn)
        assertEquals(Haus.Wort.UNBEKANNT, z[1].wort)
        assertNull(Haus.parse("kaputt")); assertNull(Haus.parse("""{"ok":false}"""))
    }

    @Test
    fun alterWirdGezeigtUndVeraltetIstAbgeblendet() {
        assertNull(Haus.alterMinuten(30)); assertNull(Haus.alterMinuten(null))
        assertEquals(5, Haus.alterMinuten(300))
        assertFalse(Haus.veraltet(600)); assertTrue(Haus.veraltet(Haus.VERALTET_SEK)); assertFalse(Haus.veraltet(null))
        assertEquals(2, Haus.sichtbar(1)); assertEquals(4, Haus.sichtbar(2)); assertEquals(6, Haus.sichtbar(3))
    }

    @Test
    fun hausKachelHatKeinenSchaltwegUndKeineZugangsdaten() {
        val k = quelle("HausKachel.kt")
        for (verboten in listOf("\"POST\"", "\"PUT\"", "Authorization", "token", "Token", "password", "setOnLongClickListener", "setOnClickListener")) {
            assertFalse(verboten, k.contains(verboten))
        }
        val c = quelle("PanoptesClient.kt")
        val i = c.indexOf("fun haus(")
        assertTrue(c.substring(i, c.indexOf("val hintergrundVideoUrl", i)).let { it.contains("\"GET\"") && !it.contains("\"POST\"") })
        // MainActivity: Tipp auf die Kachel wählt nur Geräte
        val m = quelle("MainActivity.kt")
        assertTrue(m.contains("Kachel.HAUS -> v.setOnClickListener { hausWaehlen() }"))
        assertTrue(m.contains("Kachel.HAUS -> haus.ansicht(gross, e.h)"))
    }

    // ---------- Fahrerprofile ----------

    private val zwei = Fahrerprofile.Stand(aktiv = 1, zweitesDa = true, sitz1 = "p1", sitz2 = "p2", automatisch = true)

    @Test
    fun abWerkEinProfilKeinWechsel() {
        val st = Fahrerprofile.Stand()
        assertFalse(st.zweitesDa); assertFalse(st.automatisch)
        assertNull(Fahrerprofile.anderes(st))
        assertFalse(Fahrerprofile.moeglich(st, 2))
        assertNull(Fahrerprofile.ziel(st.copy(sitz2 = "p2"), "p2", null, "P", 0.0))
    }

    @Test
    fun wechselMoeglichNurZumAnderenAngelegtenProfil() {
        assertEquals(2, Fahrerprofile.anderes(zwei)); assertEquals(1, Fahrerprofile.anderes(zwei.copy(aktiv = 2)))
        assertTrue(Fahrerprofile.moeglich(zwei, 2)); assertFalse(Fahrerprofile.moeglich(zwei, 1))
        assertFalse(Fahrerprofile.moeglich(zwei, 3)); assertFalse(Fahrerprofile.moeglich(zwei, 0))
    }

    @Test
    fun automatikNurImStandBeiAenderungUndEindeutigerZuordnung() {
        assertEquals(2, Fahrerprofile.ziel(zwei, "p2", "p1", "P", 0.0))
        assertEquals("erste Erkennung nach dem Start zählt", 2, Fahrerprofile.ziel(zwei, "p2", null, "P", null))
        assertNull("gleiche Position wie zuletzt: kein Wechsel gegen einen Tipp", Fahrerprofile.ziel(zwei, "p2", "p2", "P", 0.0))
        assertNull("schon aktiv", Fahrerprofile.ziel(zwei, "p1", "p2", "P", 0.0))
        assertNull("nicht in P", Fahrerprofile.ziel(zwei, "p2", "p1", "D", 0.0))
        assertNull("Gang unbekannt", Fahrerprofile.ziel(zwei, "p2", "p1", null, 0.0))
        assertNull("Tempo", Fahrerprofile.ziel(zwei, "p2", "p1", "P", 3.0))
        assertNull("Schalter aus", Fahrerprofile.ziel(zwei.copy(automatisch = false), "p2", "p1", "P", 0.0))
        assertNull("keine Position erkannt", Fahrerprofile.ziel(zwei, null, "p1", "P", 0.0))
        assertNull("unbekannte Position", Fahrerprofile.ziel(zwei, "px", "p1", "P", 0.0))
        assertNull("beide Profile derselben Position: mehrdeutig", Fahrerprofile.ziel(zwei.copy(sitz1 = "p2"), "p2", "p1", "P", 0.0))
    }

    @Test
    fun abfrageNurWennSieEtwasBewirkenKann() {
        val t = Fahrerprofile.SITZ_TAKT_MS
        assertTrue(Fahrerprofile.sitzAbfragen(zwei, "P", 0.0, t, 0L))
        assertFalse(Fahrerprofile.sitzAbfragen(zwei, "P", 0.0, t - 1, 0L))
        assertFalse(Fahrerprofile.sitzAbfragen(zwei, "D", 0.0, t, 0L))
        assertFalse(Fahrerprofile.sitzAbfragen(zwei.copy(automatisch = false), "P", 0.0, t, 0L))
        assertFalse(Fahrerprofile.sitzAbfragen(zwei.copy(zweitesDa = false), "P", 0.0, t, 0L))
    }

    @Test
    fun parseSitz() {
        val i = Fahrerprofile.parseSitz("""{"ok":true,"lesbar":true,"aktuell":"p2","positionen":[{"id":"p1","name":"Anna"},{"id":"p2","name":"Ben"}]}""")!!
        assertEquals(listOf("p1" to "Anna", "p2" to "Ben"), i.positionen); assertEquals("p2", i.aktuell); assertTrue(i.lesbar)
        val n = Fahrerprofile.parseSitz("""{"ok":true,"lesbar":false,"aktuell":null,"positionen":[]}""")!!
        assertNull(n.aktuell); assertFalse(n.lesbar)
        assertNull(Fahrerprofile.parseSitz("kaputt"))
    }

    @Test
    fun profilLayoutEigenesDesignUeberJsonUndProfilKachelImLayout() {
        val a = Raster.design(Raster.standard(null), "hell")
        val b = Raster.design(Raster.standard(null), "nacht")
        assertEquals("hell", Raster.fromJson(Raster.toJson(a))!!.design)
        assertEquals("nacht", Raster.fromJson(Raster.toJson(b))!!.design)
        val mit = Fahrerprofile.mitKachel(a)
        assertTrue(Raster.hatWidget(mit, Kachel.PROFIL))
        assertTrue("Profil-Kachel übersteht Speichern und Laden", Raster.hatWidget(Raster.fromJson(Raster.toJson(mit))!!, Kachel.PROFIL))
        assertTrue(Raster.hatWidget(Raster.fromJson(Raster.toJson(Raster.seiteHinzufuegen(a).let { Raster.widgetHinzufuegen(it, it.seiten.size - 1, Kachel.HAUS, listOf(2 to 2)) }))!!, Kachel.HAUS))
        assertEquals("zweimal nie doppelt", mit, Fahrerprofile.mitKachel(mit))
    }

    @Test
    fun sitzWirdNurGelesenNieGesetzt() {
        val k = quelle("Fahrerprofile.kt") + quelle("HausKachel.kt")
        val m = quelle("MainActivity.kt")
        val c = quelle("PanoptesClient.kt")
        val i = c.indexOf("fun sitz()")
        assertTrue(c.substring(i, c.indexOf("val hintergrundVideoUrl", i)).let { it.contains("\"GET\"") && !it.contains("\"POST\"") })
        for (v in listOf("positions/apply", "positions/capture", "/api/positions", "seatApply", "sitzSetzen(", "applySeat")) {
            if (v == "sitzSetzen(") continue   // lokale Zuordnung der Profile, kein Fahrzeug
            assertFalse(v, k.contains(v)); assertFalse(v, m.contains(v))
        }
        // der Wechsel läuft nur im Stand: darfBedienen vor dem Tipp-Wechsel
        assertTrue(m.contains("private fun profilTipp() {\r\n        if (!darfBedienen()) return") || m.contains("private fun profilTipp() {\n        if (!darfBedienen()) return"))
    }

    // ---------- Texte, Kern-Prüfung ----------

    @Test
    fun alleNeuenTexteInFuenfSprachen() {
        val keys = listOf("widget_haus", "widget_profil", "haus_leer", "haus_waehlen", "haus_keine", "haus_hinweis", "haus_alter", "haus_max",
            "haus_an", "haus_aus", "haus_offen", "haus_zu", "haus_verriegelt", "haus_entriegelt", "haus_nicht_lesbar", "profil_titel",
            "profil_name_1", "profil_name_2", "profil_hinweis", "profil_aktiv", "profil_wechseln", "profil_gewechselt", "profil_anlegen",
            "profil_entfernen", "profil_entfernen_erst", "profil_sitz", "profil_sitz_keine", "profil_sitz_waehlen", "profil_sitz_hinweis",
            "profil_auto", "profil_sitz_leer")
        for (o in listOf("values", "values-de", "values-fr", "values-it", "values-nl")) {
            val a = File("src/main/res/$o/strings.xml")
            val t = (if (a.isFile) a else File("panoptes-launcher/src/main/res/$o/strings.xml")).readText()
            for (k in keys) assertTrue("$o: $k", t.contains("name=\"$k\""))
        }
    }

    @Test
    fun kernLayoutPruefungKenntDieNeuenKacheln() {
        val a = File("../app/src/main/java/at/greenchili/surfmate/server/LauncherLayoutPruefung.java")
        val t = (if (a.isFile) a else File("app/src/main/java/at/greenchili/surfmate/server/LauncherLayoutPruefung.java")).readText()
        for (k in Kachel.values()) if (k != Kachel.GERAETEWIDGET) assertTrue(k.name, t.contains("\"" + k.name.lowercase() + "\""))
    }
}
