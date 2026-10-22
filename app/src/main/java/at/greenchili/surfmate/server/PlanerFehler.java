package at.greenchili.surfmate.server;

import org.json.JSONObject;

/**
 * Fehlerantworten des Planers für die Oberfläche übersetzen (Koordination 22.10.: „rohe Server-Fehlertexte werden nie
 * angezeigt“). Bekannte {@code code}-Werte bekommen einen eigenen Text, alles andere einen allgemeinen mit dem Code.
 * Erfolgreiche Antworten bleiben unverändert.
 */
final class PlanerFehler {

    private PlanerFehler() {}

    /** Texte in der Anfragesprache (Laufzeit: {@link Messages}). */
    interface Texte {
        String text(String key, String rueckfall, String... args);
    }

    static final Texte LAUFZEIT = (key, rueckfall, args) -> Messages.text(key, rueckfall, (Object[]) args);

    /** Antworttext für die Oberfläche; {@code httpCode} 200 = unverändert. */
    static String fuerNutzer(int httpCode, String text, Texte t) {
        if (httpCode == 200 || text == null) return text;
        JSONObject roh;
        try {
            roh = new JSONObject(text);
        } catch (Exception e) {
            return text;   // eigene Fehlertexte (kein JSON vom Planer) wurden schon vorher gebaut
        }
        if (!roh.has("fehler") && !roh.has("code")) return text;
        try {
            String code = roh.optString("code", "");
            JSONObject o = new JSONObject();
            o.put("ok", false);
            if (!code.isEmpty()) o.put("code", code);
            if ("fahrzeug_fehlt".equals(code)) {
                o.put("fehler", t.text("planer.navi.fahrzeug_fehlt", "Please choose your vehicle model first."));
                o.put("fahrzeugWaehlen", true);   // Oberfläche: Auswahl aus /api/laden → fahrzeug.kandidaten/alle
            } else if (httpCode == 429) {
                o.put("fehler", t.text("planer.navi.zu_viele", "Too many requests – please wait 2 minutes."));
                if (roh.has("warte_s")) o.put("warte_s", roh.optInt("warte_s"));
            } else {
                o.put("fehler", code.isEmpty()
                        ? t.text("planer.navi.planer_fehler_allgemein", "The route planner could not plan this route.")
                        : t.text("planer.navi.planer_fehler", "The route planner could not plan this route ({0}).", code));
            }
            return o.toString();
        } catch (Exception e) {
            return text;
        }
    }
}
