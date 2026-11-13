package at.greenchili.panoptes.launcher

import android.widget.HorizontalScrollView

/**
 * 0.1.44 Fixrunde nach E2E 0.1.43b (G1, G3, G4): Platzregeln, damit Knöpfe bei jeder Dichte und mit „Große Bedienung“
 * ganz sichtbar bleiben. Rein (bis auf [Leistenlage.einhaengen]), im Unit-Test belegt; MainActivity hängt sie nur ein.
 */
object MusikKartePlatz {
    /** Flache Karte (eine Rasterzeile hoch, z. B. Vorlage Fahrzeug): dort zählt nur „Zugriff erlauben“. */
    fun flach(hoeheZellen: Int): Boolean = hoeheZellen < 2

    /**
     * G1: ohne Zugriff zeigt eine flache Karte nur Titel und den Knopf „Zugriff erlauben“ (quer, niedrig), ohne
     * Hinweissatz und Musiktasten – vorher lag der Knopf unter dem Hinweis und war bis auf einen Streifen abgeschnitten.
     * Der Hinweis bleibt als Beschreibung am Knopf (Sprachausgabe) und in größeren Karten sichtbar.
     */
    fun kompakt(hoeheZellen: Int, erlaubt: Boolean): Boolean = flach(hoeheZellen) && !erlaubt

    /** Rand der flachen Karte in dp (statt 20), damit Titel + Knopf auch bei 240 dpi (≈ 100 dp Zeilenhöhe) passen. */
    const val RAND_FLACH = 12

    /** Knopfhöhe „Zugriff erlauben“ in der flachen Karte (dp, vor „Große Bedienung“). */
    const val KNOPF_FLACH = 48
}

/**
 * G4: Breite der Klima-Leiste im Dock, fest ausgerechnet statt „so breit wie der Inhalt“. Mit „Große Bedienung“ fiel
 * die Leiste aus dem Dock; jetzt bekommt sie ihre feste Breite, und reicht das Dock nicht, die kompakte Form
 * (Temperatur −/+ und an/aus, Gebläse nur im großen Klima-Feld). Alle Maße in dp vor der Dichte.
 */
object DockPlatz {
    const val KNOPF = 64            // MainActivity.DOCK_KNOPF
    const val WERT_TEMP = 98        // Rand 8 + Symbol 22 + 4 + Zahl mind. 64 („21,5°“)
    const val WERT_LUFT = 54        // Rand 8 + Symbol 22 + 4 + Zahl 20
    const val POWER = 52
    const val INNEN = 12            // Innenrand links + rechts

    private fun b(v: Int, bedien: Float) = Math.round(v * bedien)

    /** Breite der Klima-Leiste; [kompakt] = ohne Gebläse. */
    fun klimaBreite(bedien: Float, kompakt: Boolean): Int {
        val k = b(KNOPF, bedien)
        return if (kompakt) 2 * k + 6 + WERT_TEMP + POWER + INNEN
        else 4 * k + 6 + 6 + WERT_TEMP + WERT_LUFT + POWER + INNEN
    }

    /** Feste Teile des Docks: Rand, Startseite, Letzte App, Apps, Einstellungen, Abstand zur Klima-Leiste. */
    fun fest(bedien: Float): Int = 24 + b(84, bedien) + 6 + b(76, bedien) + 6 + b(76, bedien) + 4 + b(76, bedien) + 10

    /** Platz, der für die Lieblings-Apps mindestens bleibt (zwei Knöpfe: eine App und „Hinzufügen“). */
    fun favsMin(bedien: Float): Int = 2 * (b(76, bedien) + 4)

    /** Kompakte Klima-Leiste, wenn die volle bei dieser Dockbreite den Apps den Platz nähme. */
    fun kompakt(breiteDp: Int, bedien: Float): Boolean = fest(bedien) + klimaBreite(bedien, false) + favsMin(bedien) > breiteDp
}

/**
 * G3: Die Bearbeiten-Leiste wird nach jeder Aktion neu gebaut; ihre Scrollposition bleibt hier gemerkt, damit
 * „Einrasten“, „Beschriftung“ und „Auf Vorlage zurücksetzen“ nicht jedes Mal wieder herausgewischt werden müssen.
 */
object Leistenlage {
    @Volatile var x = 0
        private set

    fun merken(neu: Int) { x = neu.coerceAtLeast(0) }
    fun zuruecksetzen() { x = 0 }

    fun einhaengen(h: HorizontalScrollView) {
        h.setOnScrollChangeListener { _, sx, _, _, _ -> merken(sx) }
        val ziel = x
        h.post { h.scrollTo(ziel, 0) }
    }
}
