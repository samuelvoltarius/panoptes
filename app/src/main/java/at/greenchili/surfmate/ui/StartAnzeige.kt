package at.greenchili.surfmate.ui

import at.greenchili.surfmate.ui.daemon.StartStufe
import at.greenchili.surfmate.ui.daemon.StufeZustand

/**
 * 0.1.38.10: Zeilen je Dienst unter dem Ladekreis von „Panoptes startet …“ (aus [at.greenchili.surfmate.ui.daemon.StartStatus]).
 * Rein, JVM-testbar; die Texte kommen aus den Android-Ressourcen (5 Sprachen).
 */
object StartAnzeige {
    fun zeichen(z: StufeZustand): String = when (z) {
        StufeZustand.BEREIT -> "✓"
        StufeZustand.STARTET -> "…"
        StufeZustand.STARTET_NICHT -> "✗"
    }

    /** Eine Zeile je Stufe: „✓ Oberfläche: bereit“; leer, wenn kein Start bekannt ist (dann bleibt die Zeile weg). */
    fun text(stand: List<Pair<StartStufe, StufeZustand>>, name: (StartStufe) -> String, zustand: (StufeZustand) -> String): String =
        stand.joinToString("\n") { (s, z) -> zeichen(z) + "  " + name(s) + ": " + zustand(z) }
}
