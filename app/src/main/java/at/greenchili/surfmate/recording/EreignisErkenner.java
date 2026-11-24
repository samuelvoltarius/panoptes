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
 * 0.1.43.20 (FIX_014320_IMU): zweiter IMU-Chip und Kreisel.
 * <ul>
 *   <li><b>Zwei Chips</b> ({@link ZweiSensoren}): Ein Stoß, Schleudern oder Überschlag zählt nur, wenn der zweite
 *       Chip ihn mitsah – sofern er bewährt ist und den Zeitraum geliefert hat. Sonst (fehlt, unbewährt, zu spät)
 *       wie bisher mit einem Sensor; im Zweifel wird gesichert. Ein Tempo-Ereignis wird zusätzlich verworfen,
 *       wenn auch der zweite Chip dabei ruhig blieb.</li>
 *   <li><b>Schleudern</b>: Drehrate um die Hochachse ≥ {@link #SCHLEUDERN_RADS} über {@link #SCHLEUDERN_HALTEN_MS}
 *       ab {@link #SCHLEUDERN_MIN_KMH}. Begründung: bei 30 km/h (8,3 m/s) entspricht 0,9 rad/s einem Kurvenradius
 *       von 9,3 m und 7,5 m/s² Querbeschleunigung – mehr als im normalen Fahren (bis ~4 m/s²) und nahe der
 *       Haftgrenze; schneller wird es nur strenger. Eine Kurve mit dem Radius gibt es bei 30 km/h nicht.</li>
 *   <li><b>Überschlag</b>: Drehung um eine waagrechte Achse, aufsummiert über {@link #UEBERSCHLAG_FENSTER_MS},
 *       ≥ {@link #UEBERSCHLAG_RAD} (40°). Wanken in der Kurve bleibt unter ~6°, Rampe/Bordstein ~10° über Sekunden,
 *       ein Schlagloch wenige Grad, die sich wieder aufheben. Nur, wenn Beschleunigung und Kreisel vom selben Chip
 *       kommen (gleiche Achsen), sonst ist „waagrecht“ nicht bestimmbar.</li>
 * </ul>
 * <b>Harte Grenze</b> (Koordination 24.11.): ab {@link #HART_MS2} (4 g) waagrecht am Hauptchip oder wenn eine waagrechte
 * Achse des Hauptchips im Anschlag ist ({@link #SAETTIGUNG_ANTEIL} des Messbereichs) wird ein Stoß IMMER gesichert,
 * auch wenn der zweite Chip ruhig war: Bei einem echten Unfall kann er sättigen oder ausfallen, und Fahrdaten der
 * Kreisel fehlen noch. Verworfen wird nur darunter, und jede Verwerfung kommt mit den Werten beider Chips ins Log.
 * Begründung 4 g: der bisherige Einzelstoß beginnt bei 25 m/s² (~2,5 g); Schlagloch und Bordstein bleiben in der
 * Waagrechten am Headunit deutlich darunter (senkrechte Schläge zählen ohnehin nicht), ein Anprall, der die
 * Karosserie verformt, liegt darüber. Weil der eingestellte Messbereich der Chips nicht bekannt ist (±2/4/8/16 g
 * möglich, bei ±4 g sättigt er genau dort), zählt der Anschlag zusätzlich.
 * Danach Ruhezeit {@link #RUHE_MS} und höchstens {@link #MAX_PRO_STUNDE} pro Stunde.
 * Außerhalb einer Fahrstufe wird nichts ausgelöst und der Verlauf verworfen.
 */
public final class EreignisErkenner {

    public enum Grund { VERZOEGERUNG, STOSS, SCHLEUDERN, UEBERSCHLAG }

    /**
     * 0.1.36: Was genau ausgelöst hat (für {@code <clip>.ausloeser.json}): Regel, Quelle, auslösende Werte und die
     * Schwellen, gegen die sie geprüft wurden. Nur Zahlen und Kennwörter, kein Ort.
     */
    public static final class Details {
        public final Grund grund;
        /** {@code tempo}, {@code tempo+sensor} oder {@code sensor}. */
        public final String quelle;
        public final String regel;
        public final java.util.Map<String, Double> werte = new java.util.LinkedHashMap<>();
        public final java.util.Map<String, Double> schwellen = new java.util.LinkedHashMap<>();

        Details(Grund grund, String quelle, String regel) {
            this.grund = grund;
            this.quelle = quelle;
            this.regel = regel;
        }

        /** 0.1.43.20: gleiche Details mit anderer Quelle (z. B. {@code sensor+zweit}). */
        Details mitQuelle(String q) {
            Details d = new Details(grund, q, regel);
            d.werte.putAll(werte);
            d.schwellen.putAll(schwellen);
            return d;
        }

        Details wert(String name, double v) {
            if (!Double.isNaN(v) && !Double.isInfinite(v)) werte.put(name, v);
            return this;
        }

        Details schwelle(String name, double v) {
            schwellen.put(name, v);
            return this;
        }
    }

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
     * Unter {@link #MIN_START_KMH} (Rangieren, Garage, Ampel) zählt ein Stoß nur, wenn die Fahrt
     * schon einmal schneller war, und dann nur zwei harte Schläge ≥ {@link #STOSS_EINZELN_MS2}
     * innerhalb {@link #STOSS_FENSTER_MS}. Erster Lauf am Auto (0.1.27, 29.10.2026): Fehlauslösung
     * bei ~6 km/h 24 s nach dem Losfahren aus einem Duplex-Parker (Rampe/Schwelle).
     */
    static final double LANGSAM_KMH = MIN_START_KMH;
    /**
     * Form eines echten Anpralls beim Rangieren: das Auto steht danach (oder stand schon, z. B.
     * Auffahrunfall). Über eine Schwelle oder Rampe rollt es weiter – das zählt nicht.
     */
    static final double STEHT_KMH = 2.0;
    static final long STEHT_BINNEN_MS = 1_500L;
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

    // ---------------------------------------------------------------- Kreisel und zweiter Chip (0.1.43.20)
    static final double SCHLEUDERN_MIN_KMH = 30.0;
    static final double SCHLEUDERN_RADS = 0.9;
    static final long SCHLEUDERN_HALTEN_MS = 300L;
    /** Längere Lücke zwischen zwei Kreisel-Proben unterbricht das Halten. */
    static final long KREISEL_LUECKE_MS = 200L;
    static final double UEBERSCHLAG_RAD = 0.7;
    static final long UEBERSCHLAG_FENSTER_MS = 2_000L;
    /** Bestätigungsfenster des zweiten Chips um einen Stoß (Takt 25 Hz = 40 ms, dazu Versatz). */
    static final long STOSS_ZWEIT_RAND_MS = 150L;
    /** So lange wartet eine Auslösung höchstens auf den zweiten Chip; danach wie mit einem Sensor. */
    static final long ZWEIT_WARTE_MS = 1_500L;
    /** Tempo-Ereignis: unter diesem Ausschlag blieb auch der zweite Chip ruhig (gedämpfter Chip, siehe ZweiSensoren). */
    static final double ZWEIT_EINSPRUCH_MS2 = 0.5;
    /** Harte Grenze: 4 g waagrecht – darüber nie verwerfen. */
    public static final double HART_MS2 = 4 * 9.80665;
    /** Anschlag: so viel des gemeldeten Messbereichs auf einer waagrechten Achse gilt als gesättigt. */
    static final double SAETTIGUNG_ANTEIL = 0.95;

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
    private Details rueckprallDetails;
    // Tempo-Auslösung, die noch auf die Sensor-Proben ihres Zeitraums wartet.
    private boolean wartet;
    private long warteVon, warteBis;
    private double warteA, warteV, warteAltV;
    /** Was die nächste Auslösung begründet; wird bei Erfolg zu {@link #letzteDetails}. */
    private Details kandidat;
    private Details letzteDetails;

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
    private long stossHartErsterMs = Long.MIN_VALUE;
    /** Letztes bekanntes Tempo (NaN = unbekannt, gilt als langsam). */
    private double letzteKmh = Double.NaN;
    /** War diese Fahrt (seit der Fahrstufe) schon einmal ≥ {@link #LANGSAM_KMH}? */
    private boolean warSchnell;
    private double letzteSpitze;
    // Langsamer Stoß, der noch auf „Auto steht danach“ wartet.
    private boolean stossKandidat;
    private long stossKandidatMs;
    private double stossKandidatSpitze;

    private boolean fahrbereit;
    private long letzteAusloesung = Long.MIN_VALUE / 2;
    private final long[] stunde = new long[MAX_PRO_STUNDE];
    private int stundeIdx;
    private int stundeAnzahl;

    // 0.1.43.20: zweiter Chip und Kreisel.
    private final ZweiSensoren zwei;
    private boolean gleicherChip;
    private static final int DREH_VERLAUF = 256;
    private final long[] dtz = new long[DREH_VERLAUF];
    private final double[] dhx = new double[DREH_VERLAUF], dhy = new double[DREH_VERLAUF],
            dhz = new double[DREH_VERLAUF], dbetrag = new double[DREH_VERLAUF];
    private int dKopf, dAnzahl;
    private long dLetzte = Long.MIN_VALUE;
    private long schleudernSeit = Long.MIN_VALUE;
    // Auslösung, die noch auf den zweiten Chip wartet.
    private Grund offenGrund;
    private Details offenDetails;
    private long offenT, offenVon, offenBis;
    private double offenWert;
    private boolean offenDreh;
    private String letzteAbweisung;
    private int abweisungen;
    /** Messbereich des Hauptchips (m/s², NaN = unbekannt). */
    private double messbereich = Double.NaN;

    public EreignisErkenner() {
        this(new ZweiSensoren());
    }

    /** 0.1.43.20: mit gemeinsamer Zwei-Chip-Plausibilität (Gerät: {@link ZweiSensoren#geraet()}). */
    public EreignisErkenner(ZweiSensoren zwei) {
        this.zwei = zwei == null ? new ZweiSensoren() : zwei;
    }

    /** 0.1.43.20: kommen Haupt-Beschleunigung und Haupt-Kreisel vom selben Chip (gleiche Achsen)? */
    public synchronized void gleicherChip(boolean ja) {
        gleicherChip = ja;
    }

    /** 0.1.43.20: was zuletzt wegen des zweiten Chips verworfen wurde, mit Werten beider Chips (null = nichts). */
    public synchronized String letzteAbweisung() {
        return letzteAbweisung;
    }

    /** 0.1.43.20: Anzahl der Verwerfungen wegen des zweiten Chips (jede wird geloggt). */
    public synchronized int abweisungen() {
        return abweisungen;
    }

    /** 0.1.43.20: gemeldeter Messbereich des Hauptchips (m/s²; ≤ 0 oder NaN = unbekannt). */
    public synchronized void messbereich(double r) {
        messbereich = r > 0 ? r : Double.NaN;
    }

    private void abweisen(String regel, double haupt, double zweit, double noetig, String einheit) {
        abweisungen++;
        letzteAbweisung = String.format(java.util.Locale.ROOT,
                "%s: zweiter Chip ruhig (haupt %.2f %s, zweit %s, noetig %.2f %s)", regel, haupt, einheit,
                Double.isNaN(zweit) ? "keine Probe" : String.format(java.util.Locale.ROOT, "%.2f %s", zweit, einheit),
                noetig, einheit);
    }

    /** Ist eine waagrechte Achse des Hauptchips im Anschlag? (Schwerkraft-Achse ausgenommen.) */
    private boolean gesaettigt(float ax, float ay, float az) {
        if (Double.isNaN(messbereich)) return false;
        double g = Math.sqrt(gx * gx + gy * gy + gz * gz);
        if (g < 1e-3) return false;
        double grenze = SAETTIGUNG_ANTEIL * messbereich;
        return (Math.abs(ax) >= grenze && Math.abs(gx) < 0.5 * g)
                || (Math.abs(ay) >= grenze && Math.abs(gy) < 0.5 * g)
                || (Math.abs(az) >= grenze && Math.abs(gz) < 0.5 * g);
    }

    /**
     * Neue Tempo-Probe. {@code kmh} NaN oder negativ = kein Wert. {@code fahrstufe} = D/R/S/M.
     *
     * @return Grund, wenn jetzt ein Ereignis gesichert werden soll, sonst null.
     */
    public synchronized Grund tempo(long tMs, double kmh, boolean fahrstufe) {
        if (!fahrstufe) {
            // 0.1.43.20: wartet eine Auslösung noch auf den zweiten Chip, jetzt entscheiden (im Zweifel sichern).
            Grund offen = offenGrund != null ? offeneEntscheiden(tMs, true) : null;
            fahrbereit = false;
            warSchnell = false;
            letzteKmh = Double.NaN;
            verlaufLeeren();
            return offen;
        }
        fahrbereit = true;
        Grund offen = offeneEntscheiden(tMs);
        if (offen != null) return offen;
        letzteKmh = (Double.isNaN(kmh) || kmh < 0) ? Double.NaN : kmh;
        if (!Double.isNaN(letzteKmh) && letzteKmh >= LANGSAM_KMH) warSchnell = true;
        if (Double.isNaN(kmh) || kmh < 0) {
            if (hatWartend && tMs - wartendT > LUECKE_MS) verlaufLeeren();
            return null;
        }
        if (hatWartend && tMs - wartendT > LUECKE_MS) verlaufLeeren();
        if (hatWartend && tMs <= wartendT) return null;   // Uhr rückwärts / doppelt

        Grund g = null;
        if (stossKandidat) {
            if (tMs - stossKandidatMs > STEHT_BINNEN_MS || tMs < stossKandidatMs) {
                stossKandidat = false;                        // rollte weiter: Schwelle/Rampe
            } else if (kmh <= STEHT_KMH) {
                stossKandidat = false;
                letzteSpitze = stossKandidatSpitze;
                kandidat = stossDetails("stoss_langsam_steht", stossKandidatSpitze, STOSS_EINZELN_MS2)
                        .wert("kmhDanach", kmh).schwelle("stehtKmh", STEHT_KMH);
                g = ausloesen(tMs, Grund.STOSS);
            }
        }
        if (wartet && tMs - warteBis > IMU_WARTE_MS) {
            // Sensor liefert nicht nach: nach dem Tempo allein entscheiden.
            wartet = false;
            g = entscheide(warteBis, warteA, warteAltV, warteV, Double.NaN, warteVon, warteBis);
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
                kandidat = rueckprallDetails == null ? null
                        : rueckprallDetails.wert("kmhDanach", jetztV).schwelle("rueckprallKmh", RUECKPRALL_KMH);
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
                warteAltV = altV;
                return null;
            }
            return entscheide(jetztT, a, altV, jetztV, imuSpitze(zeit(alt), jetztT), zeit(alt), jetztT);
        }
        return entscheide(jetztT, a, altV, jetztV, Double.NaN, zeit(alt), jetztT);
    }

    /** {@code spitze} NaN = kein Sensor; sonst größter waagrechter Anteil im Zeitraum. */
    private Grund entscheide(long tMs, double a, double altV, double jetztV, double spitze, long von, long bis) {
        Details d = new Details(Grund.VERZOEGERUNG, Double.isNaN(spitze) ? "tempo" : "tempo+sensor", "verzoegerung")
                .wert("verzoegerungMs2", a).wert("kmhVorher", altV).wert("kmhNachher", jetztV)
                .wert("sensorSpitzeMs2", spitze)
                .schwelle("verzoegerungMs2", SCHWELLE_MS2).schwelle("minStartKmh", MIN_START_KMH)
                .schwelle("fensterMs", FENSTER_MS).schwelle("fensterNoetig", FENSTER_NOETIG)
                .schwelle("plausibelMs2", PLAUSIBEL_MS2);
        if (!Double.isNaN(spitze)) {
            d.schwelle("sensorEinspruchMs2", IMU_EINSPRUCH_MS2).schwelle("sensorBestaetigungMs2", IMU_BESTAETIGUNG_MS2);
        }
        if (!Double.isNaN(spitze)) {
            if (spitze < IMU_EINSPRUCH_MS2) { treffer = 0; return null; }   // Sensor ruhig: Tempo-Fehler
            if (spitze < IMU_BESTAETIGUNG_MS2 && a > PLAUSIBEL_MS2) { treffer = 0; return null; }
            // 0.1.43.20: auch der zweite Chip muss die Verzögerung sehen, wenn er bewährt ist und den Zeitraum hat.
            // Eine Bremsung ≥ 5 m/s² über ≥ 0,5 s ist langsam genug für den gedämpften Chip (zeigt dann ≥ ~3 m/s²).
            ZweiSensoren.Urteil u = zwei.beschlBestaetigt(von - 250, bis + 250, bis, ZWEIT_EINSPRUCH_MS2, 1.0);
            d.wert("zweitSpitzeMs2", zwei.beschl2Spitze(von - 250, bis + 250));
            if (u == ZweiSensoren.Urteil.NEIN) {
                abweisen("verzoegerung", spitze, zwei.beschl2Spitze(von - 250, bis + 250), ZWEIT_EINSPRUCH_MS2, "m/s²");
                treffer = 0;
                return null;
            }
            if (u == ZweiSensoren.Urteil.JA) d = d.mitQuelle("tempo+sensor+zweit");
        }
        if (a > PLAUSIBEL_MS2) {
            pruefeRueckprall = true;
            rueckprallBasis = jetztV;
            rueckprallDetails = new Details(d.grund, d.quelle, "verzoegerung_ohne_rueckprall");
            rueckprallDetails.werte.putAll(d.werte);
            rueckprallDetails.schwellen.putAll(d.schwellen);
            return null;
        }
        kandidat = d;
        return ausloesen(tMs, Grund.VERZOEGERUNG);
    }

    /** Neue Probe des Beschleunigungssensors (Gerätekoordinaten, m/s²). */
    public synchronized Grund imu(long tMs, float ax, float ay, float az) {
        zwei.beschl1(tMs, ax, ay, az);
        Grund offen = offeneEntscheiden(tMs);
        if (offen != null) return offen;
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
        // Harte Grenze: darüber verwirft der zweite Chip nie (auch eine wartende Auslösung nicht).
        boolean hart = waagrecht >= HART_MS2 || gesaettigt(ax, ay, az);
        it[iKopf] = tMs;
        ih[iKopf] = waagrecht;
        iKopf = (iKopf + 1) % IMU_VERLAUF;
        if (iAnzahl < IMU_VERLAUF) iAnzahl++;

        if (!fahrbereit) return null;
        if (wartet && tMs >= warteBis) {
            wartet = false;
            Grund g = entscheide(tMs, warteA, warteAltV, warteV, imuSpitze(warteVon, warteBis), warteVon, warteBis);
            if (g != null) return g;
        }
        if (offenGrund != null) {
            if (!hart) return null;   // eine Auslösung wartet schon auf den zweiten Chip
            offenGrund = null;        // harter Stoß: jetzt ohne Gegenprobe sichern
            offenDetails = null;
        }
        boolean langsam = Double.isNaN(letzteKmh) || letzteKmh < LANGSAM_KMH;
        if (langsam) {
            // Rangieren/Garage: nur nach einer schnelleren Fahrt und nur zwei harte Schläge.
            if (!warSchnell || waagrecht < STOSS_EINZELN_MS2) return null;
            if (stossHartErsterMs != Long.MIN_VALUE && tMs - stossHartErsterMs <= STOSS_FENSTER_MS) {
                stossHartErsterMs = Long.MIN_VALUE;
                if (!Double.isNaN(letzteKmh) && letzteKmh <= STEHT_KMH) {
                    letzteSpitze = waagrecht;
                    return pruefeZweit(Grund.STOSS, stossDetails("stoss_im_stand", waagrecht, STOSS_EINZELN_MS2)
                            .schwelle("stehtKmh", STEHT_KMH), tMs, waagrecht, hart);   // stand schon: Auffahrunfall
                }
                // rollt noch: erst auslösen, wenn das Auto binnen STEHT_BINNEN_MS steht
                if (!hart && zwei.beschlBestaetigt(tMs - STOSS_ZWEIT_RAND_MS, tMs + STOSS_ZWEIT_RAND_MS,
                        tMs + STOSS_ZWEIT_RAND_MS, waagrecht, ZweiSensoren.BESCHL_ANTEIL) == ZweiSensoren.Urteil.NEIN) {
                    abweisen("stoss_langsam", waagrecht,
                            zwei.beschl2Spitze(tMs - STOSS_ZWEIT_RAND_MS, tMs + STOSS_ZWEIT_RAND_MS),
                            ZweiSensoren.BESCHL_ANTEIL * waagrecht, "m/s²");
                    return null;
                }
                stossKandidat = true;
                stossKandidatMs = tMs;
                stossKandidatSpitze = waagrecht;
                return null;
            }
            stossHartErsterMs = tMs;
            return null;
        }
        if (waagrecht >= STOSS_EINZELN_MS2) {
            letzteSpitze = waagrecht;
            return pruefeZweit(Grund.STOSS, stossDetails("stoss_einzeln", waagrecht, STOSS_EINZELN_MS2), tMs, waagrecht,
                    hart);
        }
        if (waagrecht >= STOSS_MS2) {
            if (stossErsterMs != Long.MIN_VALUE && tMs - stossErsterMs <= STOSS_FENSTER_MS) {
                stossErsterMs = Long.MIN_VALUE;
                letzteSpitze = waagrecht;
                return pruefeZweit(Grund.STOSS, stossDetails("stoss_doppelt", waagrecht, STOSS_MS2), tMs, waagrecht,
                        hart);
            }
            stossErsterMs = tMs;
        }
        return null;
    }

    // ---------------------------------------------------------------- 0.1.43.20: zweiter Chip, Kreisel

    /** Probe des zweiten Beschleunigungssensors (eigene Achsen, m/s²). */
    public synchronized Grund imu2(long tMs, float ax, float ay, float az) {
        zwei.beschl2(tMs, ax, ay, az);
        return offeneEntscheiden(tMs);
    }

    /** Probe des zweiten Kreisels (eigene Achsen, rad/s). */
    public synchronized Grund gyro2(long tMs, float gx, float gy, float gz) {
        zwei.dreh2(tMs, gx, gy, gz);
        return offeneEntscheiden(tMs);
    }

    /** Probe des Hauptkreisels (rad/s; Achsen wie die Haupt-Beschleunigung, wenn {@link #gleicherChip}). */
    public synchronized Grund gyro(long tMs, float gxr, float gyr, float gzr) {
        zwei.dreh1(tMs, gxr, gyr, gzr);
        Grund offen = offeneEntscheiden(tMs);
        if (offen != null) return offen;
        if (dLetzte != Long.MIN_VALUE && tMs <= dLetzte) return null;   // doppelt / Uhr rückwärts
        if (dLetzte != Long.MIN_VALUE && tMs - dLetzte > IMU_LUECKE_MS) {
            dAnzahl = 0;
            schleudernSeit = Long.MIN_VALUE;
        }
        long dt = dLetzte == Long.MIN_VALUE ? 0L : tMs - dLetzte;
        dLetzte = tMs;
        double betrag = Math.sqrt((double) gxr * gxr + (double) gyr * gyr + (double) gzr * gzr);
        double gB = Math.sqrt(gx * gx + gy * gy + gz * gz);
        boolean rahmen = gleicherChip && hatSchwerkraft && gB > 1e-3;
        double hoch, hx = 0, hy = 0, hz = 0;
        if (rahmen) {
            double ux = gx / gB, uy = gy / gB, uz = gz / gB;
            double p = gxr * ux + gyr * uy + gzr * uz;
            hoch = Math.abs(p);
            hx = gxr - p * ux;
            hy = gyr - p * uy;
            hz = gzr - p * uz;
        } else {
            hoch = betrag;   // ohne gemeinsame Achsen: Betrag (Schleudern und Überschlag nicht trennbar)
        }
        dtz[dKopf] = tMs;
        dhx[dKopf] = hx;
        dhy[dKopf] = hy;
        dhz[dKopf] = hz;
        dbetrag[dKopf] = betrag;
        dKopf = (dKopf + 1) % DREH_VERLAUF;
        if (dAnzahl < DREH_VERLAUF) dAnzahl++;
        if (!fahrbereit || offenGrund != null) return null;

        if (rahmen) {
            // Überschlag: waagrechte Drehung über das Fenster aufsummieren (Vektor, Hin und Zurück heben sich auf).
            double sx = 0, sy = 0, sz = 0, maxB = 0;
            long spaeter = Long.MIN_VALUE;
            for (int i = 0; i < dAnzahl; i++) {
                int j = (dKopf - 1 - i + DREH_VERLAUF) % DREH_VERLAUF;
                long tt = dtz[j];
                if (tMs - tt > UEBERSCHLAG_FENSTER_MS) break;
                long schritt = spaeter == Long.MIN_VALUE ? Math.min(dt, KREISEL_LUECKE_MS)
                        : Math.min(spaeter - tt, KREISEL_LUECKE_MS);
                double s = schritt / 1000.0;
                sx += dhx[j] * s;
                sy += dhy[j] * s;
                sz += dhz[j] * s;
                if (dbetrag[j] > maxB) maxB = dbetrag[j];
                spaeter = tt;
            }
            double winkel = Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (winkel >= UEBERSCHLAG_RAD) {
                dAnzahl = 0;
                Details d = new Details(Grund.UEBERSCHLAG, "sensor", "ueberschlag")
                        .wert("winkelRad", winkel).wert("drehMaxRads", maxB).wert("kmh", letzteKmh)
                        .schwelle("winkelRad", UEBERSCHLAG_RAD).schwelle("fensterMs", UEBERSCHLAG_FENSTER_MS);
                return pruefeZweitDreh(Grund.UEBERSCHLAG, d, tMs, tMs - UEBERSCHLAG_FENSTER_MS, maxB);
            }
        }

        boolean schnell = !Double.isNaN(letzteKmh) && letzteKmh >= SCHLEUDERN_MIN_KMH;
        if (schnell && hoch >= SCHLEUDERN_RADS) {
            if (schleudernSeit == Long.MIN_VALUE || dt > KREISEL_LUECKE_MS) schleudernSeit = tMs;
            if (tMs - schleudernSeit >= SCHLEUDERN_HALTEN_MS) {
                long seit = schleudernSeit;
                schleudernSeit = Long.MIN_VALUE;
                Details d = new Details(Grund.SCHLEUDERN, "sensor", rahmen ? "schleudern" : "schleudern_betrag")
                        .wert("drehRads", hoch).wert("haltenMs", tMs - seit).wert("kmh", letzteKmh)
                        .schwelle("drehRads", SCHLEUDERN_RADS).schwelle("haltenMs", SCHLEUDERN_HALTEN_MS)
                        .schwelle("minKmh", SCHLEUDERN_MIN_KMH);
                return pruefeZweitDreh(Grund.SCHLEUDERN, d, tMs, seit, SCHLEUDERN_RADS);
            }
        } else {
            schleudernSeit = Long.MIN_VALUE;
        }
        return null;
    }

    /** Stoß: zweiter Chip im Fenster ±{@link #STOSS_ZWEIT_RAND_MS}; über der harten Grenze immer sichern. */
    private Grund pruefeZweit(Grund g, Details d, long tMs, double spitze, boolean hart) {
        if (hart) {
            d.schwelle("hartMs2", HART_MS2).wert("hart", 1);
            kandidat = mitZweit(d, tMs - STOSS_ZWEIT_RAND_MS, tMs + STOSS_ZWEIT_RAND_MS, false);
            return ausloesen(tMs, g);
        }
        return pruefe(g, d, tMs, tMs - STOSS_ZWEIT_RAND_MS, tMs + STOSS_ZWEIT_RAND_MS, spitze, false);
    }

    /** Drehung: zweiter Kreisel im Zeitraum der Drehung. */
    private Grund pruefeZweitDreh(Grund g, Details d, long tMs, long von, double rate) {
        return pruefe(g, d, tMs, von, tMs + STOSS_ZWEIT_RAND_MS, rate, true);
    }

    private Grund pruefe(Grund g, Details d, long tMs, long von, long bis, double wert, boolean dreh) {
        ZweiSensoren.Urteil u = urteil(von, bis, wert, dreh);
        if (u == ZweiSensoren.Urteil.JA) {
            kandidat = mitZweit(d, von, bis, dreh).mitQuelle(d.quelle + "+zweit");
            return ausloesen(tMs, g);
        }
        if (u == ZweiSensoren.Urteil.NEIN) {
            abweisen(d.regel, wert, dreh ? zwei.dreh2Spitze(von, bis) : zwei.beschl2Spitze(von, bis),
                    (dreh ? ZweiSensoren.DREH_ANTEIL : ZweiSensoren.BESCHL_ANTEIL) * wert, dreh ? "rad/s" : "m/s²");
            return null;
        }
        boolean entschieden = dreh ? zwei.drehEntschieden(bis) : zwei.beschlEntschieden(bis);
        if (entschieden) {   // zweiter Chip fehlt / unbewährt: wie bisher mit einem Sensor
            kandidat = d;
            return ausloesen(tMs, g);
        }
        offenGrund = g;
        offenDetails = d;
        offenT = tMs;
        offenVon = von;
        offenBis = bis;
        offenWert = wert;
        offenDreh = dreh;
        return null;
    }

    private ZweiSensoren.Urteil urteil(long von, long bis, double wert, boolean dreh) {
        return dreh ? zwei.drehBestaetigt(von, bis, bis, wert, ZweiSensoren.DREH_ANTEIL)
                : zwei.beschlBestaetigt(von, bis, bis, wert, ZweiSensoren.BESCHL_ANTEIL);
    }

    private Details mitZweit(Details d, long von, long bis, boolean dreh) {
        return d.wert(dreh ? "zweitDrehRads" : "zweitSpitzeMs2",
                dreh ? zwei.dreh2Spitze(von, bis) : zwei.beschl2Spitze(von, bis));
    }

    /** Wartende Auslösung entscheiden, sobald der zweite Chip den Zeitraum hat oder die Frist um ist. */
    private Grund offeneEntscheiden(long jetzt) {
        return offeneEntscheiden(jetzt, false);
    }

    private Grund offeneEntscheiden(long jetzt, boolean erzwingen) {
        if (offenGrund == null) return null;
        ZweiSensoren.Urteil u = urteil(offenVon, offenBis, offenWert, offenDreh);
        boolean frist = erzwingen || jetzt - offenT > ZWEIT_WARTE_MS || jetzt < offenT;
        if (u == ZweiSensoren.Urteil.UNBEKANNT && !frist
                && !(offenDreh ? zwei.drehEntschieden(offenBis) : zwei.beschlEntschieden(offenBis))) {
            return null;   // weiter warten
        }
        Grund g = offenGrund;
        Details d = offenDetails;
        offenGrund = null;
        offenDetails = null;
        if (u == ZweiSensoren.Urteil.NEIN) {
            abweisen(d.regel, offenWert,
                    offenDreh ? zwei.dreh2Spitze(offenVon, offenBis) : zwei.beschl2Spitze(offenVon, offenBis),
                    (offenDreh ? ZweiSensoren.DREH_ANTEIL : ZweiSensoren.BESCHL_ANTEIL) * offenWert,
                    offenDreh ? "rad/s" : "m/s²");
            return null;
        }
        kandidat = u == ZweiSensoren.Urteil.JA
                ? mitZweit(d, offenVon, offenBis, offenDreh).mitQuelle(d.quelle + "+zweit") : d;
        return ausloesen(offenT, g);
    }

    /** Waagrechter Anteil (m/s²) des letzten Stoßes, der ausgelöst hat – fürs Log. */
    public synchronized double letzteSpitze() {
        return letzteSpitze;
    }

    /** Tempo, das der Erkenner zuletzt gesehen hat (NaN = unbekannt) – fürs Log. */
    public synchronized double letzteKmh() {
        return letzteKmh;
    }

    /** 0.1.36: Was zuletzt ausgelöst hat (null = noch nichts). */
    public synchronized Details letzteDetails() {
        return letzteDetails;
    }

    private Details stossDetails(String regel, double spitze, double schwelle) {
        return new Details(Grund.STOSS, "sensor", regel)
                .wert("stossMs2", spitze).wert("kmh", letzteKmh)
                .schwelle("stossMs2", schwelle).schwelle("fensterMs", STOSS_FENSTER_MS)
                .schwelle("langsamKmh", LANGSAM_KMH);
    }

    private Grund ausloesen(long tMs, Grund g) {
        Details d = kandidat;
        kandidat = null;
        treffer = 0;
        pruefeRueckprall = false;
        if (tMs - letzteAusloesung < RUHE_MS) return null;
        if (stundeAnzahl >= MAX_PRO_STUNDE) {
            long aeltester = stunde[stundeIdx];
            if (tMs - aeltester < 3_600_000L) return null;
        }
        letzteDetails = d != null && d.grund == g ? d : new Details(g, "unbekannt", "unbekannt");
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
        stossHartErsterMs = Long.MIN_VALUE;
        stossKandidat = false;
        schleudernSeit = Long.MIN_VALUE;
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
