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
            "/api/camping/weiter"
    )));

    /** true, wenn die Anfrage eines gekoppelten Geräts abgelehnt werden muss. */
    public static boolean blocks(String method, String pathOnly) {
        return !erlaubt(method, pathOnly);
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
