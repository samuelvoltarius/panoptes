package at.greenchili.surfmate.ui

/**
 * 0.1.38.11 (M1): Was gilt nach einem Neuaufbau der MainActivity (Zündung aus/an, Tag/Nacht, Bildschirmgröße,
 * Prozess neu mit gespeichertem Zustand)? Nie die alte native Oberfläche wiederherstellen – immer die Web-Oberfläche
 * mit Menüleiste. Ein frischer Start (ohne gespeicherten Zustand) beginnt ohnehin beim Startziel.
 */
object NeuaufbauZiel {
    fun zurWeb(neuaufbau: Boolean, alteOberflaecheImStapel: Boolean): Boolean = neuaufbau && alteOberflaecheImStapel
}
