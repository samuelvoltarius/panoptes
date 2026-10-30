package at.greenchili.surfmate.tacho;

import at.greenchili.surfmate.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * „Navi-App am Tacho“ (Kern Tacho 0.1.30, Teil 2): eine vom Nutzer gewählte Navi-App aus einer festen
 * Erlaubt-Liste wird auf die Tacho-Projektionsfläche gestartet, wie bei DashCast.
 *
 * <p>Kein eigener Projektionsweg: Start und Ende laufen ausschließlich über {@code ClusterCast} und damit
 * über {@code ClusterProjectionController}. Dessen Rückkehr-Garantie gilt unverändert (Tacho-Anzeigen
 * kommen zurück bei Fehler, Zündung aus, Daemon-Ende per SIGTERM, Neustart über den Boot-Sweep der
 * gestrandeten App). AutoContainer-Kommandos 2/3 (Warnleuchten) sperrt der Controller hart.
 *
 * <p>Regeln:
 * <ul>
 *   <li>Schalter, Standard AUS ({@code tachoNavi.an=false}).</li>
 *   <li>Einschalten nur im Stand und nur, wenn der Fähigkeitstest „Andere Apps am Tacho: ja“ meldet –
 *       also erst nach der bestätigten Probe „Warnleuchten bleiben frei“. Sonst wird die Funktion auf
 *       diesem Auto nicht angeboten.</li>
 *   <li>Nur Navi-/Karten-Apps aus {@link #ERLAUBT} dürfen während der Fahrt am Tacho bleiben. Jede andere
 *       App (Video, Spiele …) nur in P: {@link #fahrtSperre} wird von {@code ClusterCast.start} geprüft,
 *       und {@link #beiGangwechsel} beendet eine solche Projektion beim Verlassen von P.</li>
 * </ul>
 */
public final class TachoNaviApp {

    private static final String TAG = "TachoNaviApp";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    static final String SEKTION = "tachoNavi";
    /** Länge der Probe im Stand, danach kommt die Tacho-Anzeige von selbst zurück. */
    static final long PROBE_MS = 20_000L;

    /** Paket → Anzeigename. Nur Navi-/Karten-Apps, kein Video. */
    public static final Map<String, String> ERLAUBT;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("com.google.android.apps.maps", "Google Maps");
        m.put("com.waze", "Waze");
        m.put("com.iternio.abrpapp", "A Better Routeplanner (ABRP)");
        m.put("net.osmand", "OsmAnd");
        m.put("net.osmand.plus", "OsmAnd+");
        m.put("app.organicmaps", "Organic Maps");
        m.put("com.generalmagic.magicearth", "Magic Earth");
        m.put("com.here.app.maps", "HERE WeGo");
        ERLAUBT = Collections.unmodifiableMap(m);
    }

    private static volatile long probeBis;
    private static volatile String probePaket;

    private TachoNaviApp() {}

    // ---------------------------------------------------------------- reine Regeln

    public static boolean istErlaubt(String paket) {
        return paket != null && ERLAUBT.containsKey(paket.trim());
    }

    /**
     * Video nur in P: eine App außerhalb der Navi-Erlaubt-Liste darf nur auf den Tacho, wenn das Auto steht.
     *
     * @param bewegungsGrund {@code DrivingSafetyGuard.getMovementBlockReason()} (null = steht)
     * @return null = erlaubt, sonst Grund-Code
     */
    public static String fahrtSperre(String paket, String bewegungsGrund) {
        if (istErlaubt(paket)) return null;
        return bewegungsGrund == null ? null : "nur_in_p";
    }

    /**
     * Darf der Schalter eingeschaltet werden?
     *
     * @param andereApps     Urteil {@code andereApps} des Fähigkeitstests oder null
     * @param bewegungsGrund null = steht
     * @return null = erlaubt, sonst Grund-Code
     */
    static String einschaltSperre(String paket, JSONObject andereApps, String bewegungsGrund) {
        if (!istErlaubt(paket)) return "nicht_erlaubt";
        if (bewegungsGrund != null) return "nur_im_stand";
        if (andereApps == null || !TachoFaehigkeit.JA.equals(andereApps.optString("status"))) {
            return "auf_diesem_auto_nicht_angeboten";
        }
        return null;
    }

    /** Probe im Stand: nur wenn der Weg technisch da ist (nicht bei Single-OS / fehlendem Dienst). */
    static String probeSperre(String paket, JSONObject andereApps, String bewegungsGrund) {
        if (!istErlaubt(paket)) return "nicht_erlaubt";
        if (bewegungsGrund != null) return "nur_im_stand";
        String code = andereApps == null ? "" : andereApps.optString("code");
        if (!(code.startsWith("warnleuchten_"))) return "auf_diesem_auto_nicht_angeboten";
        return null;
    }

    // ---------------------------------------------------------------- Einstellungen

    static JSONObject sektion() {
        try {
            JSONObject s = at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig().optJSONObject(SEKTION);
            return s == null ? new JSONObject() : s;
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    public static boolean istAn() {
        return sektion().optBoolean("an", false);
    }

    public static String paket() {
        return sektion().optString("paket", "");
    }

    /** ja | nein | "" (noch keine Probe). */
    public static String warnleuchtenFrei() {
        String w = sektion().optString("warnleuchtenFrei", "");
        return TachoFaehigkeit.JA.equals(w) || TachoFaehigkeit.NEIN.equals(w) ? w : "";
    }

    private static boolean speichere(Map<String, Object> werte) {
        try {
            return at.greenchili.surfmate.config.UnifiedConfigManager.updateValues(SEKTION, werte);
        } catch (Throwable t) {
            logger.warn("tachoNavi speichern fehlgeschlagen: " + t.getMessage());
            return false;
        }
    }

    // ---------------------------------------------------------------- live

    private static String bewegungsGrund() {
        try {
            return at.greenchili.surfmate.byd.routing.DrivingSafetyGuard.getMovementBlockReason();
        } catch (Throwable t) {
            return "state_unknown";
        }
    }

    private static JSONObject andereApps() {
        try {
            return TachoFaehigkeit.bewerte(TachoFaehigkeit.erhebe()).optJSONObject("andereApps");
        } catch (Throwable t) {
            return null;
        }
    }

    /** Status für die Oberfläche: Schalter, gewählte App, Erlaubt-Liste (mit „installiert“), aktiv. */
    public static JSONObject status() throws Exception {
        JSONObject r = new JSONObject();
        r.put("an", istAn());
        r.put("paket", paket());
        r.put("warnleuchtenFrei", warnleuchtenFrei());
        String aktiv = null;
        try {
            if (at.greenchili.surfmate.launcher.ClusterCast.isActive()) {
                aktiv = at.greenchili.surfmate.launcher.ClusterCast.getCastPackage();
            }
        } catch (Throwable ignored) { }
        r.put("aktiv", aktiv == null ? JSONObject.NULL : aktiv);
        r.put("probeLaeuft", probeBis > System.currentTimeMillis());
        JSONArray liste = new JSONArray();
        String pm = run("pm list packages 2>/dev/null");
        for (Map.Entry<String, String> e : ERLAUBT.entrySet()) {
            liste.put(new JSONObject().put("paket", e.getKey()).put("name", e.getValue())
                    .put("installiert", pm.contains("package:" + e.getKey() + "\n")));
        }
        r.put("erlaubt", liste);
        return r;
    }

    /** Schalter setzen. Ausschalten geht immer und holt die Tacho-Anzeige sofort zurück. */
    public static synchronized JSONObject setze(boolean an, String paket) throws Exception {
        JSONObject r = new JSONObject();
        if (!an) {
            Map<String, Object> w = new HashMap<>();
            w.put("an", false);
            boolean ok = speichere(w);
            stoppeEigene();
            r.put("ok", ok);
            return r;
        }
        String p = paket == null ? "" : paket.trim();
        String sperre = einschaltSperre(p, andereApps(), bewegungsGrund());
        if (sperre != null) {
            r.put("ok", false).put("grund", sperre);
            return r;
        }
        Map<String, Object> w = new HashMap<>();
        w.put("an", true);
        w.put("paket", p);
        boolean ok = speichere(w);
        boolean gestartet = ok && at.greenchili.surfmate.launcher.ClusterCast.start(p);
        r.put("ok", ok).put("gestartet", gestartet);
        return r;
    }

    /** Probe im Stand: App für {@link #PROBE_MS} auf den Tacho, dann automatisch zurück. */
    public static synchronized JSONObject probe(String paket) throws Exception {
        JSONObject r = new JSONObject();
        String p = paket == null ? "" : paket.trim();
        String sperre = probeSperre(p, andereApps(), bewegungsGrund());
        if (sperre != null) {
            r.put("ok", false).put("grund", sperre);
            return r;
        }
        boolean gestartet = at.greenchili.surfmate.launcher.ClusterCast.start(p);
        if (!gestartet) {
            r.put("ok", false).put("grund", "start_fehlgeschlagen");
            return r;
        }
        final long bis = System.currentTimeMillis() + PROBE_MS;
        probeBis = bis;
        probePaket = p;
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(PROBE_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            synchronized (TachoNaviApp.class) {
                if (probeBis == bis) beendeProbe("zeit_um");
            }
        }, "TachoNaviProbe");
        t.setDaemon(true);
        t.start();
        r.put("ok", true).put("sekunden", PROBE_MS / 1000);
        return r;
    }

    /** Antwort des Nutzers nach der Probe: Warnleuchten frei? ja|nein. „nein“ schaltet die Funktion ab. */
    public static synchronized JSONObject bestaetige(String frei) throws Exception {
        JSONObject r = new JSONObject();
        if (!TachoFaehigkeit.JA.equals(frei) && !TachoFaehigkeit.NEIN.equals(frei)) {
            r.put("ok", false).put("grund", "ja_oder_nein");
            return r;
        }
        Map<String, Object> w = new HashMap<>();
        w.put("warnleuchtenFrei", frei);
        if (TachoFaehigkeit.NEIN.equals(frei)) w.put("an", false);
        boolean ok = speichere(w);
        if (TachoFaehigkeit.NEIN.equals(frei)) stoppeEigene();
        r.put("ok", ok);
        return r;
    }

    /** ACC an: Navi-App wieder auf den Tacho, wenn Schalter an und weiterhin erlaubt. */
    public static boolean sollBeiAccStarten() {
        return istAn() && istErlaubt(paket()) && TachoFaehigkeit.JA.equals(warnleuchtenFrei());
    }

    /**
     * Gangwechsel (CameraDaemon.onGearChanged): verlässt das Auto P, endet jede Projektion einer App
     * außerhalb der Navi-Erlaubt-Liste sowie eine laufende Probe.
     */
    public static void beiGangwechsel(int gang) {
        if (gang == at.greenchili.surfmate.monitor.GearMonitor.GEAR_P) return;
        try {
            synchronized (TachoNaviApp.class) {
                if (probeBis > 0) beendeProbe("gang_" + gang);
            }
            if (!at.greenchili.surfmate.launcher.ClusterCast.isActive()) return;
            String pkg = at.greenchili.surfmate.launcher.ClusterCast.getCastPackage();
            if (pkg != null && !istErlaubt(pkg)) {
                logger.info("Gang " + gang + ": " + pkg + " ist keine Navi-App → zurück zur Tacho-Anzeige");
                at.greenchili.surfmate.launcher.ClusterCast.stop();
            }
        } catch (Throwable t) {
            logger.warn("beiGangwechsel: " + t.getMessage());
        }
    }

    private static void beendeProbe(String grund) {
        String p = probePaket;
        probeBis = 0;
        probePaket = null;
        try {
            String aktiv = at.greenchili.surfmate.launcher.ClusterCast.getCastPackage();
            // Nicht stoppen, wenn der Nutzer inzwischen bewusst eingeschaltet hat (gleiche App, Schalter an).
            boolean dauerhaft = istAn() && p != null && p.equals(paket());
            if (p != null && p.equals(aktiv) && !dauerhaft) {
                at.greenchili.surfmate.launcher.ClusterCast.stop();
            }
            logger.info("Probe beendet (" + grund + ")");
        } catch (Throwable t) {
            logger.warn("Probe beenden: " + t.getMessage());
        }
    }

    private static void stoppeEigene() {
        try {
            String aktiv = at.greenchili.surfmate.launcher.ClusterCast.getCastPackage();
            if (aktiv != null && istErlaubt(aktiv)) at.greenchili.surfmate.launcher.ClusterCast.stop();
        } catch (Throwable t) {
            logger.warn("stoppen: " + t.getMessage());
        }
        probeBis = 0;
        probePaket = null;
    }

    /** Für Tests: die Erlaubt-Liste als Paketliste. */
    static List<String> erlaubtePakete() {
        return Arrays.asList(ERLAUBT.keySet().toArray(new String[0]));
    }

    private static String run(String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            return sb.toString();
        } catch (Exception e) {
            return "";
        } finally {
            if (p != null) p.destroy();
        }
    }
}
