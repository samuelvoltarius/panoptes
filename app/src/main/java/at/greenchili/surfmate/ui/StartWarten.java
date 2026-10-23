package at.greenchili.surfmate.ui;

/**
 * Hotfix 0.1.15.1: Was die App-Hülle zeigt, wenn der Webserver (:8180) noch nicht antwortet – z. B. direkt nach einem
 * Update, wenn die App vor den Daemons im Vordergrund steht.
 *
 * <p>Statt eines rohen JSON-503 („daemon_unreachable“, dunkelgrau auf Schwarz) zeigt die Hülle „Panoptes startet …“,
 * fragt alle {@link #INTERVALL_MS} ab, ob 127.0.0.1:8180 antwortet, und lädt dann selbst neu. Nach höchstens
 * {@link #HOECHSTENS_MS} erscheint der Knopf „Erneut versuchen“. Reine Entscheidungen, ohne Android – JVM-testbar.
 */
public final class StartWarten {

    public static final long INTERVALL_MS = 2_000L;
    public static final long HOECHSTENS_MS = 3 * 60_000L;

    public enum Schritt { NEU_LADEN, WEITER_WARTEN, AUFGEBEN }

    private final long beginn;

    public StartWarten(long beginnMs) {
        this.beginn = beginnMs;
    }

    /** Nach einer Abfrage: Server antwortet → neu laden; sonst weiter, bis die Höchstzeit um ist. */
    public Schritt nachAbfrage(long jetztMs, boolean antwortet) {
        if (antwortet) return Schritt.NEU_LADEN;
        return jetztMs - beginn >= HOECHSTENS_MS ? Schritt.AUFGEBEN : Schritt.WEITER_WARTEN;
    }

    /** Hauptseite mit 5xx (synthetisch oder vom Server) = Panoptes startet noch. */
    public static boolean istStartFehler(boolean hauptseite, int status) {
        return hauptseite && status >= 500 && status <= 599;
    }

    /** Jede echte HTTP-Antwort unter 500 heißt: der Webserver läuft (auch 401/404). */
    public static boolean antwortet(int status) {
        return status >= 200 && status < 500;
    }

    /** Wurzel des lokalen Webservers zur Seite, z. B. {@code http://127.0.0.1:8180/}; null, wenn nicht lokal. */
    public static String wurzel(String seite) {
        if (seite == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(http://(?:127\\.0\\.0\\.1|localhost):\\d{1,5})(?:/.*)?$").matcher(seite);
        return m.matches() ? m.group(1) + "/" : null;
    }

    /**
     * Ersatz-Hauptseite, falls die Hülle ihre eigene Ansicht nicht zeigen kann: lesbar (hell auf dunkel), nie JSON.
     * {@code titel}/{@code hinweis} kommen aus den App-Texten (de/en/fr/it/nl).
     */
    public static String startSeiteHtml(String titel, String hinweis) {
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + esc(titel) + "</title></head>"
                + "<body style=\"margin:0;height:100vh;display:flex;flex-direction:column;align-items:center;"
                + "justify-content:center;background:#101418;color:#f2f4f7;font-family:sans-serif;text-align:center\">"
                + "<div style=\"font-size:28px;font-weight:600\">" + esc(titel) + "</div>"
                + "<div style=\"margin-top:12px;font-size:18px;color:#c9d1d9\">" + esc(hinweis) + "</div>"
                + "</body></html>";
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
