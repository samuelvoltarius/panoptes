package at.greenchili.surfmate.wallbox;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Tuya Cloud (OpenAPI mit dem eigenen Entwicklerprojekt des Nutzers), WALLBOX_VERTRAG §4b. Schickt Daten an
 * Tuya-Server und läuft deshalb nur mit eigener Einwilligung (Regel 2; Prüfung im {@link WallboxDienst}).
 * Signatur „HMAC-SHA256“ nach Tuya-Doku; gegen tinytuya 1.20 (MIT) als Orakel geprüft (WallboxTuyaTest).
 * Das Geheimnis selbst wird nie gesendet, nur die Signatur.
 */
public final class TuyaCloud {

    /** Erlaubte Rechenzentren (Vertrag §2: nur diese Hosts, nur https). */
    public static final Map<String, String> REGIONEN = new HashMap<>();
    static {
        REGIONEN.put("eu", "https://openapi.tuyaeu.com");
        REGIONEN.put("eu-west", "https://openapi-weaz.tuyaeu.com");
        REGIONEN.put("us", "https://openapi.tuyaus.com");
        REGIONEN.put("us-east", "https://openapi-ueaz.tuyaus.com");
        REGIONEN.put("cn", "https://openapi.tuyacn.com");
        REGIONEN.put("in", "https://openapi.tuyain.com");
    }

    private final String basis;
    private final String clientId;
    private final String geheimnis;
    private final WallboxHttp http;
    private final LongSupplier uhr;
    private String token;
    private long tokenBis;

    public TuyaCloud(String region, String clientId, String geheimnis, WallboxHttp http, LongSupplier uhr) throws WallboxFehler {
        this.basis = REGIONEN.get(region);
        if (basis == null) throw new WallboxFehler("einstellung_ungueltig");
        if (clientId == null || !clientId.matches("[A-Za-z0-9]{8,64}")) throw new WallboxFehler("einstellung_ungueltig");
        if (geheimnis == null || geheimnis.isEmpty()) throw new WallboxFehler("anmeldung", 502);
        this.clientId = clientId;
        this.geheimnis = geheimnis;
        this.http = http;
        this.uhr = uhr;
    }

    /** Status als {code: wert}. */
    public JSONObject status(String geraeteId) throws WallboxFehler, JSONException {
        JSONObject r = rufe("GET", "/v1.0/iot-03/devices/" + id(geraeteId) + "/status", null);
        JSONArray a = r.optJSONArray("result");
        if (a == null) throw new WallboxFehler("antwort_ungueltig", 502);
        JSONObject o = new JSONObject();
        for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.optJSONObject(i);
            if (e != null && e.has("code")) o.put(e.getString("code"), e.opt("value"));
        }
        return o;
    }

    /**
     * 0.1.20 Tuya D′ (WALLBOX_FERN_KONZEPT §4): local_key des Geräts einmal über das eigene IoT-Projekt holen, danach
     * steuert Panoptes nur noch lokal (Ablauf der Testlizenz ist dann egal). Der Schlüssel verlässt den Dienst nie.
     */
    public String lokalerSchluessel(String geraeteId) throws WallboxFehler, JSONException {
        JSONObject r = rufe("GET", "/v1.0/devices/" + id(geraeteId), null);
        JSONObject res = r.optJSONObject("result");
        String k = res == null ? "" : res.optString("local_key", "");
        if (k.length() != 16) throw new WallboxFehler("tuya_schluessel_fehlt", 502);
        return k;
    }

    public void befehle(String geraeteId, JSONObject codes) throws WallboxFehler, JSONException {
        JSONArray cmds = new JSONArray();
        for (java.util.Iterator<String> it = codes.keys(); it.hasNext(); ) {
            String k = it.next();
            cmds.put(new JSONObject().put("code", k).put("value", codes.get(k)));
        }
        rufe("POST", "/v1.0/iot-03/devices/" + id(geraeteId) + "/commands", new JSONObject().put("commands", cmds).toString());
    }

    private static String id(String g) throws WallboxFehler {
        if (g == null || !g.matches("[A-Za-z0-9]{6,64}")) throw new WallboxFehler("einstellung_ungueltig");
        return g;
    }

    private String token() throws WallboxFehler, JSONException {
        long jetzt = uhr.getAsLong();
        if (token != null && jetzt < tokenBis) return token;
        JSONObject r = anfrage("GET", "/v1.0/token?grant_type=1", null, "");
        JSONObject res = r.optJSONObject("result");
        if (res == null || res.optString("access_token", "").isEmpty()) throw new WallboxFehler("anmeldung", 502);
        token = res.getString("access_token");
        tokenBis = jetzt + Math.max(60, res.optLong("expire_time", 3600) - 60) * 1000L;
        return token;
    }

    private JSONObject rufe(String methode, String pfad, String rumpf) throws WallboxFehler, JSONException {
        return anfrage(methode, pfad, rumpf, token());
    }

    private JSONObject anfrage(String methode, String pfad, String rumpf, String tok) throws WallboxFehler, JSONException {
        String t = Long.toString(uhr.getAsLong());
        Map<String, String> kopf = new HashMap<>();
        kopf.put("client_id", clientId);
        kopf.put("t", t);
        kopf.put("sign_method", "HMAC-SHA256");
        if (!tok.isEmpty()) kopf.put("access_token", tok);
        kopf.put("sign", signatur(clientId, geheimnis, tok, t, methode, pfad, rumpf));
        WallboxHttp.Antwort a = http.anfrage(methode, basis + pfad, kopf, rumpf);
        EvccQuelle.pruefeCode(a);
        JSONObject j;
        try {
            j = new JSONObject(a.text);
        } catch (Exception e) {
            throw new WallboxFehler("antwort_ungueltig", 502);
        }
        if (!j.optBoolean("success", false)) {
            int code = j.optInt("code", 0);
            // 1010/1011 Token ungültig/abgelaufen, 1004 Signatur, 1106 keine Rechte
            if (code == 1010 || code == 1011) token = null;
            throw new WallboxFehler(code == 1004 || code == 1010 || code == 1011 || code == 1106 ? "anmeldung" : "quelle_fehler", 502);
        }
        return j;
    }

    /** Tuya-OpenAPI-Signatur (ohne Signature-Headers): HMAC-SHA256(secret, client_id + token + t + stringToSign), groß, hex. */
    static String signatur(String clientId, String geheimnis, String tok, String t, String methode, String pfad, String rumpf) {
        String zuSignieren = methode + "\n" + sha256((rumpf == null ? "" : rumpf)) + "\n" + "\n" + pfad;
        byte[] h = TuyaProtokoll.hmac(geheimnis.getBytes(StandardCharsets.UTF_8),
                (clientId + tok + t + zuSignieren).getBytes(StandardCharsets.UTF_8));
        return hex(h).toUpperCase(Locale.ROOT);
    }

    static String sha256(String s) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
