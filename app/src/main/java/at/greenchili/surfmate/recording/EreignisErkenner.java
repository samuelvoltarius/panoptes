package at.greenchili.surfmate.recording;

/**
 * Erkennt beim Fahren ein Ereignis, das als Ereignis-Clip gesichert wird (0.1.27,
 * EREIGNISCLIP_0127_PLAN.md). Rein, ohne Android – getestet in {@code EreignisErkennerTest}.
 *
 * <p>Zwei Quellen:
 * <ul>
 *   <li><b>Tempo</b> (BYD-SDK, ganze km/h, Takt ~250 ms): starke Verzögerung ≥ {@link #SCHWELLE_MS2}
 *       über ein Fenster von mindestens {@link #FENSTER_MS}, in {@link #FENSTER_NOETIG} aufeinander
 *       folgenden Auswertungen, ab {@link #MIN_START_KMH}. Ein einzelner Messwert, der nach unten
 *       ausreißt und in der nächsten Probe zurückspringt, zählt nicht (SDK-Aussetzer). Unplausibel
 *       starke Werte (&gt; {@link #PLAUSIBEL_MS2}) zählen nur, wenn das Tempo danach unten bleibt.</li>
 *   <li><b>Beschleunigungssensor</b> im Headunit (falls er Daten liefert): Bestätigung der
 *       Verzögerung (waagrechter dynamischer Anteil ≥ {@link #IMU_BESTAETIGUNG_MS2}) bzw. Einspruch,
 *       wenn er dabei ruhig bleibt; außerdem ein Stoß ohne Tempoabfall (Auffahrunfall im Stand)
 *       ab {@link #STOSS_MS2} waagrecht. Senkrechte Schläge (Schlagloch) zählen nicht.</li>
 * </ul>
 * Danach Ruhezeit {@link #RUHE_MS} und höchstens {@link #MAX_PRO_STUNDE} pro Stunde.
 * Außerhalb einer Fahrstufe wird nichts ausgelöst und der Verlauf verworfen.
 */
public final class EreignisErkenner {

    public enum Grund { VERZOEGERUNG, STOSS }

    // ---------------------------------------------------------------- Tempo
    static final double SCHWELLE_MS2 = 5.0;
    static final long FENSTER_MS = 500L;
    /** Längstes Fenster, das noch ausgewertet wird (lückenhafte Proben). */
    static final long FENSTER_MAX_MS = 1_000L;
    static final int FENSTER_NOETIG = 2;
    static final double MIN_START_KMH = 20.0;
    static final double PLAUSIBEL_MS2 = 15.0;
    /** Ausreißer: so viel tiefer als beide Nachbarn. */
    static final double AUSREISSER_KMH = 5.0;
    /** Rückprall nach einem unplausibel starken Abfall: mehr als das wieder oben = Messfehler. */
    static final double RUECKPRALL_KMH = 5.0;
    /** Längere Lücke im Tempo setzt den Verlauf zurück. */
    static final long LUECKE_MS = 1_500L;

    // ---------------------------------------------------------------- Sensor
    static final double IMU_BESTAETIGUNG_MS2 = 3.0;
    /** Darunter widerspricht der Sensor einem Tempo-Ereignis (er war ruhig). */
    static final double IMU_EINSPRUCH_MS2 = 1.0;
    static final double STOSS_MS2 = 15.0;
    static final double STOSS_EINZELN_MS2 = 25.0;
    static final long STOSS_FENSTER_MS = 150L;
    /**
     * Sensor gilt als lebendig, wenn die letzte Probe so jung ist. Großzügig, weil der Sensor im
     * sparsamen Takt (PARK) bis zu 1 s im eigenen Speicher sammelt, bevor er liefert.
     */
    static final long IMU_LEBT_MS = 2_500L;
    /** So lange wartet eine Tempo-Auslösung höchstens auf die Sensor-Proben ihres Zeitraums. */
    static final long IMU_WARTE_MS = 1_500L;
    /** Schwerkraft-Tiefpass; lang, damit eine Bremsung über ~1 s kaum hineinläuft. */
    static final long SCHWERKRAFT_TAU_MS = 5_000L;
    static final long IMU_LUECKE_MS = 3_000L;
    static final long IMU_EINSCHWING_MS = 3_000L;

    // ---------------------------------------------------------------- Grenzen
    static final long RUHE_MS = 60_000L;
    static final int MAX_PRO_STUNDE = 6;

    // Tempo-Verlauf (nur bestätigte Proben), Ringpuffer.
    private static final int VERLAUF = 16;
    private final long[] vt = new long[VERLAUF];
    private final double[] vv = new double[VERLAUF];
    private int vAnzahl;
    private int vKopf;
    // Neueste Probe wartet eine Runde, bis klar ist, ob sie ein Ausreißer ist.
    private boolean hatWartend;
    private long wartendT;
    private double wartendV;
    private int treffer;
    // Unplausibel starker Abfall: Auslösung erst nach einer Probe ohne Rückprall.
    private boolean pruefeRueckprall;
    private double rueckprallBasis;
    // Tempo-Auslösung, die noch auf die Sensor-Proben ihres Zeitraums wartet.
    private boolean wartet;
    private long warteVon, warteBis;
    private double warteA, warteV;

