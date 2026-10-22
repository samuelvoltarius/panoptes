package at.greenchili.surfmate.planer;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * „Route vom Planer übernehmen?“ (Koordination 0.1.9, Punkt 9; Vertrag API_FUER_SURFMATE.md §3/3a/3b).
 * „An Auto senden“ aus /ev/ bzw. dem Cockpit legt beim Planer eine Route bereit. Panoptes zeigt sie an und
 * übernimmt sie nur auf Tipp am Auto: nichts geht automatisch ins Navi, nichts wird am Auto ausgelöst.
 *
 * <ul>
 *   <li>{@link #status} liest nur ({@code GET meine-route}, ohne Abholmarkierung), höchstens alle 30 s.</li>
 *   <li>{@link #annehmen} holt die Route ab ({@code GET auto-abholen}), merkt sie als angenommen (Grundlage für
 *       „Reicht es noch?“) und meldet {@code auto-status „angenommen“}.</li>
 *   <li>{@link #ablehnen} meldet {@code auto-status „abgelehnt“} und bietet dieselbe Route nicht mehr an.</li>
 * </ul>
 */
public final class PlanerRoute {

    static final long CACHE_MS = 30_000L;

    /** Netz und Ablage (im Test nachgebaut). */
    public interface Umgebung {
        /** {HTTP-Status, JSON oder null}; null = Netzfehler. */
        Object[] http(String methode, String op, String[] token, String body);
        String[] token();
        String autoId();
        String angenommen();
        String abgelehnt();
        void merke(String schluessel, String id);
        boolean unterwegsAn();
        void routeNeuLaden();
    }

    private static volatile PlanerRoute instance;

    private final Umgebung env;
    private final PlanerBackoff backoff = new PlanerBackoff();
    private JSONObject cachePlan;
    private boolean cacheGueltig;
    private long cacheZeit;

    PlanerRoute(Umgebung env) {
        this.env = env;
    }

    public static PlanerRoute get() {
        PlanerRoute r = instance;
        if (r == null) {
            synchronized (PlanerRoute.class) {
                if (instance == null) instance = new PlanerRoute(new Echt());
                r = instance;
            }
        }
        return r;
    }

    /** Ist diese Route (Planer-Plan) am Auto angenommen? Nur dann überwacht RangeWatch sie. */
    public static boolean istAngenommen(JSONObject plan) {
        if (plan == null) return false;
        String id = plan.optString("erstellt", "");
        try {
            return !id.isEmpty() && id.equals(get().env.angenommen());
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ API

    /** GET /api/navi/planer-route. Rückgabe {code, json}. */
    public synchronized Object[] status(long now) {
        String[] token = env.token();
        if (token == null) return new Object[]{404, obj("vorhanden", false, "grund", "kein_planer")};
        if (!cacheGueltig || now - cacheZeit >= CACHE_MS) {
            if (backoff.warteMs(now) > 0) return new Object[]{200, obj("vorhanden", false, "grund", "netz")};
            String auto = env.autoId();
            Object[] r = env.http("GET", "meine-route" + (auto == null ? "" : "?auto=" + auto), token, null);
            if (r == null) {
                backoff.fehler(now);
                return new Object[]{200, obj("vorhanden", false, "grund", "netz")};
            }
            backoff.erfolg();
            int code = (Integer) r[0];
            if (code == 401) return new Object[]{404, obj("vorhanden", false, "grund", "kein_planer")};
            if (code != 200 || r[1] == null) return new Object[]{200, obj("vorhanden", false, "grund", "planer")};
            cachePlan = ((JSONObject) r[1]).optJSONObject("plan");
            cacheGueltig = true;
            cacheZeit = now;
        }
        return new Object[]{200, antwort(cachePlan, env.angenommen(), env.abgelehnt())};
    }

    /** POST /api/navi/planer-route/annehmen {id}. */
    public synchronized JSONObject annehmen(String id, long now) {
        String[] token = env.token();
        if (token == null) return obj("ok", false, "grund", "kein_planer");
        String auto = env.autoId();
        Object[] r = env.http("GET", "auto-abholen" + (auto == null ? "" : "?auto=" + auto), token, null);
        if (r == null) return obj("ok", false, "grund", "netz");
        if ((Integer) r[0] == 401) return obj("ok", false, "grund", "kein_planer");
        JSONObject plan = (Integer) r[0] == 200 && r[1] != null ? ((JSONObject) r[1]).optJSONObject("plan") : null;
        if (plan == null || id == null || !id.equals(plan.optString("erstellt", ""))) {
            cacheGueltig = false;
            return obj("ok", false, "grund", "anderer_stand");
        }
        env.merke("routeAngenommen", id);
        melden(token, auto, "angenommen");
        cacheGueltig = false;
        env.routeNeuLaden();
        JSONObject o = obj("ok", true);
        if (!env.unterwegsAn()) put(o, "hinweis", "unterwegs_aus");
        return o;
    }

    /** POST /api/navi/planer-route/ablehnen {id}. */
    public synchronized JSONObject ablehnen(String id, long now) {
        String[] token = env.token();
        if (token == null) return obj("ok", false, "grund", "kein_planer");
        if (id == null || id.isEmpty()) return obj("ok", false, "grund", "anderer_stand");
        env.merke("routeAbgelehnt", id);
        melden(token, env.autoId(), "abgelehnt");
        cacheGueltig = false;
        return obj("ok", true);
    }

    private void melden(String[] token, String auto, String status) {
        JSONObject b = new JSONObject();
        put(b, "status", status);
        if (auto != null) put(b, "auto", auto);
        env.http("POST", "auto-status", token, b.toString());   // 404 = keine Route mehr bereit: egal
    }

    // ------------------------------------------------------------------ Abbildung (rein)

    /** Planer-Plan → Antwort für das Web (Vertrag mit Neubau 2). */
    static JSONObject antwort(JSONObject plan, String angenommen, String abgelehnt) {
        if (plan == null) return obj("vorhanden", false);
        String id = plan.optString("erstellt", "");
        if (id.isEmpty() || id.equals(abgelehnt)) return obj("vorhanden", false);
        JSONObject o = obj("vorhanden", true, "id", id, "quelle", "planer");
        put(o, "name", plan.optString("titel", ""));
        put(o, "erstellt", millis(id));
        JSONObject z = plan.optJSONObject("ziel");
        if (z != null) put(o, "ziel", ort(z));
        JSONArray st = new JSONArray();
        JSONArray s = plan.optJSONArray("stopps");
        for (int i = 0; s != null && i < s.length() && i < 20; i++) {
            JSONObject x = s.optJSONObject(i);
            if (x == null) continue;
            JSONObject e = ort(x);
            put(e, "art", "laden");
            st.put(e);
        }
        put(o, "stopps", st);
        JSONArray zz = Zwischenziele.lies(plan);   // Kern 5, §3-Erweiterung: nur Anzeige, ohne Feld wie bisher
        if (zz.length() > 0) {
            put(o, "zwischenziele", zz);
            put(o, "wegpunkte", Zwischenziele.wegpunkte(zz, s));
        }
        if (plan.has("km")) put(o, "km", plan.optDouble("km"));
        if (plan.has("soc_ankunft")) put(o, "ankunftAkku", plan.optDouble("soc_ankunft"));
        String status = id.equals(angenommen) ? "angenommen" : null;
        JSONObject ps = plan.optJSONObject("status");
        if (status == null && ps != null && "angenommen".equals(ps.optString("status"))) status = "angenommen";
        put(o, "status", status == null ? JSONObject.NULL : status);
        return o;
    }

    private static JSONObject ort(JSONObject x) {
        JSONObject o = new JSONObject();
        put(o, "name", x.optString("name", ""));
        put(o, "lat", x.optDouble("lat", Double.NaN));
        put(o, "lng", x.has("lon") ? x.optDouble("lon", Double.NaN) : x.optDouble("lng", Double.NaN));
        return o;
    }

    static Object millis(String iso) {
        try {
            return java.time.Instant.parse(iso).toEpochMilli();
        } catch (Throwable t) {
            return JSONObject.NULL;
        }
    }

    private static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) put(o, (String) kv[i], kv[i + 1]);
        return o;
    }

    private static void put(JSONObject o, String k, Object v) {
        try {
            if (v instanceof Double && (((Double) v).isNaN() || ((Double) v).isInfinite())) v = JSONObject.NULL;
            o.put(k, v);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ echte Umgebung

    static final class Echt implements Umgebung {
        private static JSONObject planner() {
            try {
                return at.greenchili.surfmate.config.UnifiedConfigManager.forceReload().optJSONObject("planner");
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public Object[] http(String methode, String op, String[] token, String body) {
            java.net.HttpURLConnection c = null;
            try {
                c = (java.net.HttpURLConnection) new java.net.URL(PlanerSchluessel.BASIS + op).openConnection();
                c.setRequestMethod(methode);
                c.setConnectTimeout(8_000);
                c.setReadTimeout(12_000);
                c.setRequestProperty(token[0], token[1]);
                c.setRequestProperty("Accept", "application/json");
                c.setRequestProperty("User-Agent", "Panoptes");
                if (body != null) {
                    byte[] b = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    c.setFixedLengthStreamingMode(b.length);
                    try (java.io.OutputStream o = c.getOutputStream()) { o.write(b); }
                }
                int code = c.getResponseCode();
                java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                JSONObject j = null;
                if (in != null) {
                    try (java.io.InputStream i2 = in) {
                        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n, total = 0;
                        while ((n = i2.read(buf)) > 0 && total < 2_000_000) { bo.write(buf, 0, n); total += n; }
                        String t = new String(bo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).trim();
                        if (t.startsWith("{")) j = new JSONObject(t);
                    }
                }
                return new Object[]{code, j};
            } catch (Exception e) {
                return null;
            } finally {
                if (c != null) c.disconnect();
            }
        }

        @Override public String[] token() { return PlanerSchluessel.kopf(); }

        @Override
        public String autoId() {
            try {
                return at.greenchili.surfmate.server.EinstellungenApiHandler.autoId();
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public String angenommen() {
            JSONObject p = planner();
            return p == null ? "" : p.optString("routeAngenommen", "");
        }

        @Override
        public String abgelehnt() {
            JSONObject p = planner();
            return p == null ? "" : p.optString("routeAbgelehnt", "");
        }

        @Override
        public void merke(String schluessel, String id) {
            try {
                java.util.Map<String, Object> v = new java.util.HashMap<>();
                v.put(schluessel, id);
                at.greenchili.surfmate.config.UnifiedConfigManager.updateValues("planner", v);
            } catch (Throwable ignored) {
            }
        }

        @Override
        public boolean unterwegsAn() {
            JSONObject p = planner();
            return p != null && p.optBoolean("unterwegs", false);
        }

        @Override
        public void routeNeuLaden() {
            at.greenchili.surfmate.reichweite.RangeWatchDienst.routeNeuLaden();
        }
    }
}
