package at.greenchili.surfmate.ladeplan;

import at.greenchili.surfmate.byd.BydVehicleData;
import at.greenchili.surfmate.byd.KlimaStufe;
import at.greenchili.surfmate.byd.routing.CloudPowerGate;
import at.greenchili.surfmate.byd.routing.VehicleCommandRouter;
import at.greenchili.surfmate.logging.DaemonLogger;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 0.1.28 (P6) „Um 7:30 warm und auf 80 %“: zur Abfahrt aus dem Ladeplan ({@code abfahrt}, Ziel-% lädt der Ladeplan)
 * schaltet Panoptes das Klima {@code vorklima.vorlaufMin} Minuten vorher selbst ein. Standard aus.
 *
 * <p>Entschieden (Koordination 29.10.): <b>kein Cloud-Zeitplan</b> (BOOKINGAIR läuft bei BYD ohne Prüfung zur
 * Ausführungszeit, vgl. Vorfall 13.10.). Stattdessen entscheidet Panoptes genau im Moment des Starts:
 * <ul>
 *   <li>Auto nachweislich <b>an</b> (power_level 2, ≤ 60 s alt): nur lokal ({@code executeSdkOnly}), nie Cloud.</li>
 *   <li>Auto nachweislich <b>aus</b> (power_level 0, ≤ 180 s alt): erst jetzt das {@link CloudPowerGate}, dann
 *       {@code ClimateRemoteOnCommand} (OPENAIR). Erfolg nur mit BYD-Bestätigung (Pfad CLOUD).</li>
 *   <li>Sonst (unklar/alt): nichts senden, höchstens {@link #MAX_VERSUCHE} Takte warten, dann aufgeben.</li>
 * </ul>
 * Je Abfahrt höchstens ein Befehl (Zustand in {@code abfahrt_klima.json}, übersteht Neustart), nie CLOSEAIR (die
 * Sitzung läuft von selbst ab), nichts außen am Auto (REGEL A+), nie Aufsperren. Akku unter {@link #MIN_SOC} %:
 * nicht starten. Stufe nach Außentemperatur ({@link KlimaStufe#vorklimaSdk}); angezeigt wird sie im Cloud-Weg bis
 * zur Messung nur als „Heizen“/„Kühlen“ (P2).
 */
public final class AbfahrtKlima {

    private static final DaemonLogger logger = DaemonLogger.getInstance("AbfahrtKlima");

    public static final int[] VORLAUF_ERLAUBT = {10, 15, 20, 25, 30};
    public static final int VORLAUF_STANDARD = 15;
    static final int MIN_SOC = 20;
    static final int MAX_VERSUCHE = 3;
    /** Weniger als so viel vor der Abfahrt lohnt es nicht mehr (z. B. nach Neustart). */
    static final long ZU_SPAET_MS = 3 * 60_000L;
    static final long AN_MAX_ALTER_MS = 60_000L;

    private final Function<VehicleCommandRouter.VehicleCommand, VehicleCommandRouter.CommandResult> lokal;
    private final Function<VehicleCommandRouter.VehicleCommand, VehicleCommandRouter.CommandResult> router;
    private final Function<String, CloudPowerGate.Decision> gate;
    private final Supplier<BydVehicleData> daten;
    private final LongSupplier uhr;
    private final File zustandDatei;

    private long versucheFuer;
    private int versuche;
    private volatile JSONObject stand;

    /** Laufzeit: echter Router, Gate und Fahrzeugdaten. */
    AbfahrtKlima(File zustandDatei) {
        this(c -> VehicleCommandRouter.getInstance().executeSdkOnly(c),
                c -> VehicleCommandRouter.getInstance().execute(c),
                CloudPowerGate::evaluateLive,
                () -> at.greenchili.surfmate.byd.BydDataCollector.getInstance().getData(),
                System::currentTimeMillis, zustandDatei);
    }

    AbfahrtKlima(Function<VehicleCommandRouter.VehicleCommand, VehicleCommandRouter.CommandResult> lokal,
                 Function<VehicleCommandRouter.VehicleCommand, VehicleCommandRouter.CommandResult> router,
                 Function<String, CloudPowerGate.Decision> gate, Supplier<BydVehicleData> daten,
                 LongSupplier uhr, File zustandDatei) {
        this.lokal = lokal;
        this.router = router;
        this.gate = gate;
        this.daten = daten;
        this.uhr = uhr;
        this.zustandDatei = zustandDatei;
    }

    /** Standard-Einstellung {@code vorklima} im Ladeplan. */
    public static JSONObject standard() throws JSONException {
        return new JSONObject().put("an", false).put("vorlaufMin", VORLAUF_STANDARD);
    }

    /** null = gültig, sonst Fehlergrund. */
    static String pruefe(JSONObject v) {
        if (v == null || !(v.opt("an") instanceof Boolean)) return "vorklima_ungueltig";
        Object m = v.opt("vorlaufMin");
        if (!(m instanceof Number)) return "vorklima_ungueltig";
        double d = ((Number) m).doubleValue();
        for (int e : VORLAUF_ERLAUBT) if (d == e) return null;
        return "vorklima_ungueltig";
    }

    /** Letzter Stand für die Seite (ohne neuen Takt). */
    public JSONObject stand() {
        JSONObject s = stand;
        return s == null ? new JSONObject() : s;
    }

    /** Nur anzeigen (nächster Start, letztes Ergebnis), schaltet nie – für {@code GET /api/ladeplan}. */
    public synchronized JSONObject vorschau(JSONObject einstellungen, ZoneId zone, String land) throws JSONException {
        long jetzt = uhr.getAsLong();
        JSONObject v = einstellungen == null ? null : einstellungen.optJSONObject("vorklima");
        JSONObject o = new JSONObject().put("an", v != null && v.optBoolean("an"));
        JSONObject z = lies();
        if (z.has("letztes")) o.put("letztes", z.getJSONObject("letztes"));
        if (v == null || !v.optBoolean("an")) return o;
        long[] ab = Planer.naechsteAbfahrt(einstellungen.optJSONObject("abfahrt"), jetzt, zone, land);
        if (ab == null) return o.put("naechste", JSONObject.NULL);
        return o.put("naechste", new JSONObject().put("abfahrt", ab[0]).put("ziel", ab[1])
                .put("start", ab[0] - v.optInt("vorlaufMin", VORLAUF_STANDARD) * 60_000L));
    }

    /**
     * Ein Takt (60 s). {@code einstellungen} = Ladeplan-Einstellungen, {@code land} für Feiertage. Schaltet höchstens
     * einmal je Abfahrt. Liefert den Stand für die Seite.
     */
    public synchronized JSONObject takt(JSONObject einstellungen, ZoneId zone, String land) throws JSONException {
        long jetzt = uhr.getAsLong();
        JSONObject v = einstellungen == null ? null : einstellungen.optJSONObject("vorklima");
        JSONObject o = new JSONObject().put("an", v != null && v.optBoolean("an"));
        JSONObject z = lies();
        if (z.has("letztes")) o.put("letztes", z.getJSONObject("letztes"));
        if (v == null || !v.optBoolean("an")) {
            stand = o;
            return o;
        }
        int vorlauf = v.optInt("vorlaufMin", VORLAUF_STANDARD);
        long[] ab = Planer.naechsteAbfahrt(einstellungen.optJSONObject("abfahrt"), jetzt, zone, land);
        if (ab == null) {
            stand = o.put("naechste", JSONObject.NULL);
            return o;
        }
        long start = ab[0] - vorlauf * 60_000L;
        o.put("naechste", new JSONObject().put("abfahrt", ab[0]).put("ziel", ab[1]).put("start", start));
        if (jetzt < start || z.optLong("fuer", 0) == ab[0]) {
            stand = o;
            return o;
        }
        if (versucheFuer != ab[0]) {
            versucheFuer = ab[0];
            versuche = 0;
        }
        String ergebnis;
        String art = null;
        if (ab[0] - jetzt < ZU_SPAET_MS) {
            ergebnis = "zu_spaet";
        } else {
            versuche++;
            BydVehicleData d = null;
            try {
                d = daten.get();
            } catch (Throwable ignored) { }
            int sdk = KlimaStufe.vorklimaSdk(d == null ? Double.NaN : d.outsideTempC);
            art = KlimaStufe.art(KlimaStufe.ausSdk(sdk));
            ergebnis = schalten(d, sdk, cloudDauer(vorlauf), jetzt);
            if (ergebnis == null) {
                // Zustand unklar: nächsten Takt nochmal schauen, aber nicht ewig
                if (versuche < MAX_VERSUCHE) {
                    stand = o.put("wartet", true);
                    return o;
                }
                ergebnis = "zustand_unklar";
            }
        }
        JSONObject letztes = new JSONObject().put("abfahrt", ab[0]).put("zeit", jetzt).put("ergebnis", ergebnis)
                .put("art", art == null ? JSONObject.NULL : art);
        z.put("fuer", ab[0]).put("letztes", letztes);
        schreibe(z);
        logger.info("Abfahrt-Klima für " + ab[0] + ": " + ergebnis + (art == null ? "" : " (" + art + ")"));
        stand = o.put("letztes", letztes);
        return o;
    }

    /** Ergebnis-Code, oder null = Zustand unklar (später nochmal). */
    private String schalten(BydVehicleData d, int sdk, int dauer, long jetzt) {
        if (d == null) return null;
        if (!Double.isNaN(d.socPercent) && d.socPercent > 0 && d.socPercent < MIN_SOC) return "akku_niedrig";
        long alter = d.powerLevelAtMs > 0 ? jetzt - d.powerLevelAtMs : -1;
        if (d.powerLevel == 2 && alter >= 0 && alter <= AN_MAX_ALTER_MS) {
            VehicleCommandRouter.CommandResult r = lokal.apply(new VehicleCommandRouter.ClimateOnCommand(sdk, dauer));
            return r != null && r.outcome == VehicleCommandRouter.Outcome.SUCCESS && r.path == VehicleCommandRouter.Path.SDK
                    ? "lokal" : "fehler_lokal";
        }
        if (d.powerLevel == 0 && alter >= 0 && alter <= CloudPowerGate.MAX_AGE_MS) {
            // Genau jetzt prüfen, ob das Auto aus ist – nicht beim Anlegen der Abfahrt
            CloudPowerGate.Decision g = gate.apply("climate-remote-on");
            if (g == null) return null;
            if (!g.allowed) {
                if (CloudPowerGate.REASON_CAR_ON.equals(g.reasonKey)) return "auto_an";
                return null;
            }
            VehicleCommandRouter.CommandResult r = router.apply(new VehicleCommandRouter.ClimateRemoteOnCommand(sdk, dauer));
            return r != null && r.outcome == VehicleCommandRouter.Outcome.SUCCESS && r.path == VehicleCommandRouter.Path.CLOUD
                    ? "cloud" : "fehler_cloud";
        }
        return null;
    }

    /** Cloud-Dauer: kleinste erlaubte Stufe ≥ Vorlauf, höchstens 30 min. */
    static int cloudDauer(int vorlaufMin) {
        for (int d : VORLAUF_ERLAUBT) if (vorlaufMin <= d) return d;
        return 30;
    }

    private JSONObject lies() {
        try {
            if (zustandDatei != null && zustandDatei.isFile()) {
                return new JSONObject(new String(Files.readAllBytes(zustandDatei.toPath()), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            logger.warn("Zustand unlesbar: " + e.getMessage());
        }
        return new JSONObject();
    }

    private void schreibe(JSONObject z) {
        if (zustandDatei == null) return;
        try {
            File tmp = new File(zustandDatei.getPath() + ".tmp");
            Files.write(tmp.toPath(), z.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), zustandDatei.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            logger.warn("Zustand nicht gespeichert: " + e.getMessage());
        }
    }
}
