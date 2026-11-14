package at.greenchili.panoptes.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale

/** Eine Zeile der Aufnahmen-Liste (nur Anzeigefelder, die der Kern liefert). */
data class AufnahmeZeile(val id: String, val kamera: Int, val quelleAllein: Boolean, val ausloeser: String?, val zeitMs: Long, val bytes: Long, val da: Boolean)
data class AufnahmenStand(val zeilen: List<AufnahmeZeile>, val gespeichert: Int, val angezeigt: Int)

/**
 * 0.1.43.4 (Kamera im Launcher): Auswahl der Kamera (360°, vorne, hinten, links, rechts) und Liste der Aufnahmen.
 *
 * Der Launcher zeigt selbst kein Video. Er wählt nur die Kamera vor und öffnet die Panoptes-Liveansicht bzw. spielt die
 * gewählte Aufnahme in Panoptes ab; dort gelten die Regeln des Kamera-Daemons. Video gibt es nur in P und bei 0 km/h
 * ([erlaubt]); die Fahrsperre in Panoptes bleibt unverändert. Die Liste kommt über den nur lesenden Kern-Weg
 * `GET /api/launcher/v1/aufnahmen`. Es gibt keinen Lösch-, Schreib- oder Befehlsweg. Regeln rein, im Unit-Test belegt.
 */
object Kamera {
    /** Kennungen wie in der Panoptes-Liveansicht: 0 = alle (360°), 1 vorne, 2 rechts, 3 hinten, 4 links. */
    val ANORDNUNG = listOf(0, 1, 3, 4, 2)
    val ZEITEN = listOf("alle", "heute", "7", "30")
    val SORTIERUNGEN = listOf("neu", "alt")
    /** Extras für Panoptes (Deep-Link): vorgewählte Kamera bzw. abzuspielende Aufnahme. */
    const val EXTRA_KAMERA = "kamera_id"
    const val EXTRA_AUFNAHME = "aufnahme_id"

    /** Video und Liste nur in P bei 0 km/h; unbekannter Gang = nein. Unbekanntes Tempo bei P zählt als Stand. */
    fun erlaubt(gang: String?, tempoKmh: Double?): Boolean = gang == "P" && (tempoKmh == null || tempoKmh <= 0.5)

    fun gueltig(kamera: Int) = kamera in 0..4

    fun nameRes(kamera: Int): Int = when (kamera) {
        1 -> R.string.kamera_vorne
        2 -> R.string.kamera_rechts
        3 -> R.string.kamera_hinten
        4 -> R.string.kamera_links
        else -> R.string.kamera_360
    }

    fun zeitRes(z: String): Int = when (z) {
        "heute" -> R.string.aufn_zeit_heute
        "7" -> R.string.aufn_zeit_7
        "30" -> R.string.aufn_zeit_30
        else -> R.string.aufn_zeit_alle
    }

    fun sortRes(s: String): Int = if (s == "alt") R.string.aufn_sort_alt else R.string.aufn_sort_neu

    /** Kennung der Aufnahme: nur harmlose Zeichen (geht als Extra an Panoptes, dort noch einmal geprüft). */
    fun idGueltig(id: String?): Boolean = id != null && Regex("[A-Za-z0-9_.-]{1,120}").matches(id)

    /** Größe in Klartext: B, KB, MB, GB (Dezimalkomma je Sprache). */
    fun groesse(bytes: Long, sprache: Locale = Locale.getDefault()): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> String.format(sprache, "%.0f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(sprache, "%.1f MB", bytes / 1048576.0)
        else -> String.format(sprache, "%.2f GB", bytes / 1073741824.0)
    }

    fun parse(json: String?): AufnahmenStand? = try {
        val o = JSONObject(json ?: "")
        if (!o.optBoolean("ok", false)) null
        else {
            val a = o.optJSONArray("aufnahmen")
            val z = buildList {
                for (i in 0 until (a?.length() ?: 0)) {
                    val r = a!!.optJSONObject(i) ?: continue
                    val id = r.optString("id")
                    if (!idGueltig(id)) continue
                    add(AufnahmeZeile(id, r.optInt("kamera", 0), r.optString("quelle") == "allein",
                        r.optString("ausloeser").ifEmpty { null }, r.optLong("zeit", 0L), r.optLong("groesse", 0L), r.optBoolean("da", true)))
                }
            }
            AufnahmenStand(z, o.optInt("gespeichert", z.size), o.optInt("angezeigt", z.size))
        }
    } catch (_: Exception) {
        null
    }
}

