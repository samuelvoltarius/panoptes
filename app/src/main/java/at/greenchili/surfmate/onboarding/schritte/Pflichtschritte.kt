package at.greenchili.surfmate.onboarding.schritte

import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.util.TypedValue
import at.greenchili.surfmate.R
import at.greenchili.surfmate.ui.dialog.LanguagePickerDialog
import at.greenchili.surfmate.server.LocaleManager
import org.json.JSONObject

/*
 * Wahl-Schritte (WIZARD_AUFTRAG_NEUBAU Teil C, SETUP_WIZARD §2):
 *   Schnellstart: sprache · haftung · waechter. „Noch einrichten“: betrieb · karte · updates.
 * Keine Vorauswahl bei Einwilligungen/Pflichtwahlen (Ausnahme Sprache). Rechtstexte wörtlich aus dem Web-Katalog.
 */

/** Hilfsbaustein: Gruppe von Wahlkarten ohne Vorauswahl. */
class Wahl(private val a: Assistent, private val nebeneinander: Boolean = false, private val geaendert: (String) -> Unit) {
    val ansicht = LinearLayout(a.activity).apply { orientation = if (nebeneinander) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
    private val karten = LinkedHashMap<String, View>()
    var wert: String? = null; private set

    fun option(schluessel: String, titel: CharSequence, satz: CharSequence?, gesperrt: CharSequence? = null) {
        val k = a.wahlKarte(titel, if (gesperrt != null) "$satz\n$gesperrt".trim() else satz) { if (gesperrt == null) waehle(schluessel) }
        if (gesperrt != null) k.alpha = 0.45f
        karten[schluessel] = k
        val lp = if (nebeneinander) LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { if (karten.size > 1) leftMargin = a.px(16f) }
                 else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { if (karten.size > 1) topMargin = a.px(12f) }
        ansicht.addView(k, lp)
    }

    fun waehle(schluessel: String) {
        wert = schluessel
        karten.forEach { (s, v) -> a.markiere(v, s == schluessel) }
        geaendert(schluessel)
    }
}

// ---------------------------------------------------------------- 0 Sprache (Vorauswahl erlaubt)
class SpracheSchritt(a: Assistent) : SchrittAnsicht("sprache", a) {
    // 0.1.38.3: beim „Noch einmal durchgehen“ überspringbar
    override val spaeterZeigen: Boolean get() = a.stand().wiederholung
    private val wahl = Wahl(a) { a.geaendert() }
    private val namen = linkedMapOf("de" to "Deutsch", "en" to "English", "fr" to "Français", "it" to "Italiano", "nl" to "Nederlands")

    override fun ansicht(): View = spalte().apply {
        dazu(titel(t.res(R.string.einr_sprache_titel)), 0f)
        dazu(a.text(t.res(R.string.einr_sprache_text), leise = true))
        namen.forEach { (tag, name) -> wahl.option(tag, name, null) }
        dazu(wahl.ansicht, 20f)
        // Standard = aktuelle bzw. Systemsprache, sonst Deutsch (neutrale Wahl, Vorauswahl erlaubt)
        val jetzt = try { LocaleManager.get() } catch (_: Throwable) { t.sprache() }
        wahl.waehle(if (jetzt in namen) jetzt else "de")
    }

    override fun antwort(): JSONObject? = wahl.wert?.let { JSONObject().put("locale", it) }

    override fun weiter(fertig: (Boolean, String?) -> Unit) {
        val tag = wahl.wert ?: return fertig(false, t.res(R.string.einr_fehler_pflicht))
        a.speichereAntwort(id, antwort()!!) { ok, text ->
            if (ok && tag != (try { LocaleManager.get() } catch (_: Throwable) { "" })) {
                // Activity wird neu erstellt; der Assistent setzt danach am nächsten offenen Schritt fort.
                LanguagePickerDialog.applySelection(a.activity, tag)
            }
            fertig(ok, text)
        }
    }
}

// ---------------------------------------------------------------- 1 Haftung (Häkchen ohne Vorauswahl)
class HaftungSchritt(a: Assistent) : SchrittAnsicht("haftung", a) {
    // 0.1.38.3: beim „Noch einmal durchgehen“ überspringbar (bestätigt war sie schon)
    override val spaeterZeigen: Boolean get() = a.stand().wiederholung
    private var box: CheckBox? = null
    private var mehr: View? = null

