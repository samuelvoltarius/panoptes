package at.greenchili.panoptes.launcher

/**
 * 0.1.41 (CP, Alfreds Go 11.11.): Kachel „CarPlay / Android Auto“ auf der Startseite. Reine Regeln ohne Android.
 *
 * - Nur BYDs Werks-Apps (Paket-Inventur Surf); eigene CarPlay-/AA-Empfänger baut Panoptes nicht.
 * - Start immer im Vollbild: am Surf sind beide Activities resizeableActivity=false (Deploy 4, 11.11.), also kein
 *   Split und keine Kartenquelle; force_resizable wird nie gesetzt.
 * - Fehlt der Start-Intent, startet die am Surf belegte Activity ([RUECKFALL]).
 * - Bestehende Startseiten bekommen die Kachel höchstens EINMAL und nur auf freiem Platz; nichts wird verschoben,
 *   das Dock bleibt unberührt. Wer sie entfernt, behält sie weg (Merker im Launcher).
 * - Ohne Werks-App bleibt kein leerer Platz: die Kachel fällt aus dem Layout.
 */
object Projektion {
    /** Reihenfolge der Knöpfe in der Kachel. */
    val PAKETE = listOf(Raster.CARPLAY, Raster.ANDROID_AUTO)

    /** Am Surf belegte Start-Activities (CARPLAY_AA_SURF_0141.md), falls getLaunchIntentForPackage null liefert. */
    val RUECKFALL = mapOf(
        Raster.CARPLAY to "com.byd.carplay.ui.MainActivity",
        Raster.ANDROID_AUTO to "com.google.android.projection.sink.ui.AAPActivity"
    )

    /** Größen beim Ergänzen: erst groß (2×2), sonst 2×1; jeweils die erste Seite mit freiem Platz. */
    val GROESSEN = listOf(2 to 2, 2 to 1)

    /** Knöpfe der Kachel: nur startbare Werks-Apps. */
    fun sichtbar(startbar: (String) -> Boolean): List<String> = PAKETE.filter(startbar)

    data class Abgleich(val layout: StartLayout, val merken: Boolean)

    /**
     * Nach dem Laden der App-Liste. [appsBekannt] = Liste geladen (sonst nichts ändern, damit ein Fehler im
     * PackageManager keine Kachel löscht). Ergebnis: neues Layout (oder dasselbe Objekt) und ob der Einmal-Merker
     * jetzt gesetzt wird.
     */
    fun abgleichen(l: StartLayout, sichtbar: List<String>, schonErgaenzt: Boolean, appsBekannt: Boolean): Abgleich {
        if (!appsBekannt) return Abgleich(l, false)
        if (sichtbar.isEmpty()) return Abgleich(ohneKachel(l), false)
        if (schonErgaenzt) return Abgleich(l, false)
        if (Raster.hatWidget(l, Kachel.PROJEKTION)) return Abgleich(l, true)
        return Abgleich(ergaenzen(l), true)
    }

    /** Auf freien Platz legen (Seiten der Reihe nach), sonst unverändert. Verschiebt nie etwas, Dock bleibt. */
    fun ergaenzen(l: StartLayout): StartLayout {
        if (Raster.hatWidget(l, Kachel.PROJEKTION)) return l
        for (g in GROESSEN) for (s in l.seiten.indices) {
            val neu = Raster.widgetHinzufuegen(l, s, Kachel.PROJEKTION, listOf(g))
            if (neu !== l) return neu
        }
        return l
    }

    /** Kachel entfernen (kein leerer Platz ohne Werks-App); ohne Kachel dasselbe Objekt. */
    fun ohneKachel(l: StartLayout): StartLayout =
        if (!Raster.hatWidget(l, Kachel.PROJEKTION)) l
        else l.copy(seiten = l.seiten.map { s -> s.filter { !(it.typ == ElementTyp.WIDGET && it.kachel == Kachel.PROJEKTION) } })
}
