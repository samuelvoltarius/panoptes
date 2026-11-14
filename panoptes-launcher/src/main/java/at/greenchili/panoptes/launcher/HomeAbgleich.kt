package at.greenchili.panoptes.launcher

import org.json.JSONObject

/** Was der Kern zum Startbildschirm wünscht (GET /api/launcher/v1/home). */
data class HomeWunsch(val gewuenscht: Boolean, val behalten: Boolean, val frage: Boolean)

/**
 * Reine Regeln für den Panoptes Launcher als Startbildschirm (0.1.38.7). Der Launcher schaltet seinen EIGENEN
 * Alias [HomeSchalter] ein oder aus (die Shell darf das auf Android 10 nicht); wohin der Kern ihn wünscht, steht im Kern.
 */
object HomeAbgleich {
    fun parse(json: String): HomeWunsch? = try {
        val o = JSONObject(json)
        HomeWunsch(o.optBoolean("gewuenscht", false), o.optBoolean("behalten", false), o.optBoolean("frage", false))
    } catch (_: Exception) {
        null
    }

    /**
     * Wunschzustand des Alias oder null = nichts tun. Kern nicht erreichbar -> nichts ändern (kein Raten). Der Rückweg
     * (Standard zurück auf BYD) liegt im Daemon und braucht diesen Abgleich nicht.
     */
    fun aliasSoll(wunsch: HomeWunsch?, aliasAn: Boolean): Boolean? =
        if (wunsch == null || wunsch.gewuenscht == aliasAn) null else wunsch.gewuenscht

    /**
     * 0.1.43.4 (Paket S): Wurde der Startbildschirm ohne Panoptes im Launcher gewählt und kommt Panoptes dazu, dessen
     * Schalter noch aus ist, übernimmt der Launcher die Wahl in Panoptes (homeAn + behalten), statt sich abzuschalten.
     */
    fun lokalUebernehmen(wunsch: HomeWunsch?, aliasAn: Boolean, lokal: Boolean): Boolean =
        wunsch != null && lokal && aliasAn && !wunsch.gewuenscht

    /** Soll der Launcher jetzt „Behalten?“ fragen: Schalter an, Alias an, noch nicht bestätigt. */
    fun fragen(wunsch: HomeWunsch?, aliasAn: Boolean): Boolean =
        wunsch != null && wunsch.gewuenscht && wunsch.frage && !wunsch.behalten && aliasAn
}
