package at.greenchili.surfmate.planer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Welches Planer-Fahrzeugprofil gilt für dieses Auto? (Koordination 22.10.: „fahrzeug immer mitschicken, nie
 * stillschweigend annehmen“; Planer 3: ohne fahrzeug → 400 {@code code=fahrzeug_fehlt}.)
 *
 * <ol>
 *   <li>{@code laden.fahrzeug} – vom Nutzer gewählt (Profilname aus {@code GET /ev/api/fahrzeuge}).</li>
 *   <li>Sonst nur dann automatisch, wenn das erkannte Modell ({@code vehicle.modelId}, Quelle „user“ oder „auto“ –
 *       nicht der Renderer-Rückfall, nicht „legacy“) genau EIN Planer-Profil ergibt (ggf. über die vom Auto gemeldete
 *       Akkugröße eingegrenzt).</li>
 *   <li>Sonst {@code null}: die Oberfläche fragt einmal mit {@link #kandidaten}.</li>
 * </ol>
 * Gilt für jedes BYD (Regel 1); Varianten (z. B. drei Dolphin Surf) werden nie geraten.
 */
public final class FahrzeugWahl {

    private FahrzeugWahl() {}

    static final long CACHE_MS = 24 * 3600_000L;
    private static volatile JSONArray cache;
    private static volatile long cacheZeit;

    /** Wirksamer Profilname oder null. */
    public static String wirksam(JSONObject root, JSONArray profile, double akkuKwh) {
        String g = gewaehlt(root);
        if (g != null) return g;
        JSONArray k = kandidaten(root, profile, akkuKwh);
        return k.length() == 1 ? k.optString(0) : null;
    }

    /** Vom Nutzer gewählt ({@code laden.fahrzeug}) oder null. */
    public static String gewaehlt(JSONObject root) {
        JSONObject l = root == null ? null : root.optJSONObject("laden");
        if (l == null || !l.has("fahrzeug") || l.isNull("fahrzeug")) return null;
        String s = l.optString("fahrzeug", "").trim();
        return s.isEmpty() ? null : s;
    }

    /** Erkanntes Modell (nur echte Wahl, kein Rückfall) oder null. */
    public static String erkanntesModell(JSONObject root) {
        JSONObject v = root == null ? null : root.optJSONObject("vehicle");
        if (v == null) return null;
        String quelle = v.optString("modelSource", "");
        if (!"user".equals(quelle) && !"auto".equals(quelle)) return null;
        String m = v.optString("modelId", "").trim().toLowerCase(Locale.ROOT);
        return m.isEmpty() ? null : m;
    }

    /** Planer-Profilnamen, die zum erkannten Modell passen (bei bekannter Akkugröße ±1,5 kWh), sonst leer. */
    public static JSONArray kandidaten(JSONObject root, JSONArray profile, double akkuKwh) {
        JSONArray out = new JSONArray();
        String modell = erkanntesModell(root);
        if (modell == null || profile == null) return out;
        JSONArray passend = new JSONArray();
        for (int i = 0; i < profile.length(); i++) {
            JSONObject p = profile.optJSONObject(i);
            if (p == null || p.optBoolean("eigen", false)) continue;
            if (modell.equals(p.optString("modell_id", "").toLowerCase(Locale.ROOT))) passend.put(p);
        }
        boolean akku = akkuKwh > 0 && !Double.isNaN(akkuKwh);
        for (int i = 0; i < passend.length(); i++) {
            JSONObject p = passend.optJSONObject(i);
            if (akku && Math.abs(p.optDouble("akku_kwh", -100) - akkuKwh) > 1.5) continue;
            out.put(p.optString("name"));
        }
        return out;
    }

    /** Alle Profilnamen (für die Auswahlliste „anderes Fahrzeug“). */
    public static JSONArray namen(JSONArray profile) {
        JSONArray out = new JSONArray();
        for (int i = 0; profile != null && i < profile.length(); i++) {
            JSONObject p = profile.optJSONObject(i);
            if (p != null && !p.optString("name", "").isEmpty()) out.put(p.optString("name"));
        }
        return out;
    }

    /** Setzt {@code fahrzeug}, wenn die Anfrage keines (bzw. null/leer) hat und ein wirksames bekannt ist. */
    public static String einsetzen(String body, String fahrzeug) {
        if (fahrzeug == null || body == null) return body;
        try {
            JSONObject b = new JSONObject(body);
            Object f = b.opt("fahrzeug");
            if (f == null || f == JSONObject.NULL || String.valueOf(f).trim().isEmpty()) {
                b.put("fahrzeug", fahrzeug);
                return b.toString();
            }
        } catch (Exception ignored) {
        }
        return body;
    }

    // ---------------------------------------------------------------- Laufzeit

    /** Laufzeit: {@link #einsetzen} mit Config, Profil-Liste (Cache) und Akkugröße des Autos. */
    public static String einsetzen(String body) {
        try {
            return einsetzen(body, wirksamJetzt());
        } catch (Throwable t) {
            return body;
        }
    }

    public static String wirksamJetzt() {
        try {
            JSONObject root = at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig();
            String g = gewaehlt(root);
            if (g != null) return g;
            return wirksam(root, profile(), akkuKwh());
        } catch (Throwable t) {
            return null;
        }
    }

    /** Stand für /api/laden: gewählt, erkannt, wirksam, Kandidaten, alle Namen (null, wenn der Planer nicht erreichbar war). */
    public static JSONObject status(JSONObject root) {
        JSONObject o = new JSONObject();
        try {
            JSONArray p = profile();
            double akku = akkuKwh();
            String g = gewaehlt(root);
            o.put("gewaehlt", g == null ? JSONObject.NULL : g);
            o.put("modell", erkanntesModell(root) == null ? JSONObject.NULL : erkanntesModell(root));
            String w = g != null ? g : wirksam(root, p, akku);
            o.put("wirksam", w == null ? JSONObject.NULL : w);
            o.put("quelle", g != null ? "gewaehlt" : (w != null ? "erkannt" : "fehlt"));
            o.put("kandidaten", kandidaten(root, p, akku));
            o.put("alle", p == null ? JSONObject.NULL : namen(p));
        } catch (Exception ignored) {
        }
        return o;
    }

    /** Öffentliche Profil-Liste des Planers, 24 h im Speicher; Netzfehler → letzter Stand bzw. null. */
    static JSONArray profile() {
        long jetzt = System.currentTimeMillis();
        JSONArray c = cache;
        if (c != null && jetzt - cacheZeit < CACHE_MS) return c;
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(PlanerSchluessel.BASIS + "fahrzeuge").openConnection();
            h.setConnectTimeout(8_000);
            h.setReadTimeout(15_000);
            h.setRequestProperty("Accept", "application/json");
            h.setRequestProperty("User-Agent", "Panoptes");
            if (h.getResponseCode() != 200) return c;
            try (InputStream in = h.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    b.write(buf, 0, n);
                    if (b.size() > 1024 * 1024) return c;
                }
                JSONArray neu = new JSONObject(new String(b.toByteArray(), StandardCharsets.UTF_8)).optJSONArray("fahrzeuge");
                if (neu != null) {
                    cache = neu;
                    cacheZeit = jetzt;
                    return neu;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (h != null) h.disconnect();
        }
        return c;
    }

    /**
     * Nutzbare Akkugröße laut Auto (kWh) oder NaN. Heute NaN: Panoptes hat keinen vom Auto gemeldeten Nennwert
     * (die SoH-Schätzung leitet ihn selbst aus dem Modell ab – das wäre ein Zirkelschluss). Dann wird nicht eingegrenzt.
     */
    static double akkuKwh() {
        return Double.NaN;
    }
}
