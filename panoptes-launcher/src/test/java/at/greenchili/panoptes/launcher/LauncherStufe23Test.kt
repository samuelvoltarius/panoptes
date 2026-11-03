package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class LauncherStufe23Test {
    private fun quelle(datei: String) = String(Files.readAllBytes(Paths.get("src/main/java/at/greenchili/panoptes/launcher/$datei")), Charsets.UTF_8)

    // ---------- Vertrag ----------

    @Test fun summaryMitKachelnOhneErfinden() {
        val s = LauncherContract.parseSummary(
            """{"vehicle":{"modelId":"seagull","color":"#E8E8EC"},"range":{"totalKm":300},"battery":{"socPct":61.5,"healthPct":97},
               "charging":{"active":true,"kw":7.2,"targetPct":80,"etaMin":null},
               "navi":{"aktiv":true,"grund":null,"naechsterStopp":"Ladepark","naechsterKm":41.2,"istZiel":false,"prognoseProzent":23},
               "waechter":{"an":true,"scharf":false,"aufnahme":null,"zustand":"bereit"},"media":null,
               "tyres":{"flBar":2.4,"frBar":2.5,"rlBar":null,"rrBar":2.3,"loBar":2.2,"hiBar":3.0},"air":null,
               "klima":{"sollC":22,"stufe":3,"innenC":null},
               "state":{"acc":"on","gear":"D","speedKmh":48}}"""
        )
        assertEquals(61.5, s.socPct!!, 0.0)
        assertEquals(97.0, s.healthPct!!, 0.0)
        assertTrue(s.charging!!.active); assertNull(s.charging!!.etaMin)
        assertEquals("Ladepark", s.navi!!.naechsterStopp)
        assertEquals(true, s.waechter!!.an); assertNull(s.waechter!!.aufnahme)
        assertEquals("seagull", s.modelId); assertEquals("#E8E8EC", s.farbe)
        assertEquals(48.0, s.speedKmh!!, 0.0)
        assertNull(s.reifen!!.hl); assertEquals(2.2, s.reifen!!.unten!!, 0.0)
        assertNull("keine Luftqualität ohne echten Wert", s.luft)
        assertEquals(3, s.klima!!.stufe)

        val leer = LauncherContract.parseSummary("{}")
        assertNull(leer.charging); assertNull(leer.navi); assertNull(leer.waechter); assertNull(leer.speedKmh); assertNull(leer.modelId)
        val luftLeer = LauncherContract.parseSummary("""{"air":{"pm25Inside":null,"pm25Outside":null}}""")
        assertNull(luftLeer.luft)
        val boese = LauncherContract.parseSummary("""{"vehicle":{"modelId":"../x","color":"red"}}""")
        assertNull(boese.modelId); assertNull(boese.farbe)
    }

    @Test fun appearanceFailClosed() {
        val x = LauncherContract.parseAppearance("""{"startAnsicht":"cockpit","modus":"light","hintergrund":{"bild":true,"video":true}}""")
        assertEquals("cockpit", x.startAnsicht); assertEquals("light", x.modus)
        assertTrue(x.hintergrundBild); assertTrue(x.hintergrundVideo)
        val y = LauncherContract.parseAppearance("""{"startAnsicht":"../x","modus":"pink","hintergrund":{"bild":"true","video":1}}""")
        assertEquals("panoptes", y.startAnsicht); assertEquals("dark", y.modus)
        assertFalse(y.hintergrundBild); assertFalse(y.hintergrundVideo)
    }

    // ---------- Fahrsicherheit ----------

    @Test fun videoNurMitSchalterUndInP() {
        assertTrue(Fahrsicherheit.videoErlaubt(true, "P"))
        assertFalse(Fahrsicherheit.videoErlaubt(false, "P"))
        for (g in listOf("D", "R", "N", null, "p", "")) assertFalse("Gang $g", Fahrsicherheit.videoErlaubt(true, g))
    }

    @Test fun bedienenNurImStand() {
        assertTrue(Fahrsicherheit.bearbeitenErlaubt("P", "on"))
        assertTrue(Fahrsicherheit.bearbeitenErlaubt(null, "off"))
        assertFalse(Fahrsicherheit.bearbeitenErlaubt("D", "off"))
        assertFalse(Fahrsicherheit.bearbeitenErlaubt(null, "on"))
        assertFalse(Fahrsicherheit.bearbeitenErlaubt(null, null))
        assertFalse("Tempo sperrt auch in P", Fahrsicherheit.bearbeitenErlaubt("P", "on", 12.0))
        assertTrue(Fahrsicherheit.bearbeitenErlaubt("P", "on", 0.0))
    }

    @Test fun videoAppsNurInP() {
        assertTrue(Fahrsicherheit.appStartErlaubt(false, "D"))
        assertTrue(Fahrsicherheit.appStartErlaubt(true, "P"))
        assertFalse(Fahrsicherheit.appStartErlaubt(true, "D"))
        assertFalse(Fahrsicherheit.appStartErlaubt(true, null))
    }

    @Test fun quelltextHaeltDieRegeln() {
        val src = quelle("MainActivity.kt")
        assertTrue(src.contains("Fahrsicherheit.videoErlaubt(a?.hintergrundVideo == true, summary?.gear)) videoStart() else videoStopp()"))
        assertTrue("Video ohne Ton", src.contains("mp.setVolume(0f, 0f)"))
        assertTrue(src.contains("Fahrsicherheit.appStartErlaubt(a.video, summary?.gear)"))
        assertFalse("keine Fahrzeugbefehle im Launcher", src.contains("/api/launcher/v1/vehicle"))
        assertFalse("keine Fahrzeugbefehle im Launcher", src.contains("/api/vehicle/"))
        // Der BYD-Rückweg ist fest das erste Dock-Icon und nie Teil der veränderbaren Dock-Liste.
        assertTrue(src.contains("// Fest als erstes Icon, nicht entfernbar: Rückweg zum BYD-Startbildschirm."))
        val dock = src.substring(src.indexOf("private fun dock(): View"))
        assertTrue(dock.indexOf("zumBydStart()") < dock.indexOf("layout.dock.filter"))
    }

    @Test fun appSuche() {
        val l = listOf("com.spotify.music" to "Spotify", "com.waze" to "Waze", "x.y" to "Élan Radio")
        assertEquals(3, AppSuche.filter(l, "").size)
        assertEquals(listOf("com.waze" to "Waze"), AppSuche.filter(l, "WAZ"))
        assertEquals(listOf("x.y" to "Élan Radio"), AppSuche.filter(l, "elan"))
        assertEquals(listOf("com.spotify.music" to "Spotify"), AppSuche.filter(l, "spotify.mu"))
    }

    @Test fun medienFortschritt() {
        assertNull(MedienQuelle.fortschritt(null, 1000))
        assertNull(MedienQuelle.fortschritt(10, 0))
        assertEquals(500, MedienQuelle.fortschritt(500, 1000))
        assertEquals(1000, MedienQuelle.fortschritt(5000, 1000))
    }
}
