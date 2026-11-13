package at.greenchili.panoptes.launcher

/**
 * E5 (13.11.2026, Projektinhaber zugestimmt): Launcher einfacher. Reine Regeln, im Unit-Test belegt; MainActivity hängt sie nur ein.
 *
 * - Keine Layout-Frage mehr beim ersten Start: Standard bleibt „Standard“, mit „Große Bedienung“ gilt „Einfach“.
 *   Wechseln geht unter Einstellungen → Mehr → Layout-Vorlage.
 * - Nach dem ersten Start bietet der Launcher EINMAL an, ihn als Startbildschirm einzurichten („Später“ möglich).
 *   Danach nur noch in den Einstellungen (oberste Zeile).
 * - Einstellungen oben: genau [Einstellungen.OBEN], alles andere unter „Mehr“ (nichts fällt weg).
 * - Kachel-Katalog: zuerst die Grundauswahl, alles andere unter „Weitere Kacheln …“.
 */
object ErstStart {
    /**
     * Vorlage ohne Frage: mit „Großer Bedienung“ „Einfach“, sonst null = „Standard“ bleibt. Nur solange noch kein
     * eigenes Layout gespeichert ist (erster Start, oder Werks-Layout beim Einschalten von „Große Bedienung“);
     * ein eigenes Layout wird nie still ersetzt.
     */
    fun vorlage(grosseBedienung: Boolean, layoutGespeichert: Boolean = false): String? =
        if (grosseBedienung && !layoutGespeichert) Vorlagen.EINFACH else null

    /**
     * Startbildschirm-Angebot: nur einmal, nie wenn der Launcher schon Startbildschirm ist, nie während der Fahrt
     * (dann beim nächsten Kern-Stand; das Angebot gilt erst als gemacht, wenn es zu sehen war).
     */
    fun anbieten(schonAngeboten: Boolean, istStartbildschirm: Boolean, imStand: Boolean): Boolean =
        !erledigt(schonAngeboten, istStartbildschirm) && imStand

    /** Nichts mehr anzubieten: schon gezeigt oder der Launcher ist bereits Startbildschirm. */
    fun erledigt(schonAngeboten: Boolean, istStartbildschirm: Boolean): Boolean = schonAngeboten || istStartbildschirm
}

object Einstellungen {
    /** Die 5 Zeilen oben, in dieser Reihenfolge; danach nur „Mehr“. */
    enum class Zeile { STARTBILDSCHIRM, GROESSE, GROSSE_BEDIENUNG, NAVI_APP, HINTERGRUND }
    val OBEN = Zeile.values().toList()
}

object KachelKatalog {
    /** Grundauswahl, die jeder braucht (Bericht VEREINFACHUNG_0144, Gruppe A). */
    val GRUND = listOf(Kachel.FAHRZEUG, Kachel.AKKU, Kachel.NAVI, Kachel.MUSIK, Kachel.UHR)

    /** Aus den noch fehlenden Kacheln die der Grundauswahl, in deren Reihenfolge. */
    fun grund(fehlend: List<Kachel>): List<Kachel> = GRUND.filter { it in fehlend }

    /** Alles andere (kommt unter „Weitere Kacheln …“), in der Reihenfolge von [fehlend]. */
    fun weitere(fehlend: List<Kachel>): List<Kachel> = fehlend.filter { it !in GRUND }

    /** Ohne fehlende Grundkachel gleich die weitere Liste zeigen (kein Zwischenschritt mit nur einem Eintrag). */
    fun direktWeitere(fehlend: List<Kachel>): Boolean = grund(fehlend).isEmpty()
}
