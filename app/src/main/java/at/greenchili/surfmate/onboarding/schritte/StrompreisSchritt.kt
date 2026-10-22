package at.greenchili.surfmate.onboarding.schritte

import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import at.greenchili.surfmate.R
import org.json.JSONObject

/**
 * §0-Schritt `strompreis` (Kern 4, 22.10.): Strompreis zu Hause je kWh für die Fahrtkosten. Nicht Pflicht.
 * Antwort `{acRate:Zahl, waehrung?}` oder `{gratis:true}` – ohne Vorauswahl. Ungültig/fehlend → 422 `fehlt:["acRate"]`.
 */
class StrompreisSchritt(a: Assistent) : SchrittAnsicht("strompreis", a) {
    private val wahl = Wahl(a, nebeneinander = true) { onWahl() }
    private var feld: EditText? = null
    private var feldBlock: View? = null

    override fun ansicht(): View = spalte().apply {
        dazu(titel(t.res(R.string.einr_strompreis_titel)), 0f)
        dazu(a.text(t.res(R.string.einr_strompreis_text), 17f, leise = true))
        wahl.option("zahle", t.res(R.string.einr_strompreis_zahle), t.res(R.string.einr_strompreis_zahle_satz))
        wahl.option("gratis", t.res(R.string.einr_strompreis_gratis), t.res(R.string.einr_strompreis_gratis_satz))
        dazu(wahl.ansicht, 16f)
        val e = EditText(a.activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = t.res(R.string.einr_strompreis_feld)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, a.schrift(20f))
            minHeight = a.knopfHoehe
            visibility = View.GONE
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { a.geaendert() }
            })
        }
        feld = e; feldBlock = e
        dazu(e, 12f)
    }

    private fun onWahl() {
        feldBlock?.visibility = if (wahl.wert == "zahle") View.VISIBLE else View.GONE
        if (wahl.wert == "zahle") feld?.requestFocus()
        a.geaendert()
    }

    /** Eingabe mit Komma oder Punkt; nur Werte > 0 und plausibel klein (< 5 je kWh). */
    private fun preis(): Double? {
        val s = feld?.text?.toString()?.trim()?.replace(',', '.') ?: return null
        val v = s.toDoubleOrNull() ?: return null
        return if (v > 0.0 && v < 5.0) v else null
    }

    override fun weiterMoeglich(): Boolean = when (wahl.wert) {
        "gratis" -> true
        "zahle" -> preis() != null
        else -> false
    }

    override fun antwort(): JSONObject? = when (wahl.wert) {
        "gratis" -> JSONObject().put("gratis", true)
        "zahle" -> preis()?.let { JSONObject().put("acRate", it).put("waehrung", "€") }
        else -> null
    }

    override fun weiter(fertig: (Boolean, String?) -> Unit) {
        val ant = antwort() ?: return fertig(false, t.res(R.string.einr_strompreis_ungueltig))
        a.speichereAntwort(id, ant, fertig)
    }
}
