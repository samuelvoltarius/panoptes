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
 *   <li>0.1.19: {@code POST /api/wallbox/ha/anmelden} + {@code POST /api/wallbox/ha/rueckruf} („Mit Home Assistant
 *       anmelden“, OAuth), {@code POST /api/wallbox/suche} („Wallbox finden“ im WLAN, nur finden)</li>
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

    /** Auch für den Ladeplan (0.1.20), gleiche Instanz wie die API. */
    public static WallboxDienst dienst() {
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
                                    at.greenchili.surfmate.wallbox.KebaUdpAdapter.Udp.ECHT)
                            .mitBroker(WallboxApiHandler::broker);
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

    /**
     * 0.1.19 Wallbox-Brücke: Broker der eingeschalteten Panoptes-MQTT-Verbindungen. Zugangsdaten bleiben im Daemon
     * (entschlüsselt erst hier, nie in einer Antwort). 0.1.20: Verbindungen, die die Brücke nicht nutzen kann (ssl/ws,
     * Adresse unlesbar), kommen mit {@code fehler} statt still wegzufallen – die Suche zeigt das im Klartext.
     */
    private static java.util.List<JSONObject> broker() {
        java.util.List<JSONObject> aus = new java.util.ArrayList<>();
        try {
            at.greenchili.surfmate.mqtt.MqttConnectionStore s = new at.greenchili.surfmate.mqtt.MqttConnectionStore();
            s.load();
            for (at.greenchili.surfmate.mqtt.MqttConnectionConfig c : s.getEnabled()) {
                JSONObject b = brokerAus(c.brokerUrl, c.port, c.username, c.password);
                b.put("name", c.name == null ? "" : c.name);
                aus.add(b);
            }
        } catch (Throwable t) {
            try {
                aus.add(new JSONObject().put("name", "").put("fehler", "mqtt_einstellungen_unlesbar"));
            } catch (Exception ignored) { }
        }
        return aus;
    }

    /**
     * Adresse einer Panoptes-MQTT-Verbindung → {@code {host, port, benutzer, passwort}}, aufgelöst wie die Verbindung
     * selbst ({@link at.greenchili.surfmate.mqtt.MqttConnectionConfig#getBrokerUri}): nackter Host
     * („100.64.0.20“) = tcp mit dem Port der Verbindung, {@code tcp://} bzw. {@code mqtt://} mit oder ohne Port.
     * Nicht nutzbar (ssl/ws/wss, unlesbar) → {@code {host, port, fehler}}, nie null.
     * (Befund am Auto 27.10.: Verbindung „home“ mit nackter Adresse wurde bis 0.1.19.1 still übergangen.)
     */
    static JSONObject brokerAus(String url, int port, String benutzer, String passwort) {
        int std = port > 0 ? port : 1883;
        String u = url == null ? "" : url.trim();
        try {
            if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
            String klein = u.toLowerCase(java.util.Locale.ROOT);
            if (klein.startsWith("mqtt://")) u = "tcp://" + u.substring(7);
            else if (!klein.contains("://")) u = "tcp://" + u;
            java.net.URI uri = new java.net.URI(u);
            String schema = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                return new JSONObject().put("host", url == null ? "" : url.trim()).put("port", std).put("fehler", "adresse_unlesbar");
            }
            if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
            int p = uri.getPort() > 0 ? uri.getPort() : std;
            if (!"tcp".equals(schema)) {
                return new JSONObject().put("host", host).put("port", p).put("fehler", "nur_tcp");
            }
            return new JSONObject().put("host", host).put("port", p)
                    .put("benutzer", benutzer == null ? "" : benutzer).put("passwort", passwort == null ? "" : passwort);
        } catch (Exception e) {
            try {
                return new JSONObject().put("host", url == null ? "" : url.trim()).put("port", std).put("fehler", "adresse_unlesbar");
            } catch (Exception ignored) {
                return new JSONObject();
            }
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
            case "POST /api/wallbox/ha/anmelden": r = dienst().haAnmeldungStart(json(body)); break;
            case "POST /api/wallbox/ha/rueckruf": r = dienst().haAnmeldungRueckruf(json(body)); break;
            case "POST /api/wallbox/suche": r = dienst().suche(); break;
            case "POST /api/wallbox/freigabe": r = dienst().freigabe(json(body), "panoptes"); break;
            case "POST /api/wallbox/steuern": r = dienst().steuern(json(body), "panoptes"); break;
            case "GET /api/wallbox/protokoll": r = dienst().protokoll(); break;
            // 0.1.20: Tuya D′ (Schlüssel einmal holen), go-e-Token per Handy
            case "POST /api/wallbox/tuya/schluessel": r = dienst().tuyaSchluesselHolen(json(body)); break;
            case "POST /api/wallbox/eingabe": r = dienst().eingabeStart(json(body)); break;
            case "GET /api/wallbox/eingabe": r = dienst().eingabeStatus(query(path, "k")); break;
            default: return false;
        }
        HttpResponse.sendJson(out, r.status, r.json.toString());
        return true;
    }

    private static String query(String path, String name) {
        int q = path.indexOf('?');
        if (q < 0) return "";
        for (String teil : path.substring(q + 1).split("&")) {
            if (teil.startsWith(name + "=")) return teil.substring(name.length() + 1);
        }
        return "";
    }

    /** {@code POST /auth/wallbox-eingabe} ohne Login (Einmal-Kennung, ratenbegrenzt im Dienst). */
    public static void eingabeVomHandy(String body, OutputStream out) throws Exception {
        WallboxDienst.Ergebnis r = dienst().eingabe(json(body));
        HttpResponse.sendJson(out, r.status, r.json.toString());
    }

    private static JSONObject json(String body) {
        try {
            return body == null || body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
        } catch (Exception e) {
            return null;
        }
    }
}
