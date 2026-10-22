package at.greenchili.surfmate.einrichtung;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Selbsttest am Ende der Einrichtung (0.1.10 Teil G, SETUP_WIZARD Schritt 15).
 *
 * <p>Je Punkt {@code ok | fehlt | entfaellt | ungeprueft} plus Ziel-Schritt-ID für „Beheben“.
 * <b>Nur echte Prüfungen, nichts behaupten:</b> wo der Daemon es gerade nicht messen kann (Kamera ruht,
 * kein Internet für den Planer, Tailscale-Status nicht lesbar), steht {@code ungeprueft} – nie {@code ok}.
 * Ein übersprungener (offen/später) Kamera-Schritt ist immer {@code fehlt}.
 */
public final class Selbsttest {

    public static final String OK = "ok", FEHLT = "fehlt", ENTFAELLT = "entfaellt", UNGEPRUEFT = "ungeprueft";

    static final long BILD_MAX_MS = 60_000L;
    static final double SPEICHER_MIN_GB = 2.0;
    static final long PLANER_FRISCH_MS = 7L * 24 * 3_600_000L;

    private Selbsttest() {}

    /** Momentaufnahme (rein, für Tests). null = nicht messbar. */
    public static final class Fakten {
        public long jetzt;
        public Boolean autostart;
        public boolean autostartVerwaltungDa = true;
        public String kameraSchritt;        // Status des Einrichtungsschritts „kameras“
        public Integer kameraAnzahl;
        public long letztesBildMs;          // 0 = kein Bild bekannt
        public Double speicherFreiGb;
        public Boolean internet;
        public boolean planerSchluessel;
        public String planerSchritt;
        public int planerLetzterCode;       // 0 = noch keine Antwort mit Schlüssel
        public long planerLetzteZeit;
        public boolean fernzugangEingerichtet;
        public String fernzugangSchritt;
        public Boolean fernzugangOnline;
        public String waechterStatus;       // TriggerModus.status(…)
        /** H3: ok | fehlt | entfaellt | ungeprueft (siehe {@link #projektion}). */
        public String projektion = ENTFAELLT;
    }

