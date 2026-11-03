package at.greenchili.surfmate.onboarding.schritte

/**
 * Wann darf MainActivity den Daemon-Start (und damit das Android-Popup „USB-Debugging zulassen?“) aufschieben,
 * bis der Einrichtungsassistent im Schritt usb_freigabe erklärt hat, was gleich kommt?
 *
 * Regel B (nie aussperren, Kern 4 + Koordination 22.10.): Aufschieben NUR, wenn ALLE gelten:
 *   - der Daemon war auf diesem Gerät noch nie autorisiert,
 *   - die Einrichtung ist nicht abgeschlossen,
 *   - :8180 antwortet nicht (es läuft kein Daemon),
 *   - es ist kein Start nach einem Update (Bestand).
 * In jedem anderen Fall startet der Daemon sofort wie bisher. Der Startweg selbst bleibt unverändert,
 * nur der Zeitpunkt des Aufrufs ändert sich. Spätestens wenn der Assistent endet, wird gestartet.
 */
object DaemonStartRegel {

    /** Ohne HTTP-Prüfung entscheidbar: muss überhaupt geprüft werden, ob :8180 antwortet? */
    fun mussPruefen(daemonAutorisiert: Boolean, einrichtungFertig: Boolean, nachUpdate: Boolean): Boolean =
        !daemonAutorisiert && !einrichtungFertig && !nachUpdate

    fun aufschieben(daemonAutorisiert: Boolean, einrichtungFertig: Boolean, daemonAntwortet: Boolean, nachUpdate: Boolean): Boolean =
        mussPruefen(daemonAutorisiert, einrichtungFertig, nachUpdate) && !daemonAntwortet

    /** Blockierend (nie auf dem Main-Thread): antwortet der lokale Daemon über HTTP? Nie über ADB (ADB löst das Popup aus). */
    fun daemonAntwortet(): Boolean = EinrichtungApi.get("/status").status != 0
}

/**
 * Kleine, Android-freie Zustandsmaschine für die USB-Freigabe. Der Systemdialog kann ausbleiben
 * (ADB aus, Dialog verworfen, Hersteller-adbd hängt); deshalb darf „Warte …“ nie endlos sein.
 */
internal class UsbFreigabeAblauf(private val zeitlimitMs: Long = ZEITLIMIT_MS) {
    enum class Zustand { NICHT_UNTERSTUETZT, BEREIT, WARTET, ZEIT_ABGELAUFEN, ERLEDIGT }

    private var angefragtSeit: Long? = null

    fun anfragen(jetztMs: Long) {
        angefragtSeit = jetztMs
    }

    fun zustand(unterstuetzt: Boolean, daemonLaeuft: Boolean, jetztMs: Long): Zustand {
        if (!unterstuetzt) return Zustand.NICHT_UNTERSTUETZT
        if (daemonLaeuft) return Zustand.ERLEDIGT
        val seit = angefragtSeit ?: return Zustand.BEREIT
        return if (jetztMs - seit >= zeitlimitMs) Zustand.ZEIT_ABGELAUFEN else Zustand.WARTET
    }

    companion object {
        const val ZEITLIMIT_MS = 20_000L
    }
}

/** Fail-closed Plattformgrenze: Die Kern-App mit lokalem ADB-Daemon gehört aufs BYD-Infotainment. */
internal object UsbFreigabePlattform {
    fun istBydInfotainment(bydBerechtigungVorhanden: Boolean, hersteller: String?, modell: String?): Boolean {
        if (bydBerechtigungVorhanden) return true
        return listOf(hersteller, modell).any { it?.contains("BYD", ignoreCase = true) == true }
    }
}
