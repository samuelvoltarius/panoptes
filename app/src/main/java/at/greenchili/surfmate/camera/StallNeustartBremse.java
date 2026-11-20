package at.greenchili.surfmate.camera;

/**
 * 0.1.43.18 (F1, KAMERA_AUSSETZER_ANALYSE): Bremse für die Stall-Neustarts der Rundumkamera auf dem Legacy-Pfad
 * (DiLink 3 und andere ohne DiLink-4/5-Weg). Rein, JVM-testbar, eine Instanz je Kamera.
 *
 * <p>Befund 19.11. (0.1.43.15): Nach der Zündung im Stand lief die Kamera nur für den bereiten, aber nicht gezeigten
 * Toten Winkel mit 1 Bild/s. Der Treiber lieferte dabei Lücken über 4 s, und der Stall-Wächter öffnete die Kamera
 * alle 10–14 s neu (45 Mal), ohne dass jemand Bilder brauchte, ohne Grenze und ohne Pause.
 *
 * <p>Regeln:
 * <ul>
 *   <li>Schwelle nach der Ziel-Bildrate: {@code max(4 s, 4 × Bildabstand + 2 s)}; bei 1 Bild/s also 6 s.</li>
 *   <li>Neu öffnen nur bei echtem Bedarf (frische Bilder verlangt, Toter Winkel sichtbar, Aufnahme oder
 *       Wächter-Ereignis schreibt, Stream). Ohne Bedarf bleibt die Kamera offen und es wird nur gemeldet.</li>
 *   <li>Höchstens {@link #MAX_IN_FOLGE} Neustarts in Folge, dann Pause 30 s, 60 s, danach je 120 s. Nach der Pause
 *       sind wieder Neustarts erlaubt (keine endgültige Abschaltung).</li>
 *   <li>Steigt der Bedarf (Toter Winkel erscheint, Aufnahme oder Stream beginnt), ist genau ein Neustart sofort
 *       erlaubt, auch in der Pause.</li>
 *   <li>Laufen {@link #RUHE_MS} lang Bilder ohne Neustart, beginnt alles von vorn.</li>
 *   <li>Uhr rückwärts (gleiche Uhr wie der Aufrufer, monoton erwartet): Zustand zurücksetzen, nie endlos sperren.</li>
 * </ul>
 */
public final class StallNeustartBremse {

    public static final int MAX_IN_FOLGE = 3;
    public static final long[] PAUSE_MS = {30_000L, 60_000L, 120_000L};
    public static final long RUHE_MS = 60_000L;
    public static final long MIN_SCHWELLE_MS = 4_000L;
    public static final int BILDABSTAENDE = 4;
    public static final long PUFFER_MS = 2_000L;

    public enum Urteil { NEUSTART, KEIN_BEDARF, PAUSE }

    private int inFolge;
    private int pausen;
    private long pauseBis = Long.MIN_VALUE;
    private long letzterNeustart = Long.MIN_VALUE;
    private boolean sofortFrei;

    /** Lücken-Schwelle für die Ziel-Bildrate; unbekannte Rate (≤ 0) = bisherige 4 s. */
    public static long schwelleMs(int zielFps) {
        if (zielFps <= 0) return MIN_SCHWELLE_MS;
        long abstand = (1000L + zielFps - 1) / zielFps;
        return Math.max(MIN_SCHWELLE_MS, BILDABSTAENDE * abstand + PUFFER_MS);
    }

    /** Darf der Wächter jetzt neu öffnen? Ändert nichts; gezählt wird erst mit {@link #neustartGemacht}. */
    public synchronized Urteil pruefen(long jetzt, boolean bedarf) {
        uhrPruefen(jetzt);
        if (!bedarf) return Urteil.KEIN_BEDARF;
        if (sofortFrei) return Urteil.NEUSTART;
        if (pauseBis != Long.MIN_VALUE && jetzt < pauseBis) return Urteil.PAUSE;
        return Urteil.NEUSTART;
    }

    /** Ein Stall-Neustart wurde wirklich angestoßen. */
    public synchronized void neustartGemacht(long jetzt) {
        uhrPruefen(jetzt);
        boolean warSofort = sofortFrei;
        sofortFrei = false;
        letzterNeustart = jetzt;
        if (warSofort) return;   // die Sofort-Reparatur zählt nicht gegen die Grenze
        inFolge++;
        if (inFolge >= MAX_IN_FOLGE) {
            pauseBis = jetzt + PAUSE_MS[Math.min(pausen, PAUSE_MS.length - 1)];
            pausen++;
            inFolge = 0;
        }
    }

    /** Bedarf ist gestiegen: ein Neustart sofort erlaubt (nur wirksam, solange die Kamera stillsteht). */
    public synchronized void bedarfGestiegen() {
        sofortFrei = true;
    }

    /** Takt ohne Stillstand: nach {@link #RUHE_MS} ohne Neustart alles zurück; ein offener Sofort-Freibrief verfällt. */
    public synchronized void ruhig(long jetzt) {
        uhrPruefen(jetzt);
        sofortFrei = false;
        if (letzterNeustart == Long.MIN_VALUE) return;
        if (jetzt - letzterNeustart >= RUHE_MS && (pauseBis == Long.MIN_VALUE || jetzt >= pauseBis)) {
            zuruecksetzen();
        }
    }

    public synchronized boolean pausiert(long jetzt) {
        return pauseBis != Long.MIN_VALUE && jetzt < pauseBis;
    }

    public synchronized long pauseBis() {
        return pauseBis;
    }

    synchronized int inFolgeFuerTest() {
        return inFolge;
    }

    private void uhrPruefen(long jetzt) {
        boolean rueckwaerts = letzterNeustart != Long.MIN_VALUE && jetzt < letzterNeustart;
        boolean pauseZuLang = pauseBis != Long.MIN_VALUE
                && pauseBis - jetzt > PAUSE_MS[PAUSE_MS.length - 1];
        if (rueckwaerts || pauseZuLang) zuruecksetzen();
    }

    private void zuruecksetzen() {
        inFolge = 0;
        pausen = 0;
        pauseBis = Long.MIN_VALUE;
        letzterNeustart = Long.MIN_VALUE;
    }
}
