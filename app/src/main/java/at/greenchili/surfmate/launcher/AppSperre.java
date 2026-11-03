package at.greenchili.surfmate.launcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Apps am Display deaktivieren und wieder aktivieren (0.1.37, DashArc-Abgleich Punkt 24).
 *
 * <p>Nur vom Nutzer installierte Apps ({@code pm list packages -3}). Nie System- oder BYD-Apps, nie Panoptes selbst,
 * nie der Fernzugang, nie die gerade aktive Startseite oder Tastatur – sonst kann das Display unbedienbar werden.
 * Die harte Sperrliste gilt zusätzlich zur System-Prüfung, auch wenn ein Gerät eine BYD-App als Nutzer-App meldet.
 * Ausgeführt wird nur {@code pm disable-user --user 0} bzw. {@code pm enable --user 0}; danach wird zurückgelesen.
 * Deinstallieren und Installieren gibt es hier bewusst nicht.
 */
public final class AppSperre {

    /** Panoptes selbst (Paketname bleibt intern {@code at.greenchili.surfmate}). */
    public static final String EIGENES_PAKET = "at.greenchili.surfmate";

    /** Harte Sperrliste: Präfixe, die nie deaktiviert werden. */
    static final List<String> GESPERRT_PRAEFIX = Collections.unmodifiableList(Arrays.asList(
            "com.byd.", "com.bydauto.", "android.", "com.android.", "com.google.android.",
            "at.greenchili.",      // Panoptes und seine Begleit-Apps
            "com.tailscale."));    // Fernzugang zu Panoptes

