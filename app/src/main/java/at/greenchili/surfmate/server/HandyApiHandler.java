package at.greenchili.surfmate.server;

import at.greenchili.surfmate.auth.Kopplungen;
import at.greenchili.surfmate.byd.BydDataCollector;
import at.greenchili.surfmate.byd.routing.CloudPowerGate;
import at.greenchili.surfmate.byd.routing.VehicleCommandRouter;
import at.greenchili.surfmate.handy.HandyRatenlimit;
import at.greenchili.surfmate.handy.HandySignatur;

import org.json.JSONObject;

import java.io.OutputStream;
import java.util.Base64;

/**
 * 0.1.18: Handy-Rechte {@code /api/handy/v1/*} nach HANDY_API_VERTRAG.md (Handy-App-Repository, docs).
 *
 * <ul>
 *   <li>Nur für Kopplungen der Art {@code handy} (ExternGuard lässt genau diese Pfade nur dafür durch; hier noch
 *       einmal geprüft). Rechte je Kopplung: klima, zusperren, aufsperren (Standard aus), push.</li>
 *   <li>Alle Fahrzeugbefehle über den bestehenden {@link VehicleCommandRouter} (localOnlyWhenAwake,
 *       CloudPowerGate, Echo) – keine Doppelung.</li>
 *   <li>Aufsperren (27.10.: „es soll aufsperren können“): nur mit gültiger Biometrie-Signatur des
 *       hinterlegten Geräteschlüssels (P-256, ±30 s, Nonce), Recht „aufsperren“, Ratenlimit, und nur über
 *       {@link VehicleCommandRouter.HandyUnlockCommand} (Cloud hinter CloudPowerGate). Telegram bei JEDEM Versuch.</li>
 * </ul>
 * Antwort immer 200 mit {@code {ok:true, weg, bestaetigt}} bzw. {@code {ok:false, grund}}; 403/405/409 nur für
 * Zugriff, Methode bzw. „schon gemeldet“.
 */
public final class HandyApiHandler {

    /** Fahrzeug, Gate, Echo, Meldungen und Uhr – im Test ersetzt. */
    interface Umgebung {
        VehicleCommandRouter.CommandResult ausfuehren(VehicleCommandRouter.VehicleCommand cmd);
        /** Gate vorab nur lesen, damit die App die Kennung (cloud_gate_*) bekommt; verbindlich prüft der Router. */
        CloudPowerGate.Decision gate(String befehl);
        /** Echo nach dem Befehl: BydDataCollector.ECHO_OK | ECHO_AUS | ECHO_UNBEKANNT. */
        int echo(String aktion);
        void telegram(String key, Object... args);
        String pushAnlegen(String endpoint, byte[] p256dh, byte[] auth, String label);
        void pushEntfernen(String id);
        long jetzt();
    }

    static volatile Umgebung umgebung = new Echt();
    static volatile Kopplungen kopplungen;
    static final HandySignatur SIGNATUR = new HandySignatur();
    static final HandyRatenlimit LIMIT = new HandyRatenlimit();

    private HandyApiHandler() {}

    private static Kopplungen k() {
        Kopplungen x = kopplungen;
        return x != null ? x : Kopplungen.get();
    }

