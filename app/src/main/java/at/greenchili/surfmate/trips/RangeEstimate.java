package at.greenchili.surfmate.trips;

import org.json.JSONObject;

/**
 * Result of a personalized range prediction.
 * Contains the predicted range with confidence interval bounds,
 * the matched consumption bucket info, and the car's built-in range for comparison.
 */
public class RangeEstimate {

    public double predictedRangeKm;
    public double lowerBoundKm;
    public double upperBoundKm;
    public String bucketKey;
    public int sampleCount;
    public int builtInRangeKm;
    /** 0.1.26.1: Außentemperatur, mit der geschätzt wurde („Heute bei 8 °C“). */
    public int tempC;
    /** false = Außentemperatur unbekannt (geparkt, keine Quelle) – Anzeige ohne „bei X °C“. */
    public boolean tempBekannt = true;
    /** Woher die Temperatur stammt: "auto" | "wetter" | "fahrt" (null = unbekannt). */
    public String tempQuelle;
    /** 0.1.26.1: Topf zu dünn bzw. noch keine 10 Rückprüf-Fenster → Anzeige „lernt noch“. */
    public boolean lerntNoch;
    /** 0.1.26.1: Spanne aus der Rückprüfung (P25–P75 über 100 km) statt aus der Streuung einzelner Fahrten. */
    public boolean bandAusRueckpruefung;
    public int rueckpruefFenster;

    /**
     * Serialize to JSON for API responses.
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("predictedRangeKm", predictedRangeKm);
            json.put("lowerBoundKm", lowerBoundKm);
            json.put("upperBoundKm", upperBoundKm);
            json.put("bucketKey", bucketKey != null ? bucketKey : "");
            json.put("sampleCount", sampleCount);
            json.put("builtInRangeKm", builtInRangeKm);
            json.put("tempC", tempC);
            json.put("tempBekannt", tempBekannt);
            json.put("tempQuelle", tempQuelle != null ? tempQuelle : JSONObject.NULL);
            json.put("lerntNoch", lerntNoch);
            json.put("bandAusRueckpruefung", bandAusRueckpruefung);
            json.put("rueckpruefFenster", rueckpruefFenster);
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }
        return json;
    }
}
