package at.greenchili.surfmate.server;

import at.greenchili.surfmate.appupdate.LauncherPaket;
import at.greenchili.surfmate.daemon.CameraDaemon;

/**
 * 0.1.37 Stufe 4: Kommt eine schreibende Launcher-Anfrage ({@code klima}, {@code update/installieren}) wirklich von der
 * Panoptes-Launcher-APK mit derselben Signatur wie Panoptes?
 *
 * <ol>
 *   <li>nur direkt am Auto ({@link AuthMiddleware#isDirektAmAuto()}: Loopback, kein Tunnel, nicht über tailscaled,
 *       keine Kopplung, keine Handy-Web-Sitzung),</li>
 *   <li>die Linux-UID des Gegen-Sockets ({@code /proc/net/tcp}) ist die UID des Pakets
 *       {@value LauncherPaket#PAKET},</li>
 *   <li>das Signaturzertifikat dieser APK ist das der installierten Panoptes-App.</li>
 * </ol>
 *
 * <p>Fail-closed: Was nicht lesbar ist, gilt als fremd. Das Ergebnis je UID wird kurz gemerkt (Paketprüfung kostet
 * zwei Shell-Aufrufe und einen APK-Blick); eine Neuinstallation bekommt ohnehin eine neue UID.
 */
final class LauncherAufrufer {

    static final String OK = null;
    static final String NICHT_LOKAL = "nicht_lokal";
    static final String NICHT_LAUNCHER = "nicht_launcher";
    static final String SIGNATUR = "signatur";

    static final long MERKEN_MS = 60_000L;

    /** Gerät (im Test nachgebaut). */
    interface Quelle {
        /** Quellport der Anfrage, wenn sie direkt am Auto kam, sonst −1. */
        int quellport();
        /** UID des Gegen-Sockets oder −1. */
        int uid(int quellport, int serverport);
        LauncherPaket.Stand launcher();
        String launcherZertifikat(LauncherPaket.Stand s);
        String eigenesZertifikat();
        long jetzt();
    }

    static volatile Quelle quelle = new Geraet();

    private static int gemerkteUid = -1;
    private static long gemerktBis;

    private LauncherAufrufer() {}

    /** null = Launcher-APK mit Panoptes-Signatur, sonst Grund. */
    static String pruefe() {
        Quelle q = quelle;
        int port = q.quellport();
        if (port <= 0) return NICHT_LOKAL;
        int uid = q.uid(port, CameraDaemon.HTTP_PORT);
        if (uid < 10_000) return NICHT_LAUNCHER;   // keine App-UID (shell, system, root)
        long jetzt = q.jetzt();
        synchronized (LauncherAufrufer.class) {
            if (uid == gemerkteUid && jetzt < gemerktBis) return OK;
        }
        LauncherPaket.Stand s = q.launcher();
        if (s == null || !s.installiert() || s.uid != uid) return NICHT_LAUNCHER;
        String eigen = q.eigenesZertifikat();
        String fremd = q.launcherZertifikat(s);
        if (eigen == null || fremd == null || !eigen.equals(fremd)) return SIGNATUR;
        synchronized (LauncherAufrufer.class) {
            gemerkteUid = uid;
            gemerktBis = jetzt + MERKEN_MS;
        }
        return OK;
    }

    /** Für Tests: Gedächtnis leeren. */
    static synchronized void vergessen() {
        gemerkteUid = -1;
        gemerktBis = 0;
    }

    private static final class Geraet implements Quelle {
        @Override public int quellport() { return AuthMiddleware.direktQuellport(); }
        @Override public int uid(int quellport, int serverport) { return LoopbackPeer.clientUid(quellport, serverport); }
        @Override public LauncherPaket.Stand launcher() { return LauncherPaket.stand(LauncherPaket.SHELL); }
        @Override public String launcherZertifikat(LauncherPaket.Stand s) { return LauncherPaket.zertifikat(s); }

        @Override
        public String eigenesZertifikat() {
            try {
                java.io.File apk = at.greenchili.surfmate.appupdate.ApkQuelle.installiertesApk(System.getenv("CLASSPATH"));
                return apk == null ? null : at.greenchili.surfmate.appupdate.ApkZertifikat.sha256(apk);
            } catch (Throwable t) {
                return null;
            }
        }

        @Override public long jetzt() { return System.currentTimeMillis(); }
    }
}
