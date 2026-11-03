package at.greenchili.surfmate.server;

import at.greenchili.surfmate.overlay.HintergrundRegel;
import at.greenchili.surfmate.overlay.ValetRegel;
import at.greenchili.surfmate.storage.Dateiablage;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Display-Werkzeuge (0.1.37, DashArc-Abgleich): Hintergrundbild und Display-Sperre am Zentraldisplay.
 *
 * <p>Nur mit Login (Display oder voller Login); für Kopplungen ist der Pfad automatisch zu ({@link ExternGuard} ist
 * eine Erlaubnisliste), für die Web-Sitzung der Handy-App gesperrt ({@link WebHandyGuard}), für Automationen und
 * die KI nicht erreichbar (nicht in der Automations-Erlaubnisliste von {@link HttpServer}). Jede Aktion ist POST und
 * steht im {@link BefehlsLog}.
 *
 * <ul>
 *   <li>{@code GET /api/display} — {@code {ok, hintergrund:{maxBytes, video:false}, sperre:{aktiv, sichtbar, grund,
 *       fehlversuche, imStand}}}</li>
 *   <li>{@code POST /api/display/hintergrund/start} {@code {name, groesse}} → {@code {ok, id, teilBytes}} (höchstens
 *       20 MB, nur jpg/png/webp)</li>
 *   <li>{@code POST /api/display/hintergrund/teil} {@code {id, nr, daten}} (Base64)</li>
 *   <li>{@code POST /api/display/hintergrund/fertig} {@code {id}} → prüft den Inhalt und setzt das Bild</li>
 *   <li>{@code POST /api/display/hintergrund/abbrechen} {@code {id}}</li>
 *   <li>{@code POST /api/display/hintergrund/bild} {@code {marke}} — nur für die App am Display, einmal</li>
 *   <li>{@code POST /api/display/hintergrund/standard} → zurück auf den Standard-Hintergrund</li>
 *   <li>{@code POST /api/display/sperre/an} {@code {pin}} — nur im Stand</li>
 *   <li>{@code POST /api/display/sperre/entsperren} {@code {pin}}</li>
 *   <li>{@code POST /api/display/sperre/aus} — ohne PIN nur aus der Ferne mit Login; direkt am Auto braucht es
 *       die PIN (sonst könnte jeder am Display die Sperre über Panoptes selbst lösen)</li>
 * </ul>
 */
public final class DisplayApiHandler {

    public static final String PFAD = "/api/display";
    private static final String HG = PFAD + "/hintergrund";
    private static final String SP = PFAD + "/sperre";

    private static volatile Dateiablage upload;

    /**
     * Geprüftes Bild, bereit zum Abholen durch die App (im Daemon-Ordner). Die App holt es einmal mit einer
     * Marke über {@code /api/display/hintergrund/bild} ab; ab Android 11 darf die Shell nicht in den App-Ordner.
     */
    static volatile File zielOrdner = new File(at.greenchili.surfmate.util.DaemonStorage.path("hintergrund"));
    static final Rueckfrage ABHOLEN = new Rueckfrage();

    /** Brücke zum App-Prozess (Tests ersetzen sie). */
    interface AppBruecke {
        JSONObject senden(JSONObject befehl);
    }

    static volatile AppBruecke bruecke = befehl -> {
        RemoteDevViewBridgeClient.Response r = new RemoteDevViewBridgeClient().send(befehl);
        return r == null ? null : r.metadata;
    };

    /** Ist die Anfrage direkt am Autobildschirm? (Tests ersetzen es.) */
    interface DirektAmAuto {
        boolean pruefe();
    }

    static volatile DirektAmAuto direkt = AuthMiddleware::isDirektAmAuto;

    private DisplayApiHandler() {}

    static synchronized Dateiablage upload() {
        if (upload == null) {
            upload = new Dateiablage(new File(at.greenchili.surfmate.util.DaemonStorage.path("hintergrund_upload")));
        }
        return upload;
    }

