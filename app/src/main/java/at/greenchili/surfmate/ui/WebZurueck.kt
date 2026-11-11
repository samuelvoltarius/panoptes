package at.greenchili.surfmate.ui

/**
 * 0.1.39 Z1 (E2E 10.11.: nach Klick in der Leiste bzw. auf einen Link ging Android-Zurück auf moveTaskToBack, obwohl
 * die Seite `history.length=3` meldete): Zurück geht immer eine Web-Seite zurück, solange es Verlauf gibt; erst auf der
 * Startseite geht die App in den Hintergrund. Nicht mehr allein von `WebView.canGoBack()` abhängig – ein eigener
 * Verlauf der besuchten Panoptes-Seiten (aus `doUpdateVisitedHistory`) ist der Rückfall.
 */
object WebZurueck {

    enum class Schritt { WEB_ZURUECK, VORIGE_LADEN, START_LADEN, HINTERGRUND }

    const val MAX = 50

    /** Was tun bei Zurück? [verlauf] = besuchte Seiten, die letzte ist die aktuelle. */
    fun entscheide(kannZurueck: Boolean, verlauf: List<String>, aktuell: String?): Schritt = when {
        kannZurueck -> Schritt.WEB_ZURUECK
        verlauf.size >= 2 -> Schritt.VORIGE_LADEN
        aktuell != null && eigeneSeite(aktuell) && !istStart(aktuell) -> Schritt.START_LADEN
        else -> Schritt.HINTERGRUND
    }

    /** Nur Seiten der lokalen Panoptes-Oberfläche zählen (keine about:blank-, Fehler- oder fremden Seiten). */
    fun eigeneSeite(url: String): Boolean = url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:")

    /** Startseite = Pfad „/“ bzw. „/index.html“ (Abfrage und Anker egal). */
    fun istStart(url: String): Boolean {
        val ohneSchema = url.substringAfter("://", url)
        val pfad = ohneSchema.substringAfter('/', "").substringBefore('?').substringBefore('#')
        return pfad.isEmpty() || pfad == "index.html"
    }

    /** Eigener Verlauf der besuchten Panoptes-Seiten (nur UI-Thread). */
    class Verlauf {
        private val seiten = ArrayList<String>()

        fun liste(): List<String> = seiten

        /** Aus `doUpdateVisitedHistory`: neue Seite merken (Neuladen und Wiederholung nicht). */
        fun besucht(url: String?, neuLaden: Boolean) {
            if (url == null || neuLaden || !eigeneSeite(url)) return
            if (seiten.isNotEmpty() && seiten.last() == url) return
            seiten.add(url)
            while (seiten.size > MAX) seiten.removeAt(0)
        }

        fun leeren() = seiten.clear()

        /** Vor einem Zurück: aktuelle Seite weg; liefert die vorige (oder null). */
        fun zurueck(): String? {
            if (seiten.isNotEmpty()) seiten.removeAt(seiten.size - 1)
            return seiten.lastOrNull()
        }
    }
}
