package at.greenchili.surfmate.server;

import at.greenchili.surfmate.bruecke.Bruecke;
import at.greenchili.surfmate.bruecke.BrueckeVerbindung;

import org.json.JSONObject;

import java.io.OutputStream;

/**
 * 0.1.20 Panoptes-Brücke (BRUECKE_VERTRAG.md):
 * <ul>
 *   <li>{@code GET /api/bruecke} – Status (ein, verbunden, Server, Postfach, öffentlicher Auto-Schlüssel, QR-Basis)</li>
 *   <li>{@code POST /api/bruecke {"ein":true|false, "server"?}} – Opt-in-Schalter</li>
 * </ul>
 * Beides nur mit Login bzw. im Auto, nie mit einem Kopplungs-JWT (ExternGuard lässt die Pfade nicht durch, hier
 * zusätzlich geprüft). Die Handy-Seite ({@code /api/handy/v1/bruecke}) liegt im {@link HandyApiHandler}.
 */
public final class BrueckeApiHandler {

    private BrueckeApiHandler() {}

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String p = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if (!p.equals("/api/bruecke")) return false;
        if (AuthMiddleware.isExternRequest()) {
            HttpResponse.sendJson(out, 403, HandyApiHandler.fehler("nur_login").toString());
            return true;
        }
        Bruecke b = Bruecke.get();
        if ("GET".equals(method)) {
            HttpResponse.sendJson(out, status(b).toString());
            return true;
        }
        if (!"POST".equals(method)) {
            HttpResponse.sendJson(out, 405, HandyApiHandler.fehler("methode").toString());
            return true;
        }
        JSONObject req;
        try {
            req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
        } catch (Exception e) {
            HttpResponse.sendJson(out, 400, HandyApiHandler.fehler("json").toString());
            return true;
        }
        if (req.has("server") && !b.serverSetzen(req.optString("server", ""))) {
            HttpResponse.sendJson(out, 400, HandyApiHandler.fehler("server").toString());
            return true;
        }
        if (req.has("ein")) {
            Bruecke.Ergebnis e;
            if (req.optBoolean("ein", false)) {
                e = b.einschalten();
                if (e == Bruecke.Ergebnis.OK) BrueckeVerbindung.starten();
            } else {
                BrueckeVerbindung.stoppen();
                e = b.ausschalten();
            }
            if (e != Bruecke.Ergebnis.OK) {
                JSONObject r = status(b);
                r.put("ok", false);
                r.put("grund", e.name().toLowerCase(java.util.Locale.ROOT));
                HttpResponse.sendJson(out, e == Bruecke.Ergebnis.SERVER_NICHT_ERREICHBAR ? 503 : 502, r.toString());
                return true;
            }
        }
        JSONObject r = status(b);
        r.put("ok", true);
        HttpResponse.sendJson(out, r.toString());
        return true;
    }

    static JSONObject status(Bruecke b) throws Exception {
        JSONObject r = b.status();
        r.put("verbunden", BrueckeVerbindung.verbunden());
        r.put("qrBasis", qrBasis(b.server()));
        return r;
    }

    /** Landeseite für den QR v2 (HANDY_API_VERTRAG §7): {@code https://<Host des Brücken-Servers>/p/}. */
    static String qrBasis(String server) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(https://[^/]+)").matcher(server == null ? "" : server);
        return (m.find() ? m.group(1) : "https://byd.serveone.at") + "/p/";
    }
}
