package at.greenchili.surfmate.gesundheit;

/**
 * Stufe 3 des Gesundheitswächters, im App-Prozess: Der Shell-Ausführer (dadb, adb 127.0.0.1:5555) ist das
 * Nadelöhr, über das die App Daemons startet und prüft. Antwortet er {@link #FEHLER_BIS_NEUAUFBAU} Takte am
 * Stück nicht (30-s-Takt = 2 min), wird die gemeinsame Verbindung neu aufgebaut – höchstens alle
 * {@link #ABSTAND_MS}. Nie während Deploy-Sperre/Update. Rein.
 */
public final class AusfuehrerWaechter {

    static final int FEHLER_BIS_NEUAUFBAU = 4;
    static final long ABSTAND_MS = 10 * 60_000L;

    private int fehler;
    private long letzterNeuaufbau = Long.MIN_VALUE / 2;

    /** @return true, wenn jetzt neu aufgebaut werden soll. */
    public synchronized boolean ergebnis(boolean antwort, boolean sperre, long now) {
        if (antwort) {
            fehler = 0;
            return false;
        }
        fehler++;
        if (sperre || fehler < FEHLER_BIS_NEUAUFBAU || now - letzterNeuaufbau < ABSTAND_MS) return false;
        letzterNeuaufbau = now;
        fehler = 0;
        return true;
    }

    public synchronized int fehler() { return fehler; }
}