    override fun ansicht(): View = spalte().apply {
        dazu(titel(t.web("disclaimer.setup.title", "Bevor es losgeht")), 0f)
        dazu(a.text(t.web("disclaimer.setup.body")))
        val alle = spalte().apply { visibility = View.GONE }
        for (i in 1..8) t.web("disclaimer.full.p$i").takeIf { it.isNotEmpty() }?.let { alle.dazu(a.text(it, 16f, leise = true), 8f) }
        mehr = alle
        dazu(a.knopf(t.web("disclaimer.setup.link", "Alle Hinweise lesen")) { alle.visibility = if (alle.visibility == View.VISIBLE) View.GONE else View.VISIBLE })
        dazu(alle)
        box = CheckBox(a.activity).apply {
            text = t.web("disclaimer.setup.checkbox", "Gelesen und einverstanden")
            setTextSize(TypedValue.COMPLEX_UNIT_PX, a.schrift(19f))
            setTextColor(a.farbeText)
            minHeight = a.knopfHoehe
            isChecked = false                              // nie vorausgewählt
            a.grosserHaken(this)
            setOnCheckedChangeListener { _, _ -> a.geaendert() }
        }
        dazu(box!!, 20f)
    }

    override fun antwort(): JSONObject? = if (box?.isChecked == true)
        JSONObject().put("gelesen", true).put("version", t.web("disclaimer.version", "")).put("zeit", System.currentTimeMillis())
    else null
}

// ------------------------------------------------- 8 Wächter (0.1.38.8: Standard AN, ein Tipp „Verstanden“, KI optional)
/**
 * 0.1.38.8 „Wächter Standard an“ (REGEL NR.2 hier bewusst von Projektinhaber aufgehoben): Der Wächter ist vorbelegt AN mit dem
 * datensparsamen Standard „nur Erschütterung“ (shock); Kameras richtet Panoptes automatisch ein. Der Rechtshinweis
 * (guardLegal) steht auf demselben Bildschirm; ein Tipp „Verstanden“ bestätigt ihn und schaltet ein. Darunter ein
 * einzelner Schalter für die KI-Stufe (ai_private), nicht vorausgewählt. „Wächter aus“ lässt ihn aus (bleibt aus).
 * Ohne Tipp bleibt der Wächter aus (der Rechtshinweis ist Pflicht). Keine weiteren Schritte (Ton aus, Ereignis-Clip
 * nach Landes-Standard).
 */
class WaechterSchritt(a: Assistent) : SchrittAnsicht("waechter", a) {
    override val weiterZeigen = false
    // 0.1.38.3: beim „Noch einmal durchgehen“ überspringbar (der Kern lässt „Später“ nur dann zu)
    override val spaeterZeigen: Boolean get() = a.stand().wiederholung

    private var ki = false
    private var kiKarte: View? = null

    override fun ansicht(): View = spalte().apply {
        dazu(titel(t.web("guardLegal.standard.titel", "Der Wächter passt auf dein Auto auf")), 0f)
        dazu(a.text(t.web("guardLegal.standard.intro",
            "Er ist eingeschaltet und nimmt nur bei Erschütterung auf – überall unbedenklich. Die Kameras richtet Panoptes automatisch ein."), 17f))
        dazu(a.text(t.web("disclaimer.guard.body"), 16f, leise = true))
        // KI-Stufe: ein Tipp, nicht vorausgewählt (ai_private)
        val karte = a.wahlKarte(
            t.web("guardLegal.standard.ki_label", "Auch Personen am Auto erkennen (KI) – empfohlen"),
            t.web("guardLegal.standard.ki_sub",
                "Erkennt Annäherung nur an deinen privaten Orten (z. B. Garage, Einfahrt); sonst nur bei Erschütterung. Bitte den Rechtshinweis beachten.")
        ) { ki = !ki; kiKarte?.let { a.markiere(it, ki) }; a.geaendert() }
        kiKarte = karte
        dazu(karte, 18f)
        dazu(a.text(t.web("guardLegal.standard.zwoelfvolt",
            "Der Wächter schaltet sich bei schwacher 12-V-Batterie von selbst ab."), 15f, leise = true))
        // Verstanden (primär, schaltet ein) + Wächter aus
        val zeile = LinearLayout(a.activity).apply { orientation = LinearLayout.HORIZONTAL }
        zeile.addView(a.knopf(t.web("guardLegal.standard.verstanden", "Verstanden – Wächter an"), primaer = true) { antworte(if (ki) "ai_private" else "shock") },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        zeile.addView(a.knopf(t.web("guardLegal.trigger.aus.label", "Wächter aus – nimmt nichts auf")) { antworte("aus") },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = a.px(16f) })
        dazu(zeile, 22f)
        // 0.1.38.3: beim Wiederholen die KI-Stufe vorbelegen, wenn zuvor gewählt
        a.stand().vorbelegung(this@WaechterSchritt.id)?.optString("modus")?.let { m ->
            if (m == "ai_private" || m == "ai_everywhere") { ki = true; kiKarte?.let { a.markiere(it, true) } }
        }
    }

