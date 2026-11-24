package at.greenchili.surfmate.launcher

/**
 * 0.1.43.21: nach einem Neuaufbau der ADB-Verbindung den vollen Startdurchlauf (ZUGANG → REST) höchstens
 * einmal wiederholen.
 *
 * Jede neue Verbindung meldet `onAuthGranted`, und das startete bisher jedes Mal den ganzen Durchlauf neu. Hing
 * darin ein Befehl, riss die Frist die Verbindung ab, der Neuaufbau startete den Durchlauf wieder, und so
 * weiter (24.11.: gen 1 → gen 2 → gen 3). Jetzt gilt:
 * - die erste Verbindung (gen 1) startet den Durchlauf immer;
 * - ein Neuaufbau (gen > 1) startet ihn einmal;
 * - jeder weitere Neuaufbau innerhalb von [FENSTER_MS] danach startet ihn nicht mehr. Die Daemons holen dann die
 *   Wiederverbindungs-Hörer (`ConnectionReestablishedListener`) und der Wachhund nach, wie bisher.
 */
object NeuaufbauDurchlauf {

    const val FENSTER_MS = 5 * 60_000L

    private var letzterWiederholterLauf = 0L

    @JvmStatic
    @Synchronized
    fun zulassen(generation: Long, jetztMs: Long): Boolean {
        if (generation <= 1L) return true
        if (letzterWiederholterLauf > 0L && jetztMs - letzterWiederholterLauf in 0 until FENSTER_MS) return false
        letzterWiederholterLauf = jetztMs
        return true
    }

    /** Nur für Tests. */
    @JvmStatic
    @Synchronized
    fun zuruecksetzen() {
        letzterWiederholterLauf = 0L
    }
}
