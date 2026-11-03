package at.greenchili.panoptes.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.1.38 Layout im Kern: Abgleich lokal ↔ Kern, neueste Änderung gewinnt, Live über summary. */
class LayoutAbgleichTest {

    private val std = Raster.standard(null)
    private fun kern(geaendert: Long, standard: Boolean = false) = KernLayout(std, geaendert, standard)

    @Test
    fun beimHolen() {
        // lokale Änderung offen → hochladen
        assertEquals(LayoutAbgleich.Schritt.HOCHLADEN, LayoutAbgleich.beimHolen(kern(5), 5, true, true, true))
        // Umstieg: altes eigenes Layout, Kern hat noch nie etwas gespeichert → einmal hochladen
        assertEquals(LayoutAbgleich.Schritt.HOCHLADEN, LayoutAbgleich.beimHolen(kern(0, true), 0, false, true, false))
        // nach „Auf Standard zurücksetzen“ (geaendert ≠ 0) wird das alte lokale Layout NICHT wieder hochgeladen
        assertEquals(LayoutAbgleich.Schritt.UEBERNEHMEN, LayoutAbgleich.beimHolen(kern(900, true), 0, false, true, false))
        // frisch installiert: Standard des Kerns übernehmen
        assertEquals(LayoutAbgleich.Schritt.UEBERNEHMEN, LayoutAbgleich.beimHolen(kern(0, true), 0, false, false, false))
        // im Web geändert
        assertEquals(LayoutAbgleich.Schritt.UEBERNEHMEN, LayoutAbgleich.beimHolen(kern(7), 5, false, true, true))
        assertEquals(LayoutAbgleich.Schritt.NICHTS, LayoutAbgleich.beimHolen(kern(5), 5, false, true, true))
    }

    @Test
    fun konfliktUndLive() {
        assertTrue(LayoutAbgleich.nochmalSenden(2000, 1500))
        assertFalse(LayoutAbgleich.nochmalSenden(1000, 1500))
        assertTrue(LayoutAbgleich.neuHolen(8L, 5L, false, false))
        assertFalse(LayoutAbgleich.neuHolen(8L, 5L, false, true))   // nie während Bearbeiten
        assertFalse(LayoutAbgleich.neuHolen(8L, 5L, true, false))   // eigene Änderung wartet
        assertFalse(LayoutAbgleich.neuHolen(5L, 5L, false, false))
        assertFalse(LayoutAbgleich.neuHolen(null, 5L, false, false)) // alter Kern ohne Feld
    }

    @Test
    fun antwortenLesen() {
        val l = JSONObject(Raster.toJson(std))
        val get = JSONObject().put("layout", l).put("geaendert", 42L).put("quelle", "web").put("standard", false).toString()
        val k = LayoutAbgleich.parseKern(get)
        assertNotNull(k)
        assertEquals(42L, k!!.geaendert)
        assertEquals(Raster.toJson(std), Raster.toJson(k.layout))
        assertNull(LayoutAbgleich.parseKern("{\"layout\":{\"format\":\"x\"}}"))
        assertNull(LayoutAbgleich.parseKern("kaputt"))

        val ok = LayoutAbgleich.parseSpeichern(200, JSONObject().put("ok", true).put("geaendert", 43L).put("layout", l).toString())
        assertEquals(43L, (ok as SpeicherAntwort.Ok).geaendert)
        val neuer = LayoutAbgleich.parseSpeichern(409, get)
        assertEquals(42L, (neuer as SpeicherAntwort.Neuer).kern.geaendert)
        assertEquals(403, (LayoutAbgleich.parseSpeichern(403, "{}") as SpeicherAntwort.Fehler).status)

        val body = JSONObject(LayoutAbgleich.speichernBody(std, 42L))
        assertEquals(42L, body.getLong("basis"))
        assertEquals("panoptes-launcher-layout", body.getJSONObject("layout").getString("format"))
    }

    @Test
    fun summaryTraegtDenZeitstempel() {
        assertEquals(17L, LauncherContract.parseSummary("{\"launcherLayout\":{\"geaendert\":17}}").layoutGeaendert)
        assertNull(LauncherContract.parseSummary("{}").layoutGeaendert)
        assertNull(LauncherContract.parseSummary("{\"launcherLayout\":{\"geaendert\":null}}").layoutGeaendert)
    }

    /** Kern (LauncherLayoutPruefung.standard) und Launcher (Raster.standard) bauen denselben Standard. */
    @Test
    fun standardGleichDemKern() {
        val s = JSONObject(Raster.toJson(Raster.standard(null)))
        assertEquals(2, s.getJSONArray("seiten").length())
        assertEquals("fahrzeug", s.getJSONArray("seiten").getJSONArray(0).getJSONObject(0).getString("kachel"))
        assertEquals("[\"at.greenchili.surfmate\",\"com.byd.androidauto\",\"com.byd.carplay.ui\"]", s.getJSONArray("dock").toString())
        assertEquals(10, s.getInt("zaehler"))
    }
}
