package at.greenchili.surfmate.launcher;

import at.greenchili.surfmate.appupdate.LauncherHome;
import at.greenchili.surfmate.appupdate.LauncherPaket;
import at.greenchili.surfmate.config.UnifiedConfigManager;
import at.greenchili.surfmate.logging.DaemonLogger;
import at.greenchili.surfmate.server.BefehlsLog;

import org.json.JSONObject;

/**
 * Wächter für den Panoptes Launcher als Standard-Startbildschirm (0.1.38.7). Läuft im Kamera-Daemon (als {@code shell}),
 * überlebt also einen toten oder abstürzenden Launcher. Entscheidet {@link HomeWaechterRegel}, ausgeführt wird über
 * {@link LauncherHome} (feste Befehle). Zurück heißt immer: BYD-Startbildschirm wieder als Standard.
 *
 * <p>Takt 5 s, solange der Schalter an ist oder wir Standard sind; ist beides aus, nur alle 60 s ein Blick. Der Schalter
 * ist Config {@code launcher.startbildschirm} (Standard aus), „Behalten“ ist {@code launcher.startbildschirmBehalten}.
 */
public final class HomeWaechter {

    private static final DaemonLogger logger = DaemonLogger.getInstance("HomeWaechter");

    public static final String SCHALTER = "startbildschirm";
    public static final String BEHALTEN = "startbildschirmBehalten";

    private static final long TAKT_MS = 5_000L;
    private static final long RUHE_MS = 60_000L;

    private static volatile Thread thread;
    private static volatile boolean laeuft;
    private static final HomeWaechterRegel REGEL = new HomeWaechterRegel();

    private HomeWaechter() {}

    /** Idempotent. */
    public static synchronized void start() {
        if (thread != null && thread.isAlive()) return;
        laeuft = true;
        thread = new Thread(HomeWaechter::schleife, "HomeWaechter");
        thread.setDaemon(true);
        thread.start();
    }

    public static boolean wunsch() {
        return abschnitt().optBoolean(SCHALTER, false);
    }

    public static boolean behalten() {
        return abschnitt().optBoolean(BEHALTEN, false);
    }

    private static JSONObject abschnitt() {
        try {
            JSONObject l = UnifiedConfigManager.loadConfig().optJSONObject("launcher");
            return l == null ? new JSONObject() : l;
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    /** Schalter und „Behalten“ setzen; true = geschrieben. */
    public static boolean setze(boolean wunsch, boolean behalten) {
        try {
            JSONObject d = new JSONObject().put(SCHALTER, wunsch).put(BEHALTEN, behalten);
            return UnifiedConfigManager.updateSection("launcher", d);
        } catch (Throwable t) {
            logger.warn("Schalter nicht geschrieben: " + t.getMessage());
            return false;
        }
    }

    private static void schleife() {
        long letzterBlick = 0;
        // Beim Start (auch nach Daemon-Neustart) gilt der vorhandene Zustand, "Behalten" bleibt erhalten
        boolean letzterWunsch = wunsch();
        while (laeuft) {
            try {
                boolean w = wunsch();
                if (w && !letzterWunsch) {
                    // Frisch eingeschaltet: „Behalten“ aus früheren Runden gilt nicht, der Totmann beginnt neu.
                    setze(true, false);
                }
                letzterWunsch = w;
                long jetzt = android.os.SystemClock.elapsedRealtime();
                boolean sofort = w || jetzt - letzterBlick >= RUHE_MS;
                if (sofort) {
                    letzterBlick = jetzt;
                    LauncherHome.Stand s = LauncherHome.lesen(LauncherPaket.SHELL);
                    String pid = pid();
                    HomeWaechterRegel.Aktion a = REGEL.pruefe(w, behalten(), s.standard, pid, jetzt);
                    if (a != HomeWaechterRegel.Aktion.NICHTS) rueckfall(a, s);
                }
                Thread.sleep(w ? TAKT_MS : 10_000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                logger.warn("Takt: " + t.getMessage());
                try { Thread.sleep(TAKT_MS); } catch (InterruptedException ie) { return; }
            }
        }
    }

    /** Zurück zum BYD-Startbildschirm; Schalter aus. Wird auch von der Oberfläche, vom Launcher und von Telegram benutzt. */
    public static String rueckfall(HomeWaechterRegel.Aktion grund, LauncherHome.Stand s) {
        String name = grund.name().toLowerCase(java.util.Locale.ROOT);
        logger.warn("Rückfall zum BYD-Startbildschirm: " + name);
        setze(false, false);
        LauncherHome.Stand stand = s != null ? s : LauncherHome.lesen(LauncherPaket.SHELL);
        String fehler = LauncherHome.zurueckZumOriginal(LauncherPaket.SHELL, stand);
        if (fehler != null) {
            logger.error("Rückfall Stufe 1 fehlgeschlagen (" + fehler + ") - Launcher-Paket wird ausgeschaltet");
            fehler = LauncherHome.paketAus(LauncherPaket.SHELL);
        }
        REGEL.zuruecksetzen();
        try {
            BefehlsLog.ereignis("launcher.home:rueckfall", fehler == null ? BefehlsLog.OK : BefehlsLog.FEHLER, name, null);
        } catch (Throwable ignored) {
        }
        return fehler;
    }

    /** PID des Launcher-Prozesses oder "" (läuft nicht). */
    static String pid() {
        String aus = LauncherPaket.SHELL.shell("pidof " + LauncherHome.PAKET, 5);
        if (aus == null) return "";
        String t = aus.trim();
        return t.matches("[0-9 ]{1,40}") ? t : "";
    }
}
