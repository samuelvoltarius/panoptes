package at.greenchili.surfmate.server;

import at.greenchili.surfmate.auth.CloudUebergabe;
import at.greenchili.surfmate.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.OutputStream;
import java.security.SecureRandom;

/**
 * BYD-Cloud vom EV-Cockpit übernehmen (CLOUD_UEBERGABE_VERTRAG.md, 0.1.21).
 * <ul>
 *   <li>Cockpit (Kopplungs-JWT, art=cockpit; ExternGuard lässt nur diese Art durch):
 *       {@code POST /api/cloud-uebergabe/anfrage}, {@code GET /api/cloud-uebergabe/<ref>}, {@code POST /api/cloud-uebergabe/<ref>}</li>
 *   <li>Besitzer (Login bzw. im Auto, nie Kopplungs-JWT, nie Web-Sitzung der Handy-App):
 *       {@code GET /api/cloud-uebergabe} (offene Rückfragen), {@code POST /api/cloud-uebergabe/<ref>/erlauben|ablehnen}
 *       – auch vom Display-Overlay und vom Telegram-Knopf über den Kamera-Daemon.</li>
 * </ul>
 * Nie Zugangsdaten in einer Antwort oder im Protokoll.
 */
public final class CloudUebergabeApiHandler {

    private static final DaemonLogger logger = DaemonLogger.getInstance("CloudUebergabe");

    private CloudUebergabeApiHandler() {}

    private static volatile CloudUebergabe dienst;

    /** Laufzeit-Einrichter: derselbe Weg wie „BYD-Cloud einrichten“ (Speichern + Prüfen). Tests/Emulator: setzbar. */
    static volatile CloudUebergabe.Einrichter einrichter = zugang -> {
        // Nur Emulator-Abnahme (Build mit -PpanoptesScreenshotModus=true, nie Auto/Community) + Merkdatei: Attrappe statt
        // BYD-Login – nichts geht ins Netz, nichts gespeichert. (ENTWICKLUNG ist auch im Auto-Build true, DEBUG immer false.)
        if (at.greenchili.surfmate.BuildConfig.SCREENSHOT_MODUS
                && new java.io.File(at.greenchili.surfmate.util.DaemonStorage.path("cloud_uebergabe_attrappe")).exists()) {
            logger.info("cloud-uebergabe Attrappe: Felder angekommen (" + zugang.length() + ")");
            return new JSONObject().put("gespeichert", true).put("verified", true);
        }
        JSONObject r = BydCloudApiHandler.einrichten(zugang);
        JSONObject o = new JSONObject().put("gespeichert", r.optBoolean("gespeichert", false))
                .put("verified", r.optBoolean("success", false));
        String code = r.optString("code", "");
        if (!o.getBoolean("gespeichert")) {
            // vor dem Speichern abgelehnt (Feld fehlt/Format) = Inhalt; sonst Speichern gescheitert
            o.put("grund", code.endsWith("_fehlt") || "pin_format".equals(code) ? "inhalt" : "speichern");
        } else if (!o.getBoolean("verified")) {
            o.put("grund", code.isEmpty() ? "pruefung" : code);
        }
        return o;
    };

    /** Art der Kopplung (cockpit|handy|homeassistant); Tests: setzbar. */
    static volatile java.util.function.Function<String, String> artVon = kid -> at.greenchili.surfmate.auth.Kopplungen.get().art(kid);

    static CloudUebergabe dienst() {
        CloudUebergabe d = dienst;
        if (d == null) {
            synchronized (CloudUebergabeApiHandler.class) {
                d = dienst;
                if (d == null) {
                    d = new CloudUebergabe(System::currentTimeMillis, new SecureRandom(),
                            zugang -> einrichter.einrichten(zugang), MELDER);
                    dienst = d;
                }
            }
        }
        return d;
    }

    /** Nur für Tests: frischer Zustand mit eigenem Einrichter. */
    static void zuruecksetzen(CloudUebergabe.Einrichter e) {
        synchronized (CloudUebergabeApiHandler.class) {
            if (e != null) einrichter = e;
            dienst = null;
        }
    }

