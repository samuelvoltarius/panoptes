package at.greenchili.panoptes.launcher

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 0.1.44 (D1 „Fahrzeug-Startseite“): Kacheln „Fahrstatus“ (Tempo-Ring, Gang P/R/N/D, Reichweite, Akku-Balken,
 * vier Reifendrücke) und „Fahrzeug-Ansicht“ (großes, plastisch gezeichnetes Fahrzeug als Blickfang mit Türen,
 * Fenstern, Schloss, Laden; darunter Fenster-Pillen). Nur Kern-Werte, fehlend = „—“. Farben aus dem aktuellen Design,
 * damit auch „Tag/Nacht automatisch“ greift. Auf einer Szene schweben die Karten halbtransparent mit feinem Rand und
 * weichem Schatten; die Fahrzeug-Ansicht steht ohne Karte frei auf der Szene. Die Regeln stehen in [FahrzeugStart].
 *
 * Fenster: nur auf Tipp, nur im Stand, immer mit kurzer Bestätigung; gesendet wird über den engen Kern-Weg
 * `/api/launcher/v1/fenster` (der prüft Stand und Absender noch einmal).
 */
class FahrzeugKacheln(
    private val ctx: Context,
    private val ui: Ui,
    private val reg: (() -> Unit) -> Unit,
    private val summary: () -> LauncherSummary?,
    private val modell: () -> String?,
    private val lack: () -> String?,
    private val fensterSenden: (String, String, (FensterAntwort) -> Unit) -> Unit,
    private val hinweis: (String) -> Unit
) {
    companion object {
        /** Deckung der Glas-Karten auf einer Szene (von 255): dunkel 150, hell 190. Unschärfe dahinter kann Android 10
         *  nicht live (RenderEffect erst ab Android 12); die Szenen sind deshalb hinter den Karten ruhig gehalten. */
        fun glasDeckung(hell: Boolean) = if (hell) 190 else 150

        fun glas(ui: Ui, radius: Float = 26f): Drawable = GradientDrawable().apply {
            cornerRadius = ui.dpf(radius)
            val grund = if (ui.p.hell) Farbe.rgb(0xFFFFFF) else Farbe.rgb(0x0A1824)
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            colors = intArrayOf(Farbe.alpha(Farbe.heller(grund, 0.06f), glasDeckung(ui.p.hell)), Farbe.alpha(grund, glasDeckung(ui.p.hell) - 20))
            setStroke(ui.dp(1), Farbe.alpha(if (ui.p.hell) ui.p.onSurface else Farbe.rgb(0xFFFFFF), if (ui.p.hell) 30 else 38))
        }

        /** Steht die Kachel frei (ohne Karte) bzw. schwebt sie mit Schatten? */
        fun frei(k: Kachel?) = k == Kachel.AUTOANSICHT
        fun schwebt(k: Kachel?, szene: Boolean) = szene && !frei(k)

        /** Hintergrund einer Kachel: Fahrzeug-Ansicht nie mit Karte, auf einer Szene Glas, sonst [standard]. */
        fun karte(k: Kachel?, szene: Boolean, ui: Ui, standard: () -> Drawable): Drawable = when {
            frei(k) -> ColorDrawable(0)
            szene -> glas(ui)
            else -> standard()
        }

        /** Reiterzeile als schwebende Pillen-Leiste (nur auf Szenen). */
        fun pillenLeiste(v: View, ui: Ui) {
            v.background = glas(ui, 44f); v.elevation = ui.dpf(8f)
            v.setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6))
        }
    }

    private val strich = ZusatzKacheln.STRICH

    // ---------- Fahrstatus ----------

    fun status(gross: Boolean): View {
        val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(22), ui.dp(18), ui.dp(22), ui.dp(16)) }
        val oben = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        val ring = TempoRing(ctx, ui)
        oben.addView(ring, ui.lp(ui.dp(if (gross) 156 else 112), ui.dp(if (gross) 156 else 112), rechts = 18))
        val rechts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val gaenge = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        val gangViews = FahrzeugStart.GAENGE.map { g ->
            TextView(ctx).apply {
                text = g; textSize = if (gross) 21f else 17f; typeface = ui.sora(700); gravity = Gravity.CENTER
                setPadding(ui.dp(11), ui.dp(3), ui.dp(11), ui.dp(3))
            }.also { gaenge.addView(it, ui.lp(rechts = 4)) }
        }
        rechts.addView(gaenge)
        val reichweite = ui.zahl(strich, if (gross) 40f else 28f)
        rechts.addView(reichweite, ui.lp(oben = 10))
        rechts.addView(ui.leise(ctx.getString(R.string.fs_reichweite), 13f))
        val akkuZeile = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        val balken = Balken(ctx, ui)
        akkuZeile.addView(balken, ui.lp(0, ui.dp(10), 1f, rechts = 10))
        val akkuText = ui.text(strich, 17f, 700)
        akkuZeile.addView(akkuText)
        rechts.addView(akkuZeile, ui.lp(-1, -2, oben = 10))
        oben.addView(rechts, ui.lp(0, -2, 1f))
        c.addView(oben, ui.lp(-1, 0, 1f))
        val reifen = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        val radNamen = intArrayOf(R.string.rad_vl, R.string.rad_vr, R.string.rad_hl, R.string.rad_hr)
        val drucke = radNamen.map { n ->
            reifen.addView(ui.leise(ctx.getString(n), 12f).apply { setPadding(0, 0, ui.dp(4), 0) })
            ui.text(strich, if (gross) 17f else 15f, 700).also { reifen.addView(it, ui.lp(rechts = 14)) }
        }
        c.addView(reifen, ui.lp(-1, -2, oben = 8))
        reg {
            val s = summary()
            ring.setze(FahrzeugStart.ringAnteil(s?.speedKmh), ZusatzKacheln.tempo(s?.speedKmh) ?: strich)
            val gi = FahrzeugStart.gangIndex(s?.gear)
            gangViews.forEachIndexed { i, v ->
                val an = i == gi
                v.setTextColor(if (an) ui.p.onPrimary else ui.p.onSurfaceVariant)
                v.background = if (an) GradientDrawable().apply { cornerRadius = ui.dpf(12f); setColor(ui.p.aqua) } else null
            }
            reichweite.text = s?.rangeKm?.takeIf { it >= 0 }?.let { Math.round(it).toString() + " km" } ?: strich
            val a = FahrzeugStart.akkuAnteil(s?.socPct)
            akkuText.text = s?.socPct?.takeIf { a != null }?.let { Math.round(it).toString() + " %" } ?: strich
            balken.setze(a, s?.charging?.active == true)
            balken.contentDescription = ctx.getString(R.string.fs_akku) + " " + akkuText.text
            val r = s?.reifen
            val warn = ZusatzKacheln.warnungen(r)
            listOf(r?.vl, r?.vr, r?.hl, r?.hr).forEachIndexed { i, bar ->
                drucke[i].text = FahrzeugStart.druck(bar)
                drucke[i].setTextColor(if (warn[i] != null) ui.p.warning else ui.p.onSurface)
            }
        }
        return c
    }

    // ---------- Fahrzeug-Ansicht ----------

    fun ansicht(): View {
        val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(8)) }
        val kopf = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        val name = ui.text("", 20f, 700).apply { typeface = ui.sora(600) }
        kopf.addView(name, ui.lp(0, -2, 1f))
        val zustand = ui.text("", 15f, 600, ui.p.onSurfaceVariant)
        kopf.addView(zustand)
        c.addView(kopf, ui.lp(-1, -2))
        val bild = AutoAnsicht(ctx, ui)
        c.addView(bild, ui.lp(-1, 0, 1f))
        // 0.1.44 (D1b): Namensnennung der Renderbilder (CC BY 4.0) sichtbar unter dem Auto
        val nennung = ui.leise("", 11f).apply { gravity = Gravity.END; visibility = View.GONE }
        c.addView(nennung, ui.lp(-1, -2))
        val knoepfe = LinearLayout(ctx).apply { gravity = Gravity.CENTER }
        c.addView(knoepfe, ui.lp(-1, -2, oben = 4))
        val leer = ui.leise(ctx.getString(R.string.fenster_unbekannt), 14f).apply { gravity = Gravity.CENTER }
        c.addView(leer, ui.lp(-1, -2, oben = 4))
        var zuletzt: List<String>? = null
        reg {
            val s = summary()
            val z = FahrzeugStart.zustand(s)
            name.text = s?.vehicleName ?: ctx.getString(R.string.widget_autoansicht)
            bild.setze(FahrzeugStart.form(FahrzeugStart.formWahl(ctx), modell()), z, FahrzeugStart.lack(lack(), ui.p))
            // 0.1.44 (D1b): echtes Renderbild aus Panoptes, wenn es für dieses Modell eines gibt; sonst bleibt die Form.
            // Eine eigene Formwahl in den Einstellungen geht vor (dann gezeichnet).
            val m = modell()
            if (FahrzeugStart.formWahl(ctx) == FahrzeugStart.AUTO && FahrzeugModelle.hatBild(m)) {
                Renderbilder.holen(m!!) { bmp -> bild.setzeBild(bmp); nennung.text = FahrzeugModelle.namensnennung(m) ?: ""; nennung.visibility = if (bmp != null) View.VISIBLE else View.GONE }
            } else { bild.setzeBild(null); nennung.visibility = View.GONE }
            val teile = mutableListOf<String>()
            when (z.verriegelt) { true -> teile += ctx.getString(R.string.ansicht_verriegelt); false -> teile += ctx.getString(R.string.ansicht_entriegelt); null -> {} }
            if (z.tuerOffen.values.any { it }) teile += ctx.getString(R.string.ansicht_tuer_offen)
            if (z.laedt) teile += ctx.getString(R.string.ansicht_laedt)
            zustand.text = teile.joinToString(" · ")
            val liste = FahrzeugStart.knoepfe(s?.koerper)
            if (liste != zuletzt) {
                zuletzt = liste
                knoepfe.removeAllViews()
                liste.forEach { f -> knoepfe.addView(pille(f), ui.lp(0, ui.dp(60), 1f, rechts = 8)) }
            }
            for (i in 0 until knoepfe.childCount) {
                val v = knoepfe.getChildAt(i) as? TextView ?: continue
                val f = v.tag as? String ?: continue
                val pct = s?.koerper?.fenster?.get(f)
                v.text = kurz(f) + "  " + (pct?.let { "$it %" } ?: strich)
                v.setTextColor(if (FahrzeugStart.offen(pct)) ui.p.warning else ui.p.onSurface)
                v.contentDescription = name(f) + ", " + (pct?.let { "$it %" } ?: strich)
            }
            leer.visibility = if (liste.isEmpty()) View.VISIBLE else View.GONE
        }
        return c
    }

    /** Fenster-Pille: groß genug für den Finger (60 dp), Glas wie die Karten. */
    private fun pille(f: String) = TextView(ctx).apply {
        tag = f; gravity = Gravity.CENTER; textSize = 16f; typeface = ui.sora(600); maxLines = 1
        background = glas(ui, 30f); foreground = ui.welle()
        isClickable = true; isFocusable = true
        setOnClickListener { fensterTipp(f) }
    }

    private fun kurz(f: String) = ctx.getString(when (f) {
        "vl" -> R.string.rad_vl; "vr" -> R.string.rad_vr; "hl" -> R.string.rad_hl; "hr" -> R.string.rad_hr; else -> R.string.fenster_dach
    })

    private fun name(f: String) = ctx.getString(when (f) {
        "vl" -> R.string.fenster_vl; "vr" -> R.string.fenster_vr; "hl" -> R.string.fenster_hl; "hr" -> R.string.fenster_hr; else -> R.string.fenster_dach
    })

    /** Tipp auf einen Fenster-Knopf: nur im Stand, dann kurze Bestätigung (Öffnen / Schließen / Abbrechen). */
    fun fensterTipp(f: String) {
        val s = summary()
        if (!FahrzeugStart.darfFenster(s?.gear, s?.acc, s?.speedKmh)) { hinweis(ctx.getString(R.string.fenster_nur_stand)); return }
        val pct = s?.koerper?.fenster?.get(f)
        val vorschlag = FahrzeugStart.vorschlag(pct)
        val d = AlertDialog.Builder(ctx).setTitle(name(f))
            .setMessage(pct?.let { ctx.getString(R.string.fenster_jetzt, it) } ?: ctx.getString(R.string.fenster_unbekannt))
            .setPositiveButton(if (vorschlag == "zu") R.string.fenster_zu else R.string.fenster_auf) { _, _ -> senden(f, vorschlag) }
            .setNeutralButton(if (vorschlag == "zu") R.string.fenster_auf else R.string.fenster_zu) { _, _ -> senden(f, if (vorschlag == "zu") "auf" else "zu") }
            .setNegativeButton(R.string.abbrechen, null).show()
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE).forEach {
            d.getButton(it)?.apply { textSize = 20f; minHeight = ui.dp(64) }
        }
    }

    private fun senden(f: String, ziel: String) {
        val s = summary()
        // zwischen Frage und Tipp losgefahren? Dann nicht senden.
        if (!FahrzeugStart.darfFenster(s?.gear, s?.acc, s?.speedKmh)) { hinweis(ctx.getString(R.string.fenster_nur_stand)); return }
        fensterSenden(f, ziel) { a ->
            hinweis(when {
                a.ok -> ctx.getString(R.string.fenster_gesendet, name(f))
                a.grund == "fahrt" -> ctx.getString(R.string.fenster_nur_stand)
                else -> ctx.getString(R.string.fenster_ging_nicht, name(f), a.grund ?: strich)
            })
        }
    }
}

