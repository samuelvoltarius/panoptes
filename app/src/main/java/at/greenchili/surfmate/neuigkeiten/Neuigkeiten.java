package at.greenchili.surfmate.neuigkeiten;

import org.json.JSONObject;

/**
 * H10 „Was ist neu“ + Firmware-Hinweis (NEUBAU2_API_VORSCHLAG.md §2), einmal pro Auto statt pro Browser.
 * Zustand im Config-Abschnitt {@code neuigkeiten}: {@code gesehenVersionCode}, {@code firmwareJetzt},
 * {@code firmwareVorher}, {@code firmwareGeaendertAm}, {@code firmwareGesehen}. Rein, testbar.
 */
public final class Neuigkeiten {

    private Neuigkeiten() {}

    /**
     * Beim Daemon-Start: Firmware gegen den gemerkten Stand. Erster Start: nur merken (vorher = null, nichts melden).
     * @return die zu speichernden Werte, oder null, wenn sich nichts ändert
     */
    public static JSONObject firmwareAbgleich(JSONObject stand, String firmwareJetzt, long nowMs) {
        if (firmwareJetzt == null || firmwareJetzt.trim().isEmpty()) return null;
        String fw = firmwareJetzt.trim();
        String gemerkt = stand == null ? "" : stand.optString("firmwareJetzt", "");
        JSONObject neu = new JSONObject();
        try {
            if (gemerkt.isEmpty()) {
                neu.put("firmwareJetzt", fw);
                neu.put("firmwareGesehen", true);
                return neu;
            }
            if (gemerkt.equals(fw)) return null;
            neu.put("firmwareVorher", gemerkt);
            neu.put("firmwareJetzt", fw);
            neu.put("firmwareGeaendertAm", nowMs);
            neu.put("firmwareGesehen", false);
        } catch (Exception ignored) { }
        return neu;
    }

    /** Antwort für GET /api/neuigkeiten. */
    public static JSONObject antwort(JSONObject stand, int versionCode, String version) {
        JSONObject o = new JSONObject();
        try {
            JSONObject s = stand == null ? new JSONObject() : stand;
            o.put("versionCode", versionCode);
            o.put("version", version);
            o.put("gesehenVersionCode", s.has("gesehenVersionCode") ? s.optInt("gesehenVersionCode") : JSONObject.NULL);
            JSONObject fw = new JSONObject();
            fw.put("jetzt", s.optString("firmwareJetzt", "").isEmpty() ? JSONObject.NULL : s.optString("firmwareJetzt"));
            fw.put("vorher", s.optString("firmwareVorher", "").isEmpty() ? JSONObject.NULL : s.optString("firmwareVorher"));
            fw.put("geaendertAm", s.has("firmwareGeaendertAm") ? s.optLong("firmwareGeaendertAm") : JSONObject.NULL);
            fw.put("gesehen", s.optBoolean("firmwareGesehen", true));
            o.put("firmware", fw);
        } catch (Exception ignored) { }
        return o;
    }

    /** POST /api/neuigkeiten/gesehen {versionCode?, firmware?:true} → zu speichernde Werte (nie über die installierte Version hinaus). */
    public static JSONObject gesehen(JSONObject req, int installiert) {
        JSONObject neu = new JSONObject();
        try {
            if (req.has("versionCode")) {
                int vc = req.optInt("versionCode", installiert);
                neu.put("gesehenVersionCode", Math.max(0, Math.min(vc, installiert)));
            } else if (!req.optBoolean("firmware", false)) {
                neu.put("gesehenVersionCode", installiert);
            }
            if (req.optBoolean("firmware", false)) neu.put("firmwareGesehen", true);
        } catch (Exception ignored) { }
        return neu;
    }
}
