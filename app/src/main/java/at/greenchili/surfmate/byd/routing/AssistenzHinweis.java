package at.greenchili.surfmate.byd.routing;

import at.greenchili.surfmate.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * Haftungshinweis für Assistenzsysteme (03.11.2026: „das sollen sie selbst entscheiden, nicht ich“).
 *
 * <p>Nutzer dürfen Tempowarnung, Tempolimit-Assistent, Spurhaltung, Kollisionswarnung, ESP und die übrigen
 * Assistenzsysteme abschalten, auch per Automation und beim Start. Panoptes sperrt das nicht. Vor dem ersten
 * Abschalten muss aber einmal der Hinweis bestätigt sein ({@link #HINWEIS_VERSION}). Ohne Bestätigung schaltet
 * Panoptes nichts aus (Grund {@link #GRUND}). Einschalten geht immer ohne Hinweis.
 *
 * <p>Geprüft wird an einer Stelle im {@link VehicleCommandRouter} (beide Eingänge {@code execute} und
 * {@code executeSdkOnly}), damit Schalter, Seite, Handy, Tasten, Home Assistant und Automationen gleich behandelt
 * werden. Zusätzlich verlangen das Speichern einer Automation, einer Aktionsgruppe und der Tastenbelegung den
 * Hinweis, wenn darin ein Assistenzsystem ausgeschaltet wird.
 *
 * <p>Der Notbremsassistent (AEB) bleibt nur einschaltbar, auch mit Hinweis ({@link #GRUND_NUR_EIN}).
 *
 * <p>Gespeichert werden nur Textversion und Zeit, keine Personendaten. Bei einer Textänderung in den Katalogen
 * ({@code assistenz.hinweis_text} in {@code web/i18n/*.json}) diese Version hochzählen, dann muss neu bestätigt
 * werden.
 *
 * <p>Fahrerüberwachung, Müdigkeits- und Ablenkungswarnung (DMS, DDAW, ADDW) sind hier bewusst nicht dabei: Panoptes
 * hat dafür keinen belegten BYD-Weg und baut keinen.
 */
public final class AssistenzHinweis {

    private static final DaemonLogger logger = DaemonLogger.getInstance("AssistenzHinweis");

    /** Version des Hinweistexts. Bei jeder Textänderung hochzählen. */
    public static final String HINWEIS_VERSION = "2026-11-03";
    /** Grund, wenn ein Assistenzsystem ohne bestätigten Hinweis ausgeschaltet werden soll. */
    public static final String GRUND = "hinweis_fehlt";
    /** Grund, wenn der Notbremsassistent ausgeschaltet werden soll (nie erlaubt). */
    public static final String GRUND_NUR_EIN = "nur_einschalten";
    static final String DATEI = "assistenz_hinweis.json";

    /**
     * Assistenzsysteme mit Ein/Aus (Schlüssel aus {@code VehicleControlCatalog} und {@code Actions}). Muss mit
     * {@code SCHALTER} in {@code web/shared/assistenz-hinweis.js} übereinstimmen ({@code AssistenzHinweisTest}).
     */
    public static final Set<String> SCHALTER = set("adas_slw", "adas_islc", "adas_tsr", "adas_bsd", "adas_rcta",
            "adas_fcta", "adas_tla", "adas_dow", "adas_rcw", "adas_elka", "adas_rctb", "adas_fctb", "esp_control",
            "itac", "setting_esp_assist", "setting_lane_keeping");
    /** Spurassistent: 0 = aus, 1 = nur Warnung, 2 = nur Eingriff, 3 = beides. Alles unter 3 schaltet etwas ab. */
    public static final String SPUR = "lane_assist";
    /** Frontalkollisionswarnung: Stufe 0 = aus. */
    public static final String FCW = "adas_fcw";
    /** Kinderanwesenheitserkennung: 1 = an, 2 = aus, 3 = verzögert. */
    public static final String CPD = "cpd";
    /** Notbremsassistent: nur einschaltbar. */
    public static final Set<String> NUR_EIN = set("adas_aeb", "setting_aeb");

    /** Befehlsnamen der Ein/Aus-Systeme im Router ({@link VehicleCommandRouter.VehicleCommand#name()}). */
    static final Set<String> BEFEHLE_SCHALTER = set("adas-slw", "adas-islc", "adas-tsr", "adas-bsd", "adas-rcta",
            "adas-fcta", "adas-tla", "adas-dow", "adas-rcw", "adas-elka", "adas-rctb", "adas-fctb", "adas-esp",
            "adas-itac");

    /** Tiefe für Wenn/Schleife/Aktionsgruppe/Tastenfolge. Darüber gilt: schaltet ab (sicherer Fall). */
    static final int MAX_TIEFE = 12;

    /** In Tests ersetzbar; sonst neben den übrigen Daemon-Dateien. */
    static volatile File dateiFuerTest;
    static volatile java.util.function.LongSupplier uhr = System::currentTimeMillis;

    private AssistenzHinweis() {
    }

    private static Set<String> set(String... s) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(s)));
    }

    // ------------------------------------------------------------------ Router-Befehle

    /**
     * Schlüssel des Assistenzsystems, wenn dieser Befehl es ausschaltet oder einen Teil davon abschaltet, sonst
     * {@code null}. Einschalten liefert immer {@code null}.
     */
    public static String abschaltung(VehicleCommandRouter.VehicleCommand cmd) {
        if (cmd == null) return null;
        if (cmd instanceof VehicleCommandRouter.AdasLaneAssistCommand) {
            return ((VehicleCommandRouter.AdasLaneAssistCommand) cmd).mode != 3 ? SPUR : null;
        }
        if (cmd instanceof VehicleCommandRouter.AdasFcwLevelCommand) {
            return ((VehicleCommandRouter.AdasFcwLevelCommand) cmd).level <= 0 ? FCW : null;
        }
        if (cmd instanceof VehicleCommandRouter.SettingChildPresenceDetectionCommand) {
            return ((VehicleCommandRouter.SettingChildPresenceDetectionCommand) cmd).value == 2 ? CPD : null;
        }
        if (cmd instanceof VehicleCommandRouter.CarSettingCommand) {
            VehicleCommandRouter.CarSettingCommand c = (VehicleCommandRouter.CarSettingCommand) cmd;
            String k = "setting_" + c.key;
            if ((SCHALTER.contains(k) || NUR_EIN.contains(k)) && c.value == 0) return k;
            return null;
        }
        String name = cmd.name();
        if (BEFEHLE_SCHALTER.contains(name) || "adas-aeb".equals(name)) {
            Boolean an = eingeschaltet(cmd);
            if (an != null && !an) return katalogSchluessel(name);
        }
        return null;
    }

    /** Würde dieser Befehl den Notbremsassistenten ausschalten? Das lehnt Panoptes immer ab. */
    public static boolean aebAus(VehicleCommandRouter.VehicleCommand cmd) {
        String s = abschaltung(cmd);
        return s != null && NUR_EIN.contains(s);
    }

    private static Boolean eingeschaltet(VehicleCommandRouter.VehicleCommand cmd) {
        try {
            java.lang.reflect.Field f = cmd.getClass().getField("enabled");
            return f.getBoolean(cmd);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Router-Name → Katalog-Schlüssel ({@code adas-slw} → {@code adas_slw}, {@code adas-esp} → {@code esp_control}). */
    static String katalogSchluessel(String befehl) {
        if ("adas-esp".equals(befehl)) return "esp_control";
        if ("adas-itac".equals(befehl)) return "itac";
        return befehl.replace('-', '_');
    }

    /**
     * Prüfung vor jedem Fahrzeugbefehl: {@code null} = darf laufen, sonst der Ablehnungsgrund ({@link #GRUND} oder
     * {@link #GRUND_NUR_EIN}).
     */
    public static String sperrGrund(VehicleCommandRouter.VehicleCommand cmd) {
        String s = abschaltung(cmd);
        if (s == null) return null;
        if (NUR_EIN.contains(s)) return GRUND_NUR_EIN;
        return bestaetigt() ? null : GRUND;
    }

    /**
     * Prüfung beim Speichern (Automation, Gruppe, Tasten): {@code null} = darf gespeichert werden, sonst der Grund.
     * {@code system} ist der Treffer aus {@link #regelSchaltetAb} bzw. {@link #tastenSchaltenAb}.
     */
    public static String speichernGrund(String system) {
        if (system == null) return null;
        if (NUR_EIN.contains(system)) return GRUND_NUR_EIN;
        return bestaetigt() ? null : GRUND;
    }

    // ------------------------------------------------------------------ Automationen, Gruppen, Tasten

    /**
     * Schlüssel des Assistenzsystems, wenn diese Automations-Aktion es ausschaltet (oder ausschalten könnte, z. B.
     * mit Variable oder „umschalten“), sonst {@code null}.
     */
    public static String abschaltungAktion(String typ, JSONObject variablen) {
        if (typ == null) return null;
        String t = typ.trim();
        JSONObject v = variablen == null ? new JSONObject() : variablen;
        if (SCHALTER.contains(t) || NUR_EIN.contains(t)) {
            return "on".equalsIgnoreCase(v.optString("payload", "").trim()) ? null : t;
        }
        if (SPUR.equals(t)) return "3".equals(v.optString("payload", "").trim()) ? null : t;
        if (FCW.equals(t)) {
            String p = v.optString("payload", "").trim();
            return "1".equals(p) || "2".equals(p) || "3".equals(p) ? null : t;
        }
        if (CPD.equals(t) || "adas_cpd".equals(t)) {
            String p = v.optString(CPD.equals(t) ? "action" : "payload", "").trim();
            return "1".equals(p) || "3".equals(p) || "on".equalsIgnoreCase(p) ? null : CPD;
        }
        return null;
    }

    /** Quelle für Aktionsgruppen (im Daemon der echte Speicher, in Tests ersetzbar). */
    public interface Gruppen {
        JSONObject alle();
    }

    static final Gruppen SPEICHER = () -> {
        try {
            return at.greenchili.surfmate.automation.ActionGroups.toJson();
        } catch (Throwable t) {
            return new JSONObject();
        }
    };

    /** Schaltet diese Regel (oder Aktionsgruppe) ein Assistenzsystem aus? Liefert den ersten Schlüssel, sonst null. */
    public static String regelSchaltetAb(JSONObject regel) {
        return regelSchaltetAb(regel, SPEICHER);
    }

    static String regelSchaltetAb(JSONObject regel, Gruppen gruppen) {
        if (regel == null) return null;
        JSONObject g = gruppen == null ? null : gruppen.alle();
        Set<String> gesehen = new HashSet<>();
        String s = liste(regel.optJSONArray("actions"), g, gesehen, 0);
        if (s != null) return s;
        return liste(regel.optJSONArray("elseActions"), g, gesehen, 0);
    }

    private static String liste(JSONArray a, JSONObject gruppen, Set<String> gesehen, int tiefe) {
        if (a == null) return null;
        if (tiefe > MAX_TIEFE) return "zu_tief";
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x == null) continue;
            String typ = x.optString("type", "");
            String s = abschaltungAktion(typ, x.optJSONObject("variables"));
            if (s != null) return s;
            s = liste(x.optJSONArray("childActions"), gruppen, gesehen, tiefe + 1);
            if (s != null) return s;
            s = liste(x.optJSONArray("elseActions"), gruppen, gesehen, tiefe + 1);
            if (s != null) return s;
            if ("actionGroup".equalsIgnoreCase(typ.trim()) && gruppen != null) {
                JSONObject v = x.optJSONObject("variables");
                String gid = v == null ? "" : v.optString("groupId", "").trim();
                if (!gid.isEmpty() && gesehen.add(gid)) {
                    JSONObject gruppe = gruppen.optJSONObject(gid);
                    if (gruppe != null) {
                        s = liste(gruppe.optJSONArray("actions"), gruppen, gesehen, tiefe + 1);
                        if (s != null) return s;
                    }
                }
            }
        }
        return null;
    }

    /** Erste Regel einer Sammlung (id → Regel, z. B. Import), die ein Assistenzsystem ausschaltet, sonst null. */
    public static String sammlungSchaltetAb(JSONObject sammlung) {
        if (sammlung == null) return null;
        Iterator<String> it = sammlung.keys();
        while (it.hasNext()) {
            JSONObject r = sammlung.optJSONObject(it.next());
            String s = regelSchaltetAb(r);
            if (s != null) return s;
        }
        return null;
    }

    /**
     * Tastenbelegung: schaltet eine Belegung ein Assistenzsystem aus? Geprüft werden alle Schritte vom Typ
     * {@code catalog} (auch in Folgen); „umschalten“ zählt als Ausschalten.
     */
    public static String tastenSchaltenAb(JSONArray belegungen) {
        return tasten(belegungen, 0);
    }

    private static String tasten(Object o, int tiefe) {
        if (tiefe > MAX_TIEFE) return "zu_tief";
        if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                String s = tasten(a.opt(i), tiefe + 1);
                if (s != null) return s;
            }
            return null;
        }
        if (!(o instanceof JSONObject)) return null;
        JSONObject j = (JSONObject) o;
        if ("catalog".equals(j.optString("kind", ""))) {
            String key = j.optString("key", "").trim();
            String p = j.optString("payload", "").trim().toLowerCase(Locale.ROOT);
            if (SCHALTER.contains(key) || NUR_EIN.contains(key)) {
                if (!"on".equals(p) && !"1".equals(p)) return key;
            } else if (SPUR.equals(key)) {
                if (!"3".equals(p)) return key;
            } else if (FCW.equals(key)) {
                if (!"1".equals(p) && !"2".equals(p) && !"3".equals(p)) return key;
            } else if ("adas_cpd".equals(key)) {
                if (!"on".equals(p) && !"1".equals(p)) return CPD;
            }
        }
        Iterator<String> it = j.keys();
        while (it.hasNext()) {
            Object kind = j.opt(it.next());
            if (kind instanceof JSONObject || kind instanceof JSONArray) {
                String s = tasten(kind, tiefe + 1);
                if (s != null) return s;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ Bestätigung (nur Version und Zeit)

    static File datei() {
        File t = dateiFuerTest;
        if (t != null) return t;
        return new File(at.greenchili.surfmate.util.DaemonStorage.path(DATEI));
    }

    /** Ist der Hinweis in der aktuellen Version bestätigt? */
    public static boolean bestaetigt() {
        try {
            File f = datei();
            if (!f.isFile()) return false;
            JSONObject h = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            return HINWEIS_VERSION.equals(h.optString("version")) && h.optLong("zeit", 0) > 0;
        } catch (Throwable x) {
            return false;
        }
    }

    /** Hinweis bestätigen. Gilt nur für die aktuelle {@link #HINWEIS_VERSION}. */
    public static JSONObject bestaetigen(String version) {
        JSONObject r = new JSONObject();
        try {
            if (!HINWEIS_VERSION.equals(version)) {
                return r.put("ok", false).put("fehler", "hinweis_version").put("hinweisVersion", HINWEIS_VERSION);
            }
            JSONObject h = new JSONObject().put("version", HINWEIS_VERSION).put("zeit", uhr.getAsLong());
            File f = datei();
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            File tmp = new File(f.getPath() + ".tmp");
            Files.write(tmp.toPath(), h.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            logger.info("Assistenz-Hinweis bestätigt (Version " + HINWEIS_VERSION + ")");
            return r.put("ok", true).put("hinweisVersion", HINWEIS_VERSION).put("hinweisBestaetigt", true);
        } catch (Throwable x) {
            logger.warn("Assistenz-Hinweis nicht gespeichert: " + x.getMessage());
            try {
                return r.put("ok", false).put("fehler", "nicht_gespeichert");
            } catch (Exception e) {
                return r;
            }
        }
    }

    /** Stand für die Oberfläche: Version und ob bestätigt (keine Zeit, keine Personendaten). */
    public static JSONObject status() {
        JSONObject r = new JSONObject();
        try {
            r.put("ok", true).put("hinweisVersion", HINWEIS_VERSION).put("hinweisBestaetigt", bestaetigt());
        } catch (Exception ignored) {
        }
        return r;
    }

    /** Herkunft fürs Befehls-Log: nutzer | handy | automation | ha | telegram. */
    public static String herkunft() {
        String q = at.greenchili.surfmate.gewohnheiten.Herkunft.aktuell();
        if (q == null) return "automation";
        switch (q) {
            case at.greenchili.surfmate.gewohnheiten.Herkunft.DISPLAY:
            case at.greenchili.surfmate.gewohnheiten.Herkunft.COCKPIT:
                return "nutzer";
            case at.greenchili.surfmate.gewohnheiten.Herkunft.HANDY:
                return "handy";
            case at.greenchili.surfmate.gewohnheiten.Herkunft.HA:
                return "ha";
            case at.greenchili.surfmate.gewohnheiten.Herkunft.TELEGRAM:
                return "telegram";
            default:
                return "automation";
        }
    }

    /** HTTP-Teil: {@code GET /api/assistenz/hinweis} (Stand), {@code POST} mit {@code {"version": …}} (bestätigen). */
    public static boolean handle(String method, String path, String body, java.io.OutputStream out) throws Exception {
        String p = path == null ? "" : path;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (!"/api/assistenz/hinweis".equals(p)) return false;
        if ("GET".equals(method)) {
            at.greenchili.surfmate.server.HttpResponse.sendJson(out, status().toString());
            return true;
        }
        if ("POST".equals(method)) {
            JSONObject req = null;
            try {
                req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
            } catch (Exception ignored) {
            }
            JSONObject r = bestaetigen(req == null ? "" : req.optString("version", ""));
            at.greenchili.surfmate.server.HttpResponse.sendJson(out, r.toString());
            return true;
        }
        at.greenchili.surfmate.server.HttpResponse.sendError(out, 405, "Method not allowed");
        return true;
    }

    /** Antwort für Speichern ohne Hinweis (Automation, Gruppe, Tasten). */
    public static JSONObject ablehnung(String system, String grund) {
        JSONObject r = new JSONObject();
        try {
            boolean nurEin = GRUND_NUR_EIN.equals(grund);
            r.put("success", false).put("ok", false).put("grund", nurEin ? GRUND_NUR_EIN : GRUND)
                    .put("system", system).put("hinweisVersion", HINWEIS_VERSION)
                    .put("error", nurEin
                            ? at.greenchili.surfmate.server.Messages.text("vehicle_control.assistenz_nur_einschalten",
                                    "The emergency braking assistant can only be switched on.")
                            : at.greenchili.surfmate.server.Messages.text("vehicle_control.assistenz_hinweis_fehlt",
                                    "Please confirm the notice about driver assistance systems first."));
        } catch (Exception ignored) {
        }
        return r;
    }
}
