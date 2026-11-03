package at.greenchili.surfmate.weckwort;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Weckwort-Messung Stufe 1 im eigenen App-Prozess {@code :weckwort} (App-UID mit RECORD_AUDIO; der Daemon als uid shell darf nicht
 * aufnehmen, siehe {@code AppAudioCaptureController}).
 *
 * <p>Misst CPU, RAM, Verarbeitungszeit und Treffer des festen Messworts sowie, ob das Mikrofon neben anderen
 * Aufnehmern (z. B. BYD-Sprachassistent) frei ist. <b>Audio bleibt im Speicher dieses Threads:</b> kein Schreiben
 * auf Platte, kein Netz, kein Log mit Erkennungstext. Gespeichert wird nur die Zahlen-JSON
 * {@link WeckwortMessRegeln#ERGEBNIS_DATEI} im App-Ordner, die der Daemon über
 * {@code GET /api/weckwort-messung} (nur am Autobildschirm) ausliefert.
 *
 * <p>Löst nichts aus: keine Fahrzeugbefehle, keine Automationen, keine Verbindung zum KI-Assistenten.
 */
public final class WeckwortMessung {

    private static final String TAG = "WeckwortMessung";
    private static final int ABTASTRATE = 16000;
    private static final int STUECK_MS = 100;
    private static final int STUECK_SAMPLES = ABTASTRATE * STUECK_MS / 1000;
    private static final long PRUEF_MS = 5_000L;
    private static final long SCHREIB_MS = 10_000L;
    private static final long PSS_MS = 60_000L;

    private static final Object LOCK = new Object();
    private static WeckwortMessung laufend;

    private final Context ctx;
    private final long endeMs;
    private final WeckwortMessAnzeige anzeige;
    private final WeckwortMessStatistik stat;
    private final File modellOrdner;
    private volatile boolean stoppGewuenscht;
    private volatile String stoppGrund;
    private Thread thread;
    private Runnable fertig;
    private AudioManager.AudioRecordingCallback rueckruf;
    private volatile int eigeneSitzung = -1;
    private volatile boolean warStumm;

    private WeckwortMessung(Context ctx, int minuten, WeckwortMessAnzeige anzeige) {
        this.ctx = ctx.getApplicationContext();
        this.anzeige = anzeige;
        this.endeMs = System.currentTimeMillis() + minuten * 60_000L;
        File basis = this.ctx.getExternalFilesDir(WeckwortMessRegeln.ORDNER);
        this.modellOrdner = basis == null ? null : new File(basis, WeckwortMessRegeln.MODELL_ORDNER);
        boolean modellDa = modellVorhanden(modellOrdner);
        this.stat = new WeckwortMessStatistik(
                modellDa ? "vosk-android 0.3.47" : "keiner (nur Mikrofon)",
                modellDa ? modellName(modellOrdner) : "fehlt",
                WeckwortMessRegeln.MESSWORT, "MIC", ABTASTRATE, STUECK_MS);
        this.stat.kerne = Runtime.getRuntime().availableProcessors();
    }

    // ------------------------------------------------------------------ Steuerung (nur im Prozess :weckwort)

    /**
     * Start im eigenen Prozess {@code :weckwort} ({@link WeckwortMessDienst}). Ort (Autobildschirm, lokale Seite) und
     * Panoptes-Ton prüft vorher {@link WeckwortSteuerung} im App-Prozess; hier werden Schalter, Anzeige und Recht noch
     * einmal geprüft. {@code fertig} läuft nach dem Ende der Messung (Dienst beendet dann seinen Prozess).
     */
    static JSONObject imDienstStarten(Context ctx, int minutenWunsch, Runnable fertig) {
        JSONObject r = new JSONObject();
        try {
            synchronized (LOCK) {
                boolean schalter = schalterAn();
                boolean anzeigeMoeglich = WeckwortMessAnzeige.erlaubt(ctx);
                boolean recht = ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED;
                String nein = WeckwortMessRegeln.startVerweigert(true, schalter, anzeigeMoeglich, recht,
                        false, laufend != null);
                if (nein != null) {
                    r.put("ok", false).put("grund", nein);
                    return r;
                }
                WeckwortMessAnzeige anz = new WeckwortMessAnzeige(ctx);
                if (!anz.zeigen()) {
                    r.put("ok", false).put("grund", "keine_anzeige");
                    return r;
                }
                WeckwortMessung m = new WeckwortMessung(ctx, WeckwortMessRegeln.minuten(minutenWunsch), anz);
                laufend = m;
                m.fertig = fertig;
                m.los();
                r.put("ok", true).put("bisMs", m.endeMs);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Start fehlgeschlagen: " + t.getClass().getSimpleName());
            try {
                r.put("ok", false).put("grund", "fehler");
            } catch (Exception ignored) {
            }
        }
        return r;
    }

    static JSONObject stoppen(String grund) {
        WeckwortMessung m;
        synchronized (LOCK) {
            m = laufend;
        }
        JSONObject r = new JSONObject();
        try {
            if (m == null) return r.put("ok", true).put("lief", false);
            m.stoppGrund = grund == null ? "nutzer" : grund;
            m.stoppGewuenscht = true;
            Thread t = m.thread;
            if (t != null) t.join(5_000L);
            r.put("ok", true).put("lief", true);
        } catch (Exception ignored) {
        }
        return r;
    }

    static boolean laeuft() {
        synchronized (LOCK) {
            return laufend != null;
        }
    }

    // ------------------------------------------------------------------ Mess-Thread

    private void los() {
        stat.startMs = System.currentTimeMillis();
        stat.status = "laeuft";
        schreiben();
        thread = new Thread(this::schleife, "WeckwortMessung");
        thread.setPriority(Thread.NORM_PRIORITY);
        thread.start();
    }

    @SuppressLint("MissingPermission")
    private void schleife() {
        AudioRecord rec = null;
        VoskErkenner erkenner = null;
        short[] buf = new short[STUECK_SAMPLES];
        long naechstePruefung = 0, naechstesSchreiben = 0, naechstePss = 0;
        long wandStart = SystemClock.elapsedRealtime();
        long threadCpuStart = SystemClock.currentThreadTimeMillis();
        try {
            synchronized (stat) {
                stat.pss(Debug.getPss());
                stat.prozessCpu(android.os.Process.getElapsedCpuTime(), SystemClock.elapsedRealtime());
            }
            if (modellVorhanden(modellOrdner)) {
                // Stand vorher festhalten: stirbt der Prozess im nativen Code, sieht man danach, wo.
                synchronized (stat) {
                    stat.status = "laedt_modell";
                }
                schreiben();
                long t0 = SystemClock.elapsedRealtime();
                try {
                    erkenner = new VoskErkenner(modellOrdner.getAbsolutePath(), ABTASTRATE,
                            WeckwortMessRegeln.grammatik());
                    synchronized (stat) {
                        stat.ladeMs = SystemClock.elapsedRealtime() - t0;
                    }
                } catch (Throwable t) {
                    synchronized (stat) {
                        stat.erkennerFehler = t.getClass().getSimpleName();
                    }
                    erkenner = null;
                }
            }

            synchronized (stat) {
                stat.status = "laeuft";
            }
            int min = AudioRecord.getMinBufferSize(ABTASTRATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int puffer = Math.max(min, STUECK_SAMPLES * 2 * 4);
            try {
                rec = new AudioRecord(MediaRecorder.AudioSource.MIC, ABTASTRATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, puffer);
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                    synchronized (stat) {
                        stat.mikrofonFehler = "init_state_" + rec.getState();
                    }
                    rec.release();
                    rec = null;
                } else {
                    eigeneSitzung = rec.getAudioSessionId();
                    rueckrufAn();
                    rec.startRecording();
                    if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                        synchronized (stat) {
                            stat.mikrofonFehler = "start_state_" + rec.getRecordingState();
                        }
                    } else {
                        synchronized (stat) {
                            stat.mikrofonOffen = true;
                        }
                    }
                }
            } catch (Throwable t) {
                synchronized (stat) {
                    stat.mikrofonFehler = t.getClass().getSimpleName();
                }
                if (rec != null) {
                    try { rec.release(); } catch (Throwable ignored) { }
                    rec = null;
                }
            }

            while (!stoppGewuenscht) {
                long jetzt = System.currentTimeMillis();
                if (jetzt >= naechstePruefung) {
                    naechstePruefung = jetzt + PRUEF_MS;
                    // Panoptes-Ton meldet der App-Prozess per Stopp-Auftrag (WeckwortSteuerung), hier nicht sichtbar.
                    String g = WeckwortMessRegeln.stoppGrund(jetzt, endeMs, schalterAn(), false,
                            anzeige.istSichtbar());
                    if (g != null) {
                        stoppGrund = g;
                        break;
                    }
                    synchronized (stat) {
                        stat.prozessCpu(android.os.Process.getElapsedCpuTime(), SystemClock.elapsedRealtime());
                        stat.threadCpuMs = SystemClock.currentThreadTimeMillis() - threadCpuStart;
                        stat.nativeHeapKb = Debug.getNativeHeapAllocatedSize() / 1024L;
                    }
                }
                if (jetzt >= naechstePss) {
                    naechstePss = jetzt + PSS_MS;
                    long pss = Debug.getPss();
                    synchronized (stat) {
                        stat.pss(pss);
                    }
                }
                if (jetzt >= naechstesSchreiben) {
                    naechstesSchreiben = jetzt + SCHREIB_MS;
                    schreiben();
                }
                if (rec == null || !mikrofonOffen()) {
                    // Mikrofon nicht verfügbar: weiter messen (Zeit, CPU), aber ohne Audio.
                    SystemClock.sleep(STUECK_MS);
                    continue;
                }
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) {
                    synchronized (stat) {
                        stat.leseFehler(n);
                    }
                    SystemClock.sleep(20);
                    continue;
                }
                boolean nullen = WeckwortMessStatistik.alleNull(buf, n);
                double pegel = WeckwortMessStatistik.pegelDbfs(buf, n);
                double ms = -1;
                boolean treffer = false;
                if (erkenner != null) {
                    long t0 = System.nanoTime();
                    try {
                        treffer = erkenner.verarbeite(buf, n);
                    } catch (Throwable t) {
                        synchronized (stat) {
                            stat.erkennerFehler = t.getClass().getSimpleName();
                        }
                        erkenner.schliessen();
                        erkenner = null;
                    }
                    ms = (System.nanoTime() - t0) / 1_000_000.0;
                }
                synchronized (stat) {
                    stat.stueck(n, nullen, pegel);
                    if (ms >= 0) stat.verarbeitung(ms);
                    if (treffer) stat.treffer((System.currentTimeMillis() - stat.startMs) / 1000L);
                }
            }
        } catch (Throwable t) {
            stoppGrund = "fehler_" + t.getClass().getSimpleName();
        } finally {
            // Puffer überschreiben: das letzte Stück Audio soll nicht im Speicher liegen bleiben.
            java.util.Arrays.fill(buf, (short) 0);
            rueckrufAus();
            if (rec != null) {
                try { rec.stop(); } catch (Throwable ignored) { }
                try { rec.release(); } catch (Throwable ignored) { }
            }
            if (erkenner != null) erkenner.schliessen();
            synchronized (stat) {
                stat.mikrofonOffen = false;
                stat.threadCpuMs = SystemClock.currentThreadTimeMillis() - threadCpuStart;
                stat.prozessCpu(android.os.Process.getElapsedCpuTime(), SystemClock.elapsedRealtime());
                stat.pss(Debug.getPss());
                stat.endeMs = System.currentTimeMillis();
                stat.status = "beendet";
                stat.grund = stoppGrund == null ? "nutzer" : stoppGrund;
            }
            schreiben();
            anzeige.entfernen();
            synchronized (LOCK) {
                if (laufend == this) laufend = null;
            }
            Log.i(TAG, "Messung beendet: " + stat.grund + " nach " + (SystemClock.elapsedRealtime() - wandStart) / 1000 + " s");
            Runnable f = fertig;
            if (f != null) {
                try { f.run(); } catch (Throwable ignored) { }
            }
        }
    }

    private boolean mikrofonOffen() {
        synchronized (stat) {
            return stat.mikrofonOffen;
        }
    }

    // ------------------------------------------------------------------ Mikrofon-Konkurrenz

    private void rueckrufAn() {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            zaehleAufnahmen(am.getActiveRecordingConfigurations());
            rueckruf = new AudioManager.AudioRecordingCallback() {
                @Override
                public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                    zaehleAufnahmen(configs);
                }
            };
            am.registerAudioRecordingCallback(rueckruf, new Handler(Looper.getMainLooper()));
        } catch (Throwable ignored) {
        }
    }

    private void rueckrufAus() {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null && rueckruf != null) am.unregisterAudioRecordingCallback(rueckruf);
        } catch (Throwable ignored) {
        }
        rueckruf = null;
    }

    private void zaehleAufnahmen(List<AudioRecordingConfiguration> configs) {
        if (configs == null) return;
        int fremde = 0;
        boolean stumm = false;
        for (AudioRecordingConfiguration c : configs) {
            if (c.getClientAudioSessionId() == eigeneSitzung) {
                if (Build.VERSION.SDK_INT >= 29) stumm = c.isClientSilenced();
            } else {
                fremde++;
            }
        }
        synchronized (stat) {
            stat.fremdeAufnahmenLetzt = fremde;
            if (fremde > stat.fremdeAufnahmenMax) stat.fremdeAufnahmenMax = fremde;
            if (stumm && !warStumm) stat.stummMeldungen++;
        }
        warStumm = stumm;
    }

    // ------------------------------------------------------------------ Hilfen

    private void schreiben() {
        try {
            JSONObject j;
            synchronized (stat) {
                j = stat.alsJson(System.currentTimeMillis());
            }
            File basis = ctx.getExternalFilesDir(WeckwortMessRegeln.ORDNER);
            if (basis == null) return;
            if (!basis.exists() && !basis.mkdirs()) return;
            File ziel = new File(basis, WeckwortMessRegeln.ERGEBNIS_DATEI);
            File tmp = new File(basis, WeckwortMessRegeln.ERGEBNIS_DATEI + ".tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(j.toString(2).getBytes(StandardCharsets.UTF_8));
                o.getFD().sync();
            }
            if (!tmp.renameTo(ziel)) {
                tmp.delete();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Ergebnis nicht geschrieben: " + t.getClass().getSimpleName());
        }
    }

    private static boolean schalterAn() {
        try {
            return WeckwortMessRegeln.schalterAn(
                    at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig());
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean modellVorhanden(File ordner) {
        return ordner != null && new File(ordner, "am/final.mdl").isFile()
                && new File(ordner, "conf/model.conf").isFile();
    }

    private static String modellName(File ordner) {
        File readme = new File(ordner, "README");
        try {
            if (readme.isFile() && readme.length() < 8192) {
                String erste = new String(java.nio.file.Files.readAllBytes(readme.toPath()),
                        StandardCharsets.UTF_8).split("\n", 2)[0].trim();
                if (!erste.isEmpty() && erste.length() < 80) return erste;
            }
        } catch (Throwable ignored) {
        }
        return "vosk-modell";
    }
}
