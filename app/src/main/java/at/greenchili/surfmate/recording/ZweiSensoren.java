package at.greenchili.surfmate.recording;

/**
 * 0.1.43.20 (FIX_014320_IMU): Plausibilität über zwei IMU-Chips. Rein, ohne Android, getestet in
 * {@code ZweiSensorenTest}.
 *
 * <p>Der Surf hat zwei Bausteine in einem Gehäuse (Inventur 20.11.2026, {@code dumpsys sensorservice}): smi230
 * (Hauptsensor, „-iner“) und icm40607. Beide sitzen auf derselben Platine im selben starren Gehäuse. Ein echter Stoß
 * am Auto bewegt darum beide, ein Fehler eines Chips (Bitfehler am Bus, Sprung, eingefrorener Wert) nur einen. Eine
 * echte Drehung des Autos messen beide Kreisel als denselben Drehvektor, nur in ihren eigenen Achsen; der Betrag
 * |ω| ist darum unabhängig vom Einbau vergleichbar.
 *
 * <p><b>Bewährung:</b> Der zweite Chip redet erst mit, wenn er sich bewährt hat, also nachweislich echte Bewegungen
 * des Hauptsensors mitsieht. Gezählt wird je Sekunde: Bewegt sich der Hauptsensor deutlich ({@link #BEWEGT_MS2}
 * bzw. {@link #BEWEGT_RADS}), muss der zweite mindestens {@link #MIT_ANTEIL} davon zeigen. Grund: Am Seal war ein
 * Sensor eine Attrappe mit eingefrorenen Werten, und der icm40607 zeigt in Ruhe nur drei verschiedene Werte (z exakt
 * 0,00, gefiltert). Ein Chip, der nicht mitgeht, darf nie ein echtes Ereignis verhindern.
 *
 * <p><b>Urteil</b> ({@link Urteil}): JA = der zweite Chip sah den Ausschlag mit, NEIN = er war bewährt, hat den
 * Zeitraum gesehen und blieb ruhig, UNBEKANNT = nicht bewährt, nicht vorhanden oder Zeitraum noch nicht geliefert.
 * Bei UNBEKANNT gilt immer das bisherige Verhalten (ein Sensor).
 *
 * <p><b>Zahlen</b> (Ruhedaten am Surf 20.11.2026, 50 Proben je Chip): Rauschen smi230 σ = 0,011 m/s², icm40607
 * σ = 0,004 m/s² (Auflösung 0,01). {@link #BEWEGT_MS2} = 0,5 m/s² liegt das ~45-Fache über dem smi230-Rauschen und
 * wird beim Fahren auf normaler Straße ständig erreicht, in Ruhe nie. {@link #BESCHL_ANTEIL} = 0,4: der icm40607
 * ist sichtbar tiefpassgefiltert (nur ~15 Hz bei BYDs eigener Abfrage, 3 Werte in Ruhe), kurze Spitzen kommen bei
 * ihm gedämpft an; 0,4 lässt dafür Spielraum und trennt trotzdem klar von „gar nichts“ (Fehler eines Chips &lt; 0,05).
 * {@link #DREH_ANTEIL} = 0,6: gleicher Drehvektor, Abweichung nur durch Takt (25 Hz) und Filter.
 */
public final class ZweiSensoren {

    public enum Urteil { JA, NEIN, UNBEKANNT }

    // ---------------------------------------------------------------- Schwellen
    /** Hauptsensor „bewegt sich“ in dieser Sekunde (dynamischer Anteil |a − g|). */
    static final double BEWEGT_MS2 = 0.5;
    /** Hauptkreisel „dreht“ in dieser Sekunde (|ω|, rad/s; 0,1 rad/s ≈ 6 °/s, Kurve oder Schwelle). */
    static final double BEWEGT_RADS = 0.1;
    /** Mitgehen bei der Bewährung: mindestens dieser Anteil des Hauptsensors. */
    static final double MIT_ANTEIL = 0.2;
    /** Bewährt ab so vielen Sekunden mit Mitgehen … */
    static final int BEWAEHRT_JA = 5;
    /** … und höchstens einem Viertel davon ohne Mitgehen. */
    static final int BEWAEHRT_VERHAELTNIS = 4;
    /** Bestätigung eines Stoßes: der zweite Chip zeigt mindestens diesen Anteil der Spitze. */
    public static final double BESCHL_ANTEIL = 0.4;
    /** Bestätigung einer Drehung: |ω₂| mindestens dieser Anteil von |ω₁|. */
    public static final double DREH_ANTEIL = 0.6;
    /** Zweiter Chip gilt als lebendig, wenn seine letzte Probe so jung ist (Bündelung im Parkmodus bis 1 s). */
    static final long LEBT_MS = 2_500L;
    /** Schwerkraft-Tiefpass je Chip (wie im Wächter, {@code ShockDetector}). */
    static final long SCHWERKRAFT_TAU_MS = 2_000L;
    static final long LUECKE_MS = 3_000L;
    /** Schwerkraft muss plausibel sein (Erdbeschleunigung 9,81 m/s²), sonst ist der Chip nicht brauchbar. */
    static final double G_MIN = 8.3, G_MAX = 11.3;