    public static boolean handle(String method, String path, String body, OutputStream out, String kopplungId)
            throws Exception {
        String p = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if (!p.startsWith("/api/handy/v1/")) return false;
        if (!"POST".equals(method)) {
            HttpResponse.sendJson(out, 405, fehler("nur_post").toString());
            return true;
        }
        if (kopplungId == null || !k().istHandy(kopplungId)) {
            HttpResponse.sendJson(out, 403, fehler("nur_handy").toString());
            return true;
        }
        JSONObject req;
        try {
            req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
        } catch (Exception e) {
            HttpResponse.sendJson(out, 400, fehler("json").toString());
            return true;
        }
        switch (p) {
            case "/api/handy/v1/geraet":
                geraet(req, out, kopplungId);
                return true;
            case "/api/handy/v1/aktion":
                HttpResponse.sendJson(out, aktion(req, kopplungId).toString());
                return true;
            case "/api/handy/v1/push":
                HttpResponse.sendJson(out, push(req, kopplungId).toString());
                return true;
            case "/api/handy/v1/push/aus":
                HttpResponse.sendJson(out, pushAus(kopplungId).toString());
                return true;
            case "/api/handy/v1/websitzung":
                websitzung(req, out, kopplungId);
                return true;
            case "/api/handy/v1/bruecke":
                bruecke(req, out, kopplungId);
                return true;
            case "/api/handy/v1/bruecke/aus":
                at.greenchili.surfmate.bruecke.Bruecke.get().handyEntfernen(kopplungId);
                HttpResponse.sendJson(out, new JSONObject().put("ok", true).toString());
                return true;
            default:
                HttpResponse.sendJson(out, 404, fehler("unbekannt").toString());
                return true;
        }
    }

    // ------------------------------------------------------------------ §2 Geräteschlüssel

    static void geraet(JSONObject req, OutputStream out, String kid) throws Exception {
        String schluessel = req.optString("oeffentlicherSchluessel", "");
        if (HandySignatur.schluessel(schluessel) == null) {
            HttpResponse.sendJson(out, 400, fehler("schluessel").toString());
            return;
        }
        int r = k().schluesselMelden(kid, schluessel, req.optString("plattform", "android"),
                req.optString("name", ""));
        if (r == Kopplungen.SCHLUESSEL_SCHON) {
            HttpResponse.sendJson(out, 409, fehler("schon_gemeldet").toString());
            return;
        }
        if (r != Kopplungen.SCHLUESSEL_GESPEICHERT) {
            HttpResponse.sendJson(out, 500, fehler("speichern").toString());
            return;
        }
        // damit ein fremder Eintrag auffällt
        umgebung.telegram("handy.schluessel", k().anzeigeName(kid));
        HttpResponse.sendJson(out, new JSONObject().put("ok", true).toString());
    }

    // ------------------------------------------------------------------ 0.1.20 Web-Sitzung (Stufe B)

    private static final java.util.Map<String, Long> WEB_GEMELDET = new java.util.HashMap<>();

    /**
     * Einmal-Code (60 s) für {@code /auth/web}; nur mit Recht „web“, Ratenlimit je Kopplung. Optional
     * {@code {"ziel":"/trips.html"}} – geprüft wird beim Einlösen ({@code WebSitzung.ziel}: nur Pfade dieses Panoptes).
     */
    static void websitzung(JSONObject req, OutputStream out, String kid) throws Exception {
        if (!k().hatRecht(kid, Kopplungen.RECHT_WEB)) {
            HttpResponse.sendJson(out, 403, fehler("nicht_erlaubt").toString());
            return;
        }
        long jetzt = System.currentTimeMillis();
        String code = at.greenchili.surfmate.auth.WebSitzung.get().erzeugen(kid, jetzt);
        if (code == null) {
            HttpResponse.sendJson(out, 429, fehler("ratenlimit").toString());
            return;
        }
        // Protokoll: einmal am Tag je Handy
        synchronized (WEB_GEMELDET) {
            Long zuletzt = WEB_GEMELDET.get(kid);
            if (zuletzt == null || jetzt - zuletzt > 24L * 3600_000L) {
                WEB_GEMELDET.put(kid, jetzt);
                at.greenchili.surfmate.logging.DaemonLogger.getInstance("HandyWeb").info("Handy „" + k().anzeigeName(kid) + "“ hat die Weboberfläche geöffnet");
            }
        }
        JSONObject r = new JSONObject().put("ok", true)
                .put("url", "/auth/web?einmal=" + code + (req.optString("ziel", "").isEmpty() ? ""
                        : "&ziel=" + java.net.URLEncoder.encode(req.optString("ziel"), "UTF-8")))
                .put("gueltigS", at.greenchili.surfmate.auth.WebSitzung.GUELTIG_CODE_MS / 1000)
                .put("sitzungS", at.greenchili.surfmate.auth.WebSitzung.GUELTIG_JWT_S);
        HttpResponse.sendJson(out, r.toString());
    }