    /** Antwort (modus + Ton aus; ohne ereignisClip, es gilt der Landes-Standard); „aus“ lässt den Wächter aus, jede andere Wahl schaltet ein und bestätigt guardLegal. */
    private fun antworte(modus: String) {
        a.speichereAntwort(id, JSONObject().put("modus", modus).put("ton", false)) { ok, text ->
            if (ok) a.weiterNach(id) else a.zeigeMeldung(text ?: t.res(R.string.einr_fehler_speichern))
        }
    }
}

// ---------------------------------------------------------------- 9 Betrieb (ohne Vorauswahl, Empfehlungstext) + Wächter-Stopp
class BetriebSchritt(a: Assistent) : SchrittAnsicht("betrieb", a) {
    private val wahl = Wahl(a, nebeneinander = true) { onWahl(it) }
    private lateinit var stopp: LinearLayout
    // 0.1.28 P10 (SETUP_WIZARD §2 Nr. 9): „Wächter stoppt …“ als eine einfache Wahl, nur bei „Immer an“
    private val stoppWahl = Wahl(a) { a.geaendert() }
    private var stoppBlock: View? = null

    override fun ansicht(): View = spalte().apply {
        dazu(titel(t.res(R.string.einr_betrieb_titel)), 0f)
        dazu(a.text(t.res(R.string.einr_betrieb_text), 17f, leise = true))
        wahl.option("immer", t.res(R.string.einr_betrieb_immer), t.res(R.string.einr_betrieb_immer_satz))
        wahl.option("fahrt", t.res(R.string.einr_betrieb_fahrt), t.res(R.string.einr_betrieb_fahrt_satz))
        dazu(wahl.ansicht, 16f)
        dazu(a.text(t.res(R.string.einr_betrieb_empfehlung), 16f).apply { setTextColor(a.farbeAkzent) })
        dazu(a.text(t.web("guard.stop_title", "Wächter stoppt, wenn …"), 20f, fett = true), 20f)
        stopp = spalte()
        dazu(stopp, 6f)
        val sb = spalte().apply { visibility = View.GONE }
        STOPP_WERTE.forEach { (k, w) ->
            stoppWahl.option(k, t.web("einrichtung_p10.stopp_$k", k),
                t.web("einrichtung_p10.stopp_satz", "Akku unter {akku} % oder nach {stunden} Stunden",
                    mapOf("akku" to w.first, "stunden" to w.second)))
        }
        sb.dazu(stoppWahl.ansicht, 8f)
        sb.dazu(a.text(t.web("einrichtung_p10.stopp_12v", "Die 12-Volt-Batterie schützt Panoptes immer zusätzlich."), 16f, leise = true))
        stoppBlock = sb
        dazu(sb)
        dazu(a.text(t.res(R.string.einr_betrieb_anpassen), 16f, leise = true))
        // 0.1.38.3: beim Wiederholen mit der bisherigen Antwort vorbelegt
        a.stand().vorbelegung(this@BetriebSchritt.id)?.let { v ->
            v.optString("modus").takeIf { it.isNotEmpty() }?.let { wahl.waehle(it) }
            v.optString("stopp").takeIf { it.isNotEmpty() }?.let { stoppWahl.waehle(it) }
        }
    }

    private fun onWahl(w: String) {
        stoppBlock?.visibility = if (w == "immer") View.VISIBLE else View.GONE
        stopp.visibility = if (w == "immer") View.GONE else View.VISIBLE   // bei „Immer an“ zeigt die Wahl die Werte
        a.geaendert()
    }

