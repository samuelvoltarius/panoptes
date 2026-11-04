package at.greenchili.surfmate.server;

import at.greenchili.surfmate.appupdate.AppUpdateDienst;

import org.json.JSONObject;

import java.io.OutputStream;

/**
 * In-App-Updater (NEUBAU2_API_VORSCHLAG.md §1). Eigener Pfad, der alte Overdrive-Updater {@code /api/update/*}
 * bleibt unberührt.
 * <ul>
 *   <li>{@code GET /api/appupdate/status?sprache=de} – Login oder Kopplung (ExternGuard LESEN)</li>
 *   <li>{@code POST /api/appupdate/suchen} – nur Login; höchstens 1× je 60 s, sonst {@code {ok:false, grund:"ratenlimit"}}</li>
 *   <li>{@code POST /api/appupdate/installieren {versionCode}} – nur Login; prüft selbst Auto aus + keine Sperre</li>
 *   <li>{@code POST /api/appupdate/hinweis {aktion, version}} – 0.1.31, nur am Auto bzw. mit vollem Login: Knopf der
 *       Karte am Display („installieren“) und „zuruecknehmen“ unter System → Updates</li>
 *   <li>{@code GET/POST /api/appupdate/launcher} – 0.1.37, Panoptes Launcher: Stand; installieren/öffnen/entfernen
 *       nur am Auto bzw. mit vollem Login ({@link #launcherAktion})</li>
 * </ul>
 */
public final class AppUpdateApiHandler {

