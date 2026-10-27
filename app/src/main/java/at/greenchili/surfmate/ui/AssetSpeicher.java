package at.greenchili.surfmate.ui;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 0.1.20 Tempo (Messung am Auto: jeder Seitenwechsel lädt 20+ JS/CSS/Fonts neu, 1–2 s App-CPU 140–240 %).
 *
 * <p>Die App-WebView fängt jede Anfrage an 127.0.0.1 in {@code WebViewFragment.shouldInterceptRequest} ab und holt sie
 * per HttpURLConnection. Abgefangene Antworten landen nie im HTTP-Cache der WebView – deshalb kam bisher jede
 * Datei bei jedem Seitenwechsel erneut über die Leitung. Dieser Speicher hält die statischen, öffentlichen Dateien
 * unter {@code /shared/} (stammen aus dem APK, ändern sich nur mit einem Update = neuer Prozess) im Arbeitsspeicher.
 *
 * <p>Nur: GET, Pfad {@code /shared/…}, kein Range, keine Modelle ({@code /shared/models/}), kein HTML, Antwort 200,
 * höchstens {@link #MAX_EINTRAG} je Datei und {@link #MAX_GESAMT} insgesamt (LRU). Rein, ohne Android.
 */
public final class AssetSpeicher {

    public static final int MAX_EINTRAG = 1_536 * 1024;
    public static final long MAX_GESAMT = 16L * 1024 * 1024;

    /** Gespeicherte Antwort. */
    public static final class Eintrag {
        public final String mime, encoding;
        public final Map<String, String> kopf;
        public final byte[] daten;

        Eintrag(String mime, String encoding, Map<String, String> kopf, byte[] daten) {
            this.mime = mime;
            this.encoding = encoding;
            this.kopf = Collections.unmodifiableMap(new LinkedHashMap<>(kopf));
            this.daten = daten;
        }
    }

    private static final LinkedHashMap<String, Eintrag> SPEICHER = new LinkedHashMap<>(64, 0.75f, true);
    private static long belegt;

    private AssetSpeicher() {}

    /**
     * @param pfad   URL-Pfad ohne Query (z. B. {@code /shared/app-shell.js})
     * @param range  Range-Kopf oder null
     */
    public static boolean geeignet(String methode, String pfad, String range) {
        if (methode != null && !"GET".equalsIgnoreCase(methode)) return false;
        if (pfad == null || !pfad.startsWith("/shared/") || pfad.contains("..")) return false;
        if (pfad.startsWith("/shared/models/")) return false;
        if (range != null && !range.isEmpty()) return false;
        String p = pfad.toLowerCase(java.util.Locale.ROOT);
        return !(p.endsWith(".html") || p.endsWith(".htm") || p.endsWith(".mp4"));
    }

    /**
     * 0.1.20 (b2): Darf die WebView diese Datei selbst laden (kein Abfangen)? Dann greifen ihr HTTP-Cache
     * (max-age + ETag/304) und der JS-Code-Cache. Nur öffentliche, statische /shared/-Dateien per GET ohne Range,
     * nur ab WebView M72 (übergeht einen System-Proxy für 127.0.0.1 selbst) und nur ohne Proxy für 127.0.0.1
     * (am Auto gelesen: kein Proxy, WebView 74). Sonst bleibt es beim Abfangen mit {@link #holen}.
     */
    public static boolean direkt(String methode, String pfad, String range, int webViewHaupt, boolean proxyFuerLoopback) {
        if (webViewHaupt < 72 || proxyFuerLoopback) return false;
        if (methode != null && !"GET".equalsIgnoreCase(methode)) return false;
        if (pfad == null || !pfad.startsWith("/shared/") || pfad.contains("..")) return false;
        return range == null || range.isEmpty();
    }

    /** Hauptversion aus WebView-versionName („74.0.3729.186“ → 74), 0 = unbekannt. */
    public static int hauptversion(String versionName) {
        if (versionName == null) return 0;
        int i = 0;
        while (i < versionName.length() && Character.isDigit(versionName.charAt(i))) i++;
        try { return i == 0 ? 0 : Integer.parseInt(versionName.substring(0, i)); } catch (NumberFormatException e) { return 0; }
    }

    public static synchronized Eintrag holen(String url) {
        return url == null ? null : SPEICHER.get(url);
    }

    /** Merkt eine 200-Antwort (zu große werden ignoriert). */
    public static synchronized void merken(String url, String mime, String encoding, Map<String, String> kopf, byte[] daten) {
        if (url == null || daten == null || daten.length == 0 || daten.length > MAX_EINTRAG) return;
        Eintrag alt = SPEICHER.remove(url);
        if (alt != null) belegt -= alt.daten.length;
        SPEICHER.put(url, new Eintrag(mime, encoding, kopf == null ? Collections.<String, String>emptyMap() : kopf, daten));
        belegt += daten.length;
        java.util.Iterator<Map.Entry<String, Eintrag>> it = SPEICHER.entrySet().iterator();
        while (belegt > MAX_GESAMT && it.hasNext()) {
            Map.Entry<String, Eintrag> e = it.next();
            if (e.getKey().equals(url)) continue;
            belegt -= e.getValue().daten.length;
            it.remove();
        }
    }

    public static synchronized long belegt() {
        return belegt;
    }

    public static synchronized int anzahl() {
        return SPEICHER.size();
    }

    public static synchronized void leeren() {
        SPEICHER.clear();
        belegt = 0;
    }
}
