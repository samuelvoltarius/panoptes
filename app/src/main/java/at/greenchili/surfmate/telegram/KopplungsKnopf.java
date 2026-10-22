package at.greenchili.surfmate.telegram;

/**
 * Telegram-Knöpfe [Erlauben] [Ablehnen] einer Kopplungs-Anfrage (KOPPLUNG_ANFRAGE.md). Rein.
 *
 * <p>Callback-Daten {@code kp:<ref 16 hex>:j|n (nie die Abhol-id)}. Gültig nur, wenn Absender UND Chat der gekoppelte
 * Besitzer sind. Einmal-Wirkung und Ablauf prüft der Kamera-Daemon (409/410).
 */
public final class KopplungsKnopf {

    private KopplungsKnopf() {}

    public static final String PREFIX = "kp:";

    /** Callback-Daten für eine Anfrage. */
    public static String daten(String ref, boolean erlauben) {
        return PREFIX + ref + (erlauben ? ":j" : ":n");
    }

    /** {id, "j"|"n"} oder null bei ungültigen Daten. */
    public static String[] lies(String data) {
        if (data == null || !data.startsWith(PREFIX)) return null;
        String[] p = data.substring(PREFIX.length()).split(":");
        if (p.length != 2 || !p[0].matches("^[0-9a-f]{16}$") || !(p[1].equals("j") || p[1].equals("n"))) return null;
        return p;
    }

    /** Nur der gekoppelte Besitzer, und nur aus seinem Chat. */
    public static boolean berechtigt(long absender, long chat, long besitzer) {
        return besitzer > 0 && absender == besitzer && chat == besitzer;
    }

    /** Pfad am Kamera-Daemon (nur mit vollem Login, nie Kopplungs-JWT). */
    public static String pfad(String ref, boolean erlauben) {
        return "/api/kopplung/anfrage/" + ref + (erlauben ? "/erlauben" : "/ablehnen");
    }

    /** Text-Schlüssel (telegram.kopplung.*) zur Antwort des Daemons. */
    public static String ergebnisSchluessel(int httpCode, boolean erlauben) {
        switch (httpCode) {
            case 200: return erlauben ? "kopplung.erlaubt" : "kopplung.abgelehnt";
            case 409: return "kopplung.schon_entschieden";
            case 404:
            case 410: return "kopplung.abgelaufen";
            default:  return "kopplung.fehler";
        }
    }

    /** Markdown-Sonderzeichen im Gerätenamen entschärfen (parse_mode Markdown). */
    public static String md(String s) {
        if (s == null) return "";
        return s.replaceAll("([_*\\[\\]`])", "\\\\$1");
    }
}
