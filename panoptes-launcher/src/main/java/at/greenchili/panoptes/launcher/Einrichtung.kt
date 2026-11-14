package at.greenchili.panoptes.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * 0.1.44 (P1 „Bedienung 50+“, Punkt 10): Reine Regeln des Selbsttests in der geführten Einrichtung.
 * Der Zustand lebt im Prozess (MainActivity meldet die ankommende Haus-Taste, [EinrichtungActivity] liest ihn).
 */
object Selbsttest {
    enum class Schritt { START, HAUS, ZURUECK, ERGEBNIS }
    enum class Meldung { HAUS_OK, HAUS_FEHL, ZURUECK_OK, ZURUECK_FEHL }

    /** So lange zählt ein Druck auf die Haus-Taste nach „Haus-Taste testen“. */
    const val HAUS_FRIST_MS = 120_000L
    /** So lange wartet die Testseite auf „Zurück“. */
    const val ZURUECK_FRIST_MS = 4_000L

    @Volatile var hausWartetSeit = 0L
    @Volatile var hausOk: Boolean? = null
    @Volatile var zurueckOk: Boolean? = null

    fun hausWarten(jetzt: Long) { hausWartetSeit = jetzt; hausOk = null }

    /** Rein: Haus-Taste kam an, während der Test wartete (und die Frist läuft). */
    fun hausZaehlt(wartetSeit: Long, jetzt: Long): Boolean = wartetSeit > 0 && jetzt - wartetSeit in 0..HAUS_FRIST_MS

    /** Aus MainActivity: Haus-Taste kam an. true = der Test wartete darauf (dann weiter zum nächsten Schritt). */
    fun hausAngekommen(jetzt: Long): Boolean {
        if (!hausZaehlt(hausWartetSeit, jetzt)) return false
        hausWartetSeit = 0L; hausOk = true
        return true
    }

    /** Rein: Frist ohne Haus-Taste abgelaufen = gescheitert. */
    fun hausAbgelaufen(wartetSeit: Long, jetzt: Long): Boolean = wartetSeit > 0 && jetzt - wartetSeit > HAUS_FRIST_MS

    /** Rein: Meldungen für das Ergebnis (nur getestete Punkte). */
    fun meldungen(haus: Boolean?, zurueck: Boolean?): List<Meldung> = listOfNotNull(
        haus?.let { if (it) Meldung.HAUS_OK else Meldung.HAUS_FEHL },
        zurueck?.let { if (it) Meldung.ZURUECK_OK else Meldung.ZURUECK_FEHL })

    fun allesGut(haus: Boolean?, zurueck: Boolean?) = haus == true && zurueck == true
}

/**
 * 0.1.44 (P1, Punkt 10): Geführte Einrichtung „Panoptes Launcher als Startbildschirm“ mit Bild zu jedem Schritt,
 * Selbsttest „Haus-Taste und Zurück gehen?“, Meldung bei Misserfolg und auf jeder Seite dem großen Knopf
 * „Alles zurück auf BYD“ (Startbildschirm wieder BYD, Leiste aus).
 *
 * Einschalten geht nur über die vorhandenen Kern-Wege (Schalter an, dann „Behalten“ = Standard setzen). Rückweg über
 * den Kern und zusätzlich lokal (eigener HOME-Alias aus): dann nimmt Android von selbst den BYD-Startbildschirm, auch
 * wenn der Kern gerade nicht antwortet.
 */
class EinrichtungActivity : Activity() {

