package at.greenchili.surfmate.ui;

/**
 * 0.1.20 (Befund am Auto, Koordination: nach ACC an kamen styles.css/core.js nicht an, das Display blieb ungestylt):
 * Prüft nach dem Laden einer Seite, ob ihre Kern-Dateien gewirkt haben, und liefert den Backoff fürs Neuladen.
 * Rein, testbar; die WebView-Seite steht in {@code WebViewFragment.kernDateienPruefen}.
 */
public final class KernDateien {

    private KernDateien() {}

    public static final int OK = 0, FEHLT = 1, EGAL = 2;
    public static final int MAX_VERSUCHE = 5;
    private static final long[] WARTE_MS = {2_000L, 4_000L, 8_000L, 15_000L, 30_000L};

    /**
     * Nur Seiten, die styles.css bzw. core.js überhaupt einbinden: styles.css hat gewirkt, wenn --primary (aus
     * design-tokens.css) gesetzt ist; core.js, wenn window.BYD existiert. Antwort „ok“, „fehlt:css|js“ oder „egal“.
     */
    public static final String PRUEF_JS = "(function(){try{"
            + "var s=!!document.querySelector('link[href*=\"styles.css\"]'),c=!!document.querySelector('script[src*=\"core.js\"]');"
            + "if(!s&&!c)return 'egal';"
            + "var p=getComputedStyle(document.documentElement).getPropertyValue('--primary');"
            + "var okS=!s||(!!p&&p.trim().length>0);var okC=!c||(typeof window.BYD==='object'&&!!window.BYD);"
            + "return (okS&&okC)?'ok':('fehlt:'+(okS?'':'css')+(okC?'':'js'));"
            + "}catch(e){return 'egal';}})()";

    /** Ergebnis von evaluateJavascript (JSON-Text, z. B. {@code "\"ok\""}) → OK, FEHLT oder EGAL. */
    public static int urteil(String roh) {
        if (roh == null) return EGAL;
        String t = roh.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length() - 1);
        if (t.equals("ok")) return OK;
        if (t.startsWith("fehlt")) return FEHLT;
        return EGAL;
    }

    /** Wartezeit vor Versuch {@code bisher + 1}, oder -1, wenn nicht mehr neu geladen werden soll. */
    public static long warteMs(int bisher) {
        if (bisher < 0 || bisher >= MAX_VERSUCHE) return -1L;
        return WARTE_MS[Math.min(bisher, WARTE_MS.length - 1)];
    }
}
