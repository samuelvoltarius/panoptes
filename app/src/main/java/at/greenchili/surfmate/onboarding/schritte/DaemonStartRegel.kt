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
