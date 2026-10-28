package at.greenchili.surfmate.gewohnheiten;

/**
 * 0.1.22 Gewohnheiten (GEWOHNHEITEN_KONZEPT.md §2): Wer hat eine Bedienhandlung ausgelöst. Gilt je Thread – der
 * HTTP-Server setzt sie je Anfrage (Display, Handy, Cockpit, HA), Telegram/MQTT an ihrem Eingang. Nicht gesetzt heißt
 * {@link #AUTOMATION}: im Zweifel wird nicht gezählt.
 */
public final class Herkunft {

    public static final String DISPLAY = "display";
    public static final String HANDY = "handy";
    public static final String COCKPIT = "cockpit";
    public static final String HA = "ha";
    public static final String TELEGRAM = "telegram";
    /** Automation, Ladeplan, Camping-Takt, AcAutoOffTimer, Abschreckung – wird mitgeschrieben, zählt nie. */
    public static final String AUTOMATION = "automation";
    /** Bedienung am Auto mit den BYD-Tasten/-Menüs (Zustandsänderung ohne Panoptes-Befehl, Zündung an). */
    public static final String AUTO = "auto";
    /** Zustandsänderung ohne Panoptes-Befehl bei Zündung aus – vermutlich BYD-App (ein Schluss, kein Beleg). */
    public static final String BYD_APP = "byd_app";
    /** Selbsttätiger BYD-Vorgang (z. B. Klima aus nach Zündung aus) – wird mitgeschrieben, zählt nie. */
    public static final String AUTO_SYSTEM = "auto_system";

    private static final ThreadLocal<String> AKTUELL = new ThreadLocal<>();

    private Herkunft() {}

    /** Für den laufenden Thread setzen; null entfernt. */
    public static void setze(String quelle) {
        if (quelle == null) AKTUELL.remove();
        else AKTUELL.set(quelle);
    }

    /** Gesetzt oder nicht – ohne Rückfall. */
    public static String gesetzt() {
        return AKTUELL.get();
    }

    public static String aktuell() {
        String q = AKTUELL.get();
        return q == null ? AUTOMATION : q;
    }

    /** Zählt diese Herkunft als Handlung eines Menschen? */
    public static boolean zaehlt(String quelle) {
        if (quelle == null) return false;
        switch (quelle) {
            case DISPLAY:
            case HANDY:
            case COCKPIT:
            case HA:
            case TELEGRAM:
            case AUTO:
            case BYD_APP:
                return true;
            default:
                return false;
        }
    }

    /**
     * Herkunft einer HTTP-Anfrage (nach Anmeldung). Pfade der Handy- bzw. HA-API zuerst, dann die Kopplungsart
     * ({@code Kopplungen}: cockpit, homeassistant, handy, web), dann die Web-Sitzung der Handy-App. Alles andere ist
     * ein Mensch an der Panoptes-Weboberfläche – am Display (Loopback) oder im angemeldeten Browser.
     */
    public static String ausAnfrage(boolean gekoppelt, String kopplungsArt, boolean handyWeb, String pfad) {
        if (pfad != null && pfad.startsWith("/api/handy/")) return HANDY;
        if (pfad != null && pfad.startsWith("/api/ha/")) return HA;
        if (gekoppelt) {
            if ("homeassistant".equals(kopplungsArt)) return HA;
            if ("handy".equals(kopplungsArt)) return HANDY;
            if ("web".equals(kopplungsArt)) return DISPLAY;
            return COCKPIT;
        }
        if (handyWeb) return HANDY;
        return DISPLAY;
    }
}
