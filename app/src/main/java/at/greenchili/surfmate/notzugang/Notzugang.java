package at.greenchili.surfmate.notzugang;

import at.greenchili.surfmate.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 0.1.14 (Kern 7, NOTZUGANG_KONZEPT.md, GO Koordination 22.10.): optionaler Notzugang für die Fernwartung – ein
 * zweiter, eigener tailscaled als Shell-Prozess mit eigenem Knoten, unabhängig von Weg 1 und von der Panoptes-App
 * (überlebt das Abstellen, Panoptes-Updates und sogar die Deinstallation). Nur adb über das Tailnet, kein Web.
 *
 * <p>Regeln: Standard AUS (Regel 1, Einrichtungs-Extra „Notzugang für Fernwartung“). Einrichten nur, wenn Weg 1
 * nachweislich läuft (Regel B: uns nie aussperren), Weg 1 wird nie angefasst. Der Auth-Key des Nutzers geht nur
 * als Datei mit 0600 an den eigenen tailscaled und wird sofort gelöscht – nie in Logs, Config oder Backups.
 * Kein Server von uns ist beteiligt (Regel 2).
 */
public final class Notzugang {

    private static final DaemonLogger logger = DaemonLogger.getInstance("Notzugang");

    public static final String ORDNER = "/data/local/tmp/panoptes-notzugang";
    static final String SKRIPT = ORDNER + "/notzugang.sh";
    static final String STATE = ORDNER + "/state";
    static final String SOCKET = "127.0.0.1:8633";
    public static final String ABSCHNITT = "notzugang";

    private Notzugang() {}

    // ------------------------------------------------------------------ reine Prüfungen

    /** Tailscale-Auth-Key: „tskey-…“, nur [A-Za-z0-9-], sinnvolle Länge. */
    public static boolean keyGueltig(String k) {
        return k != null && k.startsWith("tskey-") && k.length() >= 20 && k.length() <= 200 && k.matches("[A-Za-z0-9-]+");
    }

    /** Knotenname: klein, [a-z0-9-], 1–40 Zeichen, nicht mit „-“ beginnend. */
    public static boolean nameGueltig(String n) {
        return n != null && n.matches("[a-z0-9][a-z0-9-]{0,39}");
    }

    public static boolean an(JSONObject root) {
        JSONObject s = root == null ? null : root.optJSONObject(ABSCHNITT);
        return s != null && s.optBoolean("an", false);
    }

    // ------------------------------------------------------------------ Laufzeit (Daemon, Shell-UID)

    /** Beim Daemon-Start (nach Neustart bzw. Update): nur „start“ aufrufen, wenn eingeschaltet. Idempotent. */
    public static String starten() {
        try {
            if (!an(at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig())) return "aus";
            if (!new File(SKRIPT).isFile()) return "nicht_eingerichtet";
            String r = shell("sh " + SKRIPT + " start", 15).trim();
            logger.info("Notzugang: " + r);
            return r;
        } catch (Throwable t) {
            return "fehler";
        }
    }

    public static JSONObject status() throws Exception {
        JSONObject r = new JSONObject();
        boolean da = new File(SKRIPT).isFile();
        r.put("eingerichtet", da);
        r.put("an", an(at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig()));
        if (!da) return r;
        String st = shell("sh " + SKRIPT + " status", 15).trim();
        r.put("aufpasser", st.contains("aufpasser=ja"));
        r.put("laeuft", st.contains("tailscaled=ja"));
        // 0.1.15: ohne serve --tcp 5555 gibt es über den Notzugang kein adb (kein Loopback-Forward im Build)
        r.put("adbServe", st.contains("adb_serve=ja"));
        String js = shell(ORDNER + "/bin/tailscale --socket " + SOCKET + " status --json 2>/dev/null", 10);
        try {
            JSONObject s = new JSONObject(js);
            r.put("zustand", s.optString("BackendState", ""));
            JSONObject self = s.optJSONObject("Self");
            JSONArray ips = self == null ? null : self.optJSONArray("TailscaleIPs");
            if (ips != null && ips.length() > 0) r.put("ip", ips.optString(0));
            if (self != null) {
                r.put("name", self.optString("HostName", ""));
                schluessel(self, System.currentTimeMillis(), r);
            }
        } catch (Exception ignored) {
            r.put("zustand", "unbekannt");
        }
        return r;
    }

