package at.greenchili.surfmate.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import at.greenchili.surfmate.R
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * „Ereignis von HH:MM gesichert – behalten?“ (Ereignis-Clip 0.1.27, Koordination 29.10.2026, F2).
 *
 * Startet der Kamera-Daemon beim Aussteigen (Fahrstufe → P) und beim nächsten Start, solange eine
 * Frage offen ist. Großer Knopf „Behalten“ (Pflichttext B steht darunter), daneben „Nein danke“.
 * Ohne Ton, ohne Licht. Spricht nur mit dem lokalen Daemon (127.0.0.1), nichts verlässt das Auto.
 */
class EreignisFrageActivity : Activity() {

    private lateinit var titel: TextView
    private lateinit var text: TextView
    private lateinit var behalten: Button
    private lateinit var nein: Button
    private var offen = JSONArray()
    private var index = 0
    private var stunden = 0L
    private var tage = 30

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt() }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
            background = GradientDrawable().apply {
                setColor(Color.rgb(28, 30, 34))
                cornerRadius = dp(16).toFloat()
            }
        }
        titel = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.DEFAULT_BOLD
        }
        text = TextView(this).apply {
            setTextColor(Color.rgb(205, 208, 214))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, dp(12), 0, dp(20))
        }
        val knoepfe = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        nein = Button(this).apply {
            setText(R.string.ereignis_nein)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            minHeight = dp(64)
            setOnClickListener { antworten(false) }
        }
        behalten = Button(this).apply {
            setText(R.string.ereignis_behalten)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.DEFAULT_BOLD
            minHeight = dp(72)
            minWidth = dp(220)
            setOnClickListener { antworten(true) }
        }
        knoepfe.addView(nein, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(16) })
        knoepfe.addView(behalten)
        box.addView(titel)
        box.addView(text)
        box.addView(knoepfe)
        setContentView(box)
        window?.setLayout(dp(640).coerceAtMost(resources.displayMetrics.widthPixels - dp(32)), LinearLayout.LayoutParams.WRAP_CONTENT)
        laden()
    }

    private fun laden() {
        Thread {
            val res = try { JSONObject(http("GET", "/api/ereignisse/offen", null)) } catch (_: Throwable) { null }
            runOnUiThread {
                if (res == null) { finish(); return@runOnUiThread }
                offen = res.optJSONArray("offen") ?: JSONArray()
                stunden = res.optLong("stunden", 0)
                tage = res.optInt("tage", 30)
                // neueste zuerst
                index = offen.length() - 1
                zeigen()
            }
        }.start()
    }

    private fun zeigen() {
        if (index < 0) { finish(); return }
        val o = offen.optJSONObject(index) ?: run { index--; zeigen(); return }
        val zeit = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(o.optLong("zeit", System.currentTimeMillis())))
        titel.text = getString(R.string.ereignis_frage, zeit)
        val b = StringBuilder(getString(R.string.ereignis_pflichttext_b, tage))
        if (stunden > 0) b.append("\n\n").append(getString(R.string.ereignis_erklaerung, stunden.toInt()))
        text.text = b
        behalten.isEnabled = true
        nein.isEnabled = true
    }

    private fun antworten(behaltenJa: Boolean) {
        val o = offen.optJSONObject(index) ?: return
        val datei = o.optString("datei")
        behalten.isEnabled = false
        nein.isEnabled = false
        Thread {
            val body = JSONObject().put("datei", datei).toString()
            val ok = try {
                val r = JSONObject(http("POST", if (behaltenJa) "/api/aufnahmen/behalten" else "/api/ereignisse/erledigt", body))
                r.optBoolean("ok", false)
            } catch (_: Throwable) { false }
            runOnUiThread {
                if (!ok) {
                    Toast.makeText(this, R.string.ereignis_fehler, Toast.LENGTH_LONG).show()
                    behalten.isEnabled = true
                    nein.isEnabled = true
                    return@runOnUiThread
                }
                if (behaltenJa) Toast.makeText(this, R.string.ereignis_gespeichert, Toast.LENGTH_SHORT).show()
                index--
                zeigen()
            }
        }.start()
    }

    private fun http(methode: String, pfad: String, body: String?): String {
        val c = URL("http://127.0.0.1:8180$pfad").openConnection() as HttpURLConnection
        try {
            c.requestMethod = methode
            c.connectTimeout = 2000
            c.readTimeout = 5000
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val s = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            return s?.bufferedReader()?.use { it.readText() } ?: "{}"
        } finally {
            c.disconnect()
        }
    }
}
