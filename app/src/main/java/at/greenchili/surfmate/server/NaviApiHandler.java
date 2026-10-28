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
            String sendBody = "plan".equals(op) ? at.greenchili.surfmate.planer.FahrzeugWahl.einsetzen(planBody(body)) : body;   // Kern 5: fahrzeug immer
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

    private static JSONObject error(String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("fehler", msg);
        } catch (Exception ignored) { }
        return o;
    }
}
