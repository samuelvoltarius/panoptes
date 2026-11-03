package at.greenchili.surfmate.appupdate;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.1.37 Stufe 4: die Launcher-APK {@code at.greenchili.panoptes.launcher} am Gerät – lesen, installieren, entfernen,
 * öffnen. Läuft im Daemon als {@code shell} (kein Context), darum über {@code cmd package}/{@code pm}/{@code am}.
 *
 * <p>Nie: Standard-Startbildschirm setzen ({@code set-home-activity}), BYD-Launcher abschalten, „Immer“ vorwählen.
 * „Öffnen“ startet die Launcher-App wie jede andere App (LAUNCHER_SPEC §1).
 */
public final class LauncherPaket {

    public static final String PAKET = "at.greenchili.panoptes.launcher";
    static final String AKTIVITAET = PAKET + "/.MainActivity";

    private static final Pattern ZEILE = Pattern.compile(
            "package:" + Pattern.quote(PAKET) + "(?=\\s|$)(.*)");
    private static final Pattern VC = Pattern.compile("versionCode:(\\d{1,9})");
    private static final Pattern UID = Pattern.compile("uid:(\\d{1,9})");

    /** Stand am Gerät. {@code versionCode} −1 = nicht installiert. */
    public static final class Stand {
        public final int versionCode;
        public final int uid;
        public final String apkPfad;

        Stand(int versionCode, int uid, String apkPfad) {
            this.versionCode = versionCode;
            this.uid = uid;
            this.apkPfad = apkPfad;
        }

        public boolean installiert() {
            return versionCode > 0;
        }
    }

    /** Gerät (im Test nachgebaut). */
    public interface Geraet {
        String shell(String befehl, int sekunden);
    }

    public static final Geraet SHELL = LauncherPaket::shellAusfuehren;

    private LauncherPaket() {}

    /** Aus {@code cmd package list packages -U --show-versioncode} und {@code pm path}; nie null. */
    public static Stand stand(Geraet g) {
        Stand s = ausListe(g.shell("cmd package list packages -U --show-versioncode " + PAKET, 10));
        if (!s.installiert()) return s;
        return new Stand(s.versionCode, s.uid, ausPfad(g.shell("pm path " + PAKET, 10)));
    }

    /** Zeile genau dieses Pakets (der Filter von {@code list packages} trifft auch Teilnamen). */
    static Stand ausListe(String ausgabe) {
        if (ausgabe != null) {
            for (String zeile : ausgabe.split("\n")) {
                Matcher m = ZEILE.matcher(zeile.trim());
                if (!m.matches()) continue;
                Matcher vc = VC.matcher(m.group(1));
                Matcher uid = UID.matcher(m.group(1));
                int v = vc.find() ? Integer.parseInt(vc.group(1)) : 0;
                int u = uid.find() ? Integer.parseInt(uid.group(1)) : -1;
                return new Stand(v > 0 ? v : 1, u, null);   // installiert, Version unlesbar = 1
            }
        }
        return new Stand(-1, -1, null);
    }

    /** Erste {@code package:/…/base.apk}-Zeile; null, wenn keine passt. */
    static String ausPfad(String ausgabe) {
        if (ausgabe == null) return null;
        for (String zeile : ausgabe.split("\n")) {
            String t = zeile.trim();
            if (t.startsWith("package:/") && t.endsWith(".apk") && !t.contains("..") && !t.contains("'")) {
                return t.substring("package:".length());
            }
        }
        return null;
    }

    /** SHA-256 des Signaturzertifikats der installierten Launcher-APK oder null. */
    public static String zertifikat(Stand s) {
        if (s == null || s.apkPfad == null) return null;
        try {
            return ApkZertifikat.sha256(new File(s.apkPfad));
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code pm install -r}; null = ok, sonst kurzer Grund. Die APK ist vorher geprüft (sha256, Zertifikat). */
    public static String installieren(Geraet g, File apk) {
        String pfad = apk.getAbsolutePath();
        if (pfad.contains("'")) return "pfad";
        String aus = g.shell("pm install -r '" + pfad + "'", 120);
        return aus != null && aus.contains("Success") ? null : "install:" + kurz(aus);
    }

    /** {@code pm uninstall} nur dieses Pakets; null = ok. */
    public static String entfernen(Geraet g) {
        String aus = g.shell("pm uninstall " + PAKET, 60);
        return aus != null && aus.contains("Success") ? null : "uninstall:" + kurz(aus);
    }

    /** Startet die Launcher-App als normale App (nie als Standard-Startbildschirm); null = ok. */
    public static String oeffnen(Geraet g) {
        String aus = g.shell("am start -n " + AKTIVITAET, 15);
        return aus != null && !aus.contains("Error") && !aus.contains("Exception") ? null : "start:" + kurz(aus);
    }

    static String kurz(String s) {
        if (s == null) return "";
        String t = s.trim().replaceAll("[^A-Za-z0-9_ .:\\-\\[\\]]", "");
        return t.length() > 80 ? t.substring(0, 80) : t;
    }

    private static String shellAusfuehren(String befehl, int sekunden) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", befehl).redirectErrorStream(true);
            pb.environment().remove("CLASSPATH");
            Process p = pb.start();
            p.getOutputStream().close();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1 && out.size() < 64 * 1024) out.write(buf, 0, n);
            }
            if (!p.waitFor(sekunden, TimeUnit.SECONDS)) p.destroyForcibly();
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
