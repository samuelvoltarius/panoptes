package at.greenchili.panoptes.launcher

import android.content.Context

/**
 * 0.1.44 (P3, LUECKEN_0142 Punkt 6): Ist der Kern weg, zeigen die Kacheln die letzten Werte weiter, aber nie als
 * frisch: oben steht „zuletzt vor X min“. Werte, die sich im Sekundentakt ändern oder die Sicherheit betreffen
 * (Tempo, Gang, Tempolimit, Ladeleistung), werden NIE alt gezeigt, sondern als „—“. Reine Regeln, JVM-testbar.
 */
object Frische {
    /** Älter als das zeigt der Launcher gar nichts Altes mehr (wie bisher „—“). */
    const val HOECHSTENS_MS = 6 * 60 * 60_000L

    /** Alte Werte für die Anzeige: Live-Werte raus. */
    fun veraltet(s: LauncherSummary): LauncherSummary = s.copy(
        speedKmh = null, gear = null, tempolimit = null,
        charging = s.charging?.copy(kw = null, etaMin = null)
    )

    /** Ganze Minuten seit dem letzten guten Stand (0 = unter einer Minute). */
    fun minuten(seitMs: Long, jetztMs: Long): Long = ((jetztMs - seitMs).coerceAtLeast(0L)) / 60_000L

    /** Merkt den letzten guten Stand je Activity. */
    class Merker {
        private var letzte: LauncherSummary? = null
        private var zeitMs = 0L

        fun gut(s: LauncherSummary?, jetztMs: Long) {
            if (s == null) return
            letzte = s; zeitMs = jetztMs
        }

        /** Was die Kacheln bei Kern-Aus zeigen: alter Stand ohne Live-Werte, oder null (nichts bekannt / zu alt). */
        fun alt(jetztMs: Long): LauncherSummary? =
            letzte?.takeIf { jetztMs - zeitMs in 0..HOECHSTENS_MS }?.let { veraltet(it) }

        /** Hinweis für oben, leer wenn online oder nichts Altes gezeigt wird. */
        fun hinweis(ctx: Context, online: Boolean, jetztMs: Long): String {
            if (online || alt(jetztMs) == null) return ""
            val m = minuten(zeitMs, jetztMs)
            return if (m < 1) ctx.getString(R.string.zuletzt_gerade) else ctx.getString(R.string.zuletzt_vor_min, m)
        }
    }
}