    /**
     * 0.1.15: Tag bzw. Key-Ablauf des eigenen Knotens aus {@code status --json} → Self. Ein getaggter Knoten
     * ({@code Tags}, z. B. tag:notzugang) hat standardmäßig keinen Key-Ablauf; ein Knoten ohne Tag gehört dem
     * Nutzer und fällt nach {@code KeyExpiry} aus dem Tailnet, bis jemand ihn neu anmeldet – genau dann, wenn man
     * den Notzugang braucht. Nur anzeigen, nie selbst etwas am Tailnet ändern.
     * Felder: tags[], getaggt, keyAblauf (ISO-Zeit oder null = läuft nie ab), keyAblaufTage (abgerundet).
     */
    static void schluessel(JSONObject self, long jetztMs, JSONObject r) throws Exception {
        JSONArray tags = self.optJSONArray("Tags");
        JSONArray t = new JSONArray();
        if (tags != null) {
            for (int i = 0; i < tags.length(); i++) {
                String s = tags.optString(i, "");
                if (!s.isEmpty()) t.put(s);
            }
        }
        r.put("tags", t);
        r.put("getaggt", t.length() > 0);
        String ab = self.optString("KeyExpiry", "");
        // Go schreibt den Nullwert als 0001-01-01T00:00:00Z – das heißt „kein Ablauf“
        if (ab.isEmpty() || ab.startsWith("0001-")) {
            r.put("keyAblauf", JSONObject.NULL);
            return;
        }
        r.put("keyAblauf", ab);
        try {
            long ms = java.time.Instant.parse(ab).toEpochMilli();
            r.put("keyAblaufTage", Math.floorDiv(ms - jetztMs, 86_400_000L));
        } catch (Exception ignored) {
            // unbekanntes Format: Zeit bleibt sichtbar, Tage fehlen
        }
    }

    /**
     * Einrichten: Dateien anlegen, einschalten, Knoten mit dem Auth-Key anmelden.
     *
     * @return {success, fehler?, status}
     */
    public static synchronized JSONObject einrichten(String authKey, String name) throws Exception {
        if (!keyGueltig(authKey)) return fehler("key");
        if (!nameGueltig(name)) return fehler("name");
        // Regel B: Weg 1 muss laufen, sonst könnten wir uns beim Einrichten aussperren
        String weg1 = shell(at.greenchili.surfmate.launcher.TailscaledProzesse.pidsSkript(
                at.greenchili.surfmate.launcher.TailscaledProzesse.PANOPTES_STATEDIR), 10).trim();
        if (weg1.isEmpty()) return fehler("weg1_fehlt");
        String inst = installieren();
        if (inst != null) return fehler(inst);
        at.greenchili.surfmate.config.UnifiedConfigManager.updateSection(ABSCHNITT, new JSONObject().put("an", true));
        shell("sh " + SKRIPT + " start", 15);
        // warten, bis der eigene tailscaled antwortet
        boolean bereit = false;
        for (int i = 0; i < 20 && !bereit; i++) {
            bereit = shell(ORDNER + "/bin/tailscale --socket " + SOCKET + " status --json 2>/dev/null", 5).contains("BackendState");
            if (!bereit) Thread.sleep(1000);
        }
        if (!bereit) return fehler("startet_nicht");
        File key = new File(ORDNER, ".authkey");
        try {
            Files.write(key.toPath(), authKey.getBytes(StandardCharsets.UTF_8));
            shell("chmod 600 " + key.getAbsolutePath(), 5);
            String out = shell(ORDNER + "/bin/tailscale --socket " + SOCKET + " up --auth-key=file:" + key.getAbsolutePath()
                    + " --hostname " + name + " --timeout 60s; echo rc=$?", 75);
            boolean ok = out.contains("rc=0");
            logger.info("Notzugang angemeldet: " + ok);   // nie den Key oder die Ausgabe mit Key loggen
            if (!ok) return fehler("anmeldung");
        } finally {
            //noinspection ResultOfMethodCallIgnored
            key.delete();
            shell("rm -f " + key.getAbsolutePath(), 5);
        }
        // 0.1.15: adb-Weiterleitung 5555 setzen und zurücklesen (idempotent; der Aufpasser holt sie sonst nach).
        // Der Knoten ist angemeldet – kein Fehler, sonst richtet der Nutzer mit neuem Key einen zweiten Knoten ein.
        boolean serve = serveOk(shell("sh " + SKRIPT + " serve", 30));
        logger.info("Notzugang adb-Weiterleitung: " + serve);
        JSONObject r = new JSONObject().put("success", true).put("adbServe", serve);
        if (!serve) r.put("warnung", "adb_serve");
        return r.put("status", status());
    }

