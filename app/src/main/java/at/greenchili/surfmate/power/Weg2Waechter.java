package at.greenchili.surfmate.power;

import at.greenchili.surfmate.logging.DaemonLogger;

import org.json.JSONObject;

/**
 * Wächter für Weg 2 (fremde Tailscale-App mit Always-on-VPN, die BYD beim Abstellen beendet). Läuft im Kamera-Daemon
 * (Shell-UID) und prüft jede Minute nur lesend.
 *
 * <p>0.1.13/0.1.14 sendete er den Broadcast {@code CONNECT_VPN}. Praxisbefund 22.10. 16:11 (Deploy): Nach BYDs
 * forceStop bringt der Broadcast die App nicht hoch (result=0, kein Prozess, kein tun). Seit 0.1.15 sendet er nichts
 * mehr und meldet nur noch; der unabhängige zweite Weg ist der Notzugang (NOTZUGANG_KONZEPT.md). Schalter
 * {@code fernzugang.weg2Waechter} (Standard an, wirkt nur bei Always-on-VPN der Tailscale-App).
 */
public final class Weg2Waechter {

    private static final DaemonLogger logger = DaemonLogger.getInstance("Weg2Waechter");
    private static final long TAKT_MS = 60_000L;

    private static volatile Thread takt;
    private static volatile JSONObject stand = new JSONObject();

    private static boolean warSchonAktiv;
    private static long totSeit;
    private static boolean warGeparkt;
    private static boolean versuchtDiesenPark;
    private static String tag = "";
    private static int versucheHeute, fehlschlaegeHeute;

    private Weg2Waechter() {}

    public static synchronized void starten() {
        if (takt != null) return;
        takt = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try { Thread.sleep(TAKT_MS); } catch (InterruptedException e) { return; }
                try {
                    tick(System.currentTimeMillis());
                } catch (Throwable t) {
                    logger.warn("Weg-2-Wächter: " + t.getMessage());
                }
            }
        }, "Weg2Waechter");
        takt.setDaemon(true);
        takt.setPriority(Thread.MIN_PRIORITY);
        takt.start();
    }

    /** Für /api/system/eingriffe. */
    public static JSONObject stand() {
        return stand;
    }

    static synchronized void tick(long jetzt) throws Exception {
        String heute = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(new java.util.Date(jetzt));
        if (!heute.equals(tag)) { tag = heute; versucheHeute = 0; fehlschlaegeHeute = 0; }

        Weg2Regel.Lage l = new Weg2Regel.Lage();
        l.jetztMs = jetzt;
        l.schalterAn = schalterAn();
        l.alwaysOnPaket = shell("settings get secure always_on_vpn_app").trim();
        boolean relevant = Weg2Regel.PAKET.equals(l.alwaysOnPaket);
        if (relevant) {
            l.prozessDa = !shell("pidof " + Weg2Regel.PAKET).trim().isEmpty();
            l.vpnNetzDa = shell("ls /sys/class/net 2>/dev/null").matches("(?s).*\\btun\\d*\\b.*");
        }
        // lebendig = VPN-Netz da; ein Prozess allein kann der leere Rest nach BYDs forceStop sein
        boolean lebt = l.vpnNetzDa;
        if (lebt) { warSchonAktiv = true; totSeit = 0; }
        else if (relevant && warSchonAktiv && totSeit == 0) totSeit = jetzt;
        l.warSchonAktiv = warSchonAktiv;
        l.totSeitMs = totSeit;
        l.autoSteht = autoSteht();
        if (l.autoSteht && !warGeparkt) versuchtDiesenPark = false;   // neuer Park
        warGeparkt = l.autoSteht;
        l.ersterVersuchDiesesParks = !versuchtDiesenPark;
        l.versucheHeute = versucheHeute;
        l.fehlschlaegeHeute = fehlschlaegeHeute;

        // 0.1.15 (Praxisbefund Deploy 22.10. 16:11): Nach BYDs forceStop beim Abstellen startet CONNECT_VPN die
        // Tailscale-App NICHT (Broadcast result=0, aber kein Prozess, kein tun). Der Wächter sendet deshalb nie mehr,
        // er meldet nur noch (Stand + einmal je Park ein Log-Eintrag). Die Wiederbelebung übernimmt der Notzugang.
        l.displayAus = true;   // nur Meldung: kein dumpsys power nötig
        String grund = relevant ? Weg2Regel.grund(l) : "kein_always_on";
        if (relevant && !lebt && grund == null && !versuchtDiesenPark) {
            versuchtDiesenPark = true;
            logger.info("Weg 2 (Tailscale-App) seit " + ((jetzt - totSeit) / 60_000L)
                    + " min tot, Auto steht – nur gemeldet (CONNECT_VPN wirkt nach dem BYD-Kill nicht; Notzugang nutzen)");
        }
        stand = new JSONObject().put("modus", "nur_melden").put("relevant", relevant).put("lebt", lebt)
                .put("warSchonAktiv", warSchonAktiv).put("totSeit", totSeit).put("prozessDa", l.prozessDa)
                .put("gemeldetDiesenPark", versuchtDiesenPark)
                .put("grund", grund == null ? "tot_gemeldet" : grund);
    }

    private static boolean schalterAn() {
        try {
            JSONObject f = at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig().optJSONObject("fernzugang");
            return f == null || f.optBoolean("weg2Waechter", true);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean autoSteht() {
        try {
            return at.greenchili.surfmate.monitor.AccMonitor.isAccStateAuthoritative()
                    && !at.greenchili.surfmate.monitor.AccMonitor.isAccOn();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String shell(String cmd) {
        try {
            Process p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (java.io.InputStream in = p.getInputStream()) {
                byte[] b = new byte[8192];
                int n;
                long ende = System.currentTimeMillis() + 8000;
                while ((n = in.read(b)) > 0 && sb.length() < 512_000 && System.currentTimeMillis() < ende) {
                    sb.append(new String(b, 0, n, "UTF-8"));
                }
            }
            if (!p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
