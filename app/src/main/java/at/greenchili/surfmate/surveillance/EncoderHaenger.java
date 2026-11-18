package at.greenchili.surfmate.surveillance;

import at.greenchili.surfmate.logging.DaemonLogger;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Hängende Hardware-Encoder-Aufrufe (0.1.43.14, E2E_014313 B1).
 *
 * <p><b>Befund:</b> {@code MediaCodec.createEncoderByType} lief im Emulator nach
 * dem 10-s-Timeout in nativem Code weiter (Thread „EncoderCreate“, Dauerschleife
 * {@code AString ctor got NULL}). {@code Thread.interrupt()} erreicht nativen Code
 * nicht. Der Thread hielt etwa einen CPU-Kern und wuchs in 12 min auf 3,4 GB PSS,
 * bis der Kernel den OOM-Killer rief. Der Kern hat {@code oom_score_adj=-1000};
 * der OOM-Killer räumt dann andere Prozesse ab, im Emulator startete das System
 * neu. In-Prozess lässt sich so ein Thread nicht stoppen.
 *
 * <p><b>Was diese Klasse macht:</b>
 * <ul>
 *   <li>{@link #zeitbegrenzt}: führt einen Encoder-Aufruf in einem eigenen Thread
 *       mit Frist aus. Läuft er über die Frist, wird der Thread hier registriert,
 *       und ein Ergebnis, das doch noch kommt, wird sofort freigegeben (kein
 *       verwaister Codec).</li>
 *   <li>{@link #melden}: registriert einen hängenden Encoder-Thread, zählt den
 *       Vorfall dauerhaft (Backoff über Prozess-Neustarts hinweg) und startet die
 *       Aufsicht.</li>
 *   <li>Aufsicht: Lebt ein gemeldeter Thread nach {@link #NACHFRIST_MS} noch, wird
 *       der trip-sichere Neustart des Kerns angefordert. Das ist der einzige Weg,
 *       den nativen Thread und seinen Speicher loszuwerden. Lebt der Prozess
 *       {@link #ESKALATION_MS} danach noch (der trip-sichere Weg wartet
 *       unbegrenzt auf den Trip-Checkpoint), folgt der begrenzte
 *       Notfall-Neustart.</li>
 *   <li>{@link #sperrgrund}: Solange ein gemeldeter Thread lebt oder der Backoff
 *       läuft, darf kein neuer Encoder-Aufbau starten. Sonst legt jeder Versuch
 *       einen weiteren hängenden Thread an.</li>
 * </ul>
 *
 * <p>Backoff je Vorfall in Folge: 30 s, 2 min, 5 min, danach höchstens 10 min.
 * Ein Encoder, der rechtzeitig entsteht, setzt den Zähler zurück, aber erst
 * 30 min nach dem letzten Hänger.
 */
public final class EncoderHaenger {

    private static final String TAG = "EncoderHaenger";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    /** Zeit nach dem Timeout, die ein Worker noch bekommt, bevor der Kern neu startet. */
    static final long NACHFRIST_MS = 20_000L;
    /** Prüftakt der Aufsicht. */
    static final long AUFSICHT_TAKT_MS = 2_000L;
    /**
     * Lebt der Prozess so lange nach der trip-sicheren Anforderung noch, wird
     * auf den begrenzten Notfall-Neustart eskaliert. Grund: der trip-sichere
     * Weg wartet unbegrenzt auf den Trip-Checkpoint
     * ({@code CameraDaemon.prepareTripsForProcessRestart}); währenddessen wächst
     * der hängende Thread weiter (E2E_014313: ~250 MB/min).
     */
    static final long ESKALATION_MS = 30_000L;

    /** Backoff-Stufen; der letzte Wert gilt für alle weiteren Vorfälle. */
    static final long[] BACKOFF_MS = {30_000L, 120_000L, 300_000L, 600_000L};

    /** So lange ohne neuen Hänger, bevor ein Erfolg den Folge-Zähler zurücksetzt. */
    static final long ERHOLUNG_MS = 30L * 60_000L;

    static final String DATEI = "encoder_haenger.json";

    /** Ausnahme für einen Encoder-Aufruf, der seine Frist überschritten hat. */
    public static final class EncoderHaengerException extends RuntimeException {
        public final String phase;

        public EncoderHaengerException(String phase, String message) {
            super(message);
            this.phase = phase;
        }
    }

    /** Neustart-Hook; Tests ersetzen ihn. */
    interface Neustart {
        /** Trip-sicherer Neustart (Checkpoint, dann System.exit). */
        void anfordern(String grund);
        /** Begrenzter Notfall-Neustart (feste Halt-Frist). */
        void dringend(String grund);
        boolean laeuftSchon();
    }

    private static final Object LOCK = new Object();
    private static final List<Thread> haengend = new ArrayList<>();
    private static boolean aufsichtLaeuft = false;
    private static boolean zustandGeladen = false;
    private static int anzahl = 0;
    private static long zuletztMs = 0L;

    private static volatile LongSupplier uhr = System::currentTimeMillis;
    private static volatile File zustandsDatei = null;
    private static volatile long nachfristMs = NACHFRIST_MS;
    private static volatile long aufsichtTaktMs = AUFSICHT_TAKT_MS;
    private static volatile long eskalationMs = ESKALATION_MS;
    private static volatile Neustart neustart = new Neustart() {
        @Override
        public void anfordern(String grund) {
            at.greenchili.surfmate.daemon.CameraDaemon
                    .requestProcessRestartPreservingTrip(grund);
        }

        @Override
        public void dringend(String grund) {
            at.greenchili.surfmate.daemon.CameraDaemon
                    .requestUrgentCameraReleaseRestart(grund);
        }

        @Override
        public boolean laeuftSchon() {
            return at.greenchili.surfmate.daemon.CameraDaemon.isProcessRestartPending();
        }
    };

    private EncoderHaenger() {}

    // ------------------------------------------------------------------
    // Reine Regel
    // ------------------------------------------------------------------

    /** Wartezeit nach {@code anzahl} Vorfällen in Folge (0 = keiner). */
    static long backoffMs(int anzahl) {
        if (anzahl <= 0) return 0L;
        int i = Math.min(anzahl, BACKOFF_MS.length) - 1;
        return BACKOFF_MS[i];
    }

    /**
     * Restliche Sperrzeit in ms. 0 = frei. Geht die Uhr zurück (now vor dem
     * letzten Vorfall), gilt frei: eine verstellte Uhr darf die Kamera nicht
     * dauerhaft sperren.
     */
    static long restSperreMs(int anzahl, long zuletztMs, long nowMs) {
        if (anzahl <= 0 || zuletztMs <= 0L) return 0L;
        long seit = nowMs - zuletztMs;
        if (seit < 0L) return 0L;
        long rest = backoffMs(anzahl) - seit;
        return rest > 0L ? rest : 0L;
    }

    // ------------------------------------------------------------------
    // Zeitbegrenzter Aufruf
    // ------------------------------------------------------------------

    /**
     * Führt {@code aufruf} in einem eigenen Thread {@code name} aus und wartet
     * höchstens {@code fristMs}. Überschreitet er die Frist: Thread melden,
     * {@link EncoderHaengerException} werfen; liefert er danach doch noch ein
     * Ergebnis, bekommt {@code spaetFreigeben} es sofort (z. B. Codec release).
     */
    public static <T> T zeitbegrenzt(String name, long fristMs, Callable<T> aufruf,
                                     Consumer<T> spaetFreigeben) throws Exception {
        final AtomicBoolean aufgegeben = new AtomicBoolean(false);
        final Object[] ergebnis = {null};
        final Exception[] fehler = {null};
        final AtomicBoolean fertig = new AtomicBoolean(false);
        Thread t = new Thread(() -> {
            T r = null;
            Exception f = null;
            try {
                r = aufruf.call();
            } catch (Exception e) {
                f = e;
            }
            synchronized (fertig) {
                if (aufgegeben.get()) {
                    // Zu spät: niemand nimmt das Ergebnis mehr ab.
                    if (r != null && spaetFreigeben != null) {
                        try {
                            spaetFreigeben.accept(r);
                            logger.warn(name + ": verspätetes Ergebnis freigegeben");
                        } catch (Throwable ignored) {}
                    }
                    return;
                }
                ergebnis[0] = r;
                fehler[0] = f;
                fertig.set(true);
                fertig.notifyAll();
            }
        }, name);
        t.setDaemon(true);
        t.start();

        long ende = System.nanoTime() + fristMs * 1_000_000L;
        synchronized (fertig) {
            while (!fertig.get()) {
                long restMs = (ende - System.nanoTime()) / 1_000_000L;
                if (restMs <= 0L) break;
                try {
                    fertig.wait(restMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (!fertig.get()) {
                aufgegeben.set(true);
            }
        }
        if (aufgegeben.get()) {
            t.interrupt();
            melden(t, name);
            throw new EncoderHaengerException(name, name + " timeout");
        }
        if (fehler[0] != null) throw fehler[0];
        @SuppressWarnings("unchecked")
        T r = (T) ergebnis[0];
        return r;
    }

    // ------------------------------------------------------------------
    // Melden, Sperre, Erfolg
    // ------------------------------------------------------------------

    /**
     * Registriert einen Encoder-Thread, der seine Frist überschritten hat,
     * zählt den Vorfall dauerhaft und startet die Aufsicht.
     */
    public static void melden(Thread worker, String phase) {
        synchronized (LOCK) {
            ladenLocked();
            if (worker != null) haengend.add(worker);
            anzahl++;
            zuletztMs = uhr.getAsLong();
            speichernLocked();
            logger.error("Encoder-Aufruf hängt (" + phase + "), Vorfall " + anzahl
                    + " in Folge; neuer Encoder-Aufbau gesperrt für "
                    + (backoffMs(anzahl) / 1000L) + " s bzw. bis zum Kern-Neustart");
        }
        aufsichtSicherstellen();
    }

    /**
     * Encoder rechtzeitig entstanden. Der Folge-Zähler fällt erst zurück, wenn
     * der letzte Hänger {@link #ERHOLUNG_MS} her ist. Emulator-Nachweis
     * 0.1.43.14: Der ERSTE Encoder nach dem Kern-Neustart entsteht problemlos,
     * erst der Neuaufbau hängt wieder. Ein sofortiges Zurücksetzen hielt den
     * Backoff dauerhaft bei 30 s, also Neustart alle ~75 s ohne Ende.
     */
    public static void erfolg() {
        synchronized (LOCK) {
            ladenLocked();
            if (anzahl == 0) return;
            long seit = uhr.getAsLong() - zuletztMs;
            if (seit >= 0L && seit < ERHOLUNG_MS) return;
            logger.info("Encoder wieder rechtzeitig erstellt — Hänger-Zähler zurückgesetzt");
            anzahl = 0;
            zuletztMs = 0L;
            speichernLocked();
        }
    }

    /** true, solange ein gemeldeter Encoder-Thread in diesem Prozess noch lebt. */
    public static boolean haengtNoch() {
        synchronized (LOCK) {
            aufraeumenLocked();
            return !haengend.isEmpty();
        }
    }

    /**
     * Grund, warum gerade kein Encoder aufgebaut werden darf, oder null.
     * Kurz und ohne Nutzerdaten, geht ins Log und in die Live-Antwort.
     */
    public static String sperrgrund() {
        synchronized (LOCK) {
            aufraeumenLocked();
            if (!haengend.isEmpty()) {
                return "Hardware-Encoder hängt; Kern startet neu";
            }
            ladenLocked();
            long rest = restSperreMs(anzahl, zuletztMs, uhr.getAsLong());
            if (rest > 0L) {
                return "Hardware-Encoder hing; nächster Versuch in "
                        + ((rest + 999L) / 1000L) + " s";
            }
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Aufsicht
    // ------------------------------------------------------------------

    /** Startet die Aufsicht, falls ein gemeldeter Thread lebt und keine läuft. */
    public static void aufsichtSicherstellen() {
        synchronized (LOCK) {
            aufraeumenLocked();
            if (haengend.isEmpty() || aufsichtLaeuft) return;
            aufsichtLaeuft = true;
        }
        Thread a = new Thread(EncoderHaenger::aufsicht, "EncoderHaengerAufsicht");
        a.setDaemon(true);
        try {
            a.start();
        } catch (Throwable t) {
            synchronized (LOCK) { aufsichtLaeuft = false; }
            logger.error("Encoder-Aufsicht konnte nicht starten: " + t.getMessage());
        }
    }

    private static void aufsicht() {
        long beginn = System.nanoTime();
        long angefordert = Long.MIN_VALUE;
        boolean eskaliert = false;
        try {
            while (true) {
                synchronized (LOCK) {
                    aufraeumenLocked();
                    if (haengend.isEmpty()) {
                        logger.info("Encoder-Aufsicht: kein hängender Thread mehr — beendet");
                        return;
                    }
                }
                long jetzt = System.nanoTime();
                long seitMs = (jetzt - beginn) / 1_000_000L;
                if (seitMs >= nachfristMs && angefordert == Long.MIN_VALUE) {
                    angefordert = jetzt;
                    if (neustart.laeuftSchon()) {
                        logger.error("Encoder-Thread hängt seit " + (seitMs / 1000L)
                                + " s; Kern-Neustart läuft bereits");
                    } else {
                        logger.error("Encoder-Thread hängt seit " + (seitMs / 1000L)
                                + " s in nativem Code — trip-sicherer Kern-Neustart angefordert");
                        try {
                            neustart.anfordern("encoder worker stuck in native code");
                        } catch (Throwable t) {
                            logger.error("Kern-Neustart konnte nicht angefordert werden: "
                                    + t.getMessage());
                        }
                    }
                } else if (angefordert != Long.MIN_VALUE && !eskaliert
                        && (jetzt - angefordert) / 1_000_000L >= eskalationMs) {
                    // Der trip-sichere Weg wartet unbegrenzt auf den Checkpoint;
                    // der Thread wächst so lange weiter. Begrenzt beenden.
                    eskaliert = true;
                    logger.error("Encoder-Thread hängt weiter, Kern lebt "
                            + (eskalationMs / 1000L) + " s nach der Neustart-Anforderung"
                            + " — begrenzter Notfall-Neustart");
                    try {
                        neustart.dringend("encoder worker stuck in native code (escalated)");
                    } catch (Throwable t) {
                        logger.error("Notfall-Neustart konnte nicht angefordert werden: "
                                + t.getMessage());
                    }
                }
                Thread.sleep(aufsichtTaktMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (LOCK) { aufsichtLaeuft = false; }
        }
    }

    private static void aufraeumenLocked() {
        haengend.removeIf(t -> t == null || !t.isAlive());
    }

    // ------------------------------------------------------------------
    // Dauerhafter Zustand (über Kern-Neustarts)
    // ------------------------------------------------------------------

    private static File datei() {
        File f = zustandsDatei;
        if (f != null) return f;
        try {
            return new File(at.greenchili.surfmate.util.DaemonStorage.rebase(
                    at.greenchili.surfmate.util.DaemonStorage.RELOCATED_BASE), DATEI);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void ladenLocked() {
        if (zustandGeladen) return;
        zustandGeladen = true;
        File f = datei();
        if (f == null || !f.isFile()) return;
        try {
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            org.json.JSONObject o = new org.json.JSONObject(s);
            anzahl = Math.max(0, o.optInt("anzahl", 0));
            zuletztMs = Math.max(0L, o.optLong("zuletzt", 0L));
        } catch (Throwable t) {
            // Kaputte Datei = kein Backoff. Der Aufsichts-/Thread-Teil schützt weiter.
            anzahl = 0;
            zuletztMs = 0L;
        }
    }

    private static void speichernLocked() {
        File f = datei();
        if (f == null) return;
        try {
            if (anzahl == 0) {
                if (f.exists() && !f.delete()) {
                    logger.warn("Hänger-Zustand konnte nicht entfernt werden");
                }
                return;
            }
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            String json = "{\"anzahl\":" + anzahl + ",\"zuletzt\":" + zuletztMs + "}";
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(f)) {
                Files.write(f.toPath(), json.getBytes(StandardCharsets.UTF_8));
                tmp.delete();
            }
        } catch (Throwable t) {
            logger.warn("Hänger-Zustand nicht gespeichert: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Nur für Tests
    // ------------------------------------------------------------------

    static void fuerTestZuruecksetzen(File datei, LongSupplier testUhr, Neustart testNeustart,
                                      long testNachfristMs, long testTaktMs,
                                      long testEskalationMs) {
        synchronized (LOCK) {
            haengend.clear();
            zustandGeladen = false;
            anzahl = 0;
            zuletztMs = 0L;
            zustandsDatei = datei;
            uhr = testUhr;
            neustart = testNeustart;
            nachfristMs = testNachfristMs;
            aufsichtTaktMs = testTaktMs;
            eskalationMs = testEskalationMs;
        }
    }

    static int anzahlFuerTest() {
        synchronized (LOCK) {
            ladenLocked();
            return anzahl;
        }
    }

    static boolean aufsichtLaeuftFuerTest() {
        synchronized (LOCK) { return aufsichtLaeuft; }
    }
}
