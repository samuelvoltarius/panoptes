package at.greenchili.surfmate.planer;

/**
 * Backoff für Planer-Abrufe bei Netzfehlern (Koordination 0.1.9, Punkt 5 „umkreis“): Ist der Planer nicht
 * erreichbar (kein Netz, DNS weg im Mobilfunk, Zeitüberschreitung), wird nicht bei jedem Kartenschwenk erneut
 * gefragt und nicht jedes Mal geloggt. Rein, testbar, thread-sicher.
 *
 * <p>Wartezeit 15 s, 30 s, 60 s … höchstens 5 min; der erste Erfolg setzt zurück.
 */
public final class PlanerBackoff {

    public static final long START_MS = 15_000L;
    public static final long MAX_MS = 5 * 60_000L;

    /** Gemeinsam für {@code umkreis} (Karte im Web und Belegung unterwegs). */
    public static final PlanerBackoff UMKREIS = new PlanerBackoff();

    private int fehler;
    private long gesperrtBis;

    /** Millisekunden, bis wieder gefragt werden darf (0 = jetzt). */
    public synchronized long warteMs(long now) {
        return Math.max(0L, gesperrtBis - now);
    }

    /** Netzfehler verbuchen; true = erster Fehler einer Serie (nur dann loggen). */
    public synchronized boolean fehler(long now) {
        fehler++;
        long warte = Math.min(MAX_MS, START_MS << Math.min(fehler - 1, 5));
        gesperrtBis = now + warte;
        return fehler == 1;
    }

    /** Erfolg; true = vorher gab es Fehler (einmal „wieder erreichbar“ loggen). */
    public synchronized boolean erfolg() {
        boolean war = fehler > 0;
        fehler = 0;
        gesperrtBis = 0;
        return war;
    }

    public synchronized int fehlerInFolge() {
        return fehler;
    }

    /** Netzfehler im Sinne des Backoffs (nicht: HTTP-Fehler des Planers, die sind dessen Antwort). */
    public static boolean istNetzfehler(Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause()) {
            if (x instanceof java.net.UnknownHostException
                    || x instanceof java.net.SocketException
                    || x instanceof java.net.SocketTimeoutException
                    || x instanceof javax.net.ssl.SSLException
                    || x instanceof java.io.InterruptedIOException) {
                return true;
            }
        }
        return false;
    }
}
