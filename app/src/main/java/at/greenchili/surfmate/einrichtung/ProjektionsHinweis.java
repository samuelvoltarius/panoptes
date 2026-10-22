package at.greenchili.surfmate.einrichtung;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * H3 (0.1.10, Koordination 22.10.): Panoptes-Hinweise während CarPlay/Android Auto.
 *
 * <p>Das Band bleibt wie bisher (immer versucht). Die Wahl {@code speedcam.ton} wird nie übersteuert, es gibt keine
 * zusätzlichen Geräusche. Neu: Zeigt Panoptes einen Blitzer-Hinweis, während eine Projektion im Vordergrund ist, ist
 * für dieses Auto nicht belegt, dass Hinweise darüber sichtbar sind ({@code ueberProjektion ≠ ja}), und ist der Ton aus,
 * merkt Panoptes das EINMAL je Projektionsart. Daraus wird ein Eintrag „Noch offen“ bzw. ein Selbsttest-Punkt mit
 * Verweis auf die eigene Angabe und den Hinweiston – kein Popup, schon gar nicht während der Fahrt.
 * Erledigt sich, sobald der Nutzer ja/nein angibt oder einen Ton wählt.
 */
public final class ProjektionsHinweis {

    public static final String CARPLAY = "carplay", ANDROID_AUTO = "androidauto";
    static final String SCHLUESSEL = "projektionUnbekannt";   // im Abschnitt einrichtung: {art: zeitMs}

    private ProjektionsHinweis() {}

    /**
     * Rein: welche Projektion steht im Vordergrund? Paketnamen aus der Datentabelle ({@code projektion_pakete}),
     * nicht aus dem Code. Zeile aus dumpsys; null = keine/unklar.
     */
    public static String aktiv(String resumedZeile, JSONObject projektionPakete) {
        if (resumedZeile == null) return null;
        String z = resumedZeile.toLowerCase(Locale.ROOT);
        for (String art : new String[] {CARPLAY, ANDROID_AUTO}) {
            for (String p : at.greenchili.surfmate.server.VehicleCapabilities.paketNamen(projektionPakete, art)) {
                if (z.contains(" " + p.toLowerCase(Locale.ROOT) + "/")) return art;
            }
        }
        return null;
    }

    private static String nutzerSchluessel(String art) {
        return CARPLAY.equals(art) ? "ueberCarPlay" : "ueberAndroidAuto";
    }

    /** Rein: Soll für diese Lage einmalig gemerkt werden? */
    public static boolean merken(JSONObject root, String art, String ueberProjektion, String ton) {
        if (art == null || "ja".equals(ueberProjektion) || !"aus".equals(ton)) return false;
        JSONObject e = root == null ? null : root.optJSONObject("einrichtung");
        JSONObject g = e == null ? null : e.optJSONObject(SCHLUESSEL);
        return g == null || !g.has(art);
    }

    /**
     * Rein: offene Hinweise für GET /api/einrichtung (Liste „Noch offen“). Je gemerkter Projektionsart ein Eintrag,
     * solange weder eine eigene Angabe (ja|nein) noch ein Ton gewählt ist.
     */
    public static JSONArray offen(JSONObject root, String ton) throws Exception {
        JSONArray r = new JSONArray();
        JSONObject e = root == null ? null : root.optJSONObject("einrichtung");
        JSONObject g = e == null ? null : e.optJSONObject(SCHLUESSEL);
        if (g == null || !"aus".equals(ton)) return r;
        JSONObject f = root.optJSONObject("faehigkeiten");
        for (String art : new String[] {CARPLAY, ANDROID_AUTO}) {
            if (!g.has(art)) continue;
            String n = f == null ? "" : f.optString(nutzerSchluessel(art), "");
            if ("ja".equals(n) || "nein".equals(n)) continue;
            r.put(new JSONObject()
                    .put("id", "projektion_unbekannt")
                    .put("art", art)
                    .put("textKey", "einr.hinweis.projektion_unbekannt." + art)
                    .put("einstellungen", new JSONArray().put("faehigkeiten." + nutzerSchluessel(art)).put("speedcam.ton"))
                    .put("seit", g.optLong(art)));
        }
        return r;
    }

    /**
     * Im Daemon nach einem Blitzer-Band: im Hintergrund prüfen und ggf. EINMAL merken. Nie blockierend, nie mit
     * Anzeige oder Ton. {@code ton} = wirksamer speedcam.ton.
     */
    public static void nachBand(String ton) {
        if (!"aus".equals(ton)) return;
        Thread t = new Thread(() -> {
            try {
                String art = aktiv(run("dumpsys activity activities 2>/dev/null | grep -m1 -E 'mResumedActivity|topResumedActivity'"),
                        at.greenchili.surfmate.server.VehicleCapabilities.projektionPakete());
                if (art == null) return;
                JSONObject caps = at.greenchili.surfmate.server.VehicleCapabilities.get();
                JSONObject u = caps.optJSONObject("ueberProjektion");
                String wert = u == null ? "unbekannt" : u.optString(art, "unbekannt");
                JSONObject root = at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig();
                if (!merken(root, art, wert, ton)) return;
                JSONObject e = root.optJSONObject("einrichtung");
                JSONObject g = e == null || e.optJSONObject(SCHLUESSEL) == null ? new JSONObject()
                        : new JSONObject(e.getJSONObject(SCHLUESSEL).toString());
                g.put(art, System.currentTimeMillis());
                java.util.Map<String, Object> w = new java.util.HashMap<>();
                w.put(SCHLUESSEL, g);
                at.greenchili.surfmate.config.UnifiedConfigManager.updateValues("einrichtung", w);
            } catch (Throwable ignored) {
            }
        }, "ProjektionsHinweis");
        t.setDaemon(true);
        t.start();
    }

    private static String run(String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor(5, TimeUnit.SECONDS);
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }
}
