package at.greenchili.surfmate.onboarding.schritte

import android.util.TypedValue
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import at.greenchili.surfmate.R
import at.greenchili.surfmate.ui.util.QrCodeGenerator
import org.json.JSONObject

/**
 * 0.1.32 Schnellstart, letzter Schritt: Handy koppeln. Der QR-Code steht direkt hier, ohne Umweg über die System-Seite.
 * Die Brücke schickt Daten über unseren Server, darum erst nach dem Häkchen (nie vorausgewählt, Projektinhaber 28.10.).
 * „Später“ lässt alles aus; ein neues Handy in GET /api/kopplungen zeigt der Schritt von selbst an.
 */
class HandySchritt(a: Assistent) : SchrittAnsicht("extra_handy", a) {
    private lateinit var bereich: LinearLayout
    private var verbunden = false
    private var vorher: Set<String> = emptySet()

    override val weiterZeigen: Boolean get() = verbunden

    override fun ansicht(): View = spalte().apply {
        dazu(titel(t.res(R.string.einr_extra_handy_titel)), 0f)
        bereich = spalte()
        dazu(bereich, 12f)
    }

    override fun betreten() {
        if (!a.stand().vomKern) { frage(); return }
        a.imHintergrund({ EinrichtungApi.get("/api/bruecke") }) { r ->
            if (r != null && r.json.optBoolean("ein", false) && HandyQr.zugestimmt(r.json)) qrHolen() else frage()
        }
    }

    private fun frage() {
        bereich.removeAllViews()
        bereich.dazu(a.text(t.res(R.string.einr_handy_kurz), 19f), 0f)
        val meldung = a.text("", 16f).apply { setTextColor(a.farbeWarnung); visibility = View.GONE }
        val knopf = a.knopf(t.res(R.string.einr_handy_qr_knopf), primaer = true) {
            a.imHintergrund({
                EinrichtungApi.transport("POST", "/api/bruecke",
                    JSONObject().put("ein", true).put("zustimmung", true).put("quelle", "einrichtung"))
            }) { r ->
                if (r != null && r.ok && r.json.optBoolean("ok", true)) qrHolen()
                else { meldung.text = t.res(R.string.einr_handy_fehler); meldung.visibility = View.VISIBLE }
            }
        }.apply { isEnabled = false }
        val haken = CheckBox(a.activity).apply {
            text = t.res(R.string.einr_handy_zustimmung)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, a.schrift(18f))
            setTextColor(a.farbeText)
            minHeight = a.knopfHoehe
            isChecked = false
            a.grosserHaken(this)
            setOnCheckedChangeListener { _, an -> knopf.isEnabled = an }
        }
        bereich.dazu(haken, 14f)
        bereich.dazu(a.text(t.res(R.string.einr_handy_datenschutz), 16f, leise = true), 4f)
        bereich.dazu(knopf, 14f)
        bereich.dazu(meldung, 8f)
    }

    private class Qr(val inhalt: String, val gueltigBis: Long, val handys: Set<String>)

    /** Neuer Code samt Brücken-Stand; läuft er ab, holt der Schritt von selbst den nächsten. */
    private fun qrHolen() {
        a.imHintergrund({
            val code = EinrichtungApi.transport("POST", "/api/kopplung/code", JSONObject())
            if (!code.ok || code.json.optString("code").isEmpty()) null else {
                val ts = EinrichtungApi.get("/api/system/tailscale").json
                val dns = (ts.optJSONObject("tailscale") ?: ts).optString("dnsName", "").trimEnd('.')
                Qr(HandyQr.inhalt(code.json.getString("code"), if (dns.isEmpty()) null else "https://$dns",
                    EinrichtungApi.get("/api/bruecke").json, code.json.optString("brueckeEinmal", "").ifEmpty { null }),
                    code.json.optLong("gueltigBis", System.currentTimeMillis() + 600_000L),
                    HandyQr.handyIds(EinrichtungApi.get("/api/kopplungen").json))
            }
        }) { q: Qr? ->
            if (q == null) { frage(); a.zeigeMeldung(t.res(R.string.einr_handy_fehler)); return@imHintergrund }
            vorher = q.handys
            zeigeQr(q.inhalt)
            verlassen()
            nachher(maxOf(30_000L, q.gueltigBis - System.currentTimeMillis() - 5_000L)) { qrHolen() }
            nachher(3000) { pruefeKopplung() }
        }
    }

    private fun zeigeQr(inhalt: String) {
        bereich.removeAllViews()
        val zeile = LinearLayout(a.activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val groesse = a.px(330f)
        zeile.addView(ImageView(a.activity).apply { setImageBitmap(QrCodeGenerator.generate(inhalt, groesse)) },
            LinearLayout.LayoutParams(groesse, groesse))
        val schritte = spalte()
        listOf(R.string.einr_handy_s1, R.string.einr_handy_s2, R.string.einr_handy_s3).forEachIndexed { i, s ->
            schritte.dazu(a.text("${i + 1}. " + t.res(s), 21f), if (i == 0) 0f else 16f)
        }
        schritte.dazu(a.text(t.res(R.string.einr_handy_keine_app), 16f, leise = true), 20f)
        zeile.addView(schritte, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = a.px(32f) })
        bereich.dazu(zeile, 0f)
    }

    private fun pruefeKopplung() {
        a.imHintergrund({ HandyQr.handyIds(EinrichtungApi.get("/api/kopplungen").json) }) { ids: Set<String> ->
            if ((ids - vorher).isEmpty()) { nachher(3000) { pruefeKopplung() }; return@imHintergrund }
            verlassen()   // kein neuer Code mehr
            verbunden = true
            bereich.removeAllViews()
            bereich.dazu(a.text("✓ " + t.res(R.string.einr_handy_verbunden), 26f, fett = true), 24f)
            a.geaendert()
        }
    }

    override fun antwort(): JSONObject = JSONObject()
}
