package at.greenchili.surfmate.server;

import at.greenchili.surfmate.config.UnifiedConfigManager;
import at.greenchili.surfmate.uebernahme.OverdriveQuelle;
import at.greenchili.surfmate.uebernahme.OverdriveUebernahme;

import org.json.JSONObject;

import java.io.File;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

/**
 * Overdrive-Übernahme, nur lokal am Auto (LADEN_VERTRAG.md Teil B):
 * <ul>
 *   <li>{@code GET /api/uebernahme/overdrive} – Vorschau „was wird übernommen“ (nichts geschrieben)</li>
 *   <li>{@code POST /api/uebernahme/overdrive {vorschau_id, posten:[id…]}} – übernimmt die bestätigten Posten</li>
 * </ul>
 * Nur mit Login bzw. am Display; gekoppelte Geräte nie (ExternGuard lässt den Pfad ohnehin nicht durch).
 * Nichts davon geht an einen Server; Overdrive-Dateien werden nur gelesen.
 */
public final class UebernahmeApiHandler {

    private UebernahmeApiHandler() {}

    /**
     * Haken für die Einrichtung (Kern 4, Schritt {@code extra_overdrive}): entfällt, wenn keine nutzbaren
     * Overdrive-Daten da sind. Ohne Paketabfrage (schnell, kein Prozessstart); nur lesen.
     */
    public static void anmelden() {
        ladepreisHakenSetzen();
        EinrichtungApiHandler.overdriveErkennung = () -> OverdriveUebernahme.vorhanden(
                OverdriveQuelle.erkenne(new File(OverdriveQuelle.STANDARD_ORDNER),
                        OverdriveUebernahme.dateinamen(OverdriveUebernahme.REGELN), false));
    }

    /** Außenwelt für Tests austauschbar. */
    interface Umgebung {
        OverdriveQuelle.Befund befund(List<OverdriveUebernahme.Regel> regeln);
        JSONObject config();
        OverdriveUebernahme.Schreiber schreiber();
        boolean extern();
        OverdriveUebernahme.Texte texte();
        /** Vorhandene Panoptes-MQTT-Verbindungen (nur zum Vergleich), null = keine/unbekannt. */
        default org.json.JSONArray panoptesMqtt() { return null; }
    }

    /** Overdrive Stufe 2 (eine Übernahme gleichzeitig). */
    private static volatile at.greenchili.surfmate.uebernahme.OverdriveVerlauf verlauf;

    private static at.greenchili.surfmate.uebernahme.OverdriveVerlauf verlauf() {
        if (verlauf == null) {
            synchronized (UebernahmeApiHandler.class) {
                if (verlauf == null) verlauf = new at.greenchili.surfmate.uebernahme.OverdriveVerlauf(
                        new at.greenchili.surfmate.uebernahme.VerlaufLaufzeit());
            }
        }
        return verlauf;
    }

