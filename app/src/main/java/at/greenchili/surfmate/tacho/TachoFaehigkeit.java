package at.greenchili.surfmate.tacho;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tacho-Fähigkeitstest (Kern Tacho 0.1.30, Teil 1): „Totwinkel / Karte / andere Apps am Tacho: ja/nein“,
 * je mit einem Satz Grund, für DiLink 3 (Single- und Dual-OS), DiLink 4 und DiLink 5.
 *
 * <p><b>Nur lesend.</b> Erhoben wird ausschließlich über {@code getprop}, {@code dumpsys display},
 * {@code service list} (nur Namen), {@code cmd package path}, {@code dumpsys package} und {@code id -u}.
 * Kein {@code service call}, kein {@code am}, kein Binden, kein Intent, nichts an den Tacho. Der Test
 * schickt nichts an einen Server; den Text kopiert der Nutzer selbst (Regel Nr. 2).
 *
 * <p>Grundsatz wie {@code VehicleCapabilities}: nur Belegtes ist „ja“. „Andere Apps“ wird erst „ja“,
 * wenn bestätigt ist, dass die Warnleuchten frei bleiben (Nutzer-Probe im Stand); vorher „unbekannt“.
 */
public final class TachoFaehigkeit {

    public static final String JA = "ja", NEIN = "nein", UNBEKANNT = "unbekannt";

    /** Systembaustein, über den Dual-OS-/DiLink-5-Autos die Tacho-Projektion anbieten. */
    static final String XDJA_PAKET = "com.xdja.containerservice";
    static final String XDJA_DIENST = "AutoDisplayService";
    /** Brücke Gaode-Navi → Tacho (China-Navi), am Surf gefunden. */
    static final String AMAP_PAKET = "com.example.amapservice";

    private TachoFaehigkeit() {}

    /** Rohwerte, alle nur gelesen. Felder bleiben null/false, wenn eine Abfrage nichts liefert. */
    public static final class Eingaben {
        public String singleOs = "";          // ro.build.system.fission_single_os
        public String fissionMode = "";       // ro.fission.mode
        public String clusterFirmware = "";   // persist.sys.gbClientVersion
        public String modell = "";            // ro.product.model (keine Kennung)
        public int sdk;                       // ro.build.version.sdk
        public boolean dilink5Gewaehlt;       // DiLink5Platform.isSelected()
        public String displays = "";          // dumpsys display (gefiltert)
        public String dienste = "";           // service list
        public boolean xdjaInstalliert;
        public boolean xdjaAutoDisplay;       // AutoDisplayService im Paket angeboten
        public boolean amapInstalliert;
        public String uid = "";               // id -u des Daemons
        /** Bestätigung „Warnleuchten bleiben frei“ (ja|nein|""), aus der Probe im Stand. */
        public String warnleuchtenFrei = "";
    }

    // ---------------------------------------------------------------- reine Auswertung

    private static final Pattern DISPLAY_ID = Pattern.compile("mDisplayId=(\\d+)");
    private static final Pattern DISPLAY_NAME = Pattern.compile("(?:mName|name)=\"?([^\",}]+)");
    private static final Pattern DISPLAY_SIZE = Pattern.compile("(\\d{3,4}) x (\\d{3,4})");
    /** Logische Anzeige: {@code DisplayInfo{"Name", [uniqueId …,] displayId N, … real W x H …}}. */
    private static final Pattern DISPLAY_INFO = Pattern.compile(
            "DisplayInfo\\{\"([^\"]+)\",.*?displayId (\\d+).*?real (\\d+) x (\\d+)");

