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
 * Pflichtschritte (WIZARD_AUFTRAG_NEUBAU Teil C, SETUP_WIZARD §2):
 *   sprache · haftung · waechter · betrieb · karte · updates
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
    override val spaeterZeigen = false
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
    override val spaeterZeigen = false
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

// ---------------------------------------------------------------- 8 Wächter (a/b/c/aus ohne Vorauswahl, danach Ton)
class WaechterSchritt(a: Assistent) : SchrittAnsicht("waechter", a) {
    override val spaeterZeigen = false

    private val ton = Wahl(a) { a.geaendert() }
    private var bHinweis: View? = null
    private var tonBlock: View? = null
    private var gesperrt: Map<String, String> = emptyMap()
    private lateinit var liste: LinearLayout

    override fun ansicht(): View = spalte().apply {
        dazu(titel(t.web("guardLegal.trigger.title", "Wann soll der Wächter aufnehmen?")), 0f)
        dazu(a.text(t.web("guardLegal.trigger.intro"), 17f, leise = true))
        liste = spalte()
        dazu(liste, 16f)
        optionen()
        bHinweis = a.text(t.web("guardLegal.trigger.b.note"), 16f).apply { setTextColor(a.farbeWarnung); visibility = View.GONE }
        dazu(bHinweis!!)
        dazu(a.text(t.web("disclaimer.guard.body"), 16f, leise = true))
        // Ton (Pflichttext D, Standard aus, ohne Vorauswahl)
        val tb = spalte().apply { visibility = View.GONE }
        tb.dazu(a.text(t.web("guardLegal.audio.label", "Ton aufnehmen"), 21f, fett = true), 18f)
        tb.dazu(a.text(t.web("guardLegal.audio.body"), 16f))
        ton.option("aus", t.web("guardLegal.audio.cancel", "Ohne Ton"), null)
        ton.option("an", t.web("guardLegal.audio.confirm", "Ton einschalten"), null)
        tb.dazu(ton.ansicht)
        tonBlock = tb
        dazu(tb)
        // 0.1.28 P10: Ereignis-Clip beim Fahren – eigene Frage, ohne Vorauswahl (Schweiz: eigener Hinweis)
        dazu(a.text(t.web("settings.recording.ereignisClip.title", "Ereignisse automatisch sichern"), 21f, fett = true), 22f)
        dazu(a.text(t.web("settings.recording.ereignisClip.help"), 16f))
        chHinweis = a.text(t.web("settings.recording.ereignisClip.helpCH"), 16f).apply {
            setTextColor(a.farbeWarnung); visibility = View.GONE
        }
        dazu(chHinweis!!)
        dazu(a.text(t.web("einrichtung_p10.ereignis_handy",
            "Auf dein Handy kommt ein Clip nur, wenn du dort auf „Aufs Handy laden“ tippst."), 16f, leise = true))
        ereignis.option("ja", t.web("einrichtung_p10.ereignis_ja", "Ja, Ereignisse sichern"), null)
        ereignis.option("nein", t.web("einrichtung_p10.ereignis_nein", "Nein"), null)
        dazu(ereignis.ansicht)
    }

    private val ereignis = Wahl(a, nebeneinander = true) { a.geaendert() }
    private var chHinweis: View? = null

    private fun optionen() {
        liste.removeAllViews()
        val w = Wahl(a) { onWahl(it) }
        listOf("shock" to "a", "ai_everywhere" to "b", "ai_private" to "c").forEach { (wert, k) ->
            w.option(wert, t.web("guardLegal.trigger.$k.label"), t.web("guardLegal.trigger.$k.desc"), gesperrt[wert])
        }
        w.option("aus", t.web("guardLegal.trigger.aus.label", "Wächter aus – nimmt nichts auf"), null)
        liste.addView(w.ansicht)
        wahlAktuell = w
    }
    private var wahlAktuell: Wahl? = null

