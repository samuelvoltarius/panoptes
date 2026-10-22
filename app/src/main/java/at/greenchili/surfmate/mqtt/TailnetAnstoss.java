package at.greenchili.surfmate.mqtt;

import at.greenchili.surfmate.logging.DaemonLogger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Stößt das Panoptes-eigene tailscaled an, wenn der MQTT-Broker im Tailnet liegt
 * (Deploy-Messung 21.10.: nach dem Netzwechsel beim Parken kamen die Connects 7–9 min lang nicht
 * durch; geheilt erst durch eingehenden Tailnet-Verkehr. tailscaled läuft unter der Shell-UID und
 * bekommt Netzwechsel offenbar nicht mit, also baut es seine UDP-Wege nicht selbst neu).
 *
 * <ul>
 *   <li>{@code tailscale debug rebind} – UDP-Sockets neu binden (höchstens alle {@link #REBIND_ABSTAND_MS}).</li>
 *   <li>{@code tailscale ping -c 1} zum Broker – Disco-Pfad aktiv aufbauen.</li>
 * </ul>
 * Regel B: tailscaled wird NIE beendet oder neu gestartet; beide Befehle sind nur CLI-Anfragen an
 * die laufende LocalAPI. Ohne Panoptes-Tailscale (CLI fehlt) passiert nichts.
 */
public final class TailnetAnstoss {

    private static final DaemonLogger logger = DaemonLogger.getInstance("TailnetAnstoss");

    static final long REBIND_ABSTAND_MS = 60_000L;
    private static final String CLI = at.greenchili.surfmate.util.DaemonStorage.rebase(
            "/data/user_de/0/com.android.shell/surfmate/.tailscale") + "/tailscale";
    private static final String SOCKET = "127.0.0.1:8632";

    /** Für Tests austauschbar: führt einen Befehl aus und liefert stdout. */
    public interface Shell { String run(String cmd, int timeoutSec); }

    static Shell shell = TailnetAnstoss::run;
    /** Nur Tests schalten das ab (dort gibt es keine CLI-Datei). */
    static boolean cliPruefen = true;
    private static volatile long letzterRebindMs = 0L;

    private TailnetAnstoss() {}

    /** Liegt der Broker im Tailnet (CGNAT 100.64.0.0/10, Tailscale-IPv6 fd7a:115c:a1e0::/48 oder *.ts.net)? */
    public static boolean istTailnetZiel(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase(java.util.Locale.ROOT);
        if (h.startsWith("[")) h = h.substring(1, h.length() - (h.endsWith("]") ? 1 : 0));
        if (h.endsWith(".ts.net") || h.endsWith(".ts.net.")) return true;
        if (h.startsWith("fd7a:115c:a1e0:")) return true;
        String[] t = h.split("\\.");
        if (t.length != 4) return false;
        try {
            int a = Integer.parseInt(t[0]);
            int b = Integer.parseInt(t[1]);
            return a == 100 && b >= 64 && b <= 127;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Wann anstoßen: bei Netzwechsel, sonst ab dem 2. Fehlschlag jeder zweite. */
    public static boolean sollAnstossen(boolean netzwechsel, int fehlversuche) {
        return netzwechsel || (fehlversuche >= 2 && fehlversuche % 2 == 0);
    }

    /** Rebind nur bei Netzwechsel oder jedem 4. Fehlschlag, und gedrosselt. */
    static boolean sollRebind(boolean netzwechsel, int fehlversuche, long jetztMs, long letzterMs) {
        boolean anlass = netzwechsel || (fehlversuche >= 4 && fehlversuche % 4 == 0);
        return anlass && (letzterMs == 0L || jetztMs - letzterMs >= REBIND_ABSTAND_MS);
    }

    /** Host aus einer Broker-URI wie tcp://100.1.2.3:1883 bzw. ssl://name.ts.net:8883. */
    public static String hostAus(String brokerUri) {
        if (brokerUri == null) return null;
        try {
            return new java.net.URI(brokerUri).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Anstoß ausführen (blockiert höchstens ~8 s). Keine Wirkung, wenn das Ziel nicht im Tailnet
     * liegt oder kein Anlass besteht.
     * @return true, wenn angestoßen wurde
     */
    public static boolean anstossen(String brokerUri, boolean netzwechsel, int fehlversuche) {
        String host = hostAus(brokerUri);
        if (!istTailnetZiel(host) || !sollAnstossen(netzwechsel, fehlversuche)) return false;
        if (cliPruefen && !new java.io.File(CLI).exists()) return false;
        long jetzt = System.currentTimeMillis();
        if (sollRebind(netzwechsel, fehlversuche, jetzt, letzterRebindMs)) {
            letzterRebindMs = jetzt;
            shell.run("'" + CLI + "' --socket " + SOCKET + " debug rebind 2>&1 || true", 5);
        }
        String ziel = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        String antwort = shell.run("'" + CLI + "' --socket " + SOCKET
                + " ping -c 1 --timeout 3s " + ziel + " 2>&1 || true", 6);
        logger.info("Tailnet angestoßen (netzwechsel=" + netzwechsel + ", fehlversuche=" + fehlversuche
                + "): " + (antwort == null ? "" : antwort.trim().replace('\n', ' ')));
        return true;
    }

    /** Nur für Tests. */
    static void zuruecksetzen() { letzterRebindMs = 0L; }

    private static String run(String cmd, int timeoutSec) {
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor(timeoutSec, TimeUnit.SECONDS);
            return sb.toString();
        } catch (Exception e) {
            return "";
        } finally {
            if (p != null) p.destroy();
        }
    }
}
