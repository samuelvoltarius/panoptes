package at.greenchili.surfmate.launcher

/**
 * 0.1.38.9: Erkennung fremder BYD-Werkzeug-Apps (Overdrive-artig, "Electron"), die gleichzeitig laufen.
 *
 * Zwei solche Apps stören sich gegenseitig: gleiche Prozessnamen (`byd_cam_daemon` ...), eine Kamera, ein EGL. Statt
 * eine Minute am Ladebildschirm zu warten, nennt Panoptes den Grund. Es wird nur GELESEN (nichts beendet, nichts
 * deaktiviert). Reine Auswertung, ohne Android – JVM-testbar; der Aufruf der Shell liegt im Aufrufer.
 */
object FremdApps {
    /**
     * Pakete bekannter Werkzeug-Apps. Eine Stelle für die Liste, leicht zu erweitern (z. B. das Paket von "Electron",
     * sobald es bekannt ist).
     */
    @JvmField
    val BEKANNTE_PAKETE: List<String> = listOf("com.overdrive.app")

    /** Prozessnamen, die Panoptes mit dem Ursprungsprojekt teilt. */
    @JvmField
    val PROZESSNAMEN: List<String> = listOf("byd_cam_daemon", "sentry_daemon", "acc_sentry_daemon", "telegram_bot_daemon")

    /** Skript (nur lesend) für die Erkennung: Zeilen `PAKET <name>` und `FREMD <pid> <ps-Zeile> <CLASSPATH=...>`. */
    @JvmStatic
    fun abfrageSkript(): String {
        val pakete = BEKANNTE_PAKETE.joinToString(" ") { "-e 'package:" + it.replace("'", "") + "'" }
        return "pm list packages 2>/dev/null | grep -F $pakete | sed 's/^package:/PAKET /'\n" +
            EigeneProzesse.fremdeAuflisten(PROZESSNAMEN) + "\necho FERTIG\n"
    }

    class Befund(val pakete: List<String>, val prozesse: List<String>) {
        /** Etwas Fremdes gefunden: ein laufender fremder Prozess mit unseren Namen oder ein bekanntes Paket. */
        val gefunden: Boolean get() = prozesse.isNotEmpty() || pakete.isNotEmpty()

        /** Läuft sie wirklich (nicht nur installiert)? Nur dann stört sie. */
        val laeuft: Boolean get() = prozesse.isNotEmpty()

        /** Kurzer Name für die Anzeige: Paketnamen bzw. "andere Werkzeug-App". */
        fun anzeigeName(): String = pakete.firstOrNull() ?: prozesse.firstOrNull()?.let { klassenpfadPaket(it) } ?: ""

        /** Eine Zeile fürs Diagnose-Protokoll (kein Pfad, keine Nutzerdaten). */
        fun protokollZeile(): String = "pakete=" + pakete.joinToString(",") + ";fremde_prozesse=" + prozesse.size
    }

    private val PAKET_IM_KLASSENPFAD = Regex("/data/app/(?:~~[^/]+/)?([A-Za-z0-9_.]+)-[^/]*/")

    internal fun klassenpfadPaket(zeile: String): String? =
        PAKET_IM_KLASSENPFAD.find(zeile)?.groupValues?.get(1)

    /** Wertet die Ausgabe von [abfrageSkript] aus. */
    @JvmStatic
    fun auswerten(ausgabe: String?): Befund {
        val pakete = LinkedHashSet<String>()
        val prozesse = ArrayList<String>()
        for (roh in (ausgabe ?: "").lines()) {
            val z = roh.trim()
            if (z.startsWith("PAKET ")) {
                val name = z.removePrefix("PAKET ").trim()
                if (name in BEKANNTE_PAKETE) pakete.add(name)
            } else if (z.startsWith("FREMD ")) {
                prozesse.add(z.removePrefix("FREMD ").trim())
            }
        }
        return Befund(pakete.toList(), prozesse)
    }
}
