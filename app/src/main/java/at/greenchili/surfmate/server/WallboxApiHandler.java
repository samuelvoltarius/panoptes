package at.greenchili.surfmate.server;

import at.greenchili.surfmate.byd.cloud.crypto.CredentialCipher;
import at.greenchili.surfmate.util.DaemonStorage;
import at.greenchili.surfmate.wallbox.WallboxAdresse;
import at.greenchili.surfmate.wallbox.WallboxDienst;
import at.greenchili.surfmate.wallbox.WallboxFehler;
import at.greenchili.surfmate.wallbox.WallboxHttp;
import at.greenchili.surfmate.wallbox.WallboxKonfig;
import at.greenchili.surfmate.wallbox.WallboxQuelle;

import org.json.JSONObject;

import java.io.File;
import java.io.OutputStream;

/**
 * Wallbox (WALLBOX_VERTRAG v1 §6, ab 0.1.11). Nur mit Login; gekoppelte Geräte sperrt {@link ExternGuard}
 * (keiner dieser Pfade steht in der Erlaubnisliste).
 * <ul>
 *   <li>{@code GET  /api/wallbox/status}</li>
 *   <li>{@code GET|POST /api/wallbox/einstellungen} – nie Geheimnisse in der Antwort</li>
 *   <li>{@code POST /api/wallbox/test} – nur lesender Abruf mit ungespeicherten Einstellungen</li>
 *   <li>{@code POST /api/wallbox/freigabe}, {@code POST /api/wallbox/steuern} – zweistufig bestätigt, nicht während der Fahrt</li>
 *   <li>{@code GET  /api/wallbox/protokoll}</li>
 * </ul>
 * Zugangsdaten: nur im Auto, AES-GCM über {@link CredentialCipher}; nie an unsere Server.
 */
public final class WallboxApiHandler {

    private WallboxApiHandler() {}

    private static volatile WallboxDienst dienst;

    static final WallboxKonfig.Geheim GEHEIM = new WallboxKonfig.Geheim() {
        @Override
        public String ver(String klar) throws WallboxFehler {
            String enc = CredentialCipher.encrypt(klar);
            // fail-closed: nie Klartext speichern (CredentialCipher liefert bei Fehlern den Klartext zurück)
            if (!CredentialCipher.isEncrypted(enc)) throw new WallboxFehler("verschluesselung", 500);
            return enc;
        }

        @Override
        public String ent(String gespeichert) {
            if (gespeichert == null || gespeichert.isEmpty()) return "";
            return CredentialCipher.isEncrypted(gespeichert) ? CredentialCipher.decrypt(gespeichert) : "";
        }
    };

    static WallboxDienst dienst() {
        WallboxDienst d = dienst;
        if (d == null) {
            synchronized (WallboxApiHandler.class) {
                d = dienst;
                if (d == null) {
                    d = new WallboxDienst(
                            new File(DaemonStorage.path("wallbox.json")),
                            new File(DaemonStorage.path("wallbox_protokoll.jsonl")),
                            GEHEIM, WallboxHttp.STANDARD, WallboxAdresse.DNS,
                            WallboxDienst.aus(WallboxApiHandler::telemetrie, System::currentTimeMillis),
                            System::currentTimeMillis, WallboxQuelle.ECHT,
                            at.greenchili.surfmate.wallbox.TuyaLan.TCP, WallboxApiHandler::tuyaModelle)
                            .mitDirekt(() -> webDaten("wallbox-modbus-modelle.json"),
                                    at.greenchili.surfmate.wallbox.KebaUdpAdapter.Udp.ECHT);
                    dienst = d;
                }
            }
        }
        return d;
    }

    /** Tuya-Modelltabelle (Datentabelle, nicht im Code): aus den entpackten Web-Assets. */
    private static JSONObject tuyaModelle() {
        return webDaten("wallbox-tuya-modelle.json");
    }

    /** Datentabelle aus den entpackten Web-Assets (web/shared/…). */
    private static JSONObject webDaten(String name) {
        try {
            File f = new File(DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/web/shared/" + name));
            return new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return null;
        }
    }

    private static JSONObject telemetrie() {
        try {
            at.greenchili.surfmate.mqtt.MqttConnectionManager m = SurveillanceIpcServer.mqttManager();
            return m == null ? null : m.getLatestTelemetry();
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String p = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        WallboxDienst.Ergebnis r;
        switch (method + " " + p) {
            case "GET /api/wallbox/status": r = dienst().status(); break;
            case "GET /api/wallbox/einstellungen": r = dienst().einstellungen(); break;
            case "POST /api/wallbox/einstellungen": r = dienst().speichereEinstellungen(json(body)); break;
            case "POST /api/wallbox/test": r = dienst().test(json(body)); break;
            case "POST /api/wallbox/ha/vorschlag": r = dienst().haVorschlag(json(body)); break;
            case "POST /api/wallbox/freigabe": r = dienst().freigabe(json(body), "panoptes"); break;
            case "POST /api/wallbox/steuern": r = dienst().steuern(json(body), "panoptes"); break;
            case "GET /api/wallbox/protokoll": r = dienst().protokoll(); break;
            default: return false;
        }
        HttpResponse.sendJson(out, r.status, r.json.toString());
        return true;
    }

    private static JSONObject json(String body) {
        try {
            return body == null || body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
        } catch (Exception e) {
            return null;
        }
    }
}
