package at.greenchili.surfmate.ha;

/**
 * Tor beim Heimkommen öffnen (HA-Geräte Stufe 2, 0.1.38.8). Rein, testbar; der Takt kommt aus {@link HaDienst}.
 *
 * <p>Auslösen nur, wenn alles klar ist (sonst nie):
 * <ul>
 *   <li>Heim-Bereich = vom Nutzer gewählte SafeLocation-Zone „Zuhause“, Abstand zum Mittelpunkt ≤ Radius,</li>
 *   <li>Zündung an, Gang D oder R, Tempo bekannt und unter {@link #MAX_KMH},</li>
 *   <li>GPS frisch ({@link #GPS_MAX_ALTER_MS}) und genau ({@link #GPS_MAX_UNGENAU_M}),</li>
 *   <li>Abstand nimmt ab (auf das Haus zu, mindestens {@link #NAEHER_M} seit dem letzten Takt),</li>
 *   <li>höchstens 1× je Ankunft: scharf erst, nachdem das Auto außerhalb Radius + {@link #HYSTERESE_M} gesehen wurde.
 *       Nach einem Neustart ist die Regel deshalb unscharf, bis das Auto einmal weg war.</li>
 * </ul>
 * Fernzugriff, Telegram oder Kopplungen lösen das nie aus – nur dieser Takt im Daemon.
 */
public final class Heimkommen {

    static final double MAX_KMH = 20.0;
    static final long GPS_MAX_ALTER_MS = 10_000L;
    static final double GPS_MAX_UNGENAU_M = 50.0;
    static final double HYSTERESE_M = 100.0;
    static final double NAEHER_M = 2.0;

    /** Messwerte eines Takts. NaN bzw. null = unbekannt. */
    public static final class Lage {
        public final double abstandM, radiusM, kmh, ungenauM;
        public final String gang;
        public final boolean zuendung;
        public final long gpsAlterMs;

        public Lage(double abstandM, double radiusM, String gang, double kmh, boolean zuendung, long gpsAlterMs,
                    double ungenauM) {
            this.abstandM = abstandM;
            this.radiusM = radiusM;
            this.gang = gang;
            this.kmh = kmh;
            this.zuendung = zuendung;
            this.gpsAlterMs = gpsAlterMs;
            this.ungenauM = ungenauM;
        }
    }

    private boolean scharf;
    private double letzterAbstand = Double.NaN;

    /** true = jetzt das Tor öffnen (genau einmal je Ankunft). {@code lage == null} = keine Zone/keine Daten. */
    public synchronized boolean takt(Lage lage) {
        if (lage == null || !gpsKlar(lage)) {
            letzterAbstand = Double.NaN;   // Lücke: „näher“ erst wieder aus zwei guten Werten
            return false;
        }
        double a = lage.abstandM;
        double vorher = letzterAbstand;
        letzterAbstand = a;
        if (a > lage.radiusM + HYSTERESE_M) {
            scharf = true;
            return false;
        }
        if (!scharf || a > lage.radiusM) return false;
        if (!lage.zuendung || !("D".equals(lage.gang) || "R".equals(lage.gang))) return false;
        if (Double.isNaN(lage.kmh) || lage.kmh < 0 || lage.kmh >= MAX_KMH) return false;
        if (Double.isNaN(vorher) || vorher - a < NAEHER_M) return false;
        scharf = false;
        return true;
    }

    /** Neue Automatik (anderes Tor/andere Zone): unscharf, bis das Auto einmal außerhalb war. */
    public synchronized void zuruecksetzen() {
        scharf = false;
        letzterAbstand = Double.NaN;
    }

    public synchronized boolean scharf() {
        return scharf;
    }

    private static boolean gpsKlar(Lage l) {
        return !Double.isNaN(l.abstandM) && l.abstandM >= 0 && !Double.isNaN(l.radiusM) && l.radiusM > 0
                && l.gpsAlterMs >= -GPS_MAX_ALTER_MS && l.gpsAlterMs <= GPS_MAX_ALTER_MS   // Uhr der Quelle darf leicht abweichen
                && !Double.isNaN(l.ungenauM) && l.ungenauM >= 0 && l.ungenauM <= GPS_MAX_UNGENAU_M;
    }
}
