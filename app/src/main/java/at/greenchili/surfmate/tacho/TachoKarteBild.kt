package at.greenchili.surfmate.tacho

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import at.greenchili.surfmate.einstellungen.Kartenquelle
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import java.io.ByteArrayOutputStream

/**
 * 0.1.43.9 (T3): Kartenbild für den Kasten im Tacho, außerhalb der netzfreien Tacho-Seite gerendert.
 * MapLibre-Snapshotter mit genau dem Stil der Panoptes-Karte je gewählter Quelle ([Kartenquelle]), über denselben
 * HTTP-Client (Kennung) und denselben Kachel-Cache wie die Karte am Infotainment. Ohne Quelle: nichts.
 * Regeln (Takt, Bewegung, Zoom, Mitte) in [TachoKachelKarte]. Die Seite bekommt nur Bild, Maßstab und Namensnennung.
 *
 * 0.1.43.9 (F, B1): Ein nativer Fehler im Snapshotter (z. B. `Failed to choose ARGB config.` ohne OpenGL ES 3) beendet
 * den ganzen Panoptes-Prozess. Deshalb vor dem ersten Start [kachelnMoeglich]: GLES 3 und eine passende EGL-Konfiguration
 * da, kein Fehler in dieser Sitzung, kein Absturz in diesem Gerätestart (Marke, solange das erste Bild aussteht).
 * Sonst zeigt der Kasten das gedämpfte Kartenzeichen wie ohne Quelle.
 */
class TachoKarteBild(private val ctx: Context, private val fertig: (JSONObject) -> Unit) {
    private var snapshotter: MapSnapshotter? = null
    private var stilSchluessel = ""
    private var alt: TachoKachelKarte.Stand? = null
    private var laeuft = false
    private var stand = 0
    private var bereit = false
    private var ersterLauf = true

    companion object {
        private const val MARKE = "tacho-karte-snapshot.marke"
        @Volatile private var grafik: Boolean? = null
        @Volatile private var fehlerInSitzung = false
        @Volatile private var absturzGeprueft = false
        @Volatile private var absturzVorher = false

        /** Darf der Snapshotter überhaupt starten? Einmal je Prozess geprüft; nie eine Ausnahme nach außen. */
        @JvmStatic
        fun kachelnMoeglich(ctx: Context): Boolean = try {
            if (fehlerInSitzung) false
            else {
                if (!absturzGeprueft) {
                    absturzVorher = TachoKachelKarte.nachAbsturzGesperrt(markeLesen(ctx), geraeteStart())
                    absturzGeprueft = true
                }
                !absturzVorher && (grafik ?: grafikPruefen(ctx).also { grafik = it })
            }
        } catch (_: Throwable) { false }

        private fun grafikPruefen(ctx: Context): Boolean {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val gles = try { am.deviceConfigurationInfo.reqGlEsVersion } catch (_: Throwable) { 0 }
            return TachoKachelKarte.grafikReicht(gles, gles >= TachoKachelKarte.GLES_MIN && eglKonfig())
        }

        /** Gibt es eine Pbuffer-Konfiguration für OpenGL ES 3 (wie der Snapshotter sie wählt)? Nur nachsehen. */
        private fun eglKonfig(): Boolean = try {
            val dpy = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY)
            val v = IntArray(2)
            if (dpy == android.opengl.EGL14.EGL_NO_DISPLAY || !android.opengl.EGL14.eglInitialize(dpy, v, 0, v, 1)) false
            else {
                // Kein eglTerminate: die Anzeige teilt sich der Prozess (WebView, Oberfläche)
                val attr = intArrayOf(
                    android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                    android.opengl.EGL14.EGL_SURFACE_TYPE, android.opengl.EGL14.EGL_PBUFFER_BIT,
                    android.opengl.EGL14.EGL_RED_SIZE, 8, android.opengl.EGL14.EGL_GREEN_SIZE, 8,
                    android.opengl.EGL14.EGL_BLUE_SIZE, 8, android.opengl.EGL14.EGL_ALPHA_SIZE, 8,
                    android.opengl.EGL14.EGL_NONE)
                val cfg = arrayOfNulls<android.opengl.EGLConfig>(1)
                val n = IntArray(1)
                android.opengl.EGL14.eglChooseConfig(dpy, attr, 0, cfg, 0, 1, n, 0) && n[0] > 0
            }
        } catch (_: Throwable) { false }

        private fun geraeteStart(): Long = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()

        private fun markeDatei(ctx: Context) = java.io.File(ctx.applicationContext.noBackupFilesDir, MARKE)

