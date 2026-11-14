package at.greenchili.surfmate.ui.daemon

/**
 * Wann und in welcher Reihenfolge der Daemon-Stapel startet. EINE Quelle (0.1.38.10: ersetzt auch die alte, nur vom
 * Test benutzte DaemonBootSchedule). Den Zustand je Stufe hält [StartStatus] (EINE Stelle, Leser: Ladebildschirm und
 * `GET /api/start/status`).
 *
 * <h3>Boot-Schutz (0.1.38.8)</h3>
 * Die 45 s "System stabilisieren" stammen aus dem Ursprungsprojekt (Overdrive, 04/2026): zuerst eine Verzögerung der
 * GPU-Pipeline im Daemon, "damit BYD-Kamera-Apps ihre EGL-Flächen zuerst bekommen", dann als fester Aufschub des
 * ganzen Daemon-Starts. Der Grund gilt für den Boot des Kopfgeräts. Deshalb zählen die 45 s ab Boot
 * (SystemClock.elapsedRealtime = Zeit seit dem Boot): Läuft das Gerät schon länger, startet der Kamera-Daemon sofort
 * (nach [MIN_MS]); nach dem Boot wird nur der Rest abgewartet.
 *
 * <h3>Stufen (0.1.38.10, Projektinhaber: Oberfläche zuerst, dann Schritt für Schritt)</h3>
 * 1. [StartStufe.ZUGANG] eigenes tailscaled, NUR wenn `daemons.tailscale` an ist (sonst gibt es die Stufe nicht). Nie
 *    hinter etwas anderes gestellt, sonst sperren wir uns aus. Es wird nur ausgelöst, die Bereitschaft nicht abgewartet.
 * 2. [StartStufe.OBERFLAECHE] Kamera-Daemon = Webserver :8180 und Oberfläche. Wartet auf die Antwort von :8180.
 * 3. [StartStufe.WAECHTER] Sentry und ACC-Sentry, wartet auf deren Prozesse.
 * 4. [StartStufe.REST] Telegram, WireGuard, Rest (Brücke/MQTT stecken im Kamera-Daemon). Nur ausgelöst, und nur wenn
 *    mindestens einer davon an ist.
 * Kein Schritt wartet mit einer festen Pause auf den vorigen, nur auf dessen Bereitschaftsprüfung (mit Zeitlimit).
 * Alle Stufen warten, bis ein laufender Hard-Reset durch ist (Stufe −1, [at.greenchili.surfmate.updater.HardResetTor]).
 * Reine Funktionen, ohne Android – JVM-testbar.
 */
object DaemonStartPlan {
    /** So lange nach dem Boot des Geräts (Uhr seit dem Boot) bleibt das System unruhig. */
    const val BOOT_STABIL_MS = 45_000L

    /** Kürzeste Verzögerung (Daemon-Prozess und Hauptschleife dürfen sich setzen). */
    const val MIN_MS = 1_000L

    /** Health-Check bleibt so viel nach dem Kamera-Start wie bisher (90 s minus 45 s). */
    const val HEALTH_NACH_KAMERA_MS = 45_000L

    /** Abstand der Bereitschaftsprüfungen, die den Ablauf aufhalten. */
    const val PRUEF_TAKT_MS = 500L

    /** Abstand der Beobachtung bei Stufen, die den Ablauf nicht aufhalten (nur für die Anzeige). */
    const val BEOBACHT_TAKT_MS = 2_000L

    /** Einstellungen, aus denen die Reihenfolge folgt. */
    class Einstellungen(@JvmField val tailscaleAn: Boolean, @JvmField val restAn: Boolean = true)

    /**
     * Reihenfolge aus den Einstellungen: Zugang nur mit eingeschaltetem Tailscale, sonst beginnt die Oberfläche; Rest
     * nur, wenn Telegram oder WireGuard an ist.
     */
    @JvmStatic
    fun stufen(e: Einstellungen): List<StartStufe> {
        val l = ArrayList<StartStufe>()
        if (e.tailscaleAn) l.add(StartStufe.ZUGANG)
        l.add(StartStufe.OBERFLAECHE)
        l.add(StartStufe.WAECHTER)
        if (e.restAn) l.add(StartStufe.REST)
        return l
    }