    public static JSONObject bewerte(Fakten f) throws Exception {
        JSONArray p = new JSONArray();

        // Autostart (BYD setzt ihn bei jedem Update zurück)
        String a = !f.autostartVerwaltungDa ? ENTFAELLT
                : f.autostart == null ? UNGEPRUEFT : f.autostart ? OK : FEHLT;
        p.put(punkt("autostart", a, "autostart"));

        // Dienste: dieser Test läuft im Daemon – antwortet er, läuft der Dienst
        p.put(punkt("dienste", OK, "usb_freigabe"));

        // Kameras
        JSONObject k;
        if ("offen".equals(f.kameraSchritt) || "spaeter".equals(f.kameraSchritt)) {
            k = punkt("kameras", FEHLT, "kameras");
        } else if ("entfaellt".equals(f.kameraSchritt) || (f.kameraAnzahl != null && f.kameraAnzahl == 0)) {
            k = punkt("kameras", ENTFAELLT, "kameras");
        } else if (f.letztesBildMs > 0 && f.jetzt - f.letztesBildMs >= 0 && f.jetzt - f.letztesBildMs <= BILD_MAX_MS) {
            k = punkt("kameras", OK, "kameras");
            if (f.kameraAnzahl != null) k.put("wert", f.kameraAnzahl);
        } else {
            k = punkt("kameras", UNGEPRUEFT, "kameras");   // Kamera liefert gerade nicht (z. B. ruht): nicht behaupten
        }
        p.put(k);

        // Speicher
        JSONObject sp;
        if (f.speicherFreiGb == null) {
            sp = punkt("speicher", UNGEPRUEFT, null);
        } else {
            sp = punkt("speicher", f.speicherFreiGb >= SPEICHER_MIN_GB ? OK : FEHLT, null);
            sp.put("wert", Math.round(f.speicherFreiGb * 10) / 10.0);
        }
        p.put(sp);

        // Internet
        p.put(punkt("internet", f.internet == null ? UNGEPRUEFT : f.internet ? OK : FEHLT, null));

        // Routenplaner
        String pl;
        if (!f.planerSchluessel) {
            pl = "erledigt".equals(f.planerSchritt) ? FEHLT : ENTFAELLT;
        } else if (!Boolean.TRUE.equals(f.internet)) {
            pl = UNGEPRUEFT;
        } else if (f.planerLetzterCode == 401 || f.planerLetzterCode == 403) {
            pl = FEHLT;
        } else if (f.planerLetzterCode >= 200 && f.planerLetzterCode < 300
                && f.jetzt - f.planerLetzteZeit <= PLANER_FRISCH_MS) {
            pl = OK;
        } else {
            pl = UNGEPRUEFT;
        }
        p.put(punkt("planer", pl, "extra_planer"));

        // Fernzugang (Tailscale)
        String fz;
        if (!f.fernzugangEingerichtet) {
            fz = "erledigt".equals(f.fernzugangSchritt) ? FEHLT : ENTFAELLT;
        } else if (f.fernzugangOnline == null || Boolean.FALSE.equals(f.internet)) {
            fz = UNGEPRUEFT;
        } else {
            fz = f.fernzugangOnline ? OK : FEHLT;
        }
        p.put(punkt("fernzugang", fz, "extra_fernzugang"));

        // Wächter-Modus
        JSONObject w;
        if (f.waechterStatus == null || "nicht_gewaehlt".equals(f.waechterStatus)) {
            w = punkt("waechter", FEHLT, "waechter");
        } else {
            w = punkt("waechter", OK, "waechter");
            w.put("wert", f.waechterStatus);
        }
        p.put(w);

        // H3: Hinweise über CarPlay/AA (nur wenn dort schon ein Hinweis kam; Verweis auf eigene Angabe / Ton)
        JSONObject pj = punkt("projektion", f.projektion == null ? ENTFAELLT : f.projektion, null);
        pj.put("einstellungen", new JSONArray().put("faehigkeiten.ueberCarPlay")
                .put("faehigkeiten.ueberAndroidAuto").put("speedcam.ton"));
        p.put(pj);

        boolean fehlt = false;
        for (int i = 0; i < p.length(); i++) fehlt |= FEHLT.equals(p.getJSONObject(i).getString("status"));
        return new JSONObject().put("zeit", f.jetzt).put("ok", !fehlt).put("punkte", p);
    }

    private static JSONObject punkt(String id, String status, String ziel) throws Exception {
        JSONObject o = new JSONObject().put("id", id).put("status", status);
        if (ziel != null) o.put("ziel", ziel);
        return o;
    }

    // ================================================================ Planer: letzte echte Antwort mit Schlüssel

    private static volatile int planerCode;
    private static volatile long planerZeit;

    /** Von NaviApiHandler nach jedem Planer-Aufruf (nur mit Schlüssel zählt er). */
    public static void planerAntwort(boolean mitSchluessel, int code, long jetzt) {
        if (!mitSchluessel) return;
        planerCode = code;
        planerZeit = jetzt;
    }

    /** Nur für Tests: die gemerkte Planer-Antwort. */
    static Fakten messenPlanerFuerTest() {
        Fakten f = new Fakten();
        f.planerLetzterCode = planerCode;
        f.planerLetzteZeit = planerZeit;
        return f;
    }

    // ================================================================ Messen im Daemon

