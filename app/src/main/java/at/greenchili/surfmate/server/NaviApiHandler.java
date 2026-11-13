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
 *   <li>{@code POST /api/navi/fuehrung} — {@code {"start":[lat,lon],"ziel":[lat,lon],"via":[…],"sprache":"de",…}}
 *       (0.1.39 G2, Vertrag {@code fuehrung/1}): abbiegegenaue Route für die Panoptes-Führung. Gleicher Weg wie
 *       {@code plan} (Schlüssel aus {@link at.greenchili.surfmate.planer.PlanerSchluessel#kopf()}, {@code laden} wie
 *       ein Plan-Body aufbereitet). Ohne Schlüssel geht nichts hinaus. Die Punkte gehen genau hinaus (zum Routen
 *       nötig), nur im Body, und werden hier nicht protokolliert.</li>
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
        // 0.1.44 (P3b): die Panoptes-Führung (App-Prozess) meldet das belegte Tempolimit an ihrer Teilstrecke; der Launcher liest es
        // aus dem Kern (TempolimitStand, Quelle osm, frisch höchstens 15 s). Nur Loopback, nur eine Zahl, keine Position.
        if (path.equals("/api/navi/tempolimit")) {
            if (!"POST".equals(method)) {
                HttpResponse.sendError(out, 405, "Method Not Allowed");
                return true;
            }
            if (AuthMiddleware.isExternRequest()) {
                HttpResponse.sendJson(out, 403, "{\"ok\":false,\"grund\":\"verboten\"}");
                return true;
            }
            JSONObject res = tempolimitMelden(body, System.currentTimeMillis());
            HttpResponse.sendJson(out, res.optBoolean("ok") ? 200 : 400, res.toString());
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
        else if (path.equals("/api/navi/fuehrung")) op = "fuehrung";
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
        final boolean fuehrung = "fuehrung".equals(op);
        // 0.1.38.10 Handy (Kopplung, Recht „route“ prüft ExternGuard): die Position setzt das Auto selbst ein, eine
        // Position des Handys zählt nie; danach wie immer gerundet (umkreisBody)
        if (umkreis && AuthMiddleware.isExternRequest()) {
            body = mitAutoPosition(body, autoPosition());
            if (body == null) {
                HttpResponse.sendJson(out, 409, new JSONObject().put("ok", false).put("grund", "keine_position")
                        .put("error", Messages.text("planer.navi.keine_position", "The car has no current position.")).toString());
                return true;
            }
        }
        if (umkreis || fuehrung) {
            long warte = umkreis ? at.greenchili.surfmate.planer.PlanerBackoff.UMKREIS.warteMs(System.currentTimeMillis())
                    : at.greenchili.surfmate.planer.PlanerBackoff.FUEHRUNG.warteMs(System.currentTimeMillis());
            if (warte > 0) {
                HttpResponse.sendJson(out, 503, (fuehrung ? offlineFuehrung(warte) : offline(warte)).toString());
                return true;
            }
        }
        // 0.1.39 G2: Führung (fuehrung/1) nur mit Schlüssel – ohne ihn bleibt die Position im Auto.
        final String[] kopf = "plan".equals(op) || fuehrung ? kopfQuelle.get() : null;
        if (fuehrung && kopf == null) {
            JSONObject o = error(Messages.text("planer.navi.fuehrung_schluessel",
                    "Guidance needs the connection to the route planner (member key)."));
            o.put("code", "nur_mitglieder");
            HttpResponse.sendJson(out, 401, o.toString());
            return true;
        }

        HttpURLConnection c = null;
        try {
            // 0.1.9.1 (Ladenetze/-karten nur aus dem Planer-Konto): Beim Planen nie ein leeres
            // filter.bevorzugt schicken (sonst überstimmt es die Konto-Vorlieben), und den Schlüssel mitgeben,
            // damit der Planer die Vorlieben selbst aus dem Konto nimmt. Lehnt er den Schlüssel ab (401, z. B.
            // Geräte-Typ ohne plan-Recht), einmal ohne Schlüssel planen – wie bisher, nur ohne Vorlieben.
            String sendBody = "plan".equals(op) ? at.greenchili.surfmate.planer.FahrzeugWahl.einsetzen(planBody(body))
                    : fuehrung ? fuehrungBody(body)
                    : umkreis ? umkreisBody(body) : body;   // Kern 5: fahrzeug immer; umkreis nur gerundet
            c = verbinde(op, sendBody, kopf);
            int code = c.getResponseCode();
            if (!fuehrung) {
                at.greenchili.surfmate.einrichtung.Selbsttest.planerAntwort(kopf != null, code, System.currentTimeMillis());
            }
            if (code == 401 && kopf != null) {
                if (!fuehrung) {   // Führung ohne Schlüssel gibt es nicht: kein zweiter Versuch
                    c.disconnect();
                    c = verbinde(op, sendBody, null);
                    code = c.getResponseCode();
                }
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
            if (fuehrung) {
                if (code == 429 || code == 503) {
                    // Vertrag fuehrung/1 §9: Retry-After beachten, bei 503 nicht schneller als 30 s neu fragen.
                    at.greenchili.surfmate.planer.PlanerBackoff.FUEHRUNG.pause(System.currentTimeMillis(),
                            fuehrungPauseMs(code, c.getHeaderField("Retry-After")));
                } else if (code < 500) {
                    at.greenchili.surfmate.planer.PlanerBackoff.FUEHRUNG.erfolg();
                }
                if (code == 200 && !FUEHRUNG_VERTRAG.equals(vertragVon(text))) {
                    logger.warn("Planer: Führung mit unbekanntem Vertrag");
                    HttpResponse.sendJson(out, 502, error(Messages.text("planer.navi.fuehrung_vertrag",
                            "The route planner answered in an unknown format – please update Panoptes.")).toString());
                    return true;
                }
                HttpResponse.sendJson(out, fuehrungStatus(code), mitFuehrungCode(code, text));
                return true;
            }
            if (umkreis && code == 200 && guenstigsteWunsch(body) && !AuthMiddleware.isExternRequest()) {
                text = mitEigenenPreisen(text);
            }
            HttpResponse.sendJson(out, code == 200 ? 200 : (code == 429 ? 429 : 502), text);
        } catch (Exception e) {
            if (fuehrung && at.greenchili.surfmate.planer.PlanerBackoff.istNetzfehler(e)) {
                long now = System.currentTimeMillis();
                if (at.greenchili.surfmate.planer.PlanerBackoff.FUEHRUNG.fehler(now)) {
                    logger.warn("Planer nicht erreichbar (fuehrung): " + e.getClass().getSimpleName()
                            + " – neue Versuche mit Pause");
                }
                HttpResponse.sendJson(out, 503,
                        offlineFuehrung(at.greenchili.surfmate.planer.PlanerBackoff.FUEHRUNG.warteMs(now)).toString());
                return true;
            }
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

    /** Vertragskennung der Führungs-Antwort (FUEHRUNG_VERTRAG.md). */
    static final String FUEHRUNG_VERTRAG = "fuehrung/1";
    /** Pause nach 503 ohne bzw. mit zu kurzem Retry-After (Vertrag §9: nicht schneller als 30 s). */
    static final long FUEHRUNG_PAUSE_503_MS = 30_000L;
    /** Pause nach 429 ohne Retry-After (nginx je Adresse, Vertrag §5: 10 s). */
    static final long FUEHRUNG_PAUSE_429_MS = 10_000L;
    /** Obergrenze für ein Retry-After des Planers. */
    static final long FUEHRUNG_PAUSE_MAX_MS = 10 * 60_000L;

    /** Schlüssel-Kopf für plan/fuehrung (eine Quelle: PlanerSchluessel); im Test austauschbar. */
    static java.util.function.Supplier<String[]> kopfQuelle = at.greenchili.surfmate.planer.PlanerSchluessel::kopf;
    /** Basisadresse; im Test austauschbar (lokaler Prüfserver). */
    static java.util.function.Supplier<String> basisQuelle = NaviApiHandler::baseUrlAusConfig;

    /**
     * Body für {@code /ev/api/fuehrung}: Route und Punkte unverändert (die Führung braucht die genaue Position);
     * nur ein {@code laden}-Objekt wird genau wie ein Plan-Body aufbereitet (Vorlieben aus dem Auto, Fahrzeug).
     */
    /**
     * Rein bis auf {@link at.greenchili.surfmate.navmap.nav.TempolimitStand}: {@code {kmh: 5..200}} setzt das belegte Limit
     * (Quelle osm), {@code {kmh: null}} oder ohne Feld löscht es; alles andere ist ungültig und ändert nichts.
     */
    static JSONObject tempolimitMelden(String body, long jetztMs) throws Exception {
        try {
            JSONObject j = new JSONObject(body == null || body.isEmpty() || body.length() > 256 ? "{}" : body);
            Object v = j.opt("kmh");
            if (v == null || v == JSONObject.NULL) {
                at.greenchili.surfmate.navmap.nav.TempolimitStand.loeschen();
                return new JSONObject().put("ok", true);
            }
            if (v instanceof Number) {
                double d = ((Number) v).doubleValue();
                if (d == Math.rint(d) && d >= at.greenchili.surfmate.navmap.nav.Tempolimits.MIN_KMH
                        && d <= at.greenchili.surfmate.navmap.nav.Tempolimits.MAX_KMH) {
                    at.greenchili.surfmate.navmap.nav.TempolimitStand.setze((int) d,
                            at.greenchili.surfmate.navmap.nav.TempolimitStand.QUELLE_OSM, jetztMs);
                    return new JSONObject().put("ok", true);
                }
            }
            return new JSONObject().put("ok", false).put("grund", "werte");
        } catch (Exception e) {
            return new JSONObject().put("ok", false).put("grund", "json");
        }
    }

    static String fuehrungBody(String body) {
        try {
            JSONObject b = new JSONObject(body);
            JSONObject laden = b.optJSONObject("laden");
            if (laden == null) return body;
            b.put("laden", new JSONObject(at.greenchili.surfmate.planer.FahrzeugWahl.einsetzen(planBody(laden.toString()))));
            return b.toString();
        } catch (Exception e) {
            return body;
        }
    }

    /** Pause nach 429/503 aus {@code Retry-After} (Sekunden); ohne: 429 10 s; 503 mindestens 30 s; höchstens 10 min. */
    static long fuehrungPauseMs(int code, String retryAfter) {
        long ms = 0;
        try {
            if (retryAfter != null) ms = Long.parseLong(retryAfter.trim()) * 1000L;
        } catch (NumberFormatException ignored) { }
        if (ms <= 0) ms = FUEHRUNG_PAUSE_429_MS;
        if (code == 503) ms = Math.max(ms, FUEHRUNG_PAUSE_503_MS);
        return Math.min(ms, FUEHRUNG_PAUSE_MAX_MS);
    }

    /** HTTP-Code an navmap: Antworten des Planers mit Bedeutung bleiben, alles andere ist 502. */
    static int fuehrungStatus(int code) {
        switch (code) {
            case 200: case 400: case 401: case 422: case 429: case 503: return code;
            default: return 502;
        }
    }

    /**
     * Fehlerantworten der Führung tragen immer einen {@code code} (Vertrag §5), auch wenn nginx HTML lieferte oder der
     * Körper fehlte – damit navmap 401/429/503 sicher unterscheidet.
     */
    static String mitFuehrungCode(int httpCode, String text) {
        if (httpCode == 200) return text;
        try {
            JSONObject o = new JSONObject(text);
            if (o.optString("code", "").isEmpty()) {
                String c = httpCode == 401 ? "nur_mitglieder" : httpCode == 429 ? "zu_viele"
                        : httpCode == 503 ? "routing_weg" : httpCode == 422 ? "kein_weg" : httpCode == 400 ? "werte"
                        : "planer";
                o.put("code", c);
                if (httpCode == 401) o.put("fehler", Messages.text("planer.navi.fuehrung_schluessel",
                        "Guidance needs the connection to the route planner (member key)."));
            }
            return o.toString();
        } catch (Exception e) {
            return text;
        }
    }

    static String vertragVon(String text) {
        try {
            return new JSONObject(text).optString("vertrag", "");
        } catch (Exception e) {
            return "";
        }
    }

    private static String baseUrl() {
        return basisQuelle.get();
    }

    private static String baseUrlAusConfig() {
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
        c.setReadTimeout(lesezeitMs(op, body)); // lange Strecken rechnet der Planer > 1 min
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

    /** Lesezeit je Abfrage: Plan und Führung mit Laden wie der Planer (> 1 min), Führung ohne Laden 40 s. */
    static int lesezeitMs(String op, String body) {
        if ("plan".equals(op)) return 150_000;
        if ("fuehrung".equals(op)) {
            try {
                return new JSONObject(body).optJSONObject("laden") != null ? 150_000 : 40_000;
            } catch (Exception e) {
                return 40_000;
            }
        }
        return 20_000;
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
    /** Eigene Position des Autos (höchstens 10 min alt) oder null. */
    static double[] autoPosition() {
        try {
            at.greenchili.surfmate.monitor.GpsMonitor g = at.greenchili.surfmate.monitor.GpsMonitor.getInstance();
            long age = System.currentTimeMillis() - g.getLastUpdate();
            if (g.getLastUpdate() <= 0 || age < 0 || age > 10 * 60_000L) return null;
            return new double[] {g.getLatitude(), g.getLongitude()};
        } catch (Throwable t) {
            return null;
        }
    }

    /** Anfrage einer Kopplung: lat/lon (und lng) des Handys verwerfen, die des Autos einsetzen; null ohne Position. */
    static String mitAutoPosition(String body, double[] pos) {
        if (pos == null) return null;
        try {
            JSONObject b = new JSONObject(body);
            b.remove("lng");
            return b.put("lat", pos[0]).put("lon", pos[1]).toString();
        } catch (Exception e) {
            return null;
        }
    }

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

    /** Wie {@link #offline}, dazu {@code code: routing_weg} und {@code warte_ms} (Vertrag fuehrung/1 §5). */
    static JSONObject offlineFuehrung(long warteMs) {
        JSONObject o = offline(warteMs);
        try {
            o.put("code", "routing_weg");
            o.put("warte_ms", Math.max(0L, warteMs));
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
