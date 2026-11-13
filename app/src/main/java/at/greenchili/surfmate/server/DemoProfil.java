package at.greenchili.surfmate.server;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.1.38.2 Demo-Profil: Musterdaten für Bilder (Homepage, Handy, Cockpit) am Emulator, ohne echte Daten.
 *
 * Aktiv nur, wenn BEIDES gilt:
 * <ol>
 *   <li>erlaubt: Emulator ({@code ro.kernel.qemu} oder {@code ro.boot.qemu} = 1 UND {@code ro.hardware} ranchu/goldfish)
 *       ODER Screenshot-Build ({@code -PpanoptesScreenshotModus=true}, {@code BuildConfig.SCREENSHOT_MODUS}).
 *       Die Auto-Build hat beides nicht, {@code ro.*} lässt sich am Auto nicht setzen.</li>
 *   <li>eingeschaltet: System-Eigenschaft {@code debug.panoptes.demo=1} (nur per {@code adb shell setprop}).
 *       Es gibt bewusst keinen Web-, Telegram- oder Konfig-Schalter.</li>
 * </ol>
 *
 * Getrennter Speicher: Die Musterdaten liegen fest im APK unter {@code assets/demo/}. Das Profil liest und schreibt
 * keine echte Datenbank. Solange es aktiv ist, lehnt der Server jeden schreibenden Aufruf sowie Kamera- und
 * Fahrzeugwege mit 403 ab, also gibt es auch keine Fahrzeugbefehle. Die Web-Oberfläche zeigt das Band „Musterdaten“
 * ({@code app-shell.js} liest {@code GET /api/demo}).
 *
 * <p>0.1.38.5: Muster auch für den Planer (feste Antwort {@code plan-ergebnis/1} Linz → Wien, kein Planer-Aufruf),
 * Wächter, Aufnahmen und Dashcam (gezeichnete Bilder und Clips, {@code tools/demo_muster_medien.py}), die
 * Launcher-Zusammenfassung und die Handy-App. Deren Koppeln wird nur simuliert: {@link #beantworteKopplung} gibt den
 * Demo-Schlüssel aus, {@link #beantworteGeraet} beantwortet ihn vor {@code AuthMiddleware} nur mit Mustern oder 403.
 */
public final class DemoProfil {

    public static final String PFAD = "/api/demo";
    static final String EIGENSCHAFT = "debug.panoptes.demo";

    /** Genaue Pfade → Datei unter {@code assets/demo/}. */
    static final Map<String, String> DATEIEN;
    /** Lesende POSTs (Suche mit Rumpf) → Datei; der Rumpf wird nicht ausgewertet. */
    static final Map<String, String> LESE_POSTS;
    /** Fahrten im Muster (Einzelheiten, Verlauf, Vergleich, Strecke je ID). */
    static final int[] FAHRTEN = {41, 42, 43, 44, 45, 46, 47, 48};
    /** Ladung im Muster mit Einzelheiten (Leistungsverlauf, Säule). */
    static final int LADUNG = 5;
    /** 0.1.38.5: Muster-Clips (Wächter: Person, Fahrzeug; Dashcam: Vollbremsung), Bilder unter {@code demo/medien/}. */
    static final String[] CLIPS = {"de30a0000000000000000000000000a1", "de30a0000000000000000000000000a2",
            "de30a0000000000000000000000000b1"};
    /** Lesende Aufrufe mit Bild oder Video → Datei unter {@code assets/demo/}. */
    static final Map<String, String> MEDIEN;
    /** Nur für die Demo-Kopplung der Handy-App (die Web-Oberfläche liest hier weiter den echten Handler). */
    static final Map<String, String> GERAET_DATEIEN;
    /**
     * Schlüssel der simulierten Handy-Kopplung. Gilt nur, solange das Profil aktiv ist, und kommt nie an
     * {@code AuthMiddleware} vorbei: eine echte Kopplung entsteht dabei nicht.
     */
    static final String GERAET_TOKEN = "demo.panoptes.musterkopplung";
    static final String GERAET_ID = "k-demo";
    /** Standbild des Wächters (Musterbild). */
    static final String STANDBILD = "medien/standbild.jpg";

    /**
     * Bezugstag der Musterdaten (21.10.2026, 00:00 UTC). Zeitstempel in ms rund um diesen Tag werden beim Ausliefern
     * um ganze Tage bis heute verschoben, damit die Fahrten „von gestern“ sind und Filter wie „7 Tage“ sie zeigen.
     */
    static final long ANKER_MS = 1792540800000L;
    private static final long TAG_MS = 86_400_000L;
    private static final Pattern ZEITSTEMPEL = Pattern.compile("(?<![0-9.])(1[78][0-9]{11})(?![0-9.])");

    static {
        Map<String, String> d = new LinkedHashMap<>();
        // Startseite, Fahrzeug
        d.put("/status", "status.json");
        d.put("/api/vehicle/state", "vehicle_state.json");
        d.put("/api/system/profile", "system_profile.json");
        d.put("/api/quick-controls", "quick_controls.json");
        d.put("/api/recordings/stats", "recordings_stats.json");
        d.put("/api/genai/insights/dashboard", "genai_insights_dashboard.json");
        // Fahrtenbuch
        d.put("/api/trips", "trips.json");
        d.put("/api/trips/bootstrap", "trips_bootstrap.json");
        d.put("/api/trips/config", "trips_config.json");
        d.put("/api/trips/storage", "trips_storage.json");
        d.put("/api/trips/dna", "trips_dna.json");
        d.put("/api/trips/summary", "trips_summary.json");
        d.put("/api/trips/range", "trips_range.json");
        d.put("/api/trips/verbrauch-temperatur", "trips_verbrauch_temperatur.json");
        d.put("/api/trips/standverlust", "trips_standverlust.json");
        d.put("/api/parking/sessions", "parking_sessions.json");
        for (int id : FAHRTEN) {
            d.put("/api/trips/" + id, "trips_" + id + ".json");
            d.put("/api/trips/" + id + "/telemetry", "trips_" + id + "_telemetry.json");
            d.put("/api/trips/" + id + "/similar", "trips_" + id + "_similar.json");
            d.put("/api/trips/" + id + "/gps", "trips_" + id + "_gps.json");
        }
        // Laden
        d.put("/api/charging", "charging.json");
        d.put("/api/charging/bootstrap", "charging_bootstrap.json");
        d.put("/api/charging/overview", "charging_overview.json");
        d.put("/api/charging/summary", "charging_summary.json");
        d.put("/api/charging/soc", "charging_soc.json");
        d.put("/api/charging/config", "charging_config.json");
        d.put("/api/charging/tariffs", "charging_tariffs.json");
        d.put("/api/charging/" + LADUNG, "charging_id.json");
        d.put("/api/charging/" + LADUNG + "/samples", "charging_id_samples.json");
        d.put("/api/charging/" + LADUNG + "/station", "charging_id_station.json");
        d.put("/api/laden", "laden.json");
        d.put("/api/laden/kosten", "laden_kosten.json");
        d.put("/api/laden/tarife", "laden_tarife.json");
        d.put("/api/laden/nachfrage", "laden_nachfrage.json");
        d.put("/api/laden/ladungen", "laden_ladungen.json");
        d.put("/api/laden/kurve", "laden_kurve.json");
        d.put("/api/wallbox/status", "wallbox_status.json");
        // Bericht, Regeln (Schema und Einstellungen der Regeln liefert der echte Handler, nur lesend)
        d.put("/api/bericht/monat", "bericht_monat.json");
        d.put("/api/automations/list", "automations_list.json");
        d.put("/api/action-groups", "action_groups.json");
        d.put("/api/community/list", "community_list.json");
        // Karte
        d.put("/api/gps", "gps.json");
        d.put("/api/orte", "orte.json");
        // Apps
        d.put("/api/apps/list", "apps_list.json");
        d.put("/api/apps/capabilities", "apps_capabilities.json");
        d.put("/api/apps/verwaltung", "apps_verwaltung.json");
        // 0.1.38.5 Planer („Reicht es noch?“; eine Route vom Planer zum Übernehmen gibt es im Muster nicht)
        d.put("/api/navi/unterwegs", "navi_unterwegs.json");
        d.put("/api/navi/planer-route", "navi_planer_route.json");
        // 0.1.38.5 Wächter, Aufnahmen, Dashcam (/api/recordings und /dates richten sich nach der Anfrage)
        d.put("/api/surveillance/status", "surveillance_status.json");
        d.put("/api/surveillance/gesundheit", "surveillance_gesundheit.json");
        d.put("/api/recordings", "recordings.json");
        d.put("/api/recordings/places", "recordings_places.json");
        d.put("/api/ereignisse/offen", "ereignisse_offen.json");
        // 0.1.38.5 Launcher
        d.put("/api/launcher/v1/summary", "launcher_summary.json");
        d.put("/api/launcher/v1/history", "launcher_history.json");
        d.put("/api/launcher/v1/traffic", "launcher_traffic.json");
        Map<String, String> m = new LinkedHashMap<>();
        for (String id : CLIPS) {
            d.put("/api/events/id/" + id, "events_" + id.substring(30) + ".json");
            m.put("/thumb/id/" + id, "medien/" + id + ".jpg");
            m.put("/video/id/" + id, "medien/" + id + ".mp4");
        }
        DATEIEN = Collections.unmodifiableMap(d);
        MEDIEN = Collections.unmodifiableMap(m);

        Map<String, String> l = new LinkedHashMap<>();
        l.put("/api/navi/umkreis", "navi_umkreis.json");
        l.put("/api/navi/plan", "navi_plan.json");     // feste Muster-Antwort plan-ergebnis/1, kein Planer-Aufruf
        l.put("/api/navi/orte", "navi_orte.json");
        LESE_POSTS = Collections.unmodifiableMap(l);

        Map<String, String> g = new LinkedHashMap<>();
        g.put("/api/ha/v1/zustand", "ha_zustand.json");
        g.put("/api/ladeplan", "ladeplan.json");
        g.put("/api/camping", "camping.json");
        g.put("/api/system/capabilities", "system_capabilities.json");
        g.put("/api/einstellungen", "handy_einstellungen.json");
        g.put("/api/handy/v1/update", "handy_update.json");
        g.put("/api/appupdate/fern", "appupdate_fern.json");   // Handy-App ab 0.4.x
        g.put("/api/handy/v1/clips", "handy_clips.json");
        GERAET_DATEIEN = Collections.unmodifiableMap(g);
    }

    /** Lesende Aufrufe, die trotzdem etwas auslösen (Kamera, Fahrzeug, Tacho). Im Demo-Profil zu. */
    static final String[] AKTIONS_ANFAENGE = {
            "/api/start/", "/api/view/", "/api/stop/", "/api/stopall", "/api/vehicle/", "/api/vehicle?",
            "/api/quick-controls/", "/api/launcher/v1/vehicle/", "/api/tacho/normal", "/api/tacho/navi",
            "/api/launcher/v1/tacho/", "/api/launcher/v1/seitenblick",   // 0.1.41 LC: Kombi, Seitenblick
            "/api/launcher/v1/fenster",   // 0.1.44 D1: Fenster-Schnellknöpfe
            "/api/system/ivi-reboot", "/api/audio/", "/api/stream", "/api/bs/", "/api/camview/", "/h264/", "/view/",
            "/api/communicate/", "/api/dev-view/", "/api/recording/mode",
    };

    private static volatile long gelesenUm;
    private static volatile boolean zwischenstand;

    private DemoProfil() {}

    /** Reine Regel: darf das Profil hier überhaupt eingeschaltet werden? */
    static boolean erlaubt(String kernelQemu, String bootQemu, String hardware, boolean screenshotBuild) {
        if (screenshotBuild) return true;
        boolean qemu = "1".equals(kernelQemu) || "1".equals(bootQemu);
        return qemu && ("ranchu".equals(hardware) || "goldfish".equals(hardware));
    }

    /** Reine Regel: aktiv = erlaubt UND per Eigenschaft eingeschaltet. */
    static boolean aktiv(String kernelQemu, String bootQemu, String hardware, boolean screenshotBuild, String schalter) {
        return "1".equals(schalter) && erlaubt(kernelQemu, bootQemu, hardware, screenshotBuild);
    }

    /** Laufzeit: liest die System-Eigenschaften höchstens alle 2 s neu. */
    public static boolean aktiv() {
        long jetzt = System.currentTimeMillis();
        if (jetzt - gelesenUm < 2000) return zwischenstand;
        boolean a = aktiv(eigenschaft("ro.kernel.qemu"), eigenschaft("ro.boot.qemu"), eigenschaft("ro.hardware"),
                at.greenchili.surfmate.BuildConfig.SCREENSHOT_MODUS, eigenschaft(EIGENSCHAFT));
        zwischenstand = a;
        gelesenUm = jetzt;
        return a;
    }

    /** Musterdatei für einen lesenden Aufruf, sonst null. */
    static String datei(String method, String pathOnly) {
        if (pathOnly == null) return null;
        if ("POST".equals(method)) return LESE_POSTS.get(pathOnly);
        if (!"GET".equals(method)) return null;
        return DATEIEN.get(pathOnly);
    }

    /** Ganze Tage vom Bezugstag bis jetzt (nie rückwärts). */
    static long verschiebungMs(long jetzt) {
        return jetzt <= ANKER_MS ? 0 : ((jetzt - ANKER_MS) / TAG_MS) * TAG_MS;
    }

    /** Verschiebt nur ms-Zeitstempel im Fenster 120 Tage vor bis 30 Tage nach dem Bezugstag. */
    static String verschiebe(String json, long jetzt) {
        long um = verschiebungMs(jetzt);
        if (um == 0) return json;
        Matcher m = ZEITSTEMPEL.matcher(json);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            long v = Long.parseLong(m.group(1));
            boolean imFenster = v >= ANKER_MS - 120 * TAG_MS && v <= ANKER_MS + 30 * TAG_MS;
            m.appendReplacement(sb, Long.toString(imFenster ? v + um : v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Im Demo-Profil zu: alles Schreibende und alle Aktionswege, auch als GET. */
    static boolean gesperrt(String method, String pathOnly) {
        if (pathOnly == null) return true;
        if (!"GET".equals(method) && !"HEAD".equals(method)) return datei(method, pathOnly) == null;
        for (String a : AKTIONS_ANFAENGE) {
            if (pathOnly.startsWith(a) && datei(method, pathOnly) == null) return true;
        }
        return false;
    }

    /** Bild oder Video für einen lesenden Aufruf, sonst null (Standbild und {@code /snapshot/} = Musterbild). */
    static String medium(String method, String pathOnly) {
        if (pathOnly == null || !("GET".equals(method) || "HEAD".equals(method))) return null;
        String m = MEDIEN.get(pathOnly);
        if (m != null) return m;
        if (pathOnly.startsWith(ExternGuard.STANDBILD_PFAD) || pathOnly.startsWith("/snapshot/")) return STANDBILD;
        return null;
    }

    /** {@code /api/recordings?type=…}: die Liste passend zum Filter (Wächter, Ereignis, sonst leer). */
    static String aufnahmenDatei(String query) {
        String typ = wert(query, "type");
        if (typ == null || typ.isEmpty() || "all".equals(typ)) return "recordings.json";
        if ("sentry".equals(typ)) return "recordings_sentry.json";
        if ("ereignis".equals(typ)) return "recordings_ereignis.json";
        return "recordings_leer.json";
    }

    private static String wert(String query, String name) {
        if (query == null) return null;
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals(name)) return kv.substring(eq + 1);
        }
        return null;
    }

    /** Datum und Uhrzeit der Aufnahmen passend zum verschobenen Zeitstempel (Format wie {@code RecordingsIndex}). */
    static String aufnahmenMitDatum(String json, TimeZone zone) throws Exception {
        JSONObject j = new JSONObject(json);
        JSONArray liste = j.optJSONArray("recordings");
        if (liste == null) return json;
        for (int i = 0; i < liste.length(); i++) {
            JSONObject r = liste.getJSONObject(i);
            Date d = new Date(r.getLong("timestamp"));
            r.put("date", format("yyyy-MM-dd", zone).format(d));
            r.put("time", format("HH:mm:ss", zone).format(d));
            r.put("dateFormatted", format("MMM d, yyyy", zone).format(d));
            r.put("timeFormatted", format("h:mm a", zone).format(d));
        }
        return j.toString();
    }

    /** {@code /api/recordings/dates} aus der (schon datierten) Musterliste. */
    static String tageJson(String aufnahmen) throws Exception {
        JSONArray liste = new JSONObject(aufnahmen).getJSONArray("recordings");
        Map<String, JSONObject> tage = new LinkedHashMap<>();
        for (int i = 0; i < liste.length(); i++) {
            JSONObject r = liste.getJSONObject(i);
            JSONObject t = tage.get(r.getString("date"));
            if (t == null) {
                t = new JSONObject().put("date", r.getString("date")).put("count", 0).put("hasSentry", false);
                tage.put(r.getString("date"), t);
            }
            t.put("count", t.getInt("count") + 1);
            if ("sentry".equals(r.optString("type"))) t.put("hasSentry", true);
        }
        return new JSONObject().put("success", true).put("dates", new JSONArray(tage.values())).toString();
    }

    private static SimpleDateFormat format(String muster, TimeZone zone) {
        SimpleDateFormat f = new SimpleDateFormat(muster, Locale.US);
        f.setTimeZone(zone);
        return f;
    }

    /** Musterdaten als Text: aus dem APK, Zeitstempel auf heute, Aufnahmen mit Datum. */
    private static String musterText(String name) throws Exception {
        byte[] daten = lies(name);
        if (daten == null) return null;
        String s = verschiebe(new String(daten, StandardCharsets.UTF_8), System.currentTimeMillis());
        return name.startsWith("recordings") ? aufnahmenMitDatum(s, TimeZone.getDefault()) : s;
    }

    /** {@code GET /api/demo}: immer beantwortbar, damit die Oberfläche weiß, ob sie das Band zeigt. */
    static String standJson(boolean aktiv) {
        JSONObject j = new JSONObject();
        try {
            j.put("aktiv", aktiv);
            if (aktiv) j.put("hinweis", "Musterdaten");
        } catch (Exception ignored) {
        }
        return j.toString();
    }

    /**
     * Haken in {@code HttpServer} nach den Wächtern. Antwortet mit Musterdaten oder 403 und gibt dann true zurück;
     * false heißt: normal weiter (statische Seiten, lesende Aufrufe ohne Muster).
     */
    static boolean beantworte(String method, String path, String pathOnly, String range, OutputStream out) throws Exception {
        if (PFAD.equals(pathOnly)) {
            HttpResponse.sendJson(out, standJson(aktiv()));
            return true;
        }
        if (!aktiv()) return false;
        String medium = medium(method, pathOnly);
        if (medium != null && sendeMedium(medium, range, out)) return true;
        if (gesperrt(method, pathOnly)) {
            BefehlsLog.grund("demo_profil");
            sendeGesperrt(out);
            return true;
        }
        return sendeMuster(method, path, pathOnly, null, out);
    }

    /** Muster aus {@link #DATEIEN}/{@link #LESE_POSTS} (dazu {@code extra} für GET); false = kein Muster. */
    private static boolean sendeMuster(String method, String path, String pathOnly, Map<String, String> extra,
                                       OutputStream out) throws Exception {
        String query = path != null && path.indexOf('?') >= 0 ? path.substring(path.indexOf('?') + 1) : "";
        String name;
        if ("GET".equals(method) && "/api/recordings/dates".equals(pathOnly)) {
            String a = musterText("recordings.json");
            if (a == null) return false;
            HttpResponse.sendJson(out, tageJson(a));
            return true;
        }
        if ("GET".equals(method) && "/api/recordings".equals(pathOnly)) name = aufnahmenDatei(query);
        else name = datei(method, pathOnly);
        if (name == null && extra != null && "GET".equals(method)) name = extra.get(pathOnly);
        if (name == null) return false;
        String s = musterText(name);
        if (s == null) return false;
        HttpResponse.sendJson(out, s);
        return true;
    }

    private static void sendeGesperrt(OutputStream out) throws Exception {
        HttpResponse.sendJson(out, 403, "{\"ok\":false,\"demo\":true,\"fehler\":\"Musterdaten: nichts wird gespeichert, keine Befehle ans Auto\"}");
    }

    /** Bild oder Video aus dem APK, mit HTTP-Range (Video-Player; die Handy-App lädt in Stücken). */
    private static boolean sendeMedium(String name, String range, OutputStream out) throws Exception {
        byte[] daten = lies(name);
        if (daten == null) return false;
        String typ = name.endsWith(".mp4") ? "video/mp4" : "image/jpeg";
        long[] r;
        try {
            r = HttpResponse.parseSingleByteRange(range, daten.length);
        } catch (IndexOutOfBoundsException e) {
            HttpResponse.sendRangeNotSatisfiable(out, daten.length, "Range Not Satisfiable");
            return true;
        } catch (IllegalArgumentException e) {
            r = null;
        }
        int von = r == null ? 0 : (int) r[0];
        int bis = r == null ? daten.length - 1 : (int) r[1];
        StringBuilder h = new StringBuilder(r == null ? "HTTP/1.1 200 OK\r\n" : "HTTP/1.1 206 Partial Content\r\n");
        h.append("Content-Type: ").append(typ).append("\r\n")
                .append("Content-Length: ").append(bis - von + 1).append("\r\n");
        if (r != null) {
            h.append("Content-Range: bytes ").append(von).append('-').append(bis).append('/').append(daten.length).append("\r\n");
        }
        h.append("Accept-Ranges: bytes\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(daten, von, bis - von + 1);
        out.flush();
        return true;
    }

    /**
     * Haken in {@code HttpServer} vor {@code AuthApiHandler}: Im Demo-Profil wird das Koppeln der Handy-App nur
     * simuliert. Jeder Code gilt, nichts wird gespeichert, es entsteht keine echte Kopplung; die App bekommt den
     * Demo-Schlüssel {@link #GERAET_TOKEN}. Ohne Profil: false, alles wie immer.
     */
    static boolean beantworteKopplung(String method, String pathOnly, OutputStream out) throws Exception {
        if (pathOnly == null || !pathOnly.startsWith("/auth/kopplung") || !aktiv()) return false;
        if ("POST".equals(method) && "/auth/kopplung".equals(pathOnly)) {
            HttpResponse.sendJson(out, kopplungJson().put("success", true).toString());
            return true;
        }
        if ("POST".equals(method) && "/auth/kopplung/anfrage".equals(pathOnly)) {
            HttpResponse.sendJson(out, new JSONObject().put("ok", true).put("id", "demo")
                    .put("gueltigBis", System.currentTimeMillis() + 120_000L).put("abfrageAlleMs", 2000).toString());
            return true;
        }
        if ("GET".equals(method) && pathOnly.startsWith("/auth/kopplung/anfrage/")) {
            HttpResponse.sendJson(out, kopplungJson().put("status", "erlaubt").toString());
            return true;
        }
        return false;
    }

    private static JSONObject kopplungJson() throws Exception {
        return new JSONObject().put("jwt", GERAET_TOKEN).put("kopplungId", GERAET_ID)
                .put("expiresIn", at.greenchili.surfmate.auth.AuthManager.JWT_EXPIRY_SECONDS).put("demo", true);
    }

    /** Ist das die simulierte Kopplung? Nur bei aktivem Profil. */
    static boolean istDemoGeraet(String authHeader) {
        return authHeader != null && authHeader.trim().equals("Bearer " + GERAET_TOKEN) && aktiv();
    }

    /** Schreibende Wege der Handy-App, die im Demo nur bestätigt werden (es passiert nichts). */
    static final Set<String> GERAET_BESTAETIGT = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "/api/sitzung/abmelden", "/api/handy/v1/geraet", "/api/handy/v1/push", "/api/handy/v1/push/aus")));

    /**
     * Haken in {@code HttpServer} vor {@code AuthMiddleware}: Anfragen mit dem Demo-Schlüssel bekommen nur Muster
     * (oder 403) und erreichen nie einen echten Handler. Sitzung erneuern, Gerät melden und Push werden nur bestätigt.
     */
    static boolean beantworteGeraet(String method, String path, String pathOnly, String authHeader, String range,
                                    OutputStream out) throws Exception {
        if (!istDemoGeraet(authHeader)) return false;
        if (PFAD.equals(pathOnly)) {
            HttpResponse.sendJson(out, standJson(true));
            return true;
        }
        if ("POST".equals(method) && "/api/sitzung/erneuern".equals(pathOnly)) {
            HttpResponse.sendJson(out, kopplungJson().put("success", true).toString());
            return true;
        }
        if ("POST".equals(method) && GERAET_BESTAETIGT.contains(pathOnly)) {
            HttpResponse.sendJson(out, "{\"ok\":true,\"success\":true,\"demo\":true}");
            return true;
        }
        String medium = medium(method, pathOnly);
        if (medium != null && sendeMedium(medium, range, out)) return true;
        if (gesperrt(method, pathOnly) || !sendeMuster(method, path, pathOnly, GERAET_DATEIEN, out)) sendeGesperrt(out);
        return true;
    }

    private static byte[] lies(String name) {
        android.content.res.AssetManager assets = at.greenchili.surfmate.daemon.DaemonBootstrap.apkAssets();
        if (assets == null) return null;
        try (InputStream in = assets.open("demo/" + name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] puffer = new byte[8192];
            int n;
            while ((n = in.read(puffer)) != -1) b.write(puffer, 0, n);
            return b.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static String eigenschaft(String name) {
        try {
            return (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class)
                    .invoke(null, name, "");
        } catch (Throwable t) {
            return "";
        }
    }
}
