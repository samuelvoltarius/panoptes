package at.greenchili.surfmate.wallbox;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Nachgebautes Home Assistant: /api/states/&lt;id&gt; und /api/services/&lt;domain&gt;/&lt;service&gt;. */
final class HaAttrappe implements WallboxHttp {

    static final String TOKEN = "ha-test-token";
    final Map<String, JSONObject> states = new HashMap<>();
    final List<String> dienste = new ArrayList<>();
    /** So viele Abfragen bleibt eine Änderung unsichtbar (träge Wallbox-Integration). */
    int verzoegerung;
    private final Map<String, String> ausstehend = new HashMap<>();
    private final Map<String, Integer> zaehler = new HashMap<>();

    HaAttrappe() throws Exception {
        setze("binary_sensor.wb_laedt", "off", new JSONObject());
        setze("sensor.wb_leistung", "0.0", new JSONObject().put("unit_of_measurement", "kW"));
        setze("select.wb_modus", "Off", new JSONObject().put("options", new JSONArray().put("Off").put("Fast").put("Solar")));
        setze("number.wb_ziel", "80", new JSONObject().put("min", 10).put("max", 100));
        setze("time.wb_start", "22:00:00", new JSONObject());
    }

    void setze(String id, String state, JSONObject attr) throws Exception {
        states.put(id, new JSONObject().put("entity_id", id).put("state", state).put("attributes", attr));
    }

    @Override
    public Antwort anfrage(String methode, String url, Map<String, String> kopf, String rumpf) {
        try {
            return beantworte(methode, url, kopf, rumpf);
        } catch (Exception e) {
            return new Antwort(500, String.valueOf(e));
        }
    }

    private Antwort beantworte(String methode, String url, Map<String, String> kopf, String rumpf) throws Exception {
        if (!("Bearer " + TOKEN).equals(kopf.get("Authorization"))) return new Antwort(401, "401: Unauthorized");
        String pfad = url.substring(url.indexOf("/api/"));
        if (methode.equals("GET") && pfad.equals("/api/states")) {
            JSONArray alle = new JSONArray();
            for (JSONObject st : states.values()) alle.put(st);
            return new Antwort(200, alle.toString());
        }
        if (methode.equals("GET") && pfad.startsWith("/api/states/")) {
            String id = pfad.substring("/api/states/".length());
            if (ausstehend.containsKey(id)) {
                int z = zaehler.merge(id, 1, Integer::sum);
                if (z > verzoegerung) {
                    states.get(id).put("state", ausstehend.remove(id));
                    zaehler.remove(id);
                }
            }
            JSONObject s = states.get(id);
            return s == null ? new Antwort(404, "{\"message\":\"Entity not found.\"}") : new Antwort(200, s.toString());
        }
        if (methode.equals("POST") && pfad.startsWith("/api/services/")) {
            dienste.add(pfad + " " + rumpf);
            JSONObject d = new JSONObject(rumpf);
            String id = d.getString("entity_id");
            String dienst = pfad.substring(pfad.lastIndexOf('/') + 1);
            String neu;
            switch (dienst) {
                case "select_option": neu = d.getString("option"); break;
                case "set_value": neu = d.has("value") ? String.valueOf(d.get("value")) : d.getString("time"); break;
                case "set_datetime": neu = d.getString("time"); break;
                case "turn_on": neu = "on"; break;
                case "turn_off": neu = "off"; break;
                default: return new Antwort(400, "");
            }
            if (verzoegerung > 0) ausstehend.put(id, neu);
            else states.get(id).put("state", neu);
            return new Antwort(200, "[]");
        }
        return new Antwort(404, "");
    }

    static JSONObject zuordnung() throws Exception {
        return new JSONObject().put("schluessel", "garage").put("titel", "Garage")
                .put("laedt", "binary_sensor.wb_laedt").put("leistung", "sensor.wb_leistung")
                .put("modus", "select.wb_modus").put("ziel", "number.wb_ziel").put("startzeit", "time.wb_start")
                .put("modusOptionen", new JSONObject().put("aus", "Off").put("sofort", "Fast").put("pv", "Solar"));
    }
}