    // ------------------------------------------------------------------ 0.1.20 Brücke (BRUECKE_VERTRAG §2.4)

    /** {@code {h_pub, g_handy}} (je 32 Byte base64url): Handy-Schlüssel dieser Kopplung + Server-Zugang. */
    static void bruecke(JSONObject req, OutputStream out, String kid) throws Exception {
        at.greenchili.surfmate.bruecke.Bruecke b = at.greenchili.surfmate.bruecke.Bruecke.get();
        at.greenchili.surfmate.bruecke.Bruecke.HandyErgebnis e = b.handyAnmelden(kid, req.optString("h_pub", ""), req.optString("g_handy", ""));
        switch (e) {
            case OK:
                JSONObject r = new JSONObject().put("ok", true);
                JSONObject block = b.kopplungsBlock();
                if (block != null) r.put("bruecke", block);
                HttpResponse.sendJson(out, r.toString());
                return;
            case AUS:
                HttpResponse.sendJson(out, 409, fehler("bruecke_aus").toString());
                return;
            case UNGUELTIG:
                HttpResponse.sendJson(out, 400, fehler("schluessel").toString());
                return;
            case SERVER_NICHT_ERREICHBAR:
                HttpResponse.sendJson(out, 503, fehler("server_nicht_erreichbar").toString());
                return;
            default:
                HttpResponse.sendJson(out, 502, fehler(e.name().toLowerCase(java.util.Locale.ROOT)).toString());
        }
    }

    // ------------------------------------------------------------------ §3 Aktionen

    static JSONObject aktion(JSONObject req, String kid) throws Exception {
        String aktion = req.optString("aktion", "");
        Umgebung u = umgebung;
        long jetzt = u.jetzt();
        boolean aufsperren = "aufsperren".equals(aktion);
        String recht;
        switch (aktion) {
            case "klima_an":
            case "klima_aus":
                recht = Kopplungen.RECHT_KLIMA;
                break;
            case "zusperren":
                recht = Kopplungen.RECHT_ZUSPERREN;
                break;
            case "aufsperren":
                recht = Kopplungen.RECHT_AUFSPERREN;
                break;
            default:
                return fehler("aktion");
        }
        if (!k().hatRecht(kid, recht)) {
            if (aufsperren) u.telegram("handy.aufsperren_abgelehnt", k().anzeigeName(kid), "nicht_erlaubt");
            return fehler("nicht_erlaubt");
        }
        if (!LIMIT.erlauben(kid, aufsperren, jetzt)) {
            if (aufsperren) u.telegram("handy.aufsperren_abgelehnt", k().anzeigeName(kid), "ratenlimit");
            return fehler("ratenlimit");
        }
        switch (aktion) {
            case "klima_an": {
                double t = req.optDouble("tempC", Double.NaN);
                int min = req.optInt("minuten", 20);
                if (Double.isNaN(t) || t < 17 || t > 33 || t != Math.rint(t) || min < 5 || min > 30) {
                    return fehler("werte");
                }
                return ergebnis(u, aktion, new VehicleCommandRouter.ClimateOnCommand(t, min));
            }
            case "klima_aus":
                return ergebnis(u, aktion, new VehicleCommandRouter.ClimateOffCommand());
            case "zusperren": {
                VehicleCommandRouter.LockCommand cmd = new VehicleCommandRouter.LockCommand();
                CloudPowerGate.Decision d = u.gate(cmd.name());
                if (!d.allowed) return gateFehler(d);
                return ergebnis(u, aktion, cmd);
            }
            default:
                return aufsperren(req, kid, u, jetzt);
        }
    }

