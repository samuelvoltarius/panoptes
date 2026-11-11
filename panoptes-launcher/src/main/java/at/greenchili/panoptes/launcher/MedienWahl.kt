package at.greenchili.panoptes.launcher

import android.media.session.PlaybackState

/**
 * 0.1.41 (LM, Befund CARPLAY_MUSIK_0141.md Abschnitt 4): Welche Mediensitzung zeigt und steuert der Launcher?
 *
 * Reine Regeln, ohne Android-Objekte testbar (Fake-Sitzungen). Reihenfolge:
 * 1. die gerade spielende Sitzung,
 * 2. die Sitzung von CarPlay oder Android Auto (CarPlay meldet „MediaManager“ erst, wenn ein iPhone verbunden ist),
 * 3. die Sitzung des Pakets mit Audiofokus. Wer den Fokus hält, verrät Android einer normalen App nicht; der Launcher
 *    nimmt dafür das zuletzt spielende Paket, das er selbst gesehen hat (BYD nutzt hier eigene Systemrechte),
 * 4. der Rest in der Reihenfolge von Android.
 * Die Telecom-Sitzung „HeadsetMediaButton“ ist immer da, steuert aber keine Musik: Sie fällt immer weg.
 */
object MedienWahl {
    val PROJEKTION = listOf(Raster.CARPLAY, Raster.ANDROID_AUTO)
    val AUSGESCHLOSSEN = setOf("com.android.server.telecom")

    fun <T> waehle(sitzungen: List<T>, paket: (T) -> String, zustand: (T) -> Int?, fokusPaket: String?): T? {
        val brauchbar = sitzungen.filter { paket(it) !in AUSGESCHLOSSEN }
        return brauchbar.firstOrNull { zustand(it) == PlaybackState.STATE_PLAYING }
            ?: PROJEKTION.firstNotNullOfOrNull { p -> brauchbar.firstOrNull { paket(it) == p } }
            ?: fokusPaket?.let { f -> brauchbar.firstOrNull { paket(it) == f } }
            ?: brauchbar.firstOrNull()
    }
}

/**
 * 0.1.41 (LM): Letzte Musik beim Start weiterspielen. Ab Werk AUS. Einmal je Launcher-Start, nur play() auf der
 * zuletzt genutzten Sitzung; gibt es sie (noch) nicht, nur deren App öffnen. Spielt schon etwas: nichts tun.
 */
object Weiterspielen {
    const val VERSUCHE = 3          // Sitzungen erscheinen nach dem Hochfahren mit Verzögerung
    const val ABSTAND_MS = 4_000L

    sealed class Aktion {
        object Nichts : Aktion()
        object Warten : Aktion()
        data class Spielen(val paket: String) : Aktion()
        data class Oeffnen(val paket: String) : Aktion()
    }

    /**
     * @param versuch 1..[VERSUCHE]; vor dem letzten Versuch wird ohne Sitzung gewartet statt die App zu öffnen.
     * @param sitzungen Paket → spielt sie gerade?
     */
    fun entscheiden(an: Boolean, letztesPaket: String?, sitzungen: Map<String, Boolean>, versuch: Int, appDa: Boolean): Aktion {
        if (!an || letztesPaket.isNullOrBlank()) return Aktion.Nichts
        val echte = sitzungen.filterKeys { it !in MedienWahl.AUSGESCHLOSSEN }
        if (echte.values.any { it }) return Aktion.Nichts
        if (letztesPaket in echte) return Aktion.Spielen(letztesPaket)
        if (versuch < VERSUCHE) return Aktion.Warten
        return if (appDa) Aktion.Oeffnen(letztesPaket) else Aktion.Nichts
    }

    /** Welches Paket merkt sich der Launcher als „zuletzt genutzt“? Nur echte, spielende Sitzungen. */
    fun merken(paket: String?, spielt: Boolean): String? =
        paket?.takeIf { spielt && it !in MedienWahl.AUSGESCHLOSSEN && it.isNotBlank() }
}
