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
    private val hinweis: (String) -> Unit,
    /** 0.1.43.3 (L3): Kern erreichbar? (Tempolimit nur frisch und online, [Tempolimit.anzeige]) */
    private val online: () -> Boolean = { false }
) {
    companion object {
        /** Deckung der Glas-Karten auf einer Szene (von 255): dunkel 150, hell 190. Unschärfe dahinter kann Android 10
         *  nicht live (RenderEffect erst ab Android 12); die Szenen sind deshalb hinter den Karten ruhig gehalten. */
        fun glasDeckung(hell: Boolean) = if (hell) 190 else 150

        /** 0.1.43.3 (L3): Glas-Karte aus den Design-Bausteinen ([Glas], [Ui.glas]); Radius auf die drei Stufen. */
        fun glas(ui: Ui, radius: Float = Mass.RADIUS_GROSS.toFloat()): Drawable = ui.glas(Mass.radius(Math.round(radius)))

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
            v.background = glas(ui, Mass.RADIUS_RUND.toFloat()); v.elevation = ui.dpf(Glas.schatten(ui.p.hell).toFloat())
            v.setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6))
        }
    }

    private val strich = ZusatzKacheln.STRICH

    // ---------- Fahrstatus ----------

    /**
     * 0.1.43.3 (L3): Kopf „FAHRSTATUS“ (rechts das Tempolimit, nur belegt und frisch), Tempo-Ring links, rechts die
     * Gangpillen P R N D und die Reichweite groß; darunter Akku mit Balken über die volle Breite und die vier Reifendrücke
     * als 2 × 2. Fehlend = „—“, nie geraten.
     */
    fun status(gross: Boolean): View {
        val k = if (gross) 1.15f else 1f
        val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(10)) }
        val kopf = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        kopf.addView(ui.stufe(Stufe.BESCHRIFTUNG, ctx.getString(R.string.widget_fahrstatus)), ui.lp(0, -2, 1f))
        val limit = Tempolimit.schild(ctx, ui, 30)
        val limitText = ui.stufe(Stufe.PLAKETTE, ctx.getString(R.string.fs_tempolimit), ui.p.onSurface).apply { visibility = View.GONE; setPadding(ui.dp(8), 0, 0, 0) }
        kopf.addView(limit); kopf.addView(limitText)
        c.addView(kopf, ui.lp(-1, ui.dp(22)))
        val oben = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        val ring = TempoRing(ctx, ui)
        val rg = Math.round(88 * k)
        oben.addView(ring, ui.lp(ui.dp(rg), ui.dp(rg), rechts = Mass.M + 4))
        val rechts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val gaenge = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        val gangViews = FahrzeugStart.GAENGE.map { g ->
            TextView(ctx).apply {
                text = g; textSize = 16f * k; typeface = ui.sora(700); gravity = Gravity.CENTER; includeFontPadding = false
            }.also { gaenge.addView(it, ui.lp(ui.dp(Math.round(30 * k)), ui.dp(Math.round(28 * k)), rechts = 6)) }
        }
        rechts.addView(gaenge)
        rechts.addView(ui.stufe(Stufe.BESCHRIFTUNG, ctx.getString(R.string.fs_reichweite)), ui.lp(oben = Mass.S))
        val rwZeile = LinearLayout(ctx).apply { gravity = Gravity.BOTTOM }
        val reichweite = ui.stufe(Stufe.ZAHL, strich).apply { if (gross) textSize = Stufe.ZAHL.sp * k }
        rwZeile.addView(reichweite)
        val km = ui.stufe(Stufe.KLEIN, "km").apply { textSize = 15f; setPadding(ui.dp(4), 0, 0, ui.dp(3)) }
        rwZeile.addView(km)
        LeerWert.ruhig(reichweite, ui.p.onSurface, ui.leiseFarbe)
        rechts.addView(rwZeile, ui.lp(oben = 2))
        oben.addView(rechts, ui.lp(0, -2, 1f))
        c.addView(oben, ui.lp(-1, -2, oben = 2))
        c.addView(View(ctx), ui.lp(-1, 0, 1f))   // Rest der Kachelhöhe verteilt sich hier, nichts wird gequetscht
        // Akku: Symbol, Wort, rechts der Wert; Balken über die volle Breite
        val akkuZeile = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        akkuZeile.addView(ui.icon(R.drawable.ic_pn_battery, ui.p.onSurface, 20), ui.lp(ui.dp(20), ui.dp(20), rechts = 8))
        akkuZeile.addView(ui.text(ctx.getString(R.string.fs_akku), 15f, 700), ui.lp(0, -2, 1f))
        val akkuText = ui.stufe(Stufe.WERT, strich).apply { textSize = 17f }
        akkuZeile.addView(akkuText)
        c.addView(akkuZeile, ui.lp(-1, -2, oben = Mass.XS))
        val balken = Balken(ctx, ui)
        c.addView(balken, ui.lp(-1, ui.dp(8), oben = Mass.XS + 2))
        // Reifen 2 × 2
        val radNamen = intArrayOf(R.string.rad_vl, R.string.rad_vr, R.string.rad_hl, R.string.rad_hr)
        val drucke = ArrayList<TextView>()
        for (zeile in 0..1) {
            val z = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
            for (sp in 0..1) {
                val n = radNamen[zeile * 2 + sp]
                z.addView(ui.leise(ctx.getString(n), 12f).apply { minWidth = ui.dp(28) })
                z.addView(ui.stufe(Stufe.WERT, strich).apply { textSize = 16f * k; setPadding(0, 0, 0, ui.dp(4)) }.also { drucke += it }, ui.lp(0, -2, 1f))   // Platz für die Unterlänge des Kommas (sonst wirkt „2,5“ wie „2.5“)
            }
            c.addView(z, ui.lp(-1, -2, oben = if (zeile == 0) Mass.S else 0))
        }
        reg {
            val s = summary()
            ring.setze(FahrzeugStart.ringAnteil(s?.speedKmh), ZusatzKacheln.tempo(s?.speedKmh) ?: strich)
            val lim = Tempolimit.anzeige(s?.tempolimit, online())
            limit.visibility = if (lim != null) View.VISIBLE else View.GONE
            limitText.visibility = limit.visibility
            if (lim != null) { limit.text = lim.toString(); limit.contentDescription = ctx.getString(R.string.tempolimit_beschreibung, lim) }
            ring.warnen = s?.tempoWarnung == true && Tempolimit.ueber(s.speedKmh, lim)
            val gi = FahrzeugStart.gangIndex(s?.gear)
            gangViews.forEachIndexed { i, v -> Gangpille.setze(v, ui, i == gi) }
            reichweite.text = s?.rangeKm?.takeIf { it >= 0 }?.let { Math.round(it).toString() } ?: strich
            km.visibility = if (reichweite.text == strich) View.GONE else View.VISIBLE
            val a = FahrzeugStart.akkuAnteil(s?.socPct)
            akkuText.text = s?.socPct?.takeIf { a != null }?.let { Math.round(it).toString() + " %" } ?: strich
            balken.setze(a, s?.charging?.active == true)
            balken.contentDescription = ctx.getString(R.string.fs_akku) + " " + akkuText.text
            val r = s?.reifen
            val warn = ZusatzKacheln.warnungen(r)
            listOf(r?.vl, r?.vr, r?.hl, r?.hr).forEachIndexed { i, bar ->
                drucke[i].text = FahrzeugStart.druck(bar)
                drucke[i].setTextColor(if (warn[i] != null) Signal.warnung(ui.p) else ui.p.onSurface)
            }
        }
        return c
    }

    // ---------- Fahrzeug-Ansicht ----------

    fun ansicht(): View {
        val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(8)) }
        val kopf = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        // 0.1.43.4 (E2E G2): der Name bricht nie mitten im Wort um – eine Zeile, notfalls kleiner, zuletzt „…“
        val name = ui.text("", 20f, 700).apply {
            typeface = ui.sora(600); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setAutoSizeTextTypeUniformWithConfiguration(14, 20, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        kopf.addView(name, ui.lp(0, -2, 1f))
        val zustand = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }   // 0.1.43.3 (L3): Plaketten
        kopf.addView(zustand)
        c.addView(kopf, ui.lp(-1, -2))
        // Viele Plaketten: eigene Zeile rechtsbündig unter dem Namen, damit der Name Platz behält
        val zustandZeile = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END; visibility = View.GONE }
        c.addView(zustandZeile, ui.lp(-1, -2, oben = 4))
        // 0.1.43.4 (Rang 4): Tipp-Schicht über dem Fahrzeug; Warnplaketten hängen sich in die Reihe `zustand`
        val bild = AutoAnsicht(ctx, ui)
        val tippen = TippSchicht(ctx, ui)
        val buehne = android.widget.FrameLayout(ctx)
        buehne.addView(bild, android.widget.FrameLayout.LayoutParams(-1, -1))
        buehne.addView(tippen, android.widget.FrameLayout.LayoutParams(-1, -1))
        c.addView(buehne, ui.lp(-1, 0, 1f))
        tippen.zonen = { w, h -> tippZonen(w, h, bild.hatBild()) }
        tippen.getippt = { teil -> teilGetippt(teil) }
        // 0.1.44 (D1b): Namensnennung der Renderbilder (CC BY 4.0) sichtbar unter dem Auto
        val nennung = ui.leise("", 11f).apply { gravity = Gravity.END; visibility = View.GONE }
        c.addView(nennung, ui.lp(-1, -2))
        // 0.1.43.4 (Foto statt Zeichnung): dezente Bitte um ein 3D-Modell, Tipp öffnet die Panoptes-Seite
        val bitte = ui.leise(ctx.getString(R.string.fahrzeug_modell_bitte), 11f).apply {
            gravity = Gravity.END; visibility = View.GONE
            setOnClickListener {
                val url = FahrzeugBild.bitteUrl(ctx)?.let { PanoptesSeite.bitteZiel(it, PanoptesSeite.sprache()) } ?: return@setOnClickListener
                try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            }
        }
        c.addView(bitte, ui.lp(-1, -2))
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
            // 0.1.43.4 (Rang 1): zuerst die Dreiviertel-Ansicht aus dem Launcher (5 CC-BY-Modelle, echte Lackfarbe, Ankerpunkte),
            // sonst wie bisher das Renderbild aus Panoptes, sonst die gezeichnete Form.
            if (FahrzeugStart.formWahl(ctx) == FahrzeugStart.AUTO && FahrzeugBild.hat(ctx, m)) {
                FahrzeugBild.holen(ctx, m!!, FahrzeugStart.lack(lack(), ui.p)) { bmp ->
                    bild.setzeBild(bmp, if (bmp != null) FahrzeugBild.punkte(ctx, m) else null)
                    val nn = if (bmp != null) FahrzeugBild.namensnennung(ctx, m)?.let { Namensnennung.lokal(ctx, it) } else null
                    nennung.text = nn ?: ""; nennung.visibility = if (nn != null) View.VISIBLE else View.GONE
                    bitte.visibility = View.GONE
                }
            } else if (FahrzeugStart.formWahl(ctx) == FahrzeugStart.AUTO && FahrzeugBild.hatFoto(ctx, m)) {
                // Foto statt Zeichnung (Modell ohne gutes 3D): freigestellt mit Bodenlicht, ohne Leuchtpunkte, Plaketten bleiben
                FahrzeugBild.fotoHolen(ctx, m!!) { bmp ->
                    bild.setzeBild(bmp, null, foto = true)
                    val nn = if (bmp != null) FahrzeugBild.fotoNennung(ctx, m)?.let { Namensnennung.lokal(ctx, it) } else null
                    nennung.text = nn ?: ""; nennung.visibility = if (nn != null) View.VISIBLE else View.GONE
                    bitte.visibility = if (bmp != null && FahrzeugBild.bitteUrl(ctx) != null) View.VISIBLE else View.GONE
                }
            } else if (FahrzeugStart.formWahl(ctx) == FahrzeugStart.AUTO && FahrzeugModelle.hatBild(m)) {
                bitte.visibility = View.GONE
                Renderbilder.holen(m!!) { bmp -> bild.setzeBild(bmp); nennung.text = FahrzeugModelle.namensnennung(m)?.let { Namensnennung.lokal(ctx, it) } ?: ""; nennung.visibility = if (bmp != null) View.VISIBLE else View.GONE }
            } else { bild.setzeBild(null); nennung.visibility = View.GONE; bitte.visibility = View.GONE }
            val teile = Warnplaketten.reihe(HauptPlaketten.liste(z, s), Warnplaketten.liste(s))   // 0.1.43.4 (Rang 4): eine Reihe
            if (teile != zustand.tag) {
                zustand.tag = teile; zustand.removeAllViews(); zustandZeile.removeAllViews()
                val ziel = if (HauptPlaketten.eigeneZeile(teile.size)) zustandZeile else zustand
                zustandZeile.visibility = if (ziel === zustandZeile) View.VISIBLE else View.GONE
                teile.forEach { e ->
                    val v = e.haupt?.let { t -> ui.plakette(HauptPlaketten.text(ctx, t), t.ton, t.symbol) }
                        ?: e.eigen?.let { p -> ui.plakette(Warnplaketten.text(ctx, p), p.ton, Warnplaketten.symbol(p)) }
                    if (v != null) ziel.addView(v, ui.lp(rechts = Mass.S))
                }
            }
            val liste = FahrzeugStart.knoepfe(s?.koerper)
            if (liste != zuletzt) {
                zuletzt = liste
                knoepfe.removeAllViews()
                liste.forEach { f -> knoepfe.addView(pille(f), ui.lp(ui.bd(116), ui.bd(56), rechts = Mass.S + 2)) }   // L3: kompakte Pillen, mittig
            }
            for (i in 0 until knoepfe.childCount) {
                val v = knoepfe.getChildAt(i) as? TextView ?: continue
                val f = v.tag as? String ?: continue
                val pct = s?.koerper?.fenster?.get(f)
                v.text = kurz(f) + "  " + (pct?.let { "$it %" } ?: strich)
                v.setTextColor(if (FahrzeugStart.offen(pct)) Signal.warnung(ui.p) else ui.p.onSurface); v.alpha = if (FahrzeugStart.darfFenster(s?.gear, s?.acc, s?.speedKmh)) 1f else 0.45f
                v.contentDescription = name(f) + ", " + (pct?.let { "$it %" } ?: strich)
            }
            leer.visibility = if (liste.isEmpty()) View.VISIBLE else View.GONE
        }
        return c
    }

    // ---------- 0.1.43.4 (Rang 4): Fahrzeug antippen ----------

    /** Zonen für die aktuelle Größe: Renderbild mit Ankern (Rang 1), gezeichnete Seitenansicht, sonst keine. */
    private fun tippZonen(w: Float, h: Float, mitBild: Boolean): List<FahrzeugTippen.Zone> {
        val fenster = FahrzeugTippen.fensterDa(summary()?.koerper)
        val min = ui.dp(FahrzeugTippen.MIN_DP).toFloat()
        if (!mitBild) return FahrzeugTippen.zonenSeite(FahrzeugStart.form(FahrzeugStart.formWahl(ctx), modell()), w, h, fenster, min)
        val anker = modell()?.let { m -> FahrzeugTippen.ankerQuelle?.invoke(m) } ?: return emptyList()
        return FahrzeugTippen.zonenAnker(anker, FahrzeugStart.bildRahmen(w, h), fenster, min)
    }

    /** Fenster/Dach: vorhandene Rückfrage. Türen, Kofferraum, Haube: nur Zustand (kein Knopf, kein Kern-Aufruf). */
    private fun teilGetippt(t: FahrzeugTippen.Teil) {
        if (t.istFenster) { fensterTipp(t.id); return }
        zustandZeigen(t)
    }

    private fun zustandZeigen(t: FahrzeugTippen.Teil) {
        val zustand = when (FahrzeugTippen.offen(t, summary()?.koerper)) {
            true -> R.string.teil_offen; false -> R.string.teil_zu; null -> R.string.teil_unbekannt
        }
        val d = AlertDialog.Builder(ctx).setTitle(teilName(t))
            .setMessage(ctx.getString(zustand) + "\n\n" + ctx.getString(R.string.teil_nur_anzeige))
            .setPositiveButton(android.R.string.ok, null).show()
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.apply { textSize = 20f; minHeight = ui.dp(64) }
    }

    private fun teilName(t: FahrzeugTippen.Teil): String = ctx.getString(when (t) {
        FahrzeugTippen.Teil.TUER_VL -> R.string.plakette_tuer_vl_name; FahrzeugTippen.Teil.TUER_VR -> R.string.plakette_tuer_vr_name
        FahrzeugTippen.Teil.TUER_HL -> R.string.plakette_tuer_hl_name; FahrzeugTippen.Teil.TUER_HR -> R.string.plakette_tuer_hr_name
        FahrzeugTippen.Teil.HAUBE -> R.string.teil_haube; FahrzeugTippen.Teil.KOFFERRAUM -> R.string.teil_kofferraum
        else -> R.string.widget_autoansicht
    })

    /** Fenster-Pille: groß genug für den Finger (60 dp), Glas wie die Karten. */
    private fun pille(f: String) = TextView(ctx).apply {
        tag = f; gravity = Gravity.CENTER; textSize = 16f; typeface = ui.sora(600); maxLines = 1
        background = glas(ui, Mass.RADIUS_RUND.toFloat()); foreground = ui.welle()   // 0.1.43.3 (L3): Glas-Pille
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
    /** 0.1.43.3 (L3): über dem Tempolimit (nur wenn die Warnung eingeschaltet ist) Zahl und Bogen in Warnfarbe. */
    var warnen = false; set(v) { if (v != field) { field = v; invalidate() } }
    private val bahn = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val punkt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val r = RectF()

    fun setze(a: Float?, z: String) { if (a != anteil || z != zahl) { anteil = a; zahl = z; contentDescription = "$z km/h"; invalidate() } }

    /** 0.1.43.3 (L3): schmale Bahn (270°), Bogen in Akzentfarbe mit Punkt am Ende, Zahl groß in Sora. */
    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat(); val d = s * 0.045f
        r.set((width - s) / 2f + d * 1.6f, (height - s) / 2f + d * 1.6f, (width + s) / 2f - d * 1.6f, (height + s) / 2f - d * 1.6f)
        val farbe = if (warnen) Signal.warnung(ui.p) else ui.p.aqua
        bahn.strokeWidth = d; bahn.color = Farbe.alpha(ui.p.onSurface, if (ui.p.hell) 40 else 34)
        c.drawArc(r, 135f, 270f, false, bahn)
        val a = anteil ?: 0f
        if (anteil != null && a > 0f) { bahn.color = farbe; c.drawArc(r, 135f, 270f * a, false, bahn) }
        if (anteil != null) {
            val w = Math.toRadians(135.0 + 270.0 * a)
            punkt.color = farbe
            c.drawCircle(r.centerX() + (r.width() / 2f) * Math.cos(w).toFloat(), r.centerY() + (r.height() / 2f) * Math.sin(w).toFloat(), d * 1.25f, punkt)
        }
        text.color = if (warnen) farbe else ui.p.onSurface; text.textSize = s * 0.34f; text.typeface = ui.sora(700)
        c.drawText(zahl, width / 2f, height / 2f + s * 0.10f, text)
        text.color = ui.leiseFarbe; text.textSize = s * 0.11f; text.typeface = ui.manrope(600)
        c.drawText("km/h", width / 2f, height / 2f + s * 0.27f, text)
    }
}

