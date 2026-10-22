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
 * </ul>
 */
public final class AppUpdateApiHandler {

    private AppUpdateApiHandler() {}

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String pathOnly = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        String query = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
        AppUpdateDienst d = AppUpdateDienst.get();
        if (d == null) {
            HttpResponse.sendJson(out, 503, "{\"ok\":false,\"grund\":\"nicht_bereit\"}");
            return true;
        }
        // ?sprache=de (Schema-Stil) oder ?lang=de (NEUBAU2-Vorschlag)
        String sprache = EinstellungenApiHandler.sprache(query.replace("lang=", "sprache="));
        if (pathOnly.equals("/api/appupdate/status") && method.equals("GET")) {
            HttpResponse.sendJson(out, d.status(sprache).toString());
            return true;
        }
        boolean schreibend = method.equals("POST")
                && (pathOnly.equals("/api/appupdate/suchen") || pathOnly.equals("/api/appupdate/installieren"));
        if (!schreibend) return false;
        if (AuthMiddleware.isExternRequest()) {   // zweite Sperre neben ExternGuard
            HttpResponse.sendJson(out, 403, "{\"ok\":false,\"grund\":\"nur_mit_login\"}");
            return true;
        }
        if (pathOnly.equals("/api/appupdate/suchen")) {
            JSONObject s = d.suchen(sprache);
            HttpResponse.sendJson(out, s == null ? "{\"ok\":false,\"grund\":\"ratenlimit\"}" : s.toString());
            return true;
        }
        int vc;
        try {
            vc = new JSONObject(body == null || body.isEmpty() ? "{}" : body).getInt("versionCode");
        } catch (Exception e) {
            HttpResponse.sendJson(out, 400, "{\"ok\":false,\"grund\":\"versionCode\"}");
            return true;
        }
        String grund = d.installieren(vc);
        HttpResponse.sendJson(out, grund == null ? "{\"ok\":true}"
                : new JSONObject().put("ok", false).put("grund", grund).toString());
        return true;
    }
}
