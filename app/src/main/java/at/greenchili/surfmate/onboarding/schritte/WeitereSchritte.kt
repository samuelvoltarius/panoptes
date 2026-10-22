package at.greenchili.surfmate.onboarding.schritte

import android.Manifest
import android.content.pm.PackageManager
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import at.greenchili.surfmate.R
import at.greenchili.surfmate.overlay.SetupGuideDialog
import org.json.JSONObject

/**
 * Was der Assistent von der App braucht (MainActivity/OnboardingHost liefern das). So bleiben die
 * vorhandenen Abläufe (ADB-Popup, Fahrzeug-Dialog, Kamera-Zuordnung, Fernzugang, Rundgang) unverändert.
 */
interface AssistentHost {
    /** Daemon starten → das Android-Popup „USB-Debugging zulassen?“ erscheint (erst NACH der Erklärung). */
    fun usbAnfragen()
    fun daemonLaeuft(ergebnis: (Boolean) -> Unit)
    fun fahrzeugWaehlen(fertig: (Boolean) -> Unit)
    fun kamerasZuordnen(fertig: (Boolean) -> Unit)
    fun fernzugang(fertig: (Boolean) -> Unit)
    /** Web-Seite in der App öffnen (z. B. „telegram.html“, „system.html#planerKarte“). */
    fun seiteOeffnen(pfad: String)
    fun rundgang()
}

// ================================================================ Teil D: Rechte

class AutostartSchritt(a: Assistent, private val host: AssistentHost) : SchrittAnsicht("autostart", a) {
    private lateinit var status: android.widget.TextView
    private var erledigt = false

    override fun ansicht(): View = spalte().apply {
        dazu(titel(t.res(R.string.einr_autostart_titel)), 0f)
        dazu(a.text(t.res(R.string.einr_autostart_text)))
        // Bilder je DiLink-Version folgen (Community-Tester); bis dahin Textanleitung
        dazu(a.text(t.res(R.string.einr_autostart_anleitung), 17f, leise = true))
        dazu(a.knopf(t.res(R.string.einr_autostart_knopf), primaer = false) { SetupGuideDialog.autostartEinrichten(a.activity) }, 18f)
        status = a.text("", 18f, fett = true)
        dazu(status)
    }

    override fun betreten() { pruefe() }
    private fun pruefe() {
        erledigt = !SetupGuideDialog.autostartFehlt(a.activity)
        status.text = if (erledigt) "✓ " + t.res(R.string.einr_autostart_ok) else "✗ " + t.res(R.string.einr_autostart_fehlt)
        a.geaendert()
        nachher(2000) { if (!erledigt) pruefe() }
    }

    // Bewusstes „Später“ (Kern: pflicht:false, löst die Dauererinnerung aus Teil K aus); Weiter erst nach der Rückprüfung
    override fun weiterMoeglich(): Boolean = erledigt
    override fun antwort(): JSONObject = JSONObject().put("geprueft", true).put("ok", erledigt)
}

class UeberlagerungSchritt(a: Assistent) : SchrittAnsicht("ueberlagerung", a) {
    private lateinit var status: android.widget.TextView
    override fun ansicht(): View = spalte().apply {
        dazu(titel(t.res(R.string.einr_ueberlagerung_titel)), 0f)
        dazu(a.text(t.res(R.string.einr_ueberlagerung_text)))
        dazu(a.knopf(t.res(R.string.einr_einstellungen_oeffnen)) { SetupGuideDialog.ueberlagerungOeffnen(a.activity) }, 18f)
        status = a.text("", 18f, fett = true)
        dazu(status)
    }
    override fun betreten() { pruefe() }
    private fun pruefe() {
        val ok = !SetupGuideDialog.ueberlagerungFehlt(a.activity)
        status.text = if (ok) "✓ " + t.res(R.string.einr_erteilt) else "✗ " + t.res(R.string.einr_nicht_erteilt)
        if (!ok) nachher(2000) { pruefe() }
    }
    override fun antwort(): JSONObject = JSONObject().put("geprueft", true).put("ok", !SetupGuideDialog.ueberlagerungFehlt(a.activity))
}