    // Sensor.
    private double gx, gy, gz;
    private boolean hatSchwerkraft;
    private long imuErsteMs = Long.MIN_VALUE;
    private long imuLetzteMs = Long.MIN_VALUE;
    private static final int IMU_VERLAUF = 64;
    private final long[] it = new long[IMU_VERLAUF];
    private final double[] ih = new double[IMU_VERLAUF];
    private int iKopf;
    private int iAnzahl;
    private long stossErsterMs = Long.MIN_VALUE;

    private boolean fahrbereit;
    private long letzteAusloesung = Long.MIN_VALUE / 2;
    private final long[] stunde = new long[MAX_PRO_STUNDE];
    private int stundeIdx;
    private int stundeAnzahl;

    /**
     * Neue Tempo-Probe. {@code kmh} NaN oder negativ = kein Wert. {@code fahrstufe} = D/R/S/M.
     *
     * @return Grund, wenn jetzt ein Ereignis gesichert werden soll, sonst null.
     */
    public synchronized Grund tempo(long tMs, double kmh, boolean fahrstufe) {
        if (!fahrstufe) {
            fahrbereit = false;
            verlaufLeeren();
            return null;
        }
        fahrbereit = true;
        if (Double.isNaN(kmh) || kmh < 0) {
            if (hatWartend && tMs - wartendT > LUECKE_MS) verlaufLeeren();
            return null;
        }
        if (hatWartend && tMs - wartendT > LUECKE_MS) verlaufLeeren();
        if (hatWartend && tMs <= wartendT) return null;   // Uhr rückwärts / doppelt

        Grund g = null;
        if (wartet && tMs - warteBis > IMU_WARTE_MS) {
            // Sensor liefert nicht nach: nach dem Tempo allein entscheiden.
            wartet = false;
            g = entscheide(warteBis, warteA, warteV, Double.NaN);
        }
        if (hatWartend) {
            boolean ausreisser = vAnzahl > 0
                    && wartendV < letzterV() - AUSREISSER_KMH
                    && wartendV < kmh - AUSREISSER_KMH;
            if (!ausreisser) {
                anhaengen(wartendT, wartendV);
                Grund a = auswerten();
                if (g == null) g = a;
            }
        }
        hatWartend = true;
        wartendT = tMs;
        wartendV = kmh;
        return g;
    }

    private Grund auswerten() {
        long jetztT = letzterT();
        double jetztV = letzterV();

        if (wartet) return null;
        if (pruefeRueckprall) {
            pruefeRueckprall = false;
            if (jetztV <= rueckprallBasis + RUECKPRALL_KMH) {
                return ausloesen(jetztT, Grund.VERZOEGERUNG);
            }
            treffer = 0;
            return null;
        }

        int alt = -1;
        for (int i = vAnzahl - 2; i >= 0; i--) {
            if (jetztT - zeit(i) >= FENSTER_MS) { alt = i; break; }
        }
        if (alt < 0 || jetztT - zeit(alt) > FENSTER_MAX_MS) {
            treffer = 0;
            return null;
        }
        double altV = wert(alt);
        double a = (altV - jetztV) / 3.6 / ((jetztT - zeit(alt)) / 1000.0);
        if (altV < MIN_START_KMH || a < SCHWELLE_MS2) {
            treffer = 0;
            return null;
        }
        treffer++;
        if (treffer < FENSTER_NOETIG) return null;

        if (imuLebt(jetztT)) {
            if (imuLetzteMs < jetztT) {
                // Die Sensor-Proben dieses Zeitraums kommen noch (gebündelt): warten.
                wartet = true;
                warteVon = zeit(alt);
                warteBis = jetztT;
                warteA = a;
                warteV = jetztV;
                return null;
            }
            return entscheide(jetztT, a, jetztV, imuSpitze(zeit(alt), jetztT));
        }
        return entscheide(jetztT, a, jetztV, Double.NaN);
    }

    /** {@code spitze} NaN = kein Sensor; sonst größter waagrechter Anteil im Zeitraum. */
    private Grund entscheide(long tMs, double a, double jetztV, double spitze) {
        if (!Double.isNaN(spitze)) {
            if (spitze < IMU_EINSPRUCH_MS2) { treffer = 0; return null; }   // Sensor ruhig: Tempo-Fehler
            if (spitze < IMU_BESTAETIGUNG_MS2 && a > PLAUSIBEL_MS2) { treffer = 0; return null; }
        }
        if (a > PLAUSIBEL_MS2) {
            pruefeRueckprall = true;
            rueckprallBasis = jetztV;
            return null;
        }
        return ausloesen(tMs, Grund.VERZOEGERUNG);
    }

