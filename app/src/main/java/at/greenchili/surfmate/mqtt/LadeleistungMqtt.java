package at.greenchili.surfmate.mqtt;

/**
 * 0.1.16: Ladeleistung für MQTT/HA. {@code charge_power} bleibt der gemessene Wert (nie eine Schätzung – HA zeichnet
 * ihn als Messung). Beim DC-Laden am Surf liefern die Getter oft nichts; dann gibt es nur die Schätzung aus der
 * SoC-Steigung ({@code ringEstimator}). Bisher stand dann {@code charge_power = 0}, als lüde das Auto nicht.
 * Jetzt zusätzlich {@code charge_power_estimated} plus {@code charge_power_quality} (measured | estimated | none)
 * und {@code charge_power_source} (Resolver-Quelle).
 */
public final class LadeleistungMqtt {

    public static final String GEMESSEN = "measured";
    public static final String GESCHAETZT = "estimated";
    public static final String KEINE = "none";

    public final double gemessenKw;
    public final double geschaetztKw;
    public final String qualitaet;
    public final String quelle;

    private LadeleistungMqtt(double gemessen, double geschaetzt, String qualitaet, String quelle) {
        this.gemessenKw = gemessen;
        this.geschaetztKw = geschaetzt;
        this.qualitaet = qualitaet;
        this.quelle = quelle;
    }

    /** Bereich des SDK (500 kW, nicht 300: 350-kW-DC ist echt); unter 0,1 kW zählt nicht als Laden. */
    static boolean plausibel(double kw) {
        return Double.isFinite(kw) && kw > 0.1 && kw <= 500;
    }

    public static LadeleistungMqtt aus(boolean laedt, boolean v2l, boolean geschaetzt, double kw, String quelle) {
        if (!laedt || v2l || !plausibel(kw)) return new LadeleistungMqtt(0, 0, KEINE, KEINE);
        String q = quelle == null || quelle.isEmpty() ? KEINE : quelle;
        if (geschaetzt) return new LadeleistungMqtt(0, kw, GESCHAETZT, q);
        return new LadeleistungMqtt(kw, 0, GEMESSEN, q);
    }
}