/** 0.1.43.3 (L3): Gangpille P/R/N/D – kleines Quadrat, aktiv in Akzentfarbe mit leichtem Schatten. */
object Gangpille {
    fun setze(v: TextView, ui: Ui, an: Boolean) {
        v.setTextColor(if (an) (if (ui.p.hell) android.graphics.Color.WHITE else Farbe.rgb(0x06121C)) else ui.leiseFarbe)
        v.background = GradientDrawable().apply {
            cornerRadius = ui.radius(Mass.RADIUS_KLEIN) * 0.75f
            setColor(if (an) ui.p.aqua else Farbe.alpha(ui.p.onSurface, if (ui.p.hell) 14 else 16))
        }
        v.elevation = if (an) ui.dpf(6f) else 0f
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
        p.shader = null; p.color = Farbe.alpha(ui.p.onSurface, if (ui.p.hell) 30 else 26); c.drawRoundRect(r, h / 2, h / 2, p)
        anteil?.let {
            r.right = width * it
            // 0.1.43.3 (L3): Verlauf Violett → Akzent; beim Laden OK-Grün, unter 15 % Warnfarbe
            p.shader = if (laedt || it < 0.15f) null else android.graphics.LinearGradient(0f, 0f, r.right, 0f,
                Farbe.mix(ui.p.aqua, Farbe.rgb(0x7B6CF6), 0.65f), ui.p.aqua, android.graphics.Shader.TileMode.CLAMP)
            p.color = when { laedt -> Signal.ok(ui.p); it < 0.15f -> Signal.warnung(ui.p); else -> ui.p.aqua }
            c.drawRoundRect(r, h / 2, h / 2, p); p.shader = null
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

    /** 0.1.43.4: Ankerpunkte des Bildes (Dreiviertel-Ansicht), null = altes Renderbild ohne Punkte. */
    private var anker: Map<String, FahrzeugBild.Anker>? = null

    /** Foto statt Rendering: freigestellt, mit Bodenlicht wie ein Rendering, aber ohne Leuchtpunkte (nur die Plaketten). */
    private var foto = false

    fun setzeBild(b: android.graphics.Bitmap?, punkte: Map<String, FahrzeugBild.Anker>? = null, foto: Boolean = false) {
        if (b !== bild || punkte != anker || foto != this.foto) { bild = b; anker = punkte; this.foto = foto; invalidate() }
    }
    /** 0.1.43.4 (Rang 4): liegt gerade ein Renderbild (dann gelten Ankerzonen statt der Seitenansicht)? */
    fun hatBild(): Boolean = bild != null

    fun setze(neueForm: FahrzeugStart.Form, neu: FahrzeugStart.Zustand, neuerLack: Int) {
        if (neueForm != form || neu != z || neuerLack != lack) { form = neueForm; z = neu; lack = neuerLack; invalidate() }
    }

    override fun onDraw(c: Canvas) {
        if (width <= 0 || height <= 0) return
        bild?.let { b ->
            val l = AndroidLeinwand(c)
            val r = FahrzeugStart.bildRahmen(width.toFloat(), height.toFloat())
            FahrzeugStart.bodenlicht(l, z, ui.p, r)   // 0.1.43.5: auch unter dem freigestellten Foto
            ziel.set(r[0], r[1], r[0] + r[2], r[1] + r[3]); c.drawBitmap(b, null, ziel, bildPinsel)
            if (!foto) anker?.let { FahrzeugBild.zustandZeichnen(l, z, it, ui.p, r) }   // 0.1.43.4: offene Teile an ihrer Stelle
            FahrzeugStart.symboleZeichnen(l, z, ui.p, r)
            contentDescription = context.getString(R.string.widget_autoansicht)
            return
        }
        val s = FahrzeugStart.laenge(width.toFloat(), height.toFloat())
        FahrzeugStart.zeichne(AndroidLeinwand(c), form, z, ui.p, if (lack == 0) FahrzeugStart.lack(null, ui.p) else lack, width / 2f, height * 0.50f, s)
        contentDescription = context.getString(R.string.widget_autoansicht)
    }
}

/**
 * 0.1.43.3 (L3): Zustands-Plaketten oben rechts über dem Fahrzeug: Schloss (Verriegelt = OK, Entriegelt = Warnung),
 * Tür offen und Fenster offen (Warnung), Lädt mit Leistung (Akzent). Nur bekannte Kernwerte; unbekannt = keine Plakette.
 * Reine Anzeige. Die Warnplaketten aus Rang 4 (Gurt, Reifen, Kofferraum …) kommen über [Ui.plakette] in dieselbe Reihe.
 */
object HauptPlaketten {
    enum class Art { VERRIEGELT, ENTRIEGELT, TUER, FENSTER, LAEDT }
    /** Die vier Türen (ohne „trunk“ und „hood“). */
    val TUEREN = setOf("lf", "rf", "lr", "rr")
    /** 0.1.43.4 (E2E G2): ab so vielen Plaketten stehen sie in einer eigenen Zeile unter dem Namen. */
    const val EIGENE_ZEILE_AB = 3

    fun eigeneZeile(anzahl: Int): Boolean = anzahl >= EIGENE_ZEILE_AB
    data class Teil(val art: Art, val ton: Signal.Ton, val symbol: Int?, val kw: Double? = null)

    /** Abgestellt: Zündung aus und steht (wie Rang 4 / Warnplaketten); nur dann warnen Entriegelt und Fenster. */
    fun abgestellt(s: LauncherSummary?): Boolean = s != null && s.acc == "off" && (s.speedKmh ?: 0.0) <= 0.5

    fun liste(z: FahrzeugStart.Zustand, s: LauncherSummary?): List<Teil> = buildList {
        val ab = abgestellt(s)
        when (z.verriegelt) {
            true -> add(Teil(Art.VERRIEGELT, Signal.Ton.OK, R.drawable.ic_pn_lock))
            false -> if (ab) add(Teil(Art.ENTRIEGELT, Signal.Ton.WARNUNG, R.drawable.ic_pn_lock))   // in Fahrt kein Alarm
            null -> {}
        }
        // 0.1.43.4 (E2E G1): nur die vier echten Türen; Kofferraum und Haube haben eigene Plaketten (Warnplaketten)
        if (z.tuerOffen.filterKeys { it in TUEREN }.values.any { it }) add(Teil(Art.TUER, Signal.Ton.WARNUNG, R.drawable.ic_pn_car))
        if (s?.koerper?.fenster?.values?.any { FahrzeugStart.offen(it) } == true) add(Teil(Art.FENSTER, if (ab) Signal.Ton.WARNUNG else Signal.Ton.INFO, R.drawable.ic_pn_auto_seite))
        if (z.laedt) add(Teil(Art.LAEDT, Signal.Ton.INFO, R.drawable.ic_pn_plug, s?.charging?.kw?.takeIf { it > 0 }))
    }

    fun text(ctx: Context, t: Teil): String = when (t.art) {
        Art.VERRIEGELT -> ctx.getString(R.string.ansicht_verriegelt)
        Art.ENTRIEGELT -> ctx.getString(R.string.ansicht_entriegelt)
        Art.TUER -> ctx.getString(R.string.ansicht_tuer_offen)
        Art.FENSTER -> ctx.getString(R.string.ansicht_fenster_offen)
        Art.LAEDT -> ctx.getString(R.string.ansicht_laedt) + (t.kw?.let { " · " + String.format(java.util.Locale.getDefault(), "%.1f kW", it) } ?: "")
    }
}
