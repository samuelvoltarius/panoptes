package at.greenchili.surfmate.tacho;

import at.greenchili.surfmate.logging.DaemonLogger;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Paketbezogene Nutzerfreigabe fuer Apps auf dem Fahrerdisplay.
 *
 * <p>Jede installierte App mit Launcher-Activity kann ausgewaehlt werden. Fahrt, ACC und
 * Automation bleiben jedoch gesperrt, bis der Nutzer genau dieses Paket in P auf dem echten
 * Fahrerdisplay geprueft und freie Tempo-/Warnleuchten bestaetigt hat. Alle Startwege laufen
 * weiter ueber {@code ClusterCast}; {@link #fahrtSperre} ist ihre gemeinsame Policy.
 */
public final class TachoNaviApp {
    @FunctionalInterface
    interface FreigabeSchreiber { boolean speichere(Map<String, Object> werte); }
    private static final DaemonLogger logger = DaemonLogger.getInstance("TachoNaviApp");
    private static final String SELF_PACKAGE = "at.greenchili.surfmate";
    static final String SEKTION = "tachoNavi";
    static final String FREIGABEN = "freigaben";
    static final long PROBE_MS = 20_000L;

    private static volatile long probeBis;
    private static volatile String probePaket;
    /** Nur waehrend des synchronen Probe-Starts gesetzt. */
    private static volatile String probeStartPaket;
    /** Ein fehlgeschlagener Persistenzschreibzug darf einen Widerruf nie wieder oeffnen. */
    private static final Set<String> widerrufen = ConcurrentHashMap.newKeySet();

    private TachoNaviApp() {}

    static boolean gueltigesFremdpaket(String paket) {
        if (paket == null) return false;
        String p = paket.trim();
        if (p.isEmpty() || p.length() > 255 || SELF_PACKAGE.equals(p)
                || p.startsWith(SELF_PACKAGE + ".")) return false;
        if (p.startsWith(".") || p.endsWith(".") || p.indexOf('.') < 1 || p.contains("..")) return false;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z')
                    && !(c >= '0' && c <= '9') && c != '_' && c != '.') return false;
        }
        return true;
    }

    static boolean istFreigegeben(String paket, JSONObject freigaben) {
        if (!gueltigesFremdpaket(paket) || freigaben == null) return false;
        Object wert = freigaben.opt(paket.trim());
        return Boolean.TRUE.equals(wert) || wert instanceof Number;
    }

    public static boolean istFreigegeben(String paket) {
        String p = paket == null ? "" : paket.trim();
        return !widerrufen.contains(p) && istFreigegeben(p, freigaben());
    }

    private static boolean istStartbar(String paket) {
        return gueltigesFremdpaket(paket)
                && at.greenchili.surfmate.launcher.AppLauncher.isLaunchable(paket.trim());
    }

    /** Gemeinsame Zulassung vor jedem Cluster-Start. Nur die Probe hat eine enge P-Ausnahme. */
    public static String fahrtSperre(String paket, String bewegungsGrund) {
        String p = paket == null ? "" : paket.trim();
        return fahrtSperreAuswertung(p, andereApps(p), bewegungsGrund,
                istStartbar(p), istFreigegeben(p), p.equals(probeStartPaket));
    }

    static String fahrtSperreAuswertung(String paket, JSONObject andereApps, String bewegungsGrund,
                                        boolean startbar, boolean freigegeben, boolean probe) {
        if (!gueltigesFremdpaket(paket)) return "ungueltiges_paket";
        if (!startbar) return "nicht_installiert";
        String status = andereApps == null ? "" : andereApps.optString("status");
        String code = andereApps == null ? "" : andereApps.optString("code");
        if (probe) {
            if (bewegungsGrund != null) return "nur_im_stand";
            return code.startsWith("warnleuchten_") ? null : "auf_diesem_auto_nicht_angeboten";
        }
        if (!freigegeben) return "nicht_freigegeben";
        return TachoFaehigkeit.JA.equals(status) ? null : "auf_diesem_auto_nicht_angeboten";
    }

    static String einschaltSperre(String paket, JSONObject andereApps, String bewegungsGrund,
                                  boolean startbar, boolean freigegeben) {
        if (!gueltigesFremdpaket(paket)) return "ungueltiges_paket";
        if (!startbar) return "nicht_installiert";
        if (!freigegeben) return "nicht_freigegeben";
        if (bewegungsGrund != null) return "nur_im_stand";
        if (andereApps == null || !TachoFaehigkeit.JA.equals(andereApps.optString("status"))) {
            return "auf_diesem_auto_nicht_angeboten";
        }
        return null;
    }

    static String probeSperre(String paket, JSONObject andereApps, String bewegungsGrund,
                              boolean startbar) {
        if (!gueltigesFremdpaket(paket)) return "ungueltiges_paket";
        if (!startbar) return "nicht_installiert";
        if (bewegungsGrund != null) return "nur_im_stand";
        String code = andereApps == null ? "" : andereApps.optString("code");
        if (!code.startsWith("warnleuchten_")) return "auf_diesem_auto_nicht_angeboten";
        return null;
    }

    static JSONObject sektion() {
        try {
            JSONObject s = at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig()
                    .optJSONObject(SEKTION);
            return s == null ? new JSONObject() : s;
        } catch (Throwable t) { return new JSONObject(); }
    }

    private static JSONObject freigaben() {
        JSONObject f = sektion().optJSONObject(FREIGABEN);
        return f == null ? new JSONObject() : f;
    }

    public static boolean istAn() { return sektion().optBoolean("an", false); }
    public static String paket() { return sektion().optString("paket", ""); }
    /** Kompatibilitaetswert: ja nur fuer das aktuell gewaehlte, freigegebene Paket. */
    public static String warnleuchtenFrei() {
        return warnleuchtenFrei(paket(), freigaben());
    }

    static String warnleuchtenFrei(String paket, JSONObject freigaben) {
        return istFreigegeben(paket, freigaben) ? TachoFaehigkeit.JA : "";
    }

    static JSONObject kopiereFreigaben(JSONObject original) throws Exception {
        return original == null ? new JSONObject() : new JSONObject(original.toString());
    }

    static boolean commitFreigabe(String frei, String p, JSONObject liveFreigaben,
                                   String aktuellesPaket, FreigabeSchreiber schreiber) throws Exception {
        JSONObject f = TachoFaehigkeit.JA.equals(frei)
                ? kopiereFreigaben(liveFreigaben) : liveFreigaben;
        if (f == null) f = new JSONObject();
        if (TachoFaehigkeit.JA.equals(frei)) f.put(p, System.currentTimeMillis()); else f.remove(p);
        Map<String, Object> w = new HashMap<>();
        w.put(FREIGABEN, f);
        if (TachoFaehigkeit.JA.equals(frei)) w.put("paket", p);
        if (TachoFaehigkeit.NEIN.equals(frei) && p.equals(aktuellesPaket)) w.put("an", false);
        return schreiber.speichere(w);
    }

    private static boolean speichere(Map<String, Object> werte) {
        try {
            return at.greenchili.surfmate.config.UnifiedConfigManager.updateValues(SEKTION, werte);
        } catch (Throwable t) {
            logger.warn("Fahrerdisplay-App speichern fehlgeschlagen: " + t.getMessage());
            return false;
        }
    }

    private static String bewegungsGrund() {
        try {
            return at.greenchili.surfmate.byd.routing.DrivingSafetyGuard.getMovementBlockReason();
        } catch (Throwable t) { return "state_unknown"; }
    }

    private static JSONObject andereApps(String paket) {
        try {
            TachoFaehigkeit.Eingaben e = TachoFaehigkeit.erhebe();
            e.warnleuchtenFrei = istFreigegeben(paket) ? TachoFaehigkeit.JA : "";
            return TachoFaehigkeit.bewerte(e).optJSONObject("andereApps");
        } catch (Throwable t) { return null; }
    }

    private static JSONObject plattformUrteil(boolean bestaetigt) {
        try {
            TachoFaehigkeit.Eingaben e = TachoFaehigkeit.erhebe();
            e.warnleuchtenFrei = bestaetigt ? TachoFaehigkeit.JA : "";
            return TachoFaehigkeit.bewerte(e);
        } catch (Throwable t) { return null; }
    }

    /** Status plus dieselbe Liste startbarer Apps wie die generische Projektion, ohne SurfMate. */
    public static JSONObject status() throws Exception {
        JSONObject r = new JSONObject();
        r.put("an", istAn()).put("paket", paket()).put("warnleuchtenFrei", warnleuchtenFrei());
        String aktiv = null;
        try {
            if (at.greenchili.surfmate.launcher.ClusterCast.isActive())
                aktiv = at.greenchili.surfmate.launcher.ClusterCast.getCastPackage();
        } catch (Throwable ignored) { }
        r.put("aktiv", aktiv == null ? JSONObject.NULL : aktiv);
        r.put("probeLaeuft", probeBis > System.currentTimeMillis());
        r.put("probePaket", probePaket == null ? JSONObject.NULL : probePaket);
        JSONObject probeUrteil = plattformUrteil(false), bestaetigtUrteil = plattformUrteil(true);
        JSONObject probeApps = probeUrteil == null ? null : probeUrteil.optJSONObject("andereApps");
        JSONObject bestaetigtApps = bestaetigtUrteil == null ? null : bestaetigtUrteil.optJSONObject("andereApps");
        boolean angeboten = probeApps != null && probeApps.optString("code").startsWith("warnleuchten_");
        boolean startPlattform = bestaetigtApps != null && TachoFaehigkeit.JA.equals(bestaetigtApps.optString("status"));
        r.put("angeboten", angeboten);
        r.put("grund", angeboten ? "" : (probeApps == null ? "faehigkeit_unbekannt" : probeApps.optString("code")));
        r.put("plattform", probeUrteil == null ? JSONObject.NULL : probeUrteil.opt("plattform"));
        JSONArray liste = new JSONArray();
        JSONArray apps = at.greenchili.surfmate.launcher.AppLauncher.listLaunchableApps();
        JSONObject f = freigaben();
        for (int i = 0; i < apps.length(); i++) {
            JSONObject app = apps.optJSONObject(i);
            if (app == null) continue;
            String p = app.optString("package", "");
            if (!istStartbar(p)) continue;
            boolean gespeichertFrei = !widerrufen.contains(p) && istFreigegeben(p, f);
            liste.put(new JSONObject().put("paket", p).put("name", app.optString("label", p))
                    .put("installiert", true)
                    .put("freigegeben", gespeichertFrei && startPlattform)
                    .put("gespeichertFreigegeben", gespeichertFrei));
        }
        r.put("erlaubt", liste); // Altes Feld bleibt fuer bereits ausgelieferte Web-Dateien.
        r.put("apps", liste);
        return r;
    }

    public static synchronized JSONObject setze(boolean an, String paket) throws Exception {
        JSONObject r = new JSONObject();
        if (!an) {
            Map<String, Object> w = new HashMap<>();
            w.put("an", false);
            boolean ok = speichere(w);
            stoppeEigene();
            return r.put("ok", ok);
        }
        String p = paket == null ? "" : paket.trim();
        String sperre = einschaltSperre(p, andereApps(p), bewegungsGrund(),
                istStartbar(p), istFreigegeben(p));
        if (sperre != null) return r.put("ok", false).put("grund", sperre);
        Map<String, Object> w = new HashMap<>();
        w.put("an", true); w.put("paket", p);
        boolean gespeichert = speichere(w);
        boolean gestartet = gespeichert && at.greenchili.surfmate.launcher.ClusterCast.start(p);
        if (gespeichert && !gestartet) {
            Map<String, Object> rollback = new HashMap<>();
            rollback.put("an", false);
            speichere(rollback);
        }
        return r.put("ok", gespeichert && gestartet).put("gestartet", gestartet);
    }

    /** Zeitlich begrenzte, nicht freigegebene Ausnahme ausschliesslich fuer die Probe in P. */
    public static synchronized JSONObject probe(String paket) throws Exception {
        JSONObject r = new JSONObject();
        String p = paket == null ? "" : paket.trim();
        String sperre = probeSperre(p, andereApps(p), bewegungsGrund(), istStartbar(p));
        if (sperre != null) return r.put("ok", false).put("grund", sperre);
        probeStartPaket = p;
        boolean gestartet;
        try { gestartet = at.greenchili.surfmate.launcher.ClusterCast.start(p); }
        finally { probeStartPaket = null; }
        if (!gestartet) return r.put("ok", false).put("grund", "start_fehlgeschlagen");
        final long bis = System.currentTimeMillis() + PROBE_MS;
        probeBis = bis; probePaket = p;
        Thread t = new Thread(() -> {
            try { Thread.sleep(PROBE_MS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            synchronized (TachoNaviApp.class) { if (probeBis == bis) beendeProbe("zeit_um"); }
        }, "TachoAppProbe");
        t.setDaemon(true); t.start();
        return r.put("ok", true).put("sekunden", PROBE_MS / 1000).put("paket", p);
    }

    /** Bestaetigt die laufende Probe oder widerruft eine bestehende Paketfreigabe. */
    public static synchronized JSONObject bestaetige(String frei, String paket) throws Exception {
        JSONObject r = new JSONObject();
        if (!TachoFaehigkeit.JA.equals(frei) && !TachoFaehigkeit.NEIN.equals(frei))
            return r.put("ok", false).put("grund", "ja_oder_nein");
        String p = paket == null ? "" : paket.trim();
        if (p.isEmpty()) p = probePaket == null ? "" : probePaket;
        if (!gueltigesFremdpaket(p)) return r.put("ok", false).put("grund", "ungueltiges_paket");
        if (TachoFaehigkeit.JA.equals(frei)) {
            if (probeBis <= System.currentTimeMillis() || !p.equals(probePaket))
                return r.put("ok", false).put("grund", "probe_noetig");
            if (bewegungsGrund() != null) {
                beendeProbe("nicht_mehr_in_p");
                return r.put("ok", false).put("grund", "nur_im_stand");
            }
            if (!istStartbar(p)) return r.put("ok", false).put("grund", "nicht_installiert");
        }
        // getConfig()/optJSONObject liefert den gecachten Live-Baum. Bei JA deshalb nie
        // vor dem dauerhaften Commit darin mutieren: ein Schreibfehler darf keine
        // In-Memory-Freigabe hinterlassen. NEIN bleibt zusaetzlich ueber widerrufen
        // fail-closed, selbst wenn das Speichern scheitert.
        if (TachoFaehigkeit.NEIN.equals(frei)) widerrufen.add(p);
        // Die bestaetigte App wird zugleich zur gewaehlten App. Dadurch bewertet
        // TachoFaehigkeit beim allerersten Start genau diese Freigabe und nicht ein
        // altes/leeres Paket als "warnleuchten_ungeprueft".
        boolean ok = commitFreigabe(frei, p, freigaben(), paket(), TachoNaviApp::speichere);
        if (ok && TachoFaehigkeit.JA.equals(frei)) widerrufen.remove(p);
        if (TachoFaehigkeit.NEIN.equals(frei)) stoppePaket(p); else beendeProbe("bestaetigt");
        return r.put("ok", ok).put("paket", p)
                .put("freigegeben", ok && TachoFaehigkeit.JA.equals(frei));
    }

    public static synchronized JSONObject bestaetige(String frei) throws Exception {
        return bestaetige(frei, "");
    }

    public static boolean sollBeiAccStarten() {
        String p = paket();
        return istAn() && fahrtSperre(p, null) == null;
    }

    /** Verlassen von P beendet jede Probe und jede nicht freigegebene aktive App. */
    public static void beiGangwechsel(int gang) {
        if (gang == at.greenchili.surfmate.monitor.GearMonitor.GEAR_P) return;
        try {
            synchronized (TachoNaviApp.class) { if (probeBis > 0) beendeProbe("gang_" + gang); }
            if (!at.greenchili.surfmate.launcher.ClusterCast.isActive()) return;
            String p = at.greenchili.surfmate.launcher.ClusterCast.getCastPackage();
            if (p != null && !istFreigegeben(p)) {
                logger.info("Gang " + gang + ": " + p + " nicht freigegeben - Tacho zurueck");
                at.greenchili.surfmate.launcher.ClusterCast.stop();
            }
        } catch (Throwable t) { logger.warn("beiGangwechsel: " + t.getMessage()); }
    }

    private static void beendeProbe(String grund) {
        String p = probePaket;
        probeBis = 0; probePaket = null; probeStartPaket = null;
        try {
            String aktiv = at.greenchili.surfmate.launcher.ClusterCast.getCastPackage();
            boolean dauerhaft = istAn() && p != null && p.equals(paket()) && istFreigegeben(p);
            if (p != null && p.equals(aktiv) && !dauerhaft)
                at.greenchili.surfmate.launcher.ClusterCast.stop();
            logger.info("Fahrerdisplay-Probe beendet (" + grund + ")");
        } catch (Throwable t) { logger.warn("Probe beenden: " + t.getMessage()); }
    }

    private static void stoppePaket(String p) {
        try {
            String aktiv = at.greenchili.surfmate.launcher.ClusterCast.getCastPackage();
            if (p != null && p.equals(aktiv)) at.greenchili.surfmate.launcher.ClusterCast.stop();
        } catch (Throwable t) { logger.warn("stoppen: " + t.getMessage()); }
        if (p != null && p.equals(probePaket)) {
            probeBis = 0; probePaket = null; probeStartPaket = null;
        }
    }

    private static void stoppeEigene() { stoppePaket(paket()); }
}
