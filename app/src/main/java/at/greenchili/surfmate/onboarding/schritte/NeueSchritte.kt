package at.greenchili.surfmate.onboarding.schritte

import android.view.View
import at.greenchili.surfmate.R
import org.json.JSONObject

// ================================================================ 0.1.38: neue Funktionen aus 0.1.37 (alle freiwillig)

/**
 * Hinweis-Schritte für Display, Launcher, Tacho, Tiermodus und Assistenz: ein Satz, was es gibt, und – wo es eine Seite
 * gibt – ein großer Knopf dorthin. Nichts wird eingeschaltet; „Später“ geht immer (REGEL 50+). Texte aus dem
 * Web-Katalog ({@code einr.neu.*}), damit Display und „Noch einrichten“ im Web dasselbe sagen.
 * Ob „Tacho“ überhaupt erscheint, entscheidet der Kern ({@code EinrichtungApiHandler.sicht}: Single-OS → entfällt).
 */
class NeuHinweisSchritt(id: String, a: Assistent, private val host: AssistentHost) : SchrittAnsicht(id, a) {
    override fun ansicht(): View = spalte().apply {
        val sid = this@NeuHinweisSchritt.id   // im apply wäre „id“ die View-ID
        dazu(titel(t.web("einr.schritt.$sid")), 0f)
        dazu(a.text(t.web("einr.neu.${sid}_text"), 18f))
        ZIEL[sid]?.let { ziel ->
            dazu(a.knopf(t.res(R.string.einr_jetzt_einrichten)) { host.seiteOeffnen(ziel) }, 18f)
        }
    }

    companion object {
        /** Wie ZIEL in web/shared/einrichtung-web.js. Assistenz hat keine eigene Seite (nur Hinweis). */
        val ZIEL = mapOf(
            "extra_display" to "/display.html#hintergrund",
            "extra_launcher" to "/display.html#launcher",
            "extra_tacho" to "/display.html#tacho",
            "extra_tiermodus" to "/tiermodus.html",
        )
    }
}

/**
 * Ja/Nein-Fragen zu Schaltern, die es schon gibt (Katalog): Abschaltbericht per Telegram und „Ladesäule erkennen“.
 * Wie [GewohnheitenSchritt]: erst mit „Weiter“ gespeichert, über dieselbe Prüfung wie {@code PUT /api/einstellungen}.
 * „Ladesäule erkennen“ fragt den eigenen Planer mit grober Position → keine Vorauswahl (Zustimmung ausdrücklich).
 */
class NeuJaNeinSchritt(id: String, a: Assistent) : SchrittAnsicht(id, a) {
    private val wahl = Wahl(a, nebeneinander = true) { a.geaendert() }
    private val schluessel = if (id == "extra_abschaltbericht") "telegram.abschaltBericht" else "laden.saeuleNachschlagen"

    override fun ansicht(): View = spalte().apply {
        val sid = this@NeuJaNeinSchritt.id   // im apply wäre „id“ die View-ID
        dazu(titel(t.web("einr.schritt.$sid")), 0f)
        dazu(a.text(t.web("einr.neu.${sid}_text"), 18f))
        wahl.option("ja", t.web("einr.neu.ja", "Ja"), null)
        wahl.option("nein", t.web("einr.neu.nein", "Nein"), null)
        dazu(wahl.ansicht, 16f)
        if (sid == "extra_abschaltbericht") wahl.waehle("ja")
    }

    override fun weiterMoeglich(): Boolean = wahl.wert != null
    override fun antwort(): JSONObject = JSONObject()

    override fun weiter(fertig: (Boolean, String?) -> Unit) {
        if (!a.stand().vomKern) { fertig(false, t.res(R.string.einr_fehler_verbindung)); return }
        val an = wahl.wert == "ja"
        a.imHintergrund({
            EinrichtungApi.einstellungSetzen(JSONObject().put(schluessel, an), emptyList())
        }) { r ->
            if (r == null || !r.ok || r.json.optBoolean("ok", true) == false) { fertig(false, t.res(R.string.einr_fehler_speichern)); return@imHintergrund }
            a.speichereAntwort(id, JSONObject().put("an", an), fertig)
        }
    }
}
