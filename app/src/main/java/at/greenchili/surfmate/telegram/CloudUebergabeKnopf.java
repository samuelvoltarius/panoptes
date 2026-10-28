package at.greenchili.surfmate.telegram;

/**
 * Telegram-Knöpfe [Erlauben] [Ablehnen] der BYD-Cloud-Übergabe (CLOUD_UEBERGABE_VERTRAG, Entscheidung Koordination:
 * wie beim Koppeln). Rein. Callback-Daten {@code cu:<16 hex>:j|n}; nur der gekoppelte Besitzer aus seinem Chat
 * ({@link KopplungsKnopf#berechtigt}). Einmal-Wirkung und Ablauf prüft der Kamera-Daemon (409/410).
 */
public final class CloudUebergabeKnopf {

    private CloudUebergabeKnopf() {}

    public static final String PREFIX = "cu:";
    /** 0.1.25 „weiteres Gerät koppeln“ (gleiche Rückfrage, anderer Pfad). */
    public static final String PREFIX_WEITERES = "wg:";

    public static String daten(String refHex, boolean erlauben) {
        return PREFIX + refHex + (erlauben ? ":j" : ":n");
    }

    public static String datenWeiteres(String refHex, boolean erlauben) {
        return PREFIX_WEITERES + refHex + (erlauben ? ":j" : ":n");
    }

    /** {hex, "j"|"n", "cu"|"wg"} oder null. */
    public static String[] lies(String data) {
        if (data == null) return null;
        String art = data.startsWith(PREFIX) ? "cu" : data.startsWith(PREFIX_WEITERES) ? "wg" : null;
        if (art == null) return null;
        String[] p = data.substring(3).split(":");
        if (p.length != 2 || !p[0].matches("^[0-9a-f]{16}$") || !(p[1].equals("j") || p[1].equals("n"))) return null;
        return new String[]{p[0], p[1], art};
    }

    /** Pfad am Kamera-Daemon (voller Login, nie Kopplungs-JWT). */
    public static String pfad(String refHex, boolean erlauben) {
        return "/api/cloud-uebergabe/r-" + refHex + (erlauben ? "/erlauben" : "/ablehnen");
    }

    public static String pfad(String[] k, boolean erlauben) {
        return "wg".equals(k[2]) ? "/api/kopplung/weiteres-geraet/r-" + k[0] + (erlauben ? "/erlauben" : "/ablehnen")
                : pfad(k[0], erlauben);
    }

    public static String ergebnisSchluessel(String[] k, int httpCode, boolean erlauben) {
        if ("wg".equals(k[2]) && httpCode == 200) return erlauben ? "weiteres_geraet.erlaubt" : "weiteres_geraet.abgelehnt";
        return ergebnisSchluessel(httpCode, erlauben);
    }

    public static String ergebnisSchluessel(int httpCode, boolean erlauben) {
        switch (httpCode) {
            case 200: return erlauben ? "cloud_uebergabe.erlaubt" : "cloud_uebergabe.abgelehnt";
            case 409: return "kopplung.schon_entschieden";
            case 404:
            case 410: return "kopplung.abgelaufen";
            default:  return "kopplung.fehler";
        }
    }
}
