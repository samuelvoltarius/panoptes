package at.greenchili.panoptes.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * 0.1.43.3 (Rang 7) „Was kann ich sagen?“ als Vollseite: Suchfeld, Kategorie-Chips (nur Gruppen, die es gibt),
 * „Zum Ausprobieren“, je Befehl eine Karte mit Titel, Erklärung und ehrlicher Plakette (Nur lesen / Mit Rückfrage /
 * Öffnet Vorschau / Steuert Musik), darunter die Sätze zum Antippen, oben rechts die Sprache.
 *
 * Ein angetippter Satz läuft über denselben Weg wie gesprochen (Panoptes `SprachStart` mit Befehl): Klima immer mit
 * Rückfrage, dieselben Kern-Regeln. Der Launcher selbst hört nie zu und schickt keinen Fahrzeugbefehl.
 */
class SprachHilfeActivity : Activity() {

    companion object {
        fun starten(ctx: Context) {
            runCatching { ctx.startActivity(Intent(ctx, SprachHilfeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
        }

        /** Titel und Erklärung je Befehl (Launcher-Texte, 5 Sprachen). */
        fun titel(ctx: Context, befehl: String): String = text(ctx, "sh_t_$befehl", befehl)
        fun erklaerung(ctx: Context, befehl: String): String = text(ctx, "sh_e_$befehl", "")
        fun plakette(ctx: Context, art: String): String = text(ctx, "sh_b_$art", art)
        fun kategorieName(ctx: Context, k: String): String = text(ctx, "sh_k_$k", k)

        private fun text(ctx: Context, name: String, sonst: String): String {
            val id = ctx.resources.getIdentifier(name, "string", ctx.packageName)
            return if (id == 0) sonst else ctx.getString(id)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val client = PanoptesClient()
    private lateinit var ui: Ui
    private var katalog: SprachKatalog? = null
    private var suche = ""
    private var kategorie: String? = null
    private lateinit var liste: LinearLayout
    private lateinit var chips: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this, PanoptesStil.DUNKEL, false, GrosseBedienung.faktor(this))
        window.statusBarColor = ui.p.background
        setContentView(ui.text(getString(R.string.sh_laedt), 18f).apply { gravity = Gravity.CENTER; setBackgroundColor(ui.p.background) })
        laden()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun laden() {
        io.execute {
            val k = runCatching { client.sprache() }.getOrNull()
            main.post { if (!isFinishing) { katalog = k; bauen() } }
        }
    }

    private fun bauen() {
        val k = katalog
        val seite = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(24), ui.dp(16), ui.dp(24), ui.dp(24)) }
        // Kopf: Titel, Sprache oben rechts, Schließen
        val kopf = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        kopf.addView(ui.titel(getString(R.string.sh_titel)).apply { textSize = 24f * ui.bedien }, ui.lp(0, -2, 1f))
        if (k != null) kopf.addView(ui.pill(k.sprache.uppercase() + if (k.erkennbar) "" else " · " + getString(R.string.sh_noch_nicht_kurz)).apply {
            id = View.generateViewId(); contentDescription = getString(R.string.sh_sprache_beschr, k.sprache.uppercase())
        }, ui.lp(rechts = 12))
        kopf.addView(ui.act(R.drawable.ic_pn_close, null) { finish() }.apply { contentDescription = getString(R.string.sh_schliessen) })
        seite.addView(kopf)

        if (k == null) {
            seite.addView(ui.text(getString(R.string.sh_fehlt), 18f), ui.lp(-1, -2, oben = 24))
            setzen(seite)
            return
        }
        // Zustand ehrlich oben
        val z = k.zustand()
        val zustandText = when (z) {
            "aus" -> getString(R.string.sh_aus)
            "noch_nicht" -> getString(R.string.sh_noch_nicht)
            "kein_modell" -> getString(R.string.sh_kein_modell)
            else -> getString(R.string.sh_bereit) + if (k.weckwort && k.weckwortSatz.isNotEmpty()) " " + getString(R.string.sh_weck, k.weckwortSatz) else ""
        }
        val zeile = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        zeile.addView(ui.text(zustandText, 16f, 600, if (z == "bereit") ui.p.onSurface else ui.p.warning), ui.lp(0, -2, 1f))
        if (z == "aus" || z == "kein_modell") zeile.addView(ui.act(null, getString(R.string.sh_panoptes_oeffnen)) { panoptesOeffnen() })
        seite.addView(zeile, ui.lp(-1, -2, oben = 12))

        // Mikrofon: Zuhören startet Panoptes
        seite.addView(ui.act(R.drawable.ic_pn_mikro, getString(R.string.sh_jetzt), primary = true) { startActivity(Sprache.intent()) }
            .apply { orientation = LinearLayout.HORIZONTAL; contentDescription = getString(R.string.sh_jetzt) }, ui.lp(-1, ui.bd(64), oben = 12))

        // Suche
        val feld = EditText(this).apply {
            hint = getString(R.string.sh_suche); textSize = 17f * ui.bedien; setSingleLine(); setTextColor(ui.p.onSurface); setHintTextColor(ui.p.onSurfaceVariant)
            minHeight = ui.bd(56); setPadding(ui.dp(16), 0, ui.dp(16), 0)
            background = GradientDrawable().apply { cornerRadius = ui.dpf(14f); setColor(ui.p.act); setStroke(ui.dp(1), ui.p.line) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { suche = s?.toString() ?: ""; listeZeigen() }
            })
        }
        seite.addView(feld, ui.lp(-1, -2, oben = 14))

        // Chips: nur Kategorien, die es wirklich gibt
        chips = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        seite.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, ui.lp(-1, -2, oben = 10))
        chipsZeigen()

        // Zum Ausprobieren
        seite.addView(ui.text(getString(R.string.sh_probieren), 16f, 700), ui.lp(-1, -2, oben = 16))
        val probieren = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        k.probieren().chunked(2).forEach { paar ->
            val r = LinearLayout(this)
            paar.forEach { (befehl, satz) -> r.addView(satzKnopf(befehl, satz), ui.lp(0, -2, 1f, oben = 6, rechts = 8)) }
            probieren.addView(r, ui.lp(-1, -2))
        }
        seite.addView(probieren)
        seite.addView(ui.leise(getString(R.string.sh_tipp_ausfuehren), 14f), ui.lp(-1, -2, oben = 6))

        liste = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        seite.addView(liste, ui.lp(-1, -2, oben = 10))
        listeZeigen()

        // Regel A+
        seite.addView(ui.text(getString(R.string.sh_nie), 15f, 600, ui.p.onSurfaceVariant), ui.lp(-1, -2, oben = 16))
        setzen(seite)
    }

