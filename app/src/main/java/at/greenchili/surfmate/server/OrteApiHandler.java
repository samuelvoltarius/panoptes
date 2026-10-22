package at.greenchili.surfmate.server;

import at.greenchili.surfmate.orte.Orte;
import at.greenchili.surfmate.orte.OrteDaemon;
import at.greenchili.surfmate.orte.OrteDienst;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ORTE_VERTRAG v1 §3 (0.1.11, Kern 7). Login bzw. lokal wie /api/einstellungen.
 * <ul>
 *   <li>{@code GET /api/orte} – Liste, Grenzen, Migrationszähler. Über die Cockpit-Kopplung OHNE Koordinaten/Radius,
 *       nur Name, Eigenschaften und {@code imKreis} zur aktuellen Position (Koordination 22.10.).</li>
 *   <li>{@code GET /api/orte/hier} – Orte an der aktuellen Position (ohne Koordinaten).</li>
 *   <li>{@code POST /api/orte/favoriten {liste:[{art,label,lat,lon}], version}} – 0.1.12: Navi-Favoriten der App
 *       abgleichen; Antwort {version, aendern, liste?}.</li>
 *   <li>{@code POST /api/orte}, {@code PUT /api/orte/{id}}, {@code DELETE /api/orte/{id}?bestaetigt=true} – nur im
 *       Auto bzw. in dessen Weboberfläche, nie über die Kopplung (403 nur_im_auto).</li>
 * </ul>
 * Die Ortsliste verlässt das Auto nie (Regel 2).
 */
public final class OrteApiHandler {

    private OrteApiHandler() {}

    private static final Pattern EINZEL = Pattern.compile("^/api/orte/(o_[a-z0-9]{1,32})$");

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String p = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if (!p.equals("/api/orte") && !p.startsWith("/api/orte/")) return false;
        boolean kopplung = AuthMiddleware.requestKopplungsId() != null;
        long jetzt = System.currentTimeMillis();
        OrteDienst d = OrteDaemon.dienst();
        try {
            if (p.equals("/api/orte") && method.equals("GET")) {
                JSONArray liste = d.abgleichen(jetzt);
                double[] pos = position();
                JSONObject r = new JSONObject().put("success", true).put("version", Orte.VERSION).put("max", Orte.MAX_ORTE);
                if (kopplung) {
                    JSONArray a = new JSONArray();
                    for (int i = 0; i < liste.length(); i++) {
                        a.put(Orte.ohneKoordinaten(liste.getJSONObject(i), pos == null ? null : pos[0], pos == null ? null : pos[1]));
                    }
                    r.put("liste", a);
                } else {
                    r.put("liste", liste).put("grenzen", grenzen());
                }
                HttpResponse.sendJson(out, r.toString());
                return true;
            }
            if (p.equals("/api/orte/hier") && method.equals("GET")) {
                double[] pos = position();
                JSONArray a = new JSONArray();
                if (pos != null) {
                    for (JSONObject o : Orte.hier(d.abgleichen(jetzt), pos[0], pos[1])) a.put(Orte.ohneKoordinaten(o, null, null));
                }
                HttpResponse.sendJson(out, new JSONObject().put("success", true).put("fix", pos != null).put("orte", a).toString());
                return true;
            }
            if (method.equals("GET")) return false;
            if (kopplung) {
                HttpResponse.sendJson(out, 403, "{\"success\":false,\"error\":\"nur_im_auto\"}");
                return true;
            }
            JSONObject req;
            try {
                req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
            } catch (Exception e) {
                HttpResponse.sendJson(out, 400, "{\"success\":false,\"error\":\"json\"}");
                return true;
            }
            if (p.equals("/api/orte/favoriten") && method.equals("POST")) {
                // 0.1.12: Navi-Favoriten der App (nur lokal aus der App, nie über die Kopplung)
                JSONArray liste = req.optJSONArray("liste");
                if (liste == null) {
                    HttpResponse.sendJson(out, 400, "{\"success\":false,\"error\":\"liste\"}");
                    return true;
                }
                JSONObject r = d.favoritenAbgleichen(liste, req.optLong("version", -1), jetzt);
                HttpResponse.sendJson(out, r.put("success", true).toString());
                return true;
            }
            if (p.equals("/api/orte") && method.equals("POST")) {
                JSONObject o = d.anlegen(req, jetzt);
                HttpResponse.sendJson(out, 201, new JSONObject().put("success", true).put("ort", o).toString());
                return true;
            }
            Matcher m = EINZEL.matcher(p);
            if (m.matches() && method.equals("PUT")) {
                JSONObject o = d.aendern(m.group(1), req, jetzt);
                HttpResponse.sendJson(out, new JSONObject().put("success", true).put("ort", o).toString());
                return true;
            }
            if (m.matches() && method.equals("DELETE")) {
                if (!bestaetigt(path)) {
                    HttpResponse.sendJson(out, 400, "{\"success\":false,\"error\":\"bestaetigung_fehlt\"}");
                    return true;
                }
                d.loeschen(m.group(1), jetzt);
                HttpResponse.sendJson(out, "{\"success\":true}");
                return true;
            }
            return false;
        } catch (OrteDienst.Fehler f) {
            JSONObject a = new JSONObject(f.antwort.toString()).put("success", false);
            a.put("text", Messages.text("orte.fehler." + f.antwort.optString("error"), f.antwort.optString("error")));
            HttpResponse.sendJson(out, f.status, a.toString());
            return true;
        }
    }

    static boolean bestaetigt(String path) {
        int q = path.indexOf('?');
        if (q < 0) return false;
        for (String s : path.substring(q + 1).split("&")) if (s.equals("bestaetigt=true")) return true;
        return false;
    }

    private static JSONObject grenzen() throws Exception {
        return new JSONObject()
                .put("r", new JSONObject().put("min", Orte.R_MIN).put("max", Orte.R_MAX))
                .put("privat", new JSONObject().put("min", Orte.PRIVAT_MIN).put("max", Orte.PRIVAT_MAX))
                .put("ruhezone", new JSONObject().put("min", Orte.RUHE_MIN).put("max", Orte.RUHE_MAX).put("anzahlMax", 10))
                .put("zuhause", new JSONObject().put("min", Orte.RUHE_MIN).put("max", Orte.RUHE_MAX))
                .put("tarif", new JSONObject().put("min", Orte.TARIF_MIN).put("max", Orte.TARIF_MAX));
    }

    /** Aktuelle Position, wenn ein frischer Fix (&lt; 10 min) vorliegt; sonst null. */
    private static double[] position() {
        try {
            at.greenchili.surfmate.monitor.GpsMonitor g = at.greenchili.surfmate.monitor.GpsMonitor.getInstance();
            long age = System.currentTimeMillis() - g.getLastUpdate();
            if (g.getLastUpdate() <= 0 || age < 0 || age > 10 * 60_000L) return null;
            return new double[] {g.getLatitude(), g.getLongitude()};
        } catch (Throwable t) {
            return null;
        }
    }
}
