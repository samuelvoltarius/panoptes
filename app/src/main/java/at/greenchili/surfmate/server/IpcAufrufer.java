package at.greenchili.surfmate.server;

import java.io.File;

/**
 * 0.1.38.8 Zugangsdaten Teil 2: Wer darf Befehle an die Wächter-IPC (127.0.0.1:19977) schicken?
 *
 * <p>Die IPC liefert und schreibt die ganze Config ({@code GET_CONFIG}, {@code EXPORT_CONFIG}, {@code REPLACE_CONFIG},
 * {@code UPDATE_*}), startet Updates und schaltet den Wächter. „Lauscht nur auf 127.0.0.1“ reicht im Auto nicht:
 * jede andere App am Gerät kann 127.0.0.1 erreichen, und ein tailscaled ohne den no-local-forward-Patch reicht
 * Tailnet-Verbindungen an 127.0.0.1 weiter ({@code docs/SICHERHEIT_TAILNET_LOOPBACK_20261021.md}).
 *
 * <p>Einstufung ({@link #stufe}):
 * <ul>
 *   <li>{@link Stufe#FREI}: Sensor-Ströme „senden und schließen“ ({@code UPDATE_GPS}, {@code IMU_BATCH}) – der
 *       Socket kann beim Nachsehen schon zu sein, eine Prüfung würde echte Daten verwerfen. Signierte DiLink-5-Befehle
 *       und {@code DETERRENT_INPUT_CAPTURE} (Token) prüft der Server vorher selbst.</li>
 *   <li>{@link Stufe#LOKAL}: alles andere. Erlaubt: uid 1000 (SentryDaemon, AccSentryDaemon), uid 2000 außer
 *       tailscaled (Kamera-/Telegram-Daemon, adb shell, Deploy-Werkzeuge), die uid des Pakets Panoptes.</li>
 *   <li>{@link Stufe#APP_SYSTEM}: {@link AuthZugangIpc#BEFEHL} – nur App und uid 1000 ({@link AuthZugangIpc}).</li>
 * </ul>
 * Fail-closed: unlesbare Tabelle, unbekannte uid oder uid 0 (so stehen auch geschlossene Sockets in TIME_WAIT da)
 * → abgelehnt. Notschalter: Datei {@value #NOTSCHALTER} im Daemon-Verzeichnis (nur uid 2000 kann sie anlegen) schaltet
 * die Prüfung ab, falls ein eigenes Werkzeug doch einmal ausgesperrt würde.
 */
final class IpcAufrufer {

    enum Stufe { FREI, LOKAL, APP_SYSTEM }

    static final int SYSTEM_UID = 1000;
    static final int SHELL_UID = 2000;
    static final String NOTSCHALTER = "ipc_pruefung_aus";

    /** Gerät (im Test nachgebaut). */
    interface Quelle {
        int uid(int clientPort, int serverPort);
        boolean gehoertTailscaled(int clientPort, int serverPort);
        /** uid des Pakets Panoptes; {@code neu} = Gedächtnis ignorieren (z. B. nach Neuinstallation). */
        int appUid(boolean neu);
        boolean notschalter();
    }

    static volatile Quelle quelle = new Geraet();

    private IpcAufrufer() {}

    /** Beim Daemon-Start im Hintergrund: App-uid einmal ermitteln, damit die erste App-Anfrage nicht wartet. */
    static void vorwaermen() {
        Thread t = new Thread(() -> {
            try {
                quelle.appUid(false);
            } catch (Throwable ignored) { }
        }, "IpcAufrufer-AppUid");
        t.setDaemon(true);
        t.start();
    }

    static Stufe stufe(String befehl) {
        if ("UPDATE_GPS".equals(befehl)
                || at.greenchili.surfmate.roadsense.detect.ImuFrameCodec.COMMAND.equals(befehl)
                || "DETERRENT_INPUT_CAPTURE".equals(befehl)) return Stufe.FREI;
        if (AuthZugangIpc.BEFEHL.equals(befehl)) return Stufe.APP_SYSTEM;
        return Stufe.LOKAL;
    }

    /** Rein: ist ein Aufrufer mit dieser uid für {@link Stufe#LOKAL} vertrauenswürdig? */
    static boolean lokalErlaubt(int uid, boolean tailscaled, int appUid) {
        if (uid == SYSTEM_UID) return true;
        if (uid == SHELL_UID) return !tailscaled;
        return appUid >= 10_000 && uid == appUid;
    }

    /** Prüfung einer Verbindung (einmal je Verbindung, beim ersten geprüften Befehl). */
    static boolean vertraut(int clientPort, int serverPort) {
        return vertraut(clientPort, serverPort, quelle);
    }

    static boolean vertraut(int clientPort, int serverPort, Quelle q) {
        try {
            if (q.notschalter()) return true;
            int uid = q.uid(clientPort, serverPort);
            if (uid == SYSTEM_UID) return true;
            if (uid == SHELL_UID) return !q.gehoertTailscaled(clientPort, serverPort);
            if (uid < 10_000) return false;
            if (lokalErlaubt(uid, false, q.appUid(false))) return true;
            return lokalErlaubt(uid, false, q.appUid(true));   // App neu installiert → neue uid
        } catch (Exception e) {
            return false;
        }
    }

    private static final class Geraet implements Quelle {
        private int appUid = -1;
        private long neuFruehestens;
        private long notschalterBis;
        private boolean notschalterWert;

        @Override public int uid(int clientPort, int serverPort) {
            return LoopbackPeer.clientUid(clientPort, serverPort);
        }

        @Override public boolean gehoertTailscaled(int clientPort, int serverPort) {
            return LoopbackPeer.gehoertTailscaled(clientPort, serverPort);
        }

        @Override public synchronized int appUid(boolean neu) {
            long jetzt = System.currentTimeMillis();
            if (appUid >= 10_000 && !neu) return appUid;
            if (appUid >= 10_000 && jetzt < neuFruehestens) return appUid;   // höchstens einmal je Minute neu fragen
            neuFruehestens = jetzt + 60_000L;
            int u = AuthZugangIpc.appUidAusListe(at.greenchili.surfmate.appupdate.LauncherPaket.SHELL.shell(
                    "cmd package list packages -U " + AuthZugangIpc.APP_PAKET, 10));
            if (u >= 10_000) appUid = u;
            return appUid;
        }

        @Override public synchronized boolean notschalter() {
            long jetzt = System.currentTimeMillis();
            if (jetzt >= notschalterBis) {
                notschalterWert = new File(at.greenchili.surfmate.util.DaemonStorage.path(NOTSCHALTER)).exists();
                notschalterBis = jetzt + 30_000L;
            }
            return notschalterWert;
        }
    }
}
