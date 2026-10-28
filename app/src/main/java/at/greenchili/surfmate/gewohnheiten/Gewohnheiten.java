package at.greenchili.surfmate.gewohnheiten;

import at.greenchili.surfmate.byd.BydVehicleData;
import at.greenchili.surfmate.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 0.1.22 Gewohnheiten (GEWOHNHEITEN_KONZEPT.md, Etappen E1, E1b, E2) – Laufzeit im Daemon.
 *
 * <ul>
 *   <li><b>Mitschreiben</b> ({@link #melde}): nur mit Einwilligung ({@code gewohnheiten.an}, Standard aus – neue
 *   Einrichtungen fragen im Schritt {@code extra_gewohnheiten}, Bestand erst nach der Frage; Regel 2), nur lokal im
 *   Auto, nie an einen Server (Auto ist die Quelle). Herkunft je Thread ({@link Herkunft}).</li>
 *   <li><b>Am Auto</b> ({@link #beobachte}): Schnappschuss-Vergleich ({@link AutoBeobachter}); was Panoptes selbst
 *   befohlen hat, steht vorher in den {@link Erwartungen} ({@link #erwarte}) und zählt nicht doppelt.</li>
 *   <li><b>Erkennen</b> ({@link #vorschlaege}): {@link GewohnheitsErkenner}; die Karte dazu kommt mit 0.1.23 (E3).</li>
 * </ul>
 * Alles hier ist still: kein Fehler im Mitschreiben darf eine Bedienung, einen Befehl oder den Telemetrie-Takt stören.
 */
public final class Gewohnheiten {

    private static final DaemonLogger logger = DaemonLogger.getInstance("Gewohnheiten");
    public static final String ABSCHNITT = "gewohnheiten";
    static final long KUERZEN_MS = 3_600_000L;

    /** Laufzeit-Anbindung (in Tests ersetzbar). */
    public interface Umgebung {
        long jetzt();

        ZoneId zone();

        /** Abschnitt {@code gewohnheiten} der Config oder null. */
        JSONObject einstellung();

        /** Kontext-Rohwerte zum Zeitpunkt der Handlung. */
        Kontext.Werte kontext();

        /** Zündung an? null = unbekannt. */
        Boolean zuendung();
    }

    private static volatile Gewohnheiten instanz;

    final Umgebung u;
    final GewohnheitsLog log;
    final File gesperrtDatei;
    final Erwartungen erwartungen = new Erwartungen();
    final AutoBeobachter beobachter = new AutoBeobachter(erwartungen);
    /** Schreibt der Reihe nach; im Test direkt. */
    java.util.concurrent.Executor schreiber;
    private volatile long gekuerztMs;
    private volatile long vergessenStand = -1;

    Gewohnheiten(File ordner, Umgebung u, java.util.concurrent.Executor schreiber) {
        this.u = u;
        this.log = new GewohnheitsLog(new File(ordner, "log.jsonl"));
        this.gesperrtDatei = new File(ordner, "abgelehnt.json");
        this.schreiber = schreiber;
    }

    /** Im Daemon (lazy): Ordner {@code gewohnheiten/} im Datenordner, ein eigener Schreib-Thread. */
    public static Gewohnheiten get() {
        Gewohnheiten g = instanz;
        if (g == null) {
            synchronized (Gewohnheiten.class) {
                g = instanz;
                if (g == null) {
                    ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "Gewohnheiten");
                        t.setDaemon(true);
                        return t;
                    });
                    g = new Gewohnheiten(new File(at.greenchili.surfmate.util.DaemonStorage.path("gewohnheiten")),
                            DAEMON, ex);
                    instanz = g;
                }
            }
        }
        return g;
    }

    /** Nur für Tests. */
    static void setzeInstanz(Gewohnheiten g) {
        instanz = g;
    }

    // ---------------------------------------------------------------- Einstieg (statisch, nie werfend)

    /** Bedienhandlung mit der Herkunft des laufenden Threads (nicht gesetzt = Automation, zählt nicht). */
    public static void melde(String aktion, JSONObject wert) {
        melde(aktion, wert, Herkunft.aktuell());
    }

    public static void melde(String aktion, JSONObject wert, String quelle) {
        try {
            get().notiere(aktion, wert, quelle, null);
        } catch (Throwable ignored) {
        }
    }

    /** Vor einem Panoptes-Befehl: diese Zustandsänderung ist ein Echo, keine Bedienung am Auto. */
    public static void erwarte(String signal, int ziel) {
        try {
            Gewohnheiten g = get();
            Boolean z = g.u.zuendung();
            g.erwartungen.erwarte(signal, ziel, g.u.jetzt() + (Boolean.TRUE.equals(z) ? Erwartungen.LOKAL_MS : Erwartungen.CLOUD_MS));
        } catch (Throwable ignored) {
        }
    }

    /** Jeder Fahrzeug-Schnappschuss (BydEvent.bydEvent, vor dessen Automations-Sperre). */
    public static void beobachte(BydVehicleData d) {
        if (d == null) return;
        try {
            Gewohnheiten g = get();
            if (!g.aktiv()) return;
            g.beobachteStand(stand(d, laedt()));
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- Kern

    /** Einwilligung gegeben und eingeschaltet? */
    public boolean aktiv() {
        JSONObject s = u.einstellung();
        return s != null && s.optBoolean("an", false);
    }

    int aufbewahrungTage() {
        JSONObject s = u.einstellung();
        int t = s == null ? GewohnheitsLog.AUFBEWAHRUNG_TAGE : s.optInt("aufbewahrungTage", GewohnheitsLog.AUFBEWAHRUNG_TAGE);
        return Math.max(GewohnheitsLog.TAGE_MIN, Math.min(GewohnheitsLog.TAGE_MAX, t));
    }

    void notiere(String aktion, JSONObject wert, String quelle, String grenzfall) {
        if (!Aktionen.MITSCHREIBEN.contains(aktion) || !aktiv()) return;
        final long t = u.jetzt();
        final Kontext.Werte w = u.kontext();
        schreiber.execute(() -> {
            try {
                pflege(t);
                JSONObject e = new JSONObject();
                e.put("t", t);
                e.put("aktion", aktion);
                e.put("wert", wert == null ? new JSONObject() : new JSONObject(wert.toString()));
                e.put("quelle", quelle == null ? Herkunft.AUTOMATION : quelle);
                if (grenzfall != null) e.put("grenzfall", grenzfall);
                e.put("kontext", Kontext.bilde(t, u.zone(), w));
                log.schreibe(e);
            } catch (Throwable x) {
                logger.debug("notiere: " + x.getMessage());
            }
        });
    }

    void beobachteStand(AutoBeobachter.Stand s) throws Exception {
        for (AutoBeobachter.Ereignis e : beobachter.beobachte(s, u.jetzt())) notiere(e.aktion, e.wert, e.quelle, e.grenzfall);
    }

    /** Stündlich kürzen; „Alles vergessen“ aus den Einstellungen ({@code vergessenAm}) ausführen. */
    void pflege(long jetzt) {
        JSONObject s = u.einstellung();
        long vergessen = s == null ? 0 : s.optLong("vergessenAm", 0);
        if (vergessenStand < 0) vergessenStand = vergessen;   // beim Start: nur merken
        else if (vergessen > vergessenStand) {
            vergessenStand = vergessen;
            vergiss();
        }
        if (jetzt - gekuerztMs >= KUERZEN_MS) {
            gekuerztMs = jetzt;
            int weg = log.kuerze(jetzt, aufbewahrungTage(), GewohnheitsLog.MAX_ZEILEN);
            if (weg > 0) logger.info("Gewohnheiten: " + weg + " alte Einträge verworfen");
        }
    }

    /** „Alles vergessen“: Log und abgelehnte Gewohnheiten weg; angelegte Automationen bleiben (dort löschbar). */
    public synchronized void vergiss() {
        log.loesche();
        gesperrtDatei.delete();
    }

    /** Abgelehnte Gewohnheiten („Nein“ = nie wieder, E3); bis dahin nur gelesen. */
    public synchronized JSONArray gesperrt() {
        try {
            if (!gesperrtDatei.isFile()) return new JSONArray();
            return new JSONArray(new String(java.nio.file.Files.readAllBytes(gesperrtDatei.toPath()), "UTF-8"));
        } catch (Throwable t) {
            return new JSONArray();
        }
    }

    public List<JSONObject> vorschlaege() throws Exception {
        return GewohnheitsErkenner.erkenne(log.lies(), u.jetzt(), u.zone(), GewohnheitsErkenner.menge(gesperrt()), new ArrayList<>());
    }

    /** {@code GET /api/gewohnheiten}: Schalter, Anzahl, Herkünfte, erkannte Gewohnheiten (ohne das Log selbst). */
    public JSONObject status() throws Exception {
        pflege(u.jetzt());   // ein gerade gewähltes „Alles vergessen“ gilt sofort, nicht erst bei der nächsten Handlung
        List<JSONObject> l = log.lies();
        JSONObject quellen = new JSONObject();
        long seit = 0;
        for (JSONObject e : l) {
            String q = e.optString("quelle", "?");
            quellen.put(q, quellen.optInt(q, 0) + 1);
            if (seit == 0) seit = e.optLong("t", 0);
        }
        JSONObject r = new JSONObject();
        r.put("an", aktiv());
        r.put("aufbewahrungTage", aufbewahrungTage());
        r.put("eintraege", l.size());
        r.put("seit", seit);
        r.put("quellen", quellen);
        r.put("vorschlaege", new JSONArray(vorschlaege()));
        return r;
    }

    // ---------------------------------------------------------------- Fahrzeugdaten → Stand

    static Boolean laedt() {
        try {
            return at.greenchili.surfmate.monitor.ChargingDetector.getInstance().isCharging();
        } catch (Throwable t) {
            return null;
        }
    }

    static AutoBeobachter.Stand stand(BydVehicleData d, Boolean laedt) {
        AutoBeobachter.Stand s = new AutoBeobachter.Stand();
        s.setze(AutoBeobachter.KLIMA, d.acStartState == 0 || d.acStartState == 1 ? d.acStartState : AutoBeobachter.UNBEKANNT);
        s.setze(AutoBeobachter.TEMP_FAHRER, gueltig(d.acSetpointDriver));
        s.setze(AutoBeobachter.TEMP_BEIFAHRER, gueltig(d.acSetpointPassenger));
        s.setze(AutoBeobachter.HEIZ_FAHRER, stufe(d.seatHeat, 0));
        s.setze(AutoBeobachter.HEIZ_BEIFAHRER, stufe(d.seatHeat, 1));
        s.setze(AutoBeobachter.LUEFT_FAHRER, stufe(d.seatCool, 0));
        s.setze(AutoBeobachter.LUEFT_BEIFAHRER, stufe(d.seatCool, 1));
        // Lenkradheizung: 2 = an, 1 = aus (BydVehicleData)
        s.setze(AutoBeobachter.LENKRAD, d.steeringWheelHeat == 2 ? 1 : d.steeringWheelHeat == 1 ? 0 : AutoBeobachter.UNBEKANNT);
        s.zuendung = zuendungAus(d);
        s.laedt = laedt;
        s.angesteckt = d.chargingGunState == BydVehicleData.UNAVAILABLE ? null : d.chargingGunState >= 2 && d.chargingGunState <= 5;
        return s;
    }

    static Boolean zuendungAus(BydVehicleData d) {
        try {
            return at.greenchili.surfmate.byd.BydDataCollector.getInstance().isAccOn();
        } catch (Throwable t) {
            return null;
        }
    }

    private static int gueltig(int v) {
        return v == BydVehicleData.UNAVAILABLE || v <= 0 ? AutoBeobachter.UNBEKANNT : v;
    }

    private static int stufe(int[] a, int i) {
        if (a == null || a.length <= i) return AutoBeobachter.UNBEKANNT;
        int v = a[i];
        return v >= 0 && v <= 2 ? v : AutoBeobachter.UNBEKANNT;
    }

    /** Temperatur als ganze Zahl, wenn ganz, sonst auf 0,5 °C gerundet (gleicher Wert = gleicher Text). */
    public static Number temp(double t) {
        double r = Math.round(t * 2) / 2.0;
        return r == Math.rint(r) ? (Number) (int) r : (Number) r;
    }

    // ---------------------------------------------------------------- Daemon-Umgebung

    static final Umgebung DAEMON = new Umgebung() {
        private volatile long stand;
        private volatile JSONObject abschnitt;

        @Override public long jetzt() { return System.currentTimeMillis(); }

        @Override public ZoneId zone() { return ZoneId.systemDefault(); }

        /** Höchstens alle 10 s aus der Config (deren eigener Cache macht es billig). */
        @Override public JSONObject einstellung() {
            long j = System.currentTimeMillis();
            if (j - stand > 10_000L) {
                try {
                    abschnitt = at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig().optJSONObject(ABSCHNITT);
                } catch (Throwable t) {
                    abschnitt = null;
                }
                stand = j;
            }
            return abschnitt;
        }

        @Override public Kontext.Werte kontext() {
            Kontext.Werte w = new Kontext.Werte();
            try {
                at.greenchili.surfmate.mqtt.MqttConnectionManager m = at.greenchili.surfmate.server.SurveillanceIpcServer.mqttManager();
                JSONObject t = m == null ? null : m.getLatestTelemetry();
                if (t != null) {
                    w.lat = t.optDouble("lat", Double.NaN);
                    w.lon = t.optDouble("lon", Double.NaN);
                }
            } catch (Throwable ignored) {
            }
            try {
                w.orte = at.greenchili.surfmate.orte.OrteDaemon.listeOhneAbgleich();
            } catch (Throwable ignored) {
            }
            try {
                BydVehicleData d = at.greenchili.surfmate.byd.BydDataCollector.getInstance().getData();
                if (d != null) {
                    if (!Double.isNaN(d.outsideTempC) && d.outsideTempC > -60 && d.outsideTempC < 70) w.aussen = (int) Math.round(d.outsideTempC);
                    if (!Double.isNaN(d.socPercent) && d.socPercent >= 0 && d.socPercent <= 100) w.soc = (int) Math.round(d.socPercent);
                    if (d.chargingGunState != BydVehicleData.UNAVAILABLE) w.angesteckt = d.chargingGunState >= 2 && d.chargingGunState <= 5;
                }
            } catch (Throwable ignored) {
            }
            w.zuendung = zuendung();
            return w;
        }

        @Override public Boolean zuendung() {
            try {
                return at.greenchili.surfmate.byd.BydDataCollector.getInstance().isAccOn();
            } catch (Throwable t) {
                return null;
            }
        }
    };
}