    static JSONObject aufsperren(JSONObject req, String kid, Umgebung u, long jetzt) throws Exception {
        String name = k().anzeigeName(kid);
        String schluessel = k().schluessel(kid);
        if (schluessel == null) {
            u.telegram("handy.aufsperren_abgelehnt", name, HandySignatur.GRUND_KEIN_GERAET);
            return fehler(HandySignatur.GRUND_KEIN_GERAET);
        }
        JSONObject s = req.optJSONObject("signatur");
        HandySignatur.Ergebnis e = s == null
                ? null
                : SIGNATUR.pruefeAufsperren(schluessel, kid, s.optLong("zeit", -1), s.optString("nonce", ""),
                        s.optString("sig", ""), name, jetzt);
        if (e == null || e.grund != null) {
            String g = e == null ? HandySignatur.GRUND_SIGNATUR : e.grund;
            u.telegram("handy.aufsperren_abgelehnt", name, g);
            return fehler(g);
        }
        VehicleCommandRouter.HandyUnlockCommand cmd = new VehicleCommandRouter.HandyUnlockCommand(e.freigabe);
        CloudPowerGate.Decision d = u.gate(cmd.name());
        if (!d.allowed) {
            u.telegram("handy.aufsperren_abgelehnt", name, d.reasonKey);
            return gateFehler(d);
        }
        JSONObject r = ergebnis(u, "aufsperren", cmd);
        if (r.optBoolean("ok")) {
            u.telegram(r.optBoolean("bestaetigt") ? "handy.aufsperren_ok" : "handy.aufsperren_unbestaetigt", name);
        } else {
            u.telegram("handy.aufsperren_abgelehnt", name, r.optString("grund"));
        }
        return r;
    }

    /** Router ausführen und das Ergebnis vertragsgemäß abbilden; Erfolg ohne Echo = bestaetigt:false. */
    static JSONObject ergebnis(Umgebung u, String aktion, VehicleCommandRouter.VehicleCommand cmd) throws Exception {
        VehicleCommandRouter.CommandResult r = u.ausfuehren(cmd);
        if (r == null) return fehler("fehlgeschlagen");
        switch (r.outcome) {
            case SUCCESS: {
                String weg = r.path == VehicleCommandRouter.Path.SDK
                        || r.path == VehicleCommandRouter.Path.CLOUD_THEN_SDK ? "lokal" : "cloud";
                boolean bestaetigt = u.echo(aktion) == BydDataCollector.ECHO_OK;
                return new JSONObject().put("ok", true).put("weg", weg).put("bestaetigt", bestaetigt);
            }
            case BLOCKED_DRIVING:
                return fehler("gate");
            case RATE_LIMITED:
                return fehler("ratenlimit_cloud");
            case AUTH_REQUIRED:
                // 0.1.20 P5: Auto aus + keine BYD-Cloud → eigener Grund samt Klartext (Cockpit/Handy zeigen ihn an)
                if (VehicleCommandRouter.CommandResult.GRUND_CLOUD_NICHT_EINGERICHTET.equals(r.grund)) {
                    return fehler(VehicleCommandRouter.CommandResult.GRUND_CLOUD_NICHT_EINGERICHTET)
                            .put("text", r.displayMessage).put("einrichten", "/byd-cloud.html");
                }
                return fehler("kein_cloud_konto");
            case NOT_SUPPORTED:
                return fehler("nicht_unterstuetzt");
            case VEHICLE_UNREACHABLE:
                return fehler("cloud_nicht_erreichbar");   // BYD-Cloud, nicht „Auto offline“
            default:
                return fehler("fehlgeschlagen");
        }
    }

    private static JSONObject gateFehler(CloudPowerGate.Decision d) throws Exception {
        String key = d.reasonKey == null ? CloudPowerGate.REASON_STATE_UNKNOWN : d.reasonKey;
        JSONObject f = fehler(key);
        // „Auto läuft“ ist für die App der häufigste Fall (Vertrag: auto_laeuft)
        if (CloudPowerGate.REASON_CAR_ON.equals(key)) f.put("grund", "auto_laeuft").put("gate", key);
        return f;
    }

