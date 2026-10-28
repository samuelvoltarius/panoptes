package at.greenchili.surfmate.camping;

import at.greenchili.surfmate.byd.BydVehicleData;
import at.greenchili.surfmate.byd.routing.CloudPowerGate;
import at.greenchili.surfmate.byd.routing.VehicleCommandRouter;
import at.greenchili.surfmate.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.File;
import java.util.LinkedList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Heizweg über den regulären {@link VehicleCommandRouter} (0.1.16 Stufe 2b).
 *
 * <ul>
 *   <li><b>H1</b> (Auto „an“, power_level 2, höchstens 60 s alt): {@code ClimateOnCommand(stufe)} – lokal,
 *       mit Wirkungsprüfung (start → Sollwert → Echo), bei wachem Auto nie Cloud; danach Gebläse.
 *       Aus: {@code ClimateOffCommand}.</li>
 *   <li><b>H2</b> (Auto „aus“, nur wenn erlaubt): {@code ClimateRemoteOnCommand} über das CloudPowerGate,
 *       Dauer auf 10/15/20/25/30 min aufgerundet. Aus: nichts – die Sitzung läuft ab, <b>nie CLOSEAIR</b>
 *       (Vorfall 13.10.2026). Höchstens {@link #MAX_WECKUNGEN} Sitzungen je Camping, Abstand mindestens
 *       {@link #MIN_ABSTAND_MS}. Stirbt Weg 2 (Tailscale-App, VPN-Netz {@code tun*}) nach einer Sitzung, wird H2
 *       gesperrt (Regel B, BYD-Massen-forceStop beim ACC-off, WEG2_KILL_BEFUND).
 *       <br>0.1.18 (27.10.2026): Erfolg nur mit Cloud-Bestätigung (BYD-Endergebnis über
 *       {@code remoteControlResult}, siehe BydCloudClient). Nach {@link #MAX_CLOUD_FEHLVERSUCHE} Fehlschlägen in
 *       Folge pausiert H2 bis zum nächsten Camping (keine Endlos-Wiederholung, Meldung über Camping). Jede
 *       Cloud-Entscheidung landet im Protokoll ({@code camping_cloud.jsonl}).
 *       <br>0.1.19 (SPEC_0119_CLOUD_MESSUNG): {@link #MESSEN_NACH_MS} nach einer bestätigten Sitzung einmal Sollwert,
 *       Gebläse, Klima und power_level aus den vorhandenen Fahrzeugdaten ins Protokoll – nur lesen, nie wecken;
 *       sind die Daten nicht frisch seit dem Sitzungsstart, steht dort „nicht lesbar“.</li>
 *   <li>Sonst (Zustand unbekannt oder alt, ACC, Laden mit anderem power_level): übersprungen, nichts gesendet.</li>
 * </ul>
 */
final class RouterHeizweg implements Heizweg {

    private static final DaemonLogger logger = DaemonLogger.getInstance("CampingHeizweg");
    static final int MAX_WECKUNGEN = 12;
    static final long MIN_ABSTAND_MS = 45 * 60_000L;
    static final long AN_MAX_ALTER_MS = 60_000L;
    /** Prüfen, ob Weg 2 noch lebt: frühestens so lange nach einer Cloud-Sitzung. */
    static final long WEG2_PRUEFEN_NACH_MS = 3 * 60_000L;
    /** So viele Cloud-Fehlschläge in Folge, dann Pause bis zum nächsten Camping. */
    static final int MAX_CLOUD_FEHLVERSUCHE = 2;
    /** Die letzten Protokolleinträge im Status. */
    static final int STAND_PROTOKOLL = 5;
    /** Messung nach einer bestätigten Cloud-Sitzung: so lange danach (Klima und Sollwert eingeschwungen). */
    static final long MESSEN_NACH_MS = 2 * 60_000L;

    private final Function<VehicleCommandRouter.VehicleCommand, VehicleCommandRouter.CommandResult> router;
    private final Function<String, CloudPowerGate.Decision> gate;
    private final Supplier<Boolean> weg2Lebt;
    private final int cloudTemperatur;
    private final Consumer<JSONObject> protokoll;
    private final Supplier<BydVehicleData> daten;
    private final LinkedList<JSONObject> letzteEintraege = new LinkedList<>();

    private int weckungen;
    private long letzteCloudMs;
    private Boolean weg2VorCloud;
    private boolean cloudGesperrt;
    private int cloudFehlVersuche;
    private boolean cloudPausiert;
    /** Start der bestätigten Sitzung, deren Messung noch aussteht (0 = keine). */
    private long messungFuerMs;
    private int messungDauerMin;
    private String letztesErgebnis;

    RouterHeizweg(int cloudTemperatur, Consumer<JSONObject> protokoll, Supplier<BydVehicleData> daten) {
        this(v -> VehicleCommandRouter.getInstance().execute(v), CloudPowerGate::evaluateLive,
                RouterHeizweg::weg2NetzDa, cloudTemperatur, protokoll, daten);
    }

    RouterHeizweg(Function<VehicleCommandRouter.VehicleCommand, VehicleCommandRouter.CommandResult> router,
                  Function<String, CloudPowerGate.Decision> gate, Supplier<Boolean> weg2Lebt, int cloudTemperatur) {
        this(router, gate, weg2Lebt, cloudTemperatur, null, null);
    }

    RouterHeizweg(Function<VehicleCommandRouter.VehicleCommand, VehicleCommandRouter.CommandResult> router,
                  Function<String, CloudPowerGate.Decision> gate, Supplier<Boolean> weg2Lebt, int cloudTemperatur,
                  Consumer<JSONObject> protokoll, Supplier<BydVehicleData> daten) {
        this.router = router;
        this.gate = gate;
        this.weg2Lebt = weg2Lebt;
        this.cloudTemperatur = Math.max(17, Math.min(33, cloudTemperatur));   // 0.1.26: SDK-Skala (Stufe 1–17)
        this.protokoll = protokoll;
        this.daten = daten;
    }

    static boolean autoAn(CampingRegler.Lage l) {
        return l.powerLevel == 2 && l.powerLevelAlterMs >= 0 && l.powerLevelAlterMs <= AN_MAX_ALTER_MS;
    }

    static boolean autoAus(CampingRegler.Lage l) {
        return l.powerLevel == 0 && l.powerLevelAlterMs >= 0 && l.powerLevelAlterMs <= CloudPowerGate.MAX_AGE_MS;
    }

    /** Cloud-Dauer: kleinste erlaubte Stufe ≥ geplante Dauer, höchstens 30 min. */
    static int cloudDauer(int dauerMin) {
        for (int d : new int[]{10, 15, 20, 25, 30}) if (dauerMin <= d) return d;
        return 30;
    }

    @Override
    public synchronized Ergebnis ein(int stufe, int geblaese, int dauerMin, CampingRegler.Lage l, boolean cloudErlaubt) {
        Ergebnis e;
        if (autoAn(l)) {
            VehicleCommandRouter.CommandResult r = router.apply(new VehicleCommandRouter.ClimateOnCommand(stufe, 20));
            if (r == null || r.outcome != VehicleCommandRouter.Outcome.SUCCESS) {
                e = Ergebnis.fehler("lokal_" + (r == null ? "null" : r.outcome.name().toLowerCase()));
            } else if (r.path != VehicleCommandRouter.Path.SDK) {
                // Bei Auto an darf nie die Cloud gelaufen sein (localOnlyWhenAwake) – sonst Fehler melden
                e = Ergebnis.fehler("pfad_" + r.path.name().toLowerCase());
            } else {
                VehicleCommandRouter.CommandResult f = router.apply(new VehicleCommandRouter.ClimateSetFanCommand(geblaese));
                if (f == null || f.outcome != VehicleCommandRouter.Outcome.SUCCESS) {
                    logger.warn("Gebläse " + geblaese + " nicht gesetzt: " + (f == null ? "null" : f.outcome));
                }
                e = Ergebnis.lokal();
            }
        } else if (autoAus(l)) {
            JSONObject eintrag = null;
            if (!cloudErlaubt) {
                e = Ergebnis.uebersprungen("cloud_aus");
            } else if (cloudPausiert) {
                e = Ergebnis.uebersprungen("cloud_pausiert");
            } else if (cloudGesperrt) {
                e = Ergebnis.uebersprungen("cloud_gesperrt_weg2");
            } else if (weckungen >= MAX_WECKUNGEN) {
                e = Ergebnis.uebersprungen("weck_grenze");
            } else if (letzteCloudMs > 0 && l.jetztMs - letzteCloudMs < MIN_ABSTAND_MS) {
                e = Ergebnis.uebersprungen("weck_abstand");
            } else {
                CloudPowerGate.Decision g = gate.apply("climate-remote-on");
                eintrag = eintrag("ein", l);
                put(eintrag, "gate", g == null ? "null" : (g.allowed ? "erlaubt" : g.reasonKey));
                if (g == null || !g.allowed) {
                    e = Ergebnis.uebersprungen("gate_" + (g == null ? "null" : g.reasonKey));
                } else {
                    weg2VorCloud = weg2Lebt.get();
                    int dauer = cloudDauer(dauerMin);
                    long t0 = System.currentTimeMillis();
                    VehicleCommandRouter.CommandResult r = router.apply(
                            new VehicleCommandRouter.ClimateRemoteOnCommand(cloudTemperatur, dauer));
                    weckungen++;
                    letzteCloudMs = l.jetztMs;
                    // SUCCESS nur mit BYD-Endergebnis (Cloud-Bestätigung); alles andere ist ein Fehlschlag
                    boolean bestaetigt = r != null && r.outcome == VehicleCommandRouter.Outcome.SUCCESS
                            && r.path == VehicleCommandRouter.Path.CLOUD;
                    if (bestaetigt) {
                        cloudFehlVersuche = 0;
                        messungFuerMs = l.jetztMs;
                        messungDauerMin = dauer;
                        e = Ergebnis.cloud();
                    } else {
                        cloudFehlVersuche++;
                        if (cloudFehlVersuche >= MAX_CLOUD_FEHLVERSUCHE) {
                            cloudPausiert = true;
                            logger.warn("Cloud-Heizen pausiert: " + cloudFehlVersuche + " Fehlschläge ohne Bestätigung");
                        }
                        e = Ergebnis.fehler("cloud_" + (r == null ? "null" : r.outcome.name().toLowerCase()));
                    }
                    put(eintrag, "temperatur", cloudTemperatur);
                    put(eintrag, "dauerMin", dauer);
                    put(eintrag, "weckung", weckungen);
                    put(eintrag, "outcome", r == null ? "null" : r.outcome.name().toLowerCase());
                    put(eintrag, "pfad", r == null || r.path == null ? "null" : r.path.name().toLowerCase());
                    put(eintrag, "bestaetigt", bestaetigt);
                    put(eintrag, "ms", System.currentTimeMillis() - t0);
                    put(eintrag, "fehlVersuche", cloudFehlVersuche);
                    put(eintrag, "pausiert", cloudPausiert);
                    put(eintrag, "weg2Vorher", weg2VorCloud == null ? JSONObject.NULL : weg2VorCloud);
                }
            }
            if (eintrag == null && cloudErlaubt) eintrag = eintrag("ein", l);
            if (eintrag != null) {
                put(eintrag, "ergebnis", e.toString());
                protokollieren(eintrag);
            }
        } else {
            e = Ergebnis.uebersprungen("zustand_unklar");
        }
        letztesErgebnis = "ein " + e;
        logger.info("Heizen ein (Stufe " + stufe + ", Gebläse " + geblaese + ", " + dauerMin + " min): " + e);
        return e;
    }

    @Override
    public synchronized Ergebnis aus(CampingRegler.Lage l) {
        Ergebnis e;
        if (autoAn(l)) {
            VehicleCommandRouter.CommandResult r = router.apply(new VehicleCommandRouter.ClimateOffCommand());
            e = r != null && r.outcome == VehicleCommandRouter.Outcome.SUCCESS && r.path == VehicleCommandRouter.Path.SDK
                    ? Ergebnis.lokal()
                    : Ergebnis.fehler("lokal_aus_" + (r == null ? "null" : r.outcome.name().toLowerCase()));
        } else {
            // Auto aus: lokale AC läuft nicht; eine Cloud-Sitzung läuft von selbst ab. Nie CLOSEAIR.
            e = Ergebnis.uebersprungen("auto_nicht_an");
        }
        letztesErgebnis = "aus " + e;
        logger.info("Heizen aus: " + e);
        return e;
    }

    @Override
    public synchronized String nachTakt(long jetztMs) {
        messen(jetztMs);
        if (weg2VorCloud == null || letzteCloudMs <= 0 || jetztMs - letzteCloudMs < WEG2_PRUEFEN_NACH_MS) return null;
        Boolean vorher = weg2VorCloud;
        weg2VorCloud = null;
        Boolean nachher = weg2Lebt.get();
        boolean sperren = Boolean.TRUE.equals(vorher) && Boolean.FALSE.equals(nachher);
        JSONObject o = new JSONObject();
        put(o, "zeit", jetztMs);
        put(o, "art", "weg2");
        put(o, "vorher", vorher);
        put(o, "nachher", nachher == null ? JSONObject.NULL : nachher);
        put(o, "gesperrt", sperren);
        protokollieren(o);
        if (sperren) {
            cloudGesperrt = true;
            logger.warn("Weg 2 (Tailscale-App) nach Cloud-Sitzung weg – H2 gesperrt (Regel B)");
            return "cloud_gesperrt_weg2";
        }
        return null;
    }

    @Override
    public synchronized void zuruecksetzen() {
        weckungen = 0;
        letzteCloudMs = 0;
        weg2VorCloud = null;
        letztesErgebnis = null;
        cloudFehlVersuche = 0;
        cloudPausiert = false;
        messungFuerMs = 0;
        // cloudGesperrt bleibt bis zum Daemon-Neustart: erst die Koordination entscheidet
    }

    @Override
    public synchronized JSONObject stand() {
        JSONObject o = new JSONObject();
        try {
            o.put("weckungen", weckungen).put("maxWeckungen", MAX_WECKUNGEN).put("cloudGesperrt", cloudGesperrt)
                    .put("letzteCloudMs", letzteCloudMs).put("cloudTemperatur", cloudTemperatur)
                    .put("letztes", letztesErgebnis == null ? JSONObject.NULL : letztesErgebnis)
                    .put("cloudFehlVersuche", cloudFehlVersuche).put("cloudPausiert", cloudPausiert)
                    .put("protokoll", new org.json.JSONArray(letzteEintraege));
        } catch (Exception ignored) { }
        return o;
    }

    /**
     * Einmalige Messung nach einer bestätigten Cloud-Sitzung (SPEC_0119_CLOUD_MESSUNG). Liest nur die vorhandenen
     * Fahrzeugdaten – kein Router, keine Cloud, kein Wecken.
     */
    private void messen(long jetztMs) {
        if (messungFuerMs <= 0 || jetztMs - messungFuerMs < MESSEN_NACH_MS) return;
        long start = messungFuerMs;
        messungFuerMs = 0;
        JSONObject o = new JSONObject();
        put(o, "zeit", jetztMs);
        put(o, "art", "messung");
        put(o, "seitCloudMs", jetztMs - start);
        put(o, "cloudTemperatur", cloudTemperatur);
        if (jetztMs - start > messungDauerMin * 60_000L) {
            // Takt kam zu spät (z. B. Daemon-Neustart): die Sitzung ist schon abgelaufen
            put(o, "lesbar", JSONObject.NULL);
            put(o, "verpasst", true);
            protokollieren(o);
            return;
        }
        BydVehicleData v = null;
        try {
            v = daten == null ? null : daten.get();
        } catch (Throwable ignored) { }
        boolean lesbar = v != null && (v.klimaSollwertReadAt >= start || v.powerLevelAtMs >= start);
        put(o, "lesbar", lesbar);
        if (lesbar) {
            put(o, "sollwertC", Double.isNaN(v.klimaSollwertC) ? JSONObject.NULL : (Object) v.klimaSollwertC);
            put(o, "sollwertAlterMs", v.klimaSollwertReadAt > 0 ? (Object) (jetztMs - v.klimaSollwertReadAt) : JSONObject.NULL);
            put(o, "setpointFahrer", zahl(v.acSetpointDriver));
            put(o, "geblaese", zahl(v.acFanLevel));
            put(o, "klimaAn", zahl(v.acStartState));
            put(o, "powerLevel", zahl(v.powerLevel));
            put(o, "powerLevelAlterMs", v.powerLevelAtMs > 0 ? (Object) (jetztMs - v.powerLevelAtMs) : JSONObject.NULL);
        }
        protokollieren(o);
    }

    private static Object zahl(int w) {
        return w == BydVehicleData.UNAVAILABLE ? JSONObject.NULL : (Object) w;
    }

    /** Cloud wegen wiederholter Fehlschläge pausiert (bis zum nächsten Camping). */
    synchronized boolean cloudPausiert() {
        return cloudPausiert;
    }

    private static JSONObject eintrag(String art, CampingRegler.Lage l) {
        JSONObject o = new JSONObject();
        put(o, "zeit", l.jetztMs);
        put(o, "art", art);
        put(o, "powerLevel", l.powerLevel);
        put(o, "powerLevelAlterMs", l.powerLevelAlterMs);
        return o;
    }

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (Exception ignored) { }
    }

    /** Protokoll jeder Cloud-Entscheidung (der erste echte Einsatz wird ausgewertet). Wirft nie. */
    private void protokollieren(JSONObject o) {
        letzteEintraege.addLast(o);
        while (letzteEintraege.size() > STAND_PROTOKOLL) letzteEintraege.removeFirst();
        logger.info("Cloud-Protokoll: " + o);
        if (protokoll == null) return;
        try {
            protokoll.accept(o);
        } catch (Throwable ignored) { }
    }

    /** Weg 2 lebt = ein VPN-Netz tun* ist da (wie Weg2Waechter). Unlesbar → null (keine Aussage). */
    static Boolean weg2NetzDa() {
        try {
            String[] n = new File("/sys/class/net").list();
            if (n == null) return null;
            for (String s : n) if (s.startsWith("tun")) return Boolean.TRUE;
            return Boolean.FALSE;
        } catch (Throwable t) {
            return null;
        }
    }
}
