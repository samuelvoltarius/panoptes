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
            JSONObject letzt = zustand(false);
            senden(out, "zustand", letzt.toString());
            long letzterPing = System.currentTimeMillis();
            long letzterZustand = letzterPing;
            while (!abo.geschlossen()) {
                String[] e = abo.naechstes(TAKT_MS);
                if (abo.geschlossen()) break;
                long now = System.currentTimeMillis();
                if (kid != null && !Kopplungen.get().pruefe(kid, now)) break;   // widerrufen → zu
                if (e != null && e[0] != null) senden(out, e[0], e[1]);
                JSONObject neu = zustand(false);
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
