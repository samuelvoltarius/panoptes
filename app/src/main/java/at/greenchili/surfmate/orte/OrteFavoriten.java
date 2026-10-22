package at.greenchili.surfmate.orte;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 0.1.12 (Kern 7, ORTE_VERTRAG §4.4): Navi-Favoriten (App-Speicher {@code navmap_saved_places}: home | work | custom)
 * in der Ortsliste. Reine Logik; die App schickt ihre Liste über {@code POST /api/orte/favoriten}.
 *
 * <p>Favorit in Normalform: {@code {art: home|work|custom, label, lat, lon}}; Schlüssel {@code home}, {@code work},
 * {@code custom:<label klein, getrimmt>} (wie die App: home/work einmalig, custom je Label).
 *
 * <p>Abgleich mit Version: Die App meldet ihre Liste und die Version des zuletzt übernommenen Solls. Basis für
 * „was hat die App selbst geändert?“ ist das bestätigte Soll (gleiche Version) bzw. ihre letzte gemeldete Liste – so
 * überschreibt eine alte App-Liste nie eine Änderung aus dem Web. Die App schreibt nur, wenn das Soll über die
 * Toleranz hinaus abweicht (Migration: alte Leser sehen exakt dasselbe).
 */
public final class OrteFavoriten {

    private OrteFavoriten() {}

    /** Kreis für neue Orte aus Favoriten (Favoriten haben keinen Radius). */
    public static final int FAVORIT_R = 50;

    public static String schluessel(JSONObject f) {
        String art = f.optString("art", "custom");
        if ("home".equals(art) || "work".equals(art)) return art;
        return "custom:" + f.optString("label", "").trim().toLowerCase(Locale.ROOT);
    }

