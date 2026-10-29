package at.greenchili.surfmate.byd.cloud;

import at.greenchili.surfmate.logging.DaemonLogger;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 0.1.26 (Koordination 28.10.): Klima-Felder aus den Cloud-Realtime-Antworten ins Protokoll, damit eine echte Aufnahme
 * zeigt, wie {@code airRunState} und die Sollwert-Felder bei per Cloud gestartetem Klima und ACC aus aussehen. Nur
 * lesend, kein Befehl.
 *
 * <ul>
 *   <li>Nur Felder, deren Name nach Klima aussieht (Muster {@link #KLIMA}), nur einfache Werte, nie VIN, Kennzeichen,
 *       Token oder Koordinaten ({@link #NIE}).</li>
 *   <li>Gedrosselt: höchstens eine Zeile je 10 min. Ausnahme: die erste Antwort nach einem Cloud-Klimabefehl
 *       (OPENAIR/CLOSEAIR) innerhalb von 5 min wird immer geschrieben, sonst verpasste die Drossel genau den Fall.</li>
 *   <li>Zu jedem OPENAIR eine Zeile mit dem Sollwert, der an BYD ging, und seiner Umrechnung (Roh = Stufe).</li>
 * </ul>
 */
public final class KlimaRohProtokoll {

    static final long DROSSEL_MS = 10 * 60_000L;
    static final long NACH_BEFEHL_MS = 5 * 60_000L;

    static final Pattern KLIMA = Pattern.compile(
            "(?i).*(air|temp|hvac|clim|cycle|wind|remotemode|timespan|setting|defrost|ac[A-Z_]?).*");
    static final Pattern NIE = Pattern.compile(
            "(?i)(.*(vin|plate|licen|token|sign|secret|passw|key|latitude|longitude|gps|user|phone|mail|name).*"
                    + "|lat|lng|lon|.*id)");

    private static final DaemonLogger logger = DaemonLogger.getInstance("KlimaRoh");
    private static long letzteZeile;
    private static long letzterBefehl;

    private KlimaRohProtokoll() {}

    /** Nach jeder Realtime-/MQTT-Antwort; schreibt höchstens gedrosselt. */
    public static void antwort(JSONObject vehicleInfo, JSONObject hvac) {
        try {
            String z = pruefen(vehicleInfo, hvac, System.currentTimeMillis());
            if (z != null) logger.info(z);
        } catch (Throwable ignored) { }
    }

    /** Nach einem Cloud-Klimabefehl: OPENAIR samt Sollwert und Umrechnung, CLOSEAIR nur als Marke. */
    public static void befehl(String typ, JSONObject params, boolean erfolg, String code) {
        try {
            String z = befehlZeile(typ, params, erfolg, code, System.currentTimeMillis());
            if (z != null) logger.info(z);
        } catch (Throwable ignored) { }
    }

    static synchronized String pruefen(JSONObject vi, JSONObject hvac, long jetzt) {
        if (vi == null && hvac == null) return null;
        boolean nachBefehl = letzterBefehl > 0 && jetzt - letzterBefehl <= NACH_BEFEHL_MS;
        if (!nachBefehl && letzteZeile > 0 && jetzt - letzteZeile < DROSSEL_MS) return null;
        letzteZeile = jetzt;
        if (nachBefehl) letzterBefehl = 0;
        return zeile(vi, hvac, nachBefehl);
    }

    static synchronized String befehlZeile(String typ, JSONObject p, boolean erfolg, String code, long jetzt) {
        if (!"OPENAIR".equals(typ) && !"CLOSEAIR".equals(typ)) return null;
        letzterBefehl = jetzt;
        StringBuilder b = new StringBuilder("Cloud ").append(typ).append(erfolg ? " ok" : " fehlgeschlagen");
        if (!erfolg && code != null) b.append(" code=").append(code);
        if (p != null && p.has("mainSettingTemp")) {
            int roh = p.optInt("mainSettingTemp", -1);
            // 0.1.26: Surf Roh = Stufe 1–17 (Annahme), sonst pyBYD °C − 14
            int sdk = at.greenchili.surfmate.byd.KlimaStufe.sdkAusCloudRoh(roh);
            b.append(": mainSettingTemp=").append(roh).append(" (= SDK ").append(sdk).append(", ")
                    // 0.1.28: Cloud-Weg ohne unbelegte Stufenzahl; die Rohzahl oben reicht für die Messung (P2)
                    .append(at.greenchili.surfmate.byd.KlimaStufe.anzeigeCloud(sdk))
                    .append(at.greenchili.surfmate.byd.KlimaStufe.stufenModell()
                            ? (at.greenchili.surfmate.byd.KlimaStufe.cloudSkalaBelegt() ? ", Roh = Stufe" : ", Roh = Stufe (Annahme)")
                            : ", Roh = °C − 14").append(")")
                    .append(" copilotSettingTemp=").append(p.opt("copilotSettingTemp"))
                    .append(" timeSpan=").append(p.opt("timeSpan"))
                    .append(" remoteMode=").append(p.opt("remoteMode"))
                    .append(" cycleMode=").append(p.opt("cycleMode"));
        }
        return b.toString();
    }

    /** Die Zeile selbst: Zeitstempel der Quelle plus alle Klima-Felder, sortiert. */
    static String zeile(JSONObject vi, JSONObject hvac, boolean nachBefehl) {
        Map<String, Object> f = new TreeMap<>();
        sammeln("", vi, f, 0);
        sammeln("hvac.", hvac, f, 0);
        StringBuilder b = new StringBuilder("Cloud-Klima").append(nachBefehl ? " (nach Befehl)" : "");
        Object zeit = vi != null ? vi.opt("time") : null;
        if (zeit != null) b.append(" time=").append(zeit);
        for (Map.Entry<String, Object> e : f.entrySet()) b.append(' ').append(e.getKey()).append('=').append(e.getValue());
        if (f.isEmpty()) b.append(" (keine Klima-Felder)");
        return b.toString();
    }

    private static void sammeln(String praefix, JSONObject o, Map<String, Object> f, int tiefe) {
        if (o == null || tiefe > 2) return;
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            if (NIE.matcher(k).matches()) continue;
            Object v = o.opt(k);
            if (v instanceof JSONObject) {
                sammeln(praefix + k + ".", (JSONObject) v, f, tiefe + 1);
            } else if ((v instanceof Number || v instanceof Boolean
                    || (v instanceof String && ((String) v).length() <= 20)) && KLIMA.matcher(k).matches()) {
                f.put(praefix + k, v instanceof String ? ((String) v).replace(' ', '_') : v);
            }
        }
    }

    /** Nur für Tests. */
    static synchronized void zuruecksetzen() {
        letzteZeile = 0;
        letzterBefehl = 0;
    }
}
