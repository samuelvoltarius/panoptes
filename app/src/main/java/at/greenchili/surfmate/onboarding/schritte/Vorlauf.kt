package at.greenchili.surfmate.onboarding.schritte

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Antworten aus der Zeit vor der USB-Freigabe (Daemon aus, §0 nicht erreichbar).
 * Gespeichert app-privat; sobald §0 antwortet, schickt [nachreichen] sie per POST /api/einrichtung/schritt.
 * Für Android-Schritte (autostart, ueberlagerung, rechte, usb_freigabe) merkt der Vorlauf nur „erledigt“.
 */
class Vorlauf(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun merke(id: String, antwort: JSONObject) {
        prefs.edit().putString(KEY + id, antwort.toString()).putBoolean(ERL + id, true).apply()
    }

    /** „Später“ vor der USB-Freigabe: zählt im Vorlauf als erledigt, geht danach als „später“ an §0. */
    fun merkeSpaeter(ids: List<String>) {
        val e = prefs.edit()
        ids.forEach { e.putBoolean(SP + it, true).putBoolean(ERL + it, true) }
        e.apply()
    }

    fun erledigt(): Set<String> = Einrichtung.VORLAUF.filter { prefs.getBoolean(ERL + it, false) }.toSet()

    /** Blockierend (Hintergrund): offene Vorlauf-Antworten an §0 schicken; bei Erfolg lokal löschen. */
    fun nachreichen() {
        for (id in Einrichtung.VORLAUF) {
            if (prefs.getBoolean(SP + id, false) && EinrichtungApi.spaeter(id).ok) prefs.edit().remove(SP + id).apply()
            val s = prefs.getString(KEY + id, null) ?: continue
            val antwort = try { JSONObject(s) } catch (_: Throwable) { null } ?: continue
            val r = EinrichtungApi.schritt(id, antwort)
            if (r.ok) prefs.edit().remove(KEY + id).apply()
            else Log.w("EinrichtungVorlauf", "Nachreichen $id: HTTP ${r.status}")
        }
    }

    /** Alles vergessen (POST /api/einrichtung/neu bzw. „Einrichtung erneut starten“). */
    fun zuruecksetzen() { prefs.edit().clear().apply() }

    companion object {
        const val PREFS = "surfmate_einrichtung_vorlauf"
        private const val KEY = "antwort_"
        private const val ERL = "erledigt_"
        private const val SP = "spaeter_"
    }
}
