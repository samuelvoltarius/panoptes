package at.greenchili.surfmate.ui

/**
 * 0.1.38.11 (M1): Was gilt nach einem Neuaufbau der MainActivity (Zündung aus/an, Tag/Nacht, Bildschirmgröße,
 * Prozess neu mit gespeichertem Zustand)? Nie die alte native Oberfläche wiederherstellen – immer die Web-Oberfläche
 * mit Menüleiste. Ein frischer Start (ohne gespeicherten Zustand) beginnt ohnehin beim Startziel.
 *
 * 0.1.38.14 P1 (Projektinhaber 06.11. „das Design von Overdrive muss raus“): „alt“ ist jedes Ziel außer dem Web-Startziel und
 * den nativen Funktionsflächen (Projektion), nicht nur das Dashboard.
 */
object NeuaufbauZiel {
    fun zurWeb(neuaufbau: Boolean, alteOberflaecheImStapel: Boolean): Boolean = neuaufbau && alteOberflaecheImStapel

    /** Ist [aktuell] eine alte Oberfläche? Startziel und [nativ] (Funktionsflächen) nicht, unbekannt (null) nicht. */
    fun altesZiel(aktuell: Int?, startZiel: Int, nativ: Set<Int>): Boolean =
        aktuell != null && aktuell != startZiel && aktuell !in nativ
}
