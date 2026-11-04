package at.greenchili.panoptes.launcher

/** Ein Startbildschirm, den Android für MAIN/HOME meldet. */
data class HomeKandidat(val packageName: String, val className: String)

/**
 * Wählt den Startbildschirm, zu dem der Knopf "BYD-Startbildschirm" zurückführt.
 * Reine Regel ohne Android, damit sie im Unit-Test belegt ist.
 *
 * - Nie die eigene App (sonst Schleife, sobald sie später HOME deklarieren sollte).
 * - Nie die Android-Auswahl ("android"/ResolverActivity): Die würde den Nutzer fragen
 *   und könnte zu einer ungewollten Standard-Wahl führen.
 * - Bevorzugt den vom System gemeldeten Standard, sonst den ersten fremden Kandidaten.
 * - null heißt: kein Ziel gefunden; der Aufrufer schließt dann nur die eigene Ansicht.
 */
object HomeRueckweg {
    fun ziel(eigenesPaket: String, standard: HomeKandidat?, kandidaten: List<HomeKandidat>): HomeKandidat? {
        fun erlaubt(k: HomeKandidat?) = k != null &&
            k.packageName.isNotBlank() &&
            k.packageName != eigenesPaket &&
            k.packageName != "android" &&
            !k.className.endsWith("ResolverActivity") &&
            // Boot-Notbehelf des Systems (priority -1000), kein echter Startbildschirm
            !k.className.endsWith("FallbackHome")
        if (erlaubt(standard)) return standard
        return kandidaten.firstOrNull { erlaubt(it) }
    }
}
