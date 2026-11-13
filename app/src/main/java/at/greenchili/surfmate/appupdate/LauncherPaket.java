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

    /**
     * Stand am Gerät. {@code versionCode} −1 = nicht installiert (oder unbekannt, siehe {@link #bekannt}).
     * 0.1.43.3 (U1): {@code bekannt} = false, wenn das Lesen leer blieb, abbrach oder in die Zeit lief. Ein unbekannter
     * Stand ist NIE „nicht installiert“ – wer entscheidet, muss {@link #bekannt} prüfen.
     */
    public static final class Stand {
        public final int versionCode;
        public final int uid;
        public final String apkPfad;
        public final boolean bekannt;

        Stand(int versionCode, int uid, String apkPfad) {
            this(versionCode, uid, apkPfad, true);
        }

        Stand(int versionCode, int uid, String apkPfad, boolean bekannt) {
            this.versionCode = versionCode;
            this.uid = uid;
            this.apkPfad = apkPfad;
            this.bekannt = bekannt;
        }

        public boolean installiert() {
            return versionCode > 0;
        }

        /** Sicher gelesen und nicht installiert. */
        public boolean sicherNichtInstalliert() {
            return bekannt && !installiert();
        }
    }

    /** Stand, der sich nicht lesen ließ (leer, Zeit abgelaufen, Fehler). */
    public static Stand unbekannt() {
        return new Stand(-1, -1, null, false);
    }

    /** 0.1.43.3 (U1): Ende-Zeichen hinter dem Lesebefehl; fehlt es, ist die Ausgabe abgebrochen oder leer. */
    static final String ENDE = "__PANOPTES_ENDE__";
    static final String LISTE_BEFEHL = "cmd package list packages -U --show-versioncode " + PAKET + "; echo " + ENDE;

    /** Gerät (im Test nachgebaut). */
    public interface Geraet {
        String shell(String befehl, int sekunden);
    }

    public static final Geraet SHELL = LauncherPaket::shellAusfuehren;

    private LauncherPaket() {}

    /**
     * Aus {@code cmd package list packages -U --show-versioncode} und {@code pm path}; nie null. Ohne Ende-Zeichen
     * (leer, Zeit abgelaufen, Fehler) {@link #unbekannt()} – nie „nicht installiert“.
     */
    public static Stand stand(Geraet g) {
        String aus;
        try {
            aus = g.shell(LISTE_BEFEHL, 10);
        } catch (Throwable t) {
            aus = null;
        }
        Stand s = ausAusgabe(aus);
        if (!s.installiert()) return s;
        String pfad;
        try {
            pfad = ausPfad(g.shell("pm path " + PAKET, 10));
        } catch (Throwable t) {
            pfad = null;
        }
        return new Stand(s.versionCode, s.uid, pfad);
    }

    /**
     * 0.1.43.3 (U1): Stand frisch, ohne Zwischenspeicher, für Entscheidungen direkt vor einer Installation. Ist das
     * erste Lesen unbekannt oder ohne Paketzeile (leer), wird genau einmal nachgelesen; bleibt es unbekannt, ist der
     * Stand unbekannt.
     */
    public static Stand standFrisch(Geraet g) {
        Stand s = stand(g);
        if (s.installiert()) return s;
        Stand zwei = stand(g);
        if (zwei.installiert() || zwei.bekannt) return zwei;
        return s.bekannt ? s : zwei;
    }

    /** Ausgabe von {@link #LISTE_BEFEHL}: ohne Ende-Zeichen unbekannt, sonst wie {@link #ausListe}. */
    static Stand ausAusgabe(String ausgabe) {
        if (ausgabe == null || !ausgabe.contains(ENDE)) return unbekannt();
        return ausListe(ausgabe);
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
            final Process p = pb.start();
            p.getOutputStream().close();
            // 0.1.43.3 (U1): die Frist gilt auch beim Lesen – hängt der Befehl, beendet der Wächter ihn; die Ausgabe
            // endet dann ohne Ende-Zeichen und der Stand gilt als unbekannt (nie „nicht installiert“)
            Thread waechter = new Thread(() -> {
                try {
                    if (!p.waitFor(sekunden, TimeUnit.SECONDS)) p.destroyForcibly();
                } catch (InterruptedException ignored) {
                }
            }, "LauncherPaket-Frist");
            waechter.setDaemon(true);
            waechter.start();
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
