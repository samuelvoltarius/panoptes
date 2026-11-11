package at.greenchili.surfmate.server;

import at.greenchili.surfmate.byd.BydVehicleData;

/**
 * 0.1.41 (LK): Reifenwarnung je Rad für die Launcher-Kachel „Reifen“, nur gelesen aus den vorhandenen
 * TPMS-Feldern von {@link BydVehicleData} (BYDAutoTyreDevice). Ein unbekannter Zustand ergibt null, nie eine
 * erfundene Warnung. Reihenfolge nach Dringlichkeit: schnelles Leck, langsames Leck, Unterdruck, Überdruck, Signal.
 */
public final class ReifenWarnung {
    public static final String LECK_SCHNELL = "leck_schnell";
    public static final String LECK_LANGSAM = "leck_langsam";
    public static final String UNTERDRUCK = "unterdruck";
    public static final String UEBERDRUCK = "ueberdruck";
    public static final String SIGNAL = "signal";

    private ReifenWarnung() {}

    /** Warnung eines Rades aus Druckzustand, Leckzustand und Signalzustand (je BYD-Rohwert); null = keine/unbekannt. */
    public static String fuer(int druck, int leck, int signal) {
        if (leck == 2) return LECK_SCHNELL;
        if (leck == 1) return LECK_LANGSAM;
        if (BydVehicleData.isTyreUnderpressureState(druck)) return UNTERDRUCK;
        if (BydVehicleData.isTyreOverpressureState(druck)) return UEBERDRUCK;
        if (signal == 1) return SIGNAL;
        return null;
    }

    /** Rad i (0..3 = FL, FR, RL, RR) aus den Feldern; fehlende Arrays zählen als unbekannt. */
    public static String rad(int[] druck, int[] leck, int[] signal, int i) {
        return fuer(wert(druck, i), wert(leck, i), wert(signal, i));
    }

    private static int wert(int[] a, int i) {
        return (a != null && i >= 0 && i < a.length) ? a[i] : BydVehicleData.UNAVAILABLE;
    }
}
