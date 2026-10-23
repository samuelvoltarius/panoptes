package at.greenchili.surfmate.camping;

import at.greenchili.surfmate.byd.routing.CloudPowerGate;
import at.greenchili.surfmate.byd.routing.VehicleCommandRouter;
import at.greenchili.surfmate.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.File;
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
 *       gesperrt (Regel B, BYD-Massen-forceStop beim ACC-off, WEG2_KILL_BEFUND).</li>
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

    private final Function<VehicleCommandRouter.VehicleCommand, VehicleCommandRouter.CommandResult> router;
    private final Function<String, CloudPowerGate.Decision> gate;
    private final Supplier<Boolean> weg2Lebt;
    private final int cloudTemperatur;

    private int weckungen;
    private long letzteCloudMs;
    private Boolean weg2VorCloud;
    private boolean cloudGesperrt;
    private String letztesErgebnis;

    RouterHeizweg(int cloudTemperatur) {
        this(v -> VehicleCommandRouter.getInstance().execute(v), CloudPowerGate::evaluateLive,
                RouterHeizweg::weg2NetzDa, cloudTemperatur);
    }

    RouterHeizweg(Function<VehicleCommandRouter.VehicleCommand, VehicleCommandRouter.CommandResult> router,
                  Function<String, CloudPowerGate.Decision> gate, Supplier<Boolean> weg2Lebt, int cloudTemperatur) {
        this.router = router;
        this.gate = gate;
        this.weg2Lebt = weg2Lebt;
        this.cloudTemperatur = Math.max(15, Math.min(31, cloudTemperatur));
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
            if (!cloudErlaubt) {
                e = Ergebnis.uebersprungen("cloud_aus");
            } else if (cloudGesperrt) {
                e = Ergebnis.uebersprungen("cloud_gesperrt_weg2");
            } else if (weckungen >= MAX_WECKUNGEN) {
                e = Ergebnis.uebersprungen("weck_grenze");
            } else if (letzteCloudMs > 0 && l.jetztMs - letzteCloudMs < MIN_ABSTAND_MS) {
                e = Ergebnis.uebersprungen("weck_abstand");
            } else {
                CloudPowerGate.Decision g = gate.apply("climate-remote-on");
                if (g == null || !g.allowed) {
                    e = Ergebnis.uebersprungen("gate_" + (g == null ? "null" : g.reasonKey));
                } else {
                    weg2VorCloud = weg2Lebt.get();
                    VehicleCommandRouter.CommandResult r = router.apply(
                            new VehicleCommandRouter.ClimateRemoteOnCommand(cloudTemperatur, cloudDauer(dauerMin)));
                    weckungen++;
                    letzteCloudMs = l.jetztMs;
                    e = r != null && r.outcome == VehicleCommandRouter.Outcome.SUCCESS
                            ? Ergebnis.cloud()
                            : Ergebnis.fehler("cloud_" + (r == null ? "null" : r.outcome.name().toLowerCase()));
                }
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
        if (weg2VorCloud == null || letzteCloudMs <= 0 || jetztMs - letzteCloudMs < WEG2_PRUEFEN_NACH_MS) return null;
        Boolean vorher = weg2VorCloud;
        weg2VorCloud = null;
        if (Boolean.TRUE.equals(vorher) && Boolean.FALSE.equals(weg2Lebt.get())) {
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
        // cloudGesperrt bleibt bis zum Daemon-Neustart: erst die Koordination entscheidet
    }

    @Override
    public synchronized JSONObject stand() {
        JSONObject o = new JSONObject();
        try {
            o.put("weckungen", weckungen).put("maxWeckungen", MAX_WECKUNGEN).put("cloudGesperrt", cloudGesperrt)
                    .put("letzteCloudMs", letzteCloudMs).put("cloudTemperatur", cloudTemperatur)
                    .put("letztes", letztesErgebnis == null ? JSONObject.NULL : letztesErgebnis);
        } catch (Exception ignored) { }
        return o;
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
