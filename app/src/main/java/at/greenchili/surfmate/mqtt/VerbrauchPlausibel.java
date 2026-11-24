package at.greenchili.surfmate.mqtt;

/**
 * 0.1.43.21 (Befund HA-Session: {@code consumption_50km} = 309,6 kWh/100 km, plausibel 14–20 beim Surf).
 *
 * <p>Der Wert kommt roh aus BYDs {@code getLast50KmPowerConsume}; Panoptes rechnet daran nichts (keine Division, kein
 * Fenster). Die Einheit ist nicht belegt, und Faktor ~20 passt zu keiner sauberen Umrechnung (Wh wäre ×1000, 0,1 wäre ×10).
 * Deshalb wird NICHT umgerechnet: Werte außerhalb von {@link #MIN}–{@link #MAX} kWh/100 km gelten als unbekannt
 * ({@code null} statt falsch), der Rohwert geht ins Protokoll, damit die Einheit an echten Autos belegt werden kann.
 * Die Spanne reicht vom sparsamen Kleinwagen bis zum großen SUV im Winter.
 */
public final class VerbrauchPlausibel {

    private VerbrauchPlausibel() {}

    public static final double MIN = 5.0, MAX = 60.0;

    /** kWh/100 km oder NaN, wenn unbekannt oder unplausibel. */
    public static double kwhJe100km(double roh) {
        if (!Double.isFinite(roh) || roh < MIN || roh > MAX) return Double.NaN;
        return roh;
    }

    /** Protokollzeile für einen verworfenen Rohwert (nur die Zahl). */
    public static String protokoll(double roh) {
        return "VERBRAUCH_50KM_UNPLAUSIBEL roh=" + roh + " grenzen=" + MIN + "-" + MAX;
    }
}
