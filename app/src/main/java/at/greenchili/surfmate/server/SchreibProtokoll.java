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

    private SchreibProtokoll() {}

    /** Rein: die Logzeile oder null (kein Schreibzugriff auf /api/*). */
    public static String zeile(String method, String path, String herkunft) {
        if (method == null || path == null) return null;
        String m = method.toUpperCase(Locale.ROOT);
        if (!m.equals("PUT") && !m.equals("POST") && !m.equals("DELETE") && !m.equals("PATCH")) return null;
        String p = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if (p.contains("#")) p = p.substring(0, p.indexOf('#'));
        if (!p.startsWith("/api/")) return null;
        StringBuilder sb = new StringBuilder();
        for (String teil : p.substring(1).split("/")) {
            if (teil.isEmpty()) continue;
            sb.append('/').append(WERTARTIG.matcher(teil).matches() ? "{x}" : teil);
        }
        return "SCHREIBEN " + m + " " + sb + " von=" + (herkunft == null ? "?" : herkunft);
    }

    /** Im Daemon: nach Anmeldung und ExternGuard, vor dem Handler. Fehler im Protokoll stören die Anfrage nie. */
    public static void melde(String method, String path, boolean extern, boolean loopback) {
        try {
            String z = zeile(method, path, extern ? "gekoppelt" : loopback ? "lokal" : "login");
            if (z != null) at.greenchili.surfmate.logging.DaemonLogger.getInstance("Schreiben").info(z);
        } catch (Throwable ignored) {
        }
    }
}
