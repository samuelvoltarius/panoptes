package at.greenchili.surfmate.power;

import at.greenchili.surfmate.logging.DaemonLogger;

import org.json.JSONObject;

/**
 * 0.1.13 (Kern 7): Wächter für Weg 2 (fremde Tailscale-App mit Always-on-VPN, die BYD im Stand beendet). Läuft im
 * Kamera-Daemon (Shell-UID), prüft jede Minute nur lesend und sendet höchstens den offiziellen Broadcast der
 * Tailscale-App ({@link Weg2Regel#befehl()}). Fasst weder Panoptes-tailscaled (Weg 1, Regel B) noch die
 * Tailscale-Konfiguration an. Schalter {@code fernzugang.weg2Waechter} (Standard an; wirkt ohnehin nur, wenn der
 * Nutzer die Tailscale-App selbst als Always-on-VPN eingetragen hat).
 */
public final class Weg2Waechter {

    private static final DaemonLogger logger = DaemonLogger.getInstance("Weg2Waechter");
    private static final long TAKT_MS = 60_000L;
    private static final long PRUEFEN_NACH_MS = 2 * 60_000L;

    private static volatile Thread takt;
    private static volatile JSONObject stand = new JSONObject();

    private static boolean warSchonAktiv;
    private static long totSeit;
    private static long letzterVersuch;
    private static long pruefenAb;
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
        l.letzterVersuchMs = letzterVersuch;
        l.versucheHeute = versucheHeute;
        l.fehlschlaegeHeute = fehlschlaegeHeute;

        // Ergebnis eines Versuchs nach 2 min nur lesend auswerten
        if (pruefenAb > 0 && jetzt >= pruefenAb) {
            if (lebt) logger.info("Weg 2 wieder da nach CONNECT_VPN");
            else { fehlschlaegeHeute++; logger.warn("Weg 2 nach CONNECT_VPN weiter tot (" + fehlschlaegeHeute + " heute)"); }
            pruefenAb = 0;
        }

        String grund = null;
        if (relevant && !lebt && pruefenAb == 0) {
            // Display-Zustand erst lesen, wenn sonst alles passt (dumpsys ist teurer)
            l.displayAus = true;
            grund = Weg2Regel.grund(l);
            if (grund == null) {
                l.displayAus = Weg2Regel.displayAus(shell("dumpsys power"));
                grund = Weg2Regel.grund(l);
            }
            if (grund == null) {
                letzterVersuch = jetzt;
                versucheHeute++;
                versuchtDiesenPark = true;
                pruefenAb = jetzt + PRUEFEN_NACH_MS;
                String r = shell(Weg2Regel.befehl());
                logger.info("Weg 2 seit " + ((jetzt - totSeit) / 60_000L) + " min tot, Auto steht, Display aus → CONNECT_VPN ("
                        + r.trim().replaceAll("\\s+", " ") + ")");
            }
        } else {
            grund = Weg2Regel.grund(l);
        }
        stand = new JSONObject().put("relevant", relevant).put("lebt", lebt).put("warSchonAktiv", warSchonAktiv)
                .put("totSeit", totSeit).put("letzterVersuch", letzterVersuch).put("versucheHeute", versucheHeute)
                .put("fehlschlaegeHeute", fehlschlaegeHeute).put("prozessDa", l.prozessDa)
                .put("versuchtDiesenPark", versuchtDiesenPark).put("grund", grund == null ? "gesendet" : grund);
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
