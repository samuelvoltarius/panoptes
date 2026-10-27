package at.greenchili.surfmate.ladekurve;

import at.greenchili.surfmate.logging.DaemonLogger;
import at.greenchili.surfmate.monitor.ChargingStateData;
import at.greenchili.surfmate.monitor.ChargingTypeClassifier;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 0.1.19 Ladekurve im Daemon: Messpunkte aus dem Lade-Sampler ({@link #probe}), Abschluss je Ladung, lokale Datei,
 * einmalige Startkurve aus dem eigenen Verlauf (DC-Ladungen der Ladehistorie) und der stündliche Takt fürs Teilen
 * (nur mit Einwilligung, {@link LadekurveTeilen}). Die Datei verlässt das Auto nie; im Log nur Anzahlen.
 */
public final class LadekurveDienst {

    private static final DaemonLogger logger = DaemonLogger.getInstance("Ladekurve");

    static final String DATEI = at.greenchili.surfmate.util.DaemonStorage.rebase(
            "/data/user_de/0/com.android.shell/surfmate/ladekurve.json");
    /** Ohne neuen Punkt so lange → Ladung abschließen (Sampler gestoppt, Prozess blieb). */
    static final long RUHE_MS = 15 * 60_000L;
    static final int MAX_PUNKTE = 5000;
    static final int IMPORT_MAX = 300;

    private LadekurveDienst() {}

    private static final Object LOCK = new Object();
    private static long sitzungStart = -1L;
    private static boolean sitzungDc;
    private static long letzterPunktMs;
    private static final List<Ladekurve.Punkt> punkte = new ArrayList<>();

    /**
     * Ein Messpunkt aus dem Lade-Sampler (alle paar Sekunden). Echte Leistung (MEASURED) oder Schätzung
     * (ESTIMATED, z. B. ringEstimator) – die Qualität wird mitgeführt. UNKNOWN/UNVERIFIED zählen nicht. Wirft nie.
     *
     * @param dcVerdict {@link ChargingTypeClassifier} der offenen Ladung (DC wird nur gezählt, wenn bewiesen)
     */
    public static void probe(long sessionStart, double soc, double kw, ChargingStateData cs, int dcVerdict) {
        try {
            if (sessionStart <= 0 || cs == null) return;
            long jetzt = System.currentTimeMillis();
            synchronized (LOCK) {
                if (sessionStart != sitzungStart) {
                    abschliessenLocked();
                    sitzungStart = sessionStart;
                }
                if (dcVerdict == ChargingTypeClassifier.DC) sitzungDc = true;
                ChargingStateData.PowerQuality q = cs.powerQuality;
                boolean gemessen = q == ChargingStateData.PowerQuality.MEASURED && !cs.isEstimated;
                boolean geschaetzt = q == ChargingStateData.PowerQuality.ESTIMATED || (cs.isEstimated && q == null);
                if ((gemessen || geschaetzt) && Ladekurve.gueltig(soc, kw) && punkte.size() < MAX_PUNKTE) {
                    punkte.add(new Ladekurve.Punkt(soc, kw, gemessen));
                    letzterPunktMs = jetzt;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Lade-Sampler: Ladung zu Ende. Wirft nie. */
    public static void sitzungEnde() {
        try {
            synchronized (LOCK) {
                abschliessenLocked();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void abschliessenLocked() throws Exception {
        long start = sitzungStart;
        boolean dc = sitzungDc;
        List<Ladekurve.Punkt> l = new ArrayList<>(punkte);
        punkte.clear();
        sitzungStart = -1L;
        sitzungDc = false;
        letzterPunktMs = 0;
        if (start <= 0 || !dc || l.isEmpty()) return;
        JSONObject s = Ladekurve.sitzung(start, l);
        if (s == null) return;
        JSONObject sp = lesen();
        Ladekurve.aufnehmen(sp, s);
        schreiben(sp);
        logger.info("Ladekurve: DC-Ladung aufgenommen (" + s.getJSONObject("b").length() + " Bänder, "
                + s.optString("q") + "), gesamt " + Ladekurve.ladungen(sp));
    }

    // ---------------------------------------------------------------- Datei

    static JSONObject lesen() {
        try {
            File f = new File(DATEI);
            if (!f.isFile()) return Ladekurve.leer();
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            return o.optJSONArray("sitzungen") == null ? Ladekurve.leer() : o;
        } catch (Throwable t) {
            try { return Ladekurve.leer(); } catch (Exception e) { return new JSONObject(); }
        }
    }

    static void schreiben(JSONObject sp) throws Exception {
        File f = new File(DATEI);
        File d = f.getParentFile();
        if (d != null && !d.isDirectory()) d.mkdirs();
        File tmp = new File(DATEI + ".tmp");
        Files.write(tmp.toPath(), sp.toString().getBytes(StandardCharsets.UTF_8));
        if (!tmp.renameTo(f)) {
            Files.write(f.toPath(), sp.toString().getBytes(StandardCharsets.UTF_8));
            tmp.delete();
        }
    }

    // ---------------------------------------------------------------- Startkurve

    /**
     * Einmalig: eigene DC-Ladungen aus der Ladehistorie als Startkurve (Messpunkte, wo vorhanden, sonst der
     * Durchschnitt der Ladung). Merker {@code importiert}; gilt für jede Installation mit eigenem Verlauf.
     */
    static int startkurve(JSONObject sp, at.greenchili.surfmate.monitor.SocHistoryDatabase db) throws Exception {
        if (sp.optBoolean("importiert", false) || db == null) return 0;
        JSONArray ladungen = db.getDcSessionsForCurve(IMPORT_MAX);
        int n = 0;
        for (int i = 0; i < ladungen.length(); i++) {
            JSONObject l = ladungen.getJSONObject(i);
            JSONObject s = null;
            JSONArray proben = db.getChargingSamples(l.optLong("id"));
            List<Ladekurve.Punkt> p = new ArrayList<>();
            for (int k = 0; proben != null && k < proben.length(); k++) {
                JSONObject x = proben.optJSONObject(k);
                if (x == null || x.isNull("power") || x.isNull("soc")) continue;
                p.add(new Ladekurve.Punkt(x.optDouble("soc", Double.NaN), x.optDouble("power", Double.NaN), true));
            }
            if (!p.isEmpty()) s = Ladekurve.sitzung(l.optLong("start"), p);
            if (s == null) s = Ladekurve.ausDurchschnitt(l.optLong("start"), l.optDouble("startSoc", Double.NaN),
                    l.optDouble("endSoc", Double.NaN), l.optDouble("avgKw", Double.NaN));
            if (s != null) {
                // eine schon live gemessene Ladung nicht durch die gröbere Startkurve ersetzen
                if (!enthalten(sp, s.optLong("start"))) { Ladekurve.aufnehmen(sp, s); n++; }
            }
        }
        sp.put("importiert", true);
        return n;
    }

    private static boolean enthalten(JSONObject sp, long start) {
        JSONArray a = sp.optJSONArray("sitzungen");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject s = a.optJSONObject(i);
            if (s != null && s.optLong("start") == start) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- Takt

    /** Stündlich aus dem Daemon: ruhende Ladung abschließen, Startkurve einmalig, Teilen (nur mit Einwilligung). */
    public static void taktImDaemon(long jetztMs) {
        try {
            synchronized (LOCK) {
                if (sitzungStart > 0 && letzterPunktMs > 0 && jetztMs - letzterPunktMs > RUHE_MS) abschliessenLocked();
            }
            JSONObject sp;
            synchronized (LOCK) {
                sp = lesen();
                if (!sp.optBoolean("importiert", false)) {
                    int n = startkurve(sp, at.greenchili.surfmate.monitor.SocHistoryDatabase.getInstance());
                    schreiben(sp);
                    logger.info("Ladekurve: Startkurve aus " + n + " eigenen DC-Ladung(en)");
                }
            }
            LadekurveTeilen.takt(jetztMs, at.greenchili.surfmate.BuildConfig.VERSION_NAME, sp,
                    at.greenchili.surfmate.planer.FahrzeugWahl.wirksamJetzt(),
                    new at.greenchili.surfmate.analytics.Mitzaehlen.Speicher() {
                        public JSONObject lese() {
                            return at.greenchili.surfmate.config.UnifiedConfigManager.forceReload()
                                    .optJSONObject(LadekurveTeilen.ABSCHNITT);
                        }
                        public void schreibe(JSONObject teil) {
                            at.greenchili.surfmate.config.UnifiedConfigManager.updateSection(LadekurveTeilen.ABSCHNITT, teil);
                        }
                    }, (url, json) -> {
                        // bewusst ohne Planer-Schlüssel (anonym, nicht dem Konto zugeordnet)
                        okhttp3.OkHttpClient c = new okhttp3.OkHttpClient.Builder()
                                .callTimeout(15, TimeUnit.SECONDS)
                                .proxySelector(at.greenchili.surfmate.mqtt.ProxyHelper.chainProxySelector())
                                .build();
                        okhttp3.Request req = new okhttp3.Request.Builder().url(url)
                                .post(okhttp3.RequestBody.create(json, okhttp3.MediaType.parse("application/json; charset=utf-8")))
                                .build();
                        try (okhttp3.Response r = c.newCall(req).execute()) {
                            return r.code();
                        }
                    });
        } catch (Throwable t) {
            logger.warn("Ladekurve-Takt: " + t.getMessage());
        }
    }

    /** Für GET /api/ladekurve (nur lesen, nur im Auto): lokale Kurve, Anzahl, Teilen-Stand. */
    public static JSONObject status() {
        JSONObject o = new JSONObject();
        try {
            JSONObject sp;
            synchronized (LOCK) { sp = lesen(); }
            o.put("ladungen", Ladekurve.ladungen(sp)).put("startkurveGeladen", sp.optBoolean("importiert", false))
                    .put("kurve", Ladekurve.kurve(sp, 1)).put("band", Ladekurve.BAND);
            JSONObject z = at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig().optJSONObject(LadekurveTeilen.ABSCHNITT);
            o.put("teilen", z != null && z.optBoolean("teilen", false))
                    .put("zuletztGeteilt", z == null || z.optString("letzterTag", "").isEmpty() ? JSONObject.NULL : z.optString("letzterTag"));
            synchronized (LOCK) { o.put("laeuft", sitzungStart > 0).put("punkteJetzt", punkte.size()); }
        } catch (Exception ignored) {
        }
        return o;
    }
}
