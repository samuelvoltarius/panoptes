package at.greenchili.surfmate.surveillance;

/**
 * Ruhegrenze der Abschreckungs-Anzeige (Koordination 21.10.2026): Eine Anzeige-Sitzung dauert
 * höchstens {@link #MAX_SITZUNG_MS}, auch wenn die Bewegung anhält; danach bleibt das Display für
 * die eingestellte Ruhezeit (Standard {@link #RUHE_STANDARD_S} s) aus. Verhindert, dass ein
 * Fehlalarm-Dauerlauf jede Minute das Display weckt (Lesebericht 21.10., 02:08–02:21).
 *
 * <p>0.1.28 (Projektinhaber sah die Anzeige nur jedes 2.–3. Mal, ABSCHRECKUNG_TABELLE_20261029): Die Ruhezeit merkt sich,
 * ob ihre Sitzung eine Person hatte. Eine Ruhe aus einer Sitzung OHNE Person (vorbeifahrendes Auto, verworfene
 * helle Bewegung) sperrt weitere Nicht-Person-Anzeigen (kein Dauerwecken), aber nie eine Person.
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
    /** Die laufende Sitzung hat (inzwischen) eine Person gesehen. */
    private volatile boolean sitzungMitPerson = false;
    /** Die aktuelle Ruhezeit stammt von einer Sitzung mit Person (dann sperrt sie auch Personen). */
    private volatile boolean ruheVonPerson = false;

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
        return darfBeginnen(now, false);
    }

    /** Wie oben; eine Person durchbricht eine Ruhe, die nur von einer Sitzung ohne Person stammt. */
    boolean darfBeginnen(long now, boolean person) {
        return now >= ruheBis || (person && !ruheVonPerson);
    }

    /** Restliche Ruhezeit in ms (0 = keine). */
    long ruheRestMs(long now) {
        return Math.max(0, ruheBis - now);
    }

    boolean ruheVonPerson() {
        return ruheVonPerson;
    }

    boolean sitzungMitPerson() {
        return sitzungMitPerson;
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
        sitzungBeginnt(now, false);
    }

    void sitzungBeginnt(long now, boolean person) {
        sitzungStart = now;
        sitzungMitPerson = person;
    }

    /**
     * Person kommt in eine laufende Sitzung ohne Person (z. B. erst Auto, dann Mensch): ab jetzt eine Sitzung mit
     * Person, mit neuem 30-s-Deckel – sonst sähe der Mensch nur den Rest der Auto-Sitzung.
     */
    void personDazu(long now) {
        if (sitzungStart < 0 || sitzungMitPerson) return;
        sitzungMitPerson = true;
        sitzungStart = now;
    }

    /** Sitzung ist wirklich zu Ende (kein Weiterlauf): Ruhezeit beginnt. */
    void sitzungEndet(long now) {
        if (sitzungStart < 0) return;
        sitzungStart = -1;
        ruheBis = now + ruheS * 1000L;
        ruheVonPerson = sitzungMitPerson;
        sitzungMitPerson = false;
    }

    boolean inRuhe(long now) {
        return now < ruheBis;
    }
}