    /** Reine Auswertung (Tests mit Schein-Daten Surf / Seal / DiLink 4 / DiLink 5). */
    public static JSONObject bewerte(Eingaben e) throws Exception {
        if (e == null) e = new Eingaben();
        String so = e.singleOs == null ? "" : e.singleOs.trim();
        boolean singleOs = "1".equals(so) || "true".equalsIgnoreCase(so);
        String dienste = e.dienste == null ? "" : e.dienste;
        boolean autoContainer = enthaeltDienst(dienste, "AutoContainer")
                || enthaeltDienst(dienste, "auto_container");
        JSONObject tachoDisplay = tachoDisplay(e.displays);
        boolean sharedFission = tachoDisplay != null
                && tachoDisplay.optString("name").toLowerCase(Locale.ROOT).contains("xdjascreenprojection");
        boolean dilink5 = e.dilink5Gewaehlt || sharedFission;
        boolean shell = "0".equals(trim(e.uid)) || "2000".equals(trim(e.uid));

        JSONObject plattform = new JSONObject();
        String dilink;
        boolean dilinkSicher;
        if (dilink5) { dilink = "5"; dilinkSicher = true; }
        else if (e.sdk >= 30) { dilink = "4"; dilinkSicher = false; }
        else { dilink = "3"; dilinkSicher = e.sdk > 0; }
        plattform.put("dilink", dilink);
        plattform.put("dilinkSicher", dilinkSicher);
        plattform.put("android", e.sdk);
        plattform.put("singleOs", singleOs);
        plattform.put("fissionMode", trim(e.fissionMode));
        plattform.put("tachoFirmware", trim(e.clusterFirmware));
        plattform.put("modell", trim(e.modell));
        plattform.put("tachoDisplay", tachoDisplay == null ? JSONObject.NULL : tachoDisplay);
        plattform.put("autoContainer", autoContainer);
        plattform.put("sharedFission", sharedFission);
        plattform.put("xdjaAutoDisplay", e.xdjaInstalliert && e.xdjaAutoDisplay);
        plattform.put("amapService", e.amapInstalliert);
        plattform.put("shellRechte", shell);

        // Gemeinsamer Unterbau: Single-OS hat keine Projektionsfläche (DashCast #59, wheregoes, Surf-Test
        // 29.10.); ohne Projektionsdienst kein Weg; ohne Shell-Rechte kann Panoptes den Weg nicht nutzen.
        String sperre = null, sperreGrund = null;
        if (singleOs && !dilink5) {
            sperre = "single_os";
            sperreGrund = "Dein Tacho ist ein eigenes kleines System ohne Fläche für fremde Bilder (Single-OS).";
        } else if (!autoContainer && !dilink5) {
            sperre = "kein_projektionsdienst";
            sperreGrund = "Der Projektionsdienst des Herstellers fehlt auf diesem Auto.";
        } else if (!shell) {
            sperre = "keine_shell_rechte";
            sperreGrund = "Panoptes hat noch keine Shell-Rechte. Schließe zuerst die Einrichtung ab.";
        }

        JSONObject res = new JSONObject();
        res.put("success", true);
        res.put("plattform", plattform);
        if (sperre != null) {
            res.put("totwinkel", urteil(NEIN, sperre, sperreGrund, false));
            res.put("karte", urteil(NEIN, sperre, sperreGrund, false));
            res.put("andereApps", urteil(NEIN, sperre, sperreGrund, false));
        } else {
            String basis = dilink5
                    ? "Der Tacho bietet eine Projektionsfläche (DiLink 5)."
                    : "Der Projektionsdienst des Tachos ist da.";
            res.put("totwinkel", urteil(JA, "projektion_da",
                    basis + " Am Seal bestätigt, bei deinem Modell noch Beta.", true));
            res.put("karte", urteil(JA, "projektion_da",
                    basis + " Die Panoptes-Karte kann dorthin, noch Beta.", true));
            String wf = trim(e.warnleuchtenFrei);
            if (NEIN.equals(wf)) {
                res.put("andereApps", urteil(NEIN, "warnleuchten_verdeckt",
                        "Du hast gemeldet, dass dabei Warnleuchten verdeckt werden. Deshalb aus.", false));
            } else if (JA.equals(wf)) {
                res.put("andereApps", urteil(JA, "warnleuchten_frei",
                        "Technisch möglich, und du hast im Stand bestätigt: Warnleuchten bleiben frei.", true));
            } else {
                res.put("andereApps", urteil(UNBEKANNT, "warnleuchten_ungeprueft",
                        "Technisch möglich, aber noch nicht bestätigt, dass die Warnleuchten frei bleiben. "
                                + "Mach im Stand die Probe.", true));
            }
        }
        // Ehrlicher Zusatz für Single-OS-Autos mit dem XDJA-Baustein. Surf, Bindversuch A/B 30.10. (Deploy (2)):
        // der Dienst läuft, aber sein Gegenstück im Tacho (AutoContainerServiceNative) fehlt → kein Bild möglich.
        boolean nativ = enthaeltDienst(dienste, "AutoContainerServiceNative");
        plattform.put("autoContainerNative", nativ);
        if (singleOs && e.xdjaInstalliert && e.xdjaAutoDisplay && !nativ) {
            res.put("hinweis", "Auf deinem Auto gibt es einen Systembaustein für die Tacho-Anzeige, "
                    + "aber ohne Gegenstück im Tacho. Er kann deshalb keine Bilder zeigen.");
        }
        return res;
    }

