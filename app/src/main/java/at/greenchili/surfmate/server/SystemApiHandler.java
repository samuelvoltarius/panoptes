package at.greenchili.surfmate.server;

import at.greenchili.surfmate.config.UnifiedConfigManager;
import at.greenchili.surfmate.logging.DaemonLogger;
import at.greenchili.surfmate.ui.model.DaemonType;
import at.greenchili.surfmate.util.DaemonStorage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * System settings that used to exist only on the head-unit screen: which optional
 * daemons and tunnels run, and the Tailscale node itself.
 *
 * <p>Runs inside the camera daemon (shell uid), so it can read the process table,
 * write the cross-uid stop sentinels and talk to tailscaled's local socket directly.
 * The desired on/off state is written to the unified-config section
 * {@code daemons}; the app's 30 s health check reads that section and starts what is
 * enabled. Switching something off is applied here immediately (sentinel + kill), so
 * it does not depend on the app process being alive.
 *
 * <p>Routes (all behind the normal web login; not reachable from automations):
 * <ul>
 *   <li>{@code GET  /api/system/status} — daemons with enabled/running/stopped</li>
 *   <li>{@code POST /api/system/daemon} — {@code {"type":"TAILSCALE_TUNNEL","enabled":true}}</li>
 *   <li>{@code GET  /api/system/tailscale} — backend state, IPs, host and DNS name</li>
 *   <li>{@code POST /api/system/tailscale/hostname} — {@code {"hostname":"surfmate"}}</li>
 *   <li>{@code GET  /api/system/capabilities} — was dieses Auto kann (Tacho-Anzeige, Head-up-Anzeige)</li>
 *   <li>{@code GET  /api/system/profile} — Anrede und Fahrzeugname für die Kopfzeile</li>
 *   <li>{@code POST /api/system/profile} — {@code {"displayName":"…","vehicleName":"…"}}</li>
 * </ul>
 */
public class SystemApiHandler {

    private static final String TAG = "SystemApiHandler";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final String DAEMONS_SECTION = "daemons";
    private static final String TAILSCALE_CLI =
            DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/.tailscale") + "/tailscale";
    private static final String TAILSCALE_SOCKET = "127.0.0.1:8632";
    private static final Pattern HOSTNAME = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");

    /** Daemons the web may switch. The camera daemon hosts this very server, so it is not one of them. */
    private static final DaemonType[] SWITCHABLE = {
            DaemonType.TAILSCALE_TUNNEL,
            DaemonType.TELEGRAM_DAEMON,
            DaemonType.WIREGUARD_TUNNEL,
    };

    /** Shown read-only, so the page gives the whole picture. */
    private static final DaemonType[] CORE = {
            DaemonType.CAMERA_DAEMON,
            DaemonType.SENTRY_DAEMON,
            DaemonType.ACC_SENTRY_DAEMON,
    };

    /**
     * Fallback für Typen ohne Eintrag im Abschnitt: leer (Koordination 0.1.9: Tailscale bei Neuinstallation aus).
     * Die App schreibt beim ersten Start den wirksamen Zustand fest (Bestand an, PreferencesManager), danach
     * steht jeder schaltbare Typ im Abschnitt.
     */
    private static final Set<DaemonType> DEFAULT_ON = new HashSet<>();