    static synchronized void setzeUpload(Dateiablage a) {
        upload = a;
    }

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String p = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if (!p.equals(PFAD) && !p.startsWith(PFAD + "/")) return false;
        if (p.equals(PFAD)) {
            if (!"GET".equals(method)) return fehler(out, 405, "methode");
            JSONObject hg = new JSONObject().put("maxBytes", HintergrundRegel.MAX_BYTES)
                    .put("video", HintergrundRegel.VIDEO_BELEGT).put("arten", new org.json.JSONArray()
                            .put("jpg").put("png").put("webp"));
            HttpResponse.sendJson(out, new JSONObject().put("ok", true).put("hintergrund", hg)
                    .put("sperre", ValetSperreDienst.get().stand()).toString());
            return true;
        }
        if (!"POST".equals(method)) return fehler(out, 405, "methode");
        JSONObject req;
        try {
            req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
        } catch (Exception e) {
            return fehler(out, 400, "json");
        }
        long now = System.currentTimeMillis();
        switch (p) {
            case HG + "/start": {
                String name = req.optString("name", "");
                long groesse = req.optLong("groesse", -1);
                String g = HintergrundRegel.grundVorher(name, groesse);
                if (g != null) return fehler(out, "zu_gross".equals(g) ? 413 : 400, g);
                Dateiablage a = upload();
                Dateiablage.Antwort r = a.start("hintergrund." + HintergrundRegel.endung(name), groesse,
                        frei(a), now);
                if (!r.ok()) return fehler(out, 400, r.grund);
                HttpResponse.sendJson(out, new JSONObject().put("ok", true).put("id", r.wert)
                        .put("teilBytes", Dateiablage.TEIL_BYTES).toString());
                return true;
            }
            case HG + "/teil": {
                byte[] daten;
                try {
                    daten = java.util.Base64.getDecoder().decode(req.optString("daten", ""));
                } catch (IllegalArgumentException e) {
                    return fehler(out, 400, "daten");
                }
                Dateiablage.Antwort r = upload().teil(req.optString("id", ""), req.optInt("nr", -1), daten, now);
                if (!r.ok()) return fehler(out, 400, r.grund);
                HttpResponse.sendJson(out, new JSONObject().put("ok", true).toString());
                return true;
            }
            case HG + "/abbrechen": {
                HttpResponse.sendJson(out, new JSONObject().put("ok", upload().abbrechen(req.optString("id", "")))
                        .toString());
                return true;
            }
            case HG + "/fertig": {
                Dateiablage a = upload();
                Dateiablage.Antwort r = a.fertig(req.optString("id", ""), now);
                if (!r.ok()) return fehler(out, 400, r.grund);
                String g = uebernehmen(a, r.wert);
                if (g != null) {
                    BefehlsLog.ereignis("display.hintergrund", g, null, null);
                    return fehler(out, 400, g);
                }
                String marke = ABHOLEN.frage("abholen", HintergrundRegel.DATEI, now);
                JSONObject antwort = bruecke.senden(new JSONObject().put("command", "hintergrund").put("aktion", "setzen")
                        .put("marke", marke));
                boolean ok = antwort != null && antwort.optBoolean("success", false);
                BefehlsLog.ereignis("display.hintergrund", ok ? "gesetzt" : "fehler", null, null);
                if (!ok) return fehler(out, 503, antwort == null ? "app" : antwort.optString("grund", "fehler"));
                HttpResponse.sendJson(out, new JSONObject().put("ok", true).toString());
                return true;
            }
            case HG + "/bild": {
                // nur die App am Display mit der Marke aus dem Brücken-Befehl, genau einmal
                File f = new File(zielOrdner, HintergrundRegel.DATEI);
                if (!ABHOLEN.bestaetigt(req.optString("marke", ""), "abholen", HintergrundRegel.DATEI, now)
                        || Files.isSymbolicLink(f.toPath()) || !f.isFile()) {
                    return fehler(out, 403, "marke");
                }
                long groesse = f.length();
                try (InputStream in = Files.newInputStream(f.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: " + groesse
                            + "\r\nX-Content-Type-Options: nosniff\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
                            .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    byte[] b = new byte[64 * 1024];
                    int r;
                    while ((r = in.read(b)) > 0) out.write(b, 0, r);
                    out.flush();
                }
                return true;
            }
            case HG + "/standard": {
                JSONObject antwort = bruecke.senden(new JSONObject().put("command", "hintergrund").put("aktion", "standard"));
                boolean ok = antwort != null && antwort.optBoolean("success", false);
                BefehlsLog.ereignis("display.hintergrund", ok ? "standard" : "fehler", null, null);
                if (!ok) return fehler(out, 503, antwort == null ? "app" : antwort.optString("grund", "fehler"));
                JSONObject a = new JSONObject().put("ok", true);
                if (antwort.has("gleichWieVorher")) a.put("gleichWieVorher", antwort.optBoolean("gleichWieVorher"));
                HttpResponse.sendJson(out, a.toString());
                return true;
            }
            case SP + "/an": {
                String g = ValetSperreDienst.get().anschalten(req.optString("pin", ""));
                if (g != null) return fehler(out, 409, g);
                HttpResponse.sendJson(out, new JSONObject().put("ok", true).toString());
                return true;
            }
            case SP + "/entsperren": {
                ValetRegel.Versuch v = ValetSperreDienst.get().entsperren(req.optString("pin", ""), now);
                if (v.entsperrt) {
                    HttpResponse.sendJson(out, new JSONObject().put("ok", true).toString());
                } else {
                    HttpResponse.sendJson(out, 403, new JSONObject().put("ok", false).put("grund", v.grund)
                            .put("wartenMs", v.wartenMs).toString());
                }
                return true;
            }
            case SP + "/aus": {
                if (direkt.pruefe()) {
                    // am Display selbst nur mit PIN – sonst wäre die Sperre über Panoptes zu umgehen
                    ValetRegel.Versuch v = ValetSperreDienst.get().entsperren(req.optString("pin", ""), now);
                    if (!v.entsperrt) {
                        HttpResponse.sendJson(out, 403, new JSONObject().put("ok", false).put("grund", v.grund)
                                .put("wartenMs", v.wartenMs).toString());
                        return true;
                    }
                } else {
                    ValetSperreDienst.get().aufheben("fern");
                }
                HttpResponse.sendJson(out, new JSONObject().put("ok", true).toString());
                return true;
            }
            default:
                return fehler(out, 404, "unbekannt");
        }
    }