        private fun markeLesen(ctx: Context): Long = try {
            val f = markeDatei(ctx)
            if (f.isFile) f.readText().trim().toLong() else 0L
        } catch (_: Throwable) { 0L }

        private fun markeSetzen(ctx: Context) {
            try { markeDatei(ctx).writeText(geraeteStart().toString()) } catch (_: Throwable) { }
        }

        private fun markeWeg(ctx: Context) {
            try { markeDatei(ctx).delete() } catch (_: Throwable) { }
        }
    }

    /** Auf dem Haupt-Thread aufrufen (Takt der Ansicht). */
    fun schritt(kasten: JSONObject?, quelle: String, pos: JSONObject?, nacht: Boolean) {
        if (kasten == null || pos == null || (quelle != Kartenquelle.COMMUNITY && quelle != Kartenquelle.OSM)) return
        if (laeuft) return
        val lat = pos.optDouble("lat", Double.NaN)
        val lon = pos.optDouble("lon", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return
        val kurs = if (pos.has("kurs")) pos.optDouble("kurs", Double.NaN) else Double.NaN
        val b = kasten.optInt("b")
        val h = kasten.optInt("h")
        val blick = kasten.optString("blick", "fahrt")
        val schluessel = "$quelle|$b|$h|$blick|$nacht"
        val jetzt = System.currentTimeMillis()
        if (!TachoKachelKarte.neuNoetig(alt, lat, lon, kurs, blick, schluessel, jetzt)) return
        if (!kachelnMoeglich(ctx)) return
        if (!bereit) {
            MapLibre.getInstance(ctx.applicationContext)
            try { at.greenchili.surfmate.navmap.nav.MapNetworking.installMapLibreHttpClient() } catch (_: Throwable) { }
            bereit = true
        }
        val zoom = TachoKachelKarte.zoom(lat, b, TachoKachelKarte.meter(blick))
        val mpp = TachoKachelKarte.mpp(lat, zoom)
        val m = TachoKachelKarte.mitte(lat, lon, kurs, blick, h, mpp)
        val kamera = CameraPosition.Builder().target(LatLng(m[0], m[1])).zoom(zoom)
            .bearing(if (blick == "fahrt" && !kurs.isNaN()) kurs else 0.0).build()
        val stilNeu = "$quelle|$nacht"
        var s = snapshotter
        if (s == null || stilNeu != stilSchluessel) {
            s?.cancel()
            val stil = if (quelle == Kartenquelle.COMMUNITY) Style.Builder().fromUri(Kartenquelle.COMMUNITY_STIL)
                else Style.Builder().fromJson(Kartenquelle.osmStil(nacht))
            s = MapSnapshotter(ctx.applicationContext, MapSnapshotter.Options(b, h).withStyleBuilder(stil)
                .withCameraPosition(kamera).withLogo(false).withPixelRatio(1f))
            snapshotter = s
            stilSchluessel = stilNeu
        } else {
            s.setSize(b, h)
            s.setCameraPosition(kamera)
        }
        alt = TachoKachelKarte.Stand(lat, lon, kurs, schluessel, jetzt)
        laeuft = true
        // Marke nur, bis das erste Bild da ist: stirbt der Prozess vorher, bleiben die Kacheln bis zum Neustart aus
        if (ersterLauf) markeSetzen(ctx)
        s.start({ bild ->
            laeuft = false
            if (ersterLauf) { ersterLauf = false; markeWeg(ctx) }
            val src = jpeg(bild.bitmap) ?: return@start
            stand++
            fertig(JSONObject().put("src", src).put("stand", stand).put("mpp", mpp).put("b", b).put("h", h)
                .put("kurs", if (kurs.isNaN()) JSONObject.NULL else kurs)
                .put("nennung", TachoKachelKarte.nennung(quelle)))
        }, { meldung ->
            // Fehler im Snapshot: Kacheln für diese Sitzung aus, Kasten zeigt das gedämpfte Zeichen. Nur Netz/Stil
            // (offline gestartet) nicht: dann wie bisher beim nächsten Takt erneut.
            laeuft = false
            markeWeg(ctx)
            if (TachoKachelKarte.fehlerSperrt(meldung)) { fehlerInSitzung = true; stopp() }
        })
    }

    fun stopp() {
        if (laeuft && ersterLauf) markeWeg(ctx)
        try { snapshotter?.cancel() } catch (_: Throwable) { }
        snapshotter = null
        stilSchluessel = ""
        alt = null
        laeuft = false
    }

    private fun jpeg(bmp: Bitmap?): String? {
        if (bmp == null) return null
        val out = ByteArrayOutputStream()
        if (!bmp.compress(Bitmap.CompressFormat.JPEG, 72, out)) return null
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
