package at.greenchili.surfmate.sprache;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import org.vosk.LibVosk;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 0.1.44 (P2) Zuhören mit Vosk (Apache-2.0), nur im Prozess {@code :sprache}. Eine feste Grammatik (nur die Sätze aus
 * {@link SprachBefehle}), kein Netz. Der Ton bleibt im Speicher dieses Threads und wird nach dem Zuhören genullt; nichts
 * wird gespeichert, gesendet oder geloggt (Vosk-Log aus).
 */
final class SprachErkenner {

    static final int ABTASTRATE = 16000;
    /** Unterordner unter {@code getExternalFilesDir}; darin je Sprache der Modellordner aus {@link SprachBefehle#MODELL}. */
    static final String ORDNER = "sprache";

    private static Model modell;
    private static String modellSprache;

    private SprachErkenner() {}

    static File modellOrdner(Context ctx, String sprache) {
        String name = SprachBefehle.MODELL.get(SprachBefehle.sprache(sprache));
        File basis = ctx.getExternalFilesDir(ORDNER);
        return name == null || basis == null ? null : new File(basis, name);
    }

    static boolean modellDa(Context ctx, String sprache) {
        File o = modellOrdner(ctx, sprache);
        return o != null && new File(o, "am/final.mdl").isFile() && new File(o, "conf/model.conf").isFile();
    }

    private static synchronized Model modell(Context ctx, String sprache) throws java.io.IOException {
        if (modell != null && sprache.equals(modellSprache)) return modell;
        if (modell != null) { try { modell.close(); } catch (Throwable ignored) { } modell = null; }
        LibVosk.vosk_set_log_level(-1);   // Vosk/Kaldi schreibt nichts ins Log, auch keine erkannten Wörter
        modell = new Model(modellOrdner(ctx, sprache).getAbsolutePath());
        modellSprache = sprache;
        return modell;
    }

    /**
     * Hört höchstens {@code maxMs} zu und liefert den ersten erkannten Satz (normalisiert, ohne [unk]) oder "" bei
     * Ablauf, Abbruch oder Fehler. Das Mikrofon ist nur während dieses Aufrufs offen.
     */
    static String hoeren(Context ctx, String sprache, String grammatik, long maxMs, AtomicBoolean abbruch) {
        AudioRecord rec = null;
        Recognizer erk = null;
        short[] buf = new short[ABTASTRATE / 10];   // 100 ms
        try {
            erk = new Recognizer(modell(ctx, sprache), (float) ABTASTRATE, grammatik);
            int min = AudioRecord.getMinBufferSize(ABTASTRATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, ABTASTRATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, buf.length * 4));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) return "";
            rec.startRecording();
            long ende = System.currentTimeMillis() + maxMs;
            while (!abbruch.get() && System.currentTimeMillis() < ende) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) continue;
                if (erk.acceptWaveForm(buf, n)) {
                    String t = SprachBefehle.text(erk.getResult());
                    if (!t.isEmpty()) return t;
                }
            }
            if (abbruch.get()) return "";
            return SprachBefehle.text(erk.getFinalResult());
        } catch (Throwable t) {
            return "";
        } finally {
            java.util.Arrays.fill(buf, (short) 0);
            if (rec != null) {
                try { rec.stop(); } catch (Throwable ignored) { }
                try { rec.release(); } catch (Throwable ignored) { }
            }
            if (erk != null) { try { erk.close(); } catch (Throwable ignored) { } }
        }
    }

    /** Modell freigeben (Ende des Sprach-Prozesses). */
    static synchronized void freigeben() {
        if (modell != null) { try { modell.close(); } catch (Throwable ignored) { } }
        modell = null;
        modellSprache = null;
    }
}