    static Map<String, JSONObject> nachSchluessel(JSONArray a) {
        Map<String, JSONObject> m = new LinkedHashMap<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject f = a.optJSONObject(i);
            if (f == null || f.optString("label", "").trim().isEmpty()) continue;
            if (Double.isNaN(f.optDouble("lat", Double.NaN)) || Double.isNaN(f.optDouble("lon", Double.NaN))) continue;
            m.putIfAbsent(schluessel(f), f);
        }
        return m;
    }

    static boolean nahPunkt(JSONObject a, double lat, double lon) {
        return Orte.meter(a.optDouble("lat"), a.optDouble("lon"), lat, lon) <= Orte.TOLERANZ_M;
    }

    /** Übernimmt App-Änderungen ({@code vorher} → {@code jetzt}) in die Ortsliste. Löscht nie einen Ort. */
    public static int aufnehmen(JSONArray liste, JSONArray vorher, JSONArray jetzt, long zeit) throws Exception {
        int n = 0;
        Map<String, JSONObject> v = nachSchluessel(vorher), j = nachSchluessel(jetzt);
        for (String k : v.keySet()) {
            if (j.containsKey(k)) continue;
            JSONObject o = ortMitFavorit(liste, k);
            if (o != null) {
                Orte.verweise(o).remove("favorit");
                Orte.verweise(o).remove("favoritLabel");
                Orte.eig(o).put("favorit", false);
                o.put("geaendert", zeit);
                n++;
            }
        }
        for (Map.Entry<String, JSONObject> e : j.entrySet()) {
            JSONObject f = e.getValue();
            double lat = f.optDouble("lat"), lon = f.optDouble("lon");
            JSONObject alt = v.get(e.getKey());
            JSONObject o = ortMitFavorit(liste, e.getKey());
            if (o == null) {
                o = nahOhneFavorit(liste, lat, lon);
                if (o == null) {
                    o = Orte.neuerOrt(f.optString("label").trim(), lat, lon, FAVORIT_R, zeit);
                    liste.put(o);
                }
                Orte.verweise(o).put("favorit", e.getKey());
                // Favoriten-Name merken, wenn der Ort anders heißt (z. B. „Daheim“ am Ort „Zuhause“)
                if (!o.optString("name").equals(f.optString("label").trim())) Orte.verweise(o).put("favoritLabel", f.optString("label").trim());
                Orte.eig(o).put("favorit", true);   // home → zuhause NICHT automatisch (Vertrag §4.4)
                Orte.herkunft(o, "favorit:" + e.getKey());
                o.put("geaendert", zeit);
                n++;
                continue;
            }
            if (alt != null && nahPunkt(alt, lat, lon) && alt.optString("label").equals(f.optString("label"))) continue;
            if (nahPunkt(o, lat, lon)) {
                if (!Orte.hatAndere(o, "favorit")) {
                    o.put("name", f.optString("label").trim());
                    Orte.verweise(o).remove("favoritLabel");
                } else if (!o.optString("name").equals(f.optString("label").trim())) {
                    Orte.verweise(o).put("favoritLabel", f.optString("label").trim());
                }
                o.put("geaendert", zeit);
                n++;
            } else if (!Orte.hatAndere(o, "favorit")) {
                o.put("lat", lat).put("lon", lon).put("name", f.optString("label").trim()).put("geaendert", zeit);
                Orte.verweise(o).remove("favoritLabel");
                n++;
            } else {
                // Favorit woandershin verschoben, der Ort hat weitere Eigenschaften: abtrennen
                Orte.verweise(o).remove("favorit");
                Orte.verweise(o).remove("favoritLabel");
                Orte.eig(o).put("favorit", false);
                o.put("geaendert", zeit);
                JSONObject neu = Orte.neuerOrt(f.optString("label").trim(), lat, lon, FAVORIT_R, zeit);
                Orte.verweise(neu).put("favorit", e.getKey());
                Orte.eig(neu).put("favorit", true);
                Orte.herkunft(neu, "favorit:" + e.getKey());
                liste.put(neu);
                n++;
            }
        }
        return n;
    }

    /**
     * Soll-Liste für die App aus der Ortsliste. Orte, die im Web zum Favoriten wurden, bekommen hier ihren Verweis
     * ({@code custom:<name>}) – der Aufrufer speichert die Liste danach.
     */
    public static JSONArray soll(JSONArray liste) throws Exception {
        JSONArray a = new JSONArray();
        java.util.Set<String> gesehen = new java.util.HashSet<>();
        for (int i = 0; i < liste.length(); i++) {
            JSONObject o = liste.getJSONObject(i);
            if (!Orte.eig(o).optBoolean("favorit")) continue;
            String k = Orte.verweise(o).optString("favorit", "");
            if (k.isEmpty()) {
                k = "custom:" + o.optString("name").trim().toLowerCase(Locale.ROOT);
                if (ortMitFavorit(liste, k) == null) Orte.verweise(o).put("favorit", k);
            }
            String art = "home".equals(k) || "work".equals(k) ? k : "custom";
            String label = Orte.verweise(o).optString("favoritLabel", "");
            JSONObject f = new JSONObject().put("art", art).put("label", label.isEmpty() ? o.optString("name") : label)
                    .put("lat", o.optDouble("lat")).put("lon", o.optDouble("lon"));
            if (gesehen.add(schluessel(f))) a.put(f);
        }
        return a;
    }

    /** Gleiche Favoriten (Schlüssel) mit Positionen innerhalb der Toleranz? */
    public static boolean passend(JSONArray soll, JSONArray app) {
        Map<String, JSONObject> s = nachSchluessel(soll), a = nachSchluessel(app);
        if (!s.keySet().equals(a.keySet())) return false;
        for (Map.Entry<String, JSONObject> e : s.entrySet()) {
            JSONObject x = a.get(e.getKey());
            if (!nahPunkt(x, e.getValue().optDouble("lat"), e.getValue().optDouble("lon"))) return false;
            if (!x.optString("label").equals(e.getValue().optString("label"))) return false;
        }
        return true;
    }

    /** Exakter Vergleich zweier Favoritenlisten (Reihenfolge egal). */
    public static boolean gleich(JSONArray a, JSONArray b) {
        Map<String, JSONObject> x = nachSchluessel(a), y = nachSchluessel(b);
        if (!x.keySet().equals(y.keySet())) return false;
        for (Map.Entry<String, JSONObject> e : x.entrySet()) {
            JSONObject p = y.get(e.getKey()), q = e.getValue();
            if (p.optDouble("lat") != q.optDouble("lat") || p.optDouble("lon") != q.optDouble("lon")
                    || !p.optString("label").equals(q.optString("label"))) return false;
        }
        return true;
    }

    static JSONObject ortMitFavorit(JSONArray liste, String k) {
        for (int i = 0; i < liste.length(); i++) {
            JSONObject o = liste.optJSONObject(i);
            JSONObject v = o == null ? null : o.optJSONObject("verweise");
            if (v != null && k.equals(v.optString("favorit", null))) return o;
        }
        return null;
    }

    static JSONObject nahOhneFavorit(JSONArray liste, double lat, double lon) throws Exception {
        for (int i = 0; i < liste.length(); i++) {
            JSONObject o = liste.optJSONObject(i);
            if (o == null || Orte.verweise(o).has("favorit") || Orte.eig(o).optBoolean("favorit")) continue;
            if (nahPunkt(o, lat, lon)) return o;
        }
        return null;
    }
}