    static boolean serveOk(String ausgabe) {
        return ausgabe != null && ausgabe.contains("serve=ok");
    }

    public static JSONObject einschalten() throws Exception {
        if (!new File(SKRIPT).isFile()) return fehler("nicht_eingerichtet");
        at.greenchili.surfmate.config.UnifiedConfigManager.updateSection(ABSCHNITT, new JSONObject().put("an", true));
        shell("sh " + SKRIPT + " start", 15);
        return new JSONObject().put("success", true).put("status", status());
    }

    public static JSONObject ausschalten() throws Exception {
        at.greenchili.surfmate.config.UnifiedConfigManager.updateSection(ABSCHNITT, new JSONObject().put("an", false));
        if (new File(SKRIPT).isFile()) shell("sh " + SKRIPT + " stop", 15);
        return new JSONObject().put("success", true).put("status", status());
    }

    /** Dateien anlegen: Binärdatei aus dem APK (einmalig bzw. beim Einrichten), Skript aus den Assets. */
    static String installieren() {
        try {
            android.content.Context c = at.greenchili.surfmate.daemon.CameraDaemon.getAppContext();
            if (c == null) return "kein_kontext";
            String lib = c.getApplicationInfo().nativeLibraryDir + "/libtailscale.so";
            if (!new File(lib).isFile()) return "keine_binaerdatei";
            String r = shell("mkdir -p " + ORDNER + "/bin " + STATE + " && cp -f " + lib + " " + ORDNER + "/bin/tailscale"
                    + " && chmod 755 " + ORDNER + "/bin/tailscale && ln -sf " + ORDNER + "/bin/tailscale " + ORDNER
                    + "/bin/tailscaled && chmod 700 " + STATE + " && echo ok", 30);
            if (!r.contains("ok")) return "kopieren";
            try (InputStream in = c.getAssets().open("notzugang/notzugang.sh")) {
                Files.write(new File(SKRIPT).toPath(), lesen(in));
            }
            shell("chmod 755 " + SKRIPT, 5);
            return null;
        } catch (Throwable t) {
            logger.warn("Notzugang installieren: " + t.getMessage());
            return "installieren";
        }
    }

    private static JSONObject fehler(String code) throws Exception {
        return new JSONObject().put("success", false).put("error", code);
    }

    /**
     * 0.1.14.1: {@code InputStream.readAllBytes()} gibt es auf Android erst ab API 33 – am Auto (Android 10, API 29)
     * NoSuchMethodError. Eigene Lese-Schleife, minSdk-fest.
     */
    static byte[] lesen(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    static String shell(String cmd, int sekunden) {
        try {
            Process p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            byte[] b;
            try (InputStream in = p.getInputStream()) {
                b = lesen(in);
            }
            if (!p.waitFor(sekunden, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly();
            return new String(b, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