    /**
     * {@code GET /api/uebernahme/overdrive/verlauf} (Vorschau + Stand), {@code POST …/verlauf {start:true}},
     * {@code POST …/verlauf/abbrechen} – nur Login/Display, nie Kopplung.
     */
    static Object[] verlaufBearbeiten(String method, String pathOnly, boolean extern,
                                      at.greenchili.surfmate.uebernahme.OverdriveVerlauf v) throws Exception {
        if (extern) return fehler(403, "nur_mit_login");
        if (pathOnly.endsWith("/abbrechen")) {
            if (!"POST".equals(method)) return fehler(405, "methode");
            v.abbrechen();
            return new Object[] {200, new JSONObject().put("ok", true).put("stand", v.stand())};
        }
        if ("GET".equals(method)) return new Object[] {200, v.vorschau()};
        if ("POST".equals(method)) {
            JSONObject r = v.starten();
            int status = r.optBoolean("ok") ? 202 : ("laeuft_schon".equals(r.optString("grund")) ? 409
                    : "nur_im_stand".equals(r.optString("grund")) ? 412
                    : "zu_wenig_speicher".equals(r.optString("grund")) ? 507 : 409);
            return new Object[] {status, r.put("stand", v.stand())};
        }
        return fehler(405, "methode");
    }

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String pathOnly = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if (pathOnly.startsWith("/api/uebernahme/overdrive/verlauf")) {
            Object[] r = verlaufBearbeiten(method, pathOnly, AuthMiddleware.isExternRequest(), verlauf());
            HttpResponse.sendJson(out, (Integer) r[0], r[1].toString());
            return true;
        }
        Object[] r = bearbeite(method, pathOnly, body, OverdriveUebernahme.REGELN, ECHT);
        if (r == null) return false;
        HttpResponse.sendJson(out, (Integer) r[0], r[1].toString());
        return true;
    }

    /**
     * Kern 4s Haken {@code TripApiHandler.ladepreisVor} (Fahrtkosten nachrechnen) per Reflexion setzen, damit beide Zweige
     * unabhängig kompilieren. Fehlt das Feld (anderer Stand), passiert nichts.
     */
    static boolean ladepreisHakenSetzen() {
        try {
            java.lang.reflect.Field f = Class.forName("at.greenchili.surfmate.trips.TripApiHandler").getField("ladepreisVor");
            java.util.function.BiFunction<Long, Integer, JSONObject> fn = (vorMs, tage) -> {
                at.greenchili.surfmate.monitor.SocHistoryDatabase d = at.greenchili.surfmate.monitor.SocHistoryDatabase.getInstance();
                return d == null || vorMs == null ? null : d.getLastChargeRateVor(vorMs, tage == null ? 60 : tage);
            };
            f.set(null, fn);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static Object[] bearbeite(String method, String pathOnly, String body, List<OverdriveUebernahme.Regel> regeln,
                              Umgebung u) throws Exception {
        if (!"/api/uebernahme/overdrive".equals(pathOnly)) return null;
        if (u.extern()) return fehler(403, "nur_mit_login");
        if ("GET".equals(method)) {
            return new Object[] {200, OverdriveUebernahme.vorschau(u.befund(regeln), u.config(), u.panoptesMqtt(), regeln, u.texte())};
        }
        if ("POST".equals(method)) {
            JSONObject req;
            try {
                req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
            } catch (Exception e) {
                return fehler(400, "json");
            }
            JSONObject res = OverdriveUebernahme.uebernehmen(u.befund(regeln), u.config(), u.panoptesMqtt(), regeln,
                    req.optString("vorschau_id", null), req.optJSONArray("posten"), u.schreiber());
            int status = res.optInt("status", 200);
            res.remove("status");
            return new Object[] {status, res};
        }
        return fehler(405, "methode");
    }

    private static Object[] fehler(int status, String grund) throws Exception {
        JSONObject o = new JSONObject();
        o.put("ok", false);
        o.put("grund", grund);
        return new Object[] {status, o};
    }

    static final Umgebung ECHT = new Umgebung() {
        public OverdriveQuelle.Befund befund(List<OverdriveUebernahme.Regel> regeln) {
            return OverdriveQuelle.erkenne(new File(OverdriveQuelle.STANDARD_ORDNER),
                    OverdriveUebernahme.dateinamen(regeln), OverdriveQuelle.paketBekannt());
        }

        public JSONObject config() { return UnifiedConfigManager.forceReload(); }

        public OverdriveUebernahme.Schreiber schreiber() {
            return new OverdriveUebernahme.Schreiber() {
                public boolean werte(String abschnitt, Map<String, Object> werte) {
                    try {
                        return UnifiedConfigManager.updateValues(abschnitt, werte);
                    } catch (Exception e) {
                        return false;
                    }
                }

                /** Über den laufenden Manager (er hält die Liste im Speicher und schreibt sie selbst). */
                public boolean mqtt(JSONObject verbindung) {
                    try {
                        at.greenchili.surfmate.mqtt.MqttConnectionManager m = SurveillanceIpcServer.mqttManager();
                        return m != null && m.addConnection(new JSONObject(verbindung.toString())) != null;
                    } catch (Throwable t) {
                        return false;
                    }
                }
            };
        }

        public boolean extern() { return AuthMiddleware.isExternRequest(); }

        public OverdriveUebernahme.Texte texte() { return (key, rueckfall) -> Messages.text(key, rueckfall); }

        public org.json.JSONArray panoptesMqtt() {
            try {
                at.greenchili.surfmate.mqtt.MqttConnectionManager m = SurveillanceIpcServer.mqttManager();
                if (m == null) return null;
                org.json.JSONArray a = new org.json.JSONArray();
                for (at.greenchili.surfmate.mqtt.MqttConnectionConfig c : m.getStore().getAll()) a.put(c.toJson());
                return a;
            } catch (Throwable t) {
                return null;
            }
        }
    };
}
