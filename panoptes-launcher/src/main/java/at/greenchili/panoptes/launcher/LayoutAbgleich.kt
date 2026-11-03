package at.greenchili.panoptes.launcher

import org.json.JSONObject

/** Stand des Layouts im Panoptes-Kern (`GET /api/launcher/v1/layout`). */
data class KernLayout(val layout: StartLayout, val geaendert: Long, val standard: Boolean)

/** Antwort auf `PUT /api/launcher/v1/layout`. */
sealed class SpeicherAntwort {
    /** Gespeichert; [layout] ist der vom Kern bereinigte Stand (z. B. ohne deinstallierte Apps). */
    data class Ok(val geaendert: Long, val layout: StartLayout?) : SpeicherAntwort()
    /** Der Kern-Stand ist neuer (409). */
    data class Neuer(val kern: KernLayout) : SpeicherAntwort()
    /** Abgelehnt (400/403/413) oder nicht erreichbar (status 0): lokale Kopie bleibt. */
    data class Fehler(val status: Int) : SpeicherAntwort()
}

/**
 * 0.1.38 Launcher-Layout im Kern (LAUNCHER_LAYOUT_API.md §1/§6): reine Regeln für den Abgleich zwischen der lokalen
 * Kopie (Rückfall, wenn der Kern nicht erreichbar ist) und dem Kern. Neueste Änderung gewinnt, keine Zusammenführung
 * einzelner Kacheln. Launcher und Kern laufen auf demselben Gerät, die Uhren sind also dieselben.
 */
object LayoutAbgleich {
    enum class Schritt { UEBERNEHMEN, HOCHLADEN, NICHTS }

    /**
     * Nach `GET layout`:
     * - eine lokale Änderung wartet noch → hochladen (ist der Kern neuer, kommt 409 und [nochmalSenden] entscheidet),
     * - der Kern kennt noch nichts (Standard, nie gespeichert) und der Launcher hat ein eigenes Layout von früher, das
     *   noch nie im Kern war → einmal hochladen, damit nichts verloren geht,
     * - sonst den Kern-Stand übernehmen, wenn er anders ist als der zuletzt gekannte.
     */
    fun beimHolen(kern: KernLayout, basis: Long, offen: Boolean, eigenesAltesLayout: Boolean, kenntKern: Boolean): Schritt = when {
        offen -> Schritt.HOCHLADEN
        !kenntKern && eigenesAltesLayout && kern.geaendert == 0L && kern.standard -> Schritt.HOCHLADEN
        !kenntKern || kern.geaendert != basis -> Schritt.UEBERNEHMEN
        else -> Schritt.NICHTS
    }

    /** Bei 409: ist die eigene Änderung neuer als der Kern-Stand, noch einmal mit der neuen Basis senden. */
    fun nochmalSenden(lokalGeaendert: Long, kernGeaendert: Long): Boolean = lokalGeaendert > kernGeaendert

    /** Im 5-s-summary: neu holen, wenn der Kern-Zeitstempel sich geändert hat (nie während Bearbeiten). */
    fun neuHolen(summaryGeaendert: Long?, basis: Long, offen: Boolean, bearbeiten: Boolean): Boolean =
        summaryGeaendert != null && summaryGeaendert != basis && !offen && !bearbeiten

    // ---------- Antworten lesen ----------

    fun parseKern(json: String?): KernLayout? = try {
        val root = JSONObject(json ?: "")
        val l = Raster.fromJson(root.optJSONObject("layout")?.toString())
        if (l == null) null else KernLayout(l, root.optLong("geaendert", 0L), root.optBoolean("standard", false))
    } catch (_: Exception) {
        null
    }

    fun parseSpeichern(status: Int, json: String?): SpeicherAntwort = try {
        val root = JSONObject(json ?: "{}")
        when (status) {
            200 -> SpeicherAntwort.Ok(root.optLong("geaendert", 0L), Raster.fromJson(root.optJSONObject("layout")?.toString()))
            409 -> parseKern(json)?.let { SpeicherAntwort.Neuer(it) } ?: SpeicherAntwort.Fehler(409)
            else -> SpeicherAntwort.Fehler(status)
        }
    } catch (_: Exception) {
        SpeicherAntwort.Fehler(status)
    }

    fun speichernBody(l: StartLayout, basis: Long): String =
        JSONObject().put("layout", JSONObject(Raster.toJson(l))).put("basis", basis).toString()
}
