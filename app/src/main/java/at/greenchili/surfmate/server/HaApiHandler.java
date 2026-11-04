package at.greenchili.surfmate.server;

import at.greenchili.surfmate.auth.Kopplungen;
import at.greenchili.surfmate.automation.Automation;
import at.greenchili.surfmate.automation.Automations;
import at.greenchili.surfmate.config.UnifiedConfigManager;
import at.greenchili.surfmate.daemon.CameraDaemon;
import at.greenchili.surfmate.ha.HaEreignisse;
import at.greenchili.surfmate.ha.HaRegeln;
import at.greenchili.surfmate.ha.HaZustand;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Home-Assistant-API (HA_VERTRAG.md v0.1, Modus „Direkt“: gleiches WLAN oder Tailscale). Login oder Kopplung;
 * alle Pfade stehen ausdrücklich in {@link ExternGuard}.
 * <ul>
 *   <li>{@code GET /api/ha/v1/zustand[?position=1]} – Schnappschuss, nie VIN, Koordinaten nur mit ha.positionTeilen</li>
 *   <li>{@code GET /api/ha/v1/ereignisse} – SSE (zustand, waechter, ereignis, ankunft, abfahrt; ping 25 s)</li>
 *   <li>{@code GET /api/ha/v1/regeln} – Regeln mit ausloesbar</li>
 *   <li>{@code POST /api/ha/v1/aktion} – Erlaubnisliste waechter, ruhezeit, ziel, regel; sonst 400 unbekannt</li>
 * </ul>
 * Regel A: keine Fahrzeugbefehle. Eine Regel mit Fahrzeugaktion (oder im Zweifel) wird nie ausgelöst.
 */
public final class HaApiHandler {

    private HaApiHandler() {}

