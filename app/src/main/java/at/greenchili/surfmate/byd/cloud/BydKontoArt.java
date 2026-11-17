package at.greenchili.surfmate.byd.cloud;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kontoart je Fahrzeug (Besitzer oder Gast/geteiltes Konto) für die Lese-Abfragen der BYD-Cloud.
 *
 * <p>Seit ca. 12.11.2026 verlangt die BYD-Cloud bei geteilten Konten (Gast) im äußeren Umschlag
 * {@code userType:"1"}; das Feld muss mitsigniert werden (sonst 1007). Fehlt es bei einem Gast,
 * antwortet die Cloud mit 1001; ein Besitzer, der sich als Gast ausgibt, bekommt 8003.
 * Verhalten nachgebaut nach der Beschreibung in pyBYD PR #81 (MIT), kein übernommener Code.
 *
 * <p>Erste Annahme aus der Fahrzeugliste (empowerType 3 = Besitzer, empowerType -1 mit
 * empowerId = Gast). Ohne Annahme gilt Besitzer. Die Antwort der Cloud berichtigt die Annahme:
 * bei 1001 (ohne userType) einmal mit, bei 8003 (mit userType) einmal ohne wiederholen; das
 * Ergebnis wird je VIN gemerkt. Nur für Lese-Abfragen, nie für Fernbefehle.
 */
final class BydKontoArt {

    static final String USER_TYPE_GAST = "1";
    static final String CODE_GAST_OHNE_KENNUNG = "1001";
    static final String CODE_BESITZER_ALS_GAST = "8003";

    /** Sendet eine Lese-Abfrage mit (gast=true) oder ohne userType und gibt die Antwort zurück. */
    interface Sender {
        JSONObject sende(boolean gast) throws IOException;
    }

    private final ConcurrentHashMap<String, Boolean> gastJeVin = new ConcurrentHashMap<>();
    /** VIN|Endpunkt, bei denen beide Varianten abgelehnt wurden: kein weiterer Wechsel in dieser Sitzung. */
    private final Set<String> ohneWechsel =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /**
     * Annahme aus einem Eintrag der Fahrzeugliste: TRUE = Gast, FALSE = Besitzer, null = unklar.
     */
    static Boolean gastAusFahrzeug(JSONObject fahrzeug) {
        if (fahrzeug == null) return null;
        String typ = fahrzeug.optString("empowerType", "").trim();
        if ("3".equals(typ)) return Boolean.FALSE;
        Object id = fahrzeug.opt("empowerId");
        boolean hatId = id != null && id != JSONObject.NULL
                && !String.valueOf(id).trim().isEmpty()
                && !"0".equals(String.valueOf(id).trim())
                && !"-1".equals(String.valueOf(id).trim());
        if ("-1".equals(typ) && hatId) return Boolean.TRUE;
        if (fahrzeug.optBoolean("isShared", false)) return Boolean.TRUE;
        return null;
    }

    /** True, wenn {@code code} heißt: die gesendete Kontoart war falsch, einmal mit der anderen wiederholen. */
    static boolean falscheKontoArt(boolean gesendetAlsGast, String code) {
        return gesendetAlsGast ? CODE_BESITZER_ALS_GAST.equals(code) : CODE_GAST_OHNE_KENNUNG.equals(code);
    }

    /** Erste Annahme aus der Fahrzeugliste; überschreibt nie, was die Cloud schon bestätigt hat. */
    void annahme(String vin, Boolean gast) {
        if (vin == null || vin.isEmpty() || gast == null) return;
        gastJeVin.putIfAbsent(vin, gast);
    }

    boolean istGast(String vin) {
        Boolean g = vin == null ? null : gastJeVin.get(vin);
        return g != null && g;
    }

    /** Gemerkter Wert oder null (noch nichts bekannt). */
    Boolean gemerkt(String vin) {
        return vin == null ? null : gastJeVin.get(vin);
    }

    private void merke(String vin, boolean gast) {
        if (vin != null && !vin.isEmpty()) gastJeVin.put(vin, gast);
    }

    /**
     * Sendet mit der gemerkten Kontoart; bei passender Ablehnung genau einmal mit der anderen.
     * Gibt die Antwort zurück, die der Aufrufer auswertet (Code wie bisher).
     */
    JSONObject mitRueckfall(String vin, String endpunkt, Sender sender) throws IOException {
        boolean gast = istGast(vin);
        JSONObject erste = sender.sende(gast);
        String code = erste == null ? "" : erste.optString("code", "");
        if ("0".equals(code)) {
            merke(vin, gast);
            return erste;
        }
        String schluessel = vin + "|" + endpunkt;
        if (!falscheKontoArt(gast, code) || ohneWechsel.contains(schluessel)) return erste;

        JSONObject zweite = sender.sende(!gast);
        String code2 = zweite == null ? "" : zweite.optString("code", "");
        if (falscheKontoArt(!gast, code2)) {
            // Beide Varianten abgelehnt: nicht weiter hin und her schalten, erste Antwort melden.
            ohneWechsel.add(schluessel);
            return erste;
        }
        // Erfolg oder ein anderer Fehler: die Kontoart-Prüfung ist bestanden, also stimmt der Wechsel.
        merke(vin, !gast);
        return zweite;
    }
}