    /**
     * Sammelt die Fakten im Daemon. {@code einrichtung} = GET-Sicht (Schrittstatus, werte).
     * Jede Quelle einzeln abgesichert; was scheitert, bleibt null (= ungeprüft).
     */
    public static Fakten messen(JSONObject root, JSONObject einrichtung, JSONObject faehigkeiten) {
        Fakten f = new Fakten();
        f.jetzt = System.currentTimeMillis();
        JSONArray schritte = einrichtung == null ? null : einrichtung.optJSONArray("schritte");
        f.kameraSchritt = status(schritte, "kameras");
        f.planerSchritt = status(schritte, "extra_planer");
        f.fernzugangSchritt = status(schritte, "extra_fernzugang");
        try {
            JSONObject kam = faehigkeiten == null ? null : faehigkeiten.optJSONObject("kameras");
            if (kam != null && kam.has("anzahl")) f.kameraAnzahl = kam.getInt("anzahl");
        } catch (Exception ignored) {
        }
        try {
            at.greenchili.surfmate.surveillance.GpuSurveillancePipeline p =
                    at.greenchili.surfmate.daemon.CameraDaemon.getGpuPipeline();
            if (p != null && p.getCamera() != null) f.letztesBildMs = p.getCamera().getLastFrameTime();
        } catch (Throwable ignored) {
        }
        try {
            File d = at.greenchili.surfmate.storage.StorageManager.getInstance().getRecordingsDir();
            if (d != null && d.exists()) f.speicherFreiGb = d.getUsableSpace() / 1e9;
        } catch (Throwable ignored) {
        }
        f.internet = internet(run("dumpsys connectivity 2>/dev/null | grep -E 'Active default network|NetworkAgentInfo' | head -20", 5));
        try {
            f.planerSchluessel = at.greenchili.surfmate.planer.PlanerSchluessel.kopf() != null;
        } catch (Throwable ignored) {
        }
        f.planerLetzterCode = planerCode;
        f.planerLetzteZeit = planerZeit;
        try {
            // gleicher, nur lesender Aufruf wie SystemApiHandler.tailscaleStatus()
            String cli = at.greenchili.surfmate.util.DaemonStorage.rebase(
                    "/data/user_de/0/com.android.shell/surfmate/.tailscale") + "/tailscale";
            String ts = new File(cli).exists()
                    ? run("'" + cli + "' --socket 127.0.0.1:8632 status --json 2>/dev/null", 6) : "";
            fernzugang(f, ts);
        } catch (Throwable ignored) {
        }
        try {
            f.projektion = projektion(root, faehigkeiten,
                    at.greenchili.surfmate.blitzer.BlitzerDienst.ton(root == null ? null : root.optJSONObject("speedcam")));
        } catch (Throwable ignored) {
        }
        JSONObject sv = root == null ? null : root.optJSONObject("surveillance");
        f.waechterStatus = at.greenchili.surfmate.einstellungen.TriggerModus.status(
                sv == null ? null : sv.optString("triggerMode", null));
        // Autostart: gibt es die BYD-Verwaltung? Bestätigung (Deploy-Marke bzw. App-Rückmeldung) für diese Installation?
        String pfad = run("pm path com.byd.appstartmanagement 2>/dev/null", 3);
        f.autostartVerwaltungDa = pfad != null && pfad.contains("package:");
        if (f.autostartVerwaltungDa) {
            String inst = run("dumpsys package at.greenchili.surfmate 2>/dev/null | grep -m1 lastUpdateTime", 5);
            String marke = run("settings get global panoptes_autostart_ok 2>/dev/null", 3);
            JSONObject werte = einrichtung == null ? null : einrichtung.optJSONObject("werte");
            JSONObject app = werte == null ? null : werte.optJSONObject("autostart");
            f.autostart = autostart(inst, marke, app, TimeZone.getDefault());
        }
        return f;
    }