class RechteSchritt(a: Assistent) : SchrittAnsicht("rechte", a) {
    private lateinit var liste: LinearLayout
    private val rechte = listOf(
        Manifest.permission.CAMERA to R.string.einr_recht_kamera,
        Manifest.permission.ACCESS_FINE_LOCATION to R.string.einr_recht_standort,
        Manifest.permission.WRITE_EXTERNAL_STORAGE to R.string.einr_recht_speicher,
    )
    override fun ansicht(): View = spalte().apply {
        dazu(titel(t.res(R.string.einr_rechte_titel)), 0f)
        dazu(a.text(t.res(R.string.einr_rechte_text), 17f, leise = true))
        liste = spalte()
        dazu(liste)
    }
    override fun betreten() { zeichne() }
    private fun erteilt(p: String) = a.activity.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun zeichne() {
        liste.removeAllViews()
        var fehlt = false
        for ((p, txt) in rechte) {
            val ok = erteilt(p)
            if (!ok) fehlt = true
            liste.dazu(a.text((if (ok) "✓ " else "✗ ") + t.res(txt), 19f).apply { if (!ok) setTextColor(a.farbeWarnung) }, 10f)
        }
        if (fehlt) liste.dazu(a.knopf(t.res(R.string.einr_erneut_versuchen)) {
            a.activity.requestPermissions(rechte.map { it.first }.filter { !erteilt(it) }.toTypedArray(), 4711)
            nachher(1500) { zeichne() }
        }, 16f)
    }
    override fun antwort(): JSONObject = JSONObject().put("geprueft", true).put("ok", rechte.all { erteilt(it.first) })
}

class UsbFreigabeSchritt(a: Assistent, private val host: AssistentHost) : SchrittAnsicht("usb_freigabe", a) {
    override val spaeterZeigen = false
    private lateinit var status: android.widget.TextView
    private var laeuft = false
    private var angefragt = false

    override fun ansicht(): View = spalte().apply {
        dazu(titel(t.res(R.string.einr_usb_titel)), 0f)
        dazu(a.text(t.res(R.string.einr_usb_warum)))
        // Erst die Erklärung (mit Beschreibung des Popups), dann das Popup auslösen
        dazu(a.text(t.res(R.string.einr_usb_popup), 18f).apply { setTextColor(a.farbeAkzent) })
        status = a.text("", 18f, fett = true)
        dazu(status)
    }
    override fun betreten() { pruefe() }
    private fun pruefe() {
        host.daemonLaeuft { ok ->
            a.haupt.post {
                laeuft = ok
                status.text = when {
                    ok -> "✓ " + t.res(R.string.einr_usb_ok)
                    angefragt -> t.res(R.string.einr_usb_warte)
                    else -> ""
                }
                a.geaendert()
                if (!ok && angefragt) nachher(2000) { pruefe() }
            }
        }
    }
    override fun weiterText(): String? = if (laeuft) null else t.res(R.string.einr_usb_knopf)
    override fun weiterMoeglich(): Boolean = true
    override fun weiter(fertig: (Boolean, String?) -> Unit) {
        if (!laeuft) {
            angefragt = true
            host.usbAnfragen()
            pruefe()
            fertig(false, t.res(R.string.einr_usb_popup_jetzt))
            return
        }
        a.speichereAntwort(id, JSONObject().put("geprueft", true).put("ok", true), fertig)
    }
}

// ================================================================ Teil E: Auto + Kameras

class AutoSchritt(a: Assistent, private val host: AssistentHost) : SchrittAnsicht("auto", a) {
    override val spaeterZeigen = false
    private var modell: String? = null
    private lateinit var modellText: android.widget.TextView

