package at.greenchili.panoptes.launcher

import org.json.JSONObject

/**
 * 0.1.41 (LC, Alfreds Wort 11.11. „1-10 gleich einbauen“): Punkt 3 „Navi aufs Kombi“ und Punkt 8 „Seitenblick beim
 * Blinken“. Reine Regeln ohne Android, damit sie im Unit-Test laufen. Der Launcher hat für beides KEINEN eigenen Weg:
 *
 * - Kombi: Knopf in der Karten-Spalte (Einstellung, ab Werk aus). Er ruft `/api/launcher/v1/tacho/kombi` → im Kern
 *   `TachoNaviApp.setze` mit allen Sperren (Hinweis, Freigabe im Stand, Einschalten nur im Stand, Plattformurteil).
 *   Am Dolphin Surf sagt das Plattformurteil „nein“ (Tacho ist ein eigenes System ohne Fläche für fremde Bilder) –
 *   dann bleibt der Knopf weg und die Einstellung sagt ehrlich, warum. Zurück: „Tacho wieder normal“ (vorhanden).
 * - Seitenblick: Schalter der vorhandenen Kern-Funktion (Standard aus). Der Kern LIEST den Blinker und zeigt nur,
 *   solange der Fahrer blinkt; den Blinker bedient Panoptes nie (REGEL A+). Fahrhilfe → darf während der Fahrt
 *   erscheinen; im Rückwärtsgang hat die Werks-Rückfahrkamera Vorrang (setzt der Kern beim Einschalten).
 */
object Kombi {
    /** Launcher-Einstellung „Kombi-Knopf in der Karte“; ab Werk aus. */
    const val KEY_KNOPF = "kombi_knopf"

    enum class Hinweis { GEHT_NICHT, FREIGEBEN, NUR_STAND, KEINE_NAVI, FEHLER }

    data class Stand(val angeboten: Boolean, val grund: String, val freigegeben: Boolean, val aktiv: String?)

    fun parse(json: String): Stand? = try {
        val o = JSONObject(json)
        if (!o.has("angeboten")) null
        else Stand(o.optBoolean("angeboten", false), o.optString("grund", ""), o.optBoolean("freigegeben", false),
            if (o.isNull("aktiv")) null else o.optString("aktiv").ifEmpty { null })
    } catch (_: Exception) { null }

    /**
     * Knopf sichtbar? Nur mit eingeschalteter Einstellung und solange der Kern nicht „nein“ gesagt hat
     * ([angeboten] null = noch unbekannt → zeigen, die Antwort des Kerns entscheidet beim Tipp).
     */
    fun knopfZeigen(einstellungAn: Boolean, angeboten: Boolean?): Boolean = einstellungAn && angeboten != false

    /** Welche App aufs Kombi: die gerade geteilte Navi, sonst das Navi-Ziel; Panoptes selbst nie (eigener Web-Weg). */
    fun ziel(imSplit: String?, naviZiel: String?): String? =
        listOf(imSplit, naviZiel).firstOrNull { !it.isNullOrBlank() && it != Raster.PANOPTES }

    /** Kern-Grund (TachoNaviApp / LauncherKombi) → ehrlicher Hinweis im Launcher. */
    fun hinweis(grund: String?): Hinweis = when (grund) {
        "single_os", "kein_projektionsdienst", "keine_shell_rechte", "auf_diesem_auto_nicht_angeboten",
        "faehigkeit_unbekannt", "warnleuchten_verdeckt" -> Hinweis.GEHT_NICHT
        "hinweis_noetig", "nicht_freigegeben" -> Hinweis.FREIGEBEN
        "nur_im_stand" -> Hinweis.NUR_STAND
        "keine_navi", "ungueltiges_paket", "nicht_installiert" -> Hinweis.KEINE_NAVI
        else -> if (grund != null && grund.startsWith("dilink5")) Hinweis.GEHT_NICHT else Hinweis.FEHLER
    }

    /** Antwort auf den Start: ok, sonst der Grund des Kerns (fehlt er: „fehler“). */
    fun startGrund(json: String?): String? = try {
        val o = JSONObject(json ?: "")
        if (o.optBoolean("ok", false)) null else o.optString("grund", "").ifEmpty { "fehler" }
    } catch (_: Exception) { "fehler" }
}

object Seitenblick {
    data class Stand(val an: Boolean, val rueckwaertsVorrang: Boolean, val blinker: String)

    fun parse(json: String): Stand? = try {
        val o = JSONObject(json)
        if (!o.has("an")) null
        else Stand(o.optBoolean("an", false), o.optBoolean("rueckwaertsVorrang", false), o.optString("blinker", "unbekannt"))
    } catch (_: Exception) { null }

    /** Antwort auf ein/aus: true nur mit ok vom Kern (sonst Schalter unverändert anzeigen). */
    fun geschaltet(json: String?): Boolean = try { JSONObject(json ?: "").optBoolean("ok", false) } catch (_: Exception) { false }
}
