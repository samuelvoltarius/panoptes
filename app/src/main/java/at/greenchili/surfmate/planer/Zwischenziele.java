package at.greenchili.surfmate.planer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Zwischenziele aus „An Auto senden“ (Planer-Vertrag §3, Erweiterung §14: {@code plan.zwischenziele[]}, höchstens 5,
 * je {@code {name, lat, lon, km, aufenthalt_min, ankunft_ca?, abfahrt_ca?}}). Nur Anzeige in der Panoptes-Navi bzw.
 * Routenanzeige als Z1…Z5 zusammen mit den Ladestopps; an die Werksnavi geht weiterhin nur das Ziel.
 * Ohne {@code zwischenziele} bleibt alles wie bisher.
 */
public final class Zwischenziele {

    private Zwischenziele() {}

    public static final int MAX = 5;

    /** Gültige Zwischenziele in Fahrreihenfolge, mit Kennung Z1…; ungültige (Koordinaten) fallen still weg. */
    public static JSONArray lies(JSONObject plan) {
        JSONArray out = new JSONArray();
        JSONArray in = plan == null ? null : plan.optJSONArray("zwischenziele");
        for (int i = 0; in != null && i < in.length() && out.length() < MAX; i++) {
            JSONObject z = in.optJSONObject(i);
            if (z == null) continue;
            double lat = z.optDouble("lat", Double.NaN);
            double lon = z.has("lon") ? z.optDouble("lon", Double.NaN) : z.optDouble("lng", Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180 || (lat == 0 && lon == 0)) continue;
            try {
                JSONObject e = new JSONObject();
                e.put("nr", "Z" + (out.length() + 1));
                e.put("art", "zwischenziel");
                e.put("name", einzeilig(z.optString("name", ""), 80));
                e.put("lat", lat);
                e.put("lng", lon);
                double km = z.optDouble("km", z.optDouble("km_auf_route", Double.NaN));
                if (!Double.isNaN(km) && km >= 0) e.put("km", Math.round(km * 10) / 10.0);
                int halt = z.optInt("aufenthalt_min", -1);
                if (halt >= 0 && halt <= 1440) e.put("aufenthaltMin", halt);
                for (String[] k : new String[][] {{"ankunft_ca", "ankunft"}, {"abfahrt_ca", "abfahrt"}}) {
                    String t = z.optString(k[0], "");
                    if (!t.isEmpty()) e.put(k[1], PlanerRoute.millis(t));
                }
                out.put(e);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /**
     * Alle Wegpunkte in Fahrreihenfolge: Zwischenziele und Ladestopps nach {@code km} sortiert, wenn alle eine
     * Position auf der Route haben; sonst (ehrlich, ohne zu raten) erst die Zwischenziele, dann die Ladestopps.
     */
    public static JSONArray wegpunkte(JSONArray zwischenziele, JSONArray stoppsPlan) {
        List<JSONObject> alle = new ArrayList<>();
        boolean alleMitKm = true;
        for (int i = 0; zwischenziele != null && i < zwischenziele.length(); i++) {
            JSONObject z = zwischenziele.optJSONObject(i);
            if (z == null) continue;
            alleMitKm &= z.has("km");
            alle.add(z);
        }
        int n = 0;
        for (int i = 0; stoppsPlan != null && i < stoppsPlan.length() && n < 20; i++) {
            JSONObject s = stoppsPlan.optJSONObject(i);
            if (s == null) continue;
            n++;
            try {
                JSONObject e = new JSONObject();
                e.put("nr", "L" + n);
                e.put("art", "laden");
                e.put("name", s.optString("name", ""));
                e.put("lat", s.optDouble("lat", 0));
                e.put("lng", s.has("lon") ? s.optDouble("lon", 0) : s.optDouble("lng", 0));
                double km = s.optDouble("km", Double.NaN);
                if (!Double.isNaN(km)) e.put("km", km);
                else alleMitKm = false;
                alle.add(e);
            } catch (Exception ignored) {
            }
        }
        if (alleMitKm) {
            // stabil: bei gleichem km bleibt die Eingangsreihenfolge (Zwischenziel vor Ladestopp)
            alle.sort((a, b) -> Double.compare(a.optDouble("km"), b.optDouble("km")));
        }
        JSONArray out = new JSONArray();
        for (JSONObject e : alle) out.put(e);
        return out;
    }

    private static String einzeilig(String s, int max) {
        String t = s.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
