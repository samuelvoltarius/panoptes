package at.greenchili.surfmate.navmap.nav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Thin Android [TextToSpeech] wrapper for spoken turn-by-turn guidance.
 *
 * <p>This is the only navigation-engine file (besides needing a [Context]) that
 * touches the Android framework. TTS initialization is asynchronous; until the
 * engine reports `SUCCESS`, [speak] calls are no-ops (we do NOT queue across
 * init — by the time TTS is ready the queued instruction would be stale, so we
 * simply drop pre-init speech). Once ready, each [speak] uses `QUEUE_FLUSH` so
 * the latest instruction replaces any still-playing one.
 *
 * <p>Regel A+ (0.1.39 G5): speaks only while [erlaubt] says so — by default the user setting
 * `navi.sprachansagen` ([NaviAnsagen], Standard AUS), re-checked on every [speak]. Output goes over
 * the media channel (`STREAM_MUSIC` / `USAGE_MEDIA`, follows the media volume); no other sounds.
 *
 * <p>All calls are guarded so a TTS error never propagates to the caller.
 * Construct on the main thread (TextToSpeech requires a Looper); call
 * [shutdown] from the Activity's onDestroy.
 *
 * @param context any Context (application context is fine — held only for TTS
 *   construction; not retained beyond the TextToSpeech instance)
 * @param erlaubt whether speaking is currently allowed (default: the user setting)
 */
class NavVoice(
    context: Context,
    private val erlaubt: () -> Boolean = { NaviAnsagen.aktuell() },
) {

    companion object {
        private const val TAG = "NavVoice"
        private const val UTTERANCE_ID = "navvoice"

        /** Regel A+: nur ein ausdrückliches Ja erlaubt Sprechen; ein Fehler beim Lesen heißt schweigen. */
        @JvmStatic
        fun darfSprechen(erlaubt: () -> Boolean): Boolean =
            try { erlaubt() } catch (_: Throwable) { false }
    }

    @Volatile
    private var ready = false

    @Volatile
    private var muted = false

    private var tts: TextToSpeech? = null

    init {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    try {
                        tts?.setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    } catch (t: Throwable) {
                        Log.w(TAG, "set audio attributes failed: ${t.message}")
                    }
                    try {
                        // Speak in the user's chosen app language (matches the
                        // language the Valhalla maneuver instructions come back in).
                        // Fall back to the system default when the TTS engine has
                        // no voice for that locale, so guidance is never silenced.
                        val want = Locale.forLanguageTag(
                            at.greenchili.surfmate.server.LocaleManager.get())
                        val avail = tts?.isLanguageAvailable(want)
                        tts?.language =
                            if (avail == TextToSpeech.LANG_AVAILABLE ||
                                avail == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                                avail == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE) want
                            else Locale.getDefault()
                    } catch (t: Throwable) {
                        Log.w(TAG, "set language failed: ${t.message}")
                    }
                    // Only flip ready when the engine actually exists — guards a
                    // speak() racing in before construction returned (or after a
                    // shutdown nulled tts), so speak() stays a safe no-op.
                    ready = tts != null
                    Log.i(TAG, "TTS ready=$ready")
                } else {
                    Log.w(TAG, "TTS init failed: status=$status")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "TTS construction failed: ${t.message}")
            tts = null
        }
    }

    /** Whether the TTS engine has finished initializing and can speak. */
    fun isReady(): Boolean = ready

    /**
     * Mute or unmute spoken guidance. While muted, [speak] is a no-op and any
     * in-progress utterance is stopped.
     *
     * @param mute true to silence guidance, false to re-enable it
     */
    fun setMuted(mute: Boolean) {
        muted = mute
        if (mute) {
            try { tts?.stop() } catch (_: Throwable) {}
        }
    }

    /**
     * Speak [text] now, flushing any utterance already playing
     * (`QUEUE_FLUSH`). No-op (and never throws) if TTS is not yet ready, is
     * muted, the user has not switched announcements on, or [text] is blank.
     *
     * @param text the instruction to speak (e.g. "In 200 meters, turn left")
     */
    fun speak(text: String) {
        if (!ready || muted) return
        if (!darfSprechen(erlaubt)) {
            try { tts?.stop() } catch (_: Throwable) {}
            return
        }
        val msg = text.trim()
        if (msg.isEmpty()) return
        try {
            val params = Bundle()
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID)
        } catch (t: Throwable) {
            Log.w(TAG, "speak failed: ${t.message}")
        }
    }

    /**
     * Stop any in-progress utterance and release the TTS engine. Idempotent;
     * after this the instance can no longer speak. Call from the Activity's
     * onDestroy.
     */
    fun shutdown() {
        ready = false
        try { tts?.stop() } catch (_: Throwable) {}
        try { tts?.shutdown() } catch (_: Throwable) {}
        tts = null
    }
}
