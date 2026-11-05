package at.greenchili.surfmate.updater

/**
 * Stufe −1 des Starts (0.1.38.10): Nach einem Update beendet der Hard-Reset alle Daemons. Starten Daemons gleichzeitig
 * (Boot-Weg, Bedienungshilfe, MainActivity), kann der Hard-Reset einen frisch gestarteten Daemon töten, und alles läuft
 * langsamer, weil die langsame CPU beides zugleich macht. Das Tor hält jeden Start an, solange ein Hard-Reset erwartet
 * wird oder läuft. Es öffnet sich beim Ende des Hard-Resets oder nach [ZEITLIMIT_MS] (der Start darf an einem
 * hängenden Hard-Reset nie ewig warten). Reiner Zustand, Zeit kommt von außen – JVM-testbar.
 */
object HardResetTor {
    /** Längstens so lange hält das Tor die Stufen an. */
    const val ZEITLIMIT_MS = 20_000L

    @Volatile private var offen = true
    @Volatile private var seit = 0L

    /** Ein Hard-Reset steht an oder beginnt (idempotent: die erste Zeit zählt). */
    @JvmStatic
    @Synchronized
    fun erwarten(jetztMs: Long) {
        if (!offen) return
        offen = false
        seit = jetztMs
    }

    /** Der Hard-Reset ist zu Ende (Erfolg, Fehler oder Zeitablauf). */
    @JvmStatic
    @Synchronized
    fun beenden() {
        offen = true
    }

    /** true = Start darf laufen (kein Hard-Reset erwartet, beendet oder Zeitlimit um). */
    @JvmStatic
    fun frei(jetztMs: Long): Boolean = offen || jetztMs - seit >= ZEITLIMIT_MS

    /** Nur für Tests. */
    @JvmStatic
    @Synchronized
    fun zuruecksetzen() {
        offen = true
        seit = 0L
    }
}