/** Kachel „Live-Kamera“: Auswahl der Kamera und Zugang zur Liste. Kein eigenes Video, kein Befehl ans Auto. */
class KameraKachel(
    private val ctx: Context,
    private val ui: Ui,
    private val reg: (() -> Unit) -> Unit,
    private val erlaubt: () -> Boolean,
    private val live: (Int) -> Unit,
    private val liste: () -> Unit,
    private val hinweis: (String) -> Unit
) {
    fun ansicht(gross: Boolean): View {
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(18))
        }
        c.addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.icon(R.drawable.ic_pn_camera, ui.p.aqua, if (gross) 30 else 22))
            addView(ui.titel(ctx.getString(R.string.widget_kamera)).apply { setPadding(ui.dp(8), 0, 0, 0) })
        })
        val reihe = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        val chips = Kamera.ANORDNUNG.map { id ->
            ui.pill(ctx.getString(Kamera.nameRes(id))).apply {
                textSize = 12f; maxLines = 1; setPadding(ui.dp(6), ui.dp(8), ui.dp(6), ui.dp(8))
                isClickable = true
                setOnClickListener { if (erlaubt()) live(id) else hinweis(ctx.getString(R.string.kamera_nur_stand)) }
            }.also { reihe.addView(it, ui.lp(0, -2, 1f, rechts = 4)) }
        }
        c.addView(reihe, ui.lp(-1, -2, oben = 10))
        c.addView(ui.act(R.drawable.ic_pn_camera, ctx.getString(R.string.kamera_aufnahmen)) {
            if (erlaubt()) liste() else hinweis(ctx.getString(R.string.kamera_nur_stand))
        }.apply { orientation = LinearLayout.HORIZONTAL; (getChildAt(1) as? TextView)?.setPadding(ui.dp(10), 0, 0, 0) },
            ui.lp(-1, ui.dp(64), oben = 10))
        val hint = ui.leise(ctx.getString(R.string.kamera_nur_stand), 13f)
        c.addView(hint, ui.lp(oben = 8))
        reg {
            val ok = erlaubt()
            chips.forEach { it.alpha = if (ok) 1f else 0.5f }
            hint.visibility = if (ok) View.GONE else View.VISIBLE
        }
        return Einpassen(ctx, c)
    }
}

