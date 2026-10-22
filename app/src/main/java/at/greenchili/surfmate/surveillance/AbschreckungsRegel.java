package at.greenchili.surfmate.surveillance;

/**
 * Ruhegrenze der Abschreckungs-Anzeige (Koordination 21.10.2026): Eine Anzeige-Sitzung dauert
 * höchstens {@link #MAX_SITZUNG_MS}, auch wenn die Bewegung anhält; danach bleibt das Display für
 * die eingestellte Ruhezeit (Standard {@link #RUHE_STANDARD_S} s) aus. Verhindert, dass ein
 * Fehlalarm-Dauerlauf jede Minute das Display weckt (Lesebericht 21.10., 02:08–02:21).
 *
 * <p>Läuft im heißen Pfad des GL-Threads: nur volatile Felder, keine Sperren, keine Allokation.
 * Zeiten in {@code SystemClock.elapsedRealtime()}-Millisekunden.
 */
final class AbschreckungsRegel {

    static final int RUHE_STANDARD_S = 120;
    static final int RUHE_MIN_S = 30;
    static final int RUHE_MAX_S = 1800;
    static final long MAX_SITZUNG_MS = 30_000L;

    private volatile long sitzungStart = -1;
    private volatile long ruheBis = Long.MIN_VALUE;
    private volatile int ruheS = RUHE_STANDARD_S;

    /** Ruhezeit aus der Config ({@code surveillance.deterrentQuietSeconds}), begrenzt. */
    void setRuheSekunden(int s) {
        ruheS = Math.max(RUHE_MIN_S, Math.min(RUHE_MAX_S, s));
    }

    int ruheSekunden() {
        return ruheS;
    }

    /** Läuft gerade eine Sitzung (begonnen, noch nicht beendet)? */
    boolean laufend() {
        return sitzungStart >= 0;
    }

    /** Darf eine neue Sitzung beginnen? Nein während der Ruhezeit nach der letzten Sitzung. */
    boolean darfBeginnen(long now) {
        return now >= ruheBis;
    }

    /** Darf eine laufende Sitzung verlängert werden? Nur bis {@link #MAX_SITZUNG_MS} nach Beginn. */
    boolean darfVerlaengern(long now) {
        long s = sitzungStart;
        return s >= 0 && now - s < MAX_SITZUNG_MS;
    }

    /** Spätestes Ende der laufenden Sitzung (Deckel für die Verlängerung). */
    long sitzungsDeckel() {
        long s = sitzungStart;
        return s < 0 ? Long.MAX_VALUE : s + MAX_SITZUNG_MS;
    }

    void sitzungBeginnt(long now) {
        sitzungStart = now;
    }

    /** Sitzung ist wirklich zu Ende (kein Weiterlauf): Ruhezeit beginnt. */
    void sitzungEndet(long now) {
        if (sitzungStart < 0) return;
        sitzungStart = -1;
        ruheBis = now + ruheS * 1000L;
    }

    boolean inRuhe(long now) {
        return now < ruheBis;
    }
}