    private static final int RING = 256;

    /** Ein Kanal: Zeit + Betrag (Beschleunigung: |a − g|, Kreisel: |ω|). */
    static final class Kanal {
        final boolean beschleunigung;
        final long[] t = new long[RING];
        final double[] w = new double[RING];
        int kopf, anzahl;
        long letzte = Long.MIN_VALUE;
        double gx, gy, gz;
        boolean hatSchwerkraft;

        Kanal(boolean beschleunigung) { this.beschleunigung = beschleunigung; }

        /** @return Betrag dieser Probe oder NaN (übernommen, aber noch nicht auswertbar / verworfen). */
        double feed(long tMs, double x, double y, double z) {
            if (letzte != Long.MIN_VALUE && tMs <= letzte) return Double.NaN;   // doppelt / Uhr rückwärts
            if (letzte != Long.MIN_VALUE && tMs - letzte > LUECKE_MS) { anzahl = 0; hatSchwerkraft = false; }
            long dt = letzte == Long.MIN_VALUE ? 0 : tMs - letzte;
            letzte = tMs;
            double v;
            if (beschleunigung) {
                if (!hatSchwerkraft) {
                    gx = x; gy = y; gz = z;
                    hatSchwerkraft = true;
                    return Double.NaN;
                }
                double dx = x - gx, dy = y - gy, dz = z - gz;
                v = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double k = Math.min(1.0, dt / (double) SCHWERKRAFT_TAU_MS);
                gx += dx * k; gy += dy * k; gz += dz * k;
            } else {
                v = Math.sqrt(x * x + y * y + z * z);
            }
            t[kopf] = tMs;
            w[kopf] = v;
            kopf = (kopf + 1) % RING;
            if (anzahl < RING) anzahl++;
            return v;
        }

        boolean schwerkraftPlausibel() {
            if (!beschleunigung) return true;
            double g = Math.sqrt(gx * gx + gy * gy + gz * gz);
            return hatSchwerkraft && g >= G_MIN && g <= G_MAX;
        }

        /** Größter Betrag in [von, bis]; NaN ohne Probe darin. */
        double spitze(long von, long bis) {
            double m = Double.NaN;
            for (int i = 0; i < anzahl; i++) {
                int j = (kopf - 1 - i + RING) % RING;
                long tt = t[j];
                if (tt < von) break;
                if (tt <= bis && (Double.isNaN(m) || w[j] > m)) m = w[j];
            }
            return m;
        }
    }

    /** Bewährung eines Kanalpaars, je Sekunde (Takt des Hauptsensors). */
    static final class Bewaehrung {
        final double bewegt;
        int ja, nein;
        long sekunde = Long.MIN_VALUE;
        double spitzeHaupt, spitzeZweit = Double.NaN;

        Bewaehrung(double bewegt) { this.bewegt = bewegt; }

        void haupt(long tMs, double v, Kanal zweit) {
            long s = Math.floorDiv(tMs, 1000L);
            if (s != sekunde) {
                abschliessen(zweit);
                sekunde = s;
                spitzeHaupt = 0;
            }
            if (v > spitzeHaupt) spitzeHaupt = v;
        }

