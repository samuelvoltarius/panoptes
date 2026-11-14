package at.greenchili.panoptes.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper

/**
 * 0.1.43.4 (Paket S, Projektinhaber 13.11. „GPS-Tempo, sonst fragen“): Stand ohne Panoptes. Mit Panoptes zählt allein der
 * Kern (Gang/Tempo). Ohne Panoptes:
 * - Standort erlaubt und frischer, sicherer GPS-Wert (höchstens [FRISCH_MS] alt): frei nur unter [GRENZE_KMH].
 * - Wert abgelaufen oder unsicher: gesperrt (die Frage hilft dann nicht). Erst wenn länger als [KEIN_GPS_MS] gar kein
 *   Wert kam (Garage, Tunnel, GPS aus), gilt das wie „kein GPS“.
 * - Kein Standortrecht oder kein GPS: die Bestätigung „Ich stehe“ gilt [BESTAETIGT_MS]; ein frischer GPS-Wert ab
 *   [GRENZE_KMH] lässt sie sofort verfallen.
 * Video bleibt davon unberührt (nur in P, unbekannter Stand sperrt). Reine Regeln, JVM-testbar.
 */
object StandOhneKern {
    const val GRENZE_KMH = 3.0
    const val FRISCH_MS = 10_000L
    /** Ohne jeden Wert so lange: wie „kein GPS“ (sonst bliebe man nach einem Tunnel für immer gesperrt). */
    const val KEIN_GPS_MS = 120_000L
    const val BESTAETIGT_MS = 10 * 60_000L
    /** Unsicher: Tempo ungenauer als das (m/s) oder Ort ungenauer als [UNSICHER_M]. */
    const val UNSICHER_MPS = 1.5f
    const val UNSICHER_M = 50f

    /** Ein GPS-Wert: Tempo in km/h (null = Gerät liefert keins), Zeit, sicher. */
    data class Gps(val kmh: Double?, val zeitMs: Long, val sicher: Boolean)

    enum class Urteil { FREI, GESPERRT, FRAGEN }

    /** Was gilt ohne Panoptes? [recht] = Standort erlaubt, [gps] = letzter Wert (oder null). */
    fun urteil(recht: Boolean, gps: Gps?, jetzt: Long): Urteil {
        if (!recht || gps == null) return Urteil.FRAGEN
        val alter = jetzt - gps.zeitMs
        if (alter < 0 || alter > KEIN_GPS_MS) return Urteil.FRAGEN
        if (alter > FRISCH_MS || !gps.sicher || gps.kmh == null) return Urteil.GESPERRT
        return if (gps.kmh < GRENZE_KMH) Urteil.FREI else Urteil.GESPERRT
    }

    /** Bedienen erlaubt? Mit Kern allein der Kern; ohne Panoptes GPS, sonst die Bestätigung. */
    fun erlaubt(kernStandOk: Boolean, ohnePanoptes: Boolean, recht: Boolean, gps: Gps?, bestaetigtBis: Long, jetzt: Long): Boolean {
        if (kernStandOk) return true
        if (!ohnePanoptes) return false
        return when (urteil(recht, gps, jetzt)) {
            Urteil.FREI -> true
            Urteil.GESPERRT -> false
            Urteil.FRAGEN -> jetzt < bestaetigtBis && bestaetigtBis - jetzt <= BESTAETIGT_MS
        }
    }

    /** Fährt man laut frischem GPS-Wert, verfällt „Ich stehe“ sofort. */
    fun bestaetigungVerfaellt(gps: Gps?, jetzt: Long): Boolean =
        gps != null && gps.kmh != null && gps.sicher && jetzt - gps.zeitMs in 0..FRISCH_MS && gps.kmh >= GRENZE_KMH

    /** Aus einem Android-Ort: Tempo nur, wenn das Gerät eins liefert; unsicher bei grober Genauigkeit. */
    fun aus(l: Location, jetzt: Long): Gps {
        val kmh = if (l.hasSpeed()) l.speed * 3.6 else null
        val tempoGenau = !l.hasSpeedAccuracy() || l.speedAccuracyMetersPerSecond <= UNSICHER_MPS
        val ortGenau = !l.hasAccuracy() || l.accuracy <= UNSICHER_M
        return Gps(kmh, jetzt, tempoGenau && ortGenau)
    }

    fun recht(c: Context): Boolean = c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * GPS nur, solange der Launcher sichtbar ist, Panoptes fehlt und der Nutzer den Standort selbst erlaubt hat.
     * Kein Speichern, kein Senden; nur das Tempo des letzten Werts im Speicher.
     */
    class Quelle(private val c: Context, private val neu: (Gps) -> Unit) {
        @Volatile var letzter: Gps? = null
            private set
        private var an = false
        private val hoerer = object : LocationListener {
            override fun onLocationChanged(l: Location) { val g = aus(l, System.currentTimeMillis()); letzter = g; neu(g) }
            @Deprecated("API 29") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) = Unit
            override fun onProviderEnabled(p: String) = Unit
            override fun onProviderDisabled(p: String) = Unit
        }

        @SuppressLint("MissingPermission")
        fun start() {
            if (an || !recht(c)) return
            val lm = c.getSystemService(LocationManager::class.java) ?: return
            an = runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, hoerer, Looper.getMainLooper()) }.isSuccess
        }

        fun stop() {
            if (!an) return
            runCatching { c.getSystemService(LocationManager::class.java)?.removeUpdates(hoerer) }
            an = false
        }
    }
}