    // ------------------------------------------------------------------ §4 Push

    static JSONObject push(JSONObject req, String kid) throws Exception {
        if (!k().hatRecht(kid, Kopplungen.RECHT_PUSH)) return fehler("nicht_erlaubt");
        String endpoint = req.optString("endpoint", "");
        if (!endpoint.startsWith("https://") || endpoint.length() > 1000) return fehler("endpoint");
        byte[] p256dh, auth;
        try {
            p256dh = Base64.getUrlDecoder().decode(ohnePadding(req.optString("p256dh", "")));
            auth = Base64.getUrlDecoder().decode(ohnePadding(req.optString("auth", "")));
        } catch (Exception e) {
            return fehler("schluessel");
        }
        if (p256dh.length != 65 || auth.length != 16) return fehler("schluessel");
        Umgebung u = umgebung;
        String alt = k().pushId(kid);
        if (alt != null) u.pushEntfernen(alt);   // ein Abo je Kopplung
        String label = req.optString("label", "Handy-App");
        String id = u.pushAnlegen(endpoint, p256dh, auth, label.length() > 40 ? label.substring(0, 40) : label);
        if (id == null || !k().pushSetzen(kid, id)) return fehler("speichern");
        return new JSONObject().put("ok", true);
    }

    static JSONObject pushAus(String kid) throws Exception {
        String alt = k().pushId(kid);
        if (alt != null) umgebung.pushEntfernen(alt);
        k().pushSetzen(kid, null);
        return new JSONObject().put("ok", true);
    }

    private static String ohnePadding(String s) {
        return s.replace("=", "").trim();
    }

    static JSONObject fehler(String grund) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false).put("grund", grund);
        } catch (Exception ignored) { }
        return o;
    }

    // ------------------------------------------------------------------ echte Umgebung

    static final class Echt implements Umgebung {
        public VehicleCommandRouter.CommandResult ausfuehren(VehicleCommandRouter.VehicleCommand cmd) {
            return VehicleCommandRouter.getInstance().execute(cmd);
        }

        public CloudPowerGate.Decision gate(String befehl) {
            return CloudPowerGate.evaluateLive(befehl);
        }

        public int echo(String aktion) {
            BydDataCollector c = BydDataCollector.getInstance();
            switch (aktion) {
                case "klima_an":
                    return BydDataCollector.warteAufEcho(c::readAcPowerNow, 1, 2_000L, 250L);
                case "klima_aus":
                    return BydDataCollector.warteAufEcho(c::readAcPowerNow, 0, 2_000L, 250L);
                case "zusperren":
                    return BydDataCollector.warteAufEcho(() -> schloss(c), 1, 15_000L, 500L);
                case "aufsperren":
                    return BydDataCollector.warteAufEcho(() -> schloss(c), 0, 15_000L, 500L);
                default:
                    return BydDataCollector.ECHO_UNBEKANNT;
            }
        }

        /** 1 = zu, 0 = offen, −1 = nicht lesbar. */
        private static int schloss(BydDataCollector c) {
            int s = c.readDoorLockState();
            return s == BydDataCollector.DOOR_STATE_LOCK ? 1 : s == BydDataCollector.DOOR_STATE_UNLOCK ? 0 : -1;
        }

        public void telegram(String key, Object... args) {
            try {
                at.greenchili.surfmate.telegram.TelegramNotifier.sendMessage(
                        at.greenchili.surfmate.telegram.TelegramMessages.get(key, args), "CRITICAL");
            } catch (Throwable ignored) { }
        }

        public String pushAnlegen(String endpoint, byte[] p256dh, byte[] auth, String label) {
            return NotificationApiHandler.handyAboAnlegen(endpoint, p256dh, auth, label);
        }

        public void pushEntfernen(String id) {
            NotificationApiHandler.handyAboEntfernen(id);
        }

        public long jetzt() {
            return System.currentTimeMillis();
        }
    }
}