    override fun ansicht(): View = spalte().apply {
        dazu(titel(t.res(R.string.einr_auto_titel)), 0f)
        val f = a.stand().faehigkeiten
        if (f == null) {
            dazu(a.text(t.res(R.string.einr_auto_unbekannt), 17f, leise = true))
        } else {
            faehigkeiten(f).forEach { (name, wert) ->
                val z = when (wert) { "ja" -> "✓ "; "nein" -> "✗ "; else -> "? " }
                val text = z + name + when (wert) { "ja" -> ""; "nein" -> " – " + t.res(R.string.einr_kann_nicht); else -> " – " + t.res(R.string.einr_unbekannt) }
                dazu(a.text(text, 18f), 6f)
            }
            dazu(a.text(t.res(R.string.einr_auto_ausblenden), 17f, leise = true))
        }
        dazu(a.text(t.res(R.string.einr_auto_modell), 20f, fett = true), 20f)
        modellText = a.text("", 18f, leise = true)
        dazu(modellText, 4f)
        val zeile = LinearLayout(a.activity).apply { orientation = LinearLayout.HORIZONTAL }
        zeile.addView(a.knopf(t.res(R.string.einr_auto_waehlen)) {
            host.fahrzeugWaehlen { ok -> if (ok) { modell = gewaehltesModell(); modellText.text = "✓ " + t.res(R.string.einr_auto_gewaehlt); a.geaendert() } }
        })
        zeile.addView(a.knopf(t.res(R.string.einr_weiss_nicht)) {
            modell = "unbekannt"; modellText.text = t.res(R.string.einr_auto_weiss_nicht_satz); a.geaendert()
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = a.px(16f) })
        dazu(zeile, 8f)
    }

    /** faehigkeiten → (Name, ja|nein|unbekannt). Unbekanntes bleibt „unbekannt“, nie „ja“. */
    private fun faehigkeiten(f: JSONObject): List<Pair<String, String>> {
        val l = ArrayList<Pair<String, String>>()
        fun norm(v: String) = if (v == "ja" || v == "nein") v else "unbekannt"
        f.optJSONObject("kameras")?.let { k ->
            val n = if (k.has("anzahl") && !k.isNull("anzahl")) k.optInt("anzahl", -1) else -1
            l.add(Pair(if (n >= 0) t.res(R.string.einr_f_kameras_n, n) else t.res(R.string.einr_f_kameras), norm(k.optString("status", "unbekannt"))))
        }
        val namen = mapOf("ladestand" to R.string.einr_wert_ladestand, "spannung12v" to R.string.einr_wert_12v,
            "innentemperatur" to R.string.einr_wert_innen, "akkutemperatur" to R.string.einr_wert_akkutemp,
            "reifendruck" to R.string.einr_wert_reifen)
        f.optJSONObject("werte")?.let { w ->
            namen.forEach { (k, res) -> if (w.has(k)) l.add(Pair(t.res(res), norm(w.optString(k)))) }
        }
        f.optJSONObject("ueberProjektion")?.let { p ->
            if (p.has("carplay")) l.add(Pair(t.res(R.string.einr_wert_carplay), norm(p.optString("carplay"))))
            if (p.has("androidauto")) l.add(Pair(t.res(R.string.einr_wert_androidauto), norm(p.optString("androidauto"))))
        }
        return l
    }

    /** Modell-ID aus der Fahrzeug-Config nach dem vorhandenen Dialog; ohne Eintrag „unbekannt“. */
    private fun gewaehltesModell(): String = try {
        at.greenchili.surfmate.config.UnifiedConfigManager.getVehicle().optString("modelId", "").ifEmpty { "unbekannt" }
    } catch (_: Throwable) { "unbekannt" }

    override fun weiterMoeglich(): Boolean = modell != null
    override fun antwort(): JSONObject = JSONObject().put("modell", modell ?: "unbekannt")
}