    private AppUpdateApiHandler() {}

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String pathOnly = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        String query = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
        // 0.1.21 Knopf „Autostart wieder einschalten“: nur am Auto (lokal, voller Login) – öffnet kurz den BYD-Dialog
        if (pathOnly.equals("/api/appupdate/autostart/freigeben") && method.equals("POST")) {
            if (AuthMiddleware.isExternRequest() || AuthMiddleware.isHandyWebRequest()) {
                HttpResponse.sendJson(out, 403, "{\"ok\":false,\"grund\":\"nur_am_auto\"}");
                return true;
            }
            JSONObject r = at.greenchili.surfmate.appupdate.AutostartNachUpdate.get().freigeben(System.currentTimeMillis());
            HttpResponse.sendJson(out, r.put("ok", true).toString());
            return true;
        }
        AppUpdateDienst d = AppUpdateDienst.get();
        if (d == null) {
            HttpResponse.sendJson(out, 503, "{\"ok\":false,\"grund\":\"nicht_bereit\"}");
            return true;
        }
        // ?sprache=de (Schema-Stil) oder ?lang=de (NEUBAU2-Vorschlag)
        String sprache = EinstellungenApiHandler.sprache(query.replace("lang=", "sprache="));
        if (pathOnly.equals("/api/appupdate/status") && method.equals("GET")) {
            JSONObject s = d.status(sprache);
            try {   // 0.1.21: Autostart nach dem Update (frei true/false + Zeit), nie ein Fehlergrund für den Status
                s.put("autostart", at.greenchili.surfmate.appupdate.AutostartNachUpdate.get().zustand(System.currentTimeMillis()));
            } catch (Exception ignored) { }
            HttpResponse.sendJson(out, s.toString());
            return true;
        }
        // 0.1.38.2 Updates von außen (UPDATE_API_CLIENTS.md): Panoptes UND Launcher, Telegram/Handy/Cockpit/Handy-Web
        if (pathOnly.equals(FERN_PFAD)) {
            return fern(method, body, sprache, out);
        }
        // 0.1.37 Stufe 4: Panoptes Launcher installieren / öffnen / entfernen (Knopf unter System → Updates)
        if (pathOnly.equals("/api/appupdate/launcher")) {
            if (method.equals("GET")) {
                HttpResponse.sendJson(out, d.launcherStatus().toString());
                return true;
            }
            if (!method.equals("POST")) return false;
            // 0.1.38: Kopplungen und Web-Sitzung (Recht „launcher“ prüfen ExternGuard/WebHandyGuard) nur „Öffnen“
            if ((AuthMiddleware.isExternRequest() || AuthMiddleware.isHandyWebRequest()) && !nurOeffnen(body)) {
                HttpResponse.sendJson(out, 403, "{\"ok\":false,\"grund\":\"nur_am_auto\"}");
                return true;
            }
            HttpResponse.sendJson(out, launcherAktion(d, body).toString());
            return true;
        }
        if (pathOnly.equals("/api/appupdate/hinweis") && method.equals("POST")) {
            if (AuthMiddleware.isExternRequest() || AuthMiddleware.isHandyWebRequest()) {
                HttpResponse.sendJson(out, 403, "{\"ok\":false,\"grund\":\"nur_am_auto\"}");
                return true;
            }
            JSONObject b;
            try {
                b = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
            } catch (Exception e) {
                b = new JSONObject();
            }
            String aktion = b.optString("aktion", "");
            if ("zuruecknehmen".equals(aktion)) {
                d.hinweisZuruecknehmen();
                HttpResponse.sendJson(out, "{\"ok\":true}");
            } else if ("installieren".equals(aktion)) {
                HttpResponse.sendJson(out, d.hinweisInstallieren(at.greenchili.surfmate.appupdate.FernUpdate.get(),
                        b.optString("version", null), sprache).toString());
            } else {
                HttpResponse.sendJson(out, 400, "{\"ok\":false,\"grund\":\"aktion\"}");
            }
            return true;
        }
        boolean schreibend = method.equals("POST")
                && (pathOnly.equals("/api/appupdate/suchen") || pathOnly.equals("/api/appupdate/installieren"));
        if (!schreibend) return false;
        if (AuthMiddleware.isExternRequest()) {   // zweite Sperre neben ExternGuard
            HttpResponse.sendJson(out, 403, "{\"ok\":false,\"grund\":\"nur_mit_login\"}");
            return true;
        }
        // 0.1.26.1 Web-Sitzung der Handy-App mit Recht „update“ (WebHandyGuard): dieselben Regeln wie das Handy
        if (AuthMiddleware.isHandyWebRequest() && pathOnly.equals("/api/appupdate/installieren")) {
            String kid = AuthMiddleware.requestWebKopplungsId();
            if (!at.greenchili.surfmate.auth.Kopplungen.get().hatRecht(kid, at.greenchili.surfmate.auth.Kopplungen.RECHT_UPDATE)) {
                HttpResponse.sendJson(out, 403, "{\"ok\":false,\"grund\":\"nicht_erlaubt\"}");
                return true;
            }
            JSONObject b;
            try {
                b = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
            } catch (Exception e) {
                b = new JSONObject();
            }
            HttpResponse.sendJson(out, at.greenchili.surfmate.appupdate.FernUpdate.get().starten(b.optString("version", null),
                    b.optBoolean("bestaetigt", false), b.optBoolean("mobil", false),
                    at.greenchili.surfmate.auth.Kopplungen.get().anzeigeName(kid), sprache).toString());
            return true;
        }
        if (pathOnly.equals("/api/appupdate/suchen")) {
            JSONObject s = d.suchen(sprache);
            HttpResponse.sendJson(out, s == null ? "{\"ok\":false,\"grund\":\"ratenlimit\"}" : s.toString());
            return true;
        }
        int vc;
        boolean mobil;
        try {
            JSONObject b = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
            vc = b.getInt("versionCode");
            mobil = b.optBoolean("mobil", false);
        } catch (Exception e) {
            HttpResponse.sendJson(out, 400, "{\"ok\":false,\"grund\":\"versionCode\"}");
            return true;
        }
        String grund = d.installieren(vc, mobil);
        HttpResponse.sendJson(out, grund == null ? "{\"ok\":true}"
                : new JSONObject().put("ok", false).put("grund", grund).toString());
        return true;
    }

    /** 0.1.38.2: ein Pfad für alle, die Updates von außen auslösen dürfen. */
    public static final String FERN_PFAD = "/api/appupdate/fern";

    /**
     * {@code GET} → {@code {ok, darf, panoptes:{…}, launcher:{…}}} (Felder wie {@code /api/handy/v1/update});
     * {@code POST {was: panoptes|launcher, version, bestaetigt:true, mobil}} → {@code {ok, zustand, vorgemerkt, text}}
     * oder {@code {ok:false, grund, text}} bzw. {@code {ok:false, grund:"wartet_wlan", mobilFrage:true, groesseBytes,
     * text}}. Wer es darf: voller Login (Telegram-Daemon, Anmeldung mit PIN, am Auto), Kopplung der Art
     * {@code handy}/{@code cockpit} bzw. Handy-Web mit Recht {@code update}. Lesen geht für sie auch ohne Recht
     * ({@code darf:false}). Home Assistant, Dienste und Automationen nie (ExternGuard).
     */
    static boolean fern(String method, String body, String sprache, OutputStream out) throws Exception {
        String kid = AuthMiddleware.isExternRequest() ? AuthMiddleware.requestKopplungsId()
                : AuthMiddleware.isHandyWebRequest() ? AuthMiddleware.requestWebKopplungsId() : null;
        boolean fremd = AuthMiddleware.isExternRequest() || AuthMiddleware.isHandyWebRequest();
        at.greenchili.surfmate.auth.Kopplungen k = at.greenchili.surfmate.auth.Kopplungen.get();
        if (fremd && (kid == null || !darfArt(k.art(kid)))) {
            HttpResponse.sendJson(out, 403, "{\"ok\":false,\"grund\":\"nicht_erlaubt\"}");
            return true;
        }
        boolean darf = !fremd || k.hatRecht(kid, at.greenchili.surfmate.auth.Kopplungen.RECHT_UPDATE);
        at.greenchili.surfmate.appupdate.FernUpdate f = at.greenchili.surfmate.appupdate.FernUpdate.get();
        if (method.equals("GET")) {
            HttpResponse.sendJson(out, new JSONObject().put("ok", true).put("darf", darf)
                    .put("panoptes", f.stand(sprache, darf)).put("launcher", f.launcherStand(sprache, darf)).toString());
            return true;
        }
        if (!method.equals("POST")) return false;
        JSONObject b;
        try {
            b = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
        } catch (Exception e) {
            b = new JSONObject();
        }
        String was = b.optString("was", "panoptes");
        if (!darf) {
            HttpResponse.sendJson(out, 403, new JSONObject().put("ok", false).put("grund", "nicht_erlaubt")
                    .put("text", at.greenchili.surfmate.appupdate.FernUpdate.text(sprache, "nicht_erlaubt", null, null))
                    .toString());
            return true;
        }
        if (!b.optBoolean("bestaetigt", false)) {
            HttpResponse.sendJson(out, new JSONObject().put("ok", false).put("grund", "bestaetigung_fehlt")
                    .put("text", at.greenchili.surfmate.appupdate.FernUpdate.text(sprache, "bestaetigung_fehlt", null, null))
                    .toString());
            return true;
        }
        String geraet = fremd ? k.anzeigeName(kid) : b.optString("geraet", "");
        if (!fremd && !"telegram".equals(geraet)) geraet = "";
        if ("telegram".equals(geraet)) geraet = "Telegram";
        if (geraet.isEmpty()) geraet = at.greenchili.surfmate.appupdate.UpdateHinweis.geraetName();
        String version = b.optString("version", null);
        boolean mobil = b.optBoolean("mobil", false);
        JSONObject r;
        if ("launcher".equals(was)) {
            r = f.launcherStarten(version, true, mobil, geraet, sprache);
        } else if ("panoptes".equals(was)) {
            AppUpdateDienst d = AppUpdateDienst.get();
            r = d.installierenOderVormerken(f, version, mobil, geraet, sprache);
        } else {
            HttpResponse.sendJson(out, 400, "{\"ok\":false,\"grund\":\"was\"}");
            return true;
        }
        BefehlsLog.ereignis("update.fern." + was, r.optBoolean("ok", false) ? "ok" : "abgelehnt", null, null);
        HttpResponse.sendJson(out, r.toString());
        return true;
    }

    /** Nur Handy und Cockpit (Handy-Web gehört zu einer Handy-Kopplung); Home Assistant und Dienste nie. */
    static boolean darfArt(String art) {
        return at.greenchili.surfmate.auth.Kopplungen.ART_HANDY.equals(art) || "cockpit".equals(art);
    }

    /** Aus der Ferne erlaubt: genau {@code aktion: oeffnen} (startet den Launcher am Bildschirm im Auto). */
    static boolean nurOeffnen(String body) {
        try {
            return "oeffnen".equals(new JSONObject(body == null || body.isEmpty() ? "{}" : body).optString("aktion", ""));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * {@code {aktion: installieren|oeffnen|entfernen, mobil}}. „Öffnen“ startet den Launcher nur als App; Panoptes
     * setzt ihn nie als Standard-Startbildschirm (das macht nur der Nutzer selbst in Android).
     */
    static JSONObject launcherAktion(AppUpdateDienst d, String body) throws Exception {
        JSONObject b;
        try {
            b = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
        } catch (Exception e) {
            b = new JSONObject();
        }
        String aktion = b.optString("aktion", "");
        at.greenchili.surfmate.appupdate.LauncherPaket.Geraet g = at.greenchili.surfmate.appupdate.LauncherPaket.SHELL;
        String grund;
        switch (aktion) {
            case "installieren": {
                at.greenchili.surfmate.appupdate.Freigabe.Launcher f = d.launcherFreigabe(true);
                grund = f == null ? at.greenchili.surfmate.appupdate.LauncherRegel.KEIN_LAUNCHER
                        : d.launcherInstallieren(f.versionCode, b.optBoolean("mobil", false));
                break;
            }
            case "oeffnen":
                grund = at.greenchili.surfmate.appupdate.LauncherPaket.stand(g).installiert()
                        ? at.greenchili.surfmate.appupdate.LauncherPaket.oeffnen(g) : "nicht_installiert";
                break;
            case "aktivieren":
                // Rückfall-Stufe 2 rückgängig (pm enable des Launcher-Pakets), ohne adb
                grund = at.greenchili.surfmate.appupdate.LauncherPaket.stand(g).installiert()
                        ? at.greenchili.surfmate.appupdate.LauncherHome.paketAn(g) : "nicht_installiert";
                break;
            case "entfernen":
                grund = at.greenchili.surfmate.appupdate.LauncherPaket.stand(g).installiert()
                        ? at.greenchili.surfmate.appupdate.LauncherPaket.entfernen(g) : null;
                break;
            default:
                return new JSONObject().put("ok", false).put("grund", "aktion");
        }
        BefehlsLog.ereignis("launcher." + aktion, grund == null ? "ok" : "abgelehnt", null, null);
        JSONObject r = d.launcherStatus().put("ok", grund == null);
        if (grund != null) r.put("grund", grund);
        return r;
    }
}
