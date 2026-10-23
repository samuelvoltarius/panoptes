package at.greenchili.surfmate.camping;

/**
 * Energie-Budget und automatisches Strecken (CAMPING_PLAN.md 2.1), gerechnet in Prozent des Akkus.
 *
 * <p>Bedarf bis zur Weckzeit = Morgen-Reserve + Stunden × (Grundlast + Taktanteil × Heizlast).
 * Reicht das Verfügbare (SoC − Grenze) nicht, wird gestreckt: erst die Dauer bis auf die Mindestdauer
 * kürzen, dann das Intervall in 5-min-Schritten bis zum Höchstintervall verlängern; die Stufe bleibt.
 */
public final class EnergieBudget {

    private EnergieBudget() {}

    public static final class Eingaben {
        /** SoC − Grenze in %; ≤ 0 = kein Budget. */
        public double verfuegbarProzent = Double.NaN;
        /** Grundlast in %/h (gemessen, sonst aus Kennfeld und Kapazität); NaN = unbekannt. */
        public double grundProzentProStunde = Double.NaN;
        /** Zusätzliche Last während des Heizens in %/h; NaN = unbekannt. */
        public double heizProzentProStunde = Double.NaN;
        public int intervallMin, dauerMin, mindestDauerMin = 10, hoechstIntervallMin = 240;
        /** Minuten bis zur Weckzeit, oder &lt; 0 ohne Weckzeit. */
        public double minutenBisWeck = -1;
        /** Reserve für das Morgenheizen in %, 0 wenn aus. */
        public double morgenReserveProzent;
    }

    public static final class Plan {
        public final int intervallMin, dauerMin;
        public final boolean gestreckt;
        /** Minuten ab jetzt, bis die Grenze erreicht ist; unendlich ohne Verbrauch, NaN unbekannt. */
        public final double reichtMinuten;
        /** true/false wenn eine Weckzeit gesetzt und das Budget bekannt ist, sonst null. */
        public final Boolean reichtBisWeck;
        public final boolean bekannt;
        Plan(int iv, int du, boolean gestreckt, double reichtMinuten, Boolean reichtBisWeck, boolean bekannt) {
            this.intervallMin = iv; this.dauerMin = du; this.gestreckt = gestreckt;
            this.reichtMinuten = reichtMinuten; this.reichtBisWeck = reichtBisWeck; this.bekannt = bekannt;
        }
    }

    /** Rate in %/h für Dauer/Intervall. */
    static double rate(Eingaben e, int dauer, int intervall) {
        double takt = intervall <= 0 ? 0 : (double) dauer / intervall;
        return e.grundProzentProStunde + takt * e.heizProzentProStunde;
    }

    public static Plan rechnen(Eingaben e) {
        boolean bekannt = !Double.isNaN(e.verfuegbarProzent) && !Double.isNaN(e.grundProzentProStunde)
                && !Double.isNaN(e.heizProzentProStunde);
        if (!bekannt) return new Plan(e.intervallMin, e.dauerMin, false, Double.NaN, null, false);
        if (e.verfuegbarProzent <= 0) return new Plan(e.intervallMin, e.dauerMin, false, 0, e.minutenBisWeck >= 0 ? Boolean.FALSE : null, true);

        int dauer = e.dauerMin, intervall = e.intervallMin;
        boolean gestreckt = false;
        if (e.minutenBisWeck >= 0) {
            double stunden = e.minutenBisWeck / 60.0;
            double verfuegbar = e.verfuegbarProzent - e.morgenReserveProzent;
            int minDauer = Math.min(e.mindestDauerMin, e.dauerMin);
            boolean passt = verfuegbar >= stunden * rate(e, dauer, intervall);
            while (!passt && dauer > minDauer) {
                dauer--;
                gestreckt = true;
                passt = verfuegbar >= stunden * rate(e, dauer, intervall);
            }
            while (!passt && intervall < e.hoechstIntervallMin) {
                intervall = Math.min(e.hoechstIntervallMin, intervall + 5);
                gestreckt = true;
                passt = verfuegbar >= stunden * rate(e, dauer, intervall);
            }
        }
        double r = rate(e, dauer, intervall);
        double reicht = r <= 0 ? Double.POSITIVE_INFINITY : e.verfuegbarProzent / r * 60.0;
        Boolean bisWeck = e.minutenBisWeck >= 0
                ? (e.verfuegbarProzent - e.morgenReserveProzent) >= e.minutenBisWeck / 60.0 * r : null;
        return new Plan(intervall, dauer, gestreckt, reicht, bisWeck, true);
    }
}
