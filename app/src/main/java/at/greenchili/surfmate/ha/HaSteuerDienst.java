package at.greenchili.surfmate.ha;

import at.greenchili.surfmate.auth.Kopplungen;
import at.greenchili.surfmate.logging.DaemonLogger;

import org.json.JSONObject;

/**
 * Verdrahtung von {@link HaSteuern} und {@link Heimkommen} im Kamera-Daemon (HA-Geräte Stufe 2, 0.1.38.8).
 * <ul>
 *   <li>Befehle gehen über {@link HaEreignisse#meldeAn} nur an die HA-Kopplung, die die Geräte gemeldet hat.</li>
 *   <li>Jede Aktion: Befehls-Log (Kennwort + Kurzschlüssel der Entität, nie Name/Raum) und beim Ergebnis eine Meldung
 *       (Telegram + Push) ohne Gerätenamen, ohne Raum, ohne Ort.</li>
 *   <li>Takt alle {@link #TAKT_S} s: offene Aufträge ablaufen lassen, Tor-Automatik „Heimkommen“ prüfen.</li>
 * </ul>
 */
public final class HaSteuerDienst {

    private static final DaemonLogger logger = DaemonLogger.getInstance("HA");
    static final int TAKT_S = 3;

    private HaSteuerDienst() {}

    private static final HaSteuern STEUERN = new HaSteuern(
            (kid, befehl) -> HaEreignisse.meldeAn(kid, "befehl", befehl), HaSteuerDienst::melde);
    private static final Heimkommen HEIM = new Heimkommen();
    private static volatile String heimSchluessel;   // tor|zone der laufenden Automatik

    public static HaSteuern steuern() {
        return STEUERN;
    }

