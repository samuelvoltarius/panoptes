package at.greenchili.surfmate.surveillance;

import at.greenchili.surfmate.byd.BydDataCollector;
import at.greenchili.surfmate.byd.BydVehicleData;
import at.greenchili.surfmate.config.UnifiedConfigManager;
import at.greenchili.surfmate.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Calendar;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Laufzeit des Wächter-Stopps im Kamera-Daemon (Logik = {@link GuardStopPolicy}).
 *
 * <p>Takt 60 s. Nur bei autoritativ bekanntem "ACC aus" wird ausgewertet; ACC an setzt alles
 * zurück. Bei Stopp schaltet der Monitor die laufende Überwachung ab und sperrt jedes neue
 * Scharfschalten ({@link #blocksArming()}, abgefragt in {@code CameraDaemon}); hebt sich der
 * Stopp auf, schaltet er sie über den normalen, generationsgeprüften Weg wieder ein.
 * Beginn des Parkens wird in einer kleinen Datei gemerkt, damit ein Daemon-Neustart die
 * Parkuhr ({@code maxParkHours}) nicht zurücksetzt.
 */
public final class GuardStopMonitor {

    private static final DaemonLogger logger = DaemonLogger.getInstance("GuardStop");
    private static final long TICK_MS = 60_000L;
    private static final String PARKED_SINCE_FILE =
            at.greenchili.surfmate.util.DaemonStorage.rebase(
                    "/data/user_de/0/com.android.shell/surfmate/guardstop_parked_since");
    /** 0.1.11: Ruhespannung je Tag (nach ≥ 60 min Parken, ohne Laden) für die 12-V-Chemie-Heuristik, 14 Tage. */
    private static final String RUHE_TAGE_FILE =
            at.greenchili.surfmate.util.DaemonStorage.rebase(
                    "/data/user_de/0/com.android.shell/surfmate/guardstop_v12_ruhe_tage.json");
    private static final int RUHE_TAGE_BEHALTEN = 14;
    /** 0.1.28 P8: letzte 12-V-Vorwarnung {stufe, zeit}, damit ein Daemon-Neustart nicht erneut meldet. */
    private static final String WARNUNG_12V_FILE =
            at.greenchili.surfmate.util.DaemonStorage.rebase(
                    "/data/user_de/0/com.android.shell/surfmate/guardstop_v12_warnung.json");

    /** 0.1.31: Stand der 12-V-Auswertung, damit ein Daemon-Neustart den Zyklus nicht von vorn beginnt. */
    private static final String V12_STAND_FILE =
            at.greenchili.surfmate.util.DaemonStorage.rebase(
                    "/data/user_de/0/com.android.shell/surfmate/guardstop_v12_stand.json");
    private static final long V12_STAND_TAKT_MS = 10 * 60_000L;

    /** Zustellung der 12-V-Warnung (in Tests ersetzbar). */
    interface Melder12v {
        void melden(Starterbatterie12v.Stufe stufe, double volt);
    }

    static volatile Melder12v melder12v = GuardStopMonitor::melden12v;

    /** Eingriff in den Kamera-Daemon (in Tests ersetzbar). */
    public interface Surveillance {
        boolean isRunning();
        void stop();
        void start();
    }

    private static volatile GuardStopMonitor instance;

    private final GuardStopPolicy policy = new GuardStopPolicy();
    private final Battery12vEvaluator v12;
    private final Surveillance surveillance;
    private ScheduledExecutorService exec;
    private long parkedSinceMs;
    private boolean stoppedByUs;

    GuardStopMonitor(Surveillance surveillance, Battery12vEvaluator.Profile profile) {
        this.surveillance = surveillance;
        this.v12 = new Battery12vEvaluator(profile);
    }

    public static synchronized void start(Surveillance surveillance) {
        if (instance != null) return;
        GuardStopMonitor m = new GuardStopMonitor(surveillance,
                isDiLink3() ? Battery12vEvaluator.Profile.DILINK3 : Battery12vEvaluator.Profile.DEFAULT);
        m.parkedSinceMs = readParkedSince();
        if (m.parkedSinceMs > 0L) m.v12.wiederherstellen(leseV12Stand(m.parkedSinceMs), System.currentTimeMillis());
        m.exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "GuardStopMonitor");
            t.setDaemon(true);
            return t;
        });
        m.exec.scheduleWithFixedDelay(() -> {
            try {
                m.tick();
            } catch (Throwable t) {
                logger.warn("tick failed: " + t.getMessage());
            }
        }, 30_000L, TICK_MS, TimeUnit.MILLISECONDS);
        instance = m;
        logger.info("started (12-V-Profil " + (isDiLink3() ? "DiLink 3" : "Standard") + ")");
    }

    /** ACC an (vom Daemon gemeldet): sofort zurücksetzen, nicht erst beim nächsten Takt. */
    public static void onAccOn() {
        GuardStopMonitor m = instance;
        if (m != null) m.resetForDrive();
    }

    synchronized void resetForDrive() {
        if (policy.isActive() || parkedSinceMs != 0L) {
            logger.info("ACC an – Wächter-Stopp zurückgesetzt");
        }
        policy.reset();
        v12.reset();
        schreibeV12Stand(v12.stand());
        stoppedByUs = false;
        if (parkedSinceMs != 0L) {
            parkedSinceMs = 0L;
            writeParkedSince(0L);
        }
    }

    /** 0.1.28 Rückkehr-Karte: letzter Stopp (Grund + Zeit). Bleibt über ACC an hinaus erhalten (resetForDrive löscht ihn nicht). */
    private static volatile String letzterStoppGrund;
    private static volatile long letzterStoppMs;

    /** Grund eines Wächter-Stopps, der seit {@code abMs} begann, oder null. */
    public static String stoppGrundSeit(long abMs) {
        String g = letzterStoppGrund;
        return g != null && letzterStoppMs > 0 && letzterStoppMs >= abMs ? g : null;
    }

    /** Nur für Tests. */
    static void merkeStopp(String grund, long ms) {
        letzterStoppGrund = grund;
        letzterStoppMs = ms;
    }

    /** Grund des laufenden Wächter-Stopps oder null. */
    public static String aktuellerGrund() {
        GuardStopMonitor m = instance;
        return m == null ? null : m.policy.reason();
    }

    /** true, solange der Wächter-Stopp jedes Scharfschalten verhindern soll. */
    public static boolean blocksArming() {
        GuardStopMonitor m = instance;
        return m != null && m.policy.isActive();
    }

    /** Felder für {@code /api/surveillance/status}. */
    public static void putStatus(java.util.Map<String, Object> status) {
        GuardStopMonitor m = instance;
        boolean active = m != null && m.policy.isActive();
        status.put("guardStopActive", active);
        status.put("guardStopReason", active ? m.policy.reason() : null);
        status.put("guardStopSince", active ? m.policy.sinceMs() : 0L);
        if (m != null) {
            double med = m.v12.lastMedian();
            status.put("guardStopV12Median", Double.isNaN(med) ? null : Math.round(med * 100) / 100.0);
            status.put("guardStopV12State", m.v12.state().name().toLowerCase());
            status.put("guardStopV12Samples", m.v12.sampleCount());
            status.put("guardStopParkedSince", m.parkedSinceMs);
        }
        // 0.1.11: 12-V-Chemie mit Quelle (nutzer|profil|vermutet) und Zahl der Ruhetage ≥ 13,0 V
        try {
            at.greenchili.surfmate.einstellungen.AkkuChemie.Ergebnis ch =
                    at.greenchili.surfmate.einstellungen.AkkuChemie.aktuellMitQuelle(loadRoot());
            status.put("guardStopV12Chemie", ch.chemie);
            status.put("guardStopV12ChemieQuelle", ch.quelle);
            if (!ch.grund.isEmpty()) status.put("guardStopV12ChemieGrund", ch.grund);
            status.put("guardStopV12RuheTageLfp", at.greenchili.surfmate.einstellungen.AkkuChemie.tageAb(
                    ruheTage(), at.greenchili.surfmate.einstellungen.AkkuChemie.LFP_RUHE_V));
        } catch (Throwable ignored) {
        }
    }

    synchronized void tick() {
        boolean authoritative = at.greenchili.surfmate.monitor.AccMonitor.isAccStateAuthoritative();
        if (!authoritative) return;
        long now = System.currentTimeMillis();
        if (at.greenchili.surfmate.monitor.AccMonitor.isAccOn()) {
            resetForDrive();
            return;
        }
        if (parkedSinceMs <= 0L || parkedSinceMs > now) {
            parkedSinceMs = now;
            writeParkedSince(now);
        }
        JSONObject root = loadRoot();
        String chemie = at.greenchili.surfmate.einstellungen.AkkuChemie.aktuell(root);
        GuardStopPolicy.Config cfg = GuardStopPolicy.Config.from(
                root == null ? null : root.optJSONObject("guardStop"),
                at.greenchili.surfmate.einstellungen.AkkuChemie.v12Standard(chemie));
        BydVehicleData d = null;
        try {
            d = BydDataCollector.getInstance().getData();
        } catch (Throwable ignored) {
        }
        GuardStopPolicy.Inputs in = new GuardStopPolicy.Inputs();
        in.nowMs = now;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int dow = cal.get(Calendar.DAY_OF_WEEK); // SUNDAY=1
        in.isoDayOfWeek = dow == Calendar.SUNDAY ? 7 : dow - 1;
        in.minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        in.parkedSinceMs = parkedSinceMs;
        if (d != null) {
            in.socPercent = d.socPercent > 0 && d.socPercent <= 100 ? d.socPercent : Double.NaN;
            v12.offer(d.voltage12v, d.voltage12vAtMs, parkedSinceMs);
        }
        // Auch bei abgeschalteter Stopp-Regel auswerten: Die Warnung hängt nicht daran (GuardStopPolicy prüft v12Below selbst).
        double[] v12Schwellen = Starterbatterie12v.schwellen(cfg.v12Below, cfg.v12Resume, chemie);
        in.v12 = v12.evaluate(v12Schwellen[0], v12Schwellen[1], now);
        v12StandMerken(now);
        ruheTagMerken(now);
        warnung12vPruefen(now, cfg, chemie, ruheTage());
        try {
            in.inQuietZone = SafeLocationManager.getInstance().isInSafeZone();
        } catch (Throwable ignored) {
        }
        apply(cfg, in);
    }

    /** Ergebnis eines Takts umsetzen (paketweit für Tests). */
    void apply(GuardStopPolicy.Config cfg, GuardStopPolicy.Inputs in) {
        boolean wasActive = policy.isActive();
        boolean changed = policy.step(cfg, in);
        boolean active = policy.isActive();
        if (active) {
            if (!wasActive) {
                logger.info("Wächter-Stopp: " + policy.reason());
                merkeStopp(policy.reason(), System.currentTimeMillis());
                if (cfg.notify) GuardStopNotice.stopped(policy.reason());
                // Server-Wächter S1: geplantes Schlafen, keine unerwartete Stille.
                GuardStateReporter.schlaf("guardStop:" + policy.reason());
            } else if (changed) {
                logger.info("Wächter-Stopp: Grund jetzt " + policy.reason());
            }
            if (surveillance.isRunning()) {
                surveillance.stop();
                stoppedByUs = true;
            } else if (!wasActive) {
                // Noch nicht scharf (Ruhezone, Zeitplan …): Wieder-Ein trotzdem uns zuschreiben.
                stoppedByUs = true;
            }
        } else if (wasActive) {
            logger.info("Wächter-Stopp aufgehoben");
            if (cfg.notify && cfg.enabled) GuardStopNotice.resumed();
            // start() geht über enableSurveillance(): prüft Hauptschalter, Ruhezone, ACC-Generation.
            if (stoppedByUs) surveillance.start();
            stoppedByUs = false;
        }
    }

    boolean isActiveForTest() { return policy.isActive(); }
    String reasonForTest() { return policy.reason(); }

    private static JSONObject loadRoot() {
        try {
            return UnifiedConfigManager.loadConfig();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Ruhe-Median der 12-V-Auswertung (NaN, solange unbekannt oder der DC-DC lädt). */
    public static double ruheMedian12v() {
        GuardStopMonitor m = instance;
        if (m == null || m.v12.isCharging()) return Double.NaN;
        return m.v12.lastMedian();
    }

    /** 0.1.31: Oberkante der Ruhespannung ({@link Battery12vEvaluator#ruheOben()}), NaN = noch unbekannt. */
    public static double ruheOben12v() {
        GuardStopMonitor m = instance;
        return m == null ? Double.NaN : m.v12.ruheOben();
    }

    public static boolean istDiLink3() { return isDiLink3(); }

    /** 0.1.11: gemerkte Ruhespannungen je Tag (Kopie; leer, wenn keine). */
    public static synchronized JSONObject ruheTage() {
        try {
            File f = new File(RUHE_TAGE_FILE);
            if (!f.isFile()) return new JSONObject();
            return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    /** Nach ≥ 60 min Parken und ohne Laden: Oberkante der Ruhe des Tages merken (je Tag der größte Wert). */
    private void ruheTagMerken(long now) {
        try {
            if (parkedSinceMs <= 0 || now - parkedSinceMs < at.greenchili.surfmate.einstellungen.AkkuChemie.RUHE_NACH_MS) return;
            if (v12.isCharging()) return;
            double oben = v12.ruheOben();
            if (Double.isNaN(oben)) return;
            String tag = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(new java.util.Date(now));
            synchronized (GuardStopMonitor.class) {
                JSONObject t = ruheTage();
                if (at.greenchili.surfmate.einstellungen.AkkuChemie.ruheTagEintragen(t, tag, oben, RUHE_TAGE_BEHALTEN)) {
                    File f = new File(RUHE_TAGE_FILE);
                    File tmp = new File(RUHE_TAGE_FILE + ".tmp");
                    Files.write(tmp.toPath(), t.toString().getBytes(StandardCharsets.UTF_8));
                    if (!tmp.renameTo(f)) {
                        Files.write(f.toPath(), t.toString().getBytes(StandardCharsets.UTF_8));
                        tmp.delete();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 0.1.28 P8: Vorwarnung 12 V

    private Starterbatterie12v.Merker merker12v;

    /**
     * Nur Ruhespannung (≥ 60 min geparkt, DC-DC lädt nicht). „Sehr schwach“ nur mit dem Urteil der Auswertung
     * ({@link Battery12vEvaluator.State#LOW}), die Vorwarnung nur aus der Oberkante; der Tiefpunkt des Sägezahns
     * warnt nie. Meldet über {@link #melder12v}, höchstens einmal je Stufe und Tag (Merker auf Platte). Fehler
     * dürfen den Wächter-Stopp nie stören.
     */
    synchronized void warnung12vPruefen(long now, GuardStopPolicy.Config cfg, String chemie, JSONObject ruheTage) {
        try {
            if (parkedSinceMs <= 0 || now - parkedSinceMs < at.greenchili.surfmate.einstellungen.AkkuChemie.RUHE_NACH_MS) return;
            if (v12.isCharging()) return;
            boolean tief = v12.state() == Battery12vEvaluator.State.LOW;
            double ruhe = tief ? v12.lastMedian() : v12.ruheOben();
            if (Double.isNaN(ruhe)) return;
            double[] s = Starterbatterie12v.schwellen(cfg.v12Below, cfg.v12Resume, chemie);
            String heute = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(new java.util.Date(now));
            Starterbatterie12v.Stufe stufe = Starterbatterie12v.bestaetigt(
                    Starterbatterie12v.bewerten(ruhe, s[0], chemie, ruheTage, heute), tief);
            if (merker12v == null) merker12v = leseMerker12v();
            Starterbatterie12v.Merker neu = merker12v.nach(stufe, ruhe >= s[1], now);
            if (neu.stufe != merker12v.stufe || neu.zeit != merker12v.zeit) schreibeMerker12v(neu);
            merker12v = neu;
            if (neu.melden) {
                logger.info("12-V-Warnung: " + stufe + " (Ruhe " + Math.round(ruhe * 100) / 100.0 + " V, Stopp unter "
                        + s[0] + " V, " + chemie + ")");
                melder12v.melden(stufe, ruhe);
            }
        } catch (Throwable t) {
            logger.debug("12-V-Warnung: " + t.getMessage());
        }
    }

    /** Telegram (Kategorie Akku, Schalter batteryAlerts) + Handy-Push (vehicle.health.battery12v). */
    static void melden12v(Starterbatterie12v.Stufe stufe, double volt) {
        try {
            at.greenchili.surfmate.telegram.TelegramNotifier.notifyBattery12v(volt, Starterbatterie12v.telegramStufe(stufe));
        } catch (Throwable t) {
            logger.debug("12-V-Telegram: " + t.getMessage());
        }
        try {
            at.greenchili.surfmate.notifications.NotificationBus.get().publish(Starterbatterie12v.pushEreignis(
                    stufe, volt, at.greenchili.surfmate.server.LocaleManager.get()));
        } catch (Throwable t) {
            logger.debug("12-V-Push: " + t.getMessage());
        }
    }

    static Starterbatterie12v.Merker leseMerker12v() {
        try {
            File f = new File(WARNUNG_12V_FILE);
            if (!f.isFile()) return Starterbatterie12v.Merker.leer();
            return Starterbatterie12v.Merker.aus(new JSONObject(
                    new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
        } catch (Throwable t) {
            return Starterbatterie12v.Merker.leer();
        }
    }

    private long v12StandGeschriebenMs;
    private boolean v12StandLow;

    private void v12StandMerken(long now) {
        Battery12vEvaluator.Stand st = v12.stand();
        if (st.low == v12StandLow && now - v12StandGeschriebenMs < V12_STAND_TAKT_MS) return;
        v12StandGeschriebenMs = now;
        v12StandLow = st.low;
        schreibeV12Stand(st);
    }

    /** null, wenn nichts da ist oder der Stand aus einem früheren Parken stammt. */
    private static Battery12vEvaluator.Stand leseV12Stand(long parkedSince) {
        try {
            File f = new File(V12_STAND_FILE);
            if (!f.isFile()) return null;
            JSONObject j = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            long letzte = j.optLong("letzteMessungMs", 0L);
            if (letzte < parkedSince) return null;
            return new Battery12vEvaluator.Stand(j.optLong("zyklusMs", 0L), letzte, j.optBoolean("low", false));
        } catch (Throwable t) {
            return null;
        }
    }

    private static void schreibeV12Stand(Battery12vEvaluator.Stand st) {
        try {
            byte[] b = new JSONObject().put("zyklusMs", st.zyklusMs).put("letzteMessungMs", st.letzteMessungMs)
                    .put("low", st.low).toString().getBytes(StandardCharsets.UTF_8);
            File f = new File(V12_STAND_FILE);
            File tmp = new File(V12_STAND_FILE + ".tmp");
            Files.write(tmp.toPath(), b);
            if (!tmp.renameTo(f)) {
                Files.write(f.toPath(), b);
                tmp.delete();
            }
        } catch (Throwable t) {
            logger.debug("12-V-Stand nicht gespeichert: " + t.getMessage());
        }
    }

    private static void schreibeMerker12v(Starterbatterie12v.Merker m) {
        try {
            File f = new File(WARNUNG_12V_FILE);
            File tmp = new File(WARNUNG_12V_FILE + ".tmp");
            byte[] b = m.json().toString().getBytes(StandardCharsets.UTF_8);
            Files.write(tmp.toPath(), b);
            if (!tmp.renameTo(f)) {
                Files.write(f.toPath(), b);
                tmp.delete();
            }
        } catch (Throwable t) {
            logger.debug("12-V-Merker nicht gespeichert: " + t.getMessage());
        }
    }

    /** Für Tests: Auswertung mit festen Werten füttern. */
    void v12ForTest(double volts, long readAtMs, long parkedSince, double below, double resume) {
        parkedSinceMs = parkedSince;
        v12.offer(volts, readAtMs, parkedSince);
        v12.evaluate(below, resume, readAtMs);
    }

    void merker12vForTest(Starterbatterie12v.Merker m) { merker12v = m; }

    static boolean isDiLink3() {
        try {
            return !at.greenchili.surfmate.camera.dilink5.DiLink5Platform.isSelected()
                    && !at.greenchili.surfmate.camera.dilink5.DiLink5Platform.isDiLink4Selected();
        } catch (Throwable t) {
            return true;
        }
    }

    private static long readParkedSince() {
        try {
            File f = new File(PARKED_SINCE_FILE);
            if (!f.isFile()) return 0L;
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? 0L : Long.parseLong(s);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static void writeParkedSince(long ms) {
        try {
            File f = new File(PARKED_SINCE_FILE);
            File tmp = new File(PARKED_SINCE_FILE + ".tmp");
            Files.write(tmp.toPath(), Long.toString(ms).getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(f)) {
                Files.write(f.toPath(), Long.toString(ms).getBytes(StandardCharsets.UTF_8));
                tmp.delete();
            }
        } catch (Throwable t) {
            logger.debug("parked-since not saved: " + t.getMessage());
        }
    }
}