class KamerasSchritt(a: Assistent, private val host: AssistentHost) : SchrittAnsicht("kameras", a) {
    private var gespeichert = false
    private lateinit var status: android.widget.TextView
    override fun ansicht(): View = spalte().apply {
        dazu(titel(t.res(R.string.einr_kameras_titel)), 0f)
        dazu(a.text(t.res(R.string.einr_kameras_text)))
        dazu(a.knopf(t.res(R.string.einr_kameras_knopf), primaer = false) {
            host.kamerasZuordnen { ok -> gespeichert = ok; status.text = if (ok) "✓ " + t.res(R.string.einr_kameras_ok) else t.res(R.string.einr_kameras_nicht); a.geaendert() }
        }, 18f)
        status = a.text("", 18f, fett = true)
        dazu(status)
    }
    override fun weiterMoeglich(): Boolean = gespeichert
    override fun antwort(): JSONObject = JSONObject().put("geprueft", true).put("ok", gespeichert)
}

// ================================================================ Teil F: Daten teilen (alle aus, je Was/Wofür/Wohin)

class DatenSchritt(a: Assistent) : SchrittAnsicht("daten", a) {
    private lateinit var liste: LinearLayout
    private val schalter = LinkedHashMap<String, Switch>()

    override fun ansicht(): View = spalte().apply {
        dazu(titel(t.res(R.string.einr_daten_titel)), 0f)
        dazu(a.text(t.res(R.string.einr_daten_text), 17f, leise = true))
        liste = spalte()
        dazu(liste)
    }

    override fun betreten() {
        if (!a.stand().vomKern) return
        a.imHintergrund({ EinrichtungApi.get("/api/einstellungen/schema") }) { r ->
            liste.removeAllViews(); schalter.clear()
            val gr = r?.json?.optJSONArray("gruppen") ?: return@imHintergrund
            for (i in 0 until gr.length()) {
                val g = gr.optJSONObject(i) ?: continue
                if (g.optString("id") != "datenschutz") continue
                val f = g.optJSONArray("felder") ?: continue
                for (j in 0 until f.length()) {
                    val feld = f.optJSONObject(j) ?: continue
                    if (feld.optString("typ") != "bool" || feld.optBoolean("sichtbar", true).not()) continue
                    val key = feld.optString("key")
                    val sw = Switch(a.activity).apply {
                        text = feld.optString("titel", key)
                        setTextSize(TypedValue.COMPLEX_UNIT_PX, a.schrift(19f))
                        setTextColor(a.farbeText)
                        minHeight = a.knopfHoehe
                        isChecked = false                       // alle aus, keine Vorauswahl
                    }
                    schalter[key] = sw
                    liste.dazu(sw, 14f)
                    val hilfe = feld.optString("hilfe", "")
                    val bt = feld.optJSONObject("bestaetigungText")?.optString("text", "") ?: ""
                    (if (bt.isNotEmpty()) bt else hilfe).takeIf { it.isNotEmpty() }?.let { liste.dazu(a.text(it, 16f, leise = true), 2f) }
                }
            }
            if (schalter.isEmpty()) liste.dazu(a.text(t.res(R.string.einr_daten_keine), 17f, leise = true))
        }
    }

    /** Nur genannte Freigaben werden geschrieben; nicht genannte bleiben aus (§0). */
    override fun antwort(): JSONObject = JSONObject().apply { schalter.forEach { (k, s) -> put(k, s.isChecked) } }
}

// ================================================================ Blitzer (nur wenn gewollt; Rechtshinweis)

class BlitzerSchritt(a: Assistent) : SchrittAnsicht("blitzer", a) {
    private val wahl = Wahl(a, nebeneinander = true) { a.geaendert() }
    override fun ansicht(): View = spalte().apply {
        dazu(titel(t.web("speedcam.rechtshinweis.title", "Blitzer-Anzeige: bitte zuerst lesen")), 0f)
        dazu(a.text(t.web("speedcam.rechtshinweis.text"), 17f))
        wahl.option("aus", t.res(R.string.einr_blitzer_aus), null)
        wahl.option("an", t.res(R.string.einr_blitzer_an), null)
        dazu(wahl.ansicht, 16f)
    }
    override fun weiterMoeglich(): Boolean = wahl.wert != null
    override fun antwort(): JSONObject = JSONObject()

