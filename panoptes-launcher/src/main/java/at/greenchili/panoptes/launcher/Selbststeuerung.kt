package at.greenchili.panoptes.launcher

import android.content.Context

/**
 * 0.1.42 (L48, LUECKEN_0142.md Punkt 4): Der Launcher stellt sich selbst ein.
 * - Wechsel nach D → Karte bzw. die gewählte Navi-Ansicht,
 * - Wechsel nach P → Startseite,
 * - Laden beginnt → Seite mit der Lade-Kachel nach vorn.
 * Nur Anzeige und Seitenwechsel, NIE ein Fahrzeugbefehl. Jede Regel einzeln abschaltbar, ab Werk alle AUS.
 *
 * Quelle sind die Kern-Daten: `gear` liefert der Kern nur frisch und nur mit Zündung an (GearMonitor.gangFrisch),
 * sonst null – null zählt nie als Wechsel. Laden = `charging.active` bzw. `acc` = "charging".
 * Reagiert wird nur auf einen echten Wechsel zwischen zwei bekannten Werten (nie beim ersten Wert nach dem Start).
 * Bedient der Fahrer gerade selbst (letzte Berührung vor weniger als [SPERRE_MS]), wird nicht umgeschaltet; der
 * Wechsel wartet und kommt danach nur, wenn sein Anlass noch gilt.
 */
object Selbststeuerung {
    const val SPERRE_MS = 10_000L

    enum class Ziel { KARTE, START, LADEN }

    data class Wahl(val karteBeiD: Boolean = false, val startBeiP: Boolean = false, val ladenVorn: Boolean = false) {
        val irgendeine get() = karteBeiD || startBeiP || ladenVorn
    }

    class Zustand {
        var gang: String? = null
        var laedt: Boolean? = null
        var offen: Ziel? = null
    }

    fun laedt(acc: String?, ladenAktiv: Boolean?): Boolean? = when {
        ladenAktiv == true || acc == "charging" -> true
        ladenAktiv == false || acc != null -> false
        else -> null
    }

    /** Ein Schritt je Kern-Abfrage. Ergebnis: jetzt umschalten auf …, sonst null. */
    fun schritt(z: Zustand, w: Wahl, gang: String?, laedt: Boolean?, jetztMs: Long, letzteBedienungMs: Long): Ziel? {
        if (gang != null) {
            val alt = z.gang
            if (alt != null && alt != gang) when (gang) {
                "D" -> if (w.karteBeiD) z.offen = Ziel.KARTE
                "P" -> if (w.startBeiP) z.offen = Ziel.START
            }
            z.gang = gang
        }
        if (laedt != null) {
            if (z.laedt == false && laedt && w.ladenVorn) z.offen = Ziel.LADEN
            z.laedt = laedt
        }
        val o = z.offen ?: return null
        // Anlass muss noch gelten (und die Regel noch an sein)
        val gilt = when (o) {
            Ziel.KARTE -> w.karteBeiD && z.gang == "D"
            Ziel.START -> w.startBeiP && z.gang == "P"
            Ziel.LADEN -> w.ladenVorn && z.laedt == true
        }
        if (!gilt) { z.offen = null; return null }
        if (jetztMs - letzteBedienungMs < SPERRE_MS) return null
        z.offen = null
        return o
    }

    // ---------- Einstellungen (lokal im Launcher) ----------
    private const val PREFS = "launcher"
    const val KEY_D = "selbst_karte_bei_d"
    const val KEY_P = "selbst_start_bei_p"
    const val KEY_LADEN = "selbst_laden_vorn"
    val SCHLUESSEL = listOf(KEY_D, KEY_P, KEY_LADEN)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun wahl(ctx: Context): Wahl = prefs(ctx).let {
        Wahl(it.getBoolean(KEY_D, false), it.getBoolean(KEY_P, false), it.getBoolean(KEY_LADEN, false))
    }

    fun setzen(ctx: Context, schluessel: String, an: Boolean) {
        if (schluessel in SCHLUESSEL) prefs(ctx).edit().putBoolean(schluessel, an).apply()
    }

    /**
     * 0.1.42 (R1): Startseite aus dem Karten-Split heraus. Im Split zeigt der Launcher nur die Statusspalte; damit die
     * Startseite wirklich erscheint, muss erst der geteilte Bildschirm verlassen werden (wie Knopf „Start“). Die
     * Navi-App läuft dahinter weiter, sie wird nicht beendet.
     */
    fun splitVerlassen(ziel: Ziel, karteImSplit: String?): Boolean = ziel == Ziel.START && karteImSplit != null
}
