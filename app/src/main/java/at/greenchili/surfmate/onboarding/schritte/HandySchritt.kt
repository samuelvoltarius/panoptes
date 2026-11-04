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
        // 0.1.38.3: ausdrücklich fragen „unterwegs nutzen?“ (Standard bleibt aus), mit Klartext, was das bedeutet
        bereich.dazu(a.text(t.res(R.string.einr_handy_unterwegs_frage), 22f, fett = true), 0f)
        bereich.dazu(a.text(t.res(R.string.einr_handy_unterwegs_text), 19f), 8f)
        val meldung = a.text("", 16f).apply { setTextColor(a.farbeWarnung); visibility = View.GONE }
        val knopf = a.knopf(t.res(R.string.einr_handy_unterwegs_ja), primaer = true) {
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
        // Ohne Brücke: QR-Code nur fürs gleiche WLAN (nichts geht über unseren Server)
        bereich.dazu(a.knopf(t.res(R.string.einr_handy_nur_wlan)) { qrHolen() }, 10f)
        bereich.dazu(meldung, 8f)
    }

    /** grund: null = Kern sagt nichts (älter), "" = geht unterwegs, "aus"/"kein_internet" = nur im gleichen WLAN. */
    private class Qr(val inhalt: String, val gueltigBis: Long, val handys: Set<String>, val grund: String?)

    /** Neuer Code samt Brücken-Stand; läuft er ab, holt der Schritt von selbst den nächsten. */
    private fun qrHolen() {
        a.imHintergrund({
            val code = EinrichtungApi.transport("POST", "/api/kopplung/code", JSONObject())
            if (!code.ok || code.json.optString("code").isEmpty()) null else {
                val ts = EinrichtungApi.get("/api/system/tailscale").json
                val dns = (ts.optJSONObject("tailscale") ?: ts).optString("dnsName", "").trimEnd('.')
                val grund = when {
                    !code.json.has("brueckeEinmalGrund") -> null
                    code.json.isNull("brueckeEinmalGrund") -> ""
                    else -> code.json.optString("brueckeEinmalGrund")
                }
                Qr(HandyQr.inhalt(code.json.getString("code"), if (dns.isEmpty()) null else "https://$dns",
                    EinrichtungApi.get("/api/bruecke").json, code.json.optString("brueckeEinmal", "").ifEmpty { null }, grund),
                    code.json.optLong("gueltigBis", System.currentTimeMillis() + 600_000L),
                    HandyQr.handyIds(EinrichtungApi.get("/api/kopplungen").json), grund)
            }
        }) { q: Qr? ->
            if (q == null) { frage(); a.zeigeMeldung(t.res(R.string.einr_handy_fehler)); return@imHintergrund }
            vorher = q.handys
            zeigeQr(q.inhalt, q.grund)
            verlassen()
            nachher(maxOf(30_000L, q.gueltigBis - System.currentTimeMillis() - 5_000L)) { qrHolen() }
            nachher(3000) { pruefeKopplung() }
        }
    }

    private fun zeigeQr(inhalt: String, grund: String?) {
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
        // 0.1.38.3: ehrlich sagen, ob der Code auch unterwegs geht (gleiche Texte wie die Web-Karte, system.pair_*)
        val satz = when (grund) {
            "" -> "✓ " + t.web("system.pair_unterwegs", "Dieser Code geht auch unterwegs.")
            "aus" -> t.web("system.pair_nur_wlan", "Dieser Code geht nur, wenn dein Handy im gleichen WLAN wie das Auto ist.")
            "kein_internet" -> t.web("system.pair_kein_internet", "Das Auto hat gerade kein Internet. Der Code geht nur im gleichen WLAN.")
            else -> null
        }
        satz?.let { s ->
            bereich.dazu(a.text(s, 19f, fett = true).apply { if (grund != "") setTextColor(a.farbeWarnung) }, 18f)
        }
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