    override fun weiter(fertig: (Boolean, String?) -> Unit) {
        if (!a.stand().vomKern) { fertig(false, t.res(R.string.einr_fehler_verbindung)); return }
        val an = wahl.wert == "an"
        a.imHintergrund({
            EinrichtungApi.einstellungSetzen(JSONObject().put("speedcam.enabled", an), if (an) listOf("speedcam.enabled") else emptyList())
        }) { r ->
            if (r == null || !r.ok || r.json.optBoolean("ok", true) == false) { fertig(false, t.res(R.string.einr_fehler_speichern)); return@imHintergrund }
            a.speichereAntwort(id, JSONObject(), fertig)
        }
    }
}

// ================================================================ Teil I: Extras (alle „Später“-fähig)

class ExtraSchritt(id: String, a: Assistent, private val host: AssistentHost) : SchrittAnsicht(id, a) {
    private var erledigt = false
    override fun ansicht(): View = spalte().apply {
        val sid = this@ExtraSchritt.id
        val (titelId, textId) = when (sid) {
            "extra_telegram" -> R.string.einr_extra_telegram_titel to R.string.einr_extra_telegram_text
            "extra_planer" -> R.string.einr_extra_planer_titel to R.string.einr_extra_planer_text
            "extra_cockpit" -> R.string.einr_extra_cockpit_titel to R.string.einr_extra_cockpit_text
            // 0.1.14: Notzugang (Kern 7) – Standard aus; eingerichtet wird nur auf der System-Seite (Auth-Key nie im Assistenten)
            "extra_notzugang" -> R.string.einr_extra_notzugang_titel to R.string.einr_extra_notzugang_text
            else -> R.string.einr_extra_fernzugang_titel to R.string.einr_extra_fernzugang_text
        }
        dazu(titel(t.res(titelId)), 0f)
        dazu(a.text(t.res(textId)))
        dazu(a.knopf(t.res(R.string.einr_jetzt_einrichten), primaer = false) {
            when (sid) {
                "extra_telegram" -> host.seiteOeffnen("telegram.html")
                "extra_planer" -> host.seiteOeffnen("system.html#planerKarte")
                "extra_cockpit" -> host.seiteOeffnen("system.html#kopplung")
                "extra_notzugang" -> host.seiteOeffnen("system.html#notzugang")
                else -> host.fernzugang { ok -> erledigt = ok; a.geaendert() }
            }
        }, 18f)
    }
    override fun antwort(): JSONObject = JSONObject()
}

// ================================================================ Teil G: Fertig – Selbsttest (nur echte Prüfungen)

class FertigSchritt(a: Assistent, private val host: AssistentHost) : SchrittAnsicht("fertig", a) {
    override val spaeterZeigen = false
    private lateinit var liste: LinearLayout
    private var rundgangFrage = false

    override fun ansicht(): View = spalte().apply {
        dazu(titel(t.res(R.string.einr_fertig_titel)), 0f)
        liste = spalte()
        dazu(liste)
        liste.dazu(a.text(t.res(R.string.einr_fertig_pruefe), 17f, leise = true))
    }

    override fun betreten() {
        if (!a.stand().vomKern) { liste.removeAllViews(); liste.dazu(a.text(t.res(R.string.einr_ungeprueft), 17f, leise = true)); return }
        a.imHintergrund({ EinrichtungApi.selbsttest() }) { r -> zeichne(r?.json?.optJSONObject("selbsttest") ?: r?.json) }
    }

