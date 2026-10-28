package at.greenchili.surfmate.onboarding.schritte

import android.view.View
import at.greenchili.surfmate.R
import org.json.JSONObject

// ================================================================ 0.1.22 Gewohnheiten (GEWOHNHEITEN_KONZEPT §2)

/**
 * „Gewohnheiten merken?“ – Regel 2: klare Erklärung, zwei große Knöpfe, „Ja“ vorgewählt (Standard an laut Konzept),
 * gespeichert wird erst mit „Weiter“. Bestand bekommt den Schritt als „Noch offen“ (kein stilles Einschalten, F6).
 */
class GewohnheitenSchritt(a: Assistent) : SchrittAnsicht("extra_gewohnheiten", a) {
    private val wahl = Wahl(a, nebeneinander = true) { a.geaendert() }

    override fun ansicht(): View = spalte().apply {
        dazu(titel(t.res(R.string.einr_gewohnheiten_titel)), 0f)
        dazu(a.text(t.res(R.string.einr_gewohnheiten_text), 18f))
        wahl.option("ja", t.res(R.string.einr_gewohnheiten_ja), null)
        wahl.option("nein", t.res(R.string.einr_gewohnheiten_nein), null)
        dazu(wahl.ansicht, 16f)
        wahl.waehle("ja")
    }

    override fun weiterMoeglich(): Boolean = wahl.wert != null
    override fun antwort(): JSONObject = JSONObject()

    override fun weiter(fertig: (Boolean, String?) -> Unit) {
        if (!a.stand().vomKern) { fertig(false, t.res(R.string.einr_fehler_verbindung)); return }
        val an = wahl.wert == "ja"
        a.imHintergrund({
            EinrichtungApi.einstellungSetzen(JSONObject().put("gewohnheiten.an", an), emptyList())
        }) { r ->
            if (r == null || !r.ok || r.json.optBoolean("ok", true) == false) { fertig(false, t.res(R.string.einr_fehler_speichern)); return@imHintergrund }
            a.speichereAntwort(id, JSONObject().put("an", an), fertig)
        }
    }
}
