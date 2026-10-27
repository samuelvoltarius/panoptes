package at.greenchili.surfmate.server;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Regel A + B für gekoppelte Geräte (JWT mit scope "kopplung", z. B. EV-Cockpit, Home Assistant):
 * <b>Erlaubnisliste</b> statt Sperrliste (Befund HA-Sitzung/Koordination 21.10.2026: mit der alten
 * Sperrliste erreichte ein Kopplungs-Token schreibend /api/vehicle/*, quick-controls/fire, keymap/fire,
 * automations/test …). Alles, was hier nicht ausdrücklich steht, ist für Kopplungs-Tokens gesperrt (403) –
 * neue Pfade sind damit automatisch zu.
 *
 * <ul>
 *   <li>Lesen: Telemetrie, Übersicht, Fahrzeugzustand (nur GET), GPS, Fähigkeiten, Wächter-Status und
 *       Gesundheit, F1, Fahrtenbuch-Liste, Planer-Kennung, Einstellungen + Schema, Daemon-Status.</li>
 *   <li>Schreiben: nur {@code /api/einstellungen} (je Feld {@code nurLesendExtern} im Handler) und
 *       {@code /api/sitzung/erneuern}.</li>
 *   <li>Nie: Fahrzeugbefehle, fire/test-Pfade, Kopplungs-/Zugangsverwaltung, Fernzugang, Backup,
 *       Updates, Streams/WebSockets.</li>
 * </ul>
 */
public final class ExternGuard {

    private ExternGuard() {}

    /** Genau diese Pfade dürfen gelesen werden (GET/HEAD), ohne Unterpfade. */
    static final Set<String> LESEN = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "/api/mqtt/telemetry",
            "/api/launcher/v1/summary",
            "/api/vehicle/state",
            "/api/vehicle/status",
            "/api/gps",
            "/api/system/capabilities",
            "/api/system/daemon",
            "/api/surveillance/status",
            "/api/surveillance/gesundheit",
            "/api/navi/unterwegs",
            "/api/trips",
            "/api/trips/summary",
            "/api/trips/range",       // 0.1.20 Handy-App: Reichweiten-Schätzung, keine Koordinaten
            "/api/wallbox/status",    // 0.1.20 Handy-App/Cockpit: Wallbox-Zustand, NUR lesen (Steuern bleibt gesperrt)
            "/api/planer/identitaet",
            "/api/einstellungen",
            "/api/einstellungen/schema",
            "/api/laden",   // Lade-Vorlieben fürs Cockpit (LADEN_VERTRAG A4)
            "/api/laden/nachfrage",   // Ladenachfrage: Cockpit zeigt an (LADENACHFRAGE_VERTRAG)
            "/api/laden/kosten",      // „Was kosten 100 km?“ (0.1.10.1)
            "/api/laden/ladungen",    // Ladungsliste ohne Koordinaten (0.1.10.1)
            "/api/appupdate/status",
            "/api/neuigkeiten",
            "/api/bericht/monat",
            "/api/ha/v1/zustand",
            "/api/ha/v1/regeln",
            "/api/ha/v1/ereignisse",
            "/api/camping",   // 0.1.16: Camping-Status (Panoptes-Funktion, keine Innentemperatur)
            "/api/ladeplan",  // 0.1.20: Ladeplan lesen (Cockpit/Handy)
            // Wächter-Aufnahmen fürs Cockpit (0.1.9): Liste + Tage + Zahlen, NUR lesen
            "/api/recordings",
            "/api/recordings/dates",
            "/api/recordings/stats"
    )));

    /** Lesen mit genau einer Aufnahme-ID (32 hex): Clip (mit Range) und Vorschaubild. */
    static final String[] LESEN_MIT_ID = {"/video/id/", "/thumb/id/"};
    private static final java.util.regex.Pattern AUFNAHME_ID = java.util.regex.Pattern.compile("[0-9a-f]{32}");

    /** Genau diese Pfade dürfen schreiben (POST/PUT). */
    static final Set<String> SCHREIBEN = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "/api/einstellungen",
            "/api/sitzung/erneuern",
            "/api/sitzung/abmelden",
            "/api/laden",   // Cockpit pflegt die Vorlieben im Auto (GRUNDSATZ)
            "/api/laden/nachfrage",   // Cockpit darf beantworten (Koordination 22.10.), Auto bleibt die Quelle
            "/api/laden/nachfrage/spaeter",
            "/api/laden/nachfrage/kein-preis",
            "/api/ha/v1/aktion",
            // 0.1.16 Camping: Panoptes-Funktion (Regel A: in Stufe 2a keine Fahrzeugwirkung)
            "/api/camping",
            "/api/camping/rueckmeldung",
            "/api/camping/plan",   // 0.1.17: Cockpit plant Start/Ende (Panoptes-Funktion)
            "/api/ladeplan",   // 0.1.20: Plan ändern; Freigabe/Ladepunkt nie (Handler: nur_am_auto)
            "/api/camping/weiter"
    )));

    /**
     * 0.1.18 Handy-Vertrag §1: genau diese Schreibpfade NUR für Kopplungen der Art {@code handy} (Rechte prüft der
     * Handler je Aktion). Cockpit/HA bleiben hier 403; {@code /api/vehicle/*} bleibt für alle Kopplungen zu.
     */
    static final Set<String> HANDY_SCHREIBEN = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "/api/handy/v1/geraet",
            "/api/handy/v1/aktion",
            "/api/handy/v1/push",
            "/api/handy/v1/push/aus",
            "/api/handy/v1/bruecke",   // 0.1.20 Panoptes-Brücke: Handy-Schlüssel + Zugang (BRUECKE_VERTRAG §2.4)
            "/api/handy/v1/bruecke/aus",
            "/api/handy/v1/websitzung"   // 0.1.20 Weboberfläche in der Handy-App (HANDY_WEB_SITZUNG_VERTRAG)
    )));

    /** true, wenn die Anfrage eines gekoppelten Geräts abgelehnt werden muss. */
    public static boolean blocks(String method, String pathOnly) {
        return !erlaubt(method, pathOnly);
    }

    /** Wie {@link #blocks(String, String)}, mit der Art der Kopplung (Handy-Pfade nur für {@code handy}). */
    public static boolean blocks(String method, String pathOnly, String kopplungsArt) {
        if (erlaubt(method, pathOnly)) return false;
        if (cockpitErlaubt(method, pathOnly, kopplungsArt)) return false;
        return !handyErlaubt(method, pathOnly, kopplungsArt);
    }

    /**
     * 0.1.21 BYD-Cloud-Übergabe (CLOUD_UEBERGABE_VERTRAG §5): genau {@code POST /api/cloud-uebergabe/anfrage},
     * {@code GET|POST /api/cloud-uebergabe/r-<16 hex>} – nur für Kopplungen der Art {@code cockpit}. Erlauben/Ablehnen
     * ({@code …/erlauben|ablehnen}) und die Liste offener Rückfragen bleiben für Kopplungen zu.
     */
    static boolean cockpitErlaubt(String method, String pathOnly, String kopplungsArt) {
        if (!"cockpit".equals(kopplungsArt) || pathOnly == null) return false;
        String p = pathOnly;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (p.contains("..") || p.contains("//")) return false;
        if ("/api/cloud-uebergabe/anfrage".equals(p)) return "POST".equals(method);
        return p.matches("/api/cloud-uebergabe/r-[0-9a-f]{16}") && ("GET".equals(method) || "POST".equals(method));
    }

    static boolean handyErlaubt(String method, String pathOnly, String kopplungsArt) {
        if (!"handy".equals(kopplungsArt) || !"POST".equals(method) || pathOnly == null) return false;
        String p = pathOnly;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.contains("..") || p.contains("//")) return false;
        return HANDY_SCHREIBEN.contains(p);
    }

    public static boolean erlaubt(String method, String pathOnly) {
        if (pathOnly == null || method == null) return false;
        String p = pathOnly;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.contains("..") || p.contains("//")) return false;
        boolean lesen = "GET".equals(method) || "HEAD".equals(method);
        if (lesen) {
            if (LESEN.contains(p)) return true;
            for (String v : LESEN_MIT_ID) {
                if (p.startsWith(v) && AUFNAHME_ID.matcher(p.substring(v.length())).matches()) return true;
            }
            return false;
        }
        if ("POST".equals(method) || "PUT".equals(method)) return SCHREIBEN.contains(p);
        return false;
    }
}
