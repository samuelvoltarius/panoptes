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
        assertEquals(Pair(1, 6), v.fortschritt("sprache"))
    }

    @Test fun pflichtAusListeWennKernNichtsSagt() {
        val s = stand("""{"schritte":[{"id":"karte","status":"offen"},{"id":"daten","status":"offen"}]}""")
        assertTrue(s.schritt("karte")!!.pflicht)
        assertFalse(s.schritt("daten")!!.pflicht)
    }

    @Test fun antwortenOhneVorauswahlSindUnvollstaendig() {
        assertEquals(listOf("antwort"), Antworten.fehlt("waechter", null))
        assertEquals(listOf("modus", "ton"), Antworten.fehlt("waechter", JSONObject()))
        assertNull(Antworten.fehlt("waechter", JSONObject("""{"modus":"aus","ton":false}""")))
        assertEquals(listOf("modus"), Antworten.fehlt("waechter", JSONObject("""{"modus":"alles","ton":true}""")))
        assertEquals(listOf("gelesen"), Antworten.fehlt("haftung", JSONObject("""{"gelesen":false}""")))
        assertEquals(listOf("quelle"), Antworten.fehlt("karte", JSONObject()))
        assertNull(Antworten.fehlt("betrieb", JSONObject("""{"modus":"fahrt"}""")))
    }

    @Test fun updatesMobilfunkNurMitSim() {
        assertEquals(listOf("pruefen"), Antworten.fehlt("updates", JSONObject()))
        assertNull(Antworten.fehlt("updates", JSONObject("""{"pruefen":false}""")))
        assertEquals(listOf("mobilfunk", "modus"), Antworten.fehlt("updates", JSONObject("""{"pruefen":true}""")))
        assertEquals(listOf("modus"), Antworten.fehlt("updates", JSONObject("""{"pruefen":true}"""), mitSim = false))
        assertNull(Antworten.fehlt("updates", JSONObject("""{"pruefen":true,"mobilfunk":false,"modus":"melden"}""")))
    }
}
