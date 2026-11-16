package at.greenchili.surfmate.launcher;

import at.greenchili.surfmate.logging.DaemonLogger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 0.1.43.9 (T2) App-Fenster im Panoptes-Tacho: startet eine fremde App auf der eigenen Flaeche der Tacho-Ansicht.
 * Nur Ausfuehrung; ob es darf, entscheidet {@code tacho.TachoAppFenster}.
 *
 * <p><b>Weg:</b> Die Panoptes-Tacho-Ansicht legt eine SurfaceView genau in den Kasten des Designs und erzeugt
 * darauf ein PRIVATES VirtualDisplay ({@code VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY}, wie
 * {@code RemoteDevVirtualDisplay}). Der Daemon (uid 2000) startet die App mit {@code am start --display <id>} darauf.
 * Android 10 laesst das zu, weil die Shell {@code INTERNAL_SYSTEM_WINDOW} hat
 * ({@code ActivityStackSupervisor.isCallerAllowedToLaunchOnDisplay}: diese Pruefung kommt vor der Regel fuer fremde
 * virtuelle Displays). Die App zeichnet dann nur in diesen Kasten: Sie kann das Tempo nicht verdecken, es braucht kein
 * Freeform, keine Systemeinstellung und keine Fensterleiste.
 *
 * <p>Verworfen (Emulator Android 10, 15.11.2026): Freeform direkt auf dem Zweitdisplay. Ohne Freeform-Unterstuetzung
 * liegt die App dort im Vollbild ueber allem; mit Freeform braeuchte es eine Systemeinstellung samt Neustart.
 */
public final class TachoAppFensterStart {

    private static final DaemonLogger logger = DaemonLogger.getInstance("TachoAppFenster");
    private static final int TASK_WARTE_MS = 3000;
    private static final int TASK_TAKT_MS = 250;

    private TachoAppFensterStart() {}

    /** App auf der Flaeche {@code displayId} starten. true nur, wenn ihr Task danach wirklich dort liegt. */
    public static boolean zeige(String paket, int displayId) {
        if (paket == null || !AppLauncher.isValidPackageName(paket) || displayId <= 0) return false;
        String komponente = AppLauncher.resolveLauncherComponent(paket);
        if (komponente == null || komponente.isEmpty()) return false;
        String aus = shell("am start --user 0 --display " + displayId + " -n " + komponente);
        if (aus == null || aus.contains("Error") || aus.contains("Exception")) {
            logger.warn("App-Fenster: Start von " + paket + " auf Flaeche " + displayId + " abgelehnt: " + aus);
            return false;
        }
        if (aufDisplay(paket, displayId)) return true;
        logger.warn("App-Fenster: " + paket + " nicht auf Flaeche " + displayId + " angekommen — vom Fahrerdisplay");
        weg(paket);
        return false;
    }

    /** Laeuft die App noch auf dieser Flaeche? (Nutzer hat sie geschlossen, App abgestuerzt …) Unklar = ja. */
    public static boolean laeuft(String paket, int displayId) {
        ClusterFreeformWindow.TaskLocation o = ClusterFreeformWindow.findTaskLocation(paket, displayId);
        return !o.known || (o.taskId >= 0 && o.displayId == displayId);
    }

    /** App von der Flaeche nehmen: im Hintergrund aufs Infotainment (kein Vordergrund-Start waehrend der Fahrt). */
    public static void weg(String paket) {
        if (paket == null) return;
        try {
            AppLauncher.reparentToDisplay0(paket);
        } catch (Throwable e) {
            logger.warn("App-Fenster weg: " + e.getMessage());
        }
    }

    private static boolean aufDisplay(String paket, int displayId) {
        for (int w = 0; w < TASK_WARTE_MS; w += TASK_TAKT_MS) {
            ClusterFreeformWindow.TaskLocation o = ClusterFreeformWindow.findTaskLocation(paket, displayId);
            if (o.known && o.taskId >= 0 && o.displayId == displayId) return true;
            try { Thread.sleep(TASK_TAKT_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return false;
    }

    /** Kurzer Shell-Befehl (hoechstens 5 s); erste Zeile getrimmt, null bei Fehler oder Zeitueberschreitung. */
    static String shell(String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            final Process q = p;
            final StringBuilder alles = new StringBuilder();
            Thread leser = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(q.getInputStream(), StandardCharsets.UTF_8))) {
                    String z;
                    while ((z = r.readLine()) != null) if (alles.length() < 2000) alles.append(z.trim()).append(' ');
                } catch (Exception ignored) { }
            }, "TachoAppFenster-sh");
            leser.setDaemon(true);
            leser.start();
            if (!p.waitFor(5, TimeUnit.SECONDS)) return null;
            leser.join(500);
            return p.exitValue() == 0 ? alles.toString().trim() : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }
}
