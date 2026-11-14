package at.greenchili.surfmate.server;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.1.43.7 (Paket E, E2E_01436b H1): Welche `?v=`-Zähler der Kern gerade ausliefert.
 *
 * Nach `install -r` öffnet die App ihre Seite, während noch der alte Kern antwortet; die erste Seite bleibt dann bis zum
 * nächsten Seitenwechsel die alte (alte theme.js/app-shell.js). Die Seite vergleicht deshalb ihre eigenen `?v=`-Verweise
 * mit dieser Liste und lädt bei Abweichung einmal neu (app-shell.js, mit Schleifenschutz).
 *
 * Gelesen wird, was der Server wirklich ausliefert (WEB_ROOT: local/*.html und shared/**.js), einmal je Stand der
 * Startseite (Änderungszeit, Größe). Dateien mit mehreren Zählern bleiben draußen (WebAssetVersionTest verhindert das).
 * Die {@link #kennung()} ist kurz und steht in /api/einstellungen/anzeige (Auto-Display fragt ohnehin alle 8 s) und im
 * Profil; nur wenn sie sich ändert, holt die Seite die ganze Liste über /api/system/webstand.
 */
public final class WebStand {
    private static final Pattern VERWEIS = Pattern.compile("([\\w.-]+\\.(?:js|css))\\?v=([\\w.-]+)");

    private static String gemerktFuer = null;
    private static Map<String, String> gemerkt = new TreeMap<>();
    private static String gemerktKennung = "";

    private WebStand() { }

    /** Zähler je Dateiname aus einem Web-Verzeichnis (local/*.html, shared/**.js|html); mehrdeutige Namen entfallen. */
    public static Map<String, String> auslesen(File web) {
        Map<String, Set<String>> alle = new HashMap<>();
        sammeln(new File(web, "local"), alle);
        sammeln(new File(web, "shared"), alle);
        Map<String, String> aus = new TreeMap<>();
        for (Map.Entry<String, Set<String>> e : alle.entrySet()) {
            if (e.getValue().size() == 1) aus.put(e.getKey(), e.getValue().iterator().next());
        }
        return aus;
    }

    /** Kurze Kennung über die ganze Liste (12 Hex-Zeichen von SHA-256 über „name=v;“ in fester Reihenfolge). */
    public static String kennung(Map<String, String> z) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(z).entrySet()) b.append(e.getKey()).append('=').append(e.getValue()).append(';');
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder h = new StringBuilder();
            for (int i = 0; i < 6; i++) h.append(String.format("%02x", d[i]));
            return h.toString();
        } catch (Exception e) {
            return Integer.toHexString(b.toString().hashCode());
        }
    }

    /** Stand des ausgelieferten Web-Verzeichnisses; leer, wenn es (noch) fehlt. */
    public static synchronized Map<String, String> zaehler() {
        File web = new File(HttpServer.webRoot());
        File start = new File(web, "local/index.html");
        String fuer = start.lastModified() + "/" + start.length();
        if (!fuer.equals(gemerktFuer)) {
            Map<String, String> z = start.isFile() ? auslesen(web) : new TreeMap<String, String>();
            gemerkt = z;
            gemerktKennung = z.isEmpty() ? "" : kennung(z);
            gemerktFuer = fuer;
        }
        return gemerkt;
    }

    public static synchronized String kennung() {
        zaehler();
        return gemerktKennung;
    }

    /** Antwort für GET /api/system/webstand: {kennung, zaehler:{name: v}}. */
    public static JSONObject json() throws Exception {
        Map<String, String> z = zaehler();
        JSONObject zj = new JSONObject();
        for (Map.Entry<String, String> e : z.entrySet()) zj.put(e.getKey(), e.getValue());
        return new JSONObject().put("kennung", kennung()).put("zaehler", zj);
    }

    private static void sammeln(File d, Map<String, Set<String>> alle) {
        File[] k = d.listFiles();
        if (k == null) return;
        for (File f : k) {
            if (f.isDirectory()) { sammeln(f, alle); continue; }
            String n = f.getName();
            if (!n.endsWith(".html") && !n.endsWith(".js")) continue;
            String text = lies(f);
            if (text == null) continue;
            Matcher m = VERWEIS.matcher(text);
            while (m.find()) {
                Set<String> s = alle.get(m.group(1));
                if (s == null) { s = new HashSet<>(); alle.put(m.group(1), s); }
                s.add(m.group(2));
            }
        }
    }

    private static String lies(File f) {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) != -1) o.write(b, 0, n);
            return new String(o.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
