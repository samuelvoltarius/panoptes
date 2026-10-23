package at.greenchili.surfmate.ble;

import org.json.JSONObject;

import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.1.16: BLE-Zählen (Lese-Endpunkt {@code GET /api/ble/zaehlen}, Vorstufe zum BLE-Fühler im Camping-Plan).
 *
 * <p>Regeln (Koordination 23.10.): nur wenn Bluetooth an ist; Panoptes schaltet Bluetooth oder den Standortdienst
 * NIE selbst ein. Ergebnis nur Anzahl und grobe Typen – keine Adressen, keine Namen, keine Rohdaten; Adressen dienen
 * nur im Speicher des Scans zum Entdoppeln. Gescannt wird in der App (der Daemon mit UID 2000 darf nicht scannen);
 * der Daemon stößt den Scan per {@code am broadcast} an und liest das Ergebnis aus der Broadcast-Antwort.
 *
 * <p>Hier nur die reinen Teile (Typbestimmung, Zusammenfassung, Antwort von {@code am broadcast}) – JVM-testbar.
 */
public final class BleZaehlung {

    public static final String AKTION = "at.greenchili.surfmate.BLE_ZAEHLEN";
    public static final String RECEIVER = "at.greenchili.surfmate/.receiver.BleZaehlReceiver";
    public static final long SCAN_MS = 8_000L;

    // Zustände, wenn nicht gezählt werden kann (nie selbst einschalten)
    public static final String BT_AUS = "bt_aus";
    public static final String BT_FEHLT = "bt_fehlt";
    public static final String STANDORT_AUS = "standort_aus";
    public static final String RECHT_FEHLT = "recht_fehlt";
    public static final String SCAN_FEHLER = "scan_fehler";

    private BleZaehlung() {}

    /** Grober Typ aus 16-Bit-Dienst-UUIDs (Dienstdaten) und Hersteller-IDs der Werbung. */
    public static String typ(Collection<Integer> dienste16, Collection<Integer> hersteller) {
        if (dienste16 != null) {
            if (dienste16.contains(0xFCD2)) return "bthome";        // BTHome v2 (z. B. LYWSD03MMC mit pvvx)
            if (dienste16.contains(0xFE95)) return "xiaomi";        // Xiaomi MiBeacon
            if (dienste16.contains(0xFD3D)) return "switchbot";
            if (dienste16.contains(0x181A)) return "umwelt";        // Environmental Sensing (ATC-Firmware)
        }
        if (hersteller != null) {
            if (hersteller.contains(0xEC88)) return "govee";
            if (hersteller.contains(0x004C)) return "apple";
            if (hersteller.contains(0x0006)) return "microsoft";
            if (hersteller.contains(0x0075)) return "samsung";
        }
        return "sonstige";
    }

    /** Antwort: {ok:true, anzahl, typen:{typ:n}, scanMs} – nur Zahlen, keine Kennungen. */
    public static JSONObject ergebnis(Map<String, String> typJeGeraet, long scanMs) {
        Map<String, Integer> typen = new TreeMap<>();
        for (String t : typJeGeraet.values()) typen.merge(t == null ? "sonstige" : t, 1, Integer::sum);
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true).put("anzahl", typJeGeraet.size()).put("typen", new JSONObject(typen))
                    .put("scanMs", scanMs);
        } catch (Exception ignored) { }
        return o;
    }

    public static JSONObject nicht(String zustand) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false).put("zustand", zustand);
        } catch (Exception ignored) { }
        return o;
    }

    private static final Pattern DATA = Pattern.compile("Broadcast completed: result=-?\\d+, data=\"(.*)\"",
            Pattern.DOTALL);

    /** Ergebnis aus der Ausgabe von {@code am broadcast} ({@code data="…"}); null, wenn keins da ist. */
    public static JSONObject ausAmAusgabe(String ausgabe) {
        if (ausgabe == null) return null;
        Matcher m = DATA.matcher(ausgabe.trim());
        if (!m.find()) return null;
        try {
            JSONObject o = new JSONObject(m.group(1));
            // nur erlaubte Felder weiterreichen (Schutz gegen alles, was nicht hierher gehört)
            JSONObject r = new JSONObject();
            for (String k : new String[]{"ok", "anzahl", "typen", "scanMs", "zustand"}) {
                if (o.has(k)) r.put(k, o.get(k));
            }
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    /** Shell-Befehl, mit dem der Daemon den Scan in der App anstößt (expliziter Receiver, wartet aufs Ergebnis). */
    public static String amBefehl() {
        return "am broadcast -a " + AKTION + " -n " + RECEIVER;
    }
}
