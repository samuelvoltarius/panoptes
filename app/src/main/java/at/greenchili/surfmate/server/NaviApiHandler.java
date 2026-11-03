package at.greenchili.surfmate.server;

import at.greenchili.surfmate.config.UnifiedConfigManager;
import at.greenchili.surfmate.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Navigation über den öffentlichen Ladeplaner (byd.serveone.at/ev/api).
 *
 * <p>Die Web-Oberfläche läuft auf der Adresse des Autos; der Planer erlaubt keine
 * fremden Ursprünge (kein CORS). Deshalb reicht die App die drei öffentlichen
 * Abfragen durch. Koordinaten und Suchtext gehen nur im Body (POST) hinaus, nie in
 * einer URL, und werden hier nicht protokolliert. Ein Mitglieds-Schlüssel wird für
 * diese Abfragen nicht gebraucht und nicht mitgeschickt.
 *
 * <ul>
 *   <li>{@code POST /api/navi/orte}    — {@code {"q":"Salzburg","limit":6}}</li>
 *   <li>{@code POST /api/navi/plan}    — {@code {"start":[lat,lon],"ziel":[lat,lon],"soc_start":50,…}}</li>
 *   <li>{@code POST /api/navi/umkreis} — {@code {"lat":…,"lon":…,"radius":10,"kwh":20}}</li>
 * </ul>
 *
 * <p>Die Basisadresse steht in der Config unter {@code planner.baseUrl}
 * (Standard: der öffentliche Planer), damit andere Community-Server möglich sind.
 */
public class NaviApiHandler {

    private static final String TAG = "NaviApiHandler";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final String DEFAULT_BASE = "https://byd.serveone.at/ev/api/";
    private static final int MAX_BODY = 8 * 1024;
    private static final int MAX_RESPONSE = 4 * 1024 * 1024;

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        // F1 „Reicht es noch?“: Stand der Rechnung im Auto (nur lesen).
        if (path.equals("/api/navi/unterwegs") && method.equals("GET")) {
            HttpResponse.sendJson(out, at.greenchili.surfmate.reichweite.RangeWatchDienst.status().toString());
            return true;
        }
        // Fahrer verwirft den Neuplanungs-Vorschlag (auto-status „abgelehnt“ an den Planer).
        if (path.equals("/api/navi/unterwegs/ablehnen")) {
            if (!"POST".equals(method)) {
                HttpResponse.sendError(out, 405, "Method Not Allowed");
                return true;
            }
            HttpResponse.sendJson(out, at.greenchili.surfmate.reichweite.RangeWatchDienst.ablehnen().toString());
            return true;
        }
        // „Route vom Planer übernehmen?“ (0.1.9): lesen auch aus dem Web, annehmen/ablehnen nur mit Login bzw. lokal
        if (path.equals("/api/navi/planer-route") && "GET".equals(method)) {
            Object[] r = at.greenchili.surfmate.planer.PlanerRoute.get().status(System.currentTimeMillis());
            HttpResponse.sendJson(out, (Integer) r[0], r[1].toString());
            return true;
        }
        if (path.equals("/api/navi/planer-route/annehmen") || path.equals("/api/navi/planer-route/ablehnen")) {
            if (!"POST".equals(method)) {
                HttpResponse.sendError(out, 405, "Method Not Allowed");
                return true;
            }
            if (AuthMiddleware.isExternRequest()) {
                HttpResponse.sendJson(out, 403, "{\"ok\":false,\"grund\":\"nur_mit_login\"}");
                return true;
            }
            String id;
            try {
                id = new JSONObject(body == null || body.isEmpty() ? "{}" : body).optString("id", null);
            } catch (Exception e) {
                id = null;
            }
            at.greenchili.surfmate.planer.PlanerRoute pr = at.greenchili.surfmate.planer.PlanerRoute.get();
            long now = System.currentTimeMillis();
            JSONObject res = path.endsWith("/annehmen") ? pr.annehmen(id, now) : pr.ablehnen(id, now);
            HttpResponse.sendJson(out, res.toString());
            return true;
        }
        // Ziel aus Google-Maps-, Waze- oder geo:-Link: im Auto gelesen, nie an den Planer. Kurzlinks nur mit
        // „aufloesen“: HEAD vom Auto, nur der Location-Kopf (ZielLink). Link und Koordinaten werden nicht geloggt.
        if (path.equals("/api/navi/link")) {
            if (!"POST".equals(method)) {
                HttpResponse.sendError(out, 405, "Method Not Allowed");
                return true;
            }
            JSONObject req;
            try {
                req = new JSONObject(body == null || body.isEmpty() || body.length() > MAX_BODY ? "{}" : body);
            } catch (Exception e) {
                req = new JSONObject();
            }
            HttpResponse.sendJson(out, linkAntwort(req.optString("link", ""), req.optBoolean("aufloesen", false),
                    at.greenchili.surfmate.navmap.nav.ZielLink::headLocation).toString());
            return true;
        }
        String op;
        if (path.equals("/api/navi/orte")) op = "orte";
        else if (path.equals("/api/navi/plan")) op = "plan";
        else if (path.equals("/api/navi/umkreis")) op = "umkreis";
        else return false;

