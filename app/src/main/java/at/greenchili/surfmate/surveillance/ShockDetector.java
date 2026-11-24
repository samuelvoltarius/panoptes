package at.greenchili.surfmate.surveillance;

/**
 * Erschütterung/Berührung am geparkten Auto aus dem Beschleunigungssensor (Wächter-Modus a,
 * Koordination 21.10.2026). Rein, ohne Android – getestet mit am Auto aufgezeichneten Ruhedaten.
 *
 * <p>Verfahren: Die Schwerkraft wird mit einem langsamen Tiefpass ({@link #GRAVITY_TAU_MS})
 * geschätzt; ausgewertet wird nur der dynamische Anteil |a − g|. So stören Hangneigung, langsames
 * Einfedern (Beladung, Wagenheber) oder Temperaturdrift nicht, ein Stoß aber schon.
 * <ul>
 *   <li>Beruhigungszeit nach dem Scharfschalten ({@link #SETTLE_MS}), bis die Schwerkraft sitzt
 *       und das Zuschlagen der eigenen Tür vorbei ist.</li>
 *   <li>   Auslösen, wenn |a − g| ≥ Schwelle in {@link #MIN_HITS} Messungen innerhalb von
 *       {@link #HIT_WINDOW_MS} – ein einzelner Ausreißer reicht nicht.</li>
 *   <li>Ruhegrenze {@link #COOLDOWN_MS} zwischen zwei Auslösungen und höchstens
 *       {@link #MAX_PER_HOUR} pro Stunde (Wind an einem hohen Fahrzeug, Baustelle nebenan).</li>
 *   <li>Lücken im Datenstrom (&gt; {@link #GAP_RESET_MS}) setzen die Schwerkraft-Schätzung neu auf,
 *       statt einen Sprung als Stoß zu werten.</li>
 * </ul>
 * Am Surf gemessen (21.10.2026, icm40607 ~15 Hz, Ruhe): 0,00 / 9,80 / 0,00 m/s², Rauschen ±0,01.
 *
 * <p>0.1.43.20 (FIX_014320_IMU): mit zweitem IMU-Chip ({@link #mitZweitSensor}). Würde ausgelöst, wird erst gefragt,
 * ob der zweite Chip den Ausschlag im Fenster ±{@link #ZWEIT_RAND_MS} mitsah (mindestens
 * {@link at.greenchili.surfmate.recording.ZweiSensoren#BESCHL_ANTEIL} der Spitze). Sah er ihn nicht, obwohl er bewährt
 * ist und den Zeitraum geliefert hat, gilt die Messung als Fehler eines Chips oder als kurze harte Spitze, die nur der
 * ungedämpfte Hauptsensor sieht (z. B. ein harter Schlag auf das Gehäuse selbst): keine Auslösung, keine Ruhegrenze
 * verbraucht. Ein echtes Anstoßen oder Rütteln am Auto schaukelt die Karosserie mit ~1–2 Hz; das sehen beide Chips.
 * Fehlt der zweite Chip, ist er unbewährt oder kommt er zu spät: wie bisher.
 */
public final class ShockDetector {

    public enum Empfindlichkeit {
        NIEDRIG(1.2), MITTEL(0.6), HOCH(0.35);
        public final double schwelleMs2;
        Empfindlichkeit(double s) { this.schwelleMs2 = s; }

        public static Empfindlichkeit von(String s) {
            if ("niedrig".equals(s)) return NIEDRIG;
            if ("hoch".equals(s)) return HOCH;
            if ("mittel".equals(s)) return MITTEL;
            return null;
        }
    }

    static final long GRAVITY_TAU_MS = 2_000L;
    static final long SETTLE_MS = 20_000L;
    static final int MIN_HITS = 2;
    static final long HIT_WINDOW_MS = 1_000L;
    static final long COOLDOWN_MS = 60_000L;
    static final int MAX_PER_HOUR = 12;
    static final long GAP_RESET_MS = 3_000L;
    /** Bestätigungsfenster des zweiten Chips; im Parkmodus kommen beide im selben 1-s-Bündel. */
    static final long ZWEIT_RAND_MS = 300L;
    /** Bis hierhin muss der zweite Chip mindestens geliefert haben, sonst UNBEKANNT (zwei Proben bei 25 Hz). */
    static final long ZWEIT_MINDEST_MS = 100L;

    private at.greenchili.surfmate.recording.ZweiSensoren zweit;
    private int abgewiesen;
    private double abgewiesenHaupt = Double.NaN, abgewiesenZweit = Double.NaN;
    /** Harte Grenze (wie {@code EreignisErkenner.HART_MS2}): ab 4 g verwirft der zweite Chip nie. */
    static final double HART_MS2 = at.greenchili.surfmate.recording.EreignisErkenner.HART_MS2;

