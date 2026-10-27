package at.greenchili.surfmate.appupdate;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 0.1.19 (Befund Deploy 27.10., UPDATER_TEST_0118.md): Der In-App-Updater läuft im app_process-Daemon – dort gibt es
 * KEINEN Android-Context ({@code DaemonBootstrap.getContext()} ist null). Assets und das installierte APK werden
 * deshalb direkt aus der APK-Datei gelesen (ZipFile), nicht über {@code Context.getAssets()} bzw. ApplicationInfo.
 * Rein, ohne Android – JVM-testbar.
 */
public final class ApkQuelle {

    /** Pfad des Update-Skripts im APK. */
    public static final String SKRIPT_EINTRAG = "assets/deploy/update-on-car.sh";
    static final long MAX_SKRIPT_BYTES = 256 * 1024;

    private ApkQuelle() {}

    /** Text eines Eintrags (z. B. {@link #SKRIPT_EINTRAG}) aus einem APK; IOException, wenn er fehlt oder zu groß ist. */
    public static String text(File apk, String eintrag) throws IOException {
        if (apk == null || !apk.isFile()) throw new IOException("APK fehlt: " + apk);
        try (ZipFile z = new ZipFile(apk)) {
            ZipEntry e = z.getEntry(eintrag);
            if (e == null) throw new IOException(eintrag + " fehlt in " + apk.getName());
            if (e.getSize() > MAX_SKRIPT_BYTES) throw new IOException(eintrag + " zu groß");
            try (InputStream in = z.getInputStream(e)) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                long gesamt = 0;
                while ((n = in.read(buf)) > 0) {
                    gesamt += n;
                    if (gesamt > MAX_SKRIPT_BYTES) throw new IOException(eintrag + " zu groß");
                    b.write(buf, 0, n);
                }
                return new String(b.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }

    /**
     * Installiertes Panoptes-APK aus dem CLASSPATH des Daemons (app_process wird mit
     * {@code CLASSPATH=[/system/framework/bmmcamera.jar:]…/base.apk} gestartet); null, wenn keins da ist.
     */
    public static File installiertesApk(String classpath) {
        if (classpath == null || classpath.isEmpty()) return null;
        for (String teil : classpath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            String t = teil.trim();
            if (t.endsWith(".apk") && t.contains("at.greenchili.surfmate")) {
                File f = new File(t);
                if (f.isFile()) return f;
            }
        }
        for (String teil : classpath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            String t = teil.trim();
            if (t.endsWith(".apk")) {
                File f = new File(t);
                if (f.isFile()) return f;
            }
        }
        return null;
    }
}