    /**
     * Rein (H3): nie eine Projektion mit Hinweis erlebt → entfaellt. Sonst je erlebter Art: Ton an oder Sichtbarkeit
     * belegt (ja) → ok; eigene Angabe „nein“ und Ton aus → fehlt (Hinweis nicht wahrnehmbar); sonst ungeprueft.
     */
    static String projektion(JSONObject root, JSONObject faehigkeiten, String ton) {
        JSONObject e = root == null ? null : root.optJSONObject("einrichtung");
        JSONObject g = e == null ? null : e.optJSONObject("projektionUnbekannt");
        if (g == null || g.length() == 0) return ENTFAELLT;
        if (!"aus".equals(ton)) return OK;
        JSONObject u = faehigkeiten == null ? null : faehigkeiten.optJSONObject("ueberProjektion");
        JSONObject n = root.optJSONObject("faehigkeiten");
        String schlecht = OK;
        for (String art : new String[] {"carplay", "androidauto"}) {
            if (!g.has(art)) continue;
            String nutzer = n == null ? "" : n.optString("carplay".equals(art) ? "ueberCarPlay" : "ueberAndroidAuto", "");
            String wert = "ja".equals(nutzer) || "nein".equals(nutzer) ? nutzer
                    : (u == null ? "unbekannt" : u.optString(art, "unbekannt"));
            if ("nein".equals(wert)) return FEHLT;
            if (!"ja".equals(wert)) schlecht = UNGEPRUEFT;
        }
        return schlecht;
    }

    private static String status(JSONArray s, String id) {
        for (int i = 0; s != null && i < s.length(); i++) {
            JSONObject o = s.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) return o.optString("status", null);
        }
        return null;
    }

    /**
     * Rein: Internet aus der System-Validierung (dumpsys connectivity). „Active default network: none“ oder
     * Netze ohne VALIDATED = nein; validiertes Netz = ja; nichts Lesbares = null (ungeprüft, nicht „fehlt“).
     */
    static Boolean internet(String dumpsys) {
        if (dumpsys == null) return null;
        if (dumpsys.contains("NetworkAgentInfo") && dumpsys.contains("VALIDATED")) return true;
        if (dumpsys.contains("Active default network: none")) return false;
        if (dumpsys.contains("NetworkAgentInfo")) return false;
        return null;
    }

    /** Rein: Tailscale-Status-JSON → eingerichtet/online. */
    static void fernzugang(Fakten f, String statusJson) {
        if (statusJson == null) return;
        int i = statusJson.indexOf('{');
        if (i < 0) return;   // nicht installiert bzw. nicht lesbar: eingerichtet=false, online=null
        try {
            JSONObject st = new JSONObject(statusJson.substring(i));
            String backend = st.optString("BackendState", "");
            f.fernzugangEingerichtet = !"NeedsLogin".equals(backend) && !"NoState".equals(backend) && !backend.isEmpty();
            JSONObject self = st.optJSONObject("Self");
            if (f.fernzugangEingerichtet) f.fernzugangOnline = self != null && self.optBoolean("Online", false)
                    && "Running".equals(backend);
        } catch (Exception ignored) {
        }
    }

    private static final Pattern UPDATE_ZEIT = Pattern.compile("lastUpdateTime=(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})");

    /**
     * Rein: Autostart für DIESE Installation bestätigt? Deploy-Marke {@code panoptes_autostart_ok} gleich dem
     * lastUpdateTime, oder App-Rückmeldung {@code {ok:true, zeit}} nach der Installation. Sonst fehlt
     * (BYD setzt den Haken bei jedem Update zurück). Installationszeit nicht lesbar → null (ungeprüft).
     */
    static Boolean autostart(String dumpsysInst, String marke, JSONObject appAntwort, TimeZone tz) {
        if (dumpsysInst == null) return null;
        Matcher m = UPDATE_ZEIT.matcher(dumpsysInst);
        if (!m.find()) return null;
        String inst = m.group(1);
        if (marke != null && inst.equals(marke.trim())) return true;
        if (appAntwort != null && appAntwort.optBoolean("ok", false)) {
            try {
                SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
                fmt.setTimeZone(tz);
                long instMs = fmt.parse(inst).getTime();
                if (appAntwort.optLong("zeit", 0L) >= instMs) return true;
            } catch (Exception e) {
                return null;
            }
        }
        return false;
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
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }
}
