package at.greenchili.surfmate.camera;

import org.json.JSONObject;

import java.util.function.LongSupplier;

/**
 * 0.1.43.18 (F4b/F5, KAMERA_AUSSETZER_ANALYSE): Anlaufzeit der Kamera im Kern. Nach Kern-Start, Zündung und jedem
 * (Neu-)Öffnen der Rundumkamera braucht der BYD-Treiber auf DiLink 3 bis etwa 22 s, bis wieder Bilder kommen
 * (Freigabe an die BYD-App bis 15 s + Treiber-Anlauf 5–8 s). In dieser Zeit ist „startet“ die ehrliche Antwort,
 * nicht „startet nicht“.
 *
 * <p>Der Kern hängt an jede Live-Antwort „starting“ {@code anlaufRestMs} an; die Live-Ansicht wartet damit bis zum
 * Ende der Anlaufzeit, statt nach festen 10,5 s „Die Kamera startet nicht“ zu zeigen. Echte Fehler (kein „starting“)
 * sind davon nicht betroffen. Rein bis auf die Uhr (für Tests ersetzbar), eine Stelle für alle Aufrufer.
 */
public final class KameraAnlauf {

    public static final long ANLAUF_MS = 25_000L;
    /** Höchstens so oft schreibt der Kern eine Diagnosezeile zu „starting“-Antworten. */
    static final long DIAGNOSE_ABSTAND_MS = 10_000L;

    private static volatile LongSupplier uhr = KameraAnlauf::systemUhr;
    private static volatile long kernStartMs = -1L;
    private static volatile long zuendungMs = -1L;
    private static volatile long oeffnungMs = -1L;
    private static volatile long letzteDiagnoseMs = -1L;

    private KameraAnlauf() {}

    private static long systemUhr() {
        try {
            return android.os.SystemClock.elapsedRealtime();
        } catch (Throwable t) {
            return System.nanoTime() / 1_000_000L;   // JVM-Tests ohne Android
        }
    }

    public static void kernGestartet() { kernStartMs = uhr.getAsLong(); }

    public static void zuendung() { zuendungMs = uhr.getAsLong(); }

    public static void kameraGeoeffnet() { oeffnungMs = uhr.getAsLong(); }

    /** Restliche Anlaufzeit in ms, 0 = vorbei bzw. kein Ereignis bekannt. */
    public static long restMs() {
        return restMs(uhr.getAsLong(), kernStartMs, zuendungMs, oeffnungMs);
    }

    /** Rein: Rest der Anlaufzeit ab dem jüngsten Ereignis; Uhr rückwärts zählt nicht als Anlauf. */
    static long restMs(long jetzt, long kern, long zuendung, long oeffnung) {
        long juengstes = Math.max(kern, Math.max(zuendung, oeffnung));
        if (juengstes < 0L || jetzt < juengstes) return 0L;
        return Math.max(0L, ANLAUF_MS - (jetzt - juengstes));
    }

    /**
     * An eine „starting“-Antwort anhängen. F5: Ist die Anlaufzeit schon vorbei, schreibt der Kern höchstens alle
     * {@link #DIAGNOSE_ABSTAND_MS} eine Zeile (Abstände zu Kern-Start, Zündung, Öffnung), denn dann kann die
     * Live-Ansicht gleich „startet nicht“ zeigen.
     */
    public static void anhaengen(JSONObject antwort) {
        if (antwort == null) return;
        long jetzt = uhr.getAsLong();
        long rest = restMs(jetzt, kernStartMs, zuendungMs, oeffnungMs);
        try {
            antwort.put("anlaufRestMs", rest);
        } catch (Exception ignored) {
        }
        if (rest == 0L && (letzteDiagnoseMs < 0L || jetzt - letzteDiagnoseMs >= DIAGNOSE_ABSTAND_MS
                || jetzt < letzteDiagnoseMs)) {
            letzteDiagnoseMs = jetzt;
            try {
                at.greenchili.surfmate.logging.DaemonLogger.getInstance("KameraAnlauf").info(diagnose(jetzt));
            } catch (Throwable ignored) {
            }
        }
    }

    /** F5: Diagnosezeile (ohne Ort, ohne Kennung). */
    static String diagnose(long jetzt) {
        return "KAMERA_MELDUNG quelle=live_starting anlauf_vorbei=1"
                + " seit_kernstart_ms=" + seit(jetzt, kernStartMs)
                + " seit_acc_ms=" + seit(jetzt, zuendungMs)
                + " seit_oeffnung_ms=" + seit(jetzt, oeffnungMs);
    }

    private static long seit(long jetzt, long t) {
        return t < 0L ? -1L : Math.max(-1L, jetzt - t);
    }

    static void fuerTestZuruecksetzen(LongSupplier testUhr) {
        uhr = testUhr == null ? KameraAnlauf::systemUhr : testUhr;
        kernStartMs = -1L;
        zuendungMs = -1L;
        oeffnungMs = -1L;
        letzteDiagnoseMs = -1L;
    }
}
