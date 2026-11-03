package at.greenchili.surfmate.server;

import at.greenchili.surfmate.weckwort.WeckwortMessRegeln;

import org.json.JSONObject;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Weckwort-Messung Stufe 1: liefert die Zahlen-JSON, die der App-Prozess nach jeder Messung schreibt.
 *
 * <p>{@code GET /api/weckwort-messung} nur direkt am Autobildschirm (Login und Loopback, nicht über Tailscale,
 * Brücke, Kopplung oder Handy-Web; {@link AuthMiddleware#isDirektAmAuto}). Nur lesen: Start und Stopp gibt es nur in
 * der App-WebView am Auto (JS-Brücke), nicht über HTTP. Die Datei enthält nur Zahlen, nie Audio.
 */
public final class WeckwortMessApiHandler {

    public static final String PFAD = "/api/weckwort-messung";
    static final long MAX_BYTES = 64 * 1024;

    private WeckwortMessApiHandler() {}

    public static boolean handle(String method, String path, OutputStream out) throws Exception {
        if (!path.equals(PFAD) && !path.startsWith(PFAD + "?")) return false;
        if (!"GET".equals(method)) {
            HttpResponse.sendJson(out, 405, "{\"success\":false,\"error\":\"nur_lesen\"}");
            return true;
        }
        if (!AuthMiddleware.isDirektAmAuto()) {
            HttpResponse.sendJson(out, 403, "{\"success\":false,\"error\":\"nur_am_auto\"}");
            return true;
        }
        HttpResponse.sendJson(out, 200, antwort(new File(WeckwortMessRegeln.ERGEBNIS_PFAD_DAEMON)).toString());
        return true;
    }

    /** Antwort aus der Ergebnisdatei; fehlt sie oder ist sie kaputt, nur {@code vorhanden:false}. */
    static JSONObject antwort(File datei) throws Exception {
        JSONObject r = new JSONObject();
        r.put("schalter", WeckwortMessRegeln.SCHALTER);
        if (datei == null || !datei.isFile() || datei.length() <= 0 || datei.length() > MAX_BYTES) {
            return r.put("vorhanden", false);
        }
        try {
            String text = new String(java.nio.file.Files.readAllBytes(datei.toPath()), StandardCharsets.UTF_8);
            JSONObject e = new JSONObject(text);
            if (!"weckwort-messung".equals(e.optString("art"))) return r.put("vorhanden", false);
            r.put("vorhanden", true);
            r.put("ergebnis", e);
        } catch (Exception ex) {
            r.put("vorhanden", false).put("fehler", "unlesbar");
        }
        return r;
    }
}
