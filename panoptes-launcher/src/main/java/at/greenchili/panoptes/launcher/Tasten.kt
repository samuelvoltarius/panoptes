package at.greenchili.panoptes.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * 0.1.44 (P1 „Bedienung 50+“, Punkt 1): „Zurück“ und „Letzte App“ für Leiste und Dock.
 *
 * Zwei Wege, je nach Verfügbarkeit, mit sauberem Rückfall:
 * 1. Bedienungshilfe [PanoptesBedienhilfe], die der Nutzer selbst in den Android-Einstellungen einschaltet:
 *    Zurück über die Android-Funktion „Zurück“, Letzte App springt direkt zur vorigen App.
 * 2. Panoptes-Kern (Daemon) auf Tipp: genau die Taste Zurück bzw. Übersicht (Letzte Apps), sonst nichts.
 * Fehlt beides, zeigt die Leiste die Knöpfe nicht, und der Dock-Knopf erklärt, was fehlt.
 */
object Tasten {
    enum class Weg { HILFE, DAEMON, KEINER }

    const val ZURUECK = "zurueck"
    const val LETZTE = "letzte"

    /** Zuletzt gesehen: Kern antwortet (die Leiste und der Launcher fragen ihn ohnehin regelmäßig). */
    @Volatile var kernDa: Boolean = false

    /** Rein: welcher Weg gilt. Die Bedienungshilfe geht vor (direkt, ohne Netz), dann der Kern. */
    fun weg(hilfeAn: Boolean, kernDa: Boolean): Weg = when {
        hilfeAn -> Weg.HILFE
        kernDa -> Weg.DAEMON
        else -> Weg.KEINER
    }

    fun wegJetzt(): Weg = weg(PanoptesBedienhilfe.aktiv != null, kernDa)

    // erst beim ersten Druck anlegen (die reinen Regeln oben laufen so auch ohne Android, z. B. im Unit-Test)
    private val io by lazy { Executors.newSingleThreadExecutor() }
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Taste auslösen; [fertig] bekommt true, wenn ein Weg sie angenommen hat (Hauptthread). */
    fun druecken(ctx: Context, taste: String, client: PanoptesClient, fertig: (Boolean) -> Unit = {}) {
        val app = ctx.applicationContext
        val hilfe = PanoptesBedienhilfe.aktiv
        if (hilfe != null) {
            val ok = if (taste == ZURUECK) hilfe.zurueck() else hilfe.letzteApp()
            if (!ok) Toast.makeText(app, R.string.taste_fehler, Toast.LENGTH_SHORT).show()
            fertig(ok); return
        }
        io.execute {
            val ok = client.taste(taste)
            if (ok) kernDa = true
            main.post {
                if (!ok) Toast.makeText(app, R.string.taste_fehlt, Toast.LENGTH_LONG).show()
                fertig(ok)
            }
        }
    }
}

/**
 * Rein (P1): Verlauf der zuletzt vorn gewesenen Apps (nur Paketnamen, nur im Speicher) für „Letzte App“.
 * Übersprungen wird, was keine echte App-Wahl ist (eigener Launcher, Startbildschirme, Systemoberfläche).
 */
object AppVerlauf {
    const val MAX = 8

    fun merke(liste: List<String>, paket: String?, ignorieren: Set<String>): List<String> {
        if (paket.isNullOrBlank() || paket in ignorieren) return liste
        if (liste.lastOrNull() == paket) return liste
        return (liste.filter { it != paket } + paket).takeLast(MAX)
    }

    /** Die App vor der aktuellen (oberste ist die aktuelle); ist die aktuelle eine übersprungene, die oberste. */
    fun vorige(liste: List<String>, aktuell: String?): String? {
        if (liste.isEmpty()) return null
        return if (aktuell == liste.last()) liste.getOrNull(liste.size - 2) else liste.last()
    }
}
