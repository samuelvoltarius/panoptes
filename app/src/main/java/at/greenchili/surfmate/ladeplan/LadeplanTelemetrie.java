package at.greenchili.surfmate.ladeplan;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Ladeplan-Werte für Home Assistant (0.1.21, LADEPLAN_KONZEPT §7): nur lesend, über dieselbe Linie wie Camping
 * (Telemetrie → {@code /api/ha/v1/zustand} bzw. MQTT-Discovery über den {@code TelemetryFieldCatalog}). Quelle ist der
 * letzte Schnappschuss des Ladeplans (kein eigener Wallbox-Abruf). Börsenpreise erscheinen hier nie.
 */
public final class LadeplanTelemetrie {

    private LadeplanTelemetrie() {}

    public static final String[] SCHLUESSEL = {"ladeplan_phase", "ladeplan_laedt", "ladeplan_naechster_start",
            "ladeplan_fertig", "ladeplan_kwh", "ladeplan_kosten", "ladeplan_ersparnis"};

    /** {@code status} = Antwort von {@link LadeplanDienst#status()} bzw. dem letzten Takt, oder null (nichts eintragen). */
    public static void eintragen(JSONObject payload, JSONObject status) throws Exception {
        if (status == null) return;
        JSONObject z = status.optJSONObject("zustand");
        if (z == null) return;
        String phase = z.optString("phase", "aus");
        payload.put("ladeplan_phase", phase);
        payload.put("ladeplan_laedt", "laedt".equals(phase) ? 1 : 0);
        if ("aus".equals(phase)) return;
        JSONObject v = status.optJSONObject("vorschau");
        if (v == null) return;
        long start = v.optLong("start", 0);
        if (start > 0) payload.put("ladeplan_naechster_start", iso(start));
        long fertig = v.optLong("fertig", 0);
        if (fertig > 0) payload.put("ladeplan_fertig", iso(fertig));
        if (v.has("kwh")) payload.put("ladeplan_kwh", v.optDouble("kwh", 0));
        if (v.has("kosten") && v.optDouble("kwh", 0) > 0) payload.put("ladeplan_kosten", v.optDouble("kosten", 0));
        if (v.has("ersparnis") && v.optDouble("kwh", 0) > 0) payload.put("ladeplan_ersparnis", v.optDouble("ersparnis", 0));
    }

    static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT);
        f.setTimeZone(TimeZone.getDefault());
        return f.format(new Date(ms));
    }
}
