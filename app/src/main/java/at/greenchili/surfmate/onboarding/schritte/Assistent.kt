package at.greenchili.surfmate.onboarding.schritte

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import at.greenchili.surfmate.R
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Einrichtungsassistent 0.1.10 am Display (1280×720, Querformat): einziger Vordergrund, Vollbild.
 *
 * Regeln (SETUP_WIZARD §3): ein Thema je Bildschirm, Knöpfe ≥ 64 px, Schrift ≥ 22 px, primärer Knopf rechts unten,
 * „Schritt n von m“ oben, Zurück geht immer, „Später“ nur ohne Pflicht, keine Vorauswahl bei Einwilligungen.
 *
 * Alles Speichern läuft über §0 ([EinrichtungApi]); vor der USB-Freigabe (Daemon aus) über den [Vorlauf].
 */
class Assistent(
    val activity: Activity,
    private val wurzel: ViewGroup,
    val host: AssistentHost,
    /** Wird aufgerufen, wenn der Assistent endet (fertig oder abgebrochen). [fertig] = Abschluss erreicht. */
    private val beendet: (fertig: Boolean) -> Unit,
) {
    val texte = Texte(activity)
    val haupt = Handler(Looper.getMainLooper())
    private val arbeiter = Executors.newSingleThreadExecutor()
    val vorlauf = Vorlauf(activity)

    private var stand: EinrichtungStand = EinrichtungStand.vorlauf(emptySet())
    private var aktuell: SchrittAnsicht? = null
    private var aktiv = false

    private lateinit var rahmen: FrameLayout
    private lateinit var fortschrittText: TextView
    private lateinit var fortschrittBalken: ProgressBar
    private lateinit var inhalt: FrameLayout
    private lateinit var meldung: TextView
    private lateinit var zurueck: MaterialButton
    private lateinit var spaeter: MaterialButton
    private lateinit var weiter: MaterialButton

    // ---------------------------------------------------------------- Maße (px, Display 1280×720)
    fun px(dp: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, activity.resources.displayMetrics).toInt()
    /** Schriftgröße in px, nie unter 22 px (Regel 2). */
    fun schrift(sp: Float): Float = maxOf(22f, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, activity.resources.displayMetrics))
    val knopfHoehe: Int get() = maxOf(64, px(56f))

    // ---------------------------------------------------------------- Ablauf
    fun starten() {
        if (aktiv) return
        aktiv = true
        laeuft = true
        ScheinEinrichtung.aktivieren()   // nur Debug-Build + debug.panoptes.einrichtung_schein=1 (Emulator-Abnahme)
        aufbauen()
        laden { s -> zeige(s.startSchritt()) }
    }

    fun beenden(fertig: Boolean) {
        if (!aktiv) return
        aktiv = false
        laeuft = false
        aktuell?.verlassen()
        haupt.removeCallbacksAndMessages(null)
        (rahmen.parent as? ViewGroup)?.removeView(rahmen)
        arbeiter.shutdownNow()
        beendet(fertig)
    }

    /** Im Hintergrund ausführen, Ergebnis auf dem Main-Thread (nur solange der Assistent läuft). */
    fun <T> imHintergrund(arbeit: () -> T, danach: (T) -> Unit) {
        if (arbeiter.isShutdown) return
        arbeiter.execute {
            val r = try { arbeit() } catch (t: Throwable) { Log.w(TAG, "Hintergrund: ${t.message}"); null }
            @Suppress("UNCHECKED_CAST")
            haupt.post { if (aktiv) danach(r as T) }
        }
    }

    /** §0 lesen; ohne Daemon den Vorlauf. Liefert den Kern-Stand erst, wenn Vorlauf-Antworten nachgereicht sind. */
    fun laden(danach: (EinrichtungStand) -> Unit) {
        imHintergrund({
            val k = EinrichtungApi.laden()
            if (k != null) { vorlauf.nachreichen(); EinrichtungApi.laden() ?: k } else null
        }) { k ->
            stand = k ?: EinrichtungStand.vorlauf(vorlauf.erledigt())
            danach(stand)
        }
    }

    fun stand(): EinrichtungStand = stand

    fun zeige(id: String) {
        if (!aktiv) return
        aktuell?.verlassen()
        val ansicht = SchrittKatalog.erzeuge(id, this) ?: SchrittKatalog.erzeuge("fertig", this)!!
        aktuell = ansicht
        meldung.text = ""; meldung.visibility = View.GONE
        // Fortschritt: „Schritt n von m“ (fertig ohne Nummer)
        val f = stand.fortschritt(id)
        if (f != null) {
            fortschrittText.text = texte.res(R.string.einr_schritt_von, f.first, f.second)
            fortschrittBalken.max = f.second; fortschrittBalken.progress = f.first
            fortschrittText.visibility = View.VISIBLE; fortschrittBalken.visibility = View.VISIBLE
        } else {
            fortschrittText.visibility = View.INVISIBLE; fortschrittBalken.visibility = View.INVISIBLE
        }
        inhalt.removeAllViews()
        val scroll = ScrollView(activity)
        scroll.isFillViewport = true
        scroll.addView(ansicht.ansicht(), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        inhalt.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        knoepfeAktualisieren()
        ansicht.betreten()
    }

    /** Schritt meldet: Antwort hat sich geändert → „Weiter“ neu bewerten. */
    fun geaendert() { if (aktiv) knoepfeAktualisieren() }

    private fun knoepfeAktualisieren() {
        val a = aktuell ?: return
        zurueck.visibility = if (stand.vorheriger(a.id) != null) View.VISIBLE else View.INVISIBLE
        spaeter.visibility = if (stand.spaeterErlaubt(a.id) && a.spaeterZeigen) View.VISIBLE else View.GONE
        weiter.text = a.weiterText() ?: texte.res(R.string.einr_weiter)
        weiter.isEnabled = a.weiterMoeglich()
        weiter.visibility = if (a.weiterZeigen) View.VISIBLE else View.GONE
    }

    fun zeigeMeldung(text: String) {
        meldung.text = text
        meldung.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Nach erfolgreichem Speichern: Stand neu lesen und zum nächsten offenen bzw. folgenden Schritt. */
    fun weiterNach(id: String) {
        laden { s ->
            val naechster = s.naechster(id)
            // erst den nächsten in der Liste, sonst den ersten noch offenen, sonst Abschluss
            zeige(naechster ?: s.startSchritt())
        }
    }

    private fun aufWeiter() {
        val a = aktuell ?: return
        weiter.isEnabled = false
        a.weiter { ok, text ->
            if (!aktiv) return@weiter
            if (ok) weiterNach(a.id) else { zeigeMeldung(text ?: texte.res(R.string.einr_fehler_speichern)); knoepfeAktualisieren() }
        }
    }

    private fun aufSpaeter() {
        val a = aktuell ?: return
        spaeter.isEnabled = false
        imHintergrund({ if (stand.vomKern) EinrichtungApi.spaeter(a.id) else EinrichtungApi.Antwort(200, JSONObject()) }) { r ->
            spaeter.isEnabled = true
            if (r != null && r.ok) weiterNach(a.id) else zeigeMeldung(texte.res(R.string.einr_fehler_speichern))
        }
    }

    private fun aufZurueck() {
        val a = aktuell ?: return
        stand.vorheriger(a.id)?.let { zeige(it) }
    }

    /** Sichere Speichern-Hilfe für Schritte mit §0-Antwort (422 → „Bitte … wählen“). */
    fun speichereAntwort(id: String, antwort: JSONObject, fertig: (Boolean, String?) -> Unit) {
        if (!stand.vomKern) {
            // Vorlauf: lokal merken, später nachreichen
            vorlauf.merke(id, antwort)
            fertig(true, null); return
        }
        imHintergrund({ EinrichtungApi.schritt(id, antwort) }) { r ->
            when {
                r == null || r.status == 0 -> fertig(false, texte.res(R.string.einr_fehler_verbindung))
                r.ok -> fertig(true, null)
                r.status == 422 -> fertig(false, texte.res(R.string.einr_fehler_pflicht))
                else -> fertig(false, texte.res(R.string.einr_fehler_speichern))
            }
        }
    }

    // ---------------------------------------------------------------- Aufbau
    private fun farbe(attr: Int, rueckfall: Int): Int = try {
        com.google.android.material.color.MaterialColors.getColor(activity, attr, rueckfall)
    } catch (_: Throwable) { rueckfall }

    val farbeFlaeche: Int get() = farbe(com.google.android.material.R.attr.colorSurface, Color.parseColor("#0B1A26"))
    val farbeText: Int get() = farbe(com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
    val farbeLeise: Int get() = farbe(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.parseColor("#B7C7D3"))
    val farbeAkzent: Int get() = farbe(android.R.attr.colorPrimary, Color.parseColor("#5FD6E6"))
    val farbeWarnung: Int get() = Color.parseColor("#FFB15E")

    fun text(s: CharSequence, sp: Float = 18f, fett: Boolean = false, leise: Boolean = false): TextView = TextView(activity).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_PX, schrift(sp))
        setTextColor(if (leise) farbeLeise else farbeText)
        if (fett) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setLineSpacing(0f, 1.15f)
    }

    fun knopf(s: CharSequence, primaer: Boolean = false, klick: () -> Unit): MaterialButton =
        MaterialButton(activity, null, if (primaer) com.google.android.material.R.attr.materialButtonStyle
            else com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = s
            isAllCaps = false
            minHeight = knopfHoehe
            minimumHeight = knopfHoehe
            setTextSize(TypedValue.COMPLEX_UNIT_PX, schrift(18f))
            setPadding(px(24f), 0, px(24f), 0)
            setOnClickListener { klick() }
        }

    private fun aufbauen() {
        rahmen = FrameLayout(activity).apply {
            setBackgroundColor(farbeFlaeche)
            isClickable = true   // nichts darunter antippbar (einziger Vordergrund)
            isFocusable = true
            elevation = px(24f).toFloat()
        }
        val spalte = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(40f), px(20f), px(40f), px(20f))
        }
        // Kopf: Fortschritt
        val kopf = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fortschrittText = text("", 16f, leise = true)
        fortschrittBalken = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            progressDrawable?.setTint(farbeAkzent)
        }
        kopf.addView(fortschrittText)
        kopf.addView(fortschrittBalken, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(8f)).apply { topMargin = px(6f) })
        spalte.addView(kopf)
        // Inhalt
        inhalt = FrameLayout(activity)
        spalte.addView(inhalt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = px(16f); bottomMargin = px(12f) })
        meldung = text("", 17f).apply {
            setTextColor(farbeWarnung); visibility = View.GONE
            setPadding(0, 0, 0, px(8f))
        }
        spalte.addView(meldung)
        // Fuß: Zurück links, Später + Weiter rechts (primär rechts unten)
        val fuss = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        zurueck = knopf(texte.res(R.string.einr_zurueck)) { aufZurueck() }
        spaeter = knopf(texte.res(R.string.einr_spaeter)) { aufSpaeter() }
        weiter = knopf(texte.res(R.string.einr_weiter), primaer = true) { aufWeiter() }
        fuss.addView(zurueck)
        fuss.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        fuss.addView(spaeter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = px(16f) })
        fuss.addView(weiter)
        spalte.addView(fuss)
        // Systemleisten (Statusleiste oben, ggf. Navigation unten) nicht überdecken
        rahmen.setOnApplyWindowInsetsListener { _, ins ->
            @Suppress("DEPRECATION")
            spalte.setPadding(px(40f), px(20f) + ins.systemWindowInsetTop, px(40f), px(20f) + ins.systemWindowInsetBottom)
            ins
        }
        rahmen.addView(spalte, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        wurzel.addView(rahmen, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Auswahlkarte (ohne Vorauswahl): großer, antippbarer Block mit Titel + Satz; „an“ hebt hervor. */
    fun wahlKarte(titel: CharSequence, satz: CharSequence?, klick: () -> Unit): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(20f), px(16f), px(20f), px(16f))
        minimumHeight = knopfHoehe
        isClickable = true; isFocusable = true
        background = rahmenFlaeche(false)
        addView(text(titel, 19f, fett = true))
        if (!satz.isNullOrEmpty()) addView(text(satz, 16f, leise = true).apply { setPadding(0, px(6f), 0, 0) })
        setOnClickListener { klick() }
    }

    /** Häkchen/Schalter gut treffbar und sichtbar (Regel 2: Bedienung aus Fahrersitz-Entfernung). */
    fun grosserHaken(cb: android.widget.CompoundButton) {
        cb.minHeight = knopfHoehe
        val d = cb.buttonDrawable ?: return
        // Symbol als Compound-Drawable mit fester Größe (34 dp); es folgt dem Zustand (an/aus) der Ansicht.
        cb.buttonDrawable = null
        d.setBounds(0, 0, px(34f), px(34f))
        cb.setCompoundDrawablesRelative(d, null, null, null)
        cb.compoundDrawablePadding = px(14f)
    }

    fun markiere(karte: View, an: Boolean) {
        karte.background = rahmenFlaeche(an)
        karte.isSelected = an
        karte.contentDescription = if (an) texte.res(R.string.einr_gewaehlt) else null
    }

    private fun rahmenFlaeche(an: Boolean) = GradientDrawable().apply {
        cornerRadius = px(14f).toFloat()
        setColor(if (an) (farbeAkzent and 0x00FFFFFF) or 0x33000000 else Color.TRANSPARENT)
        setStroke(px(if (an) 3f else 1.5f), if (an) farbeAkzent else farbeLeise)
    }

    companion object {
        private const val TAG = "Einrichtung"
        /** Läuft gerade ein Assistent? (Regel 10: dann keine anderen Dialoge, z. B. der Autostart-Einzelhinweis) */
        @Volatile @JvmStatic var laeuft = false; private set
    }
}

