package at.greenchili.panoptes.launcher

import android.view.Display

/**
 * 0.1.38.8: wann der Launcher arbeitet. Autotest 0.1.38.6: bei schwarzem Display (Zündung aus) lief der
 * 1-Sekunden-Takt weiter und schrieb jede Sekunde alle Texte neu – Strom aus der 12-V-Batterie, und
 * `uiautomator dump` fand nie eine ruhige Sekunde (keine Datei).
 *
 * - Android-Display aus: nichts (keine Abfrage, kein Takt, 3D und Video angehalten).
 * - Zündung aus (Kern `acc` = "off"): BYD schaltet das Panel am Android vorbei ab (Android meldet weiter
 *   Display an, belegt am Surf 04.11.) → kein Takt, 3D und Video angehalten, Abfrage nur alle 10 s, damit das
 *   Einschalten bemerkt wird.
 * - Kein Fokus (geteilter Bildschirm, Dialog): nur die Abfrage, kein Sekundentakt.
 * - Der Sekundentakt zeichnet nur, wenn sich Medien oder die Minute geändert haben.
 */
object Ruhe {
    const val ABFRAGE_MS = 5_000L
    const val ABFRAGE_RUHE_MS = 10_000L

    /** Android-Displayzustand → an? Unbekannt zählt als an (lieber aktuell als eingefroren). */
    fun displayAn(state: Int?): Boolean = when (state) {
        Display.STATE_OFF, Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND, Display.STATE_ON_SUSPEND -> false
        else -> true
    }

    /** Sieht jemand das Bild? Nur ein ausdrückliches "off" der Zündung zählt (unbekannt/Laden = an). */
    fun bildAn(androidDisplayAn: Boolean, acc: String?) = androidDisplayAn && acc != "off"

    fun abfrageLaeuft(gestoppt: Boolean, androidDisplayAn: Boolean) = !gestoppt && androidDisplayAn

    fun abfrageMs(bildAn: Boolean) = if (bildAn) ABFRAGE_MS else ABFRAGE_RUHE_MS

    fun taktLaeuft(gestoppt: Boolean, bildAn: Boolean, fokus: Boolean) = !gestoppt && bildAn && fokus

    /** Was der Sekundentakt anzeigt: Medien (Position auf ganze Sekunden) und die Minute der Uhr. */
    fun taktSchluessel(paket: String?, titel: String?, kuenstler: String?, spielt: Boolean?, positionMs: Long?,
                      dauerMs: Long?, coverId: Int?, jetztMs: Long): String =
        listOf(paket, titel, kuenstler, spielt, positionMs?.div(1000), dauerMs, coverId, jetztMs / 60_000).joinToString("|")
}
