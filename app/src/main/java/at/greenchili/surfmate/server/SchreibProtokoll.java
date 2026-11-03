package at.greenchili.surfmate.server;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Schreib-Protokoll (0.1.10, Koordination): eine Logzeile je PUT/POST/DELETE auf {@code /api/*}, damit nachvollziehbar
 * ist, wer wann was geändert hat – <b>ohne Werte, Tokens oder Positionen</b>. Geloggt werden nur Methode, Pfad
 * (ohne Query; Teile, die wie Kennungen, Schlüssel, Zahlen oder Koordinaten aussehen, werden zu {@code {x}}) und die
 * Herkunft (lokal | login | gekoppelt). Der Rumpf wird nie gelesen.
 */
public final class SchreibProtokoll {

    /** Ziffern, Hex-/Base64-artige Kennungen, Gleichheitszeichen, @ – alles, was ein Wert sein könnte. */
    private static final Pattern WERTARTIG = Pattern.compile(".*(\\d|[=@%:+~]).*|[A-Za-z0-9_-]{24,}");

    private static final Pattern VERSION = Pattern.compile("v[0-9]{1,2}");

    private SchreibProtokoll() {}

    /** Rein: die Logzeile oder null (kein Schreibzugriff auf /api/*). */
    public static String zeile(String method, String path, String herkunft) {
        String p = pfadOhneWerte(method, path);
        if (p == null) return null;
        return "SCHREIBEN " + method.toUpperCase(Locale.ROOT) + " " + p + " von=" + (herkunft == null ? "?" : herkunft);
    }

    /**
     * 0.1.34 Befehls-Log: wie oben, plus Kopplungs-id (nur id-artig, sonst {@code {x}}) und Aktion (vom
     * {@link BefehlsLog} schon gegen die Erlaubnisliste geprüft).
     */
    public static String zeile(String method, String path, String herkunft, String kid, String aktion) {
        String z = zeile(method, path, herkunft);
        if (z == null) return null;
        if (kid != null) z += " kopplung=" + (kid.matches("[A-Za-z0-9_-]{1,64}") ? kid : "{x}");
        if (aktion != null) z += " aktion=" + aktion;
        return z;
    }

    /** 0.1.34: Pfad ohne Query und Werte bei Schreibzugriff auf /api/*, sonst null (auch für {@link BefehlsLog}). */
    static String pfadOhneWerte(String method, String path) {
        if (method == null || path == null) return null;
        String m = method.toUpperCase(Locale.ROOT);
        if (!m.equals("PUT") && !m.equals("POST") && !m.equals("DELETE") && !m.equals("PATCH")) return null;
        String p = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if (p.contains("#")) p = p.substring(0, p.indexOf('#'));
        if (!p.startsWith("/api/")) return null;
        StringBuilder sb = new StringBuilder();
        for (String teil : p.substring(1).split("/")) {
            if (teil.isEmpty()) continue;
            // 0.1.34: API-Versionen (v1, v2) bleiben stehen, sonst hieße jede Handy-Aktion /api/handy/{x}/aktion
            sb.append('/').append(WERTARTIG.matcher(teil).matches() && !VERSION.matcher(teil).matches() ? "{x}" : teil);
        }
        return sb.toString();
    }

    /** Im Daemon: nach Anmeldung und ExternGuard, vor dem Handler. Fehler im Protokoll stören die Anfrage nie. */
    public static void melde(String method, String path, boolean extern, boolean loopback) {
        melde(method, path, extern, loopback, null, null);
    }

    /** 0.1.34: mit Kopplungs-id (Kopplungs-JWT oder Web-Sitzung dahinter, dann {@code von=web}) und Aktion. */
    public static void melde(String method, String path, boolean extern, boolean loopback, String kid, String aktion) {
        try {
            String z = zeile(method, path, extern ? "gekoppelt" : kid != null ? "web" : loopback ? "lokal" : "login",
                    kid, aktion);
            if (z != null) at.greenchili.surfmate.logging.DaemonLogger.getInstance("Schreiben").info(z);
        } catch (Throwable ignored) {
        }
    }
}
