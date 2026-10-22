package at.greenchili.surfmate.charging;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Plausibilität der Strompreise (0.1.10, Koordination 22.10., TRIPKOSTEN_BEFUND): {@code tripAnalytics.electricityRate}
 * (Fahrten, AC zu Hause) und {@code chargingAnalytics.dcRate}. Am Auto stand 0,01 €/kWh aus dem Overdrive-Altbestand – jede
 * Fahrt kostete damit ~1/30 des Echten. <b>Nichts wird still korrigiert:</b> außerhalb des Bands entsteht ein Eintrag
 * „Noch offen“ und in der Fahrtenliste ein Hinweis „Preis prüfen“; der Nutzer entscheidet.
 *
 * <p>Band je Währung (Preis je kWh). Unbekannte Währung oder Preis 0 (nicht gesetzt) → keine Meldung.
 */
public final class PreisPlausibel {

    private PreisPlausibel() {}

    /** {min, max} je kWh; Euro-Band laut Koordination 0,05–2,00, andere als grober Gegenwert. */
    static final Map<String, double[]> BAND = new HashMap<>();
    static {
        double[] eur = {0.05, 2.00};
        for (String c : new String[] {"€", "EUR", "CHF", "$", "USD", "£", "GBP"}) BAND.put(c, eur);
        BAND.put("NOK", new double[] {0.5, 25});
        BAND.put("SEK", new double[] {0.5, 25});
        BAND.put("DKK", new double[] {0.4, 15});
        BAND.put("PLN", new double[] {0.2, 9});
        BAND.put("CZK", new double[] {1.2, 50});
        BAND.put("HUF", new double[] {20, 800});
    }

    public static final String ZU_NIEDRIG = "zu_niedrig", ZU_HOCH = "zu_hoch", NULL_UNBESTAETIGT = "null_unbestaetigt";

    /** Gibt es für diese Währung ein Band (also ist eine Prüfung möglich)? Für die Overdrive-Übernahme (Kern 5). */
    public static boolean pruefbar(String waehrung) {
        String k = waehrung == null ? "" : waehrung.trim();
        return BAND.containsKey(k) || BAND.containsKey(k.toUpperCase(Locale.ROOT));
    }

    /** null = plausibel bzw. nicht prüfbar; sonst {@link #ZU_NIEDRIG} oder {@link #ZU_HOCH}. 0 wird hier nicht bewertet. */
    public static String pruefe(double rate, String waehrung) {
        if (!(rate > 0) || Double.isInfinite(rate)) return null;
        return band(rate, waehrung);
    }

    /**
     * Mit Gratis-Regel (Koordination 22.10.: Projektinhaber lädt zu Hause gratis): 0 ist gültig, wenn ausdrücklich bestätigt
     * ({@code rateGratis=true}); eine gespeicherte 0 ohne Bestätigung (z. B. Import) → {@link #NULL_UNBESTAETIGT}.
     */
    public static String pruefe(double rate, String waehrung, boolean gratisBestaetigt) {
        if (rate == 0) return gratisBestaetigt ? null : NULL_UNBESTAETIGT;
        return pruefe(rate, waehrung);
    }

    private static String band(double rate, String waehrung) {
        String k = waehrung == null ? "" : waehrung.trim();
        double[] b = BAND.get(k);
        if (b == null) b = BAND.get(k.toUpperCase(Locale.ROOT));
        if (b == null) return null;
        if (rate < b[0]) return ZU_NIEDRIG;
        if (rate > b[1]) return ZU_HOCH;
        return null;
    }

    /**
     * Rein: Einträge für „Noch offen“ aus der Config ({@code tripAnalytics.electricityRate/currency},
     * {@code chargingAnalytics.dcRate}).
     */
    public static JSONArray offen(JSONObject root) throws Exception {
        JSONArray r = new JSONArray();
        if (root == null) return r;
        JSONObject trip = root.optJSONObject("tripAnalytics");
        JSONObject ch = root.optJSONObject("chargingAnalytics");   // ChargingConfig.SECTION
        String cur = trip != null ? trip.optString("currency", "") : "";
        if (cur.isEmpty() && ch != null) cur = ch.optString("currency", "");
        // Nicht gesetzt (Schlüssel fehlt) = nichts melden; der Assistent fragt (Schritt strompreis).
        JSONObject acQuelle = trip != null && trip.has("electricityRate") ? trip
                : ch != null && ch.has("electricityRate") ? ch : null;
        if (acQuelle != null) {
            eintrag(r, "ac", acQuelle.optDouble("electricityRate", 0), cur, "tripAnalytics.electricityRate",
                    trip != null && trip.optBoolean("rateGratis", false));
        }
        if (ch != null && ch.has("dcRate")) {
            eintrag(r, "dc", ch.optDouble("dcRate", 0), cur, "chargingAnalytics.dcRate",
                    ch.optBoolean("dcRateGratis", false));
        }
        return r;
    }

    private static void eintrag(JSONArray r, String art, double wert, String cur, String schluessel,
                                boolean gratis) throws Exception {
        String g = pruefe(wert, cur, gratis);
        if (g == null) return;
        r.put(new JSONObject().put("id", "strompreis_pruefen").put("art", art).put("wert", wert)
                .put("waehrung", cur).put("grund", g)
                .put("textKey", "einr.hinweis.strompreis_pruefen")
                .put("einstellungen", new JSONArray().put(schluessel)));
    }
}
