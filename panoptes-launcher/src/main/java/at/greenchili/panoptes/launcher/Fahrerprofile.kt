package at.greenchili.panoptes.launcher

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import org.json.JSONObject

/**
 * 0.1.44 (P4): Fahrerprofile im Launcher. Ab Werk EIN Profil; ein zweites legt der Nutzer selbst an (Kopie des
 * aktuellen Layouts). Jedes Profil hat sein eigenes Layout samt Design (Hell/Dunkel, Akzent, Hintergrund, Abstand –
 * das gehört alles zum [StartLayout]). Der Wechsel geschieht per Tipp (Kachel „Profil“ oder Einstellungen), optional von
 * selbst nach der gespeicherten Sitzposition.
 *
 * Die Sitzposition wird NUR GELESEN: der Kern meldet über `GET /api/launcher/v1/sitz`, welche gespeicherte Position
 * gerade passt. Der Launcher setzt nie etwas am Sitz und schaltet nur seine eigene Anzeige um. Von selbst wechselt er
 * nur im Stand (Gang P) und nur, wenn sich die erkannte Position ÄNDERT (nie gegen einen Tipp des Fahrers an).
 * Standard AUS. Alles rein und im Unit-Test belegt; die Speicherung liegt lokal im Launcher (SharedPreferences).
 */
object Fahrerprofile {
    const val MAX = 2
    private const val PREFS = "launcher"
    const val KEY_AKTIV = "profil_aktiv"
    const val KEY_ZWEI = "profil_2_da"
    const val KEY_AUTO = "profil_auto"
    /** Abfragetakt der Sitzposition (der Kern fragt das Fahrzeug höchstens alle 30 s). */
    const val SITZ_TAKT_MS = 60_000L

    data class Stand(
        val aktiv: Int = 1,
        val zweitesDa: Boolean = false,
        val sitz1: String? = null,
        val sitz2: String? = null,
        val automatisch: Boolean = false
    )

    data class SitzInfo(val positionen: List<Pair<String, String>>, val aktuell: String?, val lesbar: Boolean)

    fun layoutKey(n: Int) = "profil_${n}_layout"
    fun sitzKey(n: Int) = "profil_${n}_sitz"

    /** Wechsel erlaubt: Ziel 1 oder 2, nicht das aktive, und 2 nur wenn angelegt. */
    fun moeglich(st: Stand, nach: Int): Boolean = nach in 1..MAX && nach != st.aktiv && (nach == 1 || st.zweitesDa)

    /** Das andere Profil (für den Tipp-Wechsel), null solange es nur eines gibt. */
    fun anderes(st: Stand): Int? = if (st.zweitesDa) (if (st.aktiv == 1) 2 else 1) else null

    fun sitzVon(st: Stand, n: Int): String? = if (n == 1) st.sitz1 else st.sitz2

    /**
     * Soll von selbst gewechselt werden? Nur bei eingeschaltetem Schalter, angelegtem zweiten Profil, Gang P (Tempo
     * höchstens 0,5 km/h), erkannter Position, die sich seit der letzten Abfrage GEÄNDERT hat und genau einem Profil
     * zugeordnet ist. Ergebnis: Zielprofil oder null.
     */
    fun ziel(st: Stand, aktuelleSitzId: String?, letzteSitzId: String?, gang: String?, tempoKmh: Double?): Int? {
        if (!st.automatisch || !st.zweitesDa || gang != "P") return null
        if (tempoKmh != null && tempoKmh > 0.5) return null
        if (aktuelleSitzId == null || aktuelleSitzId == letzteSitzId) return null
        val passend = (1..MAX).filter { sitzVon(st, it) == aktuelleSitzId }
        val n = passend.singleOrNull() ?: return null
        return n.takeIf { moeglich(st, it) }
    }

    /** Lohnt sich die Abfrage? (nur wenn sie etwas bewirken könnte und lange genug her ist) */
    fun sitzAbfragen(st: Stand, gang: String?, tempoKmh: Double?, jetztMs: Long, letzteAbfrageMs: Long): Boolean =
        st.automatisch && st.zweitesDa && gang == "P" && (tempoKmh == null || tempoKmh <= 0.5) &&
            jetztMs - letzteAbfrageMs >= SITZ_TAKT_MS

