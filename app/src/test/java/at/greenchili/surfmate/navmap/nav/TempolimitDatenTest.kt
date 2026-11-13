package at.greenchili.surfmate.navmap.nav

import at.greenchili.surfmate.server.NaviApiHandlerTempolimitBruecke
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.1.44 (P3b, Tempolimit-Daten): Planer-Feld `tempolimits` (je Bein `[[von, bis, kmh], …]`, OSM `maxspeed`) -> NavRoute ->
 * Limit an der Teilstrecke -> Meldung an den Kern -> [TempolimitStand]. Nur belegte Werte, Frische, und ohne Wert ohne Anzeige.
 */
class TempolimitDatenTest {

    @After
    fun weg() {
        TempolimitStand.loeschen()
    }

    private fun quelle(rel: String): String {
        val p = "src/main/$rel"
        return (listOf(File(p), File("app/$p")).first { it.isFile }).readText().replace("\r\n", "\n")
    }

    /** Polyline mit Genauigkeit 1e6 (Valhalla `shape`), nur für den Test. */
    private fun enc(punkte: List<Pair<Double, Double>>): String {
        val sb = StringBuilder()
        var la = 0L
        var lo = 0L
        fun wert(v0: Long) {
            var v = if (v0 < 0) (v0 shl 1).inv() else v0 shl 1
            while (v >= 0x20) { sb.append(((0x20L or (v and 0x1f)) + 63).toInt().toChar()); v = v shr 5 }
            sb.append((v + 63).toInt().toChar())
        }
        for ((a, b) in punkte) {
            val ia = Math.round(a * 1e6); val ib = Math.round(b * 1e6)
            wert(ia - la); wert(ib - lo); la = ia; lo = ib
        }
        return sb.toString()
    }

    private fun leg(punkte: List<Pair<Double, Double>>) = JSONObject()
        .put("shape", enc(punkte)).put("summary", JSONObject().put("length", 1.0).put("time", 60))
        .put("maneuvers", JSONArray().put(JSONObject().put("type", 1).put("instruction", "Start").put("begin_shape_index", 0)
            .put("length", 1.0).put("time", 60)))

    private fun antwort(tempo: Any?, vararg beine: List<Pair<Double, Double>>): String {
        val legs = JSONArray(); beine.forEach { legs.put(leg(it)) }
        val o = JSONObject().put("ok", true).put("vertrag", "fuehrung/1").put("instanz", "eu")
            .put("trip", JSONObject().put("legs", legs).put("summary", JSONObject().put("length", 2.0).put("time", 120)))
        if (tempo != null) o.put("tempolimits", tempo)
        return o.toString()
    }

    private val p0 = 48.0 to 14.0
    private val p1 = 48.0 to 14.01
    private val p2 = 48.0 to 14.02
    private val p3 = 48.0 to 14.03
    private val p4 = 48.0 to 14.04

    // ---- Feld -> Route

    @Test
    fun feldWirdMitDemVersatzDerManoeverUebernommen() {
        // zwei Beine, das zweite beginnt dort, wo das erste endet (gemeinsamer Punkt p2 wird nicht doppelt geführt)
        val tempo = JSONArray().put(JSONArray().put(JSONArray().put(0).put(2).put(50)))
            .put(JSONArray().put(JSONArray().put(0).put(2).put(70)))
        val r = ValhallaRouteClient.parseRoutes(antwort(tempo, listOf(p0, p1, p2), listOf(p2, p3, p4))).first()
        assertEquals(5, r.points.size)
        assertEquals(listOf(TempoAbschnitt(0, 2, 50), TempoAbschnitt(2, 4, 70)), r.tempolimits)
        assertEquals(50, Tempolimits.fuer(r.tempolimits, 0))
        assertEquals(50, Tempolimits.fuer(r.tempolimits, 1))
        assertEquals(70, Tempolimits.fuer(r.tempolimits, 2))
        assertEquals(70, Tempolimits.fuer(r.tempolimits, 3))
        assertNull("hinter dem Ende", Tempolimits.fuer(r.tempolimits, 4))
    }

