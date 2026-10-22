package at.greenchili.surfmate.services;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Unsichtbarer Autostart-Weg über {@code ssc_whitelist} (Settings global UND secure), VORBEREITET, NICHT AKTIV.
 *
 * <p>Befund Deploy (AUTOSTART_BEFUND.md, Zustand b, 22.10.2026): kein appstartup-Provider, appops ohne
 * AUTO_START, {@code ssc_whitelist} enthält die freigegebenen Pakete. Hypothese: BYDs Schalter
 * „Deaktiver Autostart“ ist diese Liste, die Installation entfernt den Paketnamen. Erst nach Befund (a) und GO der
 * Koordination wird {@code autostart.weg = settings} genutzt; Standard bleibt {@code sichtbar} (AutoStartEnabler).
 *
 * <p>Regeln: Liste nur ergänzen, nie überschreiben oder umsortieren; andere Einträge bleiben unverändert;
 * danach zurücklesen und nur bei Beleg „ok“ melden. Nur im Daemon (Shell-Rechte).
 */
public final class SscWhitelist {

    public static final String WEG_SICHTBAR = "sichtbar", WEG_SETTINGS = "settings";
    static final String SCHLUESSEL = "ssc_whitelist";

    private SscWhitelist() {}

    /** {@code autostart.weg}: sichtbar (Standard) | settings. Alles andere = sichtbar. */
    public static String weg(JSONObject root) {
        JSONObject a = root == null ? null : root.optJSONObject("autostart");
        return a != null && WEG_SETTINGS.equals(a.optString("weg", "")) ? WEG_SETTINGS : WEG_SICHTBAR;
    }

    /** Ist {@code pkg} exakt als Eintrag in der Komma-Liste? ("null"/leer = leer) */
    public static boolean enthaelt(String liste, String pkg) {
        if (liste == null || pkg == null || pkg.isEmpty()) return false;
        for (String e : liste.trim().split(",")) {
            if (pkg.equals(e.trim())) return true;
        }
        return false;
    }

    /**
     * Neuer Wert der Liste oder null, wenn nichts zu tun ist. Hängt nur an; bestehende Einträge bleiben
     * Zeichen für Zeichen erhalten.
     */
    public static String ergaenze(String aktuell, String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.contains(",") || pkg.contains(" ")) {
            throw new IllegalArgumentException("pkg");
        }
        String a = aktuell == null ? "" : aktuell.trim();
        if ("null".equals(a)) a = "";
        if (enthaelt(a, pkg)) return null;
        return a.isEmpty() ? pkg : a + "," + pkg;
    }

    /** Schnittstelle zur Shell (Tests ersetzen sie). */
    public interface Shell {
        /** settings get &lt;ns&gt; ssc_whitelist (roh, ggf. "null") oder null bei Fehler. */
        String lies(String namensraum);
        /** settings put &lt;ns&gt; ssc_whitelist &lt;wert&gt;; true = Befehl ohne Fehler. */
        boolean schreibe(String namensraum, String wert);
    }

    /**
     * In global und secure sicherstellen, dann zurücklesen. Ergebnis je Namensraum:
     * {@code schon_da | ergaenzt | fehler}; {@code ok} nur, wenn beide zurückgelesen enthalten.
     */
    public static JSONObject sicherstellen(String pkg, Shell sh) throws Exception {
        JSONObject r = new JSONObject();
        boolean alle = true;
        for (String ns : new String[] {"global", "secure"}) {
            String vorher = sh.lies(ns);
            if (vorher == null) {
                r.put(ns, "fehler");
                alle = false;
                continue;
            }
            String neu = ergaenze(vorher, pkg);
            if (neu == null) {
                r.put(ns, "schon_da");
                continue;
            }
            boolean geschrieben = sh.schreibe(ns, neu);
            String nachher = sh.lies(ns);
            boolean belegt = geschrieben && enthaelt(nachher, pkg);
            r.put(ns, belegt ? "ergaenzt" : "fehler");
            alle &= belegt;
        }
        r.put("ok", alle);
        return r;
    }

    /** Echte Shell im Daemon (uid 2000). */
    public static final Shell DAEMON = new Shell() {
        @Override
        public String lies(String ns) {
            String out = run("settings get " + ns + " " + SCHLUESSEL);
            return out == null ? null : out.trim();
        }

        @Override
        public boolean schreibe(String ns, String wert) {
            if (!wert.matches("[A-Za-z0-9._,]+")) return false;   // nur Paketnamen-Zeichen, kein Shell-Inhalt
            return run("settings put " + ns + " " + SCHLUESSEL + " '" + wert + "' && echo OK") != null;
        }
    };

    private static String run(String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            if (!p.waitFor(5, TimeUnit.SECONDS) || p.exitValue() != 0) return null;
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }
}
