package at.greenchili.surfmate.tacho;

import org.json.JSONObject;

import java.util.regex.Pattern;

/**
 * Rueckmeldung zum Tacho-Faehigkeitstest (0.1.37). Der Text enthaelt nur Modell, DiLink,
 * Tacho-Firmware und die Faehigkeiten ja/nein/offen. Keine VIN, keine IMEI, kein Ort, keine Uhrzeit.
 *
 * <p>Senden nur mit ausdruecklicher Einwilligung fuer genau diese Sendung ({@link #senden}): ohne
 * {@code einwilligung == true} und ohne exakt den gezeigten Text geht nichts raus. Weg ist der
 * vorhandene „Fehler melden“-Kanal ({@code FehlerMelden}, Art {@code tacho_test}).
 */
public final class TachoRueckmeldung {

    private TachoRueckmeldung() {}

    /** VIN: 17 Zeichen ohne I/O/Q. */
    private static final Pattern VIN = Pattern.compile("(?i)\\b[A-HJ-NPR-Z0-9]{17}\\b");
    /** IMEI/Seriennummer-artige lange Ziffernfolgen. */
    private static final Pattern LANGE_ZAHL = Pattern.compile("\\d{12,}");
    /** Koordinaten-artige Dezimalzahlen. */
    private static final Pattern KOORDINATE = Pattern.compile("\\d{1,3}\\.\\d{4,}");
    private static final Pattern ERLAUBT = Pattern.compile("[^A-Za-z0-9 _.()+/-]");

    /** Ein Wert aus dem Auto, bereinigt: keine Kennung, hoechstens 60 Zeichen. */
    static String sauber(String wert) {
        if (wert == null) return "?";
        String v = ERLAUBT.matcher(wert.trim()).replaceAll("");
        v = VIN.matcher(v).replaceAll("[entfernt]");
        v = LANGE_ZAHL.matcher(v).replaceAll("[entfernt]");
        v = KOORDINATE.matcher(v).replaceAll("[entfernt]");
        if (v.length() > 60) v = v.substring(0, 60);
        return v.isEmpty() ? "?" : v;
    }

    private static String status(JSONObject r, String k) {
        JSONObject u = r == null ? null : r.optJSONObject(k);
        String s = u == null ? "" : u.optString("status");
        return TachoFaehigkeit.JA.equals(s) ? "ja" : TachoFaehigkeit.NEIN.equals(s) ? "nein" : "offen";
    }

    /** Der fertige Text aus dem Ergebnis von {@link TachoFaehigkeit#bewerte}. */
    public static String text(JSONObject r) {
        JSONObject p = r == null ? null : r.optJSONObject("plattform");
        StringBuilder sb = new StringBuilder("Panoptes Tacho-Test\n");
        sb.append("Modell: ").append(sauber(p == null ? null : p.optString("modell"))).append('\n');
        sb.append("DiLink: ").append(sauber(p == null ? null : p.optString("dilink")))
                .append(p != null && p.optBoolean("dilinkSicher") ? "" : " (vermutet)").append('\n');
        sb.append("Tacho-Firmware: ").append(sauber(p == null ? null : p.optString("tachoFirmware"))).append('\n');
        sb.append("Single-OS: ").append(p != null && p.optBoolean("singleOs") ? "ja" : "nein").append('\n');
        sb.append("Projektionsdienst: ").append(p != null && p.optBoolean("autoContainer") ? "ja" : "nein").append('\n');
        sb.append("Totwinkel am Tacho: ").append(status(r, "totwinkel")).append('\n');
        sb.append("Karte am Tacho: ").append(status(r, "karte")).append('\n');
        sb.append("Andere Apps am Tacho: ").append(status(r, "andereApps")).append('\n');
        JSONObject a = r == null ? null : r.optJSONObject("andereApps");
        String code = a == null ? "" : a.optString("code");
        sb.append("Warnleuchten in der Probe frei: ")
                .append("warnleuchten_frei".equals(code) ? "ja" : "warnleuchten_verdeckt".equals(code) ? "nein" : "offen")
                .append('\n');
        return sb.toString();
    }

    public interface Sender {
        /** Schickt genau diesen Text; Rueckgabe wie die Fehler-melden-Antwort. */
        JSONObject sende(String text) throws Exception;
    }

    /**
     * Sendet nur bei {@code einwilligung == true} und wenn {@code gezeigt} exakt dem frisch
     * gebauten Text entspricht. Sonst wird der Sender nie aufgerufen.
     */
    public static JSONObject senden(Object einwilligung, String gezeigt, String aktuell, Sender sender)
            throws Exception {
        if (!Boolean.TRUE.equals(einwilligung)) {
            return new JSONObject().put("ok", false).put("grund", "keine_einwilligung");
        }
        if (aktuell == null || aktuell.isEmpty() || !aktuell.equals(gezeigt)) {
            return new JSONObject().put("ok", false).put("grund", "vorschau_alt");
        }
        return sender.sende(aktuell);
    }
}
