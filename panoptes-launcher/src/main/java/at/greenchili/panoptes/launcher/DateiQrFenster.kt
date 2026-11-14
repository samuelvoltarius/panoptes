package at.greenchili.panoptes.launcher

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 0.1.43.4 (Kleinkram, F7): Fenster „Datei vom Handy“ im Launcher. Links ein großer QR-Code, rechts Zustand, Adresse,
 * Speicherordner, Countdown, „QR erneuern“ und „Adresse kopieren“.
 *
 * <p>Alles Wichtige macht der Kern (`POST /api/dateien/qr`): Einmal-Kennung, nur im lokalen Netz, 120 s, eine Datei je
 * Code, Ziel ist der Panoptes-Ordner. Das Fenster zeigt nur, was der Kern liefert; ohne lokales Netz zeigt es das ehrlich
 * (kein Code). Die Kennung steht nur im QR-Code und in der Zwischenablage (auf Tipp), nie in einem Log.
 * Die Regeln (Countdown-Text, Zustandswahl) sind rein und im Unit-Test belegt; [QrAnsicht] ist die gemeinsame
 * Hilfe für alle QR-Fenster des Launchers (auch die Aufnahmen-Liste).
 */
object DateiQr {

    /** Rein: Restzeit „1:57“; nie negativ, nie mehr als 59 Minuten. */
    fun restText(sekunden: Long): String {
        val s = sekunden.coerceIn(0L, 3599L)
        return String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    /** Was das Fenster zeigt: aus dem Zustand des Kerns. */
    enum class Anzeige { WARTE, UEBERTRAGUNG, FERTIG, ABGELAUFEN, KEIN_NETZ, FEHLER }

    /** Rein: Anzeige aus der Kern-Antwort ({@code ok, aktiv, zustand}); null = noch keine Antwort. */
    fun anzeige(kern: JSONObject?): Anzeige {
        if (kern == null) return Anzeige.FEHLER
        if (!kern.optBoolean("ok", false)) {
            return if (kern.optString("grund") == "kein_lan") Anzeige.KEIN_NETZ else Anzeige.FEHLER
        }
        if (kern.has("aktiv") && !kern.optBoolean("aktiv", false)) return Anzeige.ABGELAUFEN
        return when (kern.optString("zustand")) {
            "laeuft" -> Anzeige.UEBERTRAGUNG
            "fertig" -> Anzeige.FERTIG
            "abgelaufen" -> Anzeige.ABGELAUFEN
            else -> Anzeige.WARTE
        }
    }

    /** Rein: darf „QR erneuern“ gedrückt werden (nicht mitten in einer Übertragung)? */
    fun erneuernErlaubt(a: Anzeige): Boolean = a != Anzeige.UEBERTRAGUNG

    /** Adresse ist nur dann ein lokaler Link, wenn sie mit http://<private IPv4> beginnt (Schutz vor falschen Kern-Antworten). */
    fun lokaleAdresse(url: String?): Boolean {
        if (url == null) return false
        val m = Regex("^http://(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3}):\\d{2,5}/").find(url) ?: return false
        val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
    }
}

/** Gemeinsame QR-Zeichnung des Launchers (zxing, Apache-2.0): schwarz auf weiß, mit Rand, damit Handys ihn sicher lesen. */
object QrAnsicht {
    fun bitmap(inhalt: String, groesse: Int): Bitmap? = try {
        val m = QRCodeWriter().encode(inhalt, BarcodeFormat.QR_CODE, groesse, groesse,
            mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8"))
        val px = IntArray(groesse * groesse)
        for (y in 0 until groesse) for (x in 0 until groesse) px[y * groesse + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
        Bitmap.createBitmap(px, groesse, groesse, Bitmap.Config.ARGB_8888)
    } catch (_: Exception) {
        null
    }

    /**
     * 0.1.43.4 (Paket S): Ohne Panoptes (fehlt, oder Kern hat seit dem Start nie geantwortet) gibt es keinen QR-Weg. Statt
     * Fehlermeldung und Dauerversuchen zeigt der Launcher ruhig „Mit Panoptes: <Titel>“ mit Link. Liest nur
     * [KernVerbindung], misst selbst nie. true = der ruhige Hinweis wurde gezeigt, das Fenster macht nichts weiter.
     */
    fun ohnePanoptesHinweis(activity: Activity, ui: Ui, titel: String): Boolean {
        if (!KernVerbindung.ohnePanoptes()) return false
        val d = Dialog(activity)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val spalte = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.karte(false)
            setPadding(ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(24))
        }
        spalte.addView(ui.titel(activity.getString(R.string.mit_panoptes, titel)))
        val knoepfe = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        knoepfe.addView(ui.act(null, activity.getString(R.string.mit_panoptes_link), primary = true) {
            if (!KernVerbindung.panoptesOeffnen(activity))
                spalte.addView(ui.text(activity.getString(R.string.mit_panoptes_seite, KernVerbindung.PANOPTES_SEITE), 14f), ui.lp(-2, -2, oben = 10))
        }, ui.lp(-2, ui.bd(56), rechts = 12))
        knoepfe.addView(ui.act(null, activity.getString(R.string.datei_qr_schliessen)) { d.dismiss() }, ui.lp(-2, ui.bd(56)))
        spalte.addView(knoepfe, ui.lp(-2, -2, oben = 16))
        d.setContentView(spalte)
        d.show()
        d.window?.setLayout((activity.resources.displayMetrics.widthPixels * 0.6f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        return true
    }
}

/** Das Fenster selbst. Eine Instanz je Öffnen; schließt sich mit [Dialog.dismiss] und stoppt dann alle Abfragen. */
class DateiQrFenster(private val activity: Activity, private val ui: Ui) {

    private val kern = "http://127.0.0.1:8180/api/dateien/qr"
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "DateiQrFenster").apply { isDaemon = true } }
    private val ui2 = Handler(Looper.getMainLooper())
    private var dialog: Dialog? = null
    private var url: String? = null
    private var zu = false

    private lateinit var bild: ImageView
    private lateinit var zeileStand: TextView
    private lateinit var zeileAdresse: TextView
    private lateinit var zeileOrdner: TextView
    private lateinit var zeileRest: TextView
    private var ablaufBis = 0L

    fun zeigen() {
        if (QrAnsicht.ohnePanoptesHinweis(activity, ui, activity.getString(R.string.datei_qr_titel))) return
        val d = Dialog(activity)
        dialog = d
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val wurzel = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ui.karte(false)
            setPadding(ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(24))
            gravity = Gravity.CENTER_VERTICAL
        }
        val q = ui.dp(300)
        bild = ImageView(activity).apply { setBackgroundColor(Color.WHITE) }
        wurzel.addView(bild, LinearLayout.LayoutParams(q, q))
        val spalte = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        spalte.addView(ui.titel(activity.getString(R.string.datei_qr_titel)))
        zeileStand = ui.text("", 18f).also { spalte.addView(it, ui.lp(-2, -2, oben = 10)) }
        zeileAdresse = ui.text("", 15f).also { spalte.addView(it, ui.lp(-2, -2, oben = 10)) }
        zeileOrdner = ui.text("", 15f).also { spalte.addView(it, ui.lp(-2, -2, oben = 4)) }
        zeileRest = ui.text("", 15f).also { spalte.addView(it, ui.lp(-2, -2, oben = 4)) }
        spalte.addView(ui.text(activity.getString(R.string.datei_qr_hinweis), 14f), ui.lp(-2, -2, oben = 10))
        val knoepfe = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        knoepfe.addView(ui.act(null, activity.getString(R.string.datei_qr_kopieren)) { kopieren() }, ui.lp(-2, ui.bd(56), rechts = 12))
        knoepfe.addView(ui.act(null, activity.getString(R.string.datei_qr_erneuern), primary = true) { neu() }, ui.lp(-2, ui.bd(56), rechts = 12))
        knoepfe.addView(ui.act(null, activity.getString(R.string.datei_qr_schliessen)) { d.dismiss() }, ui.lp(-2, ui.bd(56)))
        spalte.addView(knoepfe, ui.lp(-2, -2, oben = 16))
        wurzel.addView(spalte, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ui.dp(24) })
        d.setContentView(wurzel)
        d.setOnDismissListener { zu = true; io.execute { aufruf("POST", "$kern/ende") } }
        d.show()
        d.window?.setLayout((activity.resources.displayMetrics.widthPixels * 0.8f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        neu(erstes = true)
    }

    private fun aufruf(methode: String, ziel: String): JSONObject? = try {
        val c = (URL(ziel).openConnection() as HttpURLConnection).apply {
            requestMethod = methode; connectTimeout = 1800; readTimeout = 3000; useCaches = false
            setRequestProperty("Accept", "application/json")
            if (methode == "POST") { doOutput = true; setRequestProperty("Content-Type", "application/json"); outputStream.use { it.write("{}".toByteArray()) } }
        }
        try {
            val s = c.responseCode
            val t = (if (s in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            JSONObject(t.ifEmpty { "{}" })
        } finally { c.disconnect() }
    } catch (_: Exception) { null }

    /** Neuen Code holen („QR erneuern“): der alte ist damit ungültig. */
    private fun neu(erstes: Boolean = false) {
        io.execute {
            val a = aufruf("POST", kern)
            ui2.post { if (!zu) { zeige(a, true); if (erstes) takt() } }
        }
    }

    private fun takt() {
        if (zu || KernVerbindung.ohnePanoptes()) return   // keine Dauerversuche ohne Kern
        io.execute {
            val a = aufruf("GET", kern)
            ui2.post { if (!zu) { zeige(a, false); ui2.postDelayed({ takt() }, 1000) } }
        }
    }

    private fun zeige(a: JSONObject?, neuerCode: Boolean) {
        val an = DateiQr.anzeige(a)
        if (neuerCode && a != null && a.optBoolean("ok", false)) {
            val u = a.optString("url")
            if (DateiQr.lokaleAdresse(u)) {
                url = u
                bild.setImageBitmap(QrAnsicht.bitmap(u, ui.dp(300)))
                zeileAdresse.text = activity.getString(R.string.datei_qr_adresse, a.optString("lan"))
                zeileOrdner.text = activity.getString(R.string.datei_qr_ordner, a.optString("ordner"))
            }
        }
        if (neuerCode && an == DateiQr.Anzeige.KEIN_NETZ) { url = null; bild.setImageDrawable(null); zeileAdresse.text = "" }
        if (a != null && a.has("ablaufSek")) ablaufBis = System.currentTimeMillis() + a.optLong("ablaufSek") * 1000
        zeileStand.text = when (an) {
            DateiQr.Anzeige.WARTE -> activity.getString(R.string.datei_qr_warte)
            DateiQr.Anzeige.UEBERTRAGUNG -> activity.getString(R.string.datei_qr_laeuft_ue)
            DateiQr.Anzeige.FERTIG -> activity.getString(R.string.datei_qr_empfangen, a?.optString("name").orEmpty())
            DateiQr.Anzeige.ABGELAUFEN -> activity.getString(R.string.datei_qr_abgelaufen)
            DateiQr.Anzeige.KEIN_NETZ -> activity.getString(R.string.datei_qr_kein_lan)
            DateiQr.Anzeige.FEHLER -> activity.getString(R.string.datei_qr_fehler)
        }
        zeileRest.text = if (an == DateiQr.Anzeige.WARTE || an == DateiQr.Anzeige.UEBERTRAGUNG)
            activity.getString(R.string.datei_qr_laeuft_ab, DateiQr.restText((ablaufBis - System.currentTimeMillis() + 999) / 1000)) else ""
    }

    /** Adresse in die Zwischenablage (auf Tipp). Der Link enthält die Einmal-Kennung, gilt aber nur 120 s im lokalen Netz. */
    private fun kopieren() {
        val u = url ?: return
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("Panoptes", u))
    }
}
