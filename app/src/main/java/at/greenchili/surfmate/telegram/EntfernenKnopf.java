package at.greenchili.surfmate.telegram;

/**
 * 0.1.25 Telegram-Knopf [Entfernen] an der Meldung „Neues Gerät verbunden“ (Koordination 28.10.). Rein.
 *
 * <p>Callback-Daten {@code ke:<ref 16 hex>} – ein Einmal-Griff, nie die Kopplungs-id. Gültig nur, wenn Absender UND Chat
 * der gekoppelte Besitzer sind ({@link KopplungsKnopf#berechtigt}); Einmal-Wirkung und Ablauf (24 h) prüft der
 * Kamera-Daemon.
 */
public final class EntfernenKnopf {

    private EntfernenKnopf() {}

    public static final String PREFIX = "ke:";

    public static String daten(String ref) {
        return PREFIX + ref;
    }

    /** ref oder null bei ungültigen Daten. */
    public static String lies(String data) {
        if (data == null || !data.startsWith(PREFIX)) return null;
        String ref = data.substring(PREFIX.length());
        return ref.matches("^[0-9a-f]{16}$") ? ref : null;
    }

    /** Pfad am Kamera-Daemon (nur mit vollem Login, nie Kopplungs-JWT). */
    public static String pfad(String ref) {
        return "/api/kopplung/entfernen/" + ref;
    }

    /** Text-Schlüssel (telegram.kopplung.*) zur Antwort des Daemons. */
    public static String ergebnisSchluessel(int httpCode) {
        switch (httpCode) {
            case 200: return "kopplung.entfernt";
            case 404:
            case 410: return "kopplung.entfernen_abgelaufen";
            default:  return "kopplung.fehler";
        }
    }
}
