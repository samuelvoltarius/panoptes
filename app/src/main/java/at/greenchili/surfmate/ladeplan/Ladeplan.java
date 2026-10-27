package at.greenchili.surfmate.ladeplan;

import at.greenchili.surfmate.charging.BoersenPreise;
import at.greenchili.surfmate.charging.TariffManager;
import at.greenchili.surfmate.charging.TariffProfile;
import at.greenchili.surfmate.config.UnifiedConfigManager;
import at.greenchili.surfmate.logging.DaemonLogger;
import at.greenchili.surfmate.util.DaemonStorage;
import at.greenchili.surfmate.wallbox.WallboxDienst;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.time.ZoneId;

/**
 * Laufzeit-Anbindung des Ladeplans (0.1.20): eine Instanz im Daemon, Takt alle 60 s (Muster HaDienst/OrteDaemon),
 * Wallbox über dieselbe {@link WallboxDienst}-Instanz wie die API.
 */
public final class Ladeplan {

    private static final DaemonLogger logger = DaemonLogger.getInstance("Ladeplan");
    private static volatile LadeplanDienst dienst;
    private static boolean gestartet;

    private Ladeplan() {}

    public static LadeplanDienst dienst() {
        LadeplanDienst d = dienst;
        if (d == null) {
            synchronized (Ladeplan.class) {
                d = dienst;
                if (d == null) {
                    d = new LadeplanDienst(new File(DaemonStorage.path("ladeplan.json")),
                            new File(DaemonStorage.path("ladeplan_zustand.json")), UMGEBUNG, wallbox());
                    dienst = d;
                }
            }
        }
        return d;
    }

    /** Letzter Zustand des Ladeplans für HA (ohne Abruf); null, solange noch nichts bekannt ist. */
    public static JSONObject schnappschuss() {
        try {
            LadeplanDienst d = dienst();
            JSONObject l = d.letzter();
            if (l == null && "aus".equals(d.einstellungen().optString("modus"))) l = d.alsAus();   // einmal, dann im Speicher
            return l;
        } catch (Throwable t) {
            return null;
        }
    }

    public static synchronized void start() {
        if (gestartet) return;
        gestartet = true;
        java.util.concurrent.ScheduledExecutorService ex = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Ladeplan");
            t.setDaemon(true);
            return t;
        });
        BoersenPreise.setzeAktiv(boerse());
        ex.scheduleWithFixedDelay(() -> {
            try {
                // 0.1.21 Börse: nur mit Schalter „dynamischer Tarif“ und Börsentarif am Heimort (sonst kein Abruf)
                String land = dienst().boerseLand();
                if (land != null) boerse().aktualisiere(land);
            } catch (Throwable t) {
                logger.warn("Börse: " + t.getMessage());
            }
            try {
                // Ohne Einstellung (Standard „aus“) und ohne „sofort“ kein einziger Wallbox-Abruf
                LadeplanDienst d = dienst();
                if ("aus".equals(d.einstellungen().optString("modus")) && !d.zustand().optBoolean("sofort")) return;
                d.takt();
            } catch (Throwable t) {
                logger.warn("Takt: " + t.getMessage());
            }
        }, 90, 60, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static volatile BoersenPreise boerse;
    private static volatile long schalterStand;
    private static volatile boolean schalter;

    /** Börsenpreise (0.1.21); Schalter „dynamischer Tarif“ aus ladeplan.json, höchstens alle 10 s gelesen. */
    static synchronized BoersenPreise boerse() {
        if (boerse == null) {
            boerse = new BoersenPreise(new File(DaemonStorage.path("ladeplan.json")).getParentFile(), BoersenPreise.HTTP,
                    System::currentTimeMillis, ZoneId.systemDefault(), () -> {
                        long jetzt = System.currentTimeMillis();
                        if (jetzt - schalterStand > 10_000L) {
                            try {
                                schalter = dienst().einstellungen().optBoolean("dynamischerTarif", false);
                            } catch (Throwable t) {
                                schalter = false;
                            }
                            schalterStand = jetzt;
                        }
                        return schalter;
                    });
        }
        return boerse;
    }

    static final LadeplanDienst.Umgebung UMGEBUNG = new LadeplanDienst.Umgebung() {
        @Override public long jetzt() { return System.currentTimeMillis(); }

        @Override public ZoneId zone() { return ZoneId.systemDefault(); }

        @Override public JSONObject telemetrie() {
            try {
                at.greenchili.surfmate.mqtt.MqttConnectionManager m = at.greenchili.surfmate.server.SurveillanceIpcServer.mqttManager();
                return m == null ? null : m.getLatestTelemetry();
            } catch (Throwable t) {
                return null;
            }
        }

        @Override public JSONArray orte() {
            return at.greenchili.surfmate.orte.OrteDaemon.listeOhneAbgleich();
        }

        @Override public TariffProfile tarif(String id) {
            try {
                return TariffManager.getInstance().findById(id);
            } catch (Throwable t) {
                return null;
            }
        }

        @Override public double festerPreis() {
            try {
                JSONObject t = UnifiedConfigManager.loadConfig().optJSONObject("tripAnalytics");
                return t == null ? -1 : t.optDouble("electricityRate", -1);
            } catch (Throwable t) {
                return -1;
            }
        }

        @Override public double kapazitaetKwh() {
            try {
                JSONObject v = UnifiedConfigManager.loadConfig().optJSONObject("vehicle");
                double k = v == null ? 0 : v.optDouble("nominalKwh", 0);
                if (k > 0) return k;
            } catch (Throwable ignored) { }
            try {
                return at.greenchili.surfmate.server.ModelsApiHandler.nominalKwhForSelectedModel();
            } catch (Throwable t) {
                return 0;
            }
        }
    };

    static LadeplanDienst.Wallbox wallbox() {
        return new LadeplanDienst.Wallbox() {
            private WallboxDienst w() {
                return at.greenchili.surfmate.server.WallboxApiHandler.dienst();
            }

            @Override public JSONObject status() throws JSONException {
                return w().status().json;
            }

            @Override public String fahrsperre() {
                return w().fahrsperre();
            }

            @Override public JSONObject steuern(String ladepunkt, String aktion, Object wert) throws JSONException {
                WallboxDienst.Ergebnis r = w().steuernPlan(ladepunkt, aktion, wert);
                return new JSONObject().put("status", r.status).put("json", r.json);
            }

            @Override public long letzteHandSteuerung(String ladepunkt) throws JSONException {
                return w().letzteHandSteuerung(ladepunkt);
            }
        };
    }
}
