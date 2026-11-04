package at.greenchili.surfmate.ble;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 0.1.38.8 Aufsperren beim Annähern (ANNAEHERUNG_VERTRAG §2): Nähe-Bake des gekoppelten Handys. Das Handy sendet in den
 * BLE-Dienstdaten unter {@link #DIENST_UUID} 8 Byte {@code HMAC-SHA256(bake, "panoptes-bake-v1|" + 30-s-Fenster)}.
 * Das Auto scannt NIE dauerhaft (12 V): nur kurz ({@link #SCAN_MS}) als Antwort auf eine gültig signierte Meldung, in der
 * App ({@code BleNaeheReceiver}, der Daemon mit UID 2000 darf nicht scannen), angestoßen per {@code am broadcast}.
 * Schaltet nie Bluetooth oder den Standortdienst ein.
 *
 * <p>Hier nur die reinen Teile (Bake rechnen, Befehl, Antwort lesen) – JVM-testbar.
 */
public final class BleNaehe {

    public static final String AKTION = "at.greenchili.surfmate.BLE_NAEHE";
    public static final String RECEIVER = "at.greenchili.surfmate/.receiver.BleNaeheReceiver";
    public static final String DIENST_UUID = "7a1c0d9e-5f2b-4c61-9b3e-50414e4f5054";
    /** Höchstens 20 s (Auflage Koordination 04.11.), hier 15 s. */
    public static final long SCAN_MS = 15_000L;
    public static final int RSSI_MIN = -75;
    public static final long FENSTER_MS = 30_000L;

    private static final Pattern HEX16 = Pattern.compile("[0-9a-f]{16}");

    private BleNaehe() {}

    /** Bake (16 Hex-Zeichen) für ein Zeitfenster; null bei ungültigem Geheimnis. */
    public static String bake(byte[] geheimnis, long zeitMs) {
        if (geheimnis == null || geheimnis.length < 16) return null;
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(geheimnis, "HmacSHA256"));
            byte[] h = m.doFinal(("panoptes-bake-v1|" + Math.floorDiv(zeitMs, FENSTER_MS)).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", h[i] & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Erwartete Baken: aktuelles und die beiden benachbarten Fenster (Uhren laufen nie ganz gleich). */
    public static List<String> erwartet(byte[] geheimnis, long jetztMs) {
        List<String> l = new ArrayList<>();
        for (long d = -1; d <= 1; d++) {
            String b = bake(geheimnis, jetztMs + d * FENSTER_MS);
            if (b != null && !l.contains(b)) l.add(b);
        }
        return l;
    }

    /** Shell-Befehl für den kurzen Scan; nur Hex-Baken, sonst null (nichts Fremdes in die Shell). */
    public static String amBefehl(List<String> baken) {
        if (baken == null || baken.isEmpty() || baken.size() > 3) return null;
        for (String b : baken) if (b == null || !HEX16.matcher(b).matches()) return null;
        return "am broadcast -a " + AKTION + " -n " + RECEIVER + " --es baken " + String.join(",", baken)
                + " --el scanMs " + SCAN_MS;
    }

    private static final Pattern DATA = Pattern.compile("Broadcast completed: result=-?\\d+, data=\"(.*)\"",
            Pattern.DOTALL);

    /**
     * Antwort aus {@code am broadcast}: {@code {ok, gesehen, rssi}} bzw. {@code {ok:false, zustand}}; null ohne Antwort.
     * Nur erlaubte Felder. „gesehen“ zählt nur mit RSSI ≥ {@link #RSSI_MIN}.
     */
    public static JSONObject ausAmAusgabe(String ausgabe) {
        if (ausgabe == null) return null;
        Matcher m = DATA.matcher(ausgabe.trim());
        if (!m.find()) return null;
        try {
            JSONObject o = new JSONObject(m.group(1));
            JSONObject r = new JSONObject();
            r.put("ok", o.optBoolean("ok", false));
            if (o.has("zustand")) r.put("zustand", o.optString("zustand"));
            int rssi = o.optInt("rssi", -127);
            r.put("rssi", rssi);
            r.put("gesehen", o.optBoolean("ok", false) && o.optBoolean("gesehen", false) && rssi >= RSSI_MIN);
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    /** Dienstdaten (Bytes der Werbung) zu Hex, wie die erwarteten Baken; nur die ersten 8 Byte zählen. */
    public static String hex(byte[] daten) {
        if (daten == null || daten.length < 8) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append(String.format("%02x", daten[i] & 0xff));
        return sb.toString();
    }
}
