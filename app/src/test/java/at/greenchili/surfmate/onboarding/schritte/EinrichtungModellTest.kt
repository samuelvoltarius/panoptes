package at.greenchili.surfmate.onboarding.schritte

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EinrichtungModellTest {

    private fun stand(json: String) = EinrichtungStand.ausJson(JSONObject(json))

    private val beispiel = """
        {"version":1,"abgeschlossen":false,"aktuell":"waechter",
         "schritte":[
           {"id":"sprache","pflicht":true,"status":"erledigt"},
           {"id":"haftung","pflicht":true,"status":"erledigt"},
           {"id":"kameras","pflicht":false,"status":"entfaellt","grund":"keine_kameras"},
           {"id":"waechter","pflicht":true,"status":"offen"},
           {"id":"karte","pflicht":true,"status":"offen"},
           {"id":"extra_telegram","pflicht":false,"status":"spaeter"},
           {"id":"fertig","pflicht":false,"status":"offen"}],
         "offen_spaeter":["extra_fernzugang"],"faehigkeiten":{"kameras":{"anzahl":0}},"selbsttest":null}
    """

    @Test fun liestParagraph0() {
        val s = stand(beispiel)
        assertTrue(s.vomKern)
        assertEquals("waechter", s.aktuell)
        assertEquals(7, s.schritte.size)
        assertEquals("keine_kameras", s.schritt("kameras")?.grund)
        assertNull(s.selbsttest)
        assertEquals(0, s.faehigkeiten?.optJSONObject("kameras")?.optInt("anzahl"))
    }

    @Test fun fortschrittOhneEntfalleneUndOhneFertig() {
        val s = stand(beispiel)
        // sichtbar & zählend: sprache, haftung, waechter, karte, extra_telegram = 5
        assertEquals(Pair(3, 5), s.fortschritt("waechter"))
        assertNull(s.fortschritt("kameras"))   // entfällt → keine Nummer
        assertNull(s.fortschritt("fertig"))
    }

    @Test fun zurueckUndWeiterUeberspringenEntfallene() {
        val s = stand(beispiel)
        assertEquals("haftung", s.vorheriger("waechter"))   // „kameras“ entfällt
        assertEquals("karte", s.naechster("waechter"))
        assertNull(s.vorheriger("sprache"))
        assertNull(s.naechster("fertig"))
    }

    @Test fun startAmAktuellenSonstAmErstenOffenen() {
        assertEquals("waechter", stand(beispiel).startSchritt())
        val ohneAktuell = stand(beispiel.replace("\"aktuell\":\"waechter\"", "\"aktuell\":null"))
        assertEquals("waechter", ohneAktuell.startSchritt())
        val aufEntfallenem = stand(beispiel.replace("\"aktuell\":\"waechter\"", "\"aktuell\":\"kameras\""))
        assertEquals("waechter", aufEntfallenem.startSchritt())
    }

    @Test fun spaeterNurOhnePflicht() {
        val s = stand(beispiel)
        assertFalse(s.spaeterErlaubt("waechter"))
        assertTrue(s.spaeterErlaubt("extra_telegram"))
    }

    @Test fun nochOffenOhneDoppelteUndOhneFertig() {
        assertEquals(listOf("extra_fernzugang", "waechter", "karte", "extra_telegram"), stand(beispiel).nochOffen())
    }

    @Test fun vorlaufOhneDaemon() {
        val v = EinrichtungStand.vorlauf(setOf("sprache"))
        assertFalse(v.vomKern)
        assertEquals(Einrichtung.VORLAUF, v.schritte.map { it.id })
        assertEquals("haftung", v.startSchritt())
        assertEquals(Pair(1, 7), v.fortschritt("sprache"))
    }

    @Test fun pflichtAusListeWennKernNichtsSagt() {
        val s = stand("""{"schritte":[{"id":"waechter","status":"offen"},{"id":"daten","status":"offen"}]}""")
        assertTrue(s.schritt("waechter")!!.pflicht)
        assertFalse(s.schritt("daten")!!.pflicht)
    }

    @Test fun antwortenOhneVorauswahlSindUnvollstaendig() {
        assertEquals(listOf("antwort"), Antworten.fehlt("waechter", null))
        assertEquals(listOf("modus", "ton", "ereignisClip"), Antworten.fehlt("waechter", JSONObject()))
        assertNull(Antworten.fehlt("waechter", JSONObject("""{"modus":"aus","ton":false,"ereignisClip":false}""")))
        // 0.1.28 P10: Ereignis-Clip ohne Vorauswahl
        assertEquals(listOf("ereignisClip"), Antworten.fehlt("waechter", JSONObject("""{"modus":"aus","ton":false}""")))
        assertEquals(listOf("modus"), Antworten.fehlt("waechter", JSONObject("""{"modus":"alles","ton":true,"ereignisClip":true}""")))
        assertEquals(listOf("gelesen"), Antworten.fehlt("haftung", JSONObject("""{"gelesen":false}""")))
        assertEquals(listOf("quelle"), Antworten.fehlt("karte", JSONObject()))
        assertNull(Antworten.fehlt("betrieb", JSONObject("""{"modus":"fahrt"}""")))
        // 0.1.28 P10: bei „Immer an“ die Wahl „Wächter stoppt …“
        assertEquals(listOf("stopp"), Antworten.fehlt("betrieb", JSONObject("""{"modus":"immer"}""")))
        assertEquals(listOf("stopp"), Antworten.fehlt("betrieb", JSONObject("""{"modus":"immer","stopp":"nie"}""")))
        assertNull(Antworten.fehlt("betrieb", JSONObject("""{"modus":"immer","stopp":"normal"}""")))
    }

    private val schnell = """
        {"version":1,"abgeschlossen":false,"aktuell":"ueberlagerung",
         "schritte":[
           {"id":"sprache","pflicht":true,"status":"erledigt","schnellstart":true},
           {"id":"autostart","pflicht":false,"status":"offen","schnellstart":true},
           {"id":"ueberlagerung","pflicht":false,"status":"offen","schnellstart":true},
           {"id":"rechte","pflicht":false,"status":"erledigt","schnellstart":true},
           {"id":"waechter","pflicht":true,"status":"offen","schnellstart":true},
           {"id":"karte","pflicht":false,"status":"spaeter","schnellstart":false},
           {"id":"extra_handy","pflicht":false,"status":"offen","schnellstart":true},
           {"id":"extra_fernzugang","pflicht":false,"status":"spaeter","schnellstart":false},
           {"id":"fertig","pflicht":false,"status":"offen","schnellstart":true}],
         "offen_spaeter":["karte","extra_fernzugang"],"faehigkeiten":null,"selbsttest":null}
    """

    @Test fun schnellstartZeigtNurSeineSchritte() {
        val s = stand(schnell)
        assertEquals(listOf("sprache", "autostart", "waechter", "extra_handy", "fertig"), s.sichtbar.map { it.id })
        assertEquals(Pair(4, 4), s.fortschritt("extra_handy"))
        assertNull("freiwillig: keine Nummer", s.fortschritt("karte"))
        assertEquals("Sammelschritt steht für alle drei Freigaben", "autostart", s.startSchritt())
        assertEquals("waechter", s.naechster("autostart"))
        assertNull("aus der Liste: kein Nachfolger", s.naechster("karte"))
        assertTrue(s.spaeterErlaubt("karte"))
        assertTrue(s.nochOffen().containsAll(listOf("karte", "extra_fernzugang")))
    }

    @Test fun handyQrWieImWeb() {
        val br = JSONObject("""{"ein":true,"p":"PUB","a":"ADR","qrBasis":"https://x.example/p/"}""")
        val q = HandyQr.inhalt("123456", "https://auto.example", br, "EIN")
        assertTrue(q.startsWith("https://x.example/p/#k="))
        assertFalse("kein Füllzeichen", q.endsWith("="))
        val j = JSONObject(String(java.util.Base64.getUrlDecoder().decode(q.substringAfter("#k=")), Charsets.UTF_8))
        assertEquals(2, j.getInt("v"))
        assertEquals("123456", j.getString("c"))
        assertEquals("https://auto.example", j.getJSONArray("u").getString(0))
        assertEquals("PUB", j.getJSONObject("b").getString("p"))
        assertEquals("ADR", j.getJSONObject("b").getString("a"))
        assertEquals("EIN", j.getJSONObject("b").getString("z"))
        // Brücke aus: kein b, Standard-Basis
        val aus = HandyQr.inhalt("1", null, JSONObject("""{"ein":false,"p":"P","a":"A"}"""), "EIN")
        assertTrue(aus.startsWith("https://byd.serveone.at/p/#k="))
        val ja = JSONObject(String(java.util.Base64.getUrlDecoder().decode(aus.substringAfter("#k=")), Charsets.UTF_8))
        assertFalse(ja.has("b"))
        assertFalse(ja.has("u"))
    }

    @Test fun handyZustimmungUndKopplungen() {
        assertFalse(HandyQr.zugestimmt(null))
        assertFalse(HandyQr.zugestimmt(JSONObject("""{"zustimmungVersion":2}""")))
        assertFalse("alte Fassung", HandyQr.zugestimmt(JSONObject("""{"zustimmung":{"version":1},"zustimmungVersion":2}""")))
        assertTrue(HandyQr.zugestimmt(JSONObject("""{"zustimmung":{"version":2},"zustimmungVersion":2}""")))
        assertEquals(setOf("a"), HandyQr.handyIds(JSONObject("""{"geraete":[{"id":"a","art":"handy"},{"id":"b","art":"cockpit"}]}""")))
        assertEquals(emptySet<String>(), HandyQr.handyIds(null))
    }

    @Test fun updatesEineFrage() {
        assertEquals(listOf("pruefen"), Antworten.fehlt("updates", JSONObject()))
        assertNull(Antworten.fehlt("updates", JSONObject("""{"pruefen":false}""")))
        assertNull(Antworten.fehlt("updates", JSONObject("""{"pruefen":true}""")))
    }
}