    override fun betreten() {
        // Vom Kern gesperrte Optionen (z. B. (b) in der Schweiz) aus dem Schema
        if (!a.stand().vomKern) return
        a.imHintergrund({ EinrichtungApi.get("/api/einstellungen/schema") }) { r ->
            val g = HashMap<String, String>()
            r?.json?.optJSONArray("gruppen")?.let { gr ->
                for (i in 0 until gr.length()) gr.optJSONObject(i)?.optJSONArray("felder")?.let { f ->
                    for (j in 0 until f.length()) {
                        val feld = f.optJSONObject(j) ?: continue
                        if (feld.optString("key") != "surveillance.triggerMode") continue
                        feld.optJSONArray("optionen")?.let { o -> for (x in 0 until o.length()) o.optJSONObject(x)?.let { op ->
                            if (op.optBoolean("gesperrt")) g[op.optString("wert")] = op.optString("gesperrtGrund", t.web("guardLegal.trigger.blockedCH"))
                        } }
                    }
                }
            }
            if (g.isNotEmpty()) { gesperrt = g; optionen() }
            // Schweiz: das Feld trägt den eigenen Hilfetext (Katalog hilfeKey …helpCH)
            r?.json?.optJSONArray("gruppen")?.let { gr ->
                for (i in 0 until gr.length()) gr.optJSONObject(i)?.optJSONArray("felder")?.let { f ->
                    for (j in 0 until f.length()) {
                        val feld = f.optJSONObject(j) ?: continue
                        if (feld.optString("key") == "recording.ereignisClip" && feld.optString("hilfeKey").endsWith("helpCH")) {
                            chHinweis?.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }
    }

    private fun onWahl(w: String) {
        bHinweis?.visibility = if (w == "ai_everywhere") View.VISIBLE else View.GONE
        tonBlock?.visibility = if (w == "aus") View.GONE else View.VISIBLE
        a.geaendert()
    }

    override fun antwort(): JSONObject? {
        val m = wahlAktuell?.wert ?: return JSONObject()
        val o = JSONObject().put("modus", m)
        if (m == "aus") o.put("ton", false) else ton.wert?.let { o.put("ton", it == "an") }
        ereignis.wert?.let { o.put("ereignisClip", it == "ja") }
        return o
    }
}

// ---------------------------------------------------------------- 9 Betrieb (ohne Vorauswahl, Empfehlungstext) + Wächter-Stopp
class BetriebSchritt(a: Assistent) : SchrittAnsicht("betrieb", a) {
    override val spaeterZeigen = false
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
    override val spaeterZeigen = false
    private val wahl = Wahl(a, nebeneinander = true) { a.geaendert() }

    override fun ansicht(): View = spalte().apply {
        dazu(titel(t.web("kartenquelle.titel", "Kartenquelle")), 0f)
        dazu(a.text(t.web("kartenquelle.text"), 17f, leise = true))
        wahl.option("community", t.web("kartenquelle.q_community", "Community-Karte"), t.web("kartenquelle.q_community_sub"))
        wahl.option("osm", t.web("kartenquelle.q_osm", "OpenStreetMap"), t.web("kartenquelle.q_osm_sub"))
        wahl.option("keine", t.web("kartenquelle.q_keine", "Keine Karte"), t.web("kartenquelle.q_keine_sub"))
        dazu(wahl.ansicht, 16f)
        dazu(a.text(t.res(R.string.einr_karte_internet), 16f, leise = true))
    }

    override fun antwort(): JSONObject? = wahl.wert?.let { JSONObject().put("quelle", it) } ?: JSONObject()
}

// ---------------------------------------------------------------- 13 Updates (drei Fragen, ohne Vorauswahl; Mobilfunk nur mit SIM)
class UpdatesSchritt(a: Assistent) : SchrittAnsicht("updates", a) {
    override val spaeterZeigen = false
    private val pruefen = Wahl(a, nebeneinander = true) { onPruefen() }
    private val mobil = Wahl(a, nebeneinander = true) { a.geaendert() }
    private val modus = Wahl(a, nebeneinander = true) { a.geaendert() }
    private var rest: View? = null
    private var mobilBlock: View? = null
    private var sim = true

    override fun ansicht(): View = spalte().apply {
        dazu(titel(t.res(R.string.einr_updates_titel)), 0f)
        dazu(a.text(t.res(R.string.einr_updates_frage_pruefen), 20f, fett = true))
        pruefen.option("ja", t.res(R.string.einr_ja), t.res(R.string.einr_updates_pruefen_ja))
        pruefen.option("nein", t.res(R.string.einr_nein), t.res(R.string.einr_updates_pruefen_nein))
        dazu(pruefen.ansicht, 8f)
        val r = spalte().apply { visibility = View.GONE }
        val mb = spalte()
        mb.dazu(a.text(t.res(R.string.einr_updates_frage_mobil), 20f, fett = true), 18f)
        mb.dazu(a.text(t.res(R.string.einr_updates_mobil_kosten), 16f, leise = true), 4f)
        mobil.option("ja", t.res(R.string.einr_ja), t.res(R.string.einr_updates_mobil_ja))
        mobil.option("nein", t.res(R.string.einr_nein), t.res(R.string.einr_updates_mobil_nein))
        mb.dazu(mobil.ansicht, 8f)
        mobilBlock = mb
        r.dazu(mb, 0f)
        r.dazu(a.text(t.res(R.string.einr_updates_frage_modus), 20f, fett = true), 18f)
        modus.option("melden", t.res(R.string.einr_updates_melden), t.res(R.string.einr_updates_melden_satz))
        modus.option("automatisch", t.res(R.string.einr_updates_auto), t.res(R.string.einr_updates_auto_satz))
        r.dazu(modus.ansicht, 8f)
        rest = r
        dazu(r)
    }

    override fun betreten() {
        // Aktueller Wert update.mobilfunk (Standard an) – keine Einwilligung, darum vorbelegt und änderbar
        if (a.stand().vomKern) a.imHintergrund({ EinrichtungApi.get("/api/einstellungen") }) { r ->
            val w = r?.json?.optJSONObject("werte")?.optJSONObject("update.mobilfunk")?.opt("wert")
            if (w is Boolean && mobil.wert == null) mobil.waehle(if (w) "ja" else "nein")
        }
        // Ohne SIM entfällt die Mobilfunk-Frage (Fähigkeit aus Kern E: faehigkeiten.sim)
        val f = a.stand().faehigkeiten
        if (f != null && f.has("sim")) {
            sim = f.optBoolean("sim", true)
            mobilBlock?.visibility = if (sim) View.VISIBLE else View.GONE
        }
    }

    private fun onPruefen() { rest?.visibility = if (pruefen.wert == "ja") View.VISIBLE else View.GONE; a.geaendert() }

    override fun mitSim(): Boolean = sim

    override fun antwort(): JSONObject? {
        val p = pruefen.wert ?: return JSONObject()
        val o = JSONObject().put("pruefen", p == "ja")
        if (p == "ja") {
            if (!sim) o.put("mobilfunk", JSONObject.NULL) else mobil.wert?.let { o.put("mobilfunk", it == "ja") }
            modus.wert?.let { o.put("modus", it) }
        }
        return o
    }
}
