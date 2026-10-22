package at.greenchili.surfmate.server;

/**
 * Koordination 0.1.9 (Regel A, Sicherheit; Panoptes wird öffentlich): Kein Debug-Weg darf am Auto etwas
 * auslösen. Die schreibenden Debug-Endpunkte sind im ausgelieferten APK gesperrt (404) – unabhängig vom
 * Build-Typ, weil auch Debug-APKs ans Auto gehen. Lesende Debug-Endpunkte bleiben.
 */
public final class DebugSchreibsperre {

    private DebugSchreibsperre() {}

    /** Schreibende Debug-Pfade (Präfixe ohne Query). */
    static final String[] GESPERRT = {
            "/api/debug/autoservice/remote-control",       // Fernsteuer-Opcodes (POWER_OFF, Fenster, Kofferraum …)
            "/api/debug/autoservice/sdk-remote-control",   // beliebige SDK-Setter per Name
            "/api/debug/car-property/set",                 // beliebige Fahrzeug-Property schreiben
            "/api/debug/light/fire",                       // Licht/Warnblinker auslösen (auch fire-all)
            "/api/debug/seat/write",                       // Sitz bewegen
    };

    public static boolean gesperrt(String path) {
        if (path == null) return false;
        String p = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        for (String g : GESPERRT) {
            if (p.equals(g) || p.startsWith(g)) return true;
        }
        return false;
    }
}