    /** Hat die meldende HA-Kopplung das Steuer-Recht (und gilt sie noch)? */
    public static boolean steuerRecht(String kid, long jetzt) {
        try {
            Kopplungen k = Kopplungen.get();
            return kid != null && "homeassistant".equals(k.art(kid)) && k.pruefe(kid, jetzt)
                    && k.hatRecht(kid, Kopplungen.RECHT_GERAETE) && k.hatRecht(kid, Kopplungen.RECHT_GERAETE_STEUERN);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Auftrag aus der Panoptes-Oberfläche oder der Automatik. */
    public static JSONObject auftrag(String entity, String aktion, String herkunft, long jetzt) {
        JSONObject b = HaGeraeteSpeicher.get().lies();
        String kid = b == null ? null : b.optString("kid", null);
        return STEUERN.auftrag(b, kid, steuerRecht(kid, jetzt), entity, aktion, herkunft, jetzt);
    }

    static void melde(String art, String aktion, String entity, String herkunft, String ergebnis) {
        String log = ergebnis.startsWith("abgelehnt:") ? "abgelehnt@" + ergebnis.substring(10)
                : "gesendet".equals(ergebnis) ? "gestartet"
                : HaSteuern.OK.equals(ergebnis) ? "ok" : "fehler@" + ergebnis;
        try {
            at.greenchili.surfmate.server.BefehlsLog.ereignis("ha." + art + "." + aktion, log,
                    HaSteuern.kurzHash(entity), null);
        } catch (Throwable ignored) { }
        logger.info("Gerät " + HaSteuern.kurzHash(entity) + " " + aktion + ": " + log + " (" + herkunft + ")");
        // Gemeldet wird das Ergebnis; Ablehnungen nur, wenn die Automatik sie auslöste (Tor blieb zu).
        boolean ende = HaSteuern.OK.equals(ergebnis) || HaSteuern.FEHLER.equals(ergebnis)
                || HaSteuern.NICHT_BESTAETIGT.equals(ergebnis);
        if (!ende && !("heimkommen".equals(herkunft) && ergebnis.startsWith("abgelehnt:"))) return;
        String text = meldungsText(aktion, ende ? ergebnis : "abgelehnt", herkunft);
        try {
            at.greenchili.surfmate.telegram.TelegramNotifier.sendMessage(text, "CRITICAL");
        } catch (Throwable t) {
            logger.warn("Meldung Telegram: " + t.getMessage());
        }
        try {
            at.greenchili.surfmate.notifications.NotificationBus.get().publish(
                    new at.greenchili.surfmate.notifications.NotificationEvent("automation.action",
                            at.greenchili.surfmate.notifications.NotificationEvent.Severity.INFO, text, "",
                            "ha-steuern-" + System.currentTimeMillis(), "/local/system.html", new JSONObject()));
        } catch (Throwable t) {
            logger.warn("Meldung Push: " + t.getMessage());
        }
    }

    /** „Tor öffnen: erledigt (beim Heimkommen)“ – ohne Gerätenamen, Raum oder Ort. */
    static String meldungsText(String aktion, String ergebnis, String herkunft) {
        String m = at.greenchili.surfmate.server.Messages.text("ha_steuern.aktion_" + aktion, aktion);
        String e = at.greenchili.surfmate.server.Messages.text("ha_steuern.ergebnis_" + ergebnis, ergebnis);
        String h = "heimkommen".equals(herkunft)
                ? " " + at.greenchili.surfmate.server.Messages.text("ha_steuern.heimkommen", "(beim Heimkommen)") : "";
        return "🏠 " + m + ": " + e + h;
    }

    /** Alle {@link #TAKT_S} s aus {@link HaDienst}. */
    static void takt() {
        long jetzt = System.currentTimeMillis();
        STEUERN.aufraeumen(jetzt);
        JSONObject b = HaGeraeteSpeicher.get().lies();
        JSONObject a = b == null ? null : b.optJSONObject("automatik");
        if (a == null) {
            if (heimSchluessel != null) HEIM.zuruecksetzen();
            heimSchluessel = null;
            return;
        }
        String tor = a.optString("tor", ""), zone = a.optString("zone", "");
        String schluessel = tor + "|" + zone;
        if (!schluessel.equals(heimSchluessel)) {
            heimSchluessel = schluessel;
            HEIM.zuruecksetzen();
        }
        if (!HEIM.takt(lage(zone, jetzt))) return;
        logger.info("Heimkommen: Tor " + HaSteuern.kurzHash(tor) + " öffnen");
        auftrag(tor, "oeffnen", "heimkommen", jetzt);
    }

    /** Messwerte für {@link Heimkommen} oder null (Zone weg/aus, kein GPS). Koordinaten verlassen diese Methode nie. */
    static Heimkommen.Lage lage(String zoneId, long jetzt) {
        try {
            at.greenchili.surfmate.surveillance.SafeLocation z = null;
            for (at.greenchili.surfmate.surveillance.SafeLocation s
                    : at.greenchili.surfmate.surveillance.SafeLocationManager.getInstance().getZones()) {
                if (s.getId().equals(zoneId) && s.isEnabled()) z = s;
            }
            at.greenchili.surfmate.monitor.GpsMonitor gps = at.greenchili.surfmate.monitor.GpsMonitor.getInstance();
            if (z == null || gps == null || !gps.hasLocation()) return null;
            double abstand = at.greenchili.surfmate.surveillance.SafeLocationManager.haversine(
                    gps.getLatitude(), gps.getLongitude(), z.getLatitude(), z.getLongitude());
            long alter = jetzt - gps.getLastUpdate();
            String gang = null;
            double kmh = Double.NaN;
            boolean zuendung = false;
            if (abstand <= z.getRadiusMeters()) {   // Fahrzeugwerte nur im Heim-Bereich lesen
                zuendung = at.greenchili.surfmate.monitor.AccMonitor.isAccStateAuthoritative()
                        && at.greenchili.surfmate.monitor.AccMonitor.isAccOn();
                at.greenchili.surfmate.mqtt.MqttConnectionManager m =
                        at.greenchili.surfmate.server.SurveillanceIpcServer.mqttManager();
                JSONObject t = m == null ? null : m.getLatestTelemetry();
                if (t != null) {
                    gang = t.isNull("gear") ? null : t.optString("gear", null);
                    Object v = t.opt("speed");
                    if (v instanceof Number) kmh = ((Number) v).doubleValue();
                }
            }
            return new Heimkommen.Lage(abstand, z.getRadiusMeters(), gang, kmh, zuendung, alter, gps.getAccuracy());
        } catch (Throwable t) {
            return null;
        }
    }
}