    @Test
    fun nichtAneinanderstossendeBeineBekommenIhrenEigenenVersatz() {
        val tempo = JSONArray().put(JSONArray().put(JSONArray().put(0).put(2).put(50)))
            .put(JSONArray().put(JSONArray().put(0).put(2).put(30)))
        val r = ValhallaRouteClient.parseRoutes(antwort(tempo, listOf(p0, p1, p2), listOf(p3, p4, 48.0 to 14.05))).first()
        assertEquals(6, r.points.size)
        assertEquals(listOf(TempoAbschnitt(0, 2, 50), TempoAbschnitt(3, 5, 30)), r.tempolimits)
        assertNull("zwischen den Beinen nichts belegt", Tempolimits.fuer(r.tempolimits, 2))
    }

    @Test
    fun ohneFeldOderMitNullKeineWerte() {
        val a = ValhallaRouteClient.parseRoutes(antwort(null, listOf(p0, p1, p2))).first()
        assertTrue(a.tempolimits.isEmpty())
        val b = ValhallaRouteClient.parseRoutes(antwort(JSONObject.NULL, listOf(p0, p1, p2))).first()
        assertTrue(b.tempolimits.isEmpty())
        val c = ValhallaRouteClient.parseRoutes(antwort(JSONArray().put(JSONArray()), listOf(p0, p1, p2))).first()
        assertTrue("belegte Liste ohne Eintrag = nichts belegt", c.tempolimits.isEmpty())
        assertNull(Tempolimits.fuer(c.tempolimits, 0))
        assertNull(Tempolimits.fuer(emptyList(), 0))
    }

    @Test
    fun unsinnWirdUebergangenNichtErfunden() {
        val bein = JSONArray()
            .put(JSONArray().put(0).put(1).put(4))          // unter 5
            .put(JSONArray().put(0).put(1).put(201))        // über 200
            .put(JSONArray().put(2).put(2).put(50))         // leer (bis ≤ von)
            .put(JSONArray().put(-1).put(1).put(50))        // negativ
            .put(JSONArray().put(0).put(1))                 // zu kurz
            .put("x")
            .put(JSONArray().put(1).put(2).put(130))        // gültig
        assertEquals(listOf(TempoAbschnitt(11, 12, 130)), Tempolimits.ausBein(bein, 10))
        assertTrue(Tempolimits.ausBein(null, 0).isEmpty())
    }

    @Test
    fun grenzenEinerTeilstrecke() {
        val a = listOf(TempoAbschnitt(2, 4, 80))
        assertNull(Tempolimits.fuer(a, 1))
        assertEquals("von zählt", 80, Tempolimits.fuer(a, 2))
        assertEquals(80, Tempolimits.fuer(a, 3))
        assertNull("bis zählt nicht", Tempolimits.fuer(a, 4))
        assertNull(Tempolimits.fuer(a, -1))
    }

    // ---- Führung -> Melder (rein)

    @Test
    fun melderSendetBeiAenderungUndNachFuenfSekundenUndLoeschtEinmal() {
        val e = TempolimitMelder::entscheide
        assertEquals(TempolimitMelder.Aktion.SENDEN, e(50, null, 0, 1000))
        assertEquals(TempolimitMelder.Aktion.NICHTS, e(50, 50, 1000, 2000))
        assertEquals("anderer Wert sofort", TempolimitMelder.Aktion.SENDEN, e(70, 50, 1000, 1500))
        assertEquals("erneut vor dem Verfall im Kern", TempolimitMelder.Aktion.SENDEN, e(50, 50, 1000, 1000 + TempolimitMelder.NEU_NACH_MS))
        assertTrue("häufiger als die Frische des Kerns", TempolimitMelder.NEU_NACH_MS < TempolimitStand.FRISCH_MS)
        assertEquals(TempolimitMelder.Aktion.LOESCHEN, e(null, 50, 1000, 2000))
        assertEquals("ohne Wert und ohne Vorgänger nichts", TempolimitMelder.Aktion.NICHTS, e(null, null, 0, 5000))
        assertEquals("""{"kmh":null}""", TempolimitMelder.koerper(null))
        assertEquals("""{"kmh":50}""", TempolimitMelder.koerper(50))
    }

