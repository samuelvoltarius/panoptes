package at.greenchili.surfmate.server;

import at.greenchili.surfmate.config.UnifiedConfigManager;
import at.greenchili.surfmate.neuigkeiten.Neuigkeiten;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;

/**
 * H10 (NEUBAU2 §2): {@code GET /api/neuigkeiten} (Login oder Kopplung), {@code POST /api/neuigkeiten/gesehen}
 * (nur Login). Firmware-Abgleich einmal nach dem Daemon-Start ({@link #startAbgleich()}); bei Wechsel werden die
 * Fähigkeiten neu erkannt.
 */
public final class NeuigkeitenApiHandler {

    private NeuigkeitenApiHandler() {}

    static final String ABSCHNITT = "neuigkeiten";

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String pathOnly = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if (pathOnly.equals("/api/neuigkeiten") && method.equals("GET")) {
            HttpResponse.sendJson(out, Neuigkeiten.antwort(stand(), at.greenchili.surfmate.BuildConfig.VERSION_CODE,
                    at.greenchili.surfmate.BuildConfig.VERSION_NAME).toString());
            return true;
        }
        if (pathOnly.equals("/api/neuigkeiten/gesehen") && method.equals("POST")) {
            if (AuthMiddleware.isExternRequest()) {
                HttpResponse.sendJson(out, 403, "{\"ok\":false,\"grund\":\"nur_mit_login\"}");
                return true;
            }
            JSONObject req;
            try {
                req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
            } catch (Exception e) {
                HttpResponse.sendJson(out, 400, "{\"ok\":false,\"grund\":\"json\"}");
                return true;
            }
            JSONObject neu = Neuigkeiten.gesehen(req, at.greenchili.surfmate.BuildConfig.VERSION_CODE);
            boolean ok = neu.length() == 0 || UnifiedConfigManager.updateSection(ABSCHNITT, neu);
            HttpResponse.sendJson(out, ok ? 200 : 500, ok ? "{\"ok\":true}" : "{\"ok\":false,\"grund\":\"nicht_gespeichert\"}");
            return true;
        }
        return false;
    }

    static JSONObject stand() {
        try {
            JSONObject s = UnifiedConfigManager.loadConfig().optJSONObject(ABSCHNITT);
            return s == null ? new JSONObject() : s;
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    /** Einmal, 60 s nach dem Start: Firmware merken bzw. Wechsel melden. */
    public static void startAbgleich() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(60_000L);
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                AboutApiHandler.handle("GET", "/api/about/vehicle-info", null, b);
                JSONObject info = HaApiHandler.antwortAus(b);
                String fw = info == null || info.isNull("firmware") ? null : info.optString("firmware", null);
                JSONObject neu = Neuigkeiten.firmwareAbgleich(stand(), fw, System.currentTimeMillis());
                if (neu == null) return;
                UnifiedConfigManager.updateSection(ABSCHNITT, neu);
                if (neu.has("firmwareVorher")) {
                    log("Firmware gewechselt: " + neu.optString("firmwareVorher") + " → " + fw
                            + " – Fähigkeiten werden neu erkannt");
                    VehicleCapabilities.neuErkennen();
                }
            } catch (Throwable ignored) {
            }
        }, "Neuigkeiten");
        t.setDaemon(true);
        t.start();
    }

    private static void log(String s) {
        at.greenchili.surfmate.daemon.CameraDaemon.log(s);
    }
}