/** Tempo als schlichter Ring (270°) im Launcher-Stil, Zahl in der Mitte; leer, wenn kein Messwert. */
class TempoRing(ctx: Context, private val ui: Ui) : View(ctx) {
    private var anteil: Float? = null
    private var zahl = ZusatzKacheln.STRICH
    private val bahn = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val r = RectF()

    fun setze(a: Float?, z: String) { if (a != anteil || z != zahl) { anteil = a; zahl = z; contentDescription = "$z km/h"; invalidate() } }

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat(); val d = s * 0.07f
        r.set((width - s) / 2f + d, (height - s) / 2f + d, (width + s) / 2f - d, (height + s) / 2f - d)
        bahn.strokeWidth = d; bahn.color = ui.p.line
        c.drawArc(r, 135f, 270f, false, bahn)
        anteil?.let { bahn.color = ui.p.aqua; c.drawArc(r, 135f, 270f * it, false, bahn) }
        text.color = ui.p.onSurface; text.textSize = s * 0.30f; text.typeface = ui.sora(600)
        c.drawText(zahl, width / 2f, height / 2f + s * 0.09f, text)
        text.color = ui.p.onSurfaceVariant; text.textSize = s * 0.10f; text.typeface = ui.manrope(600)
        c.drawText("km/h", width / 2f, height / 2f + s * 0.26f, text)
    }
}

