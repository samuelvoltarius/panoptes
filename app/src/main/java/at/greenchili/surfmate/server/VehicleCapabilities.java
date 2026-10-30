package at.greenchili.surfmate.server;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Eine Stelle für „kann dieses Auto das?“ (Tacho-Anzeige, Head-up-Anzeige), im Daemon.
 * Genutzt von {@code GET /api/system/capabilities}, vom Regel-Katalog ({@code Actions}) und vom
 * Einrichtungsassistenten ({@code GET /api/einrichtung.faehigkeiten}, 0.1.10 Teil E).
 * Ergebnis 10 min zwischengespeichert. Unbekannt (Fehler) zählt wie „nicht vorhanden“.
 *
 * <p>0.1.10 (IDEEN_RUNDE2 §5.2): {@code kameras.anzahl}, {@code werte.*}, {@code projektion.*} und
 * {@code ueberProjektion.*} je {@code ja | nein | unbekannt}. Grundsatz: nur Belegtes ist „ja“; was
 * eine einzelne Messung nicht beweisen kann, bleibt „unbekannt“ – nie still „ja“ (Regel 1). Der ganze
 * Check bleibt unter 20 s (Zeitlimits der Einzelabfragen zusammen).
 */
public final class VehicleCapabilities {

    public static final String JA = "ja", NEIN = "nein", UNBEKANNT = "unbekannt";

    /**
     * Belegte Fähigkeiten als Daten, nicht im Code (Koordination 22.10., Regel 1): {@code web/shared/faehigkeiten_belegt.json}
     * (Modell, DiLink, Merkmal, Projektion, Wert, Quelle, Datum). Neue Community-Meldungen ohne Codeänderung.
     */
    static final String TABELLE = at.greenchili.surfmate.util.DaemonStorage.rebase(
            "/data/user_de/0/com.android.shell/surfmate/web/shared/faehigkeiten_belegt.json");

    private static final long CACHE_MS = 10 * 60 * 1000L;
    /** 0.1.32.1: fehlen noch Werte (Daemon eben gestartet, Telemetrie läuft erst an), bald wieder nachsehen. */
    private static final long CACHE_LUECKE_MS = 30 * 1000L;
    /** 0.1.32.1: einmal belegte Werte überleben den Neustart; eine Momentaufnahme ohne Wert beweist kein „unbekannt“. */
    static final String WERTE_DATEI = at.greenchili.surfmate.util.DaemonStorage.rebase(
            "/data/user_de/0/com.android.shell/surfmate/faehigkeiten_werte.json");
    private static JSONObject cache;
    private static long cacheAt;

    private VehicleCapabilities() {}

    public static synchronized JSONObject get() {
        long now = System.currentTimeMillis();
        if (cache != null && now - cacheAt < (hatLuecke(cache) ? CACHE_LUECKE_MS : CACHE_MS)) return cache;
        try {
            String singleOs = run("getprop ro.build.system.fission_single_os", 3).trim();
            String displays = run("dumpsys display 2>/dev/null | grep -E 'mDisplayId=|  mName=|DisplayDeviceInfo\\{' ", 6);
            cache = parse(singleOs, displays);
            try {
                String kamera = run("dumpsys media.camera 2>/dev/null | grep -m1 'Number of camera devices'", 5);
                String pakete = run("pm list packages 2>/dev/null", 5);
                erweitere(cache, kamera, dilink5(), pakete, modellId(), werteSchnappschuss(), projektionPakete());
                ueberProjektion(cache, modellId(), dilinkStufe(), tabelle(), nutzerAngaben());
                werteMerken();
            } catch (Exception ignored) {
                erweitere(cache, null, false, null, null, null);   // alles „unbekannt“
            }
            cacheAt = now;
            return cache;
        } catch (Exception e) {
            JSONObject res = new JSONObject();
            try { res.put("success", false); } catch (Exception ignored) { }
            return res;
        }
    }

    /** Nach einem Firmware-Wechsel: beim nächsten Abruf neu erkennen. */
    public static synchronized void neuErkennen() {
        cache = null;
        cacheAt = 0;
        new java.io.File(WERTE_DATEI).delete();
    }