    /** Neue Probe des Beschleunigungssensors (Gerätekoordinaten, m/s²). */
    public synchronized Grund imu(long tMs, float ax, float ay, float az) {
        if (imuLetzteMs != Long.MIN_VALUE && (tMs - imuLetzteMs > IMU_LUECKE_MS || tMs < imuLetzteMs)) {
            hatSchwerkraft = false;
            iAnzahl = 0;
        }
        if (!hatSchwerkraft) {
            gx = ax; gy = ay; gz = az;
            hatSchwerkraft = true;
            imuErsteMs = tMs;
            imuLetzteMs = tMs;
            return null;
        }
        long dt = Math.max(1L, tMs - imuLetzteMs);
        imuLetzteMs = tMs;
        double k = Math.min(1.0, dt / (double) SCHWERKRAFT_TAU_MS);
        gx += (ax - gx) * k;
        gy += (ay - gy) * k;
        gz += (az - gz) * k;

        double gBetrag = Math.sqrt(gx * gx + gy * gy + gz * gz);
        double dx = ax - gx, dy = ay - gy, dz = az - gz;
        double waagrecht;
        if (gBetrag < 1e-3) {
            waagrecht = Math.sqrt(dx * dx + dy * dy + dz * dz);
        } else {
            double senkrecht = (dx * gx + dy * gy + dz * gz) / gBetrag;
            double quadrat = dx * dx + dy * dy + dz * dz - senkrecht * senkrecht;
            waagrecht = Math.sqrt(Math.max(0.0, quadrat));
        }
        if (tMs - imuErsteMs < IMU_EINSCHWING_MS) return null;
        it[iKopf] = tMs;
        ih[iKopf] = waagrecht;
        iKopf = (iKopf + 1) % IMU_VERLAUF;
        if (iAnzahl < IMU_VERLAUF) iAnzahl++;

        if (!fahrbereit) return null;
        if (wartet && tMs >= warteBis) {
            wartet = false;
            Grund g = entscheide(tMs, warteA, warteV, imuSpitze(warteVon, warteBis));
            if (g != null) return g;
        }
        if (waagrecht >= STOSS_EINZELN_MS2) return ausloesen(tMs, Grund.STOSS);
        if (waagrecht >= STOSS_MS2) {
            if (stossErsterMs != Long.MIN_VALUE && tMs - stossErsterMs <= STOSS_FENSTER_MS) {
                stossErsterMs = Long.MIN_VALUE;
                return ausloesen(tMs, Grund.STOSS);
            }
            stossErsterMs = tMs;
        }
        return null;
    }

    private Grund ausloesen(long tMs, Grund g) {
        treffer = 0;
        pruefeRueckprall = false;
        if (tMs - letzteAusloesung < RUHE_MS) return null;
        if (stundeAnzahl >= MAX_PRO_STUNDE) {
            long aeltester = stunde[stundeIdx];
            if (tMs - aeltester < 3_600_000L) return null;
        }
        letzteAusloesung = tMs;
        stunde[stundeIdx] = tMs;
        stundeIdx = (stundeIdx + 1) % MAX_PRO_STUNDE;
        if (stundeAnzahl < MAX_PRO_STUNDE) stundeAnzahl++;
        return g;
    }

    private boolean imuLebt(long jetzt) {
        return hatSchwerkraft && iAnzahl > 0 && Math.abs(jetzt - imuLetzteMs) <= IMU_LEBT_MS;
    }

    /** Größter waagrechter Anteil im Zeitraum (mit 250 ms Rand für Takt-Versatz). */
    private double imuSpitze(long von, long bis) {
        double m = 0;
        for (int i = 0; i < iAnzahl; i++) {
            long t = it[i];
            if (t >= von - 250 && t <= bis + 250 && ih[i] > m) m = ih[i];
        }
        return m;
    }

    private void verlaufLeeren() {
        vAnzahl = 0;
        vKopf = 0;
        hatWartend = false;
        treffer = 0;
        pruefeRueckprall = false;
        wartet = false;
        stossErsterMs = Long.MIN_VALUE;
    }

    private void anhaengen(long t, double v) {
        vt[vKopf] = t;
        vv[vKopf] = v;
        vKopf = (vKopf + 1) % VERLAUF;
        if (vAnzahl < VERLAUF) vAnzahl++;
    }

    /** i = 0 älteste … vAnzahl-1 neueste. */
    private int index(int i) {
        return (vKopf - vAnzahl + i + VERLAUF) % VERLAUF;
    }

    private long zeit(int i) { return vt[index(i)]; }
    private double wert(int i) { return vv[index(i)]; }
    private long letzterT() { return zeit(vAnzahl - 1); }
    private double letzterV() { return wert(vAnzahl - 1); }
}
