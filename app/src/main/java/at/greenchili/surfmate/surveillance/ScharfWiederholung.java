package at.greenchili.surfmate.surveillance;

/**
 * Wiederholung des Scharfschaltens, wenn die Pipeline es abweist (Befund Deploy 21.10.2026: nach jedem
 * Daemon-Start wies {@code enableSurveillance} den ersten Versuch mit „pipeline is not available“ ab, und der
 * Wächter war bis zum nächsten Abgleich 5 min blind).
 *
 * <p>Backoff 5 s, 10 s, 20 s, 40 s, danach alle 60 s bis zum Erfolg, ohne Obergrenze: der Wächter soll scharf
 * werden, solange das gewollt ist. Erfolg oder „nicht mehr gewollt“ setzen zurück. Rein.
 */
public final class ScharfWiederholung {

    static final long[] SCHRITTE_MS = {5_000L, 10_000L, 20_000L, 40_000L, 60_000L};

    private int versuch;
    private long faelligAm = -1;

    /** Abgewiesen: nächsten Versuch planen. @return Wartezeit in ms */
    public synchronized long abgewiesen(long now) {
        long warte = SCHRITTE_MS[Math.min(versuch, SCHRITTE_MS.length - 1)];
        versuch++;
        faelligAm = now + warte;
        return warte;
    }

    /** Scharf geworden oder nicht mehr gewollt. */
    public synchronized void zuruecksetzen() {
        versuch = 0;
        faelligAm = -1;
    }

    /** Ob jetzt ein Versuch fällig ist (und dann als „läuft“ markieren). */
    public synchronized boolean faellig(long now) {
        if (faelligAm < 0 || now < faelligAm) return false;
        faelligAm = -1;
        return true;
    }

    public synchronized boolean wartet() {
        return faelligAm >= 0;
    }

    public synchronized int versuche() {
        return versuch;
    }

    /**
     * Zustand für die Status-API (Wahrheitsregel): {@code scharf} nur, wenn wirklich scharf;
     * {@code startet}, solange gewollt, aber noch nicht scharf; {@code unterdrueckt} bei
     * Wächter-Stopp/sicherem Ort/Zeitplan; sonst {@code aus}.
     */
    public static String zustand(boolean gewollt, boolean scharf, boolean unterdrueckt) {
        if (scharf) return "scharf";
        if (!gewollt) return "aus";
        if (unterdrueckt) return "unterdrueckt";
        return "startet";
    }
}
