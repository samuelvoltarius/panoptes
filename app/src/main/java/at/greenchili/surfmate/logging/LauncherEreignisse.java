package at.greenchili.surfmate.logging;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 0.1.38.1 Ereignis-Ringdatei {@code daemon_launcher.log} (Koordination 04.11. nach dem Befund am Auto: die Ursache für
 * das fehlende {@code start_cam_daemon.sh} ließ sich nicht beweisen, weil DaemonLauncher nur in den Logcat schreibt).
 *
 * <p>Eine Zeile je Ereignis: Zeit, Prozess ({@code app}, {@code kamera}, {@code waechter}, {@code telegram}), Ereignis
 * in Großbuchstaben, kurze Werte. Nie Befehlsinhalte, Token, JWT oder Passwörter: {@link #zeile} lässt nur einen kleinen
 * Zeichenvorrat durch und ersetzt alles, was wie ein Geheimnis aussieht, durch {@code [weg]}. Auch keine Daemon- oder
 * Skriptnamen: Die Zeilen der App laufen kurz als Shell-Text, und die Kill-Muster treffen per Teilstring
 * ({@code cam_daemon}, {@code start_…}); deshalb schreibt die Art ({@code kamera}) statt des Namens.
 *
 * <p>Ort und Rechte: Die Datei liegt bei den Daemon-Dateien ({@code /data/user_de/0/com.android.shell/surfmate/}, gehört
 * {@code shell}) und übersteht Updates (UpdateLifecycle löscht nur genannte Dateien). Die Shell-Daemons schreiben direkt
 * ({@link #direktAnhaengen}). Die App (eigene UID) darf dort nicht schreiben; sie sammelt ihre Zeilen in einem Puffer
 * ({@link #merken}) und hängt sie vorn an das nächste Skript, das sie ohnehin über adb ausführt
 * ({@code AdbShellExecutor.executeScript}). So kostet das keinen eigenen adb-Aufruf, und ein adb-Abbruch wird erst nach
 * dem nächsten Erfolg nachgetragen (die Zeilen gehen bei einem Fehler zurück in den Puffer). Weg (b) der Übergabe:
 * Weg (a) über eine Datei der App hätte eine zweite Datei und eine Lese-Brücke gebraucht, die der Kern nicht hat.
 *
 * <p>Größe: höchstens {@link #MAX_BYTES}, dann wird die Datei zu {@code .1} (eine Generation).
 */
public final class LauncherEreignisse {

    public static final String DATEI = at.greenchili.surfmate.util.DaemonStorage.rebase(
            "/data/user_de/0/com.android.shell/surfmate/daemon_launcher.log");
    public static final long MAX_BYTES = 256 * 1024;
    static final int MAX_PUFFER = 200;
    static final int MAX_ZEILE = 200;

    /** Sieht aus wie ein Geheimnis: JWT, lange Hex-/Base64-Folgen, „token=…“, „passw…=…“, „key=…“. */
    private static final Pattern GEHEIM = Pattern.compile(
            "eyJ[A-Za-z0-9_\\-]*(\\.[A-Za-z0-9_\\-]*)*"
                    + "|[A-Za-z0-9+/_\\-]{24,}"
                    + "|(?i)(token|passw\\w*|secret|key|jwt|bearer)[=: ]\\S*");
    private static final Pattern ERLAUBT_NICHT = Pattern.compile("[^A-Za-z0-9 _.:=#/+\\-]");
    private static final Pattern MUSTER = Pattern.compile("(?i)(daemon|start_|sentry|telegram_bot|zrok|cloudflared|sing-box|tailscaled|wgproxy)");

    private static final ArrayDeque<String> puffer = new ArrayDeque<>();
    private static int verworfen;

    private LauncherEreignisse() {}

    /** Eine fertige Zeile ohne Zeilenumbruch. */
    public static String zeile(long ms, String prozess, String ereignis, String werte) {
        String zeit = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date(ms));
        String z = zeit + " " + sauber(prozess) + " " + sauber(ereignis).toUpperCase(Locale.ROOT)
                + (werte == null || werte.isEmpty() ? "" : " " + sauber(werte));
        return z.length() > MAX_ZEILE ? z.substring(0, MAX_ZEILE) : z;
    }

    static String sauber(String s) {
        if (s == null) return "";
        String t = GEHEIM.matcher(s).replaceAll("[weg]");
        t = ERLAUBT_NICHT.matcher(t).replaceAll("_");
        // Kill-Muster nie im Text: die App-Zeilen stehen kurz in einer argv (siehe Klassenkommentar).
        return MUSTER.matcher(t).replaceAll("~");
    }

    // ---------------------------------------------------------------- Shell-Daemons (UID shell)

    /** Direkt anhängen (Daemon-Prozesse). Fehler werden verschluckt: das Log darf nie etwas blockieren. */
    public static void direktAnhaengen(String prozess, String ereignis, String werte) {
        direktAnhaengen(new File(DATEI), zeile(System.currentTimeMillis(), prozess, ereignis, werte));
    }

    static synchronized void direktAnhaengen(File datei, String zeile) {
        try {
            if (datei.length() > MAX_BYTES) {
                File alt = new File(datei.getPath() + ".1");
                if (!datei.renameTo(alt)) {
                    alt.delete();
                    datei.renameTo(alt);
                }
            }
            try (FileOutputStream out = new FileOutputStream(datei, true)) {
                out.write((zeile + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- App (eigene UID)

    /** Ereignis der App merken; geht mit dem nächsten adb-Skript in die Datei. */
    public static void merken(String ereignis, String werte) {
        String z = zeile(System.currentTimeMillis(), "app", ereignis, werte);
        synchronized (puffer) {
            if (puffer.size() >= MAX_PUFFER) {
                puffer.pollFirst();
                verworfen++;
            }
            puffer.addLast(z);
        }
    }

    /** Wie viele Zeilen der App noch auf den nächsten adb-Lauf warten. */
    public static int ausstehend() {
        synchronized (puffer) {
            return puffer.size() + (verworfen > 0 ? 1 : 0);
        }
    }

    /** Alle gemerkten Zeilen entnehmen (leer = nichts zu tun). Bei Misserfolg mit {@link #zuruecklegen} zurück. */
    public static List<String> entnehmen() {
        synchronized (puffer) {
            List<String> l = new ArrayList<>(puffer);
            if (verworfen > 0) {
                l.add(0, zeile(System.currentTimeMillis(), "app", "PUFFER_VOLL", "verworfen=" + verworfen));
                verworfen = 0;
            }
            puffer.clear();
            return l;
        }
    }

    /** Nach einem Fehlschlag die Zeilen wieder vorn einreihen (älteste zuerst, Grenze bleibt). */
    public static void zuruecklegen(List<String> zeilen) {
        if (zeilen == null || zeilen.isEmpty()) return;
        synchronized (puffer) {
            for (int i = zeilen.size() - 1; i >= 0; i--) {
                if (puffer.size() >= MAX_PUFFER) {
                    verworfen++;
                    continue;
                }
                puffer.addFirst(zeilen.get(i));
            }
        }
    }

    /**
     * Shell-Teil, der die Zeilen an die Datei hängt (vorher drehen, wenn sie über der Grenze liegt). Die Zeilen sind
     * schon {@link #sauber}: kein Hochkomma, also sicher in {@code '…'}. Leer, wenn es nichts zu schreiben gibt.
     */
    public static String shellAnhaengen(List<String> zeilen) {
        if (zeilen == null || zeilen.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("PN_EL=\"").append(DATEI).append("\"; ");
        sb.append("if [ \"$(stat -c %s \"$PN_EL\" 2>/dev/null || echo 0)\" -gt ").append(MAX_BYTES)
                .append(" ]; then mv -f \"$PN_EL\" \"$PN_EL.1\" 2>/dev/null; fi; ");
        sb.append("printf '%s\\n'");
        for (String z : zeilen) sb.append(" '").append(z.replace("'", "_")).append('\'');
        sb.append(" >> \"$PN_EL\" 2>/dev/null\n");
        return sb.toString();
    }
}
