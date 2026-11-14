package at.greenchili.surfmate.server;

import at.greenchili.surfmate.storage.Dateiablage;
import at.greenchili.surfmate.storage.Wechselspeicher;

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
        if (DateiQrApiHandler.handleAuto(method, p, body, out)) return true;   // 0.1.43.4 Datei per QR (nur am Auto)
        if (p.equals(WECHSEL) || p.startsWith(WECHSEL + "/")) return wechsel(method, p, body, out);
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

    // ------------------------------------------------------------------ SD-Karte und USB-Stick (0.1.37)

    /** SD/USB: gleiche Rechte wie die Ablage (Login, Handy-Web nur mit Recht „dateien“; Kopplungen zu). */
    public static final String WECHSEL = PFAD + "/wechsel";
    static final Rueckfrage RUECKFRAGE = new Rueckfrage();
    private static volatile Wechselspeicher wechselTest;

    /** Für Tests: feste Wurzeln statt der live erkannten. */
    static void setzeWechsel(Wechselspeicher w) {
        wechselTest = w;
    }

    private static Wechselspeicher wechselspeicher() {
        Wechselspeicher w = wechselTest;
        return w != null ? w : Wechselspeicher.live();
    }

    private static boolean wechsel(String method, String p, String body, OutputStream out) throws Exception {
        Wechselspeicher w = wechselspeicher();
        if (p.equals(WECHSEL)) {
            if (!"GET".equals(method)) return fehler(out, 405, "methode");
            HttpResponse.sendJson(out, new JSONObject().put("ok", true)
                    .put("speicher", new JSONArray(w.speicher())).toString());
            return true;
        }
        if (!"POST".equals(method)) return fehler(out, 405, "methode");
        JSONObject req;
        try {
            req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
        } catch (Exception e) {
            return fehler(out, 400, "json");
        }
        String sp = req.optString("speicher", "");
        String pfad = req.optString("pfad", "");
        long now = System.currentTimeMillis();
        switch (p.substring(WECHSEL.length())) {
            case "/liste": {
                java.util.List<Wechselspeicher.Eintrag> l = w.liste(sp, pfad);
                if (l == null) return fehler(out, 404, "nicht_erlaubt");
                JSONArray a = new JSONArray();
                for (Wechselspeicher.Eintrag e : l) {
                    a.put(new JSONObject().put("name", e.name).put("ordner", e.ordner).put("groesse", e.groesse)
                            .put("zeit", e.zeit));
                }
                HttpResponse.sendJson(out, new JSONObject().put("ok", true).put("eintraege", a).toString());
                return true;
            }
            case "/holen": {
                long groesse = w.groesse(sp, pfad);
                InputStream in = groesse < 0 ? null : w.oeffnen(sp, pfad);
                BefehlsLog.ereignis("dateien.wechsel_holen", in == null ? "nicht_erlaubt" : "ok", null, null);
                if (in == null) return fehler(out, 404, "nicht_erlaubt");
                String name = pfad.contains("/") ? pfad.substring(pfad.lastIndexOf('/') + 1) : pfad;
                try (InputStream i = in) {
                    out.write(kopf(name, groesse).getBytes(StandardCharsets.UTF_8));
                    byte[] puffer = new byte[64 * 1024];
                    int n;
                    while ((n = i.read(puffer)) > 0) out.write(puffer, 0, n);
                    out.flush();
                }
                return true;
            }
            case "/frage": {
                String aktion = req.optString("aktion", "");
                String ziel = wechselZiel(aktion, sp, pfad, req.optString("name", ""));
                if (ziel == null) return fehler(out, 400, "aktion");
                if ("loeschen".equals(aktion) && w.groesse(sp, pfad) < 0) return fehler(out, 404, "nicht_erlaubt");
                if ("kopieren".equals(aktion) && (w.liste(sp, pfad) == null
                        || ablage().groesse(req.optString("name", "")) < 0)) {
                    return fehler(out, 404, "nicht_erlaubt");
                }
                HttpResponse.sendJson(out, new JSONObject().put("ok", true)
                        .put("marke", RUECKFRAGE.frage(aktion, ziel, now)).toString());
                return true;
            }
            case "/loeschen": {
                String ziel = wechselZiel("loeschen", sp, pfad, "");
                String g = ziel != null && RUECKFRAGE.bestaetigt(req.optString("marke", ""), "loeschen", ziel, now)
                        ? w.loeschen(sp, pfad) : "rueckfrage";
                BefehlsLog.ereignis("dateien.wechsel_loeschen", g == null ? "ok" : g, null, null);
                if (g != null) return fehler(out, "rueckfrage".equals(g) ? 409 : 404, g);
                HttpResponse.sendJson(out, new JSONObject().put("ok", true).toString());
                return true;
            }
            case "/kopieren": {
                String name = req.optString("name", "");
                String ziel = wechselZiel("kopieren", sp, pfad, name);
                String g;
                if (ziel == null || !RUECKFRAGE.bestaetigt(req.optString("marke", ""), "kopieren", ziel, now)) {
                    g = "rueckfrage";
                } else {
                    Dateiablage a = ablage();
                    long groesse = a.groesse(name);
                    InputStream in = groesse < 0 ? null : a.oeffnen(name);
                    if (in == null) {
                        g = "nicht_gefunden";
                    } else {
                        try (InputStream i = in) {
                            g = w.kopiereHinein(sp, pfad, name, i, groesse);
                        }
                    }
                }
                BefehlsLog.ereignis("dateien.wechsel_kopieren", g == null ? "ok" : g, null, null);
                if (g != null) return fehler(out, "rueckfrage".equals(g) ? 409 : 400, g);
                HttpResponse.sendJson(out, new JSONObject().put("ok", true).toString());
                return true;
            }
            default:
                return fehler(out, 404, "unbekannt");
        }
    }

    /** Ziel der Rückfrage: Aktion + Speicher + Weg (+ Name). null bei unbekannter Aktion. */
    static String wechselZiel(String aktion, String speicher, String pfad, String name) {
        if ("loeschen".equals(aktion)) return speicher + ":" + pfad;
        if ("kopieren".equals(aktion)) return speicher + ":" + pfad + "|" + name;
        return null;
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