    // ---- Melder -> Kern -> TempolimitStand

    @Test
    fun kernNimmtNurBelegteZahlenAnUndHaeltSieFrisch() {
        val jetzt = 100_000L
        assertTrue(NaviApiHandlerTempolimitBruecke.melden("""{"kmh":50}""", jetzt).getBoolean("ok"))
        val j = TempolimitStand.json(jetzt + 1000)!!
        assertEquals(50, j.getInt("kmh"))
        assertEquals("osm", j.getString("quelle"))
        assertNull("zu alt", TempolimitStand.json(jetzt + TempolimitStand.FRISCH_MS + 1))
        // „leer“ löscht
        assertTrue(NaviApiHandlerTempolimitBruecke.melden("""{"kmh":null}""", jetzt + 2000).getBoolean("ok"))
        assertNull(TempolimitStand.json(jetzt + 2000))
        // ungültig: keine Änderung am Stand, 400
        NaviApiHandlerTempolimitBruecke.melden("""{"kmh":60}""", jetzt)
        for (schlecht in listOf("""{"kmh":4}""", """{"kmh":201}""", """{"kmh":"50"}""", """{"kmh":50.5}""", """{"kmh":true}""", "kaputt")) {
            val r = NaviApiHandlerTempolimitBruecke.melden(schlecht, jetzt)
            assertFalse(schlecht, r.getBoolean("ok"))
            assertEquals(schlecht, 60, TempolimitStand.json(jetzt)!!.getInt("kmh"))
        }
        // ohne Feld = ohne Wert
        NaviApiHandlerTempolimitBruecke.melden("{}", jetzt)
        assertNull(TempolimitStand.json(jetzt))
    }

    // ---- Verdrahtung (Quellvertrag)

    @Test
    fun verdrahtungUndHinweise() {
        val engine = quelle("java/at/greenchili/surfmate/navmap/nav/NavGuidanceEngine.kt")
        assertTrue(engine.contains("fun tempolimitKmh(): Int? {"))
        assertTrue("an der zuletzt zugeordneten Teilstrecke", engine.contains("Tempolimits.fuer(r.tempolimits, matchedSegIndex)"))
        val akt = quelle("java/at/greenchili/surfmate/navmap/RoadSenseMapActivity.kt")
        assertTrue("nur auf der Route, nie abseits oder angekommen",
            akt.contains("if (state.offRoute || state.arrived) null else guidance.tempolimitKmh()"))
        assertEquals("beenden bei Ende und beim Schließen", 2, Regex("TempolimitMelder\\.beenden\\(\\)").findAll(akt).count())
        val api = quelle("java/at/greenchili/surfmate/server/NaviApiHandler.java")
        assertTrue(api.contains("path.equals(\"/api/navi/tempolimit\")"))
        assertTrue("nur vom Auto selbst", api.substring(api.indexOf("/api/navi/tempolimit")).take(700).contains("isExternRequest()"))
        val melder = quelle("java/at/greenchili/surfmate/navmap/nav/TempolimitMelder.kt")
        assertTrue(melder.contains("127.0.0.1:8180/api/navi/tempolimit"))
        assertFalse("keine Koordinate im Körper", melder.contains("lat") && melder.contains("put(\"lat\""))
        // ODbL-Nennung in den Hinweisen
        val hinweise = (listOf(File("THIRD_PARTY_NOTICES.md"), File("../THIRD_PARTY_NOTICES.md")).first { it.isFile }).readText()
        assertTrue(hinweise.contains("Speed limits") && hinweise.contains("ODbL"))
    }
}
