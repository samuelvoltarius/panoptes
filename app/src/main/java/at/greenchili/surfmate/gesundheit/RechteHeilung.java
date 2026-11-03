package at.greenchili.surfmate.gesundheit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rechte-Selbstheilung (Konkurrenzvergleich Kinex, 03.11.2026): Manche Autos nehmen Apps nach etwa 30 Tagen ohne
 * Bedienung Rechte weg. Dann fehlen Kamera, Mikrofon, Standort oder das Recht, über anderen Apps zu zeigen, still.
 *
 * <p>Rein: Ausgabe von {@code dumpsys package <pkg>} und {@code appops get <pkg>} rein, Befund und Befehle raus.
 * Wiederhergestellt wird nur mit den Mitteln, die Panoptes schon nutzt ({@code pm grant}, {@code appops set … allow}
 * als {@code shell}, siehe {@code PermissionGranter}, {@code SentryDaemon}, {@code ServiceLauncher}). Kein neuer
 * Zugriffsweg, kein Root. Was danach noch fehlt, meldet {@link RechteHeilungsDienst} an Display und Telegram.
 *
 * <p>Vorsicht in beide Richtungen: Ein Recht gilt nur als fehlend, wenn {@code dumpsys} es ausdrücklich mit
 * {@code granted=false} nennt; eine App-Op nur, wenn sie ausdrücklich {@code ignore}, {@code deny} oder
 * {@code errored} ist. Nicht lesbar heißt „unbekannt“, nie „fehlt“ (kein Fehlalarm auf fremden Autos).
 */
public final class RechteHeilung {

