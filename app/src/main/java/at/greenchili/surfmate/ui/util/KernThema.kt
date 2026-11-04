package at.greenchili.surfmate.ui.util

/**
 * Hell/Dunkel des Auto-Displays an der Quelle (Darstellung-Sync): Kern-Wert `anzeige.thema` (dark|light|auto) geht
 * vor der Native-Wahl. Nur `auto` überlässt die Entscheidung der Native-App (PreferencesManager -> AppCompat -> uiMode).
 * Reine Funktionen, damit ohne Android testbar.
 */
object KernThema {
    const val AUTO = "auto"
    const val DARK = "dark"
    const val LIGHT = "light"

    /** Gültiger Kern-Wert, sonst `auto`. */
    @JvmStatic
    fun pruefen(v: String?): String = if (v == DARK || v == LIGHT) v else AUTO

    /** `anzeige.thema` aus dem Kern-Config (gleiche Datei wie der Daemon); null, wenn der Schlüssel fehlt. */
    @JvmStatic
    fun ausKern(root: org.json.JSONObject?): String? {
        val a = root?.optJSONObject("anzeige") ?: return null
        if (!a.has("thema")) return null
        return pruefen(a.optString("thema", AUTO))
    }

    /**
     * @param kern      gespiegelter Kern-Wert (dark|light|auto)
     * @param nativ     was die Native-Wahl liefert: "dark", "light" oder null, wenn sie nichts Festes sagt (Auto/System)
     * @param nachtUi   Systemzustand uiMode night, letzter Rückfall
     */
    @JvmStatic
    fun aufloesen(kern: String?, nativ: String?, nachtUi: Boolean): String {
        val k = pruefen(kern)
        if (k != AUTO) return k
        if (nativ == DARK || nativ == LIGHT) return nativ
        return if (nachtUi) DARK else LIGHT
    }
}