        private void abschliessen(Kanal zweit) {
            if (sekunde == Long.MIN_VALUE || spitzeHaupt < bewegt) return;
            double z = zweit.spitze(sekunde * 1000L - 250L, sekunde * 1000L + 1250L);   // Takt-Versatz
            if (!Double.isNaN(z) && z >= MIT_ANTEIL * spitzeHaupt) ja++;
            else nein++;
            if (ja + nein >= 64) { ja /= 2; nein /= 2; }   // altes Verhalten verblasst, ein später toter Chip fällt raus
        }

        boolean bewaehrt() {
            return ja >= BEWAEHRT_JA && ja >= BEWAEHRT_VERHAELTNIS * nein;
        }
    }

    final Kanal a1 = new Kanal(true), a2 = new Kanal(true), g1 = new Kanal(false), g2 = new Kanal(false);
    final Bewaehrung bewA = new Bewaehrung(BEWEGT_MS2), bewG = new Bewaehrung(BEWEGT_RADS);

    private static final ZweiSensoren GERAET = new ZweiSensoren();

    /** Gemeinsame Instanz fürs Gerät (Fahrt und Stand lernen dieselbe Bewährung). */
    public static ZweiSensoren geraet() { return GERAET; }

    // ---------------------------------------------------------------- Proben

    public synchronized void beschl1(long tMs, double x, double y, double z) {
        double v = a1.feed(tMs, x, y, z);
        if (!Double.isNaN(v)) bewA.haupt(tMs, v, a2);
    }

    public synchronized void beschl2(long tMs, double x, double y, double z) { a2.feed(tMs, x, y, z); }

    public synchronized void dreh1(long tMs, double x, double y, double z) {
        double v = g1.feed(tMs, x, y, z);
        if (!Double.isNaN(v)) bewG.haupt(tMs, v, g2);
    }

    public synchronized void dreh2(long tMs, double x, double y, double z) { g2.feed(tMs, x, y, z); }

    // ---------------------------------------------------------------- Urteile

    public synchronized boolean beschlBewaehrt() { return bewA.bewaehrt(); }

    public synchronized boolean drehBewaehrt() { return bewG.bewaehrt(); }

    /**
     * Sah der zweite Beschleunigungssensor einen Ausschlag ≥ {@code anteil × spitze} in [von, bis]? NEIN nur, wenn
     * er bewährt ist, lebt, plausibel misst und bis mindestens {@code mindestBis} geliefert hat.
     */
    public synchronized Urteil beschlBestaetigt(long von, long bis, long mindestBis, double spitze, double anteil) {
        return urteil(a2, bewA, von, bis, mindestBis, spitze, anteil);
    }

    /** Wie {@link #beschlBestaetigt}, für |ω| der Kreisel. */
    public synchronized Urteil drehBestaetigt(long von, long bis, long mindestBis, double rate, double anteil) {
        return urteil(g2, bewG, von, bis, mindestBis, rate, anteil);
    }

    /** Hat der zweite Chip den Zeitraum bis {@code bis} schon geliefert (oder ist er ohnehin nicht nutzbar)? */
    public synchronized boolean beschlEntschieden(long bis) { return !nutzbar(a2, bewA, bis) || a2.letzte >= bis; }

    public synchronized boolean drehEntschieden(long bis) { return !nutzbar(g2, bewG, bis) || g2.letzte >= bis; }

    private static boolean nutzbar(Kanal k, Bewaehrung b, long bezug) {
        return b.bewaehrt() && k.letzte != Long.MIN_VALUE && Math.abs(bezug - k.letzte) <= LEBT_MS
                && k.schwerkraftPlausibel();
    }

    private static Urteil urteil(Kanal k, Bewaehrung b, long von, long bis, long mindestBis, double spitze,
                                 double anteil) {
        if (!nutzbar(k, b, bis)) return Urteil.UNBEKANNT;
        double z = k.spitze(von, bis);
        if (!Double.isNaN(z) && z >= anteil * spitze) return Urteil.JA;
        if (k.letzte < mindestBis) return Urteil.UNBEKANNT;   // Zeitraum noch nicht ganz da
        return Urteil.NEIN;
    }

    /** Fürs Log/den Auslöser: größter Ausschlag des zweiten Beschleunigungssensors in [von, bis] (NaN = keiner). */
    public synchronized double beschl2Spitze(long von, long bis) { return a2.spitze(von, bis); }

    public synchronized double dreh2Spitze(long von, long bis) { return g2.spitze(von, bis); }
}