    override fun betreten() {
        if (!a.stand().vomKern) return
        // Werte aus guardStop.* (nur Anzeige, „Anpassen“ später im Wächter)
        a.imHintergrund({ EinrichtungApi.get("/api/einstellungen") }) { r ->
            val w = r?.json?.optJSONObject("werte") ?: return@imHintergrund
            fun wert(k: String): Any? = w.optJSONObject(k)?.opt("wert")?.takeIf { it != JSONObject.NULL }
            val zeilen = ArrayList<String>()
            // Zahlen in der Schreibweise der Sprache (12,2 V statt 12.2 V)
            val nf = java.text.NumberFormat.getNumberInstance(a.activity.resources.configuration.locales[0]).apply { maximumFractionDigits = 1 }
            fun zahl(v: Any) = (v as? Number)?.let { nf.format(it.toDouble()) } ?: v.toString()
            wert("guardStop.socBelow")?.let { zeilen.add(t.res(R.string.einr_stopp_akku, zahl(it))) }
            wert("guardStop.v12Below")?.let { zeilen.add(t.res(R.string.einr_stopp_12v, zahl(it))) }
            wert("guardStop.maxParkHours")?.let { zeilen.add(t.res(R.string.einr_stopp_stunden, zahl(it))) }
            stopp.removeAllViews()
            zeilen.forEach { stopp.dazu(a.text("• $it", 17f), 4f) }
        }
    }

    override fun antwort(): JSONObject? = wahl.wert?.let { m ->
        JSONObject().put("modus", m).apply { if (m == "immer") stoppWahl.wert?.let { put("stopp", it) } }
    } ?: JSONObject()

    companion object {
        /** Wie EinrichtungApiHandler.STOPP: Akku unter %, Stunden geparkt. */
        val STOPP_WERTE = listOf("frueh" to (30 to 12), "normal" to (20 to 24), "lang" to (15 to 48))
    }
}

// ---------------------------------------------------------------- 10 Karte (drei Karten, ohne Vorauswahl, „was erfährt wer“)
class KarteSchritt(a: Assistent) : SchrittAnsicht("karte", a) {
    private val wahl = Wahl(a, nebeneinander = true) { a.geaendert() }

    override fun ansicht(): View = spalte().apply {
        dazu(titel(t.web("kartenquelle.titel", "Kartenquelle")), 0f)
        dazu(a.text(t.web("kartenquelle.text"), 17f, leise = true))
        wahl.option("community", t.web("kartenquelle.q_community", "Community-Karte"), t.web("kartenquelle.q_community_sub"))
        wahl.option("osm", t.web("kartenquelle.q_osm", "OpenStreetMap"), t.web("kartenquelle.q_osm_sub"))
        wahl.option("keine", t.web("kartenquelle.q_keine", "Keine Karte"), t.web("kartenquelle.q_keine_sub"))
        dazu(wahl.ansicht, 16f)
        dazu(a.text(t.res(R.string.einr_karte_internet), 16f, leise = true))
        // 0.1.38.3: beim Wiederholen mit der bisherigen Antwort vorbelegt
        a.stand().vorbelegung(this@KarteSchritt.id)?.optString("quelle")?.takeIf { it.isNotEmpty() }?.let { wahl.waehle(it) }
    }

    override fun antwort(): JSONObject? = wahl.wert?.let { JSONObject().put("quelle", it) } ?: JSONObject()
}

// ---------------------------------------------------------------- 13 Updates (0.1.32: ein Satz, zwei Knöpfe, ohne Vorauswahl)
class UpdatesSchritt(a: Assistent) : SchrittAnsicht("updates", a) {
    // 0.1.38.3: beim „Noch einmal durchgehen“ überspringbar
    override val spaeterZeigen: Boolean get() = a.stand().wiederholung
    override val weiterZeigen = false

    override fun ansicht(): View = spalte().apply {
        dazu(titel(t.res(R.string.einr_updates_frage_pruefen)), 0f)
        dazu(a.text(t.res(R.string.einr_updates_github), 19f))
        dazu(a.text(t.res(R.string.einr_updates_mobil_kosten), 17f, leise = true))
        val zeile = LinearLayout(a.activity).apply { orientation = LinearLayout.HORIZONTAL }
        zeile.addView(a.knopf(t.res(R.string.einr_updates_ja_knopf)) { antworte(true) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        zeile.addView(a.knopf(t.res(R.string.einr_nein)) { antworte(false) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = a.px(16f) })
        dazu(zeile, 24f)
        dazu(a.text(t.res(R.string.einr_updates_pruefen_nein), 16f, leise = true), 12f)
    }

    /** Mobilfunk und Installationsart bleiben beim Standard, änderbar unter System. */
    private fun antworte(pruefen: Boolean) {
        a.speichereAntwort(id, JSONObject().put("pruefen", pruefen)) { ok, text ->
            if (ok) a.weiterNach(id) else a.zeigeMeldung(text ?: t.res(R.string.einr_fehler_speichern))
        }
    }
}
