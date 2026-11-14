package at.greenchili.surfmate.ui;

/**
 * 0.1.21 (Koordination): Deep-Links von außen ({@code --es navigate_to trips}, z. B. vom Launcher) landen in der
 * vollflächigen Web-Oberfläche, nicht mehr in der alten nativen Oberfläche. Ziel → Web-Seite (Routen des Daemons).
 */
public final class NavigateZiel {

    private NavigateZiel() {}

    /** Bekannte Ziele, auch für den Test. */
    public static final String[] ZIELE = {
            "trips", "charging", "roadsense", "recordings", "parking", "live", "vehicle",
            "dashboard", "assistant", "waechter", "navi", "system", "display"};

    /** 0.1.28 Rückkehr-Karte: Ziel „eine Wächter-Aufnahme abspielen“, Kennung im Extra {@link #EXTRA_AUFNAHME}. */
    public static final String ZIEL_AUFNAHME = "aufnahme";
    public static final String EXTRA_AUFNAHME = "aufnahme_id";

    /** 0.1.43.4 (Kamera im Launcher): beliebige Aufnahme abspielen (alle Arten), Kennung im Extra {@link #EXTRA_AUFNAHME}. */
    public static final String ZIEL_AUFNAHME_ALLE = "aufnahme_alle";
    /** Live-Ansicht mit vorgewählter Kamera (0 = 360°, 1 vorne, 2 rechts, 3 hinten, 4 links), Extra {@link #EXTRA_KAMERA}. */
    public static final String EXTRA_KAMERA = "kamera_id";

    /** Aufnahmen-Seite, die genau diese Aufnahme (jede Art) abspielt. */
    public static String aufnahmePfadAlle(String id) {
        if (!aufnahmeGueltig(id)) return "/events";
        return "/events?filter=all&id=" + id;
    }

    /** Live-Ansicht, Kamera nur 0..4 (sonst ohne Vorwahl). */
    public static String livePfad(String kamera) {
        if (kamera != null && kamera.matches("[0-4]")) return "/live-view?cam=" + kamera;
        return "/live-view";
    }

    /** Aufnahme-Kennung bzw. Dateiname: nur harmlose Zeichen (kommt in eine URL). */
    public static boolean aufnahmeGueltig(String id) {
        return id != null && id.matches("[A-Za-z0-9_.-]{1,120}");
    }

    /** Aufnahmen-Seite, die genau diese Wächter-Aufnahme sofort abspielt ({@code events.js}: {@code ?id=}). */
    public static String aufnahmePfad(String id) {
        if (!aufnahmeGueltig(id)) return "/events?filter=sentry";
        return "/events?filter=sentry&id=" + id;
    }

    /** Web-Pfad für ein Ziel, oder null (unbekannt → ignorieren, vorwärts/rückwärts verträglich). */
    public static String pfad(String ziel) {
        if (ziel == null) return null;
        switch (ziel) {
            case "trips": return "/trips";
            case "charging": return "/charging";
            case "roadsense": return "/road-sense";
            case "recordings": return "/events";
            case "parking": return "/parking";
            case "live": return "/live-view";
            case "vehicle": return "/vehicle-control";
            case "dashboard": return "/";
            case "assistant": return "/assistant";
            case "waechter": return "/waechter";
            case "navi": return "/navi";
            case "system": return "/system";
            // 0.1.41 (LC): Launcher „Navi aufs Kombi“ → Freigabe/Probe der App am Tacho (nur in Panoptes, im Stand)
            case "display": return "/display.html";
            default: return null;
        }
    }
}