    /** Erste Anzeige ≠ 0 mit Tacho-typischem Namen (fission, xdja, cluster, instrument, meter). */
    static JSONObject tachoDisplay(String dumpsysDisplay) throws Exception {
        if (dumpsysDisplay == null) return null;
        for (String zeile : dumpsysDisplay.split("\n")) {
            Matcher mi = DISPLAY_INFO.matcher(zeile);
            if (mi.find()) {
                JSONObject t = alsTacho(Integer.parseInt(mi.group(2)), mi.group(1),
                        mi.group(3) + "x" + mi.group(4));
                if (t != null) return t;
            }
        }
        Integer id = null;
        String name = null;
        String groesse = null;
        for (String zeile : dumpsysDisplay.split("\n")) {
            Matcher mi = DISPLAY_ID.matcher(zeile);
            if (mi.find()) {
                JSONObject treffer = alsTacho(id, name, groesse);
                if (treffer != null) return treffer;
                id = Integer.parseInt(mi.group(1));
                name = null;
                groesse = null;
            }
            Matcher mn = DISPLAY_NAME.matcher(zeile);
            if (mn.find() && name == null) name = mn.group(1).trim();
            Matcher ms = DISPLAY_SIZE.matcher(zeile);
            if (ms.find() && groesse == null) groesse = ms.group(1) + "x" + ms.group(2);
        }
        return alsTacho(id, name, groesse);
    }

    private static JSONObject alsTacho(Integer id, String name, String groesse) throws Exception {
        if (id == null || id == 0 || name == null) return null;
        String n = name.toLowerCase(Locale.ROOT);
        if (!(n.contains("fission") || n.contains("xdja") || n.contains("cluster")
                || n.contains("instrument") || n.contains("meter"))) return null;
        JSONObject d = new JSONObject();
        d.put("id", id);
        d.put("name", name);
        if (groesse != null) d.put("groesse", groesse);
        return d;
    }

    static boolean enthaeltDienst(String serviceList, String name) {
        for (String zeile : serviceList.split("\n")) {
            // Format: "12\tAutoContainer: [android.os.IAutoContainer]"
            String z = zeile.trim();
            int tab = z.indexOf('\t');
            if (tab >= 0) z = z.substring(tab + 1).trim();
            if (z.startsWith(name + ":")) return true;
        }
        return false;
    }

    private static JSONObject urteil(String status, String code, String grund, boolean beta) throws Exception {
        JSONObject u = new JSONObject();
        u.put("status", status);
        u.put("code", code);
        u.put("grund", grund);
        u.put("beta", beta);
        return u;
    }

