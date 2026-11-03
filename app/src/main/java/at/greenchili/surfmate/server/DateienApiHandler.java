package at.greenchili.surfmate.server;

import at.greenchili.surfmate.storage.Dateiablage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Dateien zwischen Handy und Auto über die Weboberfläche ({@link Dateiablage}).
 *
 * <p>Wer darf: Login am Auto (Display, voller Login) immer; gekoppelte Handys und die Web-Sitzung der Handy-App nur
 * mit dem Recht {@code dateien} (Standard aus, nie automatisch, nur am Auto oder mit Login vergeben). Das prüfen
 * {@link ExternGuard} und {@link WebHandyGuard} vor diesem Handler. Jede Aktion läuft als POST durch den
 * {@link BefehlsLog}; Hochladen, Holen und Löschen tragen zusätzlich ein Ereignis (ohne Dateinamen).
 *
 * <ul>
 *   <li>{@code GET /api/dateien} — Liste {@code {ok, dateien:[{name, groesse, zeit}], maxBytes, teilBytes, frei}}</li>
 *   <li>{@code POST /api/dateien/start} {@code {name, groesse}} → {@code {ok, id, teilBytes}}</li>
 *   <li>{@code POST /api/dateien/teil} {@code {id, nr, daten}} (Base64, höchstens {@code teilBytes})</li>
 *   <li>{@code POST /api/dateien/fertig} {@code {id}} → {@code {ok, name}}</li>
 *   <li>{@code POST /api/dateien/abbrechen} {@code {id}}</li>
 *   <li>{@code POST /api/dateien/holen} {@code {name}} → Datei als {@code application/octet-stream}</li>
 *   <li>{@code POST /api/dateien/loeschen} {@code {name}} — genau eine Datei</li>
 * </ul>
 */
public final class DateienApiHandler {

    public static final String PFAD = "/api/dateien";

    private static volatile Dateiablage ablage;

    private DateienApiHandler() {}

    static synchronized Dateiablage ablage() {
        if (ablage == null) {
            ablage = new Dateiablage(new File(at.greenchili.surfmate.util.DaemonStorage.path("dateien")));
        }
        return ablage;
    }

    /** Für Tests: eigene Ablage. */
    static synchronized void setzeAblage(Dateiablage a) {
        ablage = a;
    }

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String p = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if (!p.equals(PFAD) && !p.startsWith(PFAD + "/")) return false;
        Dateiablage a = ablage();
        long now = System.currentTimeMillis();
        if (p.equals(PFAD)) {
            if (!"GET".equals(method)) return fehler(out, 405, "methode");
            JSONArray l = new JSONArray();
            for (Dateiablage.Info i : a.liste()) {
                l.put(new JSONObject().put("name", i.name).put("groesse", i.groesse).put("zeit", i.zeit));
            }
            HttpResponse.sendJson(out, new JSONObject().put("ok", true).put("dateien", l)
                    .put("maxBytes", Dateiablage.MAX_BYTES).put("teilBytes", Dateiablage.TEIL_BYTES)
                    .put("frei", Math.max(0, a.ordner().getUsableSpace())).toString());
            return true;
        }
        if (!"POST".equals(method)) return fehler(out, 405, "methode");
        JSONObject req;
        try {
            req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
        } catch (Exception e) {
            return fehler(out, 400, "json");
        }
        switch (p.substring(PFAD.length())) {
            case "/start": {
                long frei = a.ordner().getUsableSpace();
                File eltern = a.ordner().getParentFile();
                if (frei <= 0 && eltern != null) frei = eltern.getUsableSpace();
                Dateiablage.Antwort r = a.start(req.optString("name", ""), req.optLong("groesse", -1), frei, now);
                if (!r.ok()) return fehler(out, r.grund.equals("zu_gross") ? 413 : 400, r.grund);
                BefehlsLog.ereignis("dateien.hochladen", "gestartet", null, null);
                HttpResponse.sendJson(out, new JSONObject().put("ok", true).put("id", r.wert)
                        .put("teilBytes", Dateiablage.TEIL_BYTES).toString());
                return true;
            }
            case "/teil": {
                byte[] daten;
                try {
                    daten = java.util.Base64.getDecoder().decode(req.optString("daten", ""));
                } catch (IllegalArgumentException e) {
                    return fehler(out, 400, "daten");
                }
                Dateiablage.Antwort r = a.teil(req.optString("id", ""), req.optInt("nr", -1), daten, now);
                if (!r.ok()) return fehler(out, 400, r.grund);
                HttpResponse.sendJson(out, new JSONObject().put("ok", true).put("geschrieben", Long.parseLong(r.wert))
                        .toString());
                return true;
            }
            case "/fertig": {
                Dateiablage.Antwort r = a.fertig(req.optString("id", ""), now);
                BefehlsLog.ereignis("dateien.hochgeladen", r.ok() ? "ok" : r.grund, null, null);
                if (!r.ok()) return fehler(out, 400, r.grund);
                HttpResponse.sendJson(out, new JSONObject().put("ok", true).put("name", r.wert).toString());
                return true;
            }
            case "/abbrechen": {
                boolean ok = a.abbrechen(req.optString("id", ""));
                HttpResponse.sendJson(out, new JSONObject().put("ok", ok).toString());
                return true;
            }
            case "/holen": {
                String name = req.optString("name", "");
                long groesse = a.groesse(name);
                InputStream in = groesse < 0 ? null : a.oeffnen(name);
                if (in == null) {
                    BefehlsLog.ereignis("dateien.holen", "nicht_gefunden", null, null);
                    return fehler(out, 404, "nicht_gefunden");
                }
                BefehlsLog.ereignis("dateien.holen", "ok", null, null);
                try (InputStream i = in) {
                    out.write(kopf(name, groesse).getBytes(StandardCharsets.UTF_8));
                    byte[] puffer = new byte[64 * 1024];
                    int n;
                    while ((n = i.read(puffer)) > 0) out.write(puffer, 0, n);
                    out.flush();
                }
                return true;
            }
            case "/loeschen": {
                boolean ok = a.loeschen(req.optString("name", ""));
                BefehlsLog.ereignis("dateien.loeschen", ok ? "ok" : "nicht_gefunden", null, null);
                if (!ok) return fehler(out, 404, "nicht_gefunden");
                HttpResponse.sendJson(out, new JSONObject().put("ok", true).toString());
                return true;
            }
            default:
                return fehler(out, 404, "unbekannt");
        }
    }

    /** Kopf der Download-Antwort: immer Anhang, nie ausführbar im Browser (octet-stream, nosniff, CSP sandbox). */
    static String kopf(String name, long groesse) throws Exception {
        String ascii = name.replaceAll("[^A-Za-z0-9 ._()-]", "_");
        String utf8 = URLEncoder.encode(name, "UTF-8").replace("+", "%20");
        return "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/octet-stream\r\n"
                + "Content-Length: " + groesse + "\r\n"
                + "Content-Disposition: attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + utf8 + "\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Content-Security-Policy: sandbox\r\n"
                + "Cache-Control: no-store, private\r\n"
                + "Connection: close\r\n\r\n";
    }

    private static boolean fehler(OutputStream out, int status, String grund) throws Exception {
        HttpResponse.sendJson(out, status, new JSONObject().put("ok", false).put("grund", grund).toString());
        return true;
    }
}
