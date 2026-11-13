package at.greenchili.panoptes.launcher

import android.view.View
import android.view.ViewTreeObserver

/**
 * 0.1.44 (P3, LUECKEN_0142 Punkt 6): Messpunkte für „schnell/flüssig“, ins Log (logcat, Tag [TAG]) als eine Zeile
 * `PANOPTES_MESSUNG art=… ms=…`. Gemessen wird nur, nichts wird verändert:
 * - `start_bis_sichtbar`: Activity-Start (onCreate) bis zum ersten gezeichneten Bild
 * - `haus_bis_sichtbar`: Rückkehr (onStart, z. B. Haus-Taste) bis zum ersten gezeichneten Bild
 * - `start_bis_werte` / `haus_bis_werte`: bis die ersten Kachelwerte vom Kern da sind
 * - `kern_wieder`: wie lange der Kern weg war, sobald er wieder antwortet
 * Die Rechnung ist rein ([Uhr]), JVM-testbar; Android nur in [beiSichtbar] und [log].
 */
object Messpunkte {
    const val TAG = "PanoptesMessung"

    /** Rechnet die Abstände; gibt die Logzeile zurück (oder null, wenn nichts zu melden ist). */
    class Uhr {
        private var art = ""
        private var beginnMs = -1L
        private var sichtbarGemeldet = true
        private var werteGemeldet = true
        private var kernAusSeit = -1L

        /** Kaltstart der Activity. */
        fun start(jetztMs: Long) = beginn("start", jetztMs)

        /** Wieder vorn (Haus-Taste o. ä.); ein laufender Kaltstart wird nicht überschrieben. */
        fun haus(jetztMs: Long) { if (art != "start" || sichtbarGemeldet) beginn("haus", jetztMs) }

        private fun beginn(a: String, jetztMs: Long) {
            art = a; beginnMs = jetztMs; sichtbarGemeldet = false; werteGemeldet = false
        }

        fun sichtbar(jetztMs: Long): String? {
            if (sichtbarGemeldet || beginnMs < 0) return null
            sichtbarGemeldet = true
            return zeile("${art}_bis_sichtbar", jetztMs - beginnMs)
        }

        /** Nach jeder Kern-Abfrage: erste Werte und Wiederkehr des Kerns. */
        fun daten(ok: Boolean, jetztMs: Long): List<String> {
            val out = ArrayList<String>(2)
            if (ok) {
                if (!werteGemeldet && beginnMs >= 0) { werteGemeldet = true; out.add(zeile("${art}_bis_werte", jetztMs - beginnMs)) }
                if (kernAusSeit >= 0) { out.add(zeile("kern_wieder", jetztMs - kernAusSeit)); kernAusSeit = -1L }
            } else if (kernAusSeit < 0) {
                kernAusSeit = jetztMs
            }
            return out
        }
    }

    fun zeile(art: String, ms: Long): String = "PANOPTES_MESSUNG art=$art ms=${ms.coerceAtLeast(0L)}"

    fun log(zeile: String?) {
        if (zeile != null) runCatching { android.util.Log.i(TAG, zeile) }
    }

    /** Einmal beim nächsten gezeichneten Bild [fertig] aufrufen. */
    fun beiSichtbar(v: View, fertig: () -> Unit) {
        val vto = v.viewTreeObserver
        val l = object : ViewTreeObserver.OnDrawListener {
            var erledigt = false
            override fun onDraw() {
                if (erledigt) return
                erledigt = true
                fertig()
                v.post { runCatching { v.viewTreeObserver.removeOnDrawListener(this) } }
            }
        }
        runCatching { vto.addOnDrawListener(l) }
    }
}
