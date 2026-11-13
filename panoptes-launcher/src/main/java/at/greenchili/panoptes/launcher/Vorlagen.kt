package at.greenchili.panoptes.launcher

/**
 * 0.1.41 (LA) Layout-Vorlagen: „Einfach“ (wenige große Kacheln, große Schrift, für 50+), „Standard“ und „Cockpit“.
 * Die Vorlagen sind mitgelieferte Layoutdateien im Export-Format (`assets/vorlagen/<name>.json`, gelesen über
 * [Raster.fromJson], also mit denselben Prüfungen wie ein Import). Eigene Aufteilung, nichts aus anderen Launchern.
 *
 * Eine Vorlage ersetzt Startseiten und Aussehen. Dock, Schnellstart-Apps und „Nebeneinander“ gehören dem Nutzer
 * und bleiben. Vor dem Ersetzen wird gefragt (MainActivity). Reine Regeln, im Unit-Test belegt.
 */
object Vorlagen {
    const val EINFACH = "einfach"
    const val STANDARD = "standard"
    const val COCKPIT = "cockpit"
    /** 0.1.44 (D1): Fahrzeug-Startseite (Status-Karte, eigene Fahrzeug-Ansicht mit Fenstern, Musik, Szene). */
    const val FAHRZEUG = "fahrzeug"
    val WAHL = listOf(EINFACH, STANDARD, COCKPIT, FAHRZEUG)

    fun datei(name: String): String = "vorlagen/$name.json"

    /** „Einfach“ stellt zusätzlich die größere Schrift ein. 0.1.41 (E2E G3): „Standard“ und „Cockpit“ stellen sie
     *  wieder auf „Automatisch“, sonst bliebe „Größer“ von „Einfach“ stehen. Unbekannter Name: Größe bleibt. */
    fun groesse(name: String): String? = when (name) {
        EINFACH -> LauncherGroesse.GROESSER
        STANDARD, COCKPIT, FAHRZEUG -> LauncherGroesse.AUTO
        else -> null
    }

    /** Liest eine Vorlage aus ihrem Dateitext; null bei unbekanntem Namen oder kaputter Datei. */
    fun lesen(name: String, text: String?): StartLayout? = if (name in WAHL) Raster.fromJson(text) else null

    /** Vorlage über das alte Layout legen: Seiten und Aussehen aus der Vorlage, Dock und Apps des Nutzers bleiben. */
    fun anwenden(alt: StartLayout?, vorlage: StartLayout): StartLayout {
        if (alt == null) return vorlage
        return vorlage.copy(
            dock = alt.dock.ifEmpty { vorlage.dock },
            schnell = alt.schnell,
            nebeneinander = alt.nebeneinander,
            nebenAnders = alt.nebenAnders,
            zaehler = maxOf(alt.zaehler, vorlage.zaehler)
        )
    }

    /**
     * Beim ersten Einrichten fragen: nur wenn noch nie ein Layout gespeichert war und noch nie gefragt wurde.
     * Wer schon ein Layout hat, wird nicht gefragt; ab Werk wird nichts still umgestellt.
     * 0.1.41 (E2E M2): Ein 0.1.40-Launcher speichert das Layout erst nach einer eigenen Änderung. Ein Update erkennt
     * darum [lief], nicht das gespeicherte Layout.
     */
    fun fragen(layoutGespeichert: Boolean, schonGefragt: Boolean, lief: Boolean = false): Boolean =
        !layoutGespeichert && !schonGefragt && !lief

    /** Lief der Launcher schon (Update statt Neuinstallation)? Paket schon einmal aktualisiert
     *  (`firstInstallTime < lastUpdateTime`) oder schon ein Kern-Stand bekannt. */
    fun lief(ersteInstallation: Long, letztesUpdate: Long, kenntKern: Boolean): Boolean =
        kenntKern || (ersteInstallation > 0L && letztesUpdate > ersteInstallation)
}
