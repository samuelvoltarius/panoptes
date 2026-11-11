package at.greenchili.panoptes.launcher

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler

/**
 * 0.1.41 (E2E M1/G4): Die Musik-Kachel fror ein – Pause/Weiter wirkten, Titel und Symbol blieben alt, solange der
 * Sekundentakt ruhte (kein Fokus, Zündung „off“) – und ein während des Laufs erteilter Zugriff wurde erst nach einem
 * Neustart gesehen. Jetzt meldet Android jede Änderung selbst:
 * - [MediaSessionManager.OnActiveSessionsChangedListener]: Sitzungen kommen und gehen,
 * - [MediaController.Callback] je Sitzung: Zustand, Metadaten, Ende.
 * [start] ist wiederholbar (onStart/onResume): ohne Zugriff scheitert die Anmeldung still und wird beim nächsten
 * Mal erneut versucht, z. B. wenn der Nutzer aus der Android-Seite „Benachrichtigungszugriff“ zurückkommt.
 * Jede Meldung ruft [geaendert] auf dem Haupt-Thread; der Beobachter liest selbst keine Inhalte aus.
 */
class MedienBeobachter(private val ctx: Context, private val main: Handler, private val geaendert: () -> Unit) {
    private var angemeldet = false
    private var controller: List<MediaController> = emptyList()

    private val rueckruf = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = geaendert()
        override fun onMetadataChanged(metadata: MediaMetadata?) = geaendert()
        override fun onSessionDestroyed() = geaendert()
    }

    private val sitzungen = MediaSessionManager.OnActiveSessionsChangedListener { liste ->
        verfolgen(liste ?: emptyList()); geaendert()
    }

    private val msm get() = ctx.getSystemService(MediaSessionManager::class.java)
    private val ich get() = ComponentName(ctx, MedienDienst::class.java)

    /** true, wenn angemeldet (Zugriff da). */
    fun start(): Boolean {
        if (angemeldet) return true
        if (!MedienQuelle.zugriffErlaubt(ctx)) return false
        return try {
            msm.addOnActiveSessionsChangedListener(sitzungen, ich, main)
            angemeldet = true
            verfolgen(msm.getActiveSessions(ich) ?: emptyList())
            true
        } catch (_: Throwable) { angemeldet = false; false }
    }

    fun stop() {
        if (angemeldet) runCatching { msm.removeOnActiveSessionsChangedListener(sitzungen) }
        angemeldet = false
        verfolgen(emptyList())
    }

    private fun verfolgen(neu: List<MediaController>) {
        controller.forEach { runCatching { it.unregisterCallback(rueckruf) } }
        controller = neu.filter { it.packageName !in MedienWahl.AUSGESCHLOSSEN }
        controller.forEach { runCatching { it.registerCallback(rueckruf, main) } }
    }
}