    fun parseSitz(json: String?): SitzInfo? = try {
        val o = JSONObject(json ?: "")
        if (!o.optBoolean("ok", false)) null
        else {
            val a = o.optJSONArray("positionen")
            SitzInfo(buildList {
                for (i in 0 until (a?.length() ?: 0)) {
                    val p = a!!.optJSONObject(i) ?: continue
                    val id = p.optString("id")
                    if (id.isNotEmpty()) add(id to p.optString("name", id).ifBlank { id })
                }
            }, if (o.isNull("aktuell")) null else o.optString("aktuell").ifEmpty { null }, o.optBoolean("lesbar", false))
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Die Profil-Kachel (1×1) gehört in beide Layouts, damit man immer zurückwechseln kann: auf der ersten Seite mit
     * Platz, sonst auf einer neuen Seite; ist auch das nicht möglich, bleibt das Layout unverändert (Wechsel dann über
     * die Einstellungen).
     */
    fun mitKachel(l: StartLayout): StartLayout {
        if (Raster.hatWidget(l, Kachel.PROFIL)) return l
        for (i in l.seiten.indices) {
            val n = Raster.widgetHinzufuegen(l, i, Kachel.PROFIL, listOf(1 to 1))
            if (n !== l) return n
        }
        val s = Raster.seiteHinzufuegen(l)
        return if (s === l) l else Raster.widgetHinzufuegen(s, s.seiten.size - 1, Kachel.PROFIL, listOf(1 to 1))
    }

    // ---------- lokale Speicherung ----------

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun stand(ctx: Context): Stand = prefs(ctx).let {
        Stand(it.getInt(KEY_AKTIV, 1).coerceIn(1, MAX), it.getBoolean(KEY_ZWEI, false),
            it.getString(sitzKey(1), null), it.getString(sitzKey(2), null), it.getBoolean(KEY_AUTO, false))
    }

    /** Zweites Profil anlegen als Kopie von [aktuell]; liefert das aktuelle Layout (jetzt mit Profil-Kachel). */
    fun anlegen(ctx: Context, aktuell: StartLayout): StartLayout? {
        val st = stand(ctx)
        if (st.zweitesDa || st.aktiv != 1) return null
        val mit = mitKachel(aktuell)
        prefs(ctx).edit().putString(layoutKey(2), Raster.toJson(mit)).putBoolean(KEY_ZWEI, true).apply()
        return mit
    }

    /** Zweites Profil löschen (nur wenn Profil 1 aktiv ist). Das Layout von Profil 1 bleibt unberührt. */
    fun entfernen(ctx: Context): Boolean {
        val st = stand(ctx)
        if (!st.zweitesDa || st.aktiv != 1) return false
        prefs(ctx).edit().remove(layoutKey(2)).remove(sitzKey(2)).remove(sitzKey(2) + "name").putBoolean(KEY_ZWEI, false).apply()
        return true
    }

    /**
     * Zu Profil [nach] wechseln: das aktuelle Layout bleibt beim bisherigen Profil, das gespeicherte des Ziels wird
     * geliefert. Null = nicht möglich oder Ziel-Layout unlesbar (dann ändert sich nichts).
     */
    fun wechseln(ctx: Context, aktuell: StartLayout, nach: Int): StartLayout? {
        val st = stand(ctx)
        if (!moeglich(st, nach)) return null
        val ziel = Raster.fromJson(prefs(ctx).getString(layoutKey(nach), null)) ?: return null
        prefs(ctx).edit().putString(layoutKey(st.aktiv), Raster.toJson(aktuell)).putInt(KEY_AKTIV, nach).apply()
        return ziel
    }

    /** Zugeordnete Sitzposition (Kennung des Kerns und Anzeigename) eines Profils; null = keine. */
    fun sitzSetzen(ctx: Context, n: Int, id: String?, name: String?) {
        if (n !in 1..MAX) return
        prefs(ctx).edit().apply {
            if (id == null) { remove(sitzKey(n)); remove(sitzKey(n) + "name") }
            else { putString(sitzKey(n), id); putString(sitzKey(n) + "name", name ?: id) }
        }.apply()
    }

    fun sitzName(ctx: Context, n: Int): String? = prefs(ctx).getString(sitzKey(n) + "name", null)

    fun autoSetzen(ctx: Context, an: Boolean) = prefs(ctx).edit().putBoolean(KEY_AUTO, an).apply()
}

/** Kachel „Profil“ (1×1/2×1): zeigt das aktive Profil, ein Tipp wechselt zum anderen. Nur eigene Anzeige, kein Fahrzeugbefehl. */
class ProfilKachel(
    private val ctx: Context,
    private val ui: Ui,
    private val reg: (() -> Unit) -> Unit,
    private val stand: () -> Fahrerprofile.Stand
) {
    fun name(n: Int): String = ctx.getString(if (n == 1) R.string.profil_name_1 else R.string.profil_name_2)

    fun ansicht(): View {
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
        }
        c.addView(ui.icon(R.drawable.ic_pn_auto_seite, ui.p.aqua, 24))
        val t = ui.text("", 15f, 700).apply { gravity = Gravity.CENTER }
        c.addView(t, ui.lp(-1, -2, oben = 4))
        reg {
            val st = stand()
            t.text = name(st.aktiv)
            t.alpha = if (st.zweitesDa) 1f else 0.6f
        }
        return Einpassen(ctx, c)
    }
}
