package at.greenchili.surfmate.einrichtung

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * 0.1.43.20 BYD-Hilfe-Knöpfe: öffnen BYDs eigene Seiten, stellen nichts ein.
 *
 * Actions laut Auto-Fundgrube 24.11.2026 (am Auto per `query-activities` aufgelöst, Manifest ohne Recht):
 * - Autostart-Verwaltung `android.intent.action.BYD_APPSTARTMANAGEMENT` → `com.byd.appstartmanagement/.frame.AppStartManagement`
 * - Berechtigungen `android.intent.action.BYD_PERMISSION` → `com.byd.carsettings` (PermissionActivity)
 * - Versionsinfo `android.intent.action.BYD_VERSION_DETAILS` → `com.byd.carsettings` (DetailsSettings)
 *
 * Community: Andere DiLink-Stände haben die Seiten vielleicht nicht. Löst eine Action nicht zu einer BYD-App auf,
 * gibt es den Knopf nicht ([sichtbare]). Der Klick nutzt genau die aufgelöste Komponente, nie eine fremde App.
 */
object BydSeiten {
    const val AUTOSTART = "autostart"
    const val RECHTE = "rechte"
    const val VERSION = "version"

    /** Reihenfolge = Reihenfolge der Knöpfe. */
    @JvmField
    val ACTIONS: Map<String, String> = linkedMapOf(
        AUTOSTART to "android.intent.action.BYD_APPSTARTMANAGEMENT",
        RECHTE to "android.intent.action.BYD_PERMISSION",
        VERSION to "android.intent.action.BYD_VERSION_DETAILS",
    )

    /**
     * 0.1.43.21 (Koordination 24.11.): „AN heißt bei BYD: Start gesperrt“ ist nur auf DiLink 3 im Dolphin Surf gemessen.
     * Nur dort gilt der feste Satz; überall sonst (anderes Modell, DiLink 4/5, Modell oder Stufe unbekannt) der neutrale,
     * damit sich niemand Panoptes selbst aussperrt. Rein, ohne Android prüfbar.
     */
    @JvmStatic
    fun autostartBelegt(dilink: Int?, modell: String?): Boolean = dilink == 3 && modell == "Dolphin Surf"

    /** [autostartBelegt] für dieses Gerät (Plattform-Erkennung + echte Fahrzeugwahl); bei Fehlern false = neutral. */
    @JvmStatic
    fun autostartBelegt(): Boolean = autostartPlattform().first

    /**
     * 0.1.43.21: {belegt, Begründung fürs Log}. Liest die Fahrzeugwahl frisch (`forceReload`, die App liest sonst
     * einen veralteten Stand je UID). Fehler beim Lesen = nicht belegt.
     */
    @JvmStatic
    fun autostartPlattform(): Pair<Boolean, String> = try {
        val dilink = at.greenchili.surfmate.rueckmeldung.AutoAngaben.dilink()
        val modell = at.greenchili.surfmate.rueckmeldung.AutoAngaben.modell(at.greenchili.surfmate.config.UnifiedConfigManager.forceReload())
        Pair(autostartBelegt(dilink, modell), autostartGrund(dilink, modell))
    } catch (t: Throwable) {
        Pair(false, "nicht belegt: Plattform nicht lesbar (" + t.javaClass.simpleName + ")")
    }

    /** Log-Text zur Entscheidung, z. B. „belegt: DiLink 3 + Dolphin Surf“ oder „nicht belegt: DiLink 5, Modell Seal“. */
    @JvmStatic
    fun autostartGrund(dilink: Int?, modell: String?): String {
        val was = "DiLink " + (dilink?.toString() ?: "unbekannt") + ", Modell " + (modell ?: "unbekannt")
        return if (autostartBelegt(dilink, modell)) "belegt: $was (AN = Start gesperrt gemessen)"
        else "nicht belegt: $was (Bedeutung des BYD-Schalters nur auf DiLink 3 + Dolphin Surf gemessen)"
    }

    /** Nur Seiten aus BYD-Paketen (keine fremde App, die sich auf dieselbe Action legt). */
    @JvmStatic
    fun istBydPaket(paket: String?): Boolean = paket != null && (paket.startsWith("com.byd.") || paket == "com.byd")

    /**
     * Welche Knöpfe erscheinen. [aufloesen] gibt zur Action das Paket der Activity zurück oder null
     * (= `resolveActivity == null`). Rein, damit der Fall „Activity fehlt → kein Knopf“ ohne Android prüfbar ist.
     */
    @JvmStatic
    fun sichtbare(aufloesen: (String) -> String?, nur: Collection<String> = ACTIONS.keys): List<String> =
        ACTIONS.filterKeys { it in nur }.filter { (_, action) ->
            try { istBydPaket(aufloesen(action)) } catch (_: Throwable) { false }
        }.keys.toList()

    /** Explizites Intent auf die aufgelöste BYD-Activity oder null, wenn es sie hier nicht gibt. */
    @JvmStatic
    fun intent(ctx: Context, schluessel: String): Intent? {
        val action = ACTIONS[schluessel] ?: return null
        return try {
            val info = ctx.packageManager.resolveActivity(Intent(action), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
                ?: return null
            if (!istBydPaket(info.packageName) || !info.exported) return null
            Intent(action).setClassName(info.packageName, info.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } catch (_: Throwable) {
            null
        }
    }

    /** Sichtbare Knöpfe auf diesem Gerät. */
    @JvmStatic
    fun verfuegbar(ctx: Context, nur: Collection<String> = ACTIONS.keys): List<String> =
        sichtbare({ action -> aufgeloestesPaket(ctx, action) }, nur)

    private fun aufgeloestesPaket(ctx: Context, action: String): String? {
        val info = ctx.packageManager.resolveActivity(Intent(action), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
            ?: return null
        return if (info.exported) info.packageName else null
    }

    /** Öffnet die Seite. "ok" | "unavailable" | "error". */
    @JvmStatic
    fun oeffnen(ctx: Context, schluessel: String): String {
        val i = intent(ctx, schluessel) ?: return "unavailable"
        return try {
            ctx.startActivity(i)
            "ok"
        } catch (_: Throwable) {
            "error"
        }
    }
}