    /** 0.1.13: aktueller Wert, gesicherter Ursprung, Doze-Zustand (nur lesen). */
    static JSONObject eingriffeStand() throws Exception {
        String aktuell = shellText("settings get global stay_on_while_plugged_in").trim();
        String ursprung = "", wlanUrsprung = "";
        try {
            for (String z : new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                    at.greenchili.surfmate.power.SystemEingriffe.URSPRUNG_DATEI)), "UTF-8").split("\n")) {
                if (z.startsWith("global.stay_on_while_plugged_in=") && ursprung.isEmpty()) ursprung = z.substring(z.indexOf('=') + 1).trim();
                if (z.startsWith("global.wifi_on=") && wlanUrsprung.isEmpty()) wlanUrsprung = z.substring(z.indexOf('=') + 1).trim();
            }
        } catch (Exception ignored) {
        }
        String doze = shellText("dumpsys deviceidle enabled 2>/dev/null").trim();
        return new JSONObject().put("stayOnWhilePluggedIn", aktuell).put("stayOnUrsprung", ursprung.isEmpty() ? JSONObject.NULL : ursprung)
                .put("stayOnVonPanoptes", "7".equals(aktuell))
                .put("doze", doze.isEmpty() ? JSONObject.NULL : doze)
                .put("wifiOn", nullWennLeer(shellText("settings get global wifi_on").trim()))
                .put("wifiUrsprung", nullWennLeer(wlanUrsprung))
                .put("wifiKeepAliveAus", at.greenchili.surfmate.config.UnifiedConfigManager.isWifiKeepAliveSuppressed())
                .put("weg2Waechter", at.greenchili.surfmate.power.Weg2Waechter.stand());
    }

    private static Object nullWennLeer(String s) {
        return s == null || s.isEmpty() ? JSONObject.NULL : s;
    }

    private static String shellText(String cmd) {
        try {
            Process p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            byte[] b = new byte[4096];
            int n = p.getInputStream().read(b);
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly();
            return n > 0 ? new String(b, 0, n, "UTF-8") : "";
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean handle(String method, String path, String body, OutputStream out)
            throws Exception {
        // 0.1.13: globale Eingriffe – Stand und ausdrückliches Zurücksetzen (nicht über die Kopplung)
        if (path.equals("/api/system/eingriffe") && method.equals("GET")) {
            HttpResponse.sendJson(out, eingriffeStand().toString());
            return true;
        }
        if (path.equals("/api/system/eingriffe/zuruecksetzen") && method.equals("POST")) {
            if (AuthMiddleware.requestKopplungsId() != null) {
                HttpResponse.sendJson(out, 403, "{\"success\":false,\"error\":\"nur_im_auto\"}");
                return true;
            }
            String r = shellText(at.greenchili.surfmate.power.SystemEingriffe.zuruecksetzenSkript());
            HttpResponse.sendJson(out, eingriffeStand().put("success", r.contains("ok")).toString());
            return true;
        }
        if (path.equals("/api/system/status") && method.equals("GET")) {
            sendStatus(out);
            return true;
        }
        if (path.equals("/api/system/daemon") && method.equals("POST")) {
            setDaemon(body, out);
            return true;
        }
        if (path.equals("/api/system/tailscale") && method.equals("GET")) {
            HttpResponse.sendJson(out, tailscaleStatus().toString());
            return true;
        }
        if (path.equals("/api/system/tailscale/hostname") && method.equals("POST")) {
            setHostname(body, out);
            return true;
        }
        if (path.equals("/api/system/capabilities") && method.equals("GET")) {
            HttpResponse.sendJson(out, capabilities().toString());
            return true;
        }
        if (path.equals("/api/system/profile") && method.equals("GET")) {
            HttpResponse.sendJson(out, profile().toString());
            return true;
        }
        if (path.equals("/api/system/profile") && method.equals("POST")) {
            setProfile(body, out);
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- status

    private static void sendStatus(OutputStream out) throws Exception {
        Set<String> running = runningProcessNames();
        JSONObject desired = UnifiedConfigManager.forceReload().optJSONObject(DAEMONS_SECTION);

        JSONArray list = new JSONArray();
        for (DaemonType t : SWITCHABLE) list.put(describe(t, running, desired, true));
        for (DaemonType t : CORE) list.put(describe(t, running, desired, false));

        JSONObject res = new JSONObject();
        res.put("success", true);
        res.put("daemons", list);
        HttpResponse.sendJson(out, res.toString());
    }

    private static JSONObject describe(DaemonType t, Set<String> running, JSONObject desired,
                                       boolean switchable) throws Exception {
        JSONObject o = new JSONObject();
        o.put("type", t.name());
        o.put("name", t.getDisplayName());
        o.put("process", t.getProcessName());
        o.put("switchable", switchable);
        o.put("running", running.contains(t.getProcessName()));
        o.put("stopped", new File(t.getSentinelPath()).exists());
        if (switchable) {
            boolean enabled = desired != null && desired.has(t.name())
                    ? desired.optBoolean(t.name(), false)
                    : DEFAULT_ON.contains(t);
            o.put("enabled", enabled);
        }
        return o;
    }

    // ---------------------------------------------------------------- switch

    private static void setDaemon(String body, OutputStream out) throws Exception {
        JSONObject req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
        DaemonType type = switchable(req.optString("type", ""));
        if (type == null) {
            HttpResponse.sendJson(out, 400, error("unknown or non-switchable daemon").toString());
            return;
        }
        if (!req.has("enabled")) {
            HttpResponse.sendJson(out, 400, error("missing 'enabled'").toString());
            return;
        }
        boolean enabled = req.optBoolean("enabled");

        // Turning Tailscale off from a page that is most likely reached over Tailscale
        // cuts the owner off the car. Require an explicit second confirmation.
        if (!enabled && type == DaemonType.TAILSCALE_TUNNEL && !req.optBoolean("confirm", false)) {
            JSONObject res = error("confirm required");
            res.put("needsConfirm", true);
            HttpResponse.sendJson(out, 409, res.toString());
            return;
        }

        boolean saved = UnifiedConfigManager.updateSection(DAEMONS_SECTION,
                new JSONObject().put(type.name(), enabled));
        File sentinel = new File(type.getSentinelPath());
        if (enabled) {
            // Lift a previous stop; the app's health check starts it within ~30 s.
            if (sentinel.exists() && !sentinel.delete()) {
                run("rm -f '" + type.getSentinelPath() + "'", 5);
            }
        } else {
            try (FileWriter w = new FileWriter(sentinel)) {
                w.write("user stop via web\n");
            }
            sentinel.setReadable(true, false);
            sentinel.setWritable(true, false);
            run("pkill -x '" + type.getProcessName() + "'", 5);
        }
        logger.info(TAG + ": " + type.name() + " -> " + (enabled ? "on" : "off") + " (saved=" + saved + ")");

        JSONObject res = new JSONObject();
        res.put("success", saved);
        res.put("type", type.name());
        res.put("enabled", enabled);
        res.put("note", enabled ? "starts within about 30 seconds" : "stopped");
        HttpResponse.sendJson(out, res.toString());
    }

    private static DaemonType switchable(String name) {
        for (DaemonType t : SWITCHABLE) if (t.name().equals(name)) return t;
        return null;
    }

    // ------------------------------------------------------------- tailscale

    private static JSONObject tailscaleStatus() {
        JSONObject res = new JSONObject();
        try {
            String raw = run("'" + TAILSCALE_CLI + "' --socket " + TAILSCALE_SOCKET + " status --json", 10);
            int start = raw.indexOf('{');
            if (start < 0) {
                res.put("success", false);
                res.put("error", raw.trim().isEmpty() ? "tailscaled not running" : raw.trim());
                return res;
            }
            JSONObject st = new JSONObject(raw.substring(start));
            JSONObject self = st.optJSONObject("Self");
            res.put("success", true);
            res.put("backendState", st.optString("BackendState", "unknown"));
            res.put("authUrl", st.optString("AuthURL", ""));
            if (self != null) {
                res.put("hostName", self.optString("HostName", ""));
                res.put("dnsName", self.optString("DNSName", "").replaceAll("\\.$", ""));
                res.put("online", self.optBoolean("Online", false));
                res.put("ips", self.optJSONArray("TailscaleIPs") != null
                        ? self.optJSONArray("TailscaleIPs") : new JSONArray());
            }
            JSONObject peers = st.optJSONObject("Peer");
            res.put("peers", peers != null ? peers.length() : 0);
        } catch (Exception e) {
            try {
                res.put("success", false);
                res.put("error", e.getMessage());
            } catch (Exception ignored) { }
        }
        return res;
    }

    private static void setHostname(String body, OutputStream out) throws Exception {
        JSONObject req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
        String name = req.optString("hostname", "").trim().toLowerCase();
        if (!HOSTNAME.matcher(name).matches()) {
            HttpResponse.sendJson(out, 400, error("hostname: a-z, 0-9 and '-', max 63").toString());
            return;
        }
        String result = run("'" + TAILSCALE_CLI + "' --socket " + TAILSCALE_SOCKET
                + " set --hostname " + name + " 2>&1; echo EXIT=$?", 15);
        boolean ok = result.contains("EXIT=0");
        logger.info(TAG + ": tailscale hostname -> " + name + " ok=" + ok);
        JSONObject res = new JSONObject();
        res.put("success", ok);
        res.put("hostname", name);
        if (!ok) res.put("error", result.replace("EXIT=", "exit ").trim());
        HttpResponse.sendJson(out, res.toString());
    }

    // --------------------------------------------------------------- helpers

    private static Set<String> runningProcessNames() {
        Set<String> names = new HashSet<>();
        for (String line : run("ps -A -o NAME", 8).split("\n")) {
            String n = line.trim();
            if (!n.isEmpty()) names.add(n);
        }
        return names;
    }

    private static String run(String cmd, int timeoutSec) {
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor(timeoutSec, TimeUnit.SECONDS);
            return sb.toString();
        } catch (Exception e) {
            logger.warn(TAG + ": command failed: " + e.getMessage());
            return "";
        } finally {
            if (p != null) p.destroy();
        }
    }

    // ---------------------------------------------------------------- capabilities

    // Eine Stelle für „kann dieses Auto das?“ – Logik in VehicleCapabilities, damit der
    // Regel-Katalog dieselbe Antwort nutzt. Die Oberfläche blendet Tacho/HUD nur bei true ein.
    private static JSONObject capabilities() {
        JSONObject c = VehicleCapabilities.get();
        // 0.1.25 Brücke: Handy schickt v2/LIVE nur, wenn das Auto es kann (BRUECKE_MEDIEN §2)
        try {
            c = new JSONObject(c.toString());
            c.put("bruecke", new JSONObject().put("innen", 2).put("live", true).put("medienKanal", true));
            // 0.1.26 Klima-Sollwert als Stufe 1–17: Cockpit/Handy senden „stufe“ nur, wenn sie das hier sehen.
            // anzeige: „stufe“ nur bei belegten Modellen (Dolphin Surf), sonst „grad“ wie bisher
            c.put("klima", new JSONObject().put("stufe", 1)
                    .put("anzeige", at.greenchili.surfmate.byd.KlimaStufe.stufenModell() ? "stufe" : "grad")
                    // 0.1.28 (P2): Cloud-Skala gemessen? Bis dahin zeigt der Cloud-Weg nur „Heizen“/„Kühlen“
                    .put("cloudSkala", at.greenchili.surfmate.byd.KlimaStufe.cloudSkalaBelegt() ? "gemessen" : "annahme"));
            // 0.1.26.1 Update von außen (HANDY_API_VERTRAG §8): GET /api/handy/v1/update + Aktion update_starten
            c.put("update", new JSONObject().put("fern", 1));
        } catch (Exception ignored) { }
        // 0.1.26.1 (HANDY_API_VERTRAG §4): öffentlicher VAPID-Schlüssel für UnifiedPush mit FCM-Verteiler.
        // Eigener Block, damit ein Fehler oben das Feld nicht verschluckt. Nie der private Teil.
        try {
            String vapid = NotificationApiHandler.vapidOeffentlich();
            if (vapid != null) c.put("push", new JSONObject().put("vapid", vapid));
        } catch (Exception ignored) { }
        return c;
    }

    // guardStop: Einstellungen nur noch über /api/einstellungen (Schema); /api/system/guardstop
    // entfernt, nachdem 0.1.5 am Auto bestätigt war (Koordination 21.10.2026).

    // ---------------------------------------------------------------- profile

    private static final String PROFILE_SECTION = "profile";
    private static final int PROFILE_MAX = 40;

    /** Anrede und Fahrzeugname sind Einstellungen der Nutzerin/des Nutzers, nie fest im Code. */
    private static JSONObject profile() {
        JSONObject res = new JSONObject();
        try {
            JSONObject cfg = UnifiedConfigManager.loadConfig();
            JSONObject p = cfg.optJSONObject(PROFILE_SECTION);
            JSONObject v = cfg.optJSONObject("vehicle");
            res.put("success", true);
            res.put("displayName", p != null ? p.optString("displayName", "") : "");
            res.put("vehicleName", p != null ? p.optString("vehicleName", "") : "");
            // 0.1.21: nur ein wirklich gewähltes Modell, sonst "" + modellGewaehlt=false (kein geratenes „seal“)
            String gewaehlt = at.greenchili.surfmate.config.VehicleModelSelection.gewaehlt(v);
            res.put("modelId", gewaehlt != null ? gewaehlt : "");
            res.put("modellGewaehlt", gewaehlt != null);
            res.put("color", v != null ? v.optString("color", "") : "");
            if (v != null && v.has("nominalKwh") && !v.isNull("nominalKwh")) {
                res.put("batteryKwh", v.optDouble("nominalKwh", 0));
            }
        } catch (Exception e) {
            return error("profile unavailable");
        }
        return res;
    }

    private static void setProfile(String body, OutputStream out) throws Exception {
        JSONObject req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
        JSONObject current = UnifiedConfigManager.loadConfig().optJSONObject(PROFILE_SECTION);
        JSONObject next = current != null ? new JSONObject(current.toString()) : new JSONObject();
        String[] keys = {"displayName", "vehicleName"};
        for (String k : keys) {
            if (!req.has(k)) continue;
            String val = req.optString(k, "").replaceAll("[\\p{Cntrl}<>]", "").trim();
            if (val.length() > PROFILE_MAX) val = val.substring(0, PROFILE_MAX);
            next.put(k, val);
        }
        if (!UnifiedConfigManager.updateSection(PROFILE_SECTION, next)) {
            HttpResponse.sendJson(out, 500, error("could not save").toString());
            return;
        }
        HttpResponse.sendJson(out, profile().toString());
    }

    private static JSONObject error(String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("success", false);
            o.put("error", msg);
        } catch (Exception ignored) { }
        return o;
    }
}
