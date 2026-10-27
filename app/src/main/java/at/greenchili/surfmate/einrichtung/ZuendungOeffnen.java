package at.greenchili.surfmate.einrichtung;

import at.greenchili.surfmate.logging.DaemonLogger;

import org.json.JSONObject;

import java.util.Locale;

/**
 * 0.1.20 P7 „Panoptes bei Zündung an öffnen“ (Koordination 27.10.): Die BYD-Autostart-Freigabe greift nur beim
 * Hochfahren des Infotainments (Deploy-Bericht 27.10. 17:36–17:39). Mit diesem Schalter holt Panoptes die eigene
 * Activity bei JEDEM Übergang auf ACC an nach vorn.
 * <ul>
 *   <li>Standard aus ({@code autostart.beiZuendung}); gefragt im Einrichtungsschritt „Autostart“.</li>
 *   <li>Läuft CarPlay/Android Auto im Vordergrund, drängt sich Panoptes nicht davor.</li>
 *   <li>Steht Panoptes schon vorn, passiert nichts.</li>
 *   <li>Nur die Activity – kein Ton, nichts außen am Auto (A+).</li>
 * </ul>
 */
public final class ZuendungOeffnen {

    private static final DaemonLogger logger = DaemonLogger.getInstance("ZuendungOeffnen");

    public static final String PAKET = "at.greenchili.surfmate";
    /** Warten nach ACC an: Launcher und Projektion (CarPlay verbindet einige Sekunden später) sollen erst stehen. */
    static final long WARTEN_MS = 8_000L;

    private ZuendungOeffnen() {}

    /** Ergebnis der Prüfung, mit Grund für Protokoll und Tests. */
    public static String entscheide(boolean schalter, String resumedZeile, JSONObject projektionPakete) {
        if (!schalter) return "aus";
        String art = ProjektionsHinweis.aktiv(resumedZeile, projektionPakete);
        if (art != null) return "projektion_" + art;
        if (resumedZeile != null && resumedZeile.toLowerCase(Locale.ROOT).contains(" " + PAKET + "/")) return "schon_vorn";
        return "oeffnen";
    }

    /** Schalter aus der Einstellung (Standard aus, Fehler = aus). */
    static boolean schalter(JSONObject root) {
        JSONObject a = root == null ? null : root.optJSONObject("autostart");
        return a != null && a.optBoolean("beiZuendung", false);
    }

    private static volatile long letzterGeneration = Long.MIN_VALUE;

    /** Vom Daemon beim (gelesenen, nicht doppelten) ACC-an-Übergang; eigener Thread, blockiert nie den Übergang. */
    public static void beiAccAn(long generation) {
        if (generation == letzterGeneration) return;
        letzterGeneration = generation;
        Thread t = new Thread(() -> {
            try {
                if (!schalter(at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig())) return;
                Thread.sleep(WARTEN_MS);
                // nach dem Warten erneut: inzwischen ACC wieder aus oder Schalter aus → nichts
                if (!at.greenchili.surfmate.monitor.AccMonitor.isAccOn()) return;
                boolean an = schalter(at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig());
                String zeile = ProjektionsHinweis.resumed();
                String e = entscheide(an, zeile, at.greenchili.surfmate.server.VehicleCapabilities.projektionPakete());
                logger.info("ACC an: " + e);
                if ("oeffnen".equals(e)) at.greenchili.surfmate.launcher.AppLauncher.launch(PAKET);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Throwable t2) {
                logger.warn("ACC an: " + t2.getMessage());
            }
        }, "ZuendungOeffnen");
        t.setDaemon(true);
        t.start();
    }
}