/** Akku-Balken; beim Laden in der Lade-Farbe. Unbekannt = nur die Bahn. */
class Balken(ctx: Context, private val ui: Ui) : View(ctx) {
    private var anteil: Float? = null
    private var laedt = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    fun setze(a: Float?, l: Boolean) { if (a != anteil || l != laedt) { anteil = a; laedt = l; invalidate() } }
    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); r.set(0f, 0f, width.toFloat(), h)
        p.color = ui.p.line; c.drawRoundRect(r, h / 2, h / 2, p)
        anteil?.let {
            r.right = width * it
            p.color = when { laedt -> ui.p.lime; it < 0.15f -> ui.p.warning; else -> ui.p.aqua }
            c.drawRoundRect(r, h / 2, h / 2, p)
        }
    }
}

/**
 * 0.1.44 (D1b): Renderbilder aus Panoptes (`/api/models/art`), je Modell einmal geholt und gemerkt (über alle
 * Neuaufbauten). Nur für [FahrzeugModelle.hatBild]; ohne Antwort bleibt die gezeichnete Form.
 */
object Renderbilder {
    private val bilder = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>()
    private val laeuft = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val haupt = android.os.Handler(android.os.Looper.getMainLooper())

    fun holen(modell: String, fertig: (android.graphics.Bitmap?) -> Unit) {
        val schluessel = FahrzeugModelle.norm(modell)
        bilder[schluessel]?.let { fertig(it); return }
        if (!laeuft.add(schluessel)) return
        io.execute {
            val bmp = try {
                PanoptesClient().fahrzeugBild(modell)?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
            } catch (_: Throwable) { null }
            haupt.post { if (bmp != null) bilder[schluessel] = bmp; laeuft.remove(schluessel); fertig(bmp) }
        }
    }
}

