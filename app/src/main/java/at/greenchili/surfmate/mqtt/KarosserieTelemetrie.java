package at.greenchili.surfmate.mqtt;

import org.json.JSONObject;

/**
 * 0.1.15 (Kern 8, HA_BYD_DATEN_BEFUND.md): Verriegelung, Türen, Fenster, Klappen und „angesteckt“ als flache
 * MQTT-Schlüssel, damit HA (Discovery) sie als binary_sensor bekommt – bisher kannte HA sie nur aus der
 * BYD-Cloud-Brücke. Quelle sind dieselben zusammengeführten Werte wie {@code GET /api/vehicle/state} (Cockpit).
 *
 * <p>Regeln: nur lesend; jeder Schlüssel erscheint nur, wenn der Wert bekannt ist (unbekannt = weglassen, so
 * entsteht keine tote Entität). Werte 1/0 wie alle Binärfelder; HA-Bedeutung von „an“: lock = entriegelt,
 * door/window = offen, plug = angesteckt. Die Gesamt-Verriegelung nur, wenn sie das ganze Auto beschreibt
 * (scope „vehicle“) – eine Fahrertür-Lesung allein erscheint nur als {@code unlocked_<tür>}.
 */
public final class KarosserieTelemetrie {

    private KarosserieTelemetrie() {}

    static final String[] TUEREN = {"lf", "rf", "lr", "rr"};

    /**
     * @param doors      Verriegelung im API-Schema (1 = verriegelt, 2 = entriegelt, sonst unbekannt; overall, scope)
     * @param doorOpen   offen je lf/rf/lr/rr/hood/trunk (Boolean, nur bekannte)
     * @param windowOpen offen je lf/rf/lr/rr (Boolean, nur bekannte)
     * @param gun        BYD-Ladestecker-Zustand (0/1 = nicht angesteckt, 2..5 = angesteckt; sonst unbekannt)
     */
    public static void eintragen(JSONObject payload, JSONObject doors, JSONObject doorOpen, JSONObject windowOpen,
                                 int gun) throws org.json.JSONException {
        if (doors != null) {
            if ("vehicle".equals(doors.optString("scope", ""))) {
                int ov = sperre(doors.optInt("overall", -1));
                if (ov >= 0) payload.put("unlocked", ov);
            }
            for (String t : TUEREN) {
                int v = sperre(doors.optInt(t, -1));
                if (v >= 0) payload.put("unlocked_" + t, v);
            }
        }
        if (doorOpen != null) {
            for (String t : TUEREN) offen(payload, "door_open_" + t, doorOpen, t);
            offen(payload, "trunk_open", doorOpen, "trunk");
            offen(payload, "hood_open", doorOpen, "hood");
        }
        if (windowOpen != null) {
            for (String t : TUEREN) offen(payload, "window_open_" + t, windowOpen, t);
        }
        if (gun >= 0 && gun <= 5) payload.put("plugged", gun >= 2 ? 1 : 0);
    }

    /** API-Verriegelung → 1 = entriegelt, 0 = verriegelt, -1 = unbekannt. */
    static int sperre(int api) {
        if (api == 2) return 1;
        if (api == 1) return 0;
        return -1;
    }

    private static void offen(JSONObject payload, String schluessel, JSONObject quelle, String feld)
            throws org.json.JSONException {
        Object v = quelle.opt(feld);
        if (v instanceof Boolean) payload.put(schluessel, ((Boolean) v) ? 1 : 0);
    }
}