    /** Fertig hochgeladenes Bild prüfen und in den App-Ordner legen. null = ok, sonst Grund. */
    static String uebernehmen(Dateiablage a, String name) {
        long groesse = a.groesse(name);
        byte[] kopf = new byte[16];
        int n = 0;
        try (InputStream in = a.oeffnen(name)) {
            if (in == null) return "datei";
            int r;
            while (n < kopf.length && (r = in.read(kopf, n, kopf.length - n)) > 0) n += r;
        } catch (Exception e) {
            return "datei";
        }
        String g = HintergrundRegel.grundNachher(name, groesse, kopf);
        if (g != null) {
            a.loeschen(name);
            return g;
        }
        try {
            File ordner = zielOrdner;
            Path o = ordner.toPath();
            if (Files.isSymbolicLink(o)) return "ziel";
            Files.createDirectories(o);
            Path ziel = o.resolve(HintergrundRegel.DATEI);
            if (Files.isSymbolicLink(ziel)) Files.delete(ziel);
            Path tmp = o.resolve(HintergrundRegel.DATEI + ".neu");
            Files.deleteIfExists(tmp);
            try (InputStream in = a.oeffnen(name);
                 OutputStream w = Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                         LinkOption.NOFOLLOW_LINKS)) {
                byte[] b = new byte[64 * 1024];
                int r;
                while ((r = in.read(b)) > 0) w.write(b, 0, r);
            }
            Files.move(tmp, ziel, StandardCopyOption.REPLACE_EXISTING);
            return null;
        } catch (Exception e) {
            return "ziel";
        } finally {
            a.loeschen(name);
        }
    }

    private static long frei(Dateiablage a) {
        long f = a.ordner().getUsableSpace();
        File eltern = a.ordner().getParentFile();
        if (f <= 0 && eltern != null) f = eltern.getUsableSpace();
        return f;
    }

    private static boolean fehler(OutputStream out, int status, String grund) throws Exception {
        HttpResponse.sendJson(out, status, new JSONObject().put("ok", false).put("grund", grund).toString());
        return true;
    }
}
