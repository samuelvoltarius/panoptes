package at.greenchili.surfmate.server;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
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
        DATEIEN = Collections.unmodifiableMap(d);

        Map<String, String> l = new LinkedHashMap<>();
        l.put("/api/navi/umkreis", "navi_umkreis.json");
        LESE_POSTS = Collections.unmodifiableMap(l);
    }

    /** Lesende Aufrufe, die trotzdem etwas auslösen (Kamera, Fahrzeug, Tacho). Im Demo-Profil zu. */
    static final String[] AKTIONS_ANFAENGE = {
            "/api/start/", "/api/view/", "/api/stop/", "/api/stopall", "/api/vehicle/", "/api/vehicle?",
            "/api/quick-controls/", "/api/launcher/v1/vehicle/", "/api/tacho/normal", "/api/tacho/navi",
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
    static boolean beantworte(String method, String pathOnly, OutputStream out) throws Exception {
        if (PFAD.equals(pathOnly)) {
            HttpResponse.sendJson(out, standJson(aktiv()));
            return true;
        }
        if (!aktiv()) return false;
        if (gesperrt(method, pathOnly)) {
            BefehlsLog.grund("demo_profil");
            HttpResponse.sendJson(out, 403, "{\"ok\":false,\"demo\":true,\"fehler\":\"Musterdaten: nichts wird gespeichert, keine Befehle ans Auto\"}");
            return true;
        }
        String name = datei(method, pathOnly);
        if (name == null) return false;
        byte[] daten = lies(name);
        if (daten == null) return false;
        HttpResponse.sendJson(out, verschiebe(new String(daten, StandardCharsets.UTF_8), System.currentTimeMillis()));
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
