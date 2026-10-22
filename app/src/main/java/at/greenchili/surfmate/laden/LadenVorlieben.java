package at.greenchili.surfmate.laden;

import at.greenchili.surfmate.einstellungen.Feld;
import at.greenchili.surfmate.einstellungen.LadenKatalog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Lade-Vorlieben aus der Config im Auto (LADEN_VERTRAG.md Teil A) und ihre Abbildung auf den
 * Plan-Request des Planers ({@code POST /ev/api/plan}). Rein bis auf {@link #inPlanAnfrage(String)}
 * und {@link #ankunftReserve()}, die die Config lesen.
 *
 * <p>Solange {@code laden.stand} fehlt, schickt Panoptes keine Vorlieben mit (Übergang: Planer-Konto bzw.
 * Planer-Standard). Danach ist das Auto die Quelle: auch leere Listen gehen mit („keine Karten“).
 * Der Planer speichert nichts davon.
 */
public final class LadenVorlieben {

    private LadenVorlieben() {}

    public static final String QUELLE_AUTO = "auto";

    /** Alle Felder in Vertragsform (kurze Namen), nicht gesetzte als null bzw. Standard. */
    public static JSONObject lies(JSONObject root) {
        JSONObject o = new JSONObject();
        try {
            for (Feld f : LadenKatalog.felder()) {
                Object v = f.zugriff.lese(root);
                if (v == null) v = f.standard;
                if (v instanceof JSONArray) v = new JSONArray(v.toString());
                o.put(f.schluesselConfig(), v == null ? JSONObject.NULL : v);
            }
        } catch (Exception ignored) {
        }
        return o;
    }

    /** ISO-Zeit des letzten Speicherns, oder null (= nie, Übergang). */
    public static String stand(JSONObject root) {
        JSONObject l = root == null ? null : root.optJSONObject(LadenKatalog.ABSCHNITT);
        if (l == null || !l.has(LadenKatalog.STAND) || l.isNull(LadenKatalog.STAND)) return null;
        String s = l.optString(LadenKatalog.STAND, "");
        return s.isEmpty() ? null : s;
    }

    /** Das Stück für {@code POST plan}; null, solange das Auto (noch) nicht die Quelle ist. */
    public static JSONObject planerStueck(JSONObject root) {
        if (stand(root) == null) return null;
        JSONObject l = lies(root);
        JSONObject p = new JSONObject();
        try {
            JSONArray karten = new JSONArray();
            JSONArray k = l.optJSONArray("karten");
            for (int i = 0; k != null && i < k.length(); i++) karten.put(k.getJSONObject(i).getInt("id"));
            p.put("karten", karten);
            p.put("nur_meine_karten", l.optBoolean("nurMeineKarten") ? 1 : 0);
            putWenn(p, "soc_ziel_min", l.opt("ankunftSocMin"));
            putWenn(p, "soc_max", l.opt("ladenBis"));
            putWenn(p, "modus", l.opt("modus"));
            JSONObject f = new JSONObject();
            f.put("bevorzugt", cpos(l.optJSONArray("netze")));
            f.put("netze_modus", l.optString("netzeModus", "bevorzugen"));
            f.put("umweg_max_min", l.optInt("umwegMaxMin", 10));
            f.put("nur_offen", l.optBoolean("nurOffen"));
            f.put("ausschluss", cpos(l.optJSONArray("meiden")));
            putWenn(f, "min_kw", l.opt("minKw"));
            putWenn(f, "stecker", l.opt("stecker"));
            p.put("filter", f);
            p.put("vorlieben_quelle", QUELLE_AUTO);
        } catch (Exception e) {
            return null;
        }
        return p;
    }

    /**
     * Mischt das Stück in eine Plan-Anfrage. Was die Anfrage selbst mit Wert setzt, gewinnt;
     * null, leere Listen und leere Texte gelten als „nicht angegeben“.
     */
    public static JSONObject mische(JSONObject anfrage, JSONObject stueck) {
        JSONObject b;
        try {
            b = new JSONObject(anfrage == null ? "{}" : anfrage.toString());
            if (stueck == null) return b;
            java.util.Iterator<String> it = stueck.keys();
            while (it.hasNext()) {
                String key = it.next();
                Object v = stueck.get(key);
                if ("filter".equals(key) && v instanceof JSONObject) {
                    JSONObject f = b.optJSONObject("filter");
                    if (f == null) {
                        f = new JSONObject();
                        b.put("filter", f);
                    }
                    java.util.Iterator<String> fi = ((JSONObject) v).keys();
                    while (fi.hasNext()) {
                        String fk = fi.next();
                        if (!hatWert(f.opt(fk))) f.put(fk, ((JSONObject) v).get(fk));
                    }
                } else if (!hatWert(b.opt(key))) {
                    b.put(key, v);
                }
            }
            b.put("vorlieben_quelle", QUELLE_AUTO);
        } catch (Exception e) {
            return anfrage;
        }
        return b;
    }

    /** Laufzeit (NaviApiHandler, Neuplanung): Vorlieben aus der Config in den Body; Fehler → Body unverändert. */
    public static String inPlanAnfrage(String body) {
        String m = mitVorliebenOderNull(body);
        return m != null ? m : body;
    }

    /** Wie {@link #inPlanAnfrage}, aber null, solange das Auto nicht die Quelle ist (dann gilt das Altverhalten). */
    public static String mitVorliebenOderNull(String body) {
        try {
            JSONObject stueck = planerStueck(at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig());
            if (stueck == null) return null;
            return mische(new JSONObject(body), stueck).toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** „Ankommen mit mind.“ in % für F1, oder NaN (= Wert aus der Planung). Ersetzt planner.reserve. */
    public static double ankunftReserve(JSONObject root) {
        Object v = lies(root).opt("ankunftSocMin");
        return v instanceof Number && ((Number) v).doubleValue() > 0 ? ((Number) v).doubleValue() : Double.NaN;
    }

    public static double ankunftReserve() {
        try {
            return ankunftReserve(at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig());
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    /** Antwort für {@code GET /api/laden}. */
    public static JSONObject antwort(JSONObject root) throws Exception {
        JSONObject l = lies(root);
        String stand = stand(root);
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("version", 1);
        o.put("quelle", stand == null ? "standard" : QUELLE_AUTO);
        o.put("stand", stand == null ? JSONObject.NULL : stand);
        o.put("laden", l);
        JSONObject p = planerStueck(root);
        o.put("planer", p == null ? JSONObject.NULL : p);
        o.put("etag", etag(root));
        return o;
    }

    public static String etag(JSONObject root) {
        String s = lies(root).toString() + "|" + stand(root);
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("l-");
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return "l-" + Integer.toHexString(s.hashCode());
        }
    }

    /** Felder der Gruppe als Liste (für Prüfen/Schreiben im Handler). */
    public static List<Feld> felder() {
        return LadenKatalog.felder();
    }

    // ---------------------------------------------------------------- intern

    private static boolean hatWert(Object v) {
        if (v == null || v == JSONObject.NULL) return false;
        if (v instanceof JSONArray) return ((JSONArray) v).length() > 0;
        if (v instanceof String) return !((String) v).trim().isEmpty();
        return true;
    }

    private static void putWenn(JSONObject o, String key, Object v) throws Exception {
        if (v != null && v != JSONObject.NULL) o.put(key, v);
    }

    private static JSONArray cpos(JSONArray netze) {
        JSONArray a = new JSONArray();
        for (int i = 0; netze != null && i < netze.length(); i++) {
            JSONObject n = netze.optJSONObject(i);
            if (n != null && !n.optString("cpo", "").isEmpty()) a.put(n.optString("cpo"));
        }
        return a;
    }
}