    static final long PING_MS = 25_000L;
    static final long TAKT_MS = 5_000L;
    /** Telemetrie-Änderungen höchstens so oft (Wächter-Wechsel sofort). */
    static final long ZUSTAND_MIN_MS = 15_000L;

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String pathOnly = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        String query = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
        if (pathOnly.equals("/api/ha/v1/zustand") && method.equals("GET")) {
            boolean pos = query.matches("(^|.*&)position=1(&.*|$)");
            HttpResponse.sendJson(out, zustand(pos).toString());
            return true;
        }
        if (pathOnly.equals("/api/ha/v1/regeln") && method.equals("GET")) {
            HttpResponse.sendJson(out, regeln().toString());
            return true;
        }
        if (pathOnly.equals("/api/ha/v1/aktion") && method.equals("POST")) {
            JSONObject[] r = aktion(body);
            HttpResponse.sendJson(out, r[1].optInt("_status", 200), entferneStatus(r[1]).toString());
            return true;
        }
        // 0.1.39 HA-Geräte (HA_VERTRAG v1.1 Teil D, Weg A). ExternGuard lässt nur homeassistant mit Recht „geraete“ durch.
        if (pathOnly.equals(ExternGuard.HA_GERAETE_PFAD) && (method.equals("GET") || method.equals("POST"))) {
            JSONObject r = geraete(method, body, AuthMiddleware.isExternRequest() ? AuthMiddleware.requestKopplungsId() : null,
                    at.greenchili.surfmate.ha.HaGeraeteSpeicher.get(), System.currentTimeMillis());
            HttpResponse.sendJson(out, r.optInt("_status", 200), entferneStatus(r).toString());
            return true;
        }
        // 0.1.38.8 Stufe 2: Echo der Integration auf einen Befehl (Recht geraete_steuern, ExternGuard + hier)
        if (pathOnly.equals(ExternGuard.HA_GERAETE_ECHO_PFAD) && method.equals("POST")) {
            JSONObject r = geraeteEcho(body, AuthMiddleware.isExternRequest() ? AuthMiddleware.requestKopplungsId() : null,
                    System.currentTimeMillis());
            HttpResponse.sendJson(out, r.optInt("_status", 200), entferneStatus(r).toString());
            return true;
        }
        if (pathOnly.equals("/api/ha/v1/ereignisse") && method.equals("GET")) {
            sse(out);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ zustand

    public static JSONObject zustand(boolean positionGewuenscht) {
        JSONObject z = new JSONObject();
        try {
            z.put("version", HaZustand.VERSION);
            JSONObject g = new JSONObject();
            g.put("autoId", nullSafe(EinstellungenApiHandler.autoId()));   // bleibt für Bestand
            g.put("haId", at.greenchili.surfmate.ha.HaMdns.kennung());   // 0.1.32.1: passt zur mDNS-Entdeckung
            g.put("name", nullSafe(geraetName()));
            // 0.1.20: Modell (Anzeigename, String wie im HA_VERTRAG) + Kennung + offizieller Lack, nur lesen
            JSONObject aussehen = ModelsApiHandler.fahrzeugAussehen();
            g.put("modell", aussehen != null ? aussehen.optString("name") : JSONObject.NULL);
            g.put("modellId", aussehen != null ? aussehen.optString("modelId") : JSONObject.NULL);
            g.put("lack", aussehen != null ? aussehen.opt("paint") : JSONObject.NULL);
            g.put("appVersion", at.greenchili.surfmate.BuildConfig.VERSION_NAME);
            g.put("dilink", JSONObject.NULL);
            z.put("geraet", g);
            JSONObject roh = null;
            try {
                at.greenchili.surfmate.mqtt.MqttConnectionManager m = SurveillanceIpcServer.mqttManager();
                roh = m == null ? null : m.getLatestTelemetry();
            } catch (Throwable ignored) { }
            JSONObject t = HaZustand.telemetrie(roh);
            z.put("telemetrie", t);
            z.put("felder", HaZustand.felder(t));
            JSONObject gs = HaZustand.gesundheit(at.greenchili.surfmate.gesundheit.GesundheitsDienst.status());
            boolean fehler = "fehler".equals(gs.optString("zustand", ""));
            JSONObject w = new JSONObject();
            String pz = null;
            try { pz = CameraDaemon.surveillanceZustand(); } catch (Throwable ignored) { }
            w.put("zustand", HaZustand.waechter(pz, fehler));
            w.put("grund", "unterdrueckt".equals(pz) ? "zone_oder_stopp" : JSONObject.NULL);
            w.put("seit", JSONObject.NULL);
            z.put("waechter", w);
            z.put("gesundheit", gs);
            z.put("f1", HaZustand.f1(at.greenchili.surfmate.reichweite.RangeWatchDienst.status()));
            long bis = at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig.ruheBis();
            boolean aktiv = bis > System.currentTimeMillis();
            z.put("ruhezeit", new JSONObject().put("aktiv", aktiv).put("bis", aktiv ? bis : JSONObject.NULL));
            z.put("position", positionGewuenscht && positionErlaubt() ? position() : JSONObject.NULL);
            // 0.1.16 Camping: nur Werte des Modus (keine Innentemperatur); Heizen in Stufe 2a nur Probelauf
            JSONObject cs = at.greenchili.surfmate.camping.Camping.status();
            JSONObject c = new JSONObject();
            c.put("aktiv", cs.optBoolean("aktiv", false));
            c.put("probelauf", cs.optBoolean("trockenlauf", true));
            c.put("phase", cs.optString("phase", "ende"));
            c.put("naechsterZyklus", cs.has("naechsterZyklusMs") ? cs.get("naechsterZyklusMs") : JSONObject.NULL);
            JSONObject b = cs.optJSONObject("budget");
            c.put("reichtBis", b != null && b.has("reichtBisMs") ? b.get("reichtBisMs") : JSONObject.NULL);
            z.put("camping", c);
            // 0.1.21: ob Panoptes BYD-Cloud-Zugangsdaten hat (nur ja/nein, nie Werte) – Cockpit/Handy blenden ihren Hinweis aus
            boolean cloud = false;
            try {
                cloud = at.greenchili.surfmate.byd.cloud.BydCloudConfig.fromUnifiedConfig().isConfigured();
            } catch (Throwable ignored) { }
            z.put("cloud", new JSONObject().put("eingerichtet", cloud));
        } catch (Exception ignored) { }
        return z;
    }

    private static Object nullSafe(Object o) { return o == null ? JSONObject.NULL : o; }

    private static String geraetName() {
        try {
            JSONObject ha = UnifiedConfigManager.loadConfig().optJSONObject("ha");
            String n = ha == null ? "" : ha.optString("name", "").trim();
            return n.isEmpty() ? "Panoptes" : n;
        } catch (Throwable t) {
            return "Panoptes";
        }
    }

    static boolean positionErlaubt() {
        try {
            JSONObject ha = UnifiedConfigManager.loadConfig().optJSONObject("ha");
            return ha != null && ha.optBoolean("positionTeilen", false);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 0.1.21 Telegram /wo (Koordination): Ort nur mit {@code ha.positionTeilen}. Ohne Freigabe werden Koordinaten und
     * Ortsname aus den Parkdaten entfernt ({@code positionTeilen:false}); mit Freigabe kommt die aktuelle Position als
     * Rückfall dazu ({@code jetzt}), falls Parken aus ist oder keine Sitzung hat.
     */
    static void woFreigabe(JSONObject r) throws Exception {
        woFreigabe(r, positionErlaubt(), HaApiHandler::position);
    }

    /** Rein (für Tests): ohne Freigabe Ort/Koordinaten entfernen, mit Freigabe die aktuelle Position anhängen. */
    static void woFreigabe(JSONObject r, boolean frei, java.util.function.Supplier<Object> position) throws Exception {
        r.put("positionTeilen", frei);
        if (!frei) {
            for (String k : new String[]{"current", "latest"}) {
                JSONObject s = r.optJSONObject(k);
                if (s == null) continue;
                s.remove("place");
                s.remove("safeZone");
                s.remove("signage");
                JSONObject g = s.optJSONObject("gps");
                if (g != null) { g.remove("lat"); g.remove("lng"); g.remove("lon"); }
            }
            return;
        }
        Object p = position.get();
        if (p instanceof JSONObject) r.put("jetzt", p);
    }

    private static Object position() {
        try {
            at.greenchili.surfmate.mqtt.MqttConnectionManager m = SurveillanceIpcServer.mqttManager();
            JSONObject roh = m == null ? null : m.getLatestTelemetry();
            if (roh == null || !roh.has("lat") || !roh.has("lon")) return JSONObject.NULL;
            return new JSONObject().put("lat", roh.getDouble("lat")).put("lon", roh.getDouble("lon"))
                    .put("genauigkeitM", JSONObject.NULL).put("zeit", System.currentTimeMillis());
        } catch (Exception e) {
            return JSONObject.NULL;
        }
    }

    // ------------------------------------------------------------------ regeln

    static JSONObject regeln() {
        JSONArray a = new JSONArray();
        try {
            for (Map.Entry<String, Automation> e : Automations.alleLesend().entrySet()) {
                Automation r = e.getValue();
                String g = HaRegeln.grund(r.getActions(), r.getElseActions());
                String name = r.getName().isEmpty() ? "Regel " + kurz(e.getKey()) : r.getName();
                a.put(new JSONObject().put("id", e.getKey()).put("name", name)
                        .put("ausloesbar", g == null).put("grund", g == null ? JSONObject.NULL : g));
            }
        } catch (Exception ignored) { }
        JSONObject o = new JSONObject();
        try { o.put("regeln", a); } catch (Exception ignored) { }
        return o;
    }

    private static String kurz(String id) { return id.length() > 8 ? id.substring(0, 8) : id; }

    // ------------------------------------------------------------------ aktion

    /**
     * Panoptes-Oberfläche (nur Login bzw. Autobildschirm; Kopplungen sperrt ExternGuard, Handy-Web WebHandyGuard):
     * <ul>
     *   <li>{@code GET /api/ha/geraete} – Liste mit Freigaben, Tor-Automatik, Zonen (id + Name), letzter Auftrag</li>
     *   <li>{@code POST /api/ha/geraete/freigabe {entity, steuern, bestaetigt}} – Steuern je Tor/Schloss (Rückfrage)</li>
     *   <li>{@code POST /api/ha/geraete/befehl {entity, aktion, bestaetigt}} – Öffnen/Aufsperren nur am Autobildschirm</li>
     *   <li>{@code GET /api/ha/geraete/befehl?id=b-…} – Stand (offen|ok|fehler|nicht_bestaetigt)</li>
     *   <li>{@code POST /api/ha/geraete/automatik {tor|null, zone, bestaetigt}} – Tor beim Heimkommen (Standard AUS)</li>
     * </ul>
     */
    public static boolean handleAnzeige(String method, String path, String body, OutputStream out) throws Exception {
        String pathOnly = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        String query = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
        boolean bekannt = "/api/ha/geraete".equals(pathOnly) || pathOnly.equals("/api/ha/geraete/freigabe")
                || pathOnly.equals("/api/ha/geraete/befehl") || pathOnly.equals("/api/ha/geraete/automatik");
        if (!bekannt) return false;
        if (AuthMiddleware.isExternRequest() || AuthMiddleware.isHandyWebRequest()) {   // zweite Sperre
            HttpResponse.sendError(out, 403, "Nicht für gekoppelte Geräte");
            return true;
        }
        JSONObject r = anzeige(method, pathOnly, query, body, AuthMiddleware.isDirektAmAuto(),
                at.greenchili.surfmate.ha.HaGeraeteSpeicher.get(), System.currentTimeMillis());
        if (r == null) return false;
        HttpResponse.sendJson(out, r.optInt("_status", 200), entferneStatus(r).toString());
        return true;
    }

    /** Ohne HTTP (für Tests). {@code amAuto} = Autobildschirm selbst (Loopback, kein Tunnel, kein Fern-Login). */
    static JSONObject anzeige(String method, String pathOnly, String query, String body, boolean amAuto,
                              at.greenchili.surfmate.ha.HaGeraeteSpeicher speicher, long jetzt) throws Exception {
        if ("/api/ha/geraete".equals(pathOnly) && "GET".equals(method)) {
            JSONObject o = at.greenchili.surfmate.ha.HaGeraete.fuerAnzeige(speicher.lies());
            JSONArray zonen = new JSONArray();
            try {
                for (at.greenchili.surfmate.surveillance.SafeLocation z
                        : at.greenchili.surfmate.surveillance.SafeLocationManager.getInstance().getZones()) {
                    if (z.isEnabled()) zonen.put(new JSONObject().put("id", z.getId()).put("name", z.getName()));
                }
            } catch (Throwable ignored) { }
            JSONObject b = speicher.lies();
            String kid = b == null ? null : b.optString("kid", null);
            JSONObject letzter = at.greenchili.surfmate.ha.HaSteuerDienst.steuern().letzter(jetzt);
            return o.put("zonen", zonen).put("amAuto", amAuto)
                    .put("steuerRecht", at.greenchili.surfmate.ha.HaSteuerDienst.steuerRecht(kid, jetzt))
                    .put("letzter", letzter == null ? JSONObject.NULL : letzter);
        }
        if ("/api/ha/geraete/befehl".equals(pathOnly) && "GET".equals(method)) {
            String id = query.replaceAll("(^|.*&)id=([^&]*).*", "$2");
            JSONObject st = at.greenchili.surfmate.ha.HaSteuerDienst.steuern().status(id, jetzt);
            return st != null ? st : new JSONObject().put("ok", false).put("grund", "unbekannt").put("_status", 404);
        }
        if (!"POST".equals(method)) return null;
        JSONObject req;
        try {
            req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
        } catch (Exception e) {
            return new JSONObject().put("ok", false).put("grund", "json").put("_status", 400);
        }
        // Jede Änderung und jeder Befehl nur nach der Rückfrage in der Oberfläche
        if (!req.optBoolean("bestaetigt", false)) {
            return new JSONObject().put("ok", false).put("grund", "bestaetigung").put("_status", 400);
        }
        JSONObject b = speicher.lies();
        String entity = req.isNull("entity") ? null : req.optString("entity", null);
        if ("/api/ha/geraete/freigabe".equals(pathOnly)) {
            boolean an = req.optBoolean("steuern", false);
            JSONObject neu = at.greenchili.surfmate.ha.HaGeraete.setzeFreigabe(b, entity, an);
            if (neu == null) return new JSONObject().put("ok", false).put("grund", "nicht_steuerbar").put("_status", 400);
            if (!speicher.schreibe(neu)) return new JSONObject().put("ok", false).put("grund", "speichern").put("_status", 500);
            BefehlsLog.ereignis("ha.freigabe", an ? "gesetzt" : "entfernt", at.greenchili.surfmate.ha.HaSteuern.kurzHash(entity), null);
            return new JSONObject().put("ok", true);
        }
        if ("/api/ha/geraete/automatik".equals(pathOnly)) {
            String tor = req.isNull("tor") ? null : req.optString("tor", null);
            JSONObject neu = at.greenchili.surfmate.ha.HaGeraete.setzeAutomatik(b, tor,
                    req.isNull("zone") ? null : req.optString("zone", null));
            if (neu == null) return new JSONObject().put("ok", false).put("grund", "ungueltig").put("_status", 400);
            if (!speicher.schreibe(neu)) return new JSONObject().put("ok", false).put("grund", "speichern").put("_status", 500);
            BefehlsLog.ereignis("ha.automatik", tor == null ? "entfernt" : "gesetzt",
                    tor == null ? null : at.greenchili.surfmate.ha.HaSteuern.kurzHash(tor), null);
            return new JSONObject().put("ok", true);
        }
        // befehl: Öffnen und Aufsperren machen das Haus auf – nur direkt am Autobildschirm, nie aus der Ferne
        String aktion = req.optString("aktion", "");
        if (at.greenchili.surfmate.ha.HaSteuern.oeffnet(aktion) && !amAuto) {
            return new JSONObject().put("ok", false).put("grund", "nur_am_auto").put("_status", 403);
        }
        JSONObject r = at.greenchili.surfmate.ha.HaSteuerDienst.auftrag(entity, aktion, amAuto ? "display" : "login", jetzt);
        if (!r.optBoolean("ok", false)) r.put("_status", "nicht_verbunden".equals(r.optString("grund")) ? 503 : 409);
        return r;
    }

    /** {@code POST /api/ha/v1/geraete/echo}: nur die HA-Kopplung mit beiden Rechten, nur ihr eigener Auftrag. */
    static JSONObject geraeteEcho(String body, String kid, long jetzt) {
        JSONObject o = new JSONObject();
        try {
            if (!at.greenchili.surfmate.ha.HaSteuerDienst.steuerRecht(kid, jetzt)) {
                JSONObject f = ExternGuard.rechtFehltAntwort(Kopplungen.RECHT_GERAETE_STEUERN);
                f.put("_status", 403);
                return f;
            }
            JSONObject e;
            try {
                e = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
            } catch (Exception x) {
                return o.put("ok", false).put("grund", "json").put("_status", 400);
            }
            JSONObject r = at.greenchili.surfmate.ha.HaSteuerDienst.steuern().echo(kid, e, jetzt);
            if (!r.optBoolean("ok", false)) r.put("_status", 409);
            return r;
        } catch (Exception e) {
            try { return o.put("ok", false).put("grund", "fehler").put("_status", 500); } catch (Exception ignored) { return o; }
        }
    }

    /**
     * {@code GET|POST /api/ha/v1/geraete}. Nur eine HA-Kopplung (kid, Art homeassistant, Recht geraete – zusätzlich
     * hier geprüft, falls der Pfad je anders erreicht wird). Antwort mit optional {@code _status}.
     */
    static JSONObject geraete(String method, String body, String kid, at.greenchili.surfmate.ha.HaGeraeteSpeicher speicher,
                              long jetzt) {
        JSONObject o = new JSONObject();
        try {
            if (kid == null || !"homeassistant".equals(Kopplungen.get().art(kid))
                    || !Kopplungen.get().hatRecht(kid, Kopplungen.RECHT_GERAETE)) {
                JSONObject f = ExternGuard.rechtFehltAntwort(Kopplungen.RECHT_GERAETE);
                f.put("_status", 403);
                return f;
            }
            if ("GET".equals(method)) {
                return at.greenchili.surfmate.ha.HaGeraete.fuerIntegration(speicher.lies(),
                        Kopplungen.get().hatRecht(kid, Kopplungen.RECHT_GERAETE_STEUERN));
            }
            at.greenchili.surfmate.ha.HaGeraete.Meldung m;
            try {
                m = at.greenchili.surfmate.ha.HaGeraete.pruefe(body);
            } catch (Exception e) {
                return o.put("ok", false).put("grund", "ungueltig").put("_status", 400);
            }
            JSONObject neu = at.greenchili.surfmate.ha.HaGeraete.zusammenfuehren(speicher.lies(), m, jetzt).put("kid", kid);
            if (!speicher.schreibe(neu)) return o.put("ok", false).put("grund", "speichern").put("_status", 500);
            // Befehls-Log: nur das Kennwort, nie Namen/Räume
            BefehlsLog.ereignis("ha.geraete", "gemeldet", null, null);
            return o.put("ok", true).put("angenommen", m.geraete.length()).put("verworfen", m.verworfen);
        } catch (Exception e) {
            try { return o.put("ok", false).put("grund", "fehler").put("_status", 500); } catch (Exception ignored) { return o; }
        }
    }

    /** {_, antwort mit optional _status}. Auch vom Webhook-Weg nutzbar. */
    public static JSONObject[] aktion(String body) {
        JSONObject res = new JSONObject();
        try {
            JSONObject req;
            try {
                req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
            } catch (Exception e) {
                return fehler(400, "json");
            }
            String art = req.optString("aktion", "");
            switch (art) {
                case "waechter": {
                    if (!req.has("an")) return fehler(400, "an");
                    boolean an = req.optBoolean("an", false);
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    SurveillanceApiHandler.waechterSetzen(an, b);
                    JSONObject r = antwortAus(b);
                    if (r == null || !r.optBoolean("success", false)) return fehler(500, "nicht_gespeichert");
                    res.put("ok", true).put("verschoben", r.optBoolean("deferred", false));
                    return new JSONObject[]{null, res};
                }
                case "ruhezeit": {
                    int min = req.optInt("minuten", -1);
                    if (min < 0 || min > 1440) return fehler(400, "minuten");
                    if (!at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig.setRuheFuer(min,
                            System.currentTimeMillis())) {
                        return fehler(500, "nicht_gespeichert");
                    }
                    res.put("ok", true);
                    return new JSONObject[]{null, res};
                }
                case "camping": {
                    // 0.1.16: Panoptes-Funktion (Stufe 2a ohne Heizwirkung)
                    if (!req.has("an")) return fehler(400, "an");
                    JSONObject r = req.optBoolean("an", false)
                            ? at.greenchili.surfmate.camping.Camping.ein("ha")
                            : at.greenchili.surfmate.camping.Camping.aus("ha");
                    if (!r.optBoolean("ok", false)) return fehler(409, r.optString("fehler", "abgelehnt"));
                    res.put("ok", true);
                    return new JSONObject[]{null, res};
                }
                case "camping_rueckmeldung": {
                    JSONObject r = at.greenchili.surfmate.camping.Camping.rueckmeldung(req.optString("art", ""));
                    if (!r.optBoolean("ok", false)) return fehler(400, r.optString("fehler", "art"));
                    res.put("ok", true).put("uebernommen", r.optBoolean("uebernommen", false));
                    return new JSONObject[]{null, res};
                }
                case "ziel":
                    // Planer-Postfach „nur Ziel“ ist noch nicht vereinbart; nie direkt ins Navi (Koordination).
                    return fehler(200, "nicht_verfuegbar");
                case "regel": {
                    String id = req.optString("id", "");
                    Automation r = id.isEmpty() ? null : Automations.alleLesend().get(id);
                    if (r == null) return fehler(404, "unbekannt");
                    String g = HaRegeln.grund(r.getActions(), r.getElseActions());
                    if (g != null) return fehler(403, g);
                    AutomationApiHandler.dispatchExplicitAutomation(id, false);
                    res.put("ok", true);
                    return new JSONObject[]{null, res};
                }
                default:
                    return fehler(400, "unbekannt");
            }
        } catch (Exception e) {
            return fehler(500, "fehler");
        }
    }

    private static JSONObject[] fehler(int status, String grund) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false).put("grund", grund);
            if (status != 200) o.put("_status", status);
        } catch (Exception ignored) { }
        return new JSONObject[]{null, o};
    }

