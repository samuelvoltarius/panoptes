package at.greenchili.surfmate.telegram;

/**
 * 0.1.21 Telegram-Knöpfe [Erlauben] [Ablehnen] für die Web-Anmeldung per Telegram ({@code TelegramAnmeldung}). Rein.
 * Callback-Daten {@code ta:<16 hex>:j|n}; nur der gekoppelte Besitzer aus seinem Chat ({@link KopplungsKnopf#berechtigt}).
 */
public final class AnmeldungsKnopf {

    private AnmeldungsKnopf() {}

    public static final String PREFIX = "ta:";

    public static String daten(String ref, boolean erlauben) {
        return PREFIX + ref + (erlauben ? ":j" : ":n");
    }

    /** {ref, "j"|"n"} oder null. */
    public static String[] lies(String data) {
        if (data == null || !data.startsWith(PREFIX)) return null;
        String[] p = data.substring(PREFIX.length()).split(":");
        if (p.length != 2 || !p[0].matches("^[0-9a-f]{16}$") || !(p[1].equals("j") || p[1].equals("n"))) return null;
        return p;
    }

    /** Pfad am Kamera-Daemon (voller Login, nie Kopplung, nie Web-Sitzung). */
    public static String pfad(String ref, boolean erlauben) {
        return "/api/telegram-anmeldung/" + ref + (erlauben ? "/erlauben" : "/ablehnen");
    }

    public static String ergebnisSchluessel(int httpCode, boolean erlauben) {
        switch (httpCode) {
            case 200: return erlauben ? "anmeldung.erlaubt" : "anmeldung.abgelehnt";
            case 409: return "kopplung.schon_entschieden";
            case 404:
            case 410: return "kopplung.abgelaufen";
            default:  return "kopplung.fehler";
        }
    }
}
