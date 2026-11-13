package at.greenchili.surfmate.server;

import at.greenchili.surfmate.daemon.CameraDaemon;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.1.44 (P2) Wer darf Sprachbefehle an den Kern schicken? Nur die Panoptes-App selbst ({@value #PAKET}), und nur
 * direkt am Auto über Loopback: die Linux-UID des Absenders (wie bei {@link LauncherAufrufer}) muss die UID der App
 * sein. Kopplung, Handy-Web, Telegram, Home Assistant, MQTT und Automationen kommen so nie an die Route.
 */
final class SprachAufrufer {

    static final String PAKET = "at.greenchili.surfmate";
    static final String OK = null;
    static final String NICHT_LOKAL = "nicht_lokal";
    static final String NICHT_APP = "nicht_app";
    static final long MERKEN_MS = 60_000L;

    private static final Pattern UID = Pattern.compile("package:" + Pattern.quote(PAKET) + "\\s+uid:(\\d{1,9})");

    /** Gerät (im Test nachgebaut). */
    interface Quelle {
        int quellport();
        int uid(int quellport, int serverport);
        /** Ausgabe von {@code cmd package list packages -U <paket>}. */
        String paketListe();
        long jetzt();
    }

    static volatile Quelle quelle = new Geraet();

    private static int appUid = -1;
    private static long gemerktBis;

    private SprachAufrufer() {}

    /** null = die Panoptes-App selbst, direkt am Auto; sonst Grund. */
    static String pruefe() {
        Quelle q = quelle;
        int port = q.quellport();
        if (port <= 0) return NICHT_LOKAL;
        int uid = q.uid(port, CameraDaemon.HTTP_PORT);
        if (uid < 10_000) return NICHT_APP;   // keine App-UID (shell, system, root)
        long jetzt = q.jetzt();
        int app;
        synchronized (SprachAufrufer.class) {
            if (appUid < 0 || jetzt >= gemerktBis) {
                appUid = uidAus(q.paketListe());
                gemerktBis = jetzt + MERKEN_MS;
            }
            app = appUid;
        }
        return app >= 10_000 && uid == app ? OK : NICHT_APP;
    }

    /** UID der App aus der Paketliste; -1, wenn nicht gefunden. Andere Pakete mit ähnlichem Namen zählen nicht. */
    static int uidAus(String liste) {
        if (liste == null) return -1;
        for (String zeile : liste.split("\\r?\\n")) {
            Matcher m = UID.matcher(zeile.trim());
            if (m.matches()) return Integer.parseInt(m.group(1));
        }
        return -1;
    }

    /** Für Tests. */
    static synchronized void vergessen() {
        appUid = -1;
        gemerktBis = 0;
    }

    private static final class Geraet implements Quelle {
        @Override public int quellport() { return AuthMiddleware.direktQuellport(); }
        @Override public int uid(int quellport, int serverport) { return LoopbackPeer.clientUid(quellport, serverport); }
        @Override public String paketListe() {
            return at.greenchili.surfmate.appupdate.LauncherPaket.SHELL.shell("cmd package list packages -U " + PAKET, 10);
        }
        @Override public long jetzt() { return System.currentTimeMillis(); }
    }
}
