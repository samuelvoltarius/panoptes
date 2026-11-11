package at.greenchili.panoptes.launcher

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * Nur damit Android dem Launcher die laufenden Mediensitzungen und die Zahl der Benachrichtigungen
 * zeigt. Der Nutzer muss den Zugriff selbst in den Android-Einstellungen erlauben (Standard: aus).
 * Der Dienst liest keine Benachrichtigungsinhalte, speichert und sendet nichts.
 */
class MedienDienst : NotificationListenerService() {
    override fun onListenerConnected() { verbunden = this }
    override fun onListenerDisconnected() { if (verbunden === this) verbunden = null }

    companion object {
        @Volatile var verbunden: MedienDienst? = null

        /** Gibt es löschbare Benachrichtigungen? null = kein Zugriff. */
        fun hatBenachrichtigungen(): Boolean? = try {
            verbunden?.activeNotifications?.any { it.isClearable }
        } catch (_: Throwable) { null }
    }
}

data class Wiedergabe(
    val paket: String,
    val titel: String?,
    val kuenstler: String?,
    val cover: Bitmap?,
    val spielt: Boolean,
    val positionMs: Long?,
    val dauerMs: Long?,
    val controller: MediaController
)

object MedienQuelle {
    fun zugriffErlaubt(ctx: Context): Boolean {
        val liste = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        val ich = ComponentName(ctx, MedienDienst::class.java)
        return liste.split(':').any { ComponentName.unflattenFromString(it) == ich }
    }

    /** Alle aktiven Sitzungen; leer ohne Zugriff. */
    private fun sitzungen(ctx: Context): List<MediaController> = try {
        ctx.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(ComponentName(ctx, MedienDienst::class.java)) ?: emptyList()
    } catch (_: Throwable) { emptyList() }

    /** 0.1.41 (LM): Paket → spielt gerade? (ohne Telecom). null ohne Zugriff. */
    fun ueberblick(ctx: Context): Map<String, Boolean>? {
        if (!zugriffErlaubt(ctx)) return null
        return sitzungen(ctx).filter { it.packageName !in MedienWahl.AUSGESCHLOSSEN }
            .groupBy { it.packageName }.mapValues { (_, l) -> l.any { it.playbackState?.state == PlaybackState.STATE_PLAYING } }
    }

    /** 0.1.41 (LM): play() auf der Sitzung des Pakets (TransportControls). false, wenn es keine gibt. */
    fun spielen(ctx: Context, paket: String): Boolean = try {
        val c = sitzungen(ctx).firstOrNull { it.packageName == paket }
        c?.transportControls?.play(); c != null
    } catch (_: Throwable) { false }

    /**
     * Aktive Sitzung nach [MedienWahl]: spielend > CarPlay/Android Auto > Paket mit Audiofokus (hier: zuletzt
     * spielend gesehen, [fokusPaket]) > Rest; nie die Telecom-Sitzung. null ohne Zugriff oder ohne Sitzung.
     */
    fun aktuell(ctx: Context, fokusPaket: String? = null): Wiedergabe? = try {
        val c = MedienWahl.waehle(sitzungen(ctx), { it.packageName }, { it.playbackState?.state }, fokusPaket)
        c?.let { m ->
            val md = m.metadata
            val st = m.playbackState
            val dauer = md?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }
            val pos = st?.let {
                val nachgefuehrt = if (it.state == PlaybackState.STATE_PLAYING && it.lastPositionUpdateTime > 0)
                    it.position + ((android.os.SystemClock.elapsedRealtime() - it.lastPositionUpdateTime) * it.playbackSpeed).toLong()
                else it.position
                nachgefuehrt.takeIf { p -> p >= 0 }
            }
            Wiedergabe(
                m.packageName,
                md?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() },
                (md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.takeIf { it.isNotBlank() },
                md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART),
                st?.state == PlaybackState.STATE_PLAYING,
                pos,
                dauer,
                m
            )
        }
    } catch (_: SecurityException) {
        null
    } catch (_: Throwable) {
        null
    }

    /** Fortschritt 0..1000 oder null, wenn Position oder Dauer fehlt. */
    fun fortschritt(positionMs: Long?, dauerMs: Long?): Int? =
        if (positionMs == null || dauerMs == null || dauerMs <= 0) null else ((positionMs.coerceIn(0, dauerMs) * 1000) / dauerMs).toInt()
}