    private fun setzen(seite: View) {
        setContentView(ScrollView(this).apply { setBackgroundColor(ui.p.background); addView(seite) })
    }

    private fun chipsZeigen() {
        val k = katalog ?: return
        chips.removeAllViews()
        fun chip(name: String, wert: String?) = chips.addView(ui.pill(name, kategorie == wert).apply {
            minHeight = ui.bd(48); isClickable = true
            setOnClickListener { kategorie = wert; chipsZeigen(); listeZeigen() }
        }, ui.lp(rechts = 8))
        chip(getString(R.string.sh_alle), null)
        for (kat in k.kategorien()) chip(kategorieName(this, kat), kat)
    }

    private fun listeZeigen() {
        val k = katalog ?: return
        liste.removeAllViews()
        val treffer = k.filter(suche, kategorie) { titel(this, it) }
        if (treffer.isEmpty()) { liste.addView(ui.leise(getString(R.string.sh_keine), 16f), ui.lp(-1, -2, oben = 8)); return }
        for (e in treffer) liste.addView(karte(e), ui.lp(-1, -2, oben = 10))
    }

    /** Karte: Titel + ehrliche Plakette, Erklärung, „Sag zum Beispiel:“ mit Sätzen zum Antippen. */
    private fun karte(e: SprachEintrag): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = ui.karte(); setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(14))
        }
        val kopf = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        kopf.addView(ui.text(titel(this, e.befehl), 18f, 700), ui.lp(0, -2, 1f))
        kopf.addView(ui.pill(plakette(this, e.art), e.art == "rueckfrage"))
        c.addView(kopf)
        erklaerung(this, e.befehl).takeIf { it.isNotEmpty() }?.let { c.addView(ui.leise(it, 15f), ui.lp(-1, -2, oben = 4)) }
        c.addView(ui.text(getString(R.string.sh_sag), 14f, 600, ui.p.onSurfaceVariant), ui.lp(-1, -2, oben = 8))
        val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        e.saetze.take(3).forEach { r.addView(satzKnopf(e.befehl, it), ui.lp(-1, -2, oben = 6)) }
        c.addView(r)
        return c
    }

    /** Satz als großer Knopf: Tipp = ausführen über Panoptes (Klima dort mit Rückfrage). */
    private fun satzKnopf(befehl: String, satz: String): View = TextView(this).apply {
        text = "„$satz“  ▶"; textSize = 17f * ui.bedien; setTextColor(ui.p.onSurface); gravity = Gravity.CENTER_VERTICAL
        minHeight = ui.bd(56); setPadding(ui.dp(16), 0, ui.dp(16), 0)
        background = GradientDrawable().apply { cornerRadius = ui.dpf(14f); setColor(ui.p.act); setStroke(ui.dp(1), ui.p.line) }
        isClickable = true; foreground = ui.welle()
        contentDescription = getString(R.string.sh_ausfuehren_beschr, satz)
        setOnClickListener { runCatching { startActivity(Sprache.intent(befehl)) } }
    }

    private fun panoptesOeffnen() {
        val i = packageManager.getLaunchIntentForPackage(Raster.PANOPTES) ?: return
        runCatching { startActivity(i.putExtra("navigate_to", "system").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