    /** Harte Sperrliste: genaue Paketnamen. */
    static final Set<String> GESPERRT_GENAU = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "android", EIGENES_PAKET, "com.byd", "com.google.android", "moe.shizuku.privileged.api")));

    private static final Pattern PAKET = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");

    private AppSperre() {}

    /** Gültiger Paketname (nur Buchstaben, Ziffern, Unterstrich, Punkte; mindestens zwei Teile). */
    public static boolean gueltigerName(String pkg) {
        return pkg != null && pkg.length() <= 200 && PAKET.matcher(pkg).matches();
    }

    /** Steht auf der harten Sperrliste (unabhängig davon, was das Gerät meldet). */
    public static boolean hartGesperrt(String pkg) {
        if (pkg == null) return true;
        String p = pkg.trim().toLowerCase(Locale.ROOT);
        if (GESPERRT_GENAU.contains(p)) return true;
        for (String g : GESPERRT_PRAEFIX) if (p.startsWith(g)) return true;
        return false;
    }

    /**
     * Darf diese App deaktiviert werden? {@code null} = ja, sonst ein Kennwort als Grund.
     *
     * @param nutzerApps     Ausgabe von {@code pm list packages -3} (nur vom Nutzer installiert)
     * @param startseite     Paket der aktiven Startseite (HOME) oder null
     * @param tastatur       Paket der aktiven Tastatur oder null
     */
    public static String grundGegenDeaktivieren(String pkg, Set<String> nutzerApps, String startseite, String tastatur) {
        if (!gueltigerName(pkg)) return "name_ungueltig";
        if (hartGesperrt(pkg)) return "gesperrt";
        if (nutzerApps == null || !nutzerApps.contains(pkg)) return "keine_nutzer_app";
        if (pkg.equals(startseite)) return "startseite";
        if (pkg.equals(tastatur)) return "tastatur";
        return null;
    }

    /** Wieder aktivieren: nur gültige Nutzer-Apps, die gerade deaktiviert sind. */
    public static String grundGegenAktivieren(String pkg, Set<String> nutzerApps, Set<String> deaktiviert) {
        if (!gueltigerName(pkg)) return "name_ungueltig";
        if (nutzerApps == null || !nutzerApps.contains(pkg)) return "keine_nutzer_app";
        if (deaktiviert == null || !deaktiviert.contains(pkg)) return "nicht_deaktiviert";
        return null;
    }

    /** {@code package:a.b} Zeilen aus {@code pm list packages}. */
    public static Set<String> pakete(String ausgabe) {
        Set<String> r = new LinkedHashSet<>();
        if (ausgabe == null) return r;
        for (String z : ausgabe.split("\\r?\\n")) {
            String t = z.trim();
            if (!t.startsWith("package:")) continue;
            String p = t.substring("package:".length()).trim();
            int gleich = p.lastIndexOf('=');   // „-f“ Form: pfad=paket
            if (gleich >= 0) p = p.substring(gleich + 1);
            if (gueltigerName(p)) r.add(p);
        }
        return r;
    }

    /** Paket aus {@code pkg/klasse} (Ausgabe von resolve-activity bzw. default_input_method), sonst null. */
    public static String paketAusKomponente(String ausgabe) {
        if (ausgabe == null) return null;
        String[] zeilen = ausgabe.trim().split("\\r?\\n");
        for (int i = zeilen.length - 1; i >= 0; i--) {
            String z = zeilen[i].trim();
            int s = z.indexOf('/');
            if (s > 0) {
                String p = z.substring(0, s);
                if (gueltigerName(p)) return p;
            }
        }
        return null;
    }

    /** Befehle (Paketname ist geprüft, enthält also keine Shell-Zeichen). */
    public static List<String> befehlDeaktivieren(String pkg) {
        if (!gueltigerName(pkg) || hartGesperrt(pkg)) throw new IllegalArgumentException("gesperrt");
        return new ArrayList<>(Arrays.asList("pm", "disable-user", "--user", "0", pkg));
    }

    public static List<String> befehlAktivieren(String pkg) {
        if (!gueltigerName(pkg)) throw new IllegalArgumentException("name");
        return new ArrayList<>(Arrays.asList("pm", "enable", "--user", "0", pkg));
    }

    // ------------------------------------------------------------------ Laufzeit

    /** Shell-Zugang (Tests ersetzen ihn). Liefert die Ausgabe oder null bei Fehler/Zeitüberschreitung. */
    public interface Shell {
        String run(List<String> argv);
    }

    public static final Shell ECHT = argv -> {
        try {
            Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            java.io.InputStream in = p.getInputStream();
            byte[] b = new byte[4096];
            int n;
            long ende = System.currentTimeMillis() + 8000;
            while ((n = in.read(b)) != -1) {
                sb.append(new String(b, 0, n, java.nio.charset.StandardCharsets.UTF_8));
                if (sb.length() > 512 * 1024 || System.currentTimeMillis() > ende) break;
            }
            boolean fertig = p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS);
            if (!fertig) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? sb.toString() : null;
        } catch (Throwable t) {
            return null;
        }
    };

    /** Lage am Gerät, einmal gelesen. */
    public static final class Lage {
        public final Set<String> nutzerApps, deaktiviert;
        public final String startseite, tastatur;

        Lage(Set<String> nutzerApps, Set<String> deaktiviert, String startseite, String tastatur) {
            this.nutzerApps = nutzerApps;
            this.deaktiviert = deaktiviert;
            this.startseite = startseite;
            this.tastatur = tastatur;
        }
    }

    public static Lage lese(Shell sh) {
        Set<String> nutzer = pakete(sh.run(Arrays.asList("pm", "list", "packages", "-3")));
        Set<String> aus = pakete(sh.run(Arrays.asList("pm", "list", "packages", "-d", "-3")));
        String home = paketAusKomponente(sh.run(Arrays.asList("cmd", "package", "resolve-activity", "--brief",
                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")));
        String ime = paketAusKomponente(sh.run(Arrays.asList("settings", "get", "secure", "default_input_method")));
        return new Lage(nutzer, aus, home, ime);
    }

    /** Ergebnis: {@code null} = ok (zurückgelesen), sonst Grund. */
    public static String deaktivieren(Shell sh, String pkg) {
        Lage l = lese(sh);
        String g = grundGegenDeaktivieren(pkg, l.nutzerApps, l.startseite, l.tastatur);
        if (g != null) return g;
        if (l.deaktiviert.contains(pkg)) return null;
        if (sh.run(befehlDeaktivieren(pkg)) == null) return "fehlgeschlagen";
        return pakete(sh.run(Arrays.asList("pm", "list", "packages", "-d", "-3"))).contains(pkg)
                ? null : "nicht_bestaetigt";
    }

    public static String aktivieren(Shell sh, String pkg) {
        Lage l = lese(sh);
        String g = grundGegenAktivieren(pkg, l.nutzerApps, l.deaktiviert);
        if (g != null) return g;
        if (sh.run(befehlAktivieren(pkg)) == null) return "fehlgeschlagen";
        return pakete(sh.run(Arrays.asList("pm", "list", "packages", "-d", "-3"))).contains(pkg)
                ? "nicht_bestaetigt" : null;
    }
}
