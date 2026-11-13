package at.greenchili.surfmate.navmap.nav;

import org.json.JSONObject;

/**
 * 0.1.44 (P3, LUECKEN_0142 Punkt 5): EINE Stelle für das aktuelle Tempolimit, das der Launcher zeigt.
 *
 * <p>Nur ein belegter Wert zählt: {@link #setze} mit Quelle (z. B. {@link #QUELLE_OSM} = OpenStreetMap {@code maxspeed}
 * entlang der Panoptes-Route, ODbL). Ohne Wert, mit unbekannter Quelle oder älter als {@link #FRISCH_MS} liefert
 * {@link #json} {@code null} – dann zeigt der Launcher nichts (nie raten, nie ein altes Limit als aktuell).
 *
 * <p>Stand 0.1.44: Weder das BYD-SDK am Surf (kein Getter der Verkehrszeichen-Erkennung) noch die Führung
 * ({@code fuehrung/1} des Planers) liefern ein Limit. Die Quelle kommt mit dem Planer-Feld {@code tempolimits}
 * (Valhalla {@code edge.speed_limit} = OSM {@code maxspeed}); bis dahin bleibt der Wert leer.
 */
public final class TempolimitStand {

    public static final String QUELLE_OSM = "osm";
    /** Älter → nicht mehr zeigen (Fahrt bewegt sich, Limits wechseln). */
    public static final long FRISCH_MS = 15_000L;

    private static final Object LOCK = new Object();
    private static int kmh = -1;
    private static String quelle;
    private static long zeitMs;

    private TempolimitStand() {}

    /** Neuer belegter Wert; {@code kmhNeu ≤ 0} oder unbekannte Quelle löscht ihn. */
    public static void setze(int kmhNeu, String quelleNeu, long jetztMs) {
        synchronized (LOCK) {
            if (kmhNeu <= 0 || kmhNeu > 200 || !QUELLE_OSM.equals(quelleNeu)) {
                kmh = -1;
                quelle = null;
            } else {
                kmh = kmhNeu;
                quelle = quelleNeu;
            }
            zeitMs = jetztMs;
        }
    }

    public static void loeschen() {
        synchronized (LOCK) {
            kmh = -1;
            quelle = null;
            zeitMs = 0;
        }
    }

    /** {@code summary.tempolimit}: {kmh, quelle, alterMs} oder null (kein frischer, belegter Wert). */
    public static JSONObject json(long jetztMs) {
        synchronized (LOCK) {
            if (kmh <= 0 || quelle == null) return null;
            long alter = jetztMs - zeitMs;
            if (alter < 0 || alter > FRISCH_MS) return null;
            try {
                return new JSONObject().put("kmh", kmh).put("quelle", quelle).put("alterMs", alter);
            } catch (Exception e) {
                return null;
            }
        }
    }
}
