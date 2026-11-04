package at.greenchili.surfmate.server;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 0.1.39 PC über die Brücke (PLAN_BRUECKE_PC_0139 P1.6): {@code GET /api/web/manifest} – sha256 jeder statischen
 * Web-Datei unter {@code /shared/} und {@code /i18n/} dieses Panoptes, gelesen aus der installierten APK.
 *
 * <p>Die PC-Hülle holt diese Dateien vom öffentlichen Spiegel ({@code /ui/<versionCode>/}) statt über das LTE des
 * Autos und übernimmt nur, was hier mit gleichem sha256 steht. So kann der Server der Auto-Oberfläche keinen Code
 * unterschieben. Seiten (HTML) und API gehen immer durch die Brücke. Öffentlich (gleich für alle Autos derselben
 * Version), deshalb für jede Kopplung lesbar ({@code ExternGuard.LESEN}), aber nur bei eingeschaltetem
 * {@code bruecke.pcAnmeldung} (sonst 403 {@code pc_aus}). Einmal berechnet, dann im Speicher.
 */
public final class WebManifest {

    private WebManifest() {}

    public static final String PFAD = "/api/web/manifest";
    /** Präfixe in der APK und die URL-Pfade dazu. */
    static final String[][] BEREICHE = {{"assets/web/shared/", "/shared/"}, {"assets/web/i18n/", "/i18n/"}};

    private static volatile JSONObject gemerkt;

    /** APK des laufenden Daemons (im Test ersetzbar). */
    static volatile java.util.function.Supplier<File> apk =
            () -> at.greenchili.surfmate.appupdate.ApkQuelle.installiertesApk(System.getenv("CLASSPATH"));

    /** true, wenn der Pfad hierher gehört (dann ist die Antwort geschrieben). */
    public static boolean handle(String method, String pathOnly, OutputStream out) throws Exception {
        if (!PFAD.equals(pathOnly)) return false;
        // 0.1.38.9 schlafend (Entscheid Projektinhaber): solange „Am PC anmelden“ aus ist, gibt es auch kein Manifest
        if (!at.greenchili.surfmate.auth.PcZugang.erlaubt()) {
            HttpResponse.sendJson(out, 403, new JSONObject().put("ok", false).put("grund", "pc_aus").toString());
            return true;
        }
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            HttpResponse.sendJson(out, 405, new JSONObject().put("ok", false).put("grund", "nur_get").toString());
            return true;
        }
        JSONObject m = manifest();
        if (m == null) {
            HttpResponse.sendJson(out, 503, new JSONObject().put("ok", false).put("grund", "kein_apk").toString());
            return true;
        }
        HttpResponse.sendJson(out, m.toString());
        return true;
    }

    static JSONObject manifest() {
        JSONObject m = gemerkt;
        if (m != null) return m;
        synchronized (WebManifest.class) {
            if (gemerkt != null) return gemerkt;
            File f = apk.get();
            if (f == null || !f.isFile()) return null;
            try {
                gemerkt = bauen(f, at.greenchili.surfmate.BuildConfig.VERSION_NAME, at.greenchili.surfmate.BuildConfig.VERSION_CODE);
            } catch (Exception e) {
                return null;
            }
            return gemerkt;
        }
    }

    /** Manifest aus einer APK: {@code {ok, version, versionCode, dateien:{"/shared/…": "<sha256 hex>"}}}. */
    static JSONObject bauen(File apk, String version, int versionCode) throws Exception {
        TreeMap<String, String> dateien = new TreeMap<>();
        try (ZipFile z = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> e = z.entries();
            byte[] buf = new byte[64 * 1024];
            while (e.hasMoreElements()) {
                ZipEntry x = e.nextElement();
                if (x.isDirectory()) continue;
                String url = urlFuer(x.getName());
                if (url == null) continue;
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                try (InputStream in = z.getInputStream(x)) {
                    for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
                }
                dateien.put(url, hex(md.digest()));
            }
        }
        JSONObject d = new JSONObject();
        for (java.util.Map.Entry<String, String> x : dateien.entrySet()) d.put(x.getKey(), x.getValue());
        return new JSONObject().put("ok", true).put("version", version).put("versionCode", versionCode)
                .put("anzahl", dateien.size()).put("dateien", d);
    }

    /** URL-Pfad für einen APK-Eintrag oder null (nur {@link #BEREICHE}, ohne „..“). */
    static String urlFuer(String eintrag) {
        if (eintrag == null || eintrag.contains("..") || eintrag.contains("\\")) return null;
        for (String[] b : BEREICHE) {
            if (eintrag.startsWith(b[0]) && eintrag.length() > b[0].length()) return b[1] + eintrag.substring(b[0].length());
        }
        return null;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    /** Nur Tests. */
    static void vergessen() {
        gemerkt = null;
    }
}
