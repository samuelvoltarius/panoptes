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

        /**
         * 0.1.21 Sitzheizung: Befehl wie {@code /api/vehicle/seat} – je Sitz lokal; der Cloud-Sammelbefehl bekommt nur
         * einen frischen, vollständigen Sitzzustand als Grundlage (sonst prüft der Router selbst). Ohne Snapshot: Nullen.
         */
        default VehicleCommandRouter.VehicleCommand sitzheizung(int position, int stufe) {
            return new VehicleCommandRouter.SeatHeatCommand(position, stufe,
                    position == 1 ? stufe : 0, 0, position == 2 ? stufe : 0, 0, true, 0L);
        }

        /** Echo der Sitzheizung (live gelesen): ECHO_OK, wenn der Sitz die Stufe meldet. */
        default int echoSitz(int position, int stufe) {
            return BydDataCollector.ECHO_UNBEKANNT;
        }

        /**
         * 0.1.25 Wallbox (Recht „wallbox“): {@code {ladepunkt?, aktion:"modus"|"strom", wert}} über den eng begrenzten
         * Weg des Wallbox-Dienstes (Herkunft „handy“). Antwort {@code {status, json}} wie {@code WallboxDienst.Ergebnis}.
         */
        /** 0.1.25 Route ins Auto schicken (Recht „route“): sofort {ok, status:"plant"} oder {ok:false, grund}. */
        default JSONObject route(String kid, JSONObject req, long jetzt) throws Exception {
            return at.greenchili.surfmate.planer.HandyRoute.get().starten(kid, req, jetzt);
        }

        /** 0.1.25 Stand der letzten Handy-Route dieser Kopplung. */
        default JSONObject routeStand(String kid) throws Exception {
            return at.greenchili.surfmate.planer.HandyRoute.get().stand(kid);
        }

        default JSONObject[] wallbox(JSONObject befehl) throws Exception {
            at.greenchili.surfmate.wallbox.WallboxDienst.Ergebnis e =
                    at.greenchili.surfmate.server.WallboxApiHandler.dienst().steuernHandy(befehl);
            return new JSONObject[]{new JSONObject().put("status", e.status), e.json};
        }
    }

    static volatile Umgebung umgebung = new Echt();
    /** 0.1.26.1 Update von außen; im Test ersetzt. */
    static volatile at.greenchili.surfmate.appupdate.FernUpdate fernUpdate;

    private static at.greenchili.surfmate.appupdate.FernUpdate update() {
        at.greenchili.surfmate.appupdate.FernUpdate f = fernUpdate;
        return f != null ? f : at.greenchili.surfmate.appupdate.FernUpdate.get();
    }
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
        // 0.1.26.1 Update-Stand lesen (HANDY_API_VERTRAG §8): einziger GET-Pfad, jede Handy-Kopplung; „darf“ = Recht
        if ("/api/handy/v1/update".equals(p) && "GET".equals(method)) {
            if (kopplungId == null || !k().istHandy(kopplungId)) {
                HttpResponse.sendJson(out, 403, fehler("nur_handy").toString());
                return true;
            }
            String q = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
            HttpResponse.sendJson(out, update().stand(EinstellungenApiHandler.sprache(q.replace("lang=", "sprache=")),
                    k().hatRecht(kopplungId, Kopplungen.RECHT_UPDATE)).toString());
            return true;
        }
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
            case "/api/handy/v1/gewohnheit": {
                // 0.1.23 Gewohnheiten E3: ohne „antwort“ die Karte, mit „antwort“ beantworten (wie /api/gewohnheiten/antwort)
                at.greenchili.surfmate.gewohnheiten.Gewohnheiten g = at.greenchili.surfmate.gewohnheiten.Gewohnheiten.get();
                JSONObject r = req.has("antwort") ? g.angebot().antworte(req) : GewohnheitenApiHandler.karte();
                HttpResponse.sendJson(out, r.toString());
                return true;
            }
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
            case "sitzheizung":   // 0.1.21: gleiches Recht wie das Klima
            case "akku_heizen":   // 0.1.26: Akku vorheizen, gleiches Recht wie das Klima
            case "heizen_stoppen":   // 0.1.26: Klima UND Akku aus, ohne Rückfrage (Stoppen ist sicher)
                recht = Kopplungen.RECHT_KLIMA;
                break;
            case "zusperren":
                recht = Kopplungen.RECHT_ZUSPERREN;
                break;
            case "aufsperren":
                recht = Kopplungen.RECHT_AUFSPERREN;
                break;
            case "wallbox":   // 0.1.25: Start/Stop/Ladestrom, Standard AUS
                recht = Kopplungen.RECHT_WALLBOX;
                break;
            case "route":     // 0.1.25: Ziel ins Auto schicken (nur Anzeige), Standard AN
            case "route_stand":
                recht = Kopplungen.RECHT_ROUTE;
                break;
            case "update_starten":   // 0.1.26.1: nur die vom Updater freigegebene Version, nur geparkt, Standard AUS
                recht = Kopplungen.RECHT_UPDATE;
                break;
            default:
                return fehler("aktion");
        }
        if (!k().hatRecht(kid, recht)) {
            if (aufsperren) u.telegram("handy.aufsperren_abgelehnt", k().anzeigeName(kid), "nicht_erlaubt");
            return fehler("nicht_erlaubt");
        }
        // 0.1.25: Stand der Handy-Route nur lesen – zählt nicht zum Ratenlimit (die App fragt nach)
        if ("route_stand".equals(aktion)) return u.routeStand(kid);
        if (!LIMIT.erlauben(kid, aufsperren, jetzt)) {
            if (aufsperren) u.telegram("handy.aufsperren_abgelehnt", k().anzeigeName(kid), "ratenlimit");
            return fehler("ratenlimit");
        }
        switch (aktion) {
            case "klima_an": {
                // 0.1.26: stufe 1–17 wie im Display (bevorzugt) oder tempC in der SDK-Skala 17–33
                double t = at.greenchili.surfmate.byd.KlimaStufe.sdkAusAnfrage(req, "tempC", Double.NaN);
                int min = req.optInt("minuten", 20);
                if (Double.isNaN(t) || min < 5 || min > 30) {
                    return fehler("werte");
                }
                return ergebnis(u, aktion, new VehicleCommandRouter.ClimateOnCommand(t, min));
            }
            case "klima_aus":
                return ergebnis(u, aktion, new VehicleCommandRouter.ClimateOffCommand());
            case "sitzheizung":
                return sitzheizung(req, u);
            case "akku_heizen":
                return akkuHeizen(req, u);
            case "heizen_stoppen":
                return heizenStoppen(u);
            case "wallbox":
                return wallbox(req, kid, u);
            case "update_starten":
                return update().starten(req.optString("version", null), req.optBoolean("bestaetigt", false),
                        k().anzeigeName(kid), EinstellungenApiHandler.sprache("sprache=" + req.optString("sprache", "")));
            case "route":
                // Weg A (Koordination 28.10.): planen ab Auto-Position, ins Planer-Postfach; Karte am Display bleibt der
                // einzige Weg ins Navi – nichts startet von selbst
                return u.route(kid, req, jetzt);
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

    /**
     * 0.1.25 {@code {aktion:"wallbox", was:"start"|"stop"|"strom", strom?:int, ladepunkt?}} (Recht „wallbox“):
     * Start = Modus „sofort“, Stop = Modus „aus“, Ladestrom nur in den erlaubten Stufen. Nicht während der Fahrt, nur ein
     * freigegebener Ladepunkt, Erfolg nur mit Echo ({@code bestaetigt}). Jede Änderung von außen meldet Telegram.
     */
    static JSONObject wallbox(JSONObject req, String kid, Umgebung u) throws Exception {
        String was = req.optString("was", "");
        JSONObject b = new JSONObject();
        if (!req.optString("ladepunkt", "").isEmpty()) b.put("ladepunkt", req.optString("ladepunkt"));
        switch (was) {
            case "start":
                b.put("aktion", "modus").put("wert", at.greenchili.surfmate.wallbox.WallboxQuelle.MODUS_SOFORT);
                break;
            case "stop":
                b.put("aktion", "modus").put("wert", at.greenchili.surfmate.wallbox.WallboxQuelle.MODUS_AUS);
                break;
            case "strom": {
                Object s = req.opt("strom");
                if (!(s instanceof Integer)) return fehler("werte");
                b.put("aktion", "strom").put("wert", s);
                break;
            }
            default:
                return fehler("werte");
        }
        JSONObject[] e = u.wallbox(b);
        JSONObject r = e[1];
        if (!r.optBoolean("ok", false)) {
            JSONObject f = fehler(r.optString("grund", "fehlgeschlagen"));
            return f.put("was", was);
        }
        boolean bestaetigt = r.optBoolean("uebernommen", false);
        u.telegram("handy.wallbox", k().anzeigeName(kid), was + ("strom".equals(was) ? " " + b.get("wert") + " A" : ""));
        JSONObject o = new JSONObject().put("ok", true).put("was", was).put("bestaetigt", bestaetigt);
        if ("strom".equals(was)) o.put("strom", b.get("wert"));
        return o;
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

    /**
     * 0.1.21 {@code {aktion:"sitzheizung", sitz:"fahrer"|"beifahrer", stufe:0|1|2}}: über den Router wie das Klima
     * (wach → nur lokal je Sitz; Auto aus → BYD-Cloud nur über CloudPowerGate). Sperrt das Gate den Cloud-Weg, kommt
     * dessen Grund (z. B. {@code auto_laeuft}) statt eines allgemeinen „gate“.
     */
    static JSONObject sitzheizung(JSONObject req, Umgebung u) throws Exception {
        String sitz = req.optString("sitz", "");
        int position = "fahrer".equals(sitz) ? 1 : "beifahrer".equals(sitz) ? 2 : 0;
        Object roh = req.opt("stufe");
        int stufe = roh instanceof Integer || roh instanceof Long ? ((Number) roh).intValue() : -1;
        if (position == 0 || stufe < 0 || stufe > 2) return fehler("werte");
        VehicleCommandRouter.VehicleCommand cmd = u.sitzheizung(position, stufe);
        VehicleCommandRouter.CommandResult r = u.ausfuehren(cmd);
        if (r != null && r.outcome == VehicleCommandRouter.Outcome.BLOCKED_DRIVING) {
            CloudPowerGate.Decision d = u.gate(cmd.name());
            if (d != null && !d.allowed) return gateFehler(d).put("sitz", sitz).put("stufe", stufe);
        }
        JSONObject o = abbilden(r, () -> u.echoSitz(position, stufe));
        return o.put("sitz", sitz).put("stufe", stufe);
    }

    /**
     * 0.1.26 {@code {aktion:"akku_heizen", an:true|false}}: wie {@code /api/vehicle/battery-heat} – nur über die
     * BYD-Cloud (CLOUD_ONLY, also immer durch das CloudPowerGate). Es gibt keine lokale Rückmeldung, daher
     * {@code bestaetigt:false}; den Zustand liest die App aus {@code batteryHeat} in {@code /api/vehicle/state}.
     */
    static JSONObject akkuHeizen(JSONObject req, Umgebung u) throws Exception {
        Object an = req.opt("an");
        if (!(an instanceof Boolean)) return fehler("werte");
        return mitGate(u, "akku_heizen", new VehicleCommandRouter.BatteryHeatCommand((Boolean) an)).put("an", an);
    }

    /**
     * 0.1.26 {@code {aktion:"heizen_stoppen"}}: erst Klima aus, dann Akku heizen aus – immer beide, auch wenn der
     * Zustand unbekannt ist. Cloud nur wie sonst über Router + CloudPowerGate (bei Auto aus erlaubt). Antwort
     * {@code {ok, klima:{…}, akku:{…}}}; {@code ok}, sobald einer der beiden Befehle durchging, sonst der Grund des
     * Klimas (samt {@code einrichten}).
     */
    static JSONObject heizenStoppen(Umgebung u) throws Exception {
        JSONObject klima = mitGate(u, "klima_aus", new VehicleCommandRouter.ClimateOffCommand());
        JSONObject akku = mitGate(u, "akku_heizen", new VehicleCommandRouter.BatteryHeatCommand(false));
        JSONObject o = klima.optBoolean("ok") || akku.optBoolean("ok")
                ? new JSONObject().put("ok", true)
                : new JSONObject(klima.toString());
        return o.put("klima", klima).put("akku", akku);
    }

    /** Router ausführen; sperrt das Gate den Cloud-Weg, dessen Grund (z. B. auto_laeuft) statt „gate“. */
    private static JSONObject mitGate(Umgebung u, String aktion, VehicleCommandRouter.VehicleCommand cmd)
            throws Exception {
        VehicleCommandRouter.CommandResult r = u.ausfuehren(cmd);
        if (r != null && r.outcome == VehicleCommandRouter.Outcome.BLOCKED_DRIVING) {
            CloudPowerGate.Decision d = u.gate(cmd.name());
            if (d != null && !d.allowed) return gateFehler(d);
        }
        return abbilden(r, () -> u.echo(aktion));
    }

    /** Router ausführen und das Ergebnis vertragsgemäß abbilden; Erfolg ohne Echo = bestaetigt:false. */
    static JSONObject ergebnis(Umgebung u, String aktion, VehicleCommandRouter.VehicleCommand cmd) throws Exception {
        return abbilden(u.ausfuehren(cmd), () -> u.echo(aktion));
    }

    private static JSONObject abbilden(VehicleCommandRouter.CommandResult r, java.util.function.IntSupplier echo)
            throws Exception {
        if (r == null) return fehler("fehlgeschlagen");
        switch (r.outcome) {
            case SUCCESS: {
                String weg = r.path == VehicleCommandRouter.Path.SDK
                        || r.path == VehicleCommandRouter.Path.CLOUD_THEN_SDK ? "lokal" : "cloud";
                boolean bestaetigt = echo.getAsInt() == BydDataCollector.ECHO_OK;
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

        @Override
        public VehicleCommandRouter.VehicleCommand sitzheizung(int position, int stufe) {
            // wie VehicleControlApiHandler (Sitz-Route): Cloud-Grundlage nur aus frischem, vollständigem Zustand
            at.greenchili.surfmate.byd.BydVehicleData snap = BydDataCollector.getInstance().getData();
            if (!VehicleCommandRouter.hasFreshCompleteSeatState(snap)) {
                return Umgebung.super.sitzheizung(position, stufe);
            }
            int[] z = {snap.seatHeat[0], snap.seatCool[0], snap.seatHeat[1], snap.seatCool[1]};
            z[position == 1 ? 0 : 2] = stufe;
            return new VehicleCommandRouter.SeatHeatCommand(position, stufe, z[0], z[1], z[2], z[3], true,
                    snap.seatClimateAtMs);
        }

        @Override
        public int echoSitz(int position, int stufe) {
            BydDataCollector c = BydDataCollector.getInstance();
            return BydDataCollector.warteAufEcho(() -> c.readSeatClimateNow(true, position), stufe, 2_000L, 250L);
        }
    }
}
