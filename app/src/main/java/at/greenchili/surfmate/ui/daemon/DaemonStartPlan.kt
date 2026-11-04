package at.greenchili.surfmate.ui.daemon

/**
 * 0.1.38.8 Schnellstart: wann der Kamera-Daemon (er bedient den Webserver :8180) gestartet wird.
 *
 * Die 45 s "System stabilisieren" stammen aus dem Ursprungsprojekt (Overdrive, 04/2026): zuerst eine Verzögerung der
 * GPU-Pipeline im Daemon, "damit BYD-Kamera-Apps ihre EGL-Flächen zuerst bekommen", dann als fester Aufschub des
 * ganzen Daemon-Starts. Der Grund gilt für den Boot des Kopfgeräts. Deshalb zählen die 45 s ab Boot
 * (SystemClock.elapsedRealtime = Zeit seit dem Boot): Läuft das Gerät schon länger, startet der Kamera-Daemon sofort
 * (nach [MIN_MS]); nach dem Boot wird nur der Rest abgewartet. Die Übergabe Boot-Manager → MainActivity startet damit
 * keine neue Uhr: wer 30 s nach dem Boot öffnet, wartet noch 15 s statt 45 s.
 * Sentry und ACC-Sentry folgen weiter 5/10 s nach der Kamera, optionale Daemons und Health-Check später.
 * Reine Funktionen, ohne Android – JVM-testbar.
 */
object DaemonStartPlan {
    /** So lange nach dem Boot des Geräts (Uhr seit dem Boot) bleibt das System unruhig. */
    const val BOOT_STABIL_MS = 45_000L

    /** Kürzeste Verzögerung (Daemon-Prozess und Hauptschleife dürfen sich setzen). */
    const val MIN_MS = 1_000L

    /** Optionale Daemons und Health-Check bleiben so viel nach dem Kamera-Start wie bisher (60 s / 90 s minus 45 s). */
    const val OPTIONAL_NACH_KAMERA_MS = 15_000L
    const val HEALTH_NACH_KAMERA_MS = 45_000L

    /** @param geraeteLaufzeitMs Zeit seit dem Boot des Geräts (SystemClock.elapsedRealtime) */
    @JvmStatic
    fun kameraVerzoegerungMs(geraeteLaufzeitMs: Long): Long {
        val rest = BOOT_STABIL_MS - geraeteLaufzeitMs.coerceAtLeast(0L)
        return rest.coerceIn(MIN_MS, BOOT_STABIL_MS)
    }
}