    static final CloudUebergabe.Melder MELDER = new CloudUebergabe.Melder() {
        @Override public void anfrage(String ref, String geraet, String pruefzahl) {
            try {
                at.greenchili.surfmate.telegram.TelegramNotifier.notifyCloudUebergabe(ref, geraet, pruefzahl);
            } catch (Throwable ignored) { }
            try {
                if (!at.greenchili.surfmate.byd.routing.DrivingSafetyGuard.isMovementBlocked()) {
                    at.greenchili.surfmate.byd.MessageOverlayController.showRueckfrage(ref.substring(2),
                            "/api/cloud-uebergabe/" + ref,
                            Messages.get("notifications.cloud_uebergabe_titel", geraet),
                            Messages.get("notifications.cloud_uebergabe_text", pruefzahl),
                            Messages.get("notifications.kopplung_erlauben"),
                            Messages.get("notifications.kopplung_ablehnen"),
                            (int) (CloudUebergabe.GUELTIG_MS / 1000));
                }
            } catch (Throwable ignored) { }
        }

        @Override public void entschieden(String ref, boolean erlaubt) {
            try {
                at.greenchili.surfmate.byd.MessageOverlayController.dismissKopplung(ref.substring(2));
            } catch (Throwable ignored) { }
        }

        @Override public void ergebnis(String geraet, boolean verified) {
            try {
                at.greenchili.surfmate.telegram.TelegramNotifier.sendMessage(
                        at.greenchili.surfmate.telegram.TelegramMessages.get(
                                verified ? "cloud_uebergabe.uebernommen_ok" : "cloud_uebergabe.uebernommen_nicht_geprueft", geraet),
                        "CRITICAL");
            } catch (Throwable ignored) { }
        }

        @Override public void protokoll(String zeile) {
            logger.info(zeile);
        }
    };

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String p = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if (!p.startsWith("/api/cloud-uebergabe")) return false;
        boolean extern = AuthMiddleware.isExternRequest();
        CloudUebergabe.Antwort a;
        if ("/api/cloud-uebergabe".equals(p) && "GET".equals(method)) {
            if (extern) a = fehler(403, "nur_besitzer");
            else a = antwort(200, new JSONObject().put("ok", true).put("offen", dienst().offene()));
        } else if ("/api/cloud-uebergabe/anfrage".equals(p) && "POST".equals(method)) {
            if (!extern) a = fehler(401, "kopplung");
            else {
                String kid = AuthMiddleware.requestKopplungsId();
                String art = kid == null ? null : artVon.apply(kid);
                a = dienst().anfrage(kid, art, json(body).optString("geraet", ""));
            }
        } else if (p.matches("/api/cloud-uebergabe/" + CloudUebergabe.REF_MUSTER + "/(erlauben|ablehnen)") && "POST".equals(method)) {
            if (extern) a = fehler(403, "nur_besitzer");
            else {
                String ref = p.substring("/api/cloud-uebergabe/".length(), p.lastIndexOf('/'));
                a = dienst().entscheide(ref, p.endsWith("/erlauben"));
            }
        } else if (p.matches("/api/cloud-uebergabe/" + CloudUebergabe.REF_MUSTER)) {
            String ref = p.substring("/api/cloud-uebergabe/".length());
            if (!extern) a = fehler(401, "kopplung");
            else if ("GET".equals(method)) a = dienst().status(ref, AuthMiddleware.requestKopplungsId());
            else if ("POST".equals(method)) {
                JSONObject b = json(body);
                a = dienst().senden(ref, AuthMiddleware.requestKopplungsId(), b.optString("e_pub", null), b.optString("chiffre", null));
            } else return false;
        } else {
            return false;
        }
        HttpResponse.sendJson(out, a.status, a.json.toString());
        return true;
    }

    private static CloudUebergabe.Antwort fehler(int status, String grund) throws Exception {
        return antwort(status, new JSONObject().put("ok", false).put("grund", grund));
    }

    private static CloudUebergabe.Antwort antwort(int status, JSONObject j) {
        return new CloudUebergabe.Antwort(status, j);
    }

    private static JSONObject json(String body) {
        try {
            return body == null || body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