    companion object {
        const val EXTRA_SCHRITT = "schritt"
        private const val REQ_ZURUECK = 4401

        fun starten(ctx: Context, schritt: Selbsttest.Schritt = Selbsttest.Schritt.START) {
            runCatching {
                ctx.startActivity(Intent(ctx, EinrichtungActivity::class.java).putExtra(EXTRA_SCHRITT, schritt.name)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        }

        /** Startbildschirm des Systems außer dem eigenen (BYD), wie der Dock-Knopf ([HomeRueckweg]). */
        fun zumByd(a: Activity) {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val pm = a.packageManager
            val standard = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.let { HomeKandidat(it.packageName, it.name) }
            val kandidaten = pm.queryIntentActivities(home, 0).map { HomeKandidat(it.activityInfo.packageName, it.activityInfo.name) }
            val ziel = HomeRueckweg.ziel(a.packageName, standard, kandidaten) ?: return
            runCatching { a.startActivity(Intent(home).setClassName(ziel.packageName, ziel.className).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val client = PanoptesClient()
    private lateinit var ui: Ui
    private var schritt = Selbsttest.Schritt.START

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this, PanoptesStil.DUNKEL, false, GrosseBedienung.faktor(this))
        window.statusBarColor = ui.p.background
        schritt = schrittAus(intent) ?: savedInstanceState?.getString(EXTRA_SCHRITT)?.let { runCatching { Selbsttest.Schritt.valueOf(it) }.getOrNull() }
            ?: Selbsttest.Schritt.START
        zeigen()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        schrittAus(intent)?.let { schritt = it; zeigen() }
    }

    override fun onResume() {
        super.onResume()
        // Haus-Test: Frist abgelaufen, ohne dass die Haus-Taste zu uns führte → gescheitert, weiter zu „Zurück“
        if (schritt == Selbsttest.Schritt.HAUS && Selbsttest.hausAbgelaufen(Selbsttest.hausWartetSeit, System.currentTimeMillis())) {
            Selbsttest.hausWartetSeit = 0L; Selbsttest.hausOk = false
            schritt = Selbsttest.Schritt.ZURUECK; zeigen()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(EXTRA_SCHRITT, schritt.name)
    }

    private fun schrittAus(i: Intent?) = i?.getStringExtra(EXTRA_SCHRITT)?.let { runCatching { Selbsttest.Schritt.valueOf(it) }.getOrNull() }

    private fun zeigen() {
        val spalte = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ui.dp(40), ui.dp(24), ui.dp(40), ui.dp(24))
        }
        val (titel, bild, text) = when (schritt) {
            Selbsttest.Schritt.START -> Triple(R.string.einr_s1_titel, R.drawable.bild_einr_start, R.string.einr_s1_text)
            Selbsttest.Schritt.HAUS -> Triple(R.string.einr_s2_titel, R.drawable.bild_einr_haus, R.string.einr_s2_text)
            Selbsttest.Schritt.ZURUECK -> Triple(R.string.einr_s3_titel, R.drawable.bild_einr_zurueck, R.string.einr_s3_text)
            Selbsttest.Schritt.ERGEBNIS -> Triple(R.string.einr_s4_titel,
                if (Selbsttest.allesGut(Selbsttest.hausOk, Selbsttest.zurueckOk)) R.drawable.bild_einr_start else R.drawable.bild_einr_byd,
                R.string.einr_s4_text)
        }
        spalte.addView(ui.text(getString(titel), 26f, 700), ui.lp())
        spalte.addView(ImageView(this).apply {
            setImageResource(bild); scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = getString(titel)
        }, ui.lp(ui.dp(320), ui.dp(200), oben = 16))
        spalte.addView(ui.text(getString(text), 20f, 500).apply { gravity = Gravity.CENTER }, ui.lp(oben = 16))
        if (schritt == Selbsttest.Schritt.ERGEBNIS) ergebnisZeilen(spalte)
        val knoepfe = LinearLayout(this).apply { gravity = Gravity.CENTER }
        fun knopf(label: Int, primary: Boolean = false, f: () -> Unit) =
            knoepfe.addView(ui.act(null, getString(label), primary) { f() }.apply {
                (getChildAt(0) as? TextView)?.textSize = 20f * ui.bedien
            }, ui.lp(-2, ui.bd(72), rechts = 12))
        when (schritt) {
            Selbsttest.Schritt.START -> {
                knopf(R.string.einr_einschalten, primary = true) { einschalten() }
                knopf(R.string.einr_weiter) { weiter(Selbsttest.Schritt.HAUS) }
            }
            Selbsttest.Schritt.HAUS -> {
                Selbsttest.hausWarten(System.currentTimeMillis())
                knopf(R.string.einr_ging_nicht) { Selbsttest.hausWartetSeit = 0L; Selbsttest.hausOk = false; weiter(Selbsttest.Schritt.ZURUECK) }
            }
            Selbsttest.Schritt.ZURUECK -> {
                knopf(R.string.einr_testen, primary = true) {
                    @Suppress("DEPRECATION") startActivityForResult(Intent(this, ZurueckTestActivity::class.java), REQ_ZURUECK)
                }
                knopf(R.string.einr_weiter) { weiter(Selbsttest.Schritt.ERGEBNIS) }
            }
            Selbsttest.Schritt.ERGEBNIS -> {
                if (Selbsttest.zurueckOk == false)
                    knopf(R.string.hilfe_einstellung) {
                        if (!PanoptesBedienhilfe.einstellungOeffnen(this)) hinweis(R.string.einstellung_fehlt)
                    }
                if (Selbsttest.hausOk == false) knopf(R.string.einr_nochmal) { weiter(Selbsttest.Schritt.HAUS) }
                else if (Selbsttest.zurueckOk == false) knopf(R.string.einr_nochmal) { weiter(Selbsttest.Schritt.ZURUECK) }
                knopf(R.string.einr_fertig, primary = true) { finish() }
            }
        }
        spalte.addView(knoepfe, ui.lp(oben = 24))
        // Auf jeder Seite gut sichtbar: alles zurück auf BYD
        spalte.addView(ui.act(R.drawable.ic_pn_home, getString(R.string.einr_alles_byd)) { allesZurueckFragen() }.apply {
            orientation = LinearLayout.HORIZONTAL
            (getChildAt(1) as? TextView)?.apply { textSize = 20f * ui.bedien; setPadding(ui.dp(12), 0, 0, 0) }
            background = GradientDrawable().apply { cornerRadius = ui.radius(Mass.RADIUS_MITTEL); setColor(ui.p.act); setStroke(ui.dp(3), ui.p.danger) }
        }, ui.lp(-2, ui.bd(80), oben = 32))
        setContentView(ScrollView(this).apply { setBackgroundColor(ui.p.background); addView(spalte) })
    }

    private fun ergebnisZeilen(spalte: LinearLayout) {
        Selbsttest.meldungen(Selbsttest.hausOk, Selbsttest.zurueckOk).forEach { m ->
            val (ok, txt) = when (m) {
                Selbsttest.Meldung.HAUS_OK -> true to R.string.einr_haus_ok
                Selbsttest.Meldung.HAUS_FEHL -> false to R.string.einr_haus_fehl
                Selbsttest.Meldung.ZURUECK_OK -> true to R.string.einr_zurueck_ok
                Selbsttest.Meldung.ZURUECK_FEHL -> false to R.string.einr_zurueck_fehl
            }
            spalte.addView(ui.text((if (ok) "✓  " else "✗  ") + getString(txt), 20f, 700, if (ok) ui.p.lime else ui.p.danger),
                ui.lp(oben = 12))
        }
    }

    private fun weiter(s: Selbsttest.Schritt) { schritt = s; zeigen() }

    private fun hinweis(text: Int) {
        AlertDialog.Builder(this).setMessage(text).setPositiveButton(android.R.string.ok, null).show()
            .getButton(AlertDialog.BUTTON_POSITIVE)?.apply { textSize = 20f; minHeight = ui.dp(64) }
    }

    /** Schalter an, eigenen Alias an, dann „Behalten“ (der Kern setzt den Launcher als Standard). */
    private fun einschalten() {
        if (!KernVerbindung.verfuegbar()) { lokalEinschalten(); return }   // 0.1.43.4 (Paket S)
        io.execute {
            val an = client.homeAn()
            if (an) main.post { HomeSchalter.setze(this, true) }
            val ok = an && client.homeBehalten()
            main.post {
                if (isFinishing) return@post
                if (ok) weiter(Selbsttest.Schritt.HAUS) else hinweis(R.string.einr_fehler_kern)
            }
        }
    }

    /**
     * 0.1.43.4 (Paket S): ohne Panoptes. Eigenen HOME-Alias an (eigene Komponente, keine Berechtigung nötig), merken, dass
     * die Wahl hier getroffen wurde, und die Android-Seite „Standard-Startbildschirm“ öffnen: dort wählt der Nutzer selbst.
     * Zurück geht immer mit „Alles zurück auf BYD“ (Alias aus → Android nimmt wieder BYD).
     */
    private fun lokalEinschalten() {
        HomeSchalter.setze(this, true)
        HomeSchalter.lokalMerken(this, true)
        hinweisDann(R.string.einr_lokal_text) {
            if (!HomeSchalter.androidWahlOeffnen(this)) hinweis(R.string.einr_lokal_keine_seite)
            weiter(Selbsttest.Schritt.HAUS)
        }
    }

    private fun hinweisDann(text: Int, dann: () -> Unit) {
        AlertDialog.Builder(this).setMessage(text).setCancelable(false).setPositiveButton(android.R.string.ok) { _, _ -> dann() }.show()
            .getButton(AlertDialog.BUTTON_POSITIVE)?.apply { textSize = 20f; minHeight = ui.dp(64) }
    }

    private fun allesZurueckFragen() {
        AlertDialog.Builder(this).setTitle(R.string.einr_alles_byd).setMessage(R.string.einr_alles_byd_frage)
            .setPositiveButton(R.string.einr_alles_byd) { _, _ -> allesZurueck() }
            .setNegativeButton(android.R.string.cancel, null).show().apply {
                getButton(AlertDialog.BUTTON_POSITIVE)?.apply { textSize = 20f; minHeight = ui.dp(64) }
                getButton(AlertDialog.BUTTON_NEGATIVE)?.apply { textSize = 20f; minHeight = ui.dp(64) }
            }
    }

    /** Kern-Rückweg (Standard zurück, Schalter aus) und lokal: Alias aus, Leiste aus. Dann zum BYD-Startbildschirm. */
    private fun allesZurueck() {
        HomeSchalter.setze(this, false)
        HomeSchalter.lokalMerken(this, false)
        Schnellleiste.setzen(this, Schnellleiste.AUS)
        Selbsttest.hausWartetSeit = 0L
        // 0.1.43.4 (Paket S): ohne Panoptes reicht der eigene Rückweg (Alias aus); dann nicht nach Panoptes fragen
        val ohne = KernVerbindung.ohnePanoptes()
        io.execute {
            val kern = ohne || client.homeAus()
            main.post {
                if (isFinishing) return@post
                android.widget.Toast.makeText(this, if (kern) R.string.einr_alles_byd_ok else R.string.einr_alles_byd_lokal,
                    android.widget.Toast.LENGTH_LONG).show()
                zumByd(this)
                finish()
            }
        }
    }

    @Deprecated("Activity-Ergebnis")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_ZURUECK) return
        Selbsttest.zurueckOk = resultCode == RESULT_OK
        weiter(Selbsttest.Schritt.ERGEBNIS)
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}

/**
 * 0.1.44 (P1): Testseite für „Zurück“. Löst nach kurzer Pause „Zurück“ über [Tasten] aus. Kommt „Zurück“ hier an,
 * schließt sie sich mit Erfolg; sonst nach [Selbsttest.ZURUECK_FRIST_MS] mit Misserfolg.
 */
class ZurueckTestActivity : Activity() {
    companion object { const val FEHL = 2 }

    private val main = Handler(Looper.getMainLooper())
    private var gestartet = false
    private var fertig = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = Ui(this, PanoptesStil.DUNKEL, false, GrosseBedienung.faktor(this))
        setContentView(LinearLayout(this).apply {
            gravity = Gravity.CENTER; setBackgroundColor(ui.p.background)
            addView(ui.text(getString(R.string.einr_test_seite), 28f, 700))
        })
    }

    override fun onResume() {
        super.onResume()
        if (gestartet) return
        gestartet = true
        main.postDelayed({ if (!fertig) Tasten.druecken(this, Tasten.ZURUECK, PanoptesClient()) { ok -> if (!ok) ende(false) } }, 700)
        main.postDelayed({ ende(false) }, 700 + Selbsttest.ZURUECK_FRIST_MS)
    }

    @Deprecated("Zurück-Taste")
    override fun onBackPressed() = ende(true)

    private fun ende(ok: Boolean) {
        if (fertig) return
        fertig = true
        main.removeCallbacksAndMessages(null)
        setResult(if (ok) RESULT_OK else FEHL)
        finish()
    }
}
