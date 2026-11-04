package at.greenchili.surfmate.server;

import at.greenchili.surfmate.launcher.AppLauncher;
import at.greenchili.surfmate.launcher.AppSperre;
import at.greenchili.surfmate.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;

/**
 * Installed-app enumeration + launch, shared by the automation "open app"
 * action and the key-mapping app picker.
 *
 * <ul>
 *   <li>GET  /api/apps/list   — {@code {success, apps:[{package,label}]}} launchable apps, sorted by label.</li>
 *   <li>GET  /api/apps/capabilities — fail-closed split-screen capability.</li>
 *   <li>POST /api/apps/launch — body {@code {package, split?, secondaryPackage?}} →
 *       launch one app, or a deterministic two-app split pair when both
 *       {@code split=true} and {@code secondaryPackage} are supplied. This is the endpoint
 *       an automation {@code ApiAction} and the keymap {@code openApp} action target
 *       (allowlisted in {@link HttpServer}).</li>
 * </ul>
 */
public final class AppsApiHandler {
    private static final DaemonLogger logger = DaemonLogger.getInstance("AppsApiHandler");

    private AppsApiHandler() {}

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        int q = path.indexOf('?');
        String cleanPath = q >= 0 ? path.substring(0, q) : path;

        if (cleanPath.equals("/api/apps/list") && method.equals("GET")) {
            handleList(out);
            return true;
        }
        if (cleanPath.equals("/api/apps/capabilities") && method.equals("GET")) {
            JSONObject resp = new JSONObject();
            resp.put("success", true);
            resp.put("splitScreen", AppLauncher.isSplitScreenSupported());
            HttpResponse.sendJson(out, resp.toString());
            return true;
        }
        if (cleanPath.equals("/api/apps/launch") && method.equals("POST")) {
            handleLaunch(out, body);
            return true;
        }
        // 0.1.37 Apps deaktivieren/aktivieren (nur Nutzer-Apps, harte Sperrliste in AppSperre, Rückfrage per Marke)
        if (cleanPath.equals(VERWALTUNG) && method.equals("GET")) {
            handleVerwaltung(out);
            return true;
        }
        if (cleanPath.equals(VERWALTUNG + "/frage") && method.equals("POST")) {
            handleFrage(out, body);
            return true;
        }
        if (cleanPath.equals(VERWALTUNG + "/deaktivieren") && method.equals("POST")) {
            handleDeaktivieren(out, body);
            return true;
        }
        if (cleanPath.equals(VERWALTUNG + "/aktivieren") && method.equals("POST")) {
            handleAktivieren(out, body);
            return true;
        }
        return false;
    }

    /** Pfad der App-Verwaltung (nicht in der Automations-Erlaubnisliste, für Handy-Web gesperrt). */
    public static final String VERWALTUNG = "/api/apps/verwaltung";
    static final Rueckfrage RUECKFRAGE = new Rueckfrage();
    static volatile AppSperre.Shell shell = AppSperre.ECHT;
    /** Bewegungs-Grund ({@code DrivingSafetyGuard}); Tests ersetzen ihn. Deaktivieren nur im Stand. */
    interface Stand {
        String bewegungsGrund();
    }

    static volatile Stand stand = () -> {
        try {
            return at.greenchili.surfmate.byd.routing.DrivingSafetyGuard.getMovementBlockReason();
        } catch (Throwable t) {
            return "state_unknown";
        }
    };

    private static void handleVerwaltung(OutputStream out) throws Exception {
        AppSperre.Lage l = AppSperre.lese(shell);
        java.util.Map<String, String> namen = new java.util.HashMap<>();
        try {
            JSONArray alle = AppLauncher.listLaunchableApps();
            for (int i = 0; i < alle.length(); i++) {
                JSONObject a = alle.getJSONObject(i);
                namen.put(a.optString("package"), a.optString("label"));
            }
        } catch (Throwable ignored) {
        }
        JSONArray apps = new JSONArray();
        for (String pkg : l.nutzerApps) {
            String grund = AppSperre.grundGegenDeaktivieren(pkg, l.nutzerApps, l.startseite, l.tastatur);
            boolean aus = l.deaktiviert.contains(pkg);
            if (grund != null && !aus) continue;   // gesperrte Apps gar nicht anbieten
            String label = namen.get(pkg);
            apps.put(new JSONObject().put("package", pkg).put("label", label == null || label.isEmpty() ? pkg : label)
                    .put("deaktiviert", aus));
        }
        HttpResponse.sendJson(out, new JSONObject().put("success", true).put("apps", apps).toString());
    }

    private static String paket(String body) {
        try {
            return new JSONObject(body == null || body.isEmpty() ? "{}" : body).optString("package", "").trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** Rückfrage: Marke für genau diese App, gilt 2 Minuten, einmal. */
    private static void handleFrage(OutputStream out, String body) throws Exception {
        String pkg = paket(body);
        AppSperre.Lage l = AppSperre.lese(shell);
        String g = AppSperre.grundGegenDeaktivieren(pkg, l.nutzerApps, l.startseite, l.tastatur);
        if (g == null && stand.bewegungsGrund() != null) g = "nicht_in_p";
        if (g != null) {
            HttpResponse.sendJson(out, 409, new JSONObject().put("success", false).put("grund", g).toString());
            return;
        }
        String marke = RUECKFRAGE.frage("deaktivieren", pkg, System.currentTimeMillis());
        HttpResponse.sendJson(out, new JSONObject().put("success", true).put("marke", marke).toString());
    }

    private static void handleDeaktivieren(OutputStream out, String body) throws Exception {
        String pkg = paket(body);
        String marke;
        try {
            marke = new JSONObject(body == null || body.isEmpty() ? "{}" : body).optString("marke", "");
        } catch (Exception e) {
            marke = "";
        }
        String g;
        if (!RUECKFRAGE.bestaetigt(marke, "deaktivieren", pkg, System.currentTimeMillis())) g = "rueckfrage";
        else if (stand.bewegungsGrund() != null) g = "nicht_in_p";
        else g = AppSperre.deaktivieren(shell, pkg);
        BefehlsLog.ereignis("apps.deaktivieren", g == null ? "ok" : g, ziel(pkg), pkg);
        logger.info("apps/deaktivieren " + pkg + " -> " + (g == null ? "ok" : g));
        if (g != null) {
            HttpResponse.sendJson(out, 409, new JSONObject().put("success", false).put("grund", g).toString());
            return;
        }
        HttpResponse.sendJson(out, new JSONObject().put("success", true).toString());
    }

    private static void handleAktivieren(OutputStream out, String body) throws Exception {
        String pkg = paket(body);
        String g = AppSperre.aktivieren(shell, pkg);
        BefehlsLog.ereignis("apps.aktivieren", g == null ? "ok" : g, ziel(pkg), pkg);
        logger.info("apps/aktivieren " + pkg + " -> " + (g == null ? "ok" : g));
        if (g != null) {
            HttpResponse.sendJson(out, 409, new JSONObject().put("success", false).put("grund", g).toString());
            return;
        }
        HttpResponse.sendJson(out, new JSONObject().put("success", true).toString());
    }

    /** Kurze Kennung fürs Befehls-Log (Paketnamen enthalten Punkte, die das Log als Ziel nicht nimmt). */
    static String ziel(String pkg) {
        if (!AppSperre.gueltigerName(pkg)) return null;
        return "app-" + pkg.replace('.', '_').substring(0, Math.min(60, pkg.length()));
    }

    private static void handleList(OutputStream out) throws Exception {
        JSONObject resp = new JSONObject();
        try {
            JSONArray apps = AppLauncher.listLaunchableApps();
            resp.put("success", true);
            resp.put("apps", apps);
        } catch (Throwable t) {
            logger.warn("apps/list failed: " + t.getMessage());
            resp.put("success", false);
            resp.put("error", t.getMessage());
            resp.put("apps", new JSONArray());
        }
        HttpResponse.sendJson(out, resp.toString());
    }

    private static void handleLaunch(OutputStream out, String body) throws Exception {
        JSONObject resp = new JSONObject();
        try {
            JSONObject req = new JSONObject(body == null ? "{}" : body);
            String pkg = req.optString("package", null);
            if (pkg == null || pkg.trim().isEmpty()) {
                resp.put("success", false);
                resp.put("error", "Missing package");
                HttpResponse.sendJson(out, resp.toString());
                return;
            }
            // Optional split-screen dock: {"package":..,"split":true}. Defaults to
            // false (normal full-screen launch) so existing callers are unaffected.
            boolean split = req.optBoolean("split", false);
            boolean pairRequested = req.has("secondaryPackage");
            String secondaryPkg = req.optString("secondaryPackage", null);
            if (pairRequested && (secondaryPkg == null || secondaryPkg.trim().isEmpty())) {
                resp.put("success", false);
                resp.put("error", "Missing secondaryPackage");
                HttpResponse.sendJson(out, resp.toString());
                return;
            }
            if (pairRequested && !split) {
                resp.put("success", false);
                resp.put("error", "secondaryPackage requires split=true");
                HttpResponse.sendJson(out, resp.toString());
                return;
            }
            if (pairRequested && pkg.trim().equals(secondaryPkg.trim())) {
                resp.put("success", false);
                resp.put("error", "Split-screen apps must be different");
                HttpResponse.sendJson(out, resp.toString());
                return;
            }
            if (pairRequested && !AppLauncher.isSplitScreenSupported()) {
                resp.put("success", false);
                resp.put("error", "split_screen_not_supported");
                HttpResponse.sendJson(out, resp.toString());
                return;
            }
            int paar = pairRequested ? AppLauncher.oeffnePaar(pkg, secondaryPkg) : AppLauncher.PAAR_FEHLER;
            boolean ok = pairRequested
                    ? paar == AppLauncher.PAAR_OK
                    : AppLauncher.launch(pkg, split);
            resp.put("success", ok);
            if (!ok) {
                resp.put("error", pairRequested
                        ? "Could not launch split-screen app pair"
                        : "Could not launch " + pkg);
                // 0.1.38.8: Startkomponente fehlt, Bildschirm unberührt → Aufrufer darf sicher ins Vollbild ausweichen
                if (paar == AppLauncher.PAAR_NICHT_AUFGELOEST) resp.put("grund", "nicht_aufloesbar");
            }
        } catch (Throwable t) {
            logger.warn("apps/launch failed: " + t.getMessage());
            resp.put("success", false);
            resp.put("error", t.getMessage());
        }
        HttpResponse.sendJson(out, resp.toString());
    }
}
