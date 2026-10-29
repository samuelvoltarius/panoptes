package at.greenchili.surfmate.byd;

import at.greenchili.surfmate.monitor.AccMonitor;
import at.greenchili.surfmate.monitor.GearMonitor;

/**
 * 0.1.28.1 (Befund Deploy (2) am Auto, 0.1.27): Updater meldete „fährt“, obwohl das Auto geparkt war (power 0, P,
 * 0 km/h laut HA).
 *
 * <p>Ursache: {@link BydVehicleData#speedKmh} ist der <b>letzte</b> Wert im Schnappschuss, ohne Zeitstempel. Bei
 * ACC aus leert der Collector nur auf DiLink 5 die Fahrwerte ({@code clearStaleDiLink5DrivingFields}); auf DiLink 3
 * bleibt der Wert der Fahrt stehen, weil die SDK-Abfrage im Stand nichts Gültiges mehr liefert ({@code NaN} wird nicht
 * geschrieben). So konnte ein Restwert &gt; 0,5 km/h stundenlang „fährt“ bedeuten.
 *
 * <p>Regel für Entscheidungen „steht / fährt“: Ist ACC seit mindestens {@link #ALT_MS} <b>autoritativ aus</b> und der
 * Gang P, gilt das Tempo als 0 – ein BYD fährt bei ausgeschalteter Zündung nicht. In jedem anderen Fall bleibt der
 * Wert, wie er ist: ein frisches Tempo &gt; 0,5 bei ACC an sperrt weiter (Sicherheit unverändert).
 */
public final class Stillstand {

    /** So lange muss ACC schon aus sein, bevor ein alter Tempowert nicht mehr zählt. */
    public static final long ALT_MS = 10_000L;

    private Stillstand() {}

    /**
     * Rein: Tempo für die Frage „fährt?“.
     *
     * @param kmh          Wert aus dem Schnappschuss (darf alt sein, NaN = unbekannt)
     * @param accAusSeitMs wie lange ACC autoritativ aus ist, −1 = an oder unbekannt
     * @param gangP        Gang ist P (live oder zuletzt gelesen)
     */
    public static double tempo(double kmh, long accAusSeitMs, boolean gangP) {
        if (accAusSeitMs >= ALT_MS && gangP) return 0.0;
        return kmh;
    }

    /** Laufzeit: Tempo aus {@code d} nach der Regel oben (AccMonitor, GearMonitor bzw. letzter Gang). */
    public static double tempoJetzt(BydVehicleData d) {
        if (d == null) return Double.NaN;
        return tempo(d.speedKmh, accAusSeitMs(), gangP(d));
    }

    static long accAusSeitMs() {
        try {
            return AccMonitor.accAusSeitMs();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** P live (GearMonitor aktiv) – sonst der zuletzt gelesene Gang im Schnappschuss. Live „nicht P“ gewinnt. */
    static boolean gangP(BydVehicleData d) {
        try {
            GearMonitor gm = GearMonitor.getInstance();
            if (gm.isActive()) return gm.getCurrentGear() == GearMonitor.GEAR_P;
        } catch (Throwable ignored) { }
        return d != null && d.gearMode == GearMonitor.GEAR_P;
    }
}