    private static JSONObject entferneStatus(JSONObject o) {
        o.remove("_status");
        return o;
    }

    /** JSON-Rumpf einer mit HttpResponse geschriebenen Antwort. */
    static JSONObject antwortAus(ByteArrayOutputStream b) {
        try {
            String s = new String(b.toByteArray(), StandardCharsets.UTF_8);
            int i = s.indexOf("\r\n\r\n");
            return new JSONObject(i >= 0 ? s.substring(i + 4) : s);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ SSE

    /** 0.1.33: Alle Streams teilen einen Aufbau je Takt. Nur lesen, das Objekt gehört allen. */
    private static final KurzSpeicher<JSONObject> ZUSTAND = new KurzSpeicher<>(TAKT_MS - 500L);

    private static JSONObject zustandGeteilt() throws Exception {
        return ZUSTAND.hol(System.currentTimeMillis(), () -> zustand(false));
    }

    private static void sse(OutputStream out) throws Exception {
        String kid = AuthMiddleware.requestKopplungsId();
        HaEreignisse.Abo abo = HaEreignisse.abonnieren(kid);
        if (abo == null) {
            HttpResponse.sendJson(out, 429, "{\"ok\":false,\"grund\":\"zu_viele_streams\"}");
            return;
        }
        try {
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n"
                    + "Cache-Control: no-cache\r\nConnection: keep-alive\r\nX-Accel-Buffering: no\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            JSONObject letzt = zustandGeteilt();
            senden(out, "zustand", letzt.toString());
            long letzterPing = System.currentTimeMillis();
            long letzterZustand = letzterPing;
            while (!abo.geschlossen()) {
                String[] e = abo.naechstes(TAKT_MS);
                if (abo.geschlossen()) break;
                long now = System.currentTimeMillis();
                if (kid != null && !Kopplungen.get().pruefe(kid, now)) break;   // widerrufen → zu
                if (e != null && e[0] != null) senden(out, e[0], e[1]);
                JSONObject neu = zustandGeteilt();
                JSONObject d = HaZustand.diff(letzt, neu);
                boolean waechterWechsel = d.has("waechter");
                if (d.length() > 0 && (waechterWechsel || now - letzterZustand >= ZUSTAND_MIN_MS)) {
                    senden(out, "zustand", d.toString());
                    if (waechterWechsel) senden(out, "waechter", neu.optJSONObject("waechter").toString());
                    letzt = neu;
                    letzterZustand = now;
                }
                if (now - letzterPing >= PING_MS) {
                    out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    letzterPing = now;
                }
            }
        } catch (java.io.IOException gone) {
            // Gegenstelle weg
        } finally {
            HaEreignisse.abmelden(abo);
        }
    }

    private static void senden(OutputStream out, String event, String data) throws java.io.IOException {
        out.write(("event: " + event + "\ndata: " + data.replace("\n", " ") + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
