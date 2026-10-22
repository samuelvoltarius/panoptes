package at.greenchili.surfmate.charging;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Akkukapazität (0.1.10, Koordination 22.10.): Am Auto stand {@code vehicle.nominalKwh}=44,1 als NUTZERWERT (beim
 * Umzug aus Overdrive übernommen, nicht gemessen); der Hersteller nennt für Dolphin Surf Comfort/Boost 43,2 kWh.
 * Die Katalogwerte stehen je Variante in {@code web/shared/models/manifest.json} ({@code varianten}), nie im Code.
 *
 * <p>Weicht ein eingetragener Wert um mehr als {@link #TOLERANZ} von JEDER Katalogvariante des gewählten Modells
 * ab, entsteht ein Eintrag „Noch offen“ – nichts wird still korrigiert; der Nutzer übernimmt den Katalogwert selbst.
 */
public final class KapazitaetPlausibel {

    static final double TOLERANZ = 0.01;   // ±1 %

    private KapazitaetPlausibel() {}

    /** Varianten eines Katalog-Modells: [{id, label, nominalKwh, quelle}] (leer, wenn keine). */
    public static JSONArray varianten(JSONObject modell) {
        JSONArray v = modell == null ? null : modell.optJSONArray("varianten");
        return v == null ? new JSONArray() : v;
    }

    /** 0.1.11: Einrast-Toleranz für Auto-Werte (BMS/Heuristik) auf eine Katalogvariante (Koordination 22.10.). */
    public static final double EINRASTEN = 0.05;

    /**
     * 0.1.11: nächste Katalogvariante zu einem vom Auto gelesenen Wert, wenn sie höchstens {@code tol} (relativ zur
     * Variante) entfernt ist; sonst 0. Beispiel Surf: BMS 44,1 → Comfort/Boost 43,2 (2,1 %).
     */
    public static double einrasten(double kwh, JSONArray varianten, double tol) {
        if (!(kwh > 0) || varianten == null) return 0;
        double best = 0, bestAbstand = Double.MAX_VALUE;
        for (int i = 0; i < varianten.length(); i++) {
            JSONObject o = varianten.optJSONObject(i);
            double k = o == null ? 0 : o.optDouble("nominalKwh", 0);
            if (!(k > 0)) continue;
            double a = Math.abs(kwh - k);
            if (a <= k * tol && a < bestAbstand) { best = k; bestAbstand = a; }
        }
        return best;
    }

    /** Passt der Wert (±1 %) zu einer Variante? Ohne Varianten oder ohne Wert: nichts zu prüfen (true). */
    public static boolean passt(double kwh, JSONArray varianten) {
        if (!(kwh > 0) || varianten == null || varianten.length() == 0) return true;
        for (int i = 0; i < varianten.length(); i++) {
            double k = varianten.optJSONObject(i) == null ? 0 : varianten.optJSONObject(i).optDouble("nominalKwh", 0);
            if (k > 0 && Math.abs(kwh - k) <= k * TOLERANZ) return true;
        }
        return false;
    }

    /** Quelle für die API: nutzer | katalog | auto | unbekannt (aus SohEstimator.getNominalSource). */
    public static String quelle(String sohQuelle) {
        if ("user".equals(sohQuelle)) return "nutzer";
        if ("user_model".equals(sohQuelle)) return "katalog";
        if (sohQuelle == null || sohQuelle.isEmpty() || "unset".equals(sohQuelle)) return "unbekannt";
        return "auto";
    }

    /** Eintrag für „Noch offen“ oder null. {@code eingetragen} = vehicle.nominalKwh (nur Nutzerwerte). */
    public static JSONObject offen(double eingetragen, String modellId, JSONObject modell) throws Exception {
        JSONArray v = varianten(modell);
        if (passt(eingetragen, v)) return null;
        return new JSONObject().put("id", "akku_kapazitaet_pruefen").put("eingetragen", eingetragen)
                .put("modell", modellId).put("katalog", v).put("textKey", "einr.hinweis.akku_kapazitaet_pruefen")
                .put("einstellungen", new JSONArray().put("vehicle.nominalKwh"));
    }
}