    /**
     * Text zum Kopieren für die Wunsch-Box: Modell, DiLink, Firmware des Tachos, die drei Urteile.
     * Keine Kennung (VIN, Konto, Gerät), kein Ort, keine Uhrzeit.
     */
    public static String alsText(JSONObject r) {
        if (r == null) return "";
        JSONObject p = r.optJSONObject("plattform");
        StringBuilder sb = new StringBuilder("Panoptes Tacho-Test\n");
        if (p != null) {
            sb.append("Modell: ").append(p.optString("modell", "?")).append('\n');
            sb.append("DiLink: ").append(p.optString("dilink", "?"))
                    .append(p.optBoolean("dilinkSicher") ? "" : " (vermutet)")
                    .append(", Android-API ").append(p.optInt("android")).append('\n');
            sb.append("Single-OS: ").append(p.optBoolean("singleOs") ? "ja" : "nein")
                    .append(", Projektionsdienst: ").append(p.optBoolean("autoContainer") ? "ja" : "nein")
                    .append(", DiLink-5-Fläche: ").append(p.optBoolean("sharedFission") ? "ja" : "nein").append('\n');
            Object d = p.opt("tachoDisplay");
            if (d instanceof JSONObject) {
                JSONObject dj = (JSONObject) d;
                sb.append("Tacho-Anzeige: id ").append(dj.optInt("id")).append(", ")
                        .append(dj.optString("groesse", "?")).append('\n');
            }
            sb.append("Tacho-Firmware: ").append(p.optString("tachoFirmware", "?")).append('\n');
        }
        for (String k : new String[] {"totwinkel", "karte", "andereApps"}) {
            JSONObject u = r.optJSONObject(k);
            if (u == null) continue;
            String titel = "totwinkel".equals(k) ? "Totwinkel am Tacho"
                    : "karte".equals(k) ? "Karte am Tacho" : "Andere Apps am Tacho";
            sb.append(titel).append(": ").append(u.optString("status")).append(" – ")
                    .append(u.optString("grund")).append('\n');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- Erhebung (nur lesen)

    /** Liest die Rohwerte am Auto. Jede Abfrage ist nur lesend und hat ein eigenes Zeitlimit. */
    public static Eingaben erhebe() {
        Eingaben e = new Eingaben();
        e.singleOs = run("getprop ro.build.system.fission_single_os", 3);
        e.fissionMode = run("getprop ro.fission.mode", 3);
        e.clusterFirmware = run("getprop persist.sys.gbClientVersion", 3);
        e.modell = run("getprop ro.product.model", 3);
        try { e.sdk = Integer.parseInt(run("getprop ro.build.version.sdk", 3).trim()); } catch (Exception ignored) { }
        try {
            e.dilink5Gewaehlt = at.greenchili.surfmate.camera.dilink5.DiLink5Platform.isSelected();
        } catch (Throwable ignored) { }
        e.displays = run("dumpsys display 2>/dev/null | grep -E 'mDisplayId=|mName=|DisplayInfo\\{'", 6);
        e.dienste = run("service list 2>/dev/null", 4);
        e.xdjaInstalliert = run("cmd package path " + XDJA_PAKET + " 2>/dev/null", 3).contains("package:");
        e.xdjaAutoDisplay = e.xdjaInstalliert
                && run("dumpsys package " + XDJA_PAKET + " 2>/dev/null | grep -m1 " + XDJA_DIENST, 5)
                        .contains(XDJA_DIENST);
        e.amapInstalliert = run("cmd package path " + AMAP_PAKET + " 2>/dev/null", 3).contains("package:");
        e.uid = run("id -u", 2);
        e.warnleuchtenFrei = TachoNaviApp.warnleuchtenFrei();
        return e;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
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
        } catch (Exception ex) {
            return "";
        } finally {
            if (p != null) p.destroy();
        }
    }
}