    /**
     * 0.1.43 (S2, Projektinhaber: „Wächter ✗ startet nicht“, obwohl der Wächter absichtlich aus ist): was gewollt laufen soll.
     * Nur für die Anzeige (Startkarte, Ladebildschirm); der Ablauf ([stufen]) bleibt unverändert.
     * @param waechterAn Wächter in den Einstellungen an (`surveillance.surveillanceEnabled`) – dieselbe Einstellung,
     *   ohne die der Kern den Wächter nie einschaltet; sonst zeigt der Chip „Wächter aus“
     * @param telegramAn Telegram in den Einstellungen an (nur Telegram hat in der Stufe REST eine Prüfung)
     * @param gestoppt Dienste, die der Nutzer angehalten hat (Stopp-Marke, wie ifNotUserStopped sie liest)
     */
    class Gewollt(
        @JvmField val waechterAn: Boolean,
        @JvmField val telegramAn: Boolean,
        @JvmField val gestoppt: Set<StartStufe> = emptySet()
    )

    /**
     * Welche Stufen die Anzeige führt: nur, was gewollt laufen soll. Ein gewollt ausgeschalteter oder angehaltener Dienst
     * ist kein Fehler und keine eigene Zeile (wie Zugang ohne Tailscale); „startet nicht“ bleibt für den echten Fall.
     */
    @JvmStatic
    fun anzeige(stufen: List<StartStufe>, g: Gewollt): List<StartStufe> = stufen.filter { s ->
        s !in g.gestoppt && when (s) {
            StartStufe.WAECHTER -> g.waechterAn
            StartStufe.REST -> g.telegramAn
            else -> true
        }
    }

    /**
     * Stopp-Marke des Nutzers? Gleiche Regeln wie die Shell-Prüfung in DaemonStartupManager.ifNotUserStopped:
     * „disabled by ui/telegram/user/12-V-Schlaf“ oder leer = angehalten; andere (Maschinen-)Marken räumt der Start weg.
     * @param ersteZeile erste Zeile der Marke, null = keine Marke
     */
    @JvmStatic
    fun nutzerGestoppt(ersteZeile: String?): Boolean {
        if (ersteZeile == null) return false
        val z = ersteZeile.trim()
        return z.isEmpty() || NUTZER_STOPP.any { z.startsWith(it) }
    }

    private val NUTZER_STOPP = listOf("disabled by ui", "disabled by telegram", "disabled by user", "disabled by 12-V-Schlaf")

    /**
     * Wie lange der Ablauf auf die Bereitschaft dieser Stufe wartet, bevor er mit der nächsten weitermacht; 0 = nur
     * auslösen, nie abwarten (Zugang: 10–40 s Startzeit von tailscaled sollen die Oberfläche nicht aufhalten).
     */
    @JvmStatic
    fun zeitlimitMs(s: StartStufe): Long = when (s) {
        StartStufe.ZUGANG -> 0L
        StartStufe.OBERFLAECHE -> 60_000L
        StartStufe.WAECHTER -> 20_000L
        StartStufe.REST -> 0L
    }

    /**
     * 0.1.43.7: Nachfrist einer aufhaltenden Stufe nach ihrem Zeitlimit ([zeitlimitMs]). Der Ablauf geht nach dem Limit
     * weiter, die Stufe wird aber noch so lange beobachtet, bevor sie als „startet nicht“ gilt (Anzeige, Start-Protokoll
     * `STUFE_STARTET_NICHT`). Grund: Nach einem Update meldete der Wächter 2 s nach seinen 20 s „startet nicht“, obwohl er
     * lief (0.1.43.5 03:17:49, 0.1.43.6 11:59:34) – ein Spätstart, kein Fehler. Ein echter Nichtstart wird nach Limit
     * plus Nachfrist weiter gemeldet.
     */
    @JvmStatic
    fun nachfristMs(s: StartStufe): Long = when (s) {
        StartStufe.OBERFLAECHE -> 60_000L
        StartStufe.WAECHTER -> 60_000L
        else -> 0L
    }

    /** Wie lange eine nicht aufhaltende Stufe nur beobachtet wird (danach „startet nicht“ in der Anzeige). */
    @JvmStatic
    fun beobachtungMs(s: StartStufe): Long = when (s) {
        StartStufe.ZUGANG -> 60_000L
        StartStufe.REST -> 60_000L   // 0.1.43.5: Telegram brauchte 32 s, die Frist von 30 s war 2 s zu kurz
        else -> 0L
    }

    /** @param geraeteLaufzeitMs Zeit seit dem Boot des Geräts (SystemClock.elapsedRealtime) */
    @JvmStatic
    fun kameraVerzoegerungMs(geraeteLaufzeitMs: Long): Long {
        val rest = BOOT_STABIL_MS - geraeteLaufzeitMs.coerceAtLeast(0L)
        return rest.coerceIn(MIN_MS, BOOT_STABIL_MS)
    }
}