    private fun zeichne(j: JSONObject?) {
        liste.removeAllViews()
        val punkte = j?.optJSONArray("punkte")
        if (punkte == null || punkte.length() == 0) { liste.dazu(a.text(t.res(R.string.einr_ungeprueft), 17f, leise = true)); return }
        for (i in 0 until punkte.length()) {
            val p = punkte.optJSONObject(i) ?: continue
            val status = p.optString("status")
            val zeichen = when (status) { "ok" -> "✓"; "fehlt" -> "✗"; "entfaellt" -> "–"; else -> "?" }
            val wort = when (status) {
                "ok" -> t.res(R.string.einr_st_ok)
                "fehlt" -> t.res(R.string.einr_st_fehlt)
                // „entfällt“ nie ohne Grund (Koordination 22.10.: Laien verwirrt das)
                "entfaellt" -> t.res(R.string.einr_st_entfaellt) + " – " + when (p.optString("id")) {
                    "autostart", "kameras" -> t.res(R.string.einr_entfaellt_nicht_noetig)
                    "projektion" -> t.res(R.string.einr_entfaellt_nicht_genutzt)
                    else -> t.res(R.string.einr_entfaellt_nicht_eingerichtet)
                }
                else -> t.res(R.string.einr_st_ungeprueft)
            }
            val name = selbsttestName(p)
            val zeile = LinearLayout(a.activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            zeile.addView(a.text("$zeichen $name – $wort", 18f).apply { if (status == "fehlt") setTextColor(a.farbeWarnung) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val ziel = p.optString("ziel", "")
            // „Beheben“ nur mit Ziel-Schritt; Punkte mit „einstellungen“ (z. B. projektion) nennen die Einstellung im Text
            if (status == "fehlt" && ziel.isNotEmpty()) zeile.addView(a.knopf(t.res(R.string.einr_beheben)) { a.zeige(ziel) })
            liste.dazu(zeile, 8f)
        }
    }

    /** Name je Punkt-ID (Kern G), mit Wert (Kameras n, Speicher GB, Wächter-Modus). Unbekannte IDs: Text des Kerns bzw. die ID. */
    private fun selbsttestName(p: JSONObject): String {
        val id = p.optString("id")
        val w = if (p.has("wert") && !p.isNull("wert")) p.opt("wert") else null
        return when (id) {
            "autostart" -> t.res(R.string.einr_st_autostart)
            "dienste" -> t.res(R.string.einr_st_dienste)
            "kameras" -> if (w is Number) t.res(R.string.einr_st_kameras_n, w.toInt()) else t.res(R.string.einr_st_kameras)
            "speicher" -> if (w is Number) t.res(R.string.einr_st_speicher_gb,
                java.text.NumberFormat.getNumberInstance(a.activity.resources.configuration.locales[0]).apply { maximumFractionDigits = 1 }.format(w.toDouble()))
                else t.res(R.string.einr_st_speicher)
            "internet" -> t.res(R.string.einr_st_internet)
            "planer" -> t.res(R.string.einr_st_planer)
            "fernzugang" -> t.res(R.string.einr_st_fernzugang)
            "waechter" -> t.res(R.string.einr_st_waechter) + (if (w != null) ": " + t.web("guardLegal.trigger." + mapOf("shock" to "a", "ai_everywhere" to "b", "ai_private" to "c", "aus" to "aus")[w.toString()] + ".label", w.toString()) else "")
            "projektion" -> t.res(R.string.einr_st_projektion)
            else -> p.optString("text", "").ifEmpty { id }
        }
    }

    override fun weiterText(): String = if (rundgangFrage) t.res(R.string.einr_fertig_schliessen) else t.res(R.string.einr_fertig_knopf)

    override fun weiter(fertig: (Boolean, String?) -> Unit) {
        if (!rundgangFrage) {
            // Abschluss melden, dann Angebot „Kurze Führung durch Panoptes?“
            a.speichereAntwort(id, JSONObject().put("ok", true)) { ok, text ->
                if (!ok) { fertig(false, text); return@speichereAntwort }
                rundgangFrage = true
                liste.dazu(a.text(t.res(R.string.einr_rundgang_frage), 20f, fett = true), 24f)
                liste.dazu(a.knopf(t.res(R.string.einr_rundgang_ja), primaer = true) { a.beenden(true); host.rundgang() }, 10f)
                a.geaendert()
            }
            return
        }
        a.beenden(true)
    }
}