        if (!"POST".equals(method)) {
            HttpResponse.sendError(out, 405, "Method Not Allowed");
            return true;
        }
        if (body == null || body.length() > MAX_BODY) {
            HttpResponse.sendJson(out, 400, error(Messages.text("planer.navi.anfrage_gross",
                    "Request is empty or too large")).toString());
            return true;
        }
        try {
            new JSONObject(body); // nur gültiges JSON weiterreichen
        } catch (Exception e) {
            HttpResponse.sendJson(out, 400, error(Messages.text("planer.navi.anfrage_json",
                    "Request is not JSON")).toString());
            return true;
        }
        // Backoff „umkreis“ (0.1.9): Planer gerade nicht erreichbar → nicht bei jedem Kartenschwenk erneut ins
        // Netz und nicht jedes Mal loggen; ehrlich sagen, wann wieder gefragt wird.
        final boolean umkreis = "umkreis".equals(op);
        if (umkreis) {
            long warte = at.greenchili.surfmate.planer.PlanerBackoff.UMKREIS.warteMs(System.currentTimeMillis());
            if (warte > 0) {
                HttpResponse.sendJson(out, 503, offline(warte).toString());
                return true;
            }
        }

        HttpURLConnection c = null;
        try {
            // 0.1.9.1 (Ladenetze/-karten nur aus dem Planer-Konto): Beim Planen nie ein leeres
            // filter.bevorzugt schicken (sonst überstimmt es die Konto-Vorlieben), und den Schlüssel mitgeben,
            // damit der Planer die Vorlieben selbst aus dem Konto nimmt. Lehnt er den Schlüssel ab (401, z. B.
            // Geräte-Typ ohne plan-Recht), einmal ohne Schlüssel planen – wie bisher, nur ohne Vorlieben.
            String sendBody = "plan".equals(op) ? at.greenchili.surfmate.planer.FahrzeugWahl.einsetzen(planBody(body))
                    : umkreis ? umkreisBody(body) : body;   // Kern 5: fahrzeug immer; umkreis nur gerundet
            String[] kopf = "plan".equals(op) ? at.greenchili.surfmate.planer.PlanerSchluessel.kopf() : null;
            c = verbinde(op, sendBody, kopf);
            int code = c.getResponseCode();
            at.greenchili.surfmate.einrichtung.Selbsttest.planerAntwort(kopf != null, code, System.currentTimeMillis());
            if (code == 401 && kopf != null) {
                c.disconnect();
                c = verbinde(op, sendBody, null);
                code = c.getResponseCode();
            }
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : readLimited(in);
            if (text.trim().startsWith("{")) text = PlanerFehler.fuerNutzer(code, text, PlanerFehler.LAUFZEIT);   // Kern 5: nie Rohtexte
            if (!text.trim().startsWith("{")) {
                // nginx liefert bei Zeitüberschreitung eine HTML-Seite (als 404 getarnt).
                text = error(code == 429
                        ? Messages.text("planer.navi.zu_viele", "Too many requests – please wait 2 minutes.")
                        : Messages.text("planer.navi.antwortet_nicht",
                                "The route planner is not answering (HTTP {0}). For long routes, please try again.",
                                String.valueOf(code))).toString();
            }
            if (umkreis && at.greenchili.surfmate.planer.PlanerBackoff.UMKREIS.erfolg()) {
                logger.info("Planer wieder erreichbar (umkreis)");
            }
            if (umkreis && code == 200 && guenstigsteWunsch(body) && !AuthMiddleware.isExternRequest()) {
                text = mitEigenenPreisen(text);
            }
            HttpResponse.sendJson(out, code == 200 ? 200 : (code == 429 ? 429 : 502), text);
        } catch (Exception e) {
            if (umkreis && at.greenchili.surfmate.planer.PlanerBackoff.istNetzfehler(e)) {
                long now = System.currentTimeMillis();
                boolean erster = at.greenchili.surfmate.planer.PlanerBackoff.UMKREIS.fehler(now);
                if (erster) {
                    logger.warn("Planer nicht erreichbar (umkreis): " + e.getClass().getSimpleName()
                            + " – neue Versuche mit Pause");
                }
                HttpResponse.sendJson(out, 503,
                        offline(at.greenchili.surfmate.planer.PlanerBackoff.UMKREIS.warteMs(now)).toString());
                return true;
            }
            logger.warn("Planer nicht erreichbar (" + op + "): " + e.getClass().getSimpleName());
            HttpResponse.sendJson(out, 502, error(Messages.text("planer.navi.nicht_erreichbar",
                    "Route planner not reachable")).toString());
        } finally {
            if (c != null) c.disconnect();
        }
        return true;
    }

    /** Basisadresse des Planers (Config planner.baseUrl, sonst der öffentliche Planer). */
    public static String planerBasis() {
        return baseUrl();
    }

    private static String baseUrl() {
        try {
            JSONObject p = UnifiedConfigManager.loadConfig().optJSONObject("planner");
            String b = p != null ? p.optString("baseUrl", "") : "";
            if (b.startsWith("https://")) return b.endsWith("/") ? b : b + "/";
        } catch (Exception ignored) { }
        return DEFAULT_BASE;
    }

    private static String readLimited(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[16 * 1024];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
            if (buf.size() > MAX_RESPONSE) throw new IllegalStateException("Antwort zu groß");
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static HttpURLConnection verbinde(String op, String body, String[] kopf) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl() + op).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(10_000);
        c.setReadTimeout("plan".equals(op) ? 150_000 : 20_000); // lange Strecken rechnet der Planer > 1 min
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "Panoptes");
        if (kopf != null) c.setRequestProperty(kopf[0], kopf[1]);   // nur im Header, nie in URL/Log
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(payload.length);
        try (OutputStream os = c.getOutputStream()) { os.write(payload); }
        return c;
    }

    /** 0.1.25 Handy-Route: dieselbe Aufbereitung des Plan-Bodys wie beim Planen im Auto (Vorlieben). */
    public static String planBodyFuerAuto(String body) {
        return planBody(body);
    }

    /** Leeres filter.bevorzugt (bzw. leeres filter) entfernen: dann gilt die Konto-Einstellung beim Planer. */
    static String planBody(String body) {
        String auto = at.greenchili.surfmate.laden.LadenVorlieben.mitVorliebenOderNull(body);   // Kern 5: Auto ist die Quelle
        if (auto != null) return auto;
        try {
            JSONObject b = new JSONObject(body);
            JSONObject f = b.optJSONObject("filter");
            if (f != null) {
                Object bev = f.opt("bevorzugt");
                if (bev instanceof org.json.JSONArray && ((org.json.JSONArray) bev).length() == 0) f.remove("bevorzugt");
                if (bev == JSONObject.NULL) f.remove("bevorzugt");
                if (f.length() == 0) b.remove("filter");
            }
            return b.toString();
        } catch (Exception e) {
            return body;
        }
    }

    /** Zusätzlicher Radius, wenn die Position hier erst gerundet werden musste (Rundung ≤ ~0,7 km). */
    static final double UMKREIS_RUNDUNG_KM = 1.0;
    /** Größter Radius, den die Weiterleitung nach dem Aufschlag schickt. */
    static final double UMKREIS_MAX_RADIUS_KM = 50.0;

    /**
     * Datenschutz 03.11.2026: {@code lat}/{@code lon} gehen höchstens auf 2 Nachkommastellen (~1 km) an den Planer.
     * Kam die Position genauer (alter Client), wird hier gerundet und der Radius um {@link #UMKREIS_RUNDUNG_KM}
     * vergrößert, damit keine nahe Säule wegfällt; die Feinsortierung macht das Gerät. Schon grob gerundete Anfragen
     * (Karte 0,05°, Navi 0,01°, StoppBelegung) bleiben unverändert.
     */
    static String umkreisBody(String body) {
        try {
            JSONObject b = new JSONObject(body);
            if (!b.has("lat") || !b.has("lon")) return body;
            double lat = b.getDouble("lat"), lon = b.getDouble("lon");
            if (Double.isNaN(lat) || Double.isNaN(lon) || Double.isInfinite(lat) || Double.isInfinite(lon)) return body;
            double rLat = Math.round(lat * 100) / 100.0, rLon = Math.round(lon * 100) / 100.0;
            boolean geaendert = Math.abs(rLat - lat) > 1e-9 || Math.abs(rLon - lon) > 1e-9;
            b.put("lat", rLat);
            b.put("lon", rLon);
            if (geaendert) {
                double r = b.optDouble("radius", 10);
                if (Double.isNaN(r) || r <= 0) r = 10;
                b.put("radius", Math.min(UMKREIS_MAX_RADIUS_KM, r + UMKREIS_RUNDUNG_KM));
            }
            // „Günstigste in der Nähe“: Preise mit den eigenen Ladekarten (nur Tarif-IDs aus „Meine Ladekarten“, wie
            // beim Planen) – nur auf Wunsch der Navi-Seite, der Merker selbst geht nicht hinaus.
            if (b.has("meine_karten")) {
                boolean wunsch = b.optBoolean("meine_karten", false);
                b.remove("meine_karten");
                if (wunsch && !b.has("karten")) {
                    org.json.JSONArray k = meineKarten();
                    if (k != null && k.length() > 0) b.put("karten", k);
                }
            }
            return b.toString();
        } catch (Exception e) {
            return body;   // ungültige Werte reicht der Planer selbst mit 400 zurück
        }
    }

    /** Tarif-IDs aus „Meine Ladekarten“ (nur wenn das Auto die Vorlieben-Quelle ist), sonst null. Tests ersetzen das. */
    static java.util.function.Supplier<org.json.JSONArray> meineKartenQuelle = () -> {
        JSONObject s = at.greenchili.surfmate.laden.LadenVorlieben.planerStueck(UnifiedConfigManager.loadConfig());
        return s == null ? null : s.optJSONArray("karten");
    };

    /** Wunsch der Navi-Seite „Günstigste in der Nähe“ (Merker {@code meine_karten}). */
    static boolean guenstigsteWunsch(String body) {
        try {
            return new JSONObject(body).optBoolean("meine_karten", false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 0.1.36: eigener „Laut Säule“-Preis je Betreiber und Stromart als erster Preis für „Günstigste in der Nähe“.
     * Wird nur im Auto in die Antwort geschrieben ({@code preis_eigen}); an den Planer geht davon nichts.
     * Ohne Lade-DB (z. B. Analytics nicht bereit) bleibt die Antwort unverändert.
     */
    static String mitEigenenPreisen(String text) {
        try {
            org.json.JSONArray sitz = eigeneLadungenQuelle.get();
            if (sitz == null || sitz.length() == 0) return text;
            JSONObject o = new JSONObject(text);
            int n = at.greenchili.surfmate.charging.LadePreisVorschlag.eigenePreiseEintragen(o, sitz);
            return n > 0 ? o.toString() : text;
        } catch (Throwable t) {
            return text;
        }
    }

    /** Eigene Ladungen wie {@code /api/charging} (Betreiber, Stromart, „Laut Säule“). Tests ersetzen das. */
    static java.util.function.Supplier<org.json.JSONArray> eigeneLadungenQuelle = () -> {
        at.greenchili.surfmate.charging.ChargingSessionManager csm =
                at.greenchili.surfmate.daemon.CameraDaemon.getChargingSessionManager();
        if (csm == null) return null;
        return csm.getSocDb().getChargingSessionsV2Range(0, System.currentTimeMillis() + 86_400_000L, 5000, 0);
    };

    private static org.json.JSONArray meineKarten() {
        try {
            return meineKartenQuelle.get();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Ehrliche Antwort während des Backoffs: Grund „netz“ und wann wieder gefragt wird. */
    public static JSONObject offline(long warteMs) {
        long s = Math.max(1, (warteMs + 999) / 1000);
        JSONObject o = error(Messages.text("planer.navi.offline",
                "Route planner not reachable right now (no connection) – next try in {0} s", String.valueOf(s)));
        try {
            o.put("grund", "netz");
            o.put("wiederInS", s);
        } catch (Exception ignored) { }
        return o;
    }

    /**
     * Antwort für {@code POST /api/navi/link}: {@code {ok,lat,lng,name?,quelle}} oder {@code {ok:false,grund,quelle?}}
     * mit grund kurzlink | kein_ort | unbekannter_link | leer | nicht_aufloesbar.
     */
    public static JSONObject linkAntwort(String link, boolean aufloesen, at.greenchili.surfmate.navmap.nav.ZielLink.Kopf kopf)
            throws Exception {
        at.greenchili.surfmate.navmap.nav.ZielLink.Ziel z = aufloesen
                ? at.greenchili.surfmate.navmap.nav.ZielLink.aufloesen(link, kopf)
                : at.greenchili.surfmate.navmap.nav.ZielLink.lesen(link);
        JSONObject o = new JSONObject().put("ok", z.ok());
        if (z.quelle != null) o.put("quelle", z.quelle);
        if (!z.ok()) return o.put("grund", z.grund);
        o.put("lat", Math.round(z.lat * 1e6) / 1e6).put("lng", Math.round(z.lng * 1e6) / 1e6);
        if (z.name != null) o.put("name", z.name);
        return o;
    }

    private static JSONObject error(String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("fehler", msg);
        } catch (Exception ignored) { }
        return o;
    }
}