    private final double schwelle;
    private double gx, gy, gz;
    private boolean hatSchwerkraft;
    private long letzteMessung;
    private long scharfSeit;
    private final long[] treffer = new long[MIN_HITS];
    private int trefferIdx;
    private long letzteAusloesung = Long.MIN_VALUE / 2;
    private final long[] stunde = new long[MAX_PER_HOUR];
    private int stundeIdx;
    private int stundeAnzahl;
    private double letzteSpitze;

    public ShockDetector(Empfindlichkeit e) {
        this.schwelle = e.schwelleMs2;
        java.util.Arrays.fill(treffer, Long.MIN_VALUE / 2);
    }

    /** Neu scharf geschaltet: alles vergessen, Beruhigungszeit beginnt. */
    public void reset(long nowMs) {
        hatSchwerkraft = false;
        letzteMessung = 0L;
        scharfSeit = nowMs;
        java.util.Arrays.fill(treffer, Long.MIN_VALUE / 2);
        trefferIdx = 0;
    }

    /** 0.1.43.20: zweiten IMU-Chip zur Plausibilität nutzen (null = nur ein Sensor, wie bisher). */
    public ShockDetector mitZweitSensor(at.greenchili.surfmate.recording.ZweiSensoren z) {
        this.zweit = z;
        return this;
    }

    /** 0.1.43.20: so oft hat der zweite Chip eine Auslösung verhindert (fürs Log). */
    public int abgewiesen() { return abgewiesen; }

    /** 0.1.43.20: Werte der letzten Verwerfung fürs Log: [Hauptchip |a − g|, zweiter Chip Spitze] (NaN = keine Probe). */
    public double[] letzteAbweisung() { return new double[] {abgewiesenHaupt, abgewiesenZweit}; }

    /** Spitze |a − g| der letzten Auslösung (für Log/Meldung). */
    public double letzteSpitze() { return letzteSpitze; }

    /**
     * Eine Messung (m/s², Zeit in ms). @return true, wenn eine Erschütterung erkannt wurde.
     */
    public boolean feed(long tMs, double ax, double ay, double az) {
        if (zweit != null) zweit.beschl1(tMs, ax, ay, az);
        if (!hatSchwerkraft || tMs - letzteMessung > GAP_RESET_MS || tMs < letzteMessung) {
            gx = ax; gy = ay; gz = az;
            hatSchwerkraft = true;
            letzteMessung = tMs;
            return false;
        }
        long dt = tMs - letzteMessung;
        letzteMessung = tMs;
        double dx = ax - gx, dy = ay - gy, dz = az - gz;
        double dyn = Math.sqrt(dx * dx + dy * dy + dz * dz);
        // Schwerkraft nur nachführen, wenn es ruhig ist – ein Stoß soll sie nicht verschieben.
        if (dyn < schwelle) {
            double alpha = Math.min(1.0, dt / (double) GRAVITY_TAU_MS);
            gx += alpha * dx;
            gy += alpha * dy;
            gz += alpha * dz;
        }
        if (tMs - scharfSeit < SETTLE_MS) return false;
        if (dyn < schwelle) return false;

        treffer[trefferIdx] = tMs;
        trefferIdx = (trefferIdx + 1) % MIN_HITS;
        long aeltester = Long.MAX_VALUE;
        for (long t : treffer) aeltester = Math.min(aeltester, t);
        if (tMs - aeltester > HIT_WINDOW_MS) return false;

        if (tMs - letzteAusloesung < COOLDOWN_MS) return false;
        // Höchstzahl pro Stunde
        int imFenster = 0;
        for (int i = 0; i < stundeAnzahl; i++) if (tMs - stunde[i] < 3_600_000L) imFenster++;
        if (imFenster >= MAX_PER_HOUR) return false;
        if (zweit != null && dyn < HART_MS2
                && zweit.beschlBestaetigt(tMs - ZWEIT_RAND_MS, tMs + ZWEIT_RAND_MS, tMs + ZWEIT_MINDEST_MS,
                dyn, at.greenchili.surfmate.recording.ZweiSensoren.BESCHL_ANTEIL)
                == at.greenchili.surfmate.recording.ZweiSensoren.Urteil.NEIN) {
            abgewiesen++;
            abgewiesenHaupt = dyn;
            abgewiesenZweit = zweit.beschl2Spitze(tMs - ZWEIT_RAND_MS, tMs + ZWEIT_RAND_MS);
            java.util.Arrays.fill(treffer, Long.MIN_VALUE / 2);
            return false;
        }

        letzteAusloesung = tMs;
        stunde[stundeIdx] = tMs;
        stundeIdx = (stundeIdx + 1) % MAX_PER_HOUR;
        if (stundeAnzahl < MAX_PER_HOUR) stundeAnzahl++;
        letzteSpitze = dyn;
        java.util.Arrays.fill(treffer, Long.MIN_VALUE / 2);
        return true;
    }
}