/** Das große Fahrzeug: echtes Renderbild mit Ebenen darunter/darüber, sonst die gezeichnete Form ([FahrzeugStart.zeichne]). */
class AutoAnsicht(ctx: Context, private val ui: Ui) : View(ctx) {
    private var form = FahrzeugStart.Form.KOMPAKT
    private var z = FahrzeugStart.Zustand()
    private var lack = 0
    private var bild: android.graphics.Bitmap? = null
    private val bildPinsel = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val ziel = android.graphics.RectF()

    fun setzeBild(b: android.graphics.Bitmap?) { if (b !== bild) { bild = b; invalidate() } }

    fun setze(neueForm: FahrzeugStart.Form, neu: FahrzeugStart.Zustand, neuerLack: Int) {
        if (neueForm != form || neu != z || neuerLack != lack) { form = neueForm; z = neu; lack = neuerLack; invalidate() }
    }

    override fun onDraw(c: Canvas) {
        if (width <= 0 || height <= 0) return
        bild?.let { b ->
            val l = AndroidLeinwand(c)
            val r = FahrzeugStart.bildRahmen(width.toFloat(), height.toFloat())
            FahrzeugStart.bodenlicht(l, z, ui.p, r)
            ziel.set(r[0], r[1], r[0] + r[2], r[1] + r[3]); c.drawBitmap(b, null, ziel, bildPinsel)
            FahrzeugStart.symboleZeichnen(l, z, ui.p, r)
            contentDescription = context.getString(R.string.widget_autoansicht)
            return
        }
        val s = FahrzeugStart.laenge(width.toFloat(), height.toFloat())
        FahrzeugStart.zeichne(AndroidLeinwand(c), form, z, ui.p, if (lack == 0) FahrzeugStart.lack(null, ui.p) else lack, width / 2f, height * 0.50f, s)
        contentDescription = context.getString(R.string.widget_autoansicht)
    }
}
