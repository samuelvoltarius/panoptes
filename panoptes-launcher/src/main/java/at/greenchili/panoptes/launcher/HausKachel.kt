package at.greenchili.panoptes.launcher

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

data class HausGeraet(val entity: String, val titel: String, val raum: String?, val art: String, val zustand: String?)
data class HausStand(val geraete: List<HausGeraet>, val fehlt: List<String>, val alterSek: Long?)

/**
 * 0.1.44 (P4): Kachel „Haus“ – Haus-Status aus Home Assistant, NUR LESEN.
 *
 * Daten kommen allein über den Kern-Weg `GET /api/launcher/v1/haus` (der Kern liest den Bestand der HA-Integration).
 * Im Launcher gibt es keine Zugangsdaten, keine Schlüssel und keinen Schaltweg: weder Tipp noch langer Druck schalten
 * etwas. Der Nutzer wählt bis zu [MAX] Geräte; die Wahl liegt nur lokal im Launcher. Fehlt ein Wert, steht „—“,
 * ein altes Ergebnis wird mit seinem Alter gezeigt, nie als frisch. Die Regeln sind rein und im Unit-Test belegt.
 */
object Haus {
    const val MAX = 6
    const val KEY = "haus_auswahl"
    /** Abfragetakt, solange die Kachel sichtbar angelegt ist. */
    const val TAKT_MS = 15_000L
    /** Ab diesem Alter zeigt die Kachel „vor N min“; ab [VERALTET_SEK] ist sie abgeblendet. */
    const val ALTER_ZEIGEN_SEK = 120L
    const val VERALTET_SEK = 30 * 60L

    enum class Wort { AN, AUS, OFFEN, ZU, VERRIEGELT, ENTRIEGELT, UNBEKANNT }
    data class Zeile(val titel: String, val wort: Wort, val warn: Boolean)

    private val ENTITAET = Regex("^[a-z_]{1,32}\\.[a-z0-9_]{1,128}$")

    /** Gespeicherte Wahl: nur gültige Kennungen, ohne Doppelte, höchstens [MAX]. */
    fun auswahlLesen(roh: String?): List<String> = buildList {
        for (s in roh.orEmpty().split(",")) {
            val e = s.trim()
            if (ENTITAET.matches(e) && e !in this && size < MAX) add(e)
        }
    }

    fun auswahlSchreiben(l: List<String>): String = l.joinToString(",")

    /** Gerät an-/abwählen; ein siebtes wird nicht aufgenommen (Liste bleibt unverändert). */
    fun umschalten(l: List<String>, entity: String): List<String> = when {
        entity in l -> l - entity
        l.size >= MAX || !ENTITAET.matches(entity) -> l
        else -> l + entity
    }

    /** Zustandstext von Home Assistant → Anzeige-Wort. `unavailable`/`unknown`/leer = unbekannt, nie geraten. */
    fun wort(art: String, zustand: String?): Wort {
        val z = zustand?.trim()?.lowercase().orEmpty()
        return when (art) {
            "tor" -> when (z) { "open", "opening" -> Wort.OFFEN; "closed", "closing" -> Wort.ZU; else -> Wort.UNBEKANNT }
            "schloss" -> when (z) { "locked", "locking" -> Wort.VERRIEGELT; "unlocked", "unlocking", "open" -> Wort.ENTRIEGELT; else -> Wort.UNBEKANNT }
            else -> when (z) {
                "on", "heat", "cool", "heat_cool", "auto", "dry", "fan_only" -> Wort.AN
                "off" -> Wort.AUS
                else -> Wort.UNBEKANNT
            }
        }
    }

    /** Auffällig: Tor offen oder Schloss entriegelt. */
    fun warnung(art: String, w: Wort): Boolean = (art == "tor" && w == Wort.OFFEN) || (art == "schloss" && w == Wort.ENTRIEGELT)

    /** Zeilen in der Reihenfolge der Wahl; nicht (mehr) gemeldete Geräte fehlen und werden als „—“ gezeigt. */
    fun zeilen(stand: HausStand?, auswahl: List<String>): List<Zeile> = auswahl.map { id ->
        val g = stand?.geraete?.firstOrNull { it.entity == id }
        if (g == null) Zeile(id.substringAfter('.').replace('_', ' '), Wort.UNBEKANNT, false)
        else {
            val w = wort(g.art, g.zustand)
            Zeile(g.titel, w, warnung(g.art, w))
        }
    }

