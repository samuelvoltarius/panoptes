package at.greenchili.surfmate.gesundheit;

import at.greenchili.surfmate.launcher.DaemonLauncher;
import at.greenchili.surfmate.logging.DaemonLogger;
import at.greenchili.surfmate.logging.LauncherEreignisse;
import at.greenchili.surfmate.server.Messages;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 0.1.38.1 Selbstprüfung des eigenen Watchdogs (PFLICHT Projektinhaber 04.11.: „so was muss bei jedem automatisch gehen,
 * sobald das Update durch ist“). Läuft in jedem Shell-Daemon mit Watchdog (Kamera, Wächter, Telegram) und prüft alle
 * {@link #TAKT_S} Sekunden, ob sein {@code start_*.sh} da ist (mit Anhänge-Modus, {@code sh -n} ok) und ein Watchdog
 * lebt. So greift die Prüfung nach jedem Update und dauerhaft, ohne Nutzer, ohne PC und auch ohne laufende App.
 *
 * <p>Fehlt etwas zwei Takte hintereinander (ein Neustart über die App dauert nur Sekunden und soll nicht gestört
 * werden), heilt der Daemon sich selbst mit {@link DaemonLauncher#buildSelbstheilSkript}: Skript neu schreiben und einen
 * Watchdog im Anhänge-Modus an den laufenden Daemon hängen. Nie ein zweiter Daemon: Das Skript hängt nur an genau
 * diesen Prozess an, und die Watchdog-Sperre lässt höchstens einen Watchdog zu. Klappt die Heilung
 * {@link #MELDEN_NACH} Mal nicht, gibt es eine ruhige Meldung in Klartext über Telegram, höchstens einmal in
 * {@link #MELDE_ABSTAND_MS}. Jeder Schritt steht in {@code daemon_launcher.log} ({@link LauncherEreignisse}).
 *
 * <p>Warum im Daemon und nicht im 30-s-Health-Check der App: Der Daemon läuft nach dem Update auch dann, wenn die App
 * im Hintergrund beendet wird, er darf als {@code shell} die Dateien direkt lesen und schreiben, und er braucht dafür
 * keine adb-Verbindung (am Auto 04.11. war gerade der adb-Weg das Problem). Die App heilt zusätzlich beim Öffnen
 * ({@code DaemonLauncher.launchCameraDaemon}), und {@code update-on-car.sh} bestätigt ein Update erst, wenn diese
 * Prüfung grün ist.
 */
public final class WatchdogSelbstpruefung {

    private static final DaemonLogger logger = DaemonLogger.getInstance("Selbstpruefung");

    static final long ERSTE_PRUEFUNG_S = 30;
    static final long TAKT_S = 30;
    /** So viele Takte hintereinander muss etwas fehlen, bevor geheilt wird. */
    static final int VERDACHT_BIS_HEILEN = 2;
    /** So viele erfolglose Heilversuche hintereinander, dann eine Meldung. */
    static final int MELDEN_NACH = 3;
    static final long MELDE_ABSTAND_MS = 24L * 3600 * 1000;
    private static final long HEILEN_MAX_MS = 60_000L;

    /** Was ein Takt tun soll. */
    enum Aktion { NICHTS, WARTEN, HEILEN }

    private static final List<WatchdogSelbstpruefung> laufend = new ArrayList<>();

    final String art;
    final File proc;
    private final int eigenePid;
    private final Supplier<List<String>> skriptZeilen;
    private final Consumer<String[]> melder;
    private int verdacht;
    private int fehlversuche;
    private long geprueftStempel = -1;
    private boolean syntaxOk = true;
    /** Letzter Takt war grün bzw. die Heilung ist {@link #MELDEN_NACH} Mal gescheitert (für {@link #befund}). */
    volatile boolean gruen;
    volatile boolean stoerung;

    WatchdogSelbstpruefung(String art, File proc, int eigenePid, Supplier<List<String>> skriptZeilen,
                           Consumer<String[]> melder) {
        this.art = art;
        this.proc = proc;
        this.eigenePid = eigenePid;
        this.skriptZeilen = skriptZeilen;
        this.melder = melder;
    }

    /**
     * Im Daemon starten (einmal je Art und Prozess). {@code skriptZeilen} liefert das Watchdog-Skript, wie es die App
     * schreiben würde; {@code melder} bekommt {titel, text}, wenn die Heilung nicht klappt.
     */
    public static synchronized void start(String art, Supplier<List<String>> skriptZeilen, Consumer<String[]> melder) {
        for (WatchdogSelbstpruefung w : laufend) if (w.art.equals(art)) return;
        WatchdogSelbstpruefung w = new WatchdogSelbstpruefung(art, new File("/proc"), android.os.Process.myPid(),
                skriptZeilen, melder);
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Selbstpruefung-" + art);
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(() -> {
            try {
                w.takt(System.currentTimeMillis());
            } catch (Throwable t) {
                logger.warn("takt " + art + ": " + t.getMessage());
            }
        }, ERSTE_PRUEFUNG_S, TAKT_S, TimeUnit.SECONDS);
        laufend.add(w);
        logger.info("Selbstpruefung " + art + " gestartet (alle " + TAKT_S + " s)");
    }

    /**
     * Befund dieser Art im eigenen Prozess: {@code gruen}, {@code stoerung} (Heilung gescheitert, Meldung ist raus)
     * oder {@code offen} (noch kein Takt, gesperrt oder gerade am Heilen). Für die Update-Meldung „installiert und läuft“.
     */
    public static synchronized String befund(String art) {
        for (WatchdogSelbstpruefung w : laufend) {
            if (!w.art.equals(art)) continue;
            if (w.stoerung) return "stoerung";
            return w.gruen ? "gruen" : "offen";
        }
        return "offen";
    }

    // ---------------------------------------------------------------- Entscheidung (rein)

    static Aktion entscheide(boolean gesperrt, boolean ok, int verdachtVorher) {
        if (gesperrt || ok) return Aktion.NICHTS;
        return verdachtVorher + 1 >= VERDACHT_BIS_HEILEN ? Aktion.HEILEN : Aktion.WARTEN;
    }

    static boolean meldenNoetig(int fehlversuche, long gemeldetMs, long jetzt) {
        return fehlversuche >= MELDEN_NACH && (gemeldetMs <= 0 || jetzt - gemeldetMs >= MELDE_ABSTAND_MS);
    }

    // ---------------------------------------------------------------- Takt

    void takt(long jetzt) {
        boolean gesperrt = gesperrt();
        boolean ok = !gesperrt && schnellOk();
        Aktion a = entscheide(gesperrt, ok, verdacht);
        if (a == Aktion.NICHTS) {
            if (ok) {
                gruen = true;
                stoerung = false;
            }
            if (ok && fehlversuche > 0) {
                logger.warn("Watchdog " + art + " wieder in Ordnung (nach " + fehlversuche + " Heilversuch(en))");
                LauncherEreignisse.direktAnhaengen(art, "SELBSTHEILUNG_GRUEN", "versuche=" + fehlversuche);
            }
            verdacht = 0;
            fehlversuche = 0;
            return;
        }
        verdacht++;
        gruen = false;
        if (a == Aktion.WARTEN) {
            logger.info("Watchdog " + art + ": Skript oder Watchdog fehlt, prüfe im nächsten Takt noch einmal");
            return;
        }
        String befund = heilen();
        logger.warn("Watchdog " + art + " fehlte, Selbstheilung: " + befund);
        LauncherEreignisse.direktAnhaengen(art, "SELBSTHEILUNG", "befund=" + befund);
        // Jeder Versuch zählt; erst ein grüner Takt danach setzt zurück („angehaengt“ allein ist noch kein Beweis).
        fehlversuche++;
        if (fehlversuche >= MELDEN_NACH) stoerung = true;
        long gemeldet = gemeldetMs();
        if (meldenNoetig(fehlversuche, gemeldet, jetzt)) {
            melden();
            gemeldetMerken(jetzt);
            LauncherEreignisse.direktAnhaengen(art, "SELBSTHEILUNG_GEMELDET", "versuche=" + fehlversuche);
        }
    }

    private boolean gesperrt() {
        if (new File(DaemonLauncher.watchdogSentinel(art)).exists()) return true;
        return DaemonLauncher.watchdogParkSperrt(art) && new File(at.greenchili.surfmate.util.DaemonStorage.rebase(
                "/data/user_de/0/com.android.shell/surfmate/surfmate_parked_shutdown")).exists();
    }

    /** Skript da, mit Anhänge-Modus, {@code sh -n} ok (nur nach einer Änderung neu geprüft) und ein Watchdog lebt. */
    boolean schnellOk() {
        String skript = DaemonLauncher.watchdogSkriptPfad(art);
        File f = new File(skript);
        if (!f.isFile()) return false;
        try {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            if (!text.contains(DaemonLauncher.ANHAENGEN_MARKE)) return false;
        } catch (Exception e) {
            return false;
        }
        long stempel = f.lastModified() * 31 + f.length();
        if (stempel != geprueftStempel) {
            syntaxOk = shN(skript);
            geprueftStempel = stempel;
        }
        if (!syntaxOk) return false;
        return watchdogLebt(skript);
    }

    boolean watchdogLebt(String skript) {
        for (String datei : new String[] {DaemonLauncher.watchdogPidDatei(art),
                DaemonLauncher.watchdogSperrPfad(art) + "/pid"}) {
            try {
                int pid = Integer.parseInt(new String(Files.readAllBytes(new File(datei).toPath()),
                        StandardCharsets.UTF_8).trim());
                if (istWatchdog(proc, pid, skript)) return true;
            } catch (Exception ignored) {
            }
        }
        return !watchdogPids(proc, skript).isEmpty();
    }

    private String heilen() {
        File tmp = new File(new File(DaemonLauncher.watchdogSkriptPfad(art)).getParentFile(),
                ".pn_heil_" + art + "_" + Long.toUnsignedString(System.nanoTime(), 36) + ".sh");
        Process p = null;
        try {
            String skript = DaemonLauncher.buildSelbstheilSkript(art, skriptZeilen.get(), eigenePid);
            Files.write(tmp.toPath(), skript.getBytes(StandardCharsets.UTF_8));
            // argv = `sh …/.pn_heil_<art>_<n>.sh`: kein Daemon-Muster, kein Kill-Muster trifft die Heilung.
            p = new ProcessBuilder("sh", tmp.getPath()).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String z;
                while ((z = r.readLine()) != null && sb.length() < 64 * 1024) sb.append(z).append('\n');
            }
            if (!p.waitFor(HEILEN_MAX_MS, TimeUnit.MILLISECONDS)) {
                p.destroy();
                return "zeitablauf";
            }
            String b = DaemonLauncher.selbstheilBefund(sb.toString());
            return b == null ? "unklar" : b;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return "unterbrochen";
        } catch (Exception e) {
            return "fehler";
        } finally {
            tmp.delete();
        }
    }

    private static boolean shN(String skript) {
        try {
            Process p = new ProcessBuilder("sh", "-n", skript).redirectErrorStream(true).start();
            byte[] puffer = new byte[4096];
            try (java.io.InputStream in = p.getInputStream()) {
                while (in.read(puffer) >= 0) { /* Ausgabe verwerfen (readAllBytes fehlt auf Android 10) */ }
            }
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return true;   // nicht prüfbar heißt „unbekannt“, nie „kaputt“ (kein Heilen auf Verdacht)
        }
    }

    // ---------------------------------------------------------------- /proc (exakt, nie Teilstring)

    /** argv eines Prozesses, leer wenn nicht lesbar. */
    static List<String> argv(File proc, int pid) {
        List<String> out = new ArrayList<>();
        try {
            byte[] b = Files.readAllBytes(new File(new File(proc, Integer.toString(pid)), "cmdline").toPath());
            int start = 0;
            for (int i = 0; i <= b.length; i++) {
                if (i == b.length || b[i] == 0) {
                    if (i > start) out.add(new String(b, start, i - start, StandardCharsets.UTF_8));
                    start = i + 1;
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Ist {@code pid} ein Watchdog dieses Skripts: argv genau {@code [sh|/system/bin/sh, <skript>, …]}? */
    public static boolean istWatchdog(int pid, String skript) {
        return istWatchdog(new File("/proc"), pid, skript);
    }

    static boolean istWatchdog(File proc, int pid, String skript) {
        if (pid <= 0) return false;
        List<String> a = argv(proc, pid);
        return a.size() >= 2 && ("sh".equals(a.get(0)) || "/system/bin/sh".equals(a.get(0))) && skript.equals(a.get(1));
    }

    /** Alle lebenden Watchdogs dieses Skripts. */
    public static List<Integer> watchdogPids(String skript) {
        return watchdogPids(new File("/proc"), skript);
    }

    static List<Integer> watchdogPids(File proc, String skript) {
        List<Integer> out = new ArrayList<>();
        String[] namen = proc.list();
        if (namen == null) return out;
        for (String n : namen) {
            if (n.isEmpty() || !n.chars().allMatch(Character::isDigit)) continue;
            try {
                int pid = Integer.parseInt(n);
                if (istWatchdog(proc, pid, skript)) out.add(pid);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- Meldung

    private File gemeldetDatei() {
        return new File(new File(DaemonLauncher.watchdogSkriptPfad(art)).getParentFile(),
                "selbstheilung_" + art + ".gemeldet");
    }

    private long gemeldetMs() {
        File f = gemeldetDatei();
        return f.exists() ? f.lastModified() : 0;
    }

    private void gemeldetMerken(long jetzt) {
        try {
            File f = gemeldetDatei();
            Files.write(f.toPath(), ("gemeldet " + jetzt + "\n").getBytes(StandardCharsets.UTF_8));
            f.setLastModified(jetzt);
        } catch (Exception ignored) {
        }
    }

    /** Titel und Text der Meldung (Server-Kataloge, 5 Sprachen), ohne Fachwort. */
    static String[] meldung(String art) {
        String teil = Messages.text("selbstheilung.wort_" + art, art);
        String titel = Messages.text("selbstheilung.titel", "Panoptes needs a moment of your help");
        String text = Messages.text("selbstheilung.text",
                "A part of Panoptes ({0}) could not set itself up again on its own. Please open Panoptes once on the "
                        + "car screen, then it sets itself up again.", teil);
        return new String[] {titel, text};
    }

    private void melden() {
        String[] m = meldung(art);
        logger.warn("Selbstheilung " + art + " klappt nicht, Meldung an den Nutzer: " + m[0]);
        try {
            if (melder != null) melder.accept(m);
        } catch (Throwable t) {
            logger.warn("Meldung: " + t.getMessage());
        }
    }

    /** Standard-Melder: Telegram (ruhig, Kategorie CRITICAL wie die Rechte-Heilung, ohne Alarm). */
    public static void telegramMelden(String[] m) {
        at.greenchili.surfmate.telegram.TelegramNotifier.sendMessage("*" + m[0] + "*\n" + m[1], "CRITICAL");
    }

    // ---------------------------------------------------------------- Startwerte aus dem laufenden Daemon

    /** Das installierte APK aus dem eigenen Klassenpfad (app_process mit CLASSPATH=…:<base.apk>). */
    public static String apkAusKlassenpfad() {
        String cp = System.getProperty("java.class.path", "");
        String apk = "";
        for (String t : cp.split(":")) if (t.endsWith(".apk")) apk = t;
        return apk;
    }

    /** Proxy-Argumente wie {@code DaemonLauncher.getProxyArgs}, aus den eigenen Startwerten zurückgebaut. */
    public static String proxyArgsAusSystem() {
        String host = System.getProperty("http.proxyHost");
        if (host == null || host.isEmpty()) return "";
        String port = System.getProperty("http.proxyPort", "8180");
        return "-Dhttp.proxyHost=" + host + " -Dhttp.proxyPort=" + port + " -Dhttps.proxyHost=" + host
                + " -Dhttps.proxyPort=" + port + " -Dhttp.nonProxyHosts=\"localhost|127.*|[::1]\" ";
    }
}
