package at.greenchili.panoptes.launcher

/**
 * 0.1.39 (L1, Alfreds Go): „Karte + Status auf einem Bildschirm“. Reine Regeln ohne Android, damit sie im Unit-Test laufen.
 *
 * - Standard der Navi-App bei „Automatisch“: Google Maps, falls installiert, sonst das Werksnavi; dann die Tacho-Wahl und
 *   die übrigen bekannten Navi-Apps. Eine selbst gewählte App bleibt immer zuerst (nichts wird still umgestellt).
 * - Die Rückfall-Leiste (Karte im Vollbild, weil die App nicht teilbar ist) zeigt sich nie im Rückwärtsgang
 *   (Rückfahrkamera) und nur mit erteiltem Overlay-Recht.
 */
object KartenStatus {
    const val GOOGLE_MAPS = "com.google.android.apps.maps"
    const val WERKSNAVI = "com.telenav.app.arp"

    /** Reihenfolge der Navi-Wahl: eigene Wahl, dann Google Maps, Werksnavi, Tacho-Wahl, übrige bekannte. */
    fun naviReihenfolge(eigen: String?, tacho: String?, bekannt: List<String>): List<String> =
        (listOfNotNull(eigen) + listOf(GOOGLE_MAPS, WERKSNAVI) + listOfNotNull(tacho) + bekannt).distinct()

    /** Leiste sichtbar? Nur wenn eingeschaltet, mit Overlay-Recht, und nicht im Rückwärtsgang (Rückfahrkamera). */
    fun leisteZeigen(aktiv: Boolean, overlayErlaubt: Boolean, gang: String?): Boolean =
        aktiv && overlayErlaubt && gang != "R"

    /** Grund eines Split-Abbruchs fürs Log (R/AVM am Auto belegen): Ergebnis des Kern-Wegs bzw. vom System beendet. */
    fun abbruchGrund(ergebnis: NebenErgebnis?, gang: String?): String = when (ergebnis) {
        NebenErgebnis.NICHT_TEILBAR -> "nicht_teilbar"
        NebenErgebnis.VOLLBILD -> "vollbild"
        NebenErgebnis.FEHLER -> "fehler"
        NebenErgebnis.OK, null -> if (gang == "R") "system_r" else "system"
    }

    /**
     * Neuer Split-Versuch nach einem Abbruch im Rückwärtsgang: höchstens EINMAL, erst wenn R verlassen ist und seitdem
     * [ruheMs] vergangen sind. Keine Schleife.
     */
    fun nochmalVersuchen(abbruchBeiR: Boolean, schonVersucht: Boolean, gang: String?, ruheSeitMs: Long?, jetztMs: Long,
                         ruheMs: Long = 5_000L): Boolean =
        abbruchBeiR && !schonVersucht && gang != "R" && ruheSeitMs != null && jetztMs - ruheSeitMs >= ruheMs

    /** 0.1.40 (E2E 240 dpi): Beschriftung angeschnitten (braucht mehr Pixel als da sind) → Knöpfe nur mit Symbol. */
    fun nurSymbole(textPx: Float, platzPx: Int): Boolean = textPx > platzPx + 0.5f

    /** 0.1.40 (E2E G4): Zeile der Rückfall-Leiste nach der Auswahl der Statusspalte (Akku, Reichweite, Gang, Uhr). */
    fun leistenZeile(spalte: List<String>, socPct: Double?, rangeKm: Double?, gang: String?, uhr: String?): String {
        val z = zeile(socPct.takeIf { "akku" in spalte }, rangeKm.takeIf { "reichweite" in spalte }, gang.takeIf { "gang" in spalte })
        val u = uhr?.takeIf { "uhr" in spalte } ?: return z
        return if (z == "—") u else "$z · $u"
    }

    /** Statuszeile „78 % · 312 km · D“; fehlende Werte fallen weg, der Gang nur, wenn er frisch gemeldet ist. */
    fun zeile(socPct: Double?, rangeKm: Double?, gang: String?): String {
        val t = mutableListOf<String>()
        socPct?.let { t += "${Math.round(it)} %" }
        rangeKm?.let { t += "${Math.round(it)} km" }
        if (gang != null && gang.length == 1) t += gang
        return if (t.isEmpty()) "—" else t.joinToString(" · ")
    }
}
