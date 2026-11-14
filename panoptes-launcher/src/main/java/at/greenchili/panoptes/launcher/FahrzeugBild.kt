package at.greenchili.panoptes.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 0.1.43.4 (Rang 1, Koordination 13.11.: „Fahrzeugbild und Szenen“): Dreiviertel-Ansicht des eigenen Autos als
 * vorgerendertes Bild aus den Modellen des Panoptes-Katalogs (Blender, `tools/render_launcher_fahrzeug.py`).
 * Nur die fünf CC-BY-Vorlagen (Nazh Design: Dolphin Surf, Seal, Seal U, Dolphin, Atto 3; Projektinhaber 13.11.: „nur die 5
 * guten Modelle“), Quelle in `MODELLE_LIZENZEN.md`; keine KI-Bilder, keine Fremdbilder. Die Eigenbau-Modelle haben
 * kein Bild hier und fallen auf das Kern-Renderbild bzw. die gezeichnete Form zurück.
 *
 * Eine Modellquelle: Welche Modelle ein Bild haben, steht nicht hier im Code, sondern in `assets/fahrzeug/katalog.json`.
 * Das schreibt `tools/launcher_fahrzeugbilder.py` aus dem Panoptes-Modellkatalog (`web/shared/models/manifest.json`,
 * Einträge mit `"top": true`) – dieselben GLB-Dateien (SHA-256), die Panoptes als 3D zeigt, und dieselbe Lizenzliste.
 *
 * Fotos (13.11.): Modelle ohne gutes 3D-Modell, aber mit frei lizenziertem Foto im Manifest (`"photo"`), zeigen
 * statt der Zeichnung `<id>_foto.webp` mit Namensnennung (Urheber, Lizenz) und der Bitte um ein 3D-Modell. Beim Foto
 * entfallen Leuchtpunkte und Bodenlicht; die Plaketten bleiben. Ein Rendering geht immer vor dem Foto.
 *
 * Je Modell liegen im Launcher (`assets/fahrzeug/`):
 * - `<id>_grund.webp` – Auto mit schwarzem Glanzlack (Glas, Räder, Licht, Reflexe) und weichem Bodenschatten,
 * - `<id>_lack.webp`  – Anteil des Lacks (Graustufe, sRGB-Kurve),
 * - `<id>_punkte.json` – Ankerpunkte (Türen, Fenster, Klappen, Dach, Räder) in Bild-Anteilen 0..1.
 *
 * Die echte Lackfarbe aus dem Kern wird einmal je Modell/Farbe linear eingerechnet: `sRGB(lin(Grund) + lin(Lack) ×
 * lin(Farbe))` – so passt jede Farbe ohne eigenes Rendering. Das Ergebnis wird gemerkt (höchstens zwei Bilder).
 * Bildgröße 1200 × 675 (16:9 wie [FahrzeugStart.BILD_SEITEN]); als Bitmap ~3,2 MB.
 */
object FahrzeugBild {
    data class Anker(val x: Float, val y: Float, val sichtbar: Boolean)

    const val ORDNER = "fahrzeug"
    const val BREITE = 1200
    const val HOEHE = 675

    const val KATALOG = "katalog.json"

    /** Normalisierte Modell-ID bzw. Alias ([FahrzeugModelle.norm]) → Asset-Name; Asset-Name → Namensnennung. */
    data class Katalog(
        val asset: Map<String, String>, val nennung: Map<String, String>,
        val foto: Map<String, String> = emptyMap(), val fotoNennung: Map<String, String> = emptyMap(),
        val bitteUrl: String? = null
    )

    private fun nennungen(o: JSONObject?, ziel: HashMap<String, String>, ids: HashMap<String, String>) {
        if (o == null) return
        val it = o.keys()
        while (it.hasNext()) {
            val id = it.next()
            ids[FahrzeugModelle.norm(id)] = id
            val n = o.optJSONObject(id)?.optString("namensnennung", "") ?: ""
            if (n.isNotBlank() && n != "null") ziel[id] = n
        }
    }

