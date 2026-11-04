package at.greenchili.surfmate.launcher

/**
 * 0.1.38.9 Schnellstart 2 / Fremd-Apps: Prozesse beenden, aber nur EIGENE.
 *
 * Panoptes stammt von Overdrive ab und nutzt dieselben Prozessnamen (`byd_cam_daemon`, `sentry_daemon`, ...). Das alte
 * `killall -9 byd_cam_daemon` und `grep -F cam_daemon` traf deshalb auch Daemons und Watchdog-Skripte einer anderen,
 * gleichzeitig laufenden Werkzeug-App (Overdrive-artig, "Electron"). Die startet ihren Daemon neu und beendet unseren:
 * gegenseitiges Töten, der Webserver wird nie stabil.
 *
 * Ein Prozess gilt als eigen, wenn sein Klassenpfad (Umgebung CLASSPATH eines `app_process`-Daemons) oder seine
 * Befehlszeile (Watchdog-Skript im eigenen Ordner) [PAKET] bzw. [ORDNER] nennt. Nicht lesbare Prozesse (kein
 * /proc/PID/environ) werden wie bisher beendet, damit ein Update nie an einem Altprozess hängen bleibt. Reine
 * Textbausteine für die Shell, ohne Android – JVM-testbar.
 */
object EigeneProzesse {
    const val PAKET = "at.greenchili.surfmate"
    const val ORDNER = "com.android.shell/surfmate"

    /**
     * Ein Durchgang: alle Prozesse, deren `ps`-Zeile eines der [muster] enthält, werden beendet, sofern sie eigen sind.
     * Eine einzige `ps`-Abfrage für alle Muster (auf dem Auto dauert jede ~1 s).
     * @return Shell-Text in einer Zeile, beliebig mit `;`/Zeilenumbruch verkettbar
     */
    @JvmStatic
    fun beendenMehrere(muster: List<String>): String {
        require(muster.isNotEmpty())
        val e = muster.joinToString(" ") { "-e '" + it.replace("'", "") + "'" }
        return "MY_PID=\$\$; for pn_pid in \$(ps -A -o PID,ARGS | grep -F $e | grep -v grep | awk '{print \$1}'); do " +
            "if [ \"\$pn_pid\" != \"\$MY_PID\" ]; then " +
            "pn_c=\$(tr '\\0' ' ' < /proc/\$pn_pid/cmdline 2>/dev/null); " +
            "pn_e=\$(tr '\\0' '\\n' < /proc/\$pn_pid/environ 2>/dev/null | grep -m1 '^CLASSPATH='); " +
            "case \"\$pn_c\$pn_e\" in *$PAKET*|*$ORDNER*) kill -9 \$pn_pid 2>/dev/null;; " +
            "*) [ -r /proc/\$pn_pid/environ ] || kill -9 \$pn_pid 2>/dev/null;; esac; " +
            "fi; done"
    }

    /** Wie [beendenMehrere] für ein einzelnes Muster. */
    @JvmStatic
    fun beenden(muster: String): String = beendenMehrere(listOf(muster))

    /** Zeilenumbruch-Form für `buildString`-Skripte. */
    @JvmStatic
    fun beendenZeile(muster: String): String = beenden(muster) + "\n"

    /**
     * Nur lesen: Prozesse mit dem Namen [muster], die NICHT eigen sind. Ausgabe je Treffer eine Zeile
     * `FREMD <pid> <ps-Zeile>`. Für die Erkennung fremder Werkzeug-Apps (kein Beenden).
     */
    @JvmStatic
    fun fremdeAuflisten(muster: List<String>): String {
        require(muster.isNotEmpty())
        val e = muster.joinToString(" ") { "-e '" + it.replace("'", "") + "'" }
        return "MY_PID=\$\$; ps -A -o PID,ARGS | grep -F $e | grep -v grep | while read pn_pid pn_rest; do " +
            "if [ \"\$pn_pid\" != \"\$MY_PID\" ]; then " +
            "pn_c=\$(tr '\\0' ' ' < /proc/\$pn_pid/cmdline 2>/dev/null); " +
            "pn_e=\$(tr '\\0' '\\n' < /proc/\$pn_pid/environ 2>/dev/null | grep -m1 '^CLASSPATH='); " +
            "case \"\$pn_c\$pn_e\" in *$PAKET*|*$ORDNER*) ;; " +
            "*) [ -r /proc/\$pn_pid/environ ] && echo \"FREMD \$pn_pid \$pn_rest \$pn_e\";; esac; " +
            "fi; done"
    }
}