    /** Wie viele Zeilen bei der Kachelhöhe sichtbar sind (die Wahl bleibt, der Rest sitzt unter „mehr“-Platz). */
    fun sichtbar(h: Int): Int = when { h <= 1 -> 2; h == 2 -> 4; else -> MAX }

    /** Minuten für „vor N min“, nur wenn alt genug; sonst null. */
    fun alterMinuten(alterSek: Long?): Int? = alterSek?.takeIf { it >= ALTER_ZEIGEN_SEK }?.let { (it / 60).toInt() }

    fun veraltet(alterSek: Long?): Boolean = alterSek != null && alterSek >= VERALTET_SEK

    fun parse(json: String?): HausStand? = try {
        val o = JSONObject(json ?: "")
        if (!o.optBoolean("ok", false)) null
        else {
            val a = o.optJSONArray("geraete")
            val liste = buildList {
                for (i in 0 until (a?.length() ?: 0)) {
                    val g = a!!.optJSONObject(i) ?: continue
                    val e = g.optString("entity")
                    if (!ENTITAET.matches(e)) continue
                    add(HausGeraet(e, g.optString("titel", e).ifBlank { e }, g.optString("raum", "").ifBlank { null },
                        g.optString("art", ""), if (g.isNull("zustand")) null else g.optString("zustand")))
                }
            }
            val f = o.optJSONArray("fehlt")
            HausStand(liste, buildList { for (i in 0 until (f?.length() ?: 0)) add(f!!.optString(i)) },
                if (o.isNull("alterSek")) null else o.optLong("alterSek"))
        }
    } catch (_: Exception) {
        null
    }
}

/** Ansicht der Haus-Kachel. Nur Anzeige: kein Schaltweg, MainActivity hängt sie nur ein. */
class HausKachel(
    private val ctx: Context,
    private val ui: Ui,
    private val reg: (() -> Unit) -> Unit,
    private val auswahl: () -> List<String>,
    private val stand: () -> HausStand?
) {
    private fun wortText(w: Haus.Wort): String = when (w) {
        Haus.Wort.AN -> ctx.getString(R.string.haus_an); Haus.Wort.AUS -> ctx.getString(R.string.haus_aus)
        Haus.Wort.OFFEN -> ctx.getString(R.string.haus_offen); Haus.Wort.ZU -> ctx.getString(R.string.haus_zu)
        Haus.Wort.VERRIEGELT -> ctx.getString(R.string.haus_verriegelt); Haus.Wort.ENTRIEGELT -> ctx.getString(R.string.haus_entriegelt)
        Haus.Wort.UNBEKANNT -> ZusatzKacheln.STRICH
    }

    fun ansicht(gross: Boolean, h: Int): View {
        val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(18)) }
        c.addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.icon(R.drawable.ic_pn_home, ui.p.aqua, 20))
            addView(ui.titel(ctx.getString(R.string.widget_haus)).apply { setPadding(ui.dp(8), 0, 0, 0) })
        })
        val inhalt = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        c.addView(inhalt, ui.lp(-1, -2, oben = 6))
        val alter = ui.leise("", 12f)
        c.addView(alter, ui.lp(oben = 4))
        reg {
            inhalt.removeAllViews()
            val wahl = auswahl()
            if (wahl.isEmpty()) {
                inhalt.addView(ui.leise(ctx.getString(R.string.haus_leer), 14f))
                alter.text = ""
            } else {
                val s = stand()
                val abgeblendet = s == null || Haus.veraltet(s.alterSek)
                for (z in Haus.zeilen(s, wahl).take(Haus.sichtbar(h))) {
                    val reihe = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, ui.dp(3), 0, ui.dp(3)) }
                    val name: TextView = ui.text(z.titel, if (gross) 17f else 15f).apply {
                        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                        if (abgeblendet) setTextColor(ui.p.onSurfaceVariant)
                    }
                    reihe.addView(name, ui.lp(0, -2, 1f))
                    reihe.addView(ui.text(wortText(z.wort), if (gross) 17f else 15f, 700,
                        when { z.warn -> ui.p.warning; abgeblendet || z.wort == Haus.Wort.UNBEKANNT -> ui.p.onSurfaceVariant; else -> ui.p.onSurface }))
                    inhalt.addView(reihe)
                }
                val min = Haus.alterMinuten(s?.alterSek)
                alter.text = when {
                    s == null -> ctx.getString(R.string.haus_nicht_lesbar)
                    min != null -> ctx.getString(R.string.haus_alter, min)
                    else -> ""
                }
            }
        }
        return Einpassen(ctx, c)
    }
}
