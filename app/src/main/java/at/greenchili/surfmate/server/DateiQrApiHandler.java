package at.greenchili.surfmate.server;

import at.greenchili.surfmate.storage.Dateiablage;
import at.greenchili.surfmate.storage.DateiQr;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 0.1.43.4 (Kleinkram, F7): Datei per QR-Code zwischen Handy und Auto, nur im lokalen Netz.
 *
 * <p><b>Am Auto</b> (nur {@link AuthMiddleware#isDirektAmAuto()}, nie Kopplung, Web-Sitzung oder Fernzugang):
 * <ul>
 *   <li>{@code POST /api/dateien/qr} → {@code {ok, url, lan, ordner, gueltigBis, ablaufSek, zustand}} (neue Sitzung
 *       „Handy → Auto“, die vorige verfällt)</li>
 *   <li>{@code GET /api/dateien/qr} → {@code {ok, aktiv, zustand, ablaufSek, name?}} (Zustand für die Anzeige)</li>
 *   <li>{@code POST /api/dateien/qr/ende} → Sitzung beenden</li>
 * </ul>
 *
 * <p><b>Vom Handy</b>, ohne Login, nur mit der Einmal-Kennung (128 Bit, 120 s) und nur aus dem lokalen Netz
 * ({@code lan}, vom {@link HttpServer} aus der echten Socket-Adresse bestimmt; Tunnel-Merkmale und Loopback zählen
 * nicht): {@code POST /auth/datei-qr/start|teil|fertig} (hochladen), {@code GET /auth/datei-qr/info?k=} und
 * {@code GET /auth/datei-qr/holen?k=} (holen). Die Datei landet in der {@link Dateiablage} (200 MB, keine APKs).
 *
 * <p>Die Kamera-Liste des Launchers nutzt dieselbe Grundlage: {@link #neuHolen(String, DateiQr.Quelle)}.
 */
public final class DateiQrApiHandler {

    public static final String OEFFENTLICH = "/auth/datei-qr/";
    public static final String AUTO = "/api/dateien/qr";

    /** Eine Sitzung für das ganze Auto. */
    static volatile DateiQr qr = new DateiQr();

    private DateiQrApiHandler() {}

    /** Nur für Tests. */
    static void setzeQr(DateiQr q) {
        qr = q;
    }

    /** Auto → Handy: genau diese Datei einmal anbieten (z. B. eine Aufnahme). Liefert die Sitzung oder null ohne LAN. */
    public static JSONObject neuHolen(String anzeigeName, DateiQr.Quelle quelle) throws Exception {
        String lan = DateiQr.lanAdresse();
        if (lan == null) return new JSONObject().put("ok", false).put("grund", "kein_lan");
        return antwortSitzung(qr.neuHolen(anzeigeName, quelle), lan);
    }

    // ------------------------------------------------------------------ Auto

    /** {@code /api/dateien/qr…}; true = behandelt. */
    public static boolean handleAuto(String method, String p, String body, OutputStream out) throws Exception {
        if (!p.equals(AUTO) && !p.equals(AUTO + "/ende")) return false;
        if (!AuthMiddleware.isDirektAmAuto()) return fehler(out, 403, "nur_am_auto");
        if (p.equals(AUTO + "/ende")) {
            if (!"POST".equals(method)) return fehler(out, 405, "methode");
            qr.beenden();
            HttpResponse.sendJson(out, new JSONObject().put("ok", true).toString());
            return true;
        }
        if ("POST".equals(method)) {
            JSONObject req;
            try {
                req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
            } catch (Exception e) {
                return fehler(out, 400, "json");
            }
            String lan = DateiQr.lanAdresse();
            if (lan == null) return fehler(out, 409, "kein_lan");
            if (DateiQr.HOLEN.equals(req.optString("zweck", DateiQr.HOCHLADEN))) {
                // Auto → Handy (z. B. Aufnahmen-Liste des Launchers): nur die Launcher-APK mit Panoptes-Signatur
                if (LauncherAufrufer.pruefe() != null) return fehler(out, 403, "nur_launcher");
                DateiQr.Quelle q = holenQuelle(req.optString("art", ""), req.optString("id", ""));
                if (q == null) return fehler(out, 404, "unbekannt");
                HttpResponse.sendJson(out, antwortSitzung(qr.neuHolen(null, q), lan).toString());
                return true;
            }
            HttpResponse.sendJson(out, antwortSitzung(qr.neuHochladen(), lan).toString());
            return true;
        }
        if (!"GET".equals(method)) return fehler(out, 405, "methode");
        HttpResponse.sendJson(out, status().toString());
        return true;
    }

    /**
     * Einhängepunkt für „Auto → Handy“: welche Datei ist hinter {@code art}/{@code id}? Hier noch keine Art bekannt
     * (null = 404 „unbekannt“). Die Kamera-Liste des Launchers (feat-launcher-kamera-01434) setzt hier
     * {@code "aufnahme"} auf {@code LauncherAufnahmen.quelle(id)}; nie ein freier Pfad, nur Kennungen aus dem Index.
     */
    static DateiQr.Quelle holenQuelle(String art, String id) {
        return "aufnahme".equals(art) ? LauncherAufnahmen.quelle(id) : null;
    }

    static JSONObject status() throws Exception {
        DateiQr.Sitzung s = qr.aktuell();
        JSONObject o = new JSONObject().put("ok", true).put("aktiv", s != null);
        if (s != null) {
            o.put("zweck", s.zweck).put("zustand", s.zustand()).put("ablaufSek", qr.restSekunden(s));
            if (s.ergebnisName() != null) o.put("name", s.ergebnisName());
        }
        return o;
    }

    private static JSONObject antwortSitzung(DateiQr.Sitzung s, String lan) throws Exception {
        String url = DateiQr.url(lan, at.greenchili.surfmate.daemon.CameraDaemon.HTTP_PORT, s.kennung, s.zweck);
        return new JSONObject().put("ok", true).put("url", url).put("lan", lan).put("zweck", s.zweck)
                .put("ordner", "Panoptes/Dateien").put("gueltigBis", s.bis).put("ablaufSek", qr.restSekunden(s))
                .put("zustand", s.zustand());
    }

    // ------------------------------------------------------------------ Handy (ohne Login)

    /**
     * {@code /auth/datei-qr/<aktion>}. {@code lan} = Gegenstelle ist im lokalen Netz (vom Server bestimmt).
     * Außerhalb des lokalen Netzes sieht der Aufrufer nichts als 403.
     */
    public static boolean handleHandy(String method, String path, String body, OutputStream out, boolean lan)
            throws Exception {
        if (!path.startsWith(OEFFENTLICH)) return false;
        if (!lan) return fehler(out, 403, "nur_lokales_netz");
        String ohne = path.substring(OEFFENTLICH.length());
        String aktion = ohne.contains("?") ? ohne.substring(0, ohne.indexOf('?')) : ohne;
        String k = abfrage(path, "k");
        long jetzt = System.currentTimeMillis();
        switch (aktion) {
            case "info": {
                if (!"GET".equals(method)) return fehler(out, 405, "methode");
                DateiQr.Pruefung pr = qr.pruefe(k, null);
                if (!pr.ok()) return fehler(out, pr.http, pr.grund);
                JSONObject o = new JSONObject().put("ok", true).put("zweck", pr.sitzung.zweck)
                        .put("ablaufSek", qr.restSekunden(pr.sitzung));
                if (DateiQr.HOLEN.equals(pr.sitzung.zweck)) {
                    o.put("name", pr.sitzung.anzeigeName).put("groesse", pr.sitzung.groesseHolen());
                } else {
                    o.put("maxBytes", Dateiablage.MAX_BYTES).put("teilBytes", Dateiablage.TEIL_BYTES);
                }
                HttpResponse.sendJson(out, o.toString());
                return true;
            }
            case "holen": {
                if (!"GET".equals(method)) return fehler(out, 405, "methode");
                DateiQr.Pruefung pr = qr.pruefe(k, DateiQr.HOLEN);
                if (!pr.ok()) return fehler(out, pr.http, pr.grund);
                return liefern(out, pr.sitzung);
            }
            case "start": case "teil": case "fertig": {
                if (!"POST".equals(method)) return fehler(out, 405, "methode");
                JSONObject req;
                try {
                    req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
                } catch (Exception e) {
                    return fehler(out, 400, "json");
                }
                DateiQr.Pruefung pr = qr.pruefe(req.optString("k", ""), DateiQr.HOCHLADEN);
                if (!pr.ok()) return fehler(out, pr.http, pr.grund);
                return hochladen(aktion, req, pr.sitzung, out, jetzt);
            }
            default:
                return fehler(out, 404, "unbekannt");
        }
    }

    private static boolean hochladen(String aktion, JSONObject req, DateiQr.Sitzung s, OutputStream out, long jetzt)
            throws Exception {
        Dateiablage a = DateienApiHandler.ablage();
        switch (aktion) {
            case "start": {
                long frei = a.ordner().getUsableSpace();
                java.io.File eltern = a.ordner().getParentFile();
                if (frei <= 0 && eltern != null) frei = eltern.getUsableSpace();
                if (!DateiQr.OFFEN.equals(s.zustand())) return fehler(out, 409, "belegt");
                Dateiablage.Antwort r = a.start(req.optString("name", ""), req.optLong("groesse", -1), frei, jetzt);
                if (!r.ok()) return fehler(out, r.grund.equals("zu_gross") ? 413 : 400, r.grund);
                if (!qr.beginne(s, r.wert)) {
                    a.abbrechen(r.wert);
                    return fehler(out, 409, "belegt");
                }
                BefehlsLog.ereignis("dateien.qr.hochladen", "gestartet", null, null);
                HttpResponse.sendJson(out, new JSONObject().put("ok", true).put("id", r.wert)
                        .put("teilBytes", Dateiablage.TEIL_BYTES).toString());
                return true;
            }
            case "teil": {
                String id = req.optString("id", "");
                if (!qr.istUpload(s, id)) return fehler(out, 409, "falsche_id");
                byte[] daten;
                try {
                    daten = java.util.Base64.getDecoder().decode(req.optString("daten", ""));
                } catch (IllegalArgumentException e) {
                    return fehler(out, 400, "daten");
                }
                Dateiablage.Antwort r = a.teil(id, req.optInt("nr", -1), daten, jetzt);
                if (!r.ok()) return fehler(out, 400, r.grund);
                HttpResponse.sendJson(out, new JSONObject().put("ok", true).put("geschrieben", Long.parseLong(r.wert))
                        .toString());
                return true;
            }
            default: {   // fertig
                String id = req.optString("id", "");
                if (!qr.istUpload(s, id)) return fehler(out, 409, "falsche_id");
                Dateiablage.Antwort r = a.fertig(id, jetzt);
                BefehlsLog.ereignis("dateien.qr.hochgeladen", r.ok() ? "ok" : r.grund, null, null);
                if (!r.ok()) return fehler(out, 400, r.grund);
                qr.verbrauche(s, r.wert);
                HttpResponse.sendJson(out, new JSONObject().put("ok", true).put("name", r.wert).toString());
                return true;
            }
        }
    }

    private static boolean liefern(OutputStream out, DateiQr.Sitzung s) throws Exception {
        DateiQr.Quelle q = s.quelleHolen();
        if (q == null) return fehler(out, 404, "nicht_gefunden");
        long laenge = q.laenge();
        InputStream in = q.oeffnen();
        if (in == null || laenge < 0) return fehler(out, 404, "nicht_gefunden");
        // einmal: die Kennung ist ab dem Abruf verbraucht, auch wenn die Übertragung abbricht
        qr.verbrauche(s, s.anzeigeName);
        BefehlsLog.ereignis("dateien.qr.holen", "ok", null, null);
        try (InputStream i = in) {
            out.write(DateienApiHandler.kopf(s.anzeigeName, laenge).getBytes(StandardCharsets.UTF_8));
            byte[] puffer = new byte[64 * 1024];
            int n;
            while ((n = i.read(puffer)) > 0) out.write(puffer, 0, n);
            out.flush();
        }
        return true;
    }

    static String abfrage(String path, String name) {
        int q = path.indexOf('?');
        if (q < 0) return "";
        for (String kv : path.substring(q + 1).split("&")) {
            int e = kv.indexOf('=');
            if (e > 0 && kv.substring(0, e).equals(name)) return kv.substring(e + 1);
        }
        return "";
    }

    private static boolean fehler(OutputStream out, int status, String grund) throws Exception {
        HttpResponse.sendJson(out, status, new JSONObject().put("ok", false).put("grund", grund).toString());
        return true;
    }
}
