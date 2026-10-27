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
            "dashboard", "assistant", "waechter", "navi", "system"};

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
            default: return null;
        }
    }
}