    /** Laufzeit-Rechte, ohne die Panoptes-Funktionen still ausfallen. */
    static final List<String> RECHTE = Collections.unmodifiableList(Arrays.asList(
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE"));

    /** App-Ops, die Panoptes schon heute selbst auf {@code allow} setzt. */
    static final List<String> APPOPS = Collections.unmodifiableList(Arrays.asList(
            "CAMERA", "RECORD_AUDIO", "COARSE_LOCATION", "FINE_LOCATION", "SYSTEM_ALERT_WINDOW",
            "RUN_IN_BACKGROUND", "RUN_ANY_IN_BACKGROUND", "WRITE_SETTINGS"));

    /** Zwischen zwei Meldungen mit demselben Befund mindestens so lange warten. */
    static final long MELDE_ABSTAND_MS = 24L * 3600 * 1000;

    private static final Pattern PAKET = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");

    private RechteHeilung() {}

    /** Paketname gültig (geht in einen Shell-Befehl)? */
    static boolean paketGueltig(String pkg) {
        return pkg != null && pkg.length() <= 120 && PAKET.matcher(pkg).matches();
    }

    /**
     * Beobachtete Rechte, die {@code dumpsys} ausdrücklich als {@code granted=false} meldet. Steht ein Recht einmal
     * mit {@code false} da, zählt es als fehlend, auch wenn ein anderer Benutzer-Block {@code true} sagt
     * ({@code pm grant} ohne {@code --user} repariert Benutzer 0).
     */
    public static List<String> fehlendeRechte(String dumpsys) {
        List<String> out = new ArrayList<>();
        if (dumpsys == null || dumpsys.isEmpty()) return out;
        Set<String> verweigert = new HashSet<>();
        for (String roh : dumpsys.split("\n")) {
            String z = roh.trim();
            int m = z.indexOf(": granted=");
            if (m <= 0) continue;
            String name = z.substring(0, m).trim();
            String wert = z.substring(m + ": granted=".length());
            int komma = wert.indexOf(',');
            if (komma >= 0) wert = wert.substring(0, komma);
            if (!"true".equals(wert.trim())) verweigert.add(name);
        }
        for (String r : RECHTE) if (verweigert.contains(r)) out.add(r);
        return out;
    }

    /**
     * Beobachtete App-Ops, die {@code appops get} ausdrücklich sperrt. Zeilenform (Android 10):
     * {@code CAMERA: ignore; time=…} oder {@code Uid mode: CAMERA: ignore}.
     */
    public static List<String> gesperrteAppops(String appops) {
        Set<String> out = new LinkedHashSet<>();
        if (appops == null || appops.isEmpty()) return new ArrayList<>(out);
        for (String roh : appops.split("\n")) {
            String z = roh.trim();
            if (z.startsWith("Uid mode:")) z = z.substring("Uid mode:".length()).trim();
            int d = z.indexOf(':');
            if (d <= 0) continue;
            String op = z.substring(0, d).trim();
            if (!APPOPS.contains(op)) continue;
            String rest = z.substring(d + 1).trim();
            int ende = 0;
            while (ende < rest.length() && Character.isLetter(rest.charAt(ende))) ende++;
            String modus = rest.substring(0, ende).toLowerCase(Locale.ROOT);
            if ("ignore".equals(modus) || "deny".equals(modus) || "errored".equals(modus)) out.add(op);
        }
        List<String> sortiert = new ArrayList<>();
        for (String op : APPOPS) if (out.contains(op)) sortiert.add(op);
        return sortiert;
    }

    /** Shell-Befehle zum Wiederherstellen, nur bekannte Namen, nur gültiges Paket. */
    public static List<String> befehle(String pkg, List<String> rechte, List<String> appops) {
        List<String> out = new ArrayList<>();
        if (!paketGueltig(pkg)) return out;
        for (String r : rechte) if (RECHTE.contains(r)) out.add("pm grant " + pkg + " " + r);
        for (String op : appops) if (APPOPS.contains(op)) out.add("appops set " + pkg + " " + op + " allow");
        return out;
    }

    /**
     * Wörter für die Meldung (Kennwörter, Text kommt aus den Katalogen): kamera, mikrofon, standort, speicher,
     * ueberlagern, hintergrund, einstellungen. Doppelte fallen weg, Reihenfolge fest.
     */
    public static List<String> woerter(List<String> rechte, List<String> appops) {
        Set<String> w = new LinkedHashSet<>();
        List<String> alle = new ArrayList<>();
        if (rechte != null) alle.addAll(rechte);
        if (appops != null) alle.addAll(appops);
        for (String x : alle) {
            String k = wort(x);
            if (k != null) w.add(k);
        }
        List<String> fest = Arrays.asList("kamera", "mikrofon", "standort", "speicher", "ueberlagern", "hintergrund",
                "einstellungen");
        List<String> out = new ArrayList<>();
        for (String f : fest) if (w.contains(f)) out.add(f);
        return out;
    }

    static String wort(String x) {
        if (x == null) return null;
        if (x.endsWith("CAMERA")) return "kamera";
        if (x.endsWith("RECORD_AUDIO")) return "mikrofon";
        if (x.contains("LOCATION")) return "standort";
        if (x.contains("EXTERNAL_STORAGE")) return "speicher";
        if (x.equals("SYSTEM_ALERT_WINDOW")) return "ueberlagern";
        if (x.contains("BACKGROUND")) return "hintergrund";
        if (x.equals("WRITE_SETTINGS")) return "einstellungen";
        return null;
    }

    /**
     * Melden? Nur wenn nach der Reparatur noch etwas fehlt; derselbe Befund höchstens alle {@link #MELDE_ABSTAND_MS},
     * ein neuer Befund sofort.
     */
    public static boolean meldenNoetig(List<String> offen, List<String> zuletztGemeldet, long zuletztMs, long now) {
        if (offen == null || offen.isEmpty()) return false;
        if (zuletztGemeldet == null || !new HashSet<>(offen).equals(new HashSet<>(zuletztGemeldet))) return true;
        return zuletztMs <= 0 || now - zuletztMs >= MELDE_ABSTAND_MS || now < zuletztMs;
    }

    // ------------------------------------------------------------------ ein Durchgang

    /** Shell als {@code shell}; liefert die Ausgabe oder null, wenn der Befehl nicht lief. */
    public interface Shell {
        String lauf(String befehl);
    }

    /** Ergebnis eines Durchgangs. */
    public static final class Ergebnis {
        /** ok | geheilt | offen | unbekannt */
        public final String zustand;
        public final List<String> repariert;
        public final List<String> offen;

        Ergebnis(String zustand, List<String> repariert, List<String> offen) {
            this.zustand = zustand;
            this.repariert = Collections.unmodifiableList(repariert);
            this.offen = Collections.unmodifiableList(offen);
        }
    }

    /**
     * Prüfen, mit vorhandenen Mitteln wiederherstellen, nachprüfen. {@code offen} und {@code repariert} sind
     * Kennwörter aus {@link #woerter}.
     */
    public static Ergebnis durchgang(String pkg, Shell shell) {
        if (!paketGueltig(pkg) || shell == null) {
            return new Ergebnis("unbekannt", new ArrayList<>(), new ArrayList<>());
        }
        String dump = shell.lauf("dumpsys -t 5 package " + pkg + " 2>/dev/null");
        String ops = shell.lauf("appops get " + pkg + " 2>/dev/null");
        if (istLeer(dump) && istLeer(ops)) return new Ergebnis("unbekannt", new ArrayList<>(), new ArrayList<>());
        List<String> rechte = fehlendeRechte(dump);
        List<String> appops = gesperrteAppops(ops);
        if (rechte.isEmpty() && appops.isEmpty()) return new Ergebnis("ok", new ArrayList<>(), new ArrayList<>());
        for (String b : befehle(pkg, rechte, appops)) shell.lauf(b + " 2>&1");
        List<String> rechteDanach = istLeer(dump) ? new ArrayList<>()
                : fehlendeRechte(shell.lauf("dumpsys -t 5 package " + pkg + " 2>/dev/null"));
        List<String> appopsDanach = istLeer(ops) ? new ArrayList<>()
                : gesperrteAppops(shell.lauf("appops get " + pkg + " 2>/dev/null"));
        List<String> vorher = woerter(rechte, appops);
        List<String> offen = woerter(rechteDanach, appopsDanach);
        List<String> repariert = new ArrayList<>();
        for (String w : vorher) if (!offen.contains(w)) repariert.add(w);
        return new Ergebnis(offen.isEmpty() ? "geheilt" : "offen", repariert, offen);
    }

    private static boolean istLeer(String s) {
        return s == null || s.trim().isEmpty();
    }
}