    /** `katalog.json` lesen (rein, im Test belegt). Kaputtes oder Fehlendes → leer (dann wie bisher gezeichnet). */
    fun katalogLesen(json: String): Katalog {
        val o = try { JSONObject(json) } catch (_: Throwable) { return Katalog(emptyMap(), emptyMap()) }
        val modelle = o.optJSONObject("modelle") ?: JSONObject()
        val fotos = o.optJSONObject("fotos") ?: JSONObject()
        val asset = HashMap<String, String>(); val nennung = HashMap<String, String>()
        val foto = HashMap<String, String>(); val fotoNennung = HashMap<String, String>()
        nennungen(modelle, nennung, asset)
        nennungen(fotos, fotoNennung, foto)
        val alias = o.optJSONObject("alias")
        if (alias != null) {
            val a = alias.keys()
            while (a.hasNext()) {
                val k = a.next(); val v = alias.optString(k)
                if (modelle.has(v)) asset[FahrzeugModelle.norm(k)] = v else if (fotos.has(v)) foto[FahrzeugModelle.norm(k)] = v
            }
        }
        // Foto nur ohne Rendering; Foto ohne Namensnennung nie (Lizenz verlangt sie)
        for (k in asset.keys) foto.remove(k)
        foto.values.retainAll(fotoNennung.keys)
        val url = o.optString("bitteUrl", "").takeIf { it.startsWith("https://") }
        return Katalog(asset, nennung, foto, fotoNennung, url)
    }

    @Volatile private var katalog: Katalog? = null

    fun katalog(ctx: Context): Katalog = katalog ?: (try {
        ctx.assets.open("$ORDNER/$KATALOG").bufferedReader(Charsets.UTF_8).use { katalogLesen(it.readText()) }
    } catch (_: Throwable) { Katalog(emptyMap(), emptyMap()) }).also { katalog = it }

    fun asset(ctx: Context, modell: String?): String? = katalog(ctx).asset[FahrzeugModelle.norm(modell)]
    fun hat(ctx: Context, modell: String?): Boolean = asset(ctx, modell) != null

    /** Sichtbare Namensnennung aus dem Katalog (CC BY verlangt sie); CC0 und ohne eigenes Bild keine. */
    fun namensnennung(ctx: Context, modell: String?): String? = asset(ctx, modell)?.let { katalog(ctx).nennung[it] }

    /** Foto-Asset (nur wenn es kein Rendering gibt), seine Namensnennung und der Link für die Bitte um ein 3D-Modell. */
    fun foto(ctx: Context, modell: String?): String? = katalog(ctx).foto[FahrzeugModelle.norm(modell)]
    fun hatFoto(ctx: Context, modell: String?): Boolean = foto(ctx, modell) != null
    fun fotoNennung(ctx: Context, modell: String?): String? = foto(ctx, modell)?.let { katalog(ctx).fotoNennung[it] }
    fun bitteUrl(ctx: Context): String? = katalog(ctx).bitteUrl

    /** Foto laden (RGB 565, einmal gemerkt); [fertig] im Haupt-Thread, null = kein Bild (dann bleibt die Form). */
    fun fotoHolen(ctx: Context, modell: String, fertig: (Bitmap?) -> Unit) {
        val a = foto(ctx, modell) ?: run { fertig(null); return }
        val k = "foto:$a"
        synchronized(fertigeBilder) { fertigeBilder[k] }?.let { fertig(it); return }
        if (!laeuft.add(k)) return
        val app = ctx.applicationContext
        io.execute {
            val bmp = try {
                val opt = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                app.assets.open("$ORDNER/${a}_foto.webp").use { BitmapFactory.decodeStream(it, null, opt) }
            } catch (_: Throwable) { null }
            haupt.post {
                if (bmp != null) synchronized(fertigeBilder) { fertigeBilder[k] = bmp }
                laeuft.remove(k)
                fertig(bmp)
            }
        }
    }

    // ---------------------------------------------------------------- Ankerpunkte

