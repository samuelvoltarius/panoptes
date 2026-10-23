package at.greenchili.surfmate.camping;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Camping-Werte für MQTT/HA (0.1.16): nur, was der Modus selbst weiß. Ohne laufendes Camping steht nur
 * {@code camping_aktiv = 0} da; Zeitpunkte als ISO-8601 (HA device_class timestamp). Nie eine Innentemperatur.
 */
public final class CampingTelemetrie {

    private CampingTelemetrie() {}

    public static void eintragen(JSONObject payload, JSONObject status) throws Exception {
        if (status == null || !status.optBoolean("gestartet", false)) return;
        boolean an = status.optBoolean("aktiv", false);
        payload.put("camping_aktiv", an ? 1 : 0);
        if (!an) {
            payload.put("camping_phase", "aus");
            return;
        }
        payload.put("camping_phase", status.optString("phase", "aus"));
        long n = status.optLong("naechsterZyklusMs", 0);
        if (n > 0) payload.put("camping_naechster_zyklus", iso(n));
        JSONObject b = status.optJSONObject("budget");
        if (b != null) {
            long r = b.optLong("reichtBisMs", 0);
            if (r > 0) payload.put("camping_reicht_bis", iso(r));
            payload.put("camping_intervall", b.optInt("intervallMin"));
            payload.put("camping_dauer", b.optInt("dauerMin"));
        } else {
            JSONObject z = status.optJSONObject("tabelle");
            if (z != null) {
                payload.put("camping_intervall", z.optInt("intervallMin"));
                payload.put("camping_dauer", z.optInt("dauerMin"));
            }
        }
        JSONObject v = status.optJSONObject("verbrauch");
        if (v != null && v.has("prozentProStunde")) payload.put("camping_verbrauch", v.getDouble("prozentProStunde"));
    }

    static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT);
        f.setTimeZone(TimeZone.getDefault());
        return f.format(new Date(ms));
    }
}
