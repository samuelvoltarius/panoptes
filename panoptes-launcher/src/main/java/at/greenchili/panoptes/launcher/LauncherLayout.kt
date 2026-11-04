package at.greenchili.panoptes.launcher

/** Widgets der Startseite. Daten nur aus der Launcher-API (v1) bzw. der Android-Mediensitzung. */
enum class Kachel { FAHRZEUG, AKKU, NAVI, MUSIK, WAECHTER, UHR }

/** Fahrsicherheit: reine Regeln, im Unit-Test belegt. Starten und Ansehen geht immer. */
object Fahrsicherheit {
    /** Video-Hintergrund nur mit Schalter (Standard aus) UND bestätigtem Gang P. Unbekannter Gang = kein Video. */
    fun videoErlaubt(schalter: Boolean, gear: String?): Boolean = schalter && gear == "P"

    /**
     * Bedienen (Bearbeiten-Modus, Ordner, Dock ändern, Suche tippen) nur im Stand:
     * Gang P, oder Gang unbekannt bei Zündung aus. Tempo > 0 sperrt immer.
     */
    fun bearbeitenErlaubt(gear: String?, acc: String?, speedKmh: Double? = null): Boolean {
        if (speedKmh != null && speedKmh > 0.5) return false
        return gear == "P" || (gear == null && acc == "off")
    }

    /**
     * Video-Apps (Android-Kategorie CATEGORY_VIDEO, von der App selbst erklärt) starten nur in P – außer der Nutzer
     * hat in Panoptes den EINEN Haftungshinweis „Verstanden, ich entscheide selbst“ bestätigt (0.1.38.1, derselbe wie
     * am Tacho; kommt aus dem Summary, fehlt er, gilt nein). Unbekannter Gang ohne Hinweis sperrt.
     */
    fun appStartErlaubt(istVideo: Boolean, gear: String?, fahrtHinweis: Boolean = false): Boolean =
        !istVideo || gear == "P" || fahrtHinweis
}