/**
 * Ein Bildschirm des Assistenten. Unterklassen bauen die Ansicht und liefern ihre Antwort.
 * Standard: „Weiter“ ist möglich, wenn [antwort] vollständig ist (für Pflichtschritte über [Antworten.fehlt]).
 */
abstract class SchrittAnsicht(val id: String, protected val a: Assistent) {
    protected val t: Texte get() = a.texte
    open val spaeterZeigen: Boolean = true
    open val weiterZeigen: Boolean = true

    abstract fun ansicht(): View

    /** Antwort für §0 (null = keine Antwort nötig, z. B. reine Info- oder Android-Schritte). */
    open fun antwort(): JSONObject? = null

    open fun weiterMoeglich(): Boolean {
        val ant = antwort()
        return if (id in Einrichtung.PFLICHT && id in MIT_ANTWORT) Antworten.fehlt(id, ant, mitSim()) == null else true
    }

    open fun weiterText(): String? = null
    open fun mitSim(): Boolean = true
    open fun betreten() {}
    /** Beim Verlassen: nur eigene verzögerte Aufgaben entfernen (der Handler ist geteilt). */
    open fun verlassen() { a.haupt.removeCallbacksAndMessages(this) }

    /** Verzögert ausführen, an diesen Schritt gebunden (wird bei [verlassen] verworfen). */
    protected fun nachher(ms: Long, r: () -> Unit) {
        a.haupt.postAtTime({ r() }, this, android.os.SystemClock.uptimeMillis() + ms)
    }

    /** Standard-Weiter: Antwort speichern (falls vorhanden), sonst Schritt als erledigt melden. */
    open fun weiter(fertig: (Boolean, String?) -> Unit) {
        val ant = antwort() ?: JSONObject().put("ok", true)
        a.speichereAntwort(id, ant, fertig)
    }

    protected fun spalte(): LinearLayout = LinearLayout(a.activity).apply { orientation = LinearLayout.VERTICAL }
    protected fun abstand(v: View, oben: Float = 16f): View { (v.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = a.px(oben); return v }
    protected fun LinearLayout.dazu(v: View, oben: Float = 14f) {
        addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = a.px(oben) })
    }
    protected fun titel(s: CharSequence) = a.text(s, 26f, fett = true)

    companion object {
        /** Pflichtschritte mit §0-Antwort (Prüfung über [Antworten.fehlt]). */
        val MIT_ANTWORT = setOf("sprache", "haftung", "waechter", "betrieb", "karte", "updates")
    }
}