    /** Belegte Werte aus {@code gemerkt} übernehmen und umgekehrt; true, wenn {@code gemerkt} dazugelernt hat. */
    static boolean werteAbgleichen(JSONObject werte, JSONObject gemerkt) throws Exception {
        boolean neu = false;
        java.util.List<String> namen = new java.util.ArrayList<>();
        java.util.Iterator<String> it = werte.keys();
        while (it.hasNext()) namen.add(it.next());
        for (String k : namen) {
            if (JA.equals(werte.optString(k))) {
                if (!JA.equals(gemerkt.optString(k))) { gemerkt.put(k, JA); neu = true; }
            } else if (JA.equals(gemerkt.optString(k))) {
                werte.put(k, JA);
            }
        }
        return neu;
    }

    static boolean hatLuecke(JSONObject c) {
        JSONObject w = c.optJSONObject("werte");
        if (w == null) return false;
        java.util.Iterator<String> it = w.keys();
        while (it.hasNext()) if (!JA.equals(w.optString(it.next()))) return true;
        return false;
    }

    private static void werteMerken() {
        JSONObject w = cache.optJSONObject("werte");
        if (w == null) return;
        try {
            java.io.File f = new java.io.File(WERTE_DATEI);
            JSONObject gemerkt = f.isFile()
                    ? new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()),
                            java.nio.charset.StandardCharsets.UTF_8))
                    : new JSONObject();
            if (werteAbgleichen(w, gemerkt)) {
                java.nio.file.Files.write(f.toPath(), gemerkt.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    public static boolean hasCluster() { return get().optBoolean("cluster", false); }

    public static boolean hasHud() { return get().optBoolean("hud", false); }

    /** Reine Auswertung (Tests). */
    static JSONObject parse(String singleOsProp, String dumpsysDisplay) throws Exception {
        int displayCount = 0;
        boolean hud = false;
        for (String line : (dumpsysDisplay == null ? "" : dumpsysDisplay).split("\n")) {
            if (line.contains("mDisplayId=")) displayCount++;
            String l = line.toLowerCase(Locale.ROOT);
            if (l.contains("hud") || l.contains("head-up") || l.contains("headup")) hud = true;
        }
        String s = singleOsProp == null ? "" : singleOsProp.trim();
        boolean isSingleOs = "1".equals(s) || "true".equalsIgnoreCase(s);
        // Tacho-Anzeige: nur mit eigenem, beschreibbarem zweiten Display (nicht Single-OS).
        boolean cluster = !isSingleOs && displayCount >= 2;
        JSONObject res = new JSONObject();
        res.put("success", true);
        res.put("cluster", cluster);
        res.put("hud", hud && !isSingleOs);
        res.put("singleOs", isSingleOs);
        res.put("displays", displayCount);
        return res;
    }

    private static final Pattern KAMERA_ANZAHL = Pattern.compile("Number of camera devices:\\s*(\\d+)");

    /**
     * Reine Erweiterung (Tests, Schein-Daten DiLink 3/4/5).
     *
     * @param dumpsysKamera Zeile aus {@code dumpsys media.camera} oder null
     * @param dilink5       DiLink 5: Kameras laufen dort über QCarCam, nicht über media.camera – eine
     *                      gemeldete 0 beweist dort nichts (sonst entfiele die Zuordnung fälschlich)
     * @param pakete        {@code pm list packages} oder null
     * @param modellId      gewähltes Modell ({@code vehicle.modelId}) oder null
     * @param werte         Momentaufnahme der Fahrzeugwerte (NaN/fehlend = nicht geliefert) oder null
     */
    static JSONObject erweitere(JSONObject res, String dumpsysKamera, boolean dilink5, String pakete,
                                String modellId, Map<String, Double> werte) throws Exception {
        return erweitere(res, dumpsysKamera, dilink5, pakete, modellId, werte, null);
    }

    /** @param projektionPakete {@code projektion_pakete} aus der Datentabelle (Art → [{paket, quelle, datum}]) oder null */
    static JSONObject erweitere(JSONObject res, String dumpsysKamera, boolean dilink5, String pakete,
                                String modellId, Map<String, Double> werte, JSONObject projektionPakete) throws Exception {
        // ---- Kameras
        JSONObject kam = new JSONObject();
        Integer n = null;
        if (dumpsysKamera != null) {
            Matcher m = KAMERA_ANZAHL.matcher(dumpsysKamera);
            if (m.find()) n = Integer.parseInt(m.group(1));
        }
        // Inventur Surf (DiLink 3, 22.10.): media.camera meldet 1 Android-Kamera (Legacy-HAL), die Rundumsicht läuft
        // anders. Die Zahl ist also NICHT die Kamerazahl des Autos → nur als androidKameras melden, nie als anzahl.
        // > 0 belegt, dass Kameras da sind (ja); 0 beweist nichts (DiLink 5: QCarCam) → unbekannt, Schritt entfällt nicht.
        if (n != null) kam.put("androidKameras", n);
        kam.put("status", n != null && n > 0 ? JA : UNBEKANNT);
        kam.put("quelle", "media.camera");
        res.put("kameras", kam);

        // ---- Werte: plausibler Wert = ja; sonst unbekannt (eine Momentaufnahme beweist kein „nein“)
        JSONObject w = new JSONObject();
        w.put("ladestand", plausibel(werte, "ladestand", 0.5, 100.0));
        w.put("spannung12v", plausibel(werte, "spannung12v", 9.0, 16.5));
        w.put("innentemperatur", plausibelOhneNull(werte, "innentemperatur", -40.0, 70.0));
        w.put("akkutemperatur", plausibelOhneNull(werte, "akkutemperatur", -40.0, 80.0));
        w.put("reifendruck", plausibel(werte, "reifendruck", 100.0, 450.0));
        res.put("werte", w);

        // ---- Projektion (installiert): Paketnamen aus der Datentabelle; Fund = ja, sonst unbekannt (nie geraten)
        JSONObject proj = new JSONObject();
        for (String art : new String[] {"carplay", "androidauto"}) {
            proj.put(art, pakete != null && installiert(pakete, paketNamen(projektionPakete, art)) ? JA : UNBEKANNT);
        }
        res.put("projektion", proj);

        // ---- Hinweise über der Projektion (H3): ohne Tabelle/Nutzerangabe unbekannt = aus
        res.put("ueberProjektion", new JSONObject().put("carplay", UNBEKANNT).put("androidauto", UNBEKANNT));
        return res;
    }

    /**
     * H3: Bleibt ein Panoptes-Hinweis über der Projektion sichtbar? Reihenfolge: eigene Angabe des Nutzers
     * ({@code faehigkeiten.ueberCarPlay|ueberAndroidAuto} = ja|nein) → belegter Tabelleneintrag (Modell, DiLink
     * passend oder nicht erhoben) → unbekannt. Ergebnis {@code ueberProjektion.<art>} plus {@code ueberProjektionQuelle}.
     */
    static JSONObject ueberProjektion(JSONObject res, String modellId, Integer dilink, org.json.JSONArray tabelle,
                                      JSONObject nutzer) throws Exception {
        JSONObject wert = new JSONObject();
        JSONObject quelle = new JSONObject();
        for (String art : new String[] {"carplay", "androidauto"}) {
            String n = nutzer == null ? "" : nutzer.optString("carplay".equals(art) ? "ueberCarPlay" : "ueberAndroidAuto", "");
            if (JA.equals(n) || NEIN.equals(n)) {
                wert.put(art, n);
                quelle.put(art, "nutzer");
                continue;
            }
            String t = ausTabelle(tabelle, modellId, dilink, "ueberProjektion", art);
            wert.put(art, t == null ? UNBEKANNT : t);
            quelle.put(art, t == null ? "keine" : "tabelle");
        }
        res.put("ueberProjektion", wert);
        res.put("ueberProjektionQuelle", quelle);
        return res;
    }

    /** Rein: passender Tabellenwert (ja|nein) oder null. Ohne Modell nie ein Treffer. */
    static String ausTabelle(org.json.JSONArray tabelle, String modellId, Integer dilink, String merkmal, String art) {
        if (tabelle == null || modellId == null) return null;
        for (int i = 0; i < tabelle.length(); i++) {
            JSONObject e = tabelle.optJSONObject(i);
            if (e == null || !modellId.equals(e.optString("modell")) || !merkmal.equals(e.optString("merkmal"))
                    || !art.equals(e.optString("projektion"))) continue;
            if (!e.isNull("dilink") && e.has("dilink") && (dilink == null || e.optInt("dilink") != dilink)) continue;
            String w = e.optString("wert", "");
            if (JA.equals(w) || NEIN.equals(w)) return w;
        }
        return null;
    }

    /** Rein: Paketnamen einer Projektionsart aus {@code projektion_pakete}. */
    public static java.util.List<String> paketNamen(JSONObject projektionPakete, String art) {
        java.util.List<String> l = new java.util.ArrayList<>();
        org.json.JSONArray a = projektionPakete == null ? null : projektionPakete.optJSONArray(art);
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject e = a.optJSONObject(i);
            String p = e == null ? a.optString(i, "") : e.optString("paket", "");
            if (!p.isEmpty()) l.add(p);
        }
        return l;
    }

    /** Rein: steht eines der Pakete exakt in {@code pm list packages}? */
    static boolean installiert(String pmListe, java.util.List<String> namen) {
        for (String zeile : pmListe.split("\n")) {
            String z = zeile.trim();
            if (z.startsWith("package:") && namen.contains(z.substring(8).trim())) return true;
        }
        return false;
    }

    /** {@code projektion_pakete} aus der Datentabelle (oder null). */
    public static JSONObject projektionPakete() {
        try {
            java.io.File f = new java.io.File(TABELLE);
            if (!f.isFile()) return null;
            return new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8)).optJSONObject("projektion_pakete");
        } catch (Throwable t) {
            return null;
        }
    }

    private static org.json.JSONArray tabelle() {
        try {
            java.io.File f = new java.io.File(TABELLE);
            if (!f.isFile()) return null;
            return new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8)).optJSONArray("eintraege");
        } catch (Throwable t) {
            return null;
        }
    }

    private static JSONObject nutzerAngaben() {
        try {
            return at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig().optJSONObject("faehigkeiten");
        } catch (Throwable t) {
            return null;
        }
    }

    /** DiLink-Stufe, soweit belegt: 5 bei gewähltem DiLink-5-Modus, sonst nicht erhoben (null). */
    private static Integer dilinkStufe() {
        try {
            return at.greenchili.surfmate.camera.dilink5.DiLink5Platform.isSelected() ? 5 : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String plausibel(Map<String, Double> w, String k, double min, double max) {
        Double v = w == null ? null : w.get(k);
        if (v == null || v.isNaN() || v.isInfinite()) return UNBEKANNT;
        return v >= min && v <= max ? JA : UNBEKANNT;
    }

    /** Wie {@link #plausibel}, aber genau 0 gilt als „nicht geliefert“ (häufiger Standardwert der Getter). */
    private static String plausibelOhneNull(Map<String, Double> w, String k, double min, double max) {
        Double v = w == null ? null : w.get(k);
        if (v != null && v == 0.0) return UNBEKANNT;
        return plausibel(w, k, min, max);
    }

    private static boolean dilink5() {
        try {
            return at.greenchili.surfmate.camera.dilink5.DiLink5Platform.isSelected();
        } catch (Throwable t) {
            return true;   // im Zweifel vorsichtig: eine 0 nicht glauben
        }
    }

    private static String modellId() {
        try {
            JSONObject v = at.greenchili.surfmate.config.UnifiedConfigManager.getVehicle();
            String id = v.optString("modelId", "");
            return at.greenchili.surfmate.config.VehicleModelSelection.isResolvedSelection(id,
                    v.optString("modelSource", "")) ? id.trim() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Map<String, Double> werteSchnappschuss() {
        try {
            at.greenchili.surfmate.byd.BydVehicleData d =
                    at.greenchili.surfmate.byd.BydDataCollector.getInstance().getData();
            if (d == null) return null;
            Map<String, Double> m = new java.util.HashMap<>();
            m.put("ladestand", d.socPercent);
            m.put("spannung12v", d.voltage12v);
            m.put("innentemperatur", d.insideTempC);
            m.put("akkutemperatur", d.bodyworkBattTempC);
            if (d.tyrePressure != null && d.tyrePressure.length > 0) {
                double sum = 0;
                for (int p : d.tyrePressure) sum += p;
                m.put("reifendruck", sum / d.tyrePressure.length);
            }
            return m;
        } catch (Throwable t) {
            return null;
        }
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
            return "";
        } finally {
            if (p != null) p.destroy();
        }
    }
}
