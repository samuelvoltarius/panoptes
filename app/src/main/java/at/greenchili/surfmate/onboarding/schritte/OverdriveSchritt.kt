package at.greenchili.surfmate.onboarding.schritte

import android.util.TypedValue
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import at.greenchili.surfmate.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wizard-Schritt „Von Overdrive übernehmen“ (§0-ID extra_overdrive, Kern 5 / LADEN_VERTRAG Teil B).
 *
 *   GET  /api/uebernahme/overdrive → {vorhanden, posten:[{id,bereich,titel,aktion,overdrive,panoptes,hinweis}], vorschau_id}
 *   POST /api/uebernahme/overdrive {vorschau_id, posten:[ids]} → {ok, uebernommen[], fehler{id:grund}} · 409 = Vorschau neu laden
 *
 * Kein Posten ist vorausgewählt. „manuell“ (Einwilligungen, Gefahr) ist nie wählbar, nur Hinweis.
 * Ohne Overdrive-Daten setzt der Kern den Schritt auf „entfaellt“; zeigt der Kern ihn trotzdem, erscheint ein Satz.
 */
class OverdriveSchritt(a: Assistent) : SchrittAnsicht("extra_overdrive", a) {
    private lateinit var liste: LinearLayout
    private val haken = LinkedHashMap<String, CheckBox>()
    private var vorschauId: String? = null
    private var uebernommen = false

    override fun ansicht(): View = spalte().apply {
        dazu(titel(t.res(R.string.einr_od_titel)), 0f)
        dazu(a.text(t.res(R.string.einr_od_text), 17f, leise = true))
        liste = spalte()
        dazu(liste)
    }

    override fun betreten() { laden() }

    private fun laden() {
        liste.removeAllViews(); haken.clear(); vorschauId = null
        liste.dazu(a.text(t.res(R.string.einr_od_lade), 17f, leise = true))
        a.imHintergrund({ EinrichtungApi.get("/api/uebernahme/overdrive") }) { r ->
            liste.removeAllViews()
            val j = r?.json
            if (j == null || !j.optBoolean("vorhanden", false)) { liste.dazu(a.text(t.res(R.string.einr_od_nichts), 17f, leise = true)); a.geaendert(); return@imHintergrund }
            vorschauId = j.optString("vorschau_id", "").ifEmpty { null }
            j.optString("overdriveVersion", "").takeIf { it.isNotEmpty() }?.let { liste.dazu(a.text(t.res(R.string.einr_od_version, it), 16f, leise = true), 0f) }
            val posten = j.optJSONArray("posten") ?: JSONArray()
            for (i in 0 until posten.length()) {
                val p = posten.optJSONObject(i) ?: continue
                zeile(p)
            }
            a.geaendert()
        }
    }

    private fun zeile(p: JSONObject) {
        val id = p.optString("id")
        val aktion = p.optString("aktion")
        val titel = p.optString("titel", "").ifEmpty { t.web(p.optString("titelKey", "").ifEmpty { "uebernahme.posten.$id" }, id) }
        val aktionText = when (aktion) {
            "neu" -> t.res(R.string.einr_od_neu)
            "ueberschreiben" -> t.res(R.string.einr_od_ueberschreiben)
            "gleich" -> t.res(R.string.einr_od_gleich)
            "neu_eingeben" -> t.res(R.string.einr_od_neu_eingeben)
            else -> t.res(R.string.einr_od_manuell)
        }
        val werte = "Overdrive: ${p.optString("overdrive", "–")} → Panoptes: ${p.optString("panoptes", "–")}"
        if (aktion == "manuell" || aktion == "gleich" || aktion == "neu_eingeben") {
            // nicht wählbar: nur zeigen (manuell mit Hinweis „bitte selbst einstellen“)
            liste.dazu(a.text("$titel – $aktionText", 18f, fett = true).apply { if (aktion != "gleich") setTextColor(a.farbeWarnung) }, 12f)
            liste.dazu(a.text(werte + (p.optString("hinweis", "").let { if (it.isEmpty()) "" else "\n$it" }), 16f, leise = true), 2f)
            return
        }
        val cb = CheckBox(a.activity).apply {
            text = "$titel – $aktionText"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, a.schrift(18f))
            setTextColor(a.farbeText)
            minHeight = a.knopfHoehe
            isChecked = false                       // keine Vorauswahl
            a.grosserHaken(this)
            setOnCheckedChangeListener { _, _ -> a.geaendert() }
        }
        haken[id] = cb
        liste.dazu(cb, 10f)
        liste.dazu(a.text(werte, 16f, leise = true), 0f)
    }

    private fun gewaehlt(): List<String> = haken.filter { it.value.isChecked }.keys.toList()

    override fun weiterText(): String? = if (!uebernommen && gewaehlt().isNotEmpty()) t.res(R.string.einr_od_uebernehmen) else null

    override fun weiter(fertig: (Boolean, String?) -> Unit) {
        val ids = gewaehlt()
        if (uebernommen || ids.isEmpty() || vorschauId == null) { a.speichereAntwort(id, JSONObject(), fertig); return }
        a.imHintergrund({
            EinrichtungApi.transport("POST", "/api/uebernahme/overdrive",
                JSONObject().put("vorschau_id", vorschauId).put("posten", JSONArray(ids)))
        }) { r ->
            when {
                r == null || r.status == 0 -> fertig(false, t.res(R.string.einr_fehler_verbindung))
                r.status == 409 -> { laden(); fertig(false, t.res(R.string.einr_od_veraltet)) }
                r.ok -> {
                    uebernommen = true
                    val n = r.json.optJSONArray("uebernommen")?.length() ?: 0
                    val neustart = r.json.optBoolean("neustartEmpfohlen", false)
                    a.zeigeMeldung(t.res(R.string.einr_od_ok, n) + if (neustart) " " + t.res(R.string.einr_od_neustart) else "")
                    laden()   // Rückgelesener Stand (Rückleseregel)
                    fertig(false, t.res(R.string.einr_od_ok, n) + if (neustart) " " + t.res(R.string.einr_od_neustart) else "")   // auf dem Schritt bleiben
                }
                else -> fertig(false, t.res(R.string.einr_fehler_speichern))
            }
        }
    }
}
