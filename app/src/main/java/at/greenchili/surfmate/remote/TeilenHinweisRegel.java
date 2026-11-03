package at.greenchili.surfmate.remote;

/**
 * Hinweis „Bildschirm wird geteilt“ am Auto-Display (0.1.37, DashArc-Abgleich Punkt 15), reine Regel.
 *
 * <p>Sichtbar ab dem Start einer Fernansicht ({@code launch} über die Brücke) bis zum Ende ({@code stop}). Jeder
 * Zugriff (Bild, Berührung, Taste, Text, Status) frischt auf. Kommt länger als {@link #LEERLAUF_MS} nichts, ist die
 * Sitzung im Daemon ohnehin abgelaufen (5 min) und der Hinweis verschwindet.
 */
public final class TeilenHinweisRegel {

    /** Etwas länger als die Leerlaufzeit der Sitzung im Daemon (5 min). */
    public static final long LEERLAUF_MS = 6 * 60 * 1000L;

    private long start, letzter;
    private boolean laeuft;

    public synchronized void gestartet(long now) {
        laeuft = true;
        start = now;
        letzter = now;
    }

    public synchronized void zugriff(long now) {
        if (laeuft) letzter = now;
    }

    public synchronized void beendet() {
        laeuft = false;
    }

    public synchronized boolean sichtbar(long now) {
        if (!laeuft) return false;
        if (now < letzter || now - letzter > LEERLAUF_MS) {
            laeuft = false;
            return false;
        }
        return true;
    }

    public synchronized long seit() {
        return laeuft ? start : 0;
    }
}