    /** Kern-Namen der Teile → Ankerpunkt im Bild (Kamera vorn links: linke Seite und Front sichtbar). */
    val TEIL_ANKER: Map<String, String> = mapOf(
        "lf" to "tuer_vl", "rf" to "tuer_vr", "lr" to "tuer_hl", "rr" to "tuer_hr",
        "hood" to "haube", "trunk" to "kofferraum",
        "vl" to "fenster_vl", "vr" to "fenster_vr", "hl" to "fenster_hl", "hr" to "fenster_hr",
        FahrzeugStart.DACH to "dach"
    )

    fun ankerLesen(json: String): Map<String, Anker> {
        val o = JSONObject(json).optJSONObject("punkte") ?: return emptyMap()
        val m = LinkedHashMap<String, Anker>()
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            val p = o.optJSONObject(k) ?: continue
            val x = p.optDouble("x", Double.NaN); val y = p.optDouble("y", Double.NaN)
            if (x.isNaN() || y.isNaN() || x < 0 || x > 1 || y < 0 || y > 1) continue
            m[k] = Anker(x.toFloat(), y.toFloat(), p.optBoolean("sichtbar", false))
        }
        return m
    }

    private val ankerCache = ConcurrentHashMap<String, Map<String, Anker>>()

    /** Ankerpunkte des Modells (Bild-Anteile 0..1), null ohne eigenes Bild. Für Tipp-Zonen und Zustand am Bild. */
    fun punkte(ctx: Context, modell: String?): Map<String, Anker>? {
        val a = asset(ctx, modell) ?: return null
        ankerCache[a]?.let { return it }
        val m = try {
            ctx.assets.open("$ORDNER/${a}_punkte.json").bufferedReader(Charsets.UTF_8).use { ankerLesen(it.readText()) }
        } catch (_: Throwable) { return null }
        ankerCache[a] = m
        return m
    }

    /** Welche Teile offen sind, als Ankernamen (nur echte Kernwerte, Unbekanntes nie). */
    fun offeneAnker(z: FahrzeugStart.Zustand): List<String> = buildList {
        for ((teil, offen) in z.tuerOffen) if (offen) TEIL_ANKER[teil]?.let { add(it) }
        for ((teil, wert) in z.fenster) {
            if (teil == FahrzeugStart.DACH && !z.dach) continue
            if (FahrzeugStart.offen(wert)) TEIL_ANKER[teil]?.let { add(it) }
        }
    }

    /**
     * Zustand an der richtigen Stelle des Bildes: weicher Schein in der Warnfarbe mit feinem Ring. Teile der
     * verdeckten Seite (rechts) leiser und mit dünnerem Ring – die Plakette oben nennt sie ohnehin.
     */
    fun zustandZeichnen(l: Leinwand, z: FahrzeugStart.Zustand, anker: Map<String, Anker>, p: Palette, r: FloatArray) {
        val e = r[2] * 0.03f
        for (name in offeneAnker(z)) {
            val a = anker[name] ?: continue
            val x = r[0] + a.x * r[2]; val y = r[1] + a.y * r[3]
            val stark = if (a.sichtbar) 1f else 0.55f
            l.ellipse(x, y, e * 2.4f, e * 2.4f, Radial(x, y, e * 2.4f, e * 2.4f,
                intArrayOf(Farbe.alpha(p.warning, (150 * stark).toInt()), Farbe.alpha(p.warning, (40 * stark).toInt()),
                    Farbe.alpha(p.warning, 0)), floatArrayOf(0f, 0.55f, 1f)))
            l.ellipseRahmen(x, y, e * 0.9f, e * 0.9f, if (a.sichtbar) e * 0.16f else e * 0.09f,
                Voll(Farbe.alpha(p.warning, (230 * stark).toInt())))
            l.ellipse(x, y, e * 0.32f, e * 0.32f, Voll(Farbe.alpha(p.warning, (240 * stark).toInt())))
        }
    }

    // ---------------------------------------------------------------- Lackfarbe einrechnen (rein, im Test belegt)

    /** sRGB-Byte → linear (0..1). */
    private val LIN = FloatArray(256) { i -> val c = i / 255f; if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat() }

    /** linear (0..1, 4096 Stufen) → sRGB-Byte. */
    private val SRGB = IntArray(4096) { i ->
        val v = i / 4095.0
        val s = if (v <= 0.0031308) v * 12.92 else 1.055 * Math.pow(v, 1 / 2.4) - 0.055
        (s * 255.0 + 0.5).toInt().coerceIn(0, 255)
    }

    private fun zuSrgb(lin: Float): Int = SRGB[(lin * 4095f + 0.5f).toInt().coerceIn(0, 4095)]

    /**
     * Grund (ARGB, nicht vormultipliziert) + Lack (Graustufe im Rotkanal) × Lackfarbe, linear gerechnet.
     * Alpha bleibt aus dem Grund (Auto und Schatten). Ergebnis in [aus] (darf [grund] sein).
     */
    fun mische(grund: IntArray, lack: IntArray, farbe: Int, aus: IntArray = IntArray(grund.size)): IntArray {
        val lr = LIN[(farbe shr 16) and 0xff]; val lg = LIN[(farbe shr 8) and 0xff]; val lb = LIN[farbe and 0xff]
        val n = minOf(grund.size, lack.size, aus.size)
        for (i in 0 until n) {
            val g = grund[i]
            val d = LIN[(lack[i] shr 16) and 0xff]
            if (d == 0f) { aus[i] = g; continue }
            val r = zuSrgb(LIN[(g shr 16) and 0xff] + d * lr)
            val gg = zuSrgb(LIN[(g shr 8) and 0xff] + d * lg)
            val b = zuSrgb(LIN[g and 0xff] + d * lb)
            aus[i] = (g and -0x1000000) or (r shl 16) or (gg shl 8) or b
        }
        return aus
    }

    // ---------------------------------------------------------------- Laden (Hintergrund), höchstens zwei Bilder gemerkt

    private val io by lazy { Executors.newSingleThreadExecutor() }
    private val haupt by lazy { Handler(Looper.getMainLooper()) }
    private val fertigeBilder = object : LinkedHashMap<String, Bitmap>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 2
    }
    private val laeuft = ConcurrentHashMap.newKeySet<String>()

    fun schluessel(asset: String, farbe: Int) = asset + "#" + Integer.toHexString(farbe and 0xffffff)

    /** Fertiges Bild für Modell und Lackfarbe; [fertig] kommt im Haupt-Thread, null = kein Bild (dann bleibt die Form). */
    fun holen(ctx: Context, modell: String, farbe: Int, fertig: (Bitmap?) -> Unit) {
        val a = asset(ctx, modell) ?: run { fertig(null); return }
        val k = schluessel(a, farbe)
        synchronized(fertigeBilder) { fertigeBilder[k] }?.let { fertig(it); return }
        if (!laeuft.add(k)) return
        val app = ctx.applicationContext
        io.execute {
            val bmp = try { bauen(app, a, farbe) } catch (_: Throwable) { null }
            haupt.post {
                if (bmp != null) synchronized(fertigeBilder) { fertigeBilder[k] = bmp }
                laeuft.remove(k)
                fertig(bmp)
            }
        }
    }

    private fun bauen(ctx: Context, a: String, farbe: Int): Bitmap? {
        val opt = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inMutable = true }
        val grund = ctx.assets.open("$ORDNER/${a}_grund.webp").use { BitmapFactory.decodeStream(it, null, opt) } ?: return null
        val lackBild = ctx.assets.open("$ORDNER/${a}_lack.webp").use { BitmapFactory.decodeStream(it) } ?: return grund
        val w = grund.width; val h = grund.height
        if (lackBild.width != w || lackBild.height != h) { lackBild.recycle(); return grund }
        val g = IntArray(w * h); val d = IntArray(w * h)
        grund.getPixels(g, 0, w, 0, 0, w, h)
        lackBild.getPixels(d, 0, w, 0, 0, w, h)
        lackBild.recycle()
        mische(g, d, farbe, g)
        grund.setPixels(g, 0, w, 0, 0, w, h)
        return grund
    }
}
