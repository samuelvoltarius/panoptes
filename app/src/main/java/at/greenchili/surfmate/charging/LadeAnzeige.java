package at.greenchili.surfmate.charging;

import org.json.JSONObject;

/**
 * 0.1.11 (Kern 7, Koordination 22.10.): Ladungen, deren Energie aus dem SoC geschätzt wurde ({@code soc_estimate}),
 * wurden mit dem damaligen Akku-Nennwert gerechnet (bei Projektinhaber 44,1 statt 43,2). Die ANZEIGE rechnet sie mit dem
 * heutigen Nennwert nach: {@code energyAddedNachgerechnet} + Kennzeichen. Gespeicherte kWh, Preis, Kosten,
 * preis_quelle bleiben unverändert; die angezeigte Rate bleibt die gespeicherte (keine Kosten ÷ neue kWh).
 */
public final class LadeAnzeige {

    private LadeAnzeige() {}

    /** Unterschied, ab dem nachgerechnet angezeigt wird (kWh). */
    static final double SCHWELLE_KWH = 0.05;

    /**
     * Ergänzt eine abgeschlossene Sitzung (JSON wie /api/charging) um die Nachrechnung. Ohne SoC, ohne Nennwert,
     * offene Sitzung oder andere Energiequelle: nichts.
     */
    public static void nachrechnen(JSONObject o, double nennwertKwh, double sohProzent) {
        try {
            if (o == null || o.optBoolean("inProgress", false)) return;
            if (!SessionEnergyResolver.SRC_SOC.equals(o.optString("energySource", ""))) return;
            if (o.isNull("startSoc") || o.isNull("endSoc") || o.isNull("energyAdded")) return;
            double delta = o.optDouble("endSoc", Double.NaN) - o.optDouble("startSoc", Double.NaN);
            double neu = SessionEnergyResolver.socEstimateKwh(delta, nennwertKwh, sohProzent);
            if (!Double.isFinite(neu) || neu <= 0) return;
            double alt = o.optDouble("energyAdded", Double.NaN);
            if (!Double.isFinite(alt) || Math.abs(neu - alt) < SCHWELLE_KWH) return;
            o.put("energyAddedNachgerechnet", Math.round(neu * 100) / 100.0);
            o.put("energyNachgerechnet", true);
            o.put("energyNachgerechnetGrund", "akku_nennwert");
            o.put("energyNachgerechnetNennwertKwh", nennwertKwh);
        } catch (Exception ignored) {
            // Anzeige-Zusatz: ein Fehler darf die Sitzung nie verschlucken
        }
    }
}
