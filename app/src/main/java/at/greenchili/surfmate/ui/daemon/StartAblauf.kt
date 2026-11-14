package at.greenchili.surfmate.ui.daemon

/**
 * Der gestaffelte Start (0.1.38.10) als Ablauf ohne Android: Stufen nacheinander, jede erst nach dem Ende des
 * Hard-Resets ([toroffen]) und nach dem Boot-Schutz, danach nur auf die Bereitschaft der vorigen Stufe (mit Zeitlimit),
 * nie mit fester Pause. Planer, Uhr, Aktion und Prüfung kommen von außen, damit der Ablauf mit einer Attrappen-Uhr
 * geprüft werden kann. Den Zustand je Stufe meldet er über [melde] (aus dem Manager an [StartStatus]).
 *
 * @param bootSchutzMs  Zeit ab [starten], vor der OBERFLAECHE und alle folgenden Stufen nicht starten dürfen
 *                      (Boot-Schutz); der Zugang (Tailscale) ist davon nicht betroffen
 * @param plane         führt die Aktion nach der angegebenen Zeit auf dem Haupt-Thread aus
 * @param toroffen      false, solange ein Hard-Reset läuft (er würde einen frisch gestarteten Daemon töten)
 * @param aktion        löst die Stufe aus (Daemons starten)
 * @param pruefen       fragt asynchron, ob die Stufe bereit ist, und ruft dann den Rückruf mit dem Ergebnis auf
 * @param melde         Zustand je Stufe: STARTET beim Auslösen, BEREIT bzw. STARTET_NICHT nach der Prüfung
 */
class StartAblauf(
    private val stufen: List<StartStufe>,
    private val bootSchutzMs: Long,
    private val plane: (Long, () -> Unit) -> Unit,
    private val jetzt: () -> Long,
    private val toroffen: () -> Boolean,
    private val aktion: (StartStufe) -> Unit,
    private val pruefen: (StartStufe, (Boolean) -> Unit) -> Unit,
    private val abbruch: () -> Boolean = { false },
    private val fertig: () -> Unit = {},
    private val melde: (StartStufe, StufeZustand) -> Unit = { _, _ -> }
) {
    private var t0 = 0L

    fun starten() {
        t0 = jetzt()
        plane(DaemonStartPlan.MIN_MS) { stufe(0) }
    }

    private fun stufe(i: Int) {
        if (abbruch()) return
        if (i >= stufen.size) { fertig(); return }
        val s = stufen[i]
        // Stufe −1: nie vor dem Ende des Hard-Resets (Zeitlimit liegt im Tor selbst). AUSGENOMMEN der Zugang: der
        // Hard-Reset beendet den tailscaled von Weg 1 nicht mehr, der Zugang darf nie vom Update/Tor abhängen.
        if (s != StartStufe.ZUGANG && !toroffen()) { plane(DaemonStartPlan.PRUEF_TAKT_MS) { stufe(i) }; return }
        // Boot-Schutz gilt für alles außer dem Zugang
        if (s != StartStufe.ZUGANG) {
            val rest = bootSchutzMs - (jetzt() - t0)
            if (rest > 0) { plane(rest) { stufe(i) }; return }
        }
        melde(s, StufeZustand.STARTET)
        aktion(s)
        val limit = DaemonStartPlan.zeitlimitMs(s)
        if (limit <= 0L) {
            // nur auslösen: Bereitschaft wird parallel beobachtet (Anzeige), der Ablauf geht sofort weiter
            if (DaemonStartPlan.beobachtungMs(s) > 0L) beobachten(s, jetzt() + DaemonStartPlan.beobachtungMs(s))
            plane(0L) { stufe(i + 1) }
            return
        }
        warten(i, s, jetzt() + limit)
    }

    private fun warten(i: Int, s: StartStufe, bis: Long) {
        plane(DaemonStartPlan.PRUEF_TAKT_MS) {
            if (abbruch()) return@plane
            pruefen(s) { bereit ->
                if (bereit) {
                    melde(s, StufeZustand.BEREIT)
                    stufe(i + 1)
                } else if (jetzt() >= bis) {
                    // 0.1.43.7: Limit erreicht – weiter mit der nächsten Stufe, „startet nicht“ aber erst nach der
                    // Nachfrist (ein Spätstart nach dem Update ist kein Fehler); ohne Nachfrist sofort wie bisher
                    val frist = DaemonStartPlan.nachfristMs(s)
                    if (frist > 0L) beobachten(s, jetzt() + frist) else melde(s, StufeZustand.STARTET_NICHT)
                    stufe(i + 1)
                } else {
                    warten(i, s, bis)
                }
            }
        }
    }

    /** Nur für die Anzeige: meldet BEREIT bzw. nach dem Limit STARTET_NICHT, hält den Ablauf nie auf. */
    private fun beobachten(s: StartStufe, bis: Long) {
        plane(DaemonStartPlan.BEOBACHT_TAKT_MS) {
            if (abbruch()) return@plane
            pruefen(s) { bereit ->
                if (bereit) {
                    melde(s, StufeZustand.BEREIT)
                } else if (jetzt() >= bis) {
                    melde(s, StufeZustand.STARTET_NICHT)
                } else {
                    beobachten(s, bis)
                }
            }
        }
    }
}