/** Großes Fenster „Aufnahmen“: Filter (Zeit, Sortierung), Zähler, Zeilen mit Plaketten und Abspielen. Nur lesen. */
class AufnahmenFenster(
    private val ctx: Context,
    private val ui: Ui,
    private val lade: (zeit: String, sort: String) -> String?,
    private val spielen: (String) -> Unit,
    private val qr: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var zeit = "alle"
    private var sort = "neu"
    private var gen = 0

    fun ansicht(kopf: View): View {
        val spalte = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(28), ui.dp(20), ui.dp(28), ui.dp(20)) }
        spalte.addView(kopf)
        val filter = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        val zaehler = ui.leise("", 14f)
        val liste = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        lateinit var neuLaden: () -> Unit
        fun filterZeichnen() {
            filter.removeAllViews()
            Kamera.ZEITEN.forEach { z ->
                filter.addView(ui.pill(ctx.getString(Kamera.zeitRes(z)), z == zeit).apply { isClickable = true; setOnClickListener { zeit = z; neuLaden() } }, ui.lp(rechts = 8))
            }
            filter.addView(View(ctx), ui.lp(0, 1, 1f))
            Kamera.SORTIERUNGEN.forEach { s ->
                filter.addView(ui.pill(ctx.getString(Kamera.sortRes(s)), s == sort).apply { isClickable = true; setOnClickListener { sort = s; neuLaden() } }, ui.lp(rechts = 8))
            }
        }
        neuLaden = {
            filterZeichnen()
            val mein = ++gen
            liste.removeAllViews()
            liste.addView(ui.leise(ctx.getString(R.string.aufn_laedt), 16f))
            zaehler.text = ""
            val z = zeit; val s = sort
            Thread {
                val stand = Kamera.parse(try { lade(z, s) } catch (_: Exception) { null })
                main.post { if (mein == gen) zeichnen(liste, zaehler, stand) }
            }.start()
        }
        spalte.addView(filter, ui.lp(-1, -2, oben = 12))
        spalte.addView(zaehler, ui.lp(oben = 8))
        spalte.addView(ScrollView(ctx).apply { addView(liste) }, ui.lp(-1, 0, 1f, oben = 8))
        neuLaden()
        return spalte
    }

    private fun zeichnen(liste: LinearLayout, zaehler: TextView, stand: AufnahmenStand?) {
        liste.removeAllViews()
        if (stand == null) { liste.addView(ui.leise(ctx.getString(R.string.aufn_nicht_lesbar), 16f)); return }
        zaehler.text = ctx.getString(R.string.aufn_zaehler, stand.gespeichert, stand.angezeigt)
        if (stand.zeilen.isEmpty()) { liste.addView(ui.leise(ctx.getString(R.string.aufn_leer), 16f)); return }
        val datum = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        stand.zeilen.forEach { z ->
            val zeile = LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
                background = ui.karte()
            }
            val text = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            text.addView(ui.text(ctx.getString(Kamera.nameRes(z.kamera)), 18f, 700))
            text.addView(ui.leise(datum.format(java.util.Date(z.zeitMs)) + " · " + Kamera.groesse(z.bytes), 14f))
            val plaketten = LinearLayout(ctx)
            plaketten.addView(ui.pill(ctx.getString(if (z.quelleAllein) R.string.aufn_quelle_allein else R.string.aufn_quelle_fahrt)), ui.lp(rechts = 6))
            when (z.ausloeser) {
                "manuell" -> plaketten.addView(ui.pill(ctx.getString(R.string.aufn_ausl_manuell)), ui.lp(rechts = 6))
                "auto" -> plaketten.addView(ui.pill(ctx.getString(R.string.aufn_ausl_auto)), ui.lp(rechts = 6))
            }
            plaketten.addView(ui.pill(ctx.getString(if (z.da) R.string.aufn_da else R.string.aufn_fehlt), z.da), ui.lp())
            text.addView(plaketten, ui.lp(oben = 6))
            zeile.addView(text, ui.lp(0, -2, 1f))
            if (z.da) {
                zeile.addView(ui.act(R.drawable.ic_pn_play, ctx.getString(R.string.aufn_abspielen)) { spielen(z.id) }, ui.lp(ui.dp(110), ui.dp(64), rechts = 8))
                zeile.addView(ui.act(R.drawable.ic_pn_camera, ctx.getString(R.string.aufn_qr)) { qr(z.id) }, ui.lp(ui.dp(110), ui.dp(64)))
            }
            liste.addView(zeile, ui.lp(-1, -2, oben = 10))
        }
    }
}

/**
 * QR-Download einer Aufnahme aufs Handy (nur lokales Netz, Einmal-Kennung, 120 s; alles macht der Kern über
 * `POST /api/dateien/qr` mit {zweck:holen, art:aufnahme, id}). Das Fenster zeigt nur, was der Kern liefert; ohne lokales
 * Netz kein Code. Schließt sich selbst, wenn der Download fertig ist oder der Code abläuft.
 */
