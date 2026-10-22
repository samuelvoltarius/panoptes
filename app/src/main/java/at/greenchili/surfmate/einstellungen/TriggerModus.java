package at.greenchili.surfmate.einstellungen;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Wächter-Auslöser (Koordination 21.10.2026, Pflichtwahl Text C):
 * <ul>
 *   <li>{@code shock} – (a) nur Erschütterung/Berührung,</li>
 *   <li>{@code ai_everywhere} – (b) KI-Erkennung überall (heutiges Verhalten),</li>
 *   <li>{@code ai_private} – (c) KI nur in vom Nutzer festgelegten Privat-Orten (Mittelpunkt +
 *       Radius), außerhalb automatisch (a).</li>
 * </ul>
 *   <li>{@code aus} – Wächter nimmt nicht auf (vierte Wahl, SETUP_WIZARD Schritt 8).</li>
 * </ul>
 * <b>Nicht gesetzt = „nicht gewählt“</b> (Koordination 0.1.9.1, SETUP_WIZARD Teil A, Regel 2): Bis zur
 * ausdrücklichen Wahl nimmt der Wächter nur bei Erschütterung auf – für ALLE Installationen ohne
 * gespeicherte Wahl, auch alte. Eine gespeicherte Wahl bleibt unverändert. Es wird nichts geschrieben.
 * In der Schweiz (GPS) ist (b) wirkungslos und fällt auf (c) zurück – bzw. auf (a), solange kein Privat-Ort
 * festgelegt ist. Unbekanntes Land (kein GPS): ein gewähltes (b) wirkt streng wie (c), bis das Land bekannt
 * ist (RECHTSLAGE: unbekannt = streng).
 */
public final class TriggerModus {

    public static final String SHOCK = "shock";
    public static final String AI_EVERYWHERE = "ai_everywhere";
    public static final String AI_PRIVATE = "ai_private";
    /** Vierte Wahl: Wächter nimmt nicht auf. */
    public static final String AUS = "aus";
    /** Status, wenn nichts gespeichert ist (kein Config-Wert, nur Anzeige/API). */
    public static final String NICHT_GEWAEHLT = "nicht_gewaehlt";

    private TriggerModus() {}

    public static boolean gueltig(String m) {
        return SHOCK.equals(m) || AI_EVERYWHERE.equals(m) || AI_PRIVATE.equals(m) || AUS.equals(m);
    }

    /** Für Status/API: gespeicherte Wahl oder {@link #NICHT_GEWAEHLT}. */
    public static String status(String gesetzt) {
        return gueltig(gesetzt) ? gesetzt : NICHT_GEWAEHLT;
    }

    /** Ist die KI-Erkennung in diesem Modus überhaupt vorgesehen (b/c)? Nicht gewählt, a und aus: nein. */
    public static boolean kiModus(String gesetzt) {
        return AI_EVERYWHERE.equals(gesetzt) || AI_PRIVATE.equals(gesetzt);
    }

    /** Modus, den der Daemon gerade anwendet (ortsunabhängig, für Anzeige „wirksam“). */
    public static String wirksam(String gesetzt, Laenderprofil.Land land, boolean zonenVorhanden) {
        if (!gueltig(gesetzt)) return SHOCK;          // nicht gewählt → bis zur Wahl nur Erschütterung
        if (AUS.equals(gesetzt)) return AUS;
        String m = gesetzt;
        if (AI_EVERYWHERE.equals(m) && (land == Laenderprofil.Land.SCHWEIZ
                || land == Laenderprofil.Land.UNBEKANNT)) {
            m = AI_PRIVATE;
        }
        if (AI_PRIVATE.equals(m) && !zonenVorhanden) m = SHOCK;
        return m;
    }

    /** Darf die KI-Erkennung an diesem Ort auslösen? */
    public static boolean kiErlaubt(String gesetzt, Laenderprofil.Land land, JSONArray zonen,
                                    double lat, double lon, boolean fix) {
        String m = wirksam(gesetzt, land, zonen != null && zonen.length() > 0);
        if (SHOCK.equals(m) || AUS.equals(m)) return false;
        if (AI_EVERYWHERE.equals(m)) return true;
        return fix && inZone(zonen, lat, lon);
    }

    static boolean inZone(JSONArray zonen, double lat, double lon) {
        if (zonen == null) return false;
        for (int i = 0; i < zonen.length(); i++) {
            JSONObject z = zonen.optJSONObject(i);
            if (z == null) continue;
            double zl = z.optDouble("lat", Double.NaN), zo = z.optDouble("lon", Double.NaN);
            double r = z.optDouble("radiusM", 0);
            if (Double.isNaN(zl) || Double.isNaN(zo) || r <= 0) continue;
            if (distanzM(lat, lon, zl, zo) <= r) return true;
        }
        return false;
    }

    static double distanzM(double lat1, double lon1, double lat2, double lon2) {
        double r = 6_371_000.0;
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
