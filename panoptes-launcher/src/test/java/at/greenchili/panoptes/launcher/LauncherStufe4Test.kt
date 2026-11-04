package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Stufe 4: Klima im Dock (nur der Kern-Weg `klima` mit sechs Aktionen) und Update-Karte für Panoptes und den
 * Launcher selbst. Keine anderen Fahrzeugbefehle, nie HOME setzen.
 */
class LauncherStufe4Test {

    @Test
    fun klimaNurSechsAktionen() {
        assertEquals(setOf("an", "aus", "temp_hoch", "temp_runter", "luft_hoch", "luft_runter"), LauncherContract.KLIMA_AKTIONEN)
        assertEquals("""{"aktion":"temp_hoch"}""", LauncherContract.klimaBody("temp_hoch"))
        for (boese in listOf("flash", "find", "lock", "trunk", "window", "", "power_on")) {
            try {
                LauncherContract.klimaBody(boese)
                fail(boese)
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun klimaAntwortLesen() {
        val ok = LauncherContract.parseKlima("""{"ok":true,"aktion":"luft_hoch","ausgang":"success","klima":{"sollC":21.5,"stufe":4,"innenC":null,"an":true}}""")
        assertTrue(ok.ok)
        assertEquals(4, ok.klima!!.stufe)
        assertEquals(true, ok.klima!!.an)
        val nein = LauncherContract.parseKlima("""{"ok":false,"grund":"signatur"}""")
        assertFalse(nein.ok)
        assertEquals("signatur", nein.grund)
        assertFalse(LauncherContract.parseKlima("kaputt").ok)
        assertFalse(LauncherContract.parseKlima(null).ok)
        val s = LauncherContract.parseSummary("""{"klima":{"sollC":22,"stufe":3,"innenC":null,"an":false}}""")
        assertEquals(false, s.klima!!.an)
        assertNull(LauncherContract.parseSummary("""{"klima":{"sollC":22,"stufe":3,"innenC":null}}""").klima!!.an)
    }

    @Test
    fun updateKarteNurBeiNeuem() {
        val beide = LauncherContract.parseUpdate("""{
            "panoptes":{"verfuegbar":{"version":"0.1.38","versionCode":64,"groesseBytes":75000000},"installierbar":false,
                        "mobilFrage":false,"nichtWeil":"zuendung_an","phase":null},
            "launcher":{"installiert":1,"verfuegbar":{"version":"0.1.38","versionCode":2,"groesseBytes":4200000},
                        "neuer":true,"installierbar":false,"mobilFrage":true,"nichtWeil":"wartet_wlan","phase":null}}""")
        assertEquals("0.1.38", beide.panoptes!!.version)
        assertEquals(64, beide.panoptes!!.versionCode)
        assertEquals("zuendung_an", beide.panoptes!!.nichtWeil)
        assertEquals(2, beide.launcher!!.versionCode)
        assertTrue(beide.launcher!!.mobilFrage)
        assertEquals(4_200_000L, beide.launcher!!.groesseBytes)

        val nichts = LauncherContract.parseUpdate("""{"panoptes":{"verfuegbar":null},
            "launcher":{"installiert":2,"verfuegbar":{"version":"0.1.38","versionCode":2},"neuer":false}}""")
        assertNull(nichts.panoptes)
        assertNull(nichts.launcher)
        assertEquals(UpdateStand(null, null), LauncherContract.parseUpdate("""{"panoptes":null,"launcher":null}"""))
        // kaputte Versionsangabe: keine Karte
        assertNull(LauncherContract.parseUpdate("""{"panoptes":{"verfuegbar":{"version":"<b>x</b>","versionCode":3}}}""").panoptes)
        // pausierter Download ist kein laufendes Update
        assertNull(LauncherContract.parseUpdate("""{"panoptes":{"verfuegbar":{"version":"0.1.38","versionCode":64},"phase":"pausiert"}}""").panoptes!!.phase)
    }

    @Test
    fun installierenKoerper() {
        val p = UpdatePaket("0.1.38", 64, null, true, false, null, null)
        val body = org.json.JSONObject(LauncherContract.installierenBody("panoptes", p, true))
        assertEquals("panoptes", body.getString("paket"))
        assertEquals("0.1.38", body.getString("version"))
        assertEquals(64, body.getInt("versionCode"))
        assertTrue(body.getBoolean("mobil"))
    }

    @Test
    fun quelltextNurKlimaWegKeinHome() {
        val client = quelle("PanoptesClient.kt")
        assertTrue(client.contains("/api/launcher/v1/klima"))
        assertFalse(client.contains("/api/launcher/v1/vehicle"))
        assertFalse(client.contains("/api/vehicle"))
        val main = quelle("MainActivity.kt")
        for (verboten in listOf("set-home-activity", "setPreferredActivity", "clearPackagePreferredActivities", "/api/vehicle")) {
            assertFalse(verboten, main.contains(verboten))
        }
        // Klima nur über den Client-Weg mit fester Aktionsliste
        assertTrue(main.contains("client.klima(aktion)"))
        val knoepfe = Regex("\\{ \"([a-z_]+)\" \\}").findAll(main).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("temp_hoch", "temp_runter", "luft_hoch", "luft_runter"), knoepfe)
        assertTrue(main.contains("klimaSenden(if (summary?.klima?.an == true) \"aus\" else \"an\")"))
        // 0.1.38.5: Aufrufer = Dock (Knöpfe, an/aus) und Klima-Feld (−/+ je Reihe, an/aus); nur Aktionen der festen Liste
        val aufrufe = Regex("klimaSenden\\(([^)]*)\\)").findAll(main).map { it.groupValues[1] }.toList()
        // 0.1.38.6: dazu das Komfort-Feld (an/aus-Paar und Kachel); deren feste Namen prüft LauncherKomfortTest
        assertEquals(listOf("aktion(", "if (summary?.klima?.an == true", "aktion: String", "runter", "hoch",
            "if (summary?.klima?.an == true", "an", "aus", "aktion"), aufrufe)
    }

    @Test
    fun androidAutoUndCarplayAbWerkImDock() {
        val l = Raster.standard(null)
        assertEquals(listOf(Raster.PANOPTES, "com.byd.androidauto", "com.byd.carplay.ui"), l.dock)
        // nur sichtbar, wenn startbar (andere Autos ohne diese Pakete: unsichtbar)
        assertTrue(quelle("MainActivity.kt").contains("layout.dock.filter { it !in Raster.PROJEKTION || startbar(it) }"))
    }

    @Test
    fun nebenDemLauncherEinstellungUndProApp() {
        var l = Raster.standard(null)
        assertFalse(Raster.oeffnetNeben(l, "com.waze"))
        l = Raster.nebenUmschalten(l, "com.waze")
        assertTrue("pro App umgekehrt", Raster.oeffnetNeben(l, "com.waze"))
        l = l.copy(nebeneinander = true)
        assertFalse("Einstellung an, App ausgenommen", Raster.oeffnetNeben(l, "com.waze"))
        assertTrue(Raster.oeffnetNeben(l, "com.spotify.music"))
        val zurueck = Raster.fromJson(Raster.toJson(l))!!
        assertTrue(zurueck.nebeneinander)
        assertEquals(listOf("com.waze"), zurueck.nebenAnders)
        assertFalse("Standard aus", Raster.fromJson(Raster.toJson(Raster.standard(null)))!!.nebeneinander)
        assertEquals(l, Raster.nebenUmschalten(l, "../x").let { if (it === l) l else it })
        val body = org.json.JSONObject(LauncherContract.launchBody("com.waze", "neben"))
        assertEquals("com.waze", body.getString("package"))
        assertEquals("neben", body.getString("mode"))
        val main = quelle("MainActivity.kt")
        // 0.1.38.5: kein stilles Vollbild mehr – ehrliche Frage, Vollbild nur auf „Im Vollbild öffnen“
        assertTrue(main.contains("else -> nichtTeilbarFragen(p, r)"))
        // 0.1.38.8: hat der Kern selbst ins Vollbild ausweichen müssen, sagt der Launcher das kurz
        assertTrue(main.contains("NebenErgebnis.VOLLBILD -> zeigeHinweis(getString(R.string.neben_geht_nicht))"))
        assertTrue(main.contains(".setPositiveButton(R.string.im_vollbild_oeffnen) { _, _ -> starteVollbild(p) }"))
        // Video-Sperre greift vor dem Nebeneinander
        assertTrue(main.indexOf("appStartErlaubt") < main.indexOf("if (Raster.oeffnetNeben(layout, p) && p != Raster.PANOPTES)"))
    }

    private fun quelle(name: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            for (rel in listOf("src/main/java/at/greenchili/panoptes/launcher/$name", "panoptes-launcher/src/main/java/at/greenchili/panoptes/launcher/$name")) {
                val f = File(dir, rel)
                if (f.isFile) return f.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("nicht gefunden: $name")
    }
}