class AufnahmeQrFenster(private val activity: android.app.Activity, private val ui: Ui, private val id: String) {
    private val kern = "http://127.0.0.1:8180/api/dateien/qr"
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "AufnahmeQr").apply { isDaemon = true } }
    private val ui2 = Handler(Looper.getMainLooper())
    private var zu = false
    private var ablaufBis = 0L

    fun zeigen() {
        if (!Kamera.idGueltig(id)) return
        if (QrAnsicht.ohnePanoptesHinweis(activity, ui, activity.getString(R.string.aufn_qr_titel))) return
        val d = android.app.Dialog(activity)
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        val wurzel = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = ui.karte(false); setPadding(ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(24))
        }
        val q = ui.dp(300)
        val bild = android.widget.ImageView(activity).apply { setBackgroundColor(android.graphics.Color.WHITE) }
        wurzel.addView(bild, LinearLayout.LayoutParams(q, q))
        val spalte = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        spalte.addView(ui.titel(activity.getString(R.string.aufn_qr_titel)))
        val stand = ui.text("", 18f).also { spalte.addView(it, ui.lp(-2, -2, oben = 10)) }
        val adresse = ui.text("", 15f).also { spalte.addView(it, ui.lp(-2, -2, oben = 10)) }
        val rest = ui.text("", 15f).also { spalte.addView(it, ui.lp(-2, -2, oben = 4)) }
        spalte.addView(ui.text(activity.getString(R.string.datei_qr_hinweis), 14f), ui.lp(-2, -2, oben = 10))
        val knoepfe = LinearLayout(activity)
        knoepfe.addView(ui.act(null, activity.getString(R.string.datei_qr_erneuern), primary = true) { neu(bild, stand, adresse, rest) }, ui.lp(-2, ui.bd(56), rechts = 12))
        knoepfe.addView(ui.act(null, activity.getString(R.string.datei_qr_schliessen)) { d.dismiss() }, ui.lp(-2, ui.bd(56)))
        spalte.addView(knoepfe, ui.lp(-2, -2, oben = 16))
        wurzel.addView(spalte, LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ui.dp(24) })
        d.setContentView(wurzel)
        d.setOnDismissListener { zu = true; io.execute { aufruf("POST", "$kern/ende", "{}") } }
        d.show()
        d.window?.setLayout((activity.resources.displayMetrics.widthPixels * 0.8f).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        neu(bild, stand, adresse, rest, d)
    }

    private fun aufruf(methode: String, ziel: String, body: String?): JSONObject? = try {
        val c = (java.net.URL(ziel).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = methode; connectTimeout = 1800; readTimeout = 3000; useCaches = false
            setRequestProperty("Accept", "application/json")
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json"); outputStream.use { it.write(body.toByteArray()) } }
        }
        try {
            val s = c.responseCode
            val t = (if (s in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            JSONObject(t.ifEmpty { "{}" })
        } finally { c.disconnect() }
    } catch (_: Exception) { null }

    private var dialogRef: android.app.Dialog? = null

    private fun neu(bild: android.widget.ImageView, stand: TextView, adresse: TextView, rest: TextView, d: android.app.Dialog? = null) {
        if (d != null) dialogRef = d
        io.execute {
            val a = aufruf("POST", kern, JSONObject().put("zweck", "holen").put("art", "aufnahme").put("id", id).toString())
            ui2.post { if (!zu) { zeige(a, true, bild, stand, adresse, rest); takt(bild, stand, adresse, rest) } }
        }
    }

    private var taktLaeuft = false
    private fun takt(bild: android.widget.ImageView, stand: TextView, adresse: TextView, rest: TextView) {
        if (taktLaeuft) return
        taktLaeuft = true
        fun runde() {
            if (zu || KernVerbindung.ohnePanoptes()) return   // keine Dauerversuche ohne Kern
            io.execute {
                val a = aufruf("GET", kern, null)
                ui2.post {
                    if (zu) return@post
                    zeige(a, false, bild, stand, adresse, rest)
                    if (DateiQr.anzeige(a) == DateiQr.Anzeige.FERTIG) { dialogRef?.dismiss(); return@post }
                    ui2.postDelayed({ runde() }, 1000)
                }
            }
        }
        runde()
    }

    private fun zeige(a: JSONObject?, neuerCode: Boolean, bild: android.widget.ImageView, stand: TextView, adresse: TextView, rest: TextView) {
        val an = DateiQr.anzeige(a)
        if (neuerCode && a != null && a.optBoolean("ok", false)) {
            val u = a.optString("url")
            if (DateiQr.lokaleAdresse(u)) {
                bild.setImageBitmap(QrAnsicht.bitmap(u, ui.dp(300)))
                adresse.text = activity.getString(R.string.datei_qr_adresse, a.optString("lan"))
            }
        }
        if (neuerCode && an != DateiQr.Anzeige.WARTE) { bild.setImageDrawable(null); adresse.text = "" }
        if (a != null && a.has("ablaufSek")) ablaufBis = System.currentTimeMillis() + a.optLong("ablaufSek") * 1000
        stand.text = when (an) {
            DateiQr.Anzeige.WARTE -> activity.getString(R.string.datei_qr_warte)
            DateiQr.Anzeige.UEBERTRAGUNG -> activity.getString(R.string.datei_qr_laeuft_ue)
            DateiQr.Anzeige.FERTIG -> ""
            DateiQr.Anzeige.ABGELAUFEN -> activity.getString(R.string.datei_qr_abgelaufen)
            DateiQr.Anzeige.KEIN_NETZ -> activity.getString(R.string.datei_qr_kein_lan)
            DateiQr.Anzeige.FEHLER -> activity.getString(R.string.datei_qr_fehler)
        }
        rest.text = if (an == DateiQr.Anzeige.WARTE || an == DateiQr.Anzeige.UEBERTRAGUNG)
            activity.getString(R.string.datei_qr_laeuft_ab, DateiQr.restText((ablaufBis - System.currentTimeMillis() + 999) / 1000)) else ""
    }
}
