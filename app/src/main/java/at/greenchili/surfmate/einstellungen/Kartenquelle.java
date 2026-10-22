package at.greenchili.surfmate.einstellungen;

import org.json.JSONObject;

/**
 * Kartenquelle (Datenschutz Regel Nr. 2, Koordination 21.10.): Der Nutzer wählt selbst, woher Karten
 * kommen. Einstellung {@code karte.quelle} = community | osm | keine; nicht gesetzt oder unbekannt =
 * keine. Gilt fürs ganze Auto: Web (shared/kartenquelle.js, Neubau 2) und native Karte lesen denselben
 * Wert. Kein stiller Rückfall auf einen anderen Anbieter.
 */
public final class Kartenquelle {

    private Kartenquelle() {}

    public static final String COMMUNITY = "community";
    public static final String OSM = "osm";
    public static final String KEINE = "keine";

    /** Eigene Europakarte des Projekts (wie panoptes-map.js OWN_STYLE). */
    public static final String COMMUNITY_STIL = "https://byd.serveone.at/tiles/style/surfmate-dark.json";
    /** Rasterkacheln (wie panoptes-map.js OSM_TILES). */
    public static final String OSM_KACHELN = "https://tile.openstreetmap.de/{z}/{x}/{y}.png";

    /** Wert aus der Config; alles außer community/osm gilt als keine. */
    public static String aus(JSONObject root) {
        JSONObject k = root == null ? null : root.optJSONObject("karte");
        String q = k == null ? null : k.optString("quelle", null);
        return COMMUNITY.equals(q) || OSM.equals(q) ? q : KEINE;
    }

    /** Aktueller Wert; bei jedem Lesefehler keine (es geht dann nichts raus). */
    public static String aktuell() {
        try {
            return aus(at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig());
        } catch (Throwable t) {
            return KEINE;
        }
    }

    /** Stil ohne jede Quelle: nur Hintergrund, lädt nichts aus dem Netz. */
    public static String leererStil(boolean nacht) {
        return "{\"version\":8,\"sources\":{},\"layers\":[{\"id\":\"background\",\"type\":\"background\","
                + "\"paint\":{\"background-color\":\"" + (nacht ? "#0b2236" : "#e8eef2") + "\"}}]}";
    }

    /** OSM-Rasterstil; nachts wie im Web umgefärbt, damit er zum dunklen Thema passt. */
    public static String osmStil(boolean nacht) {
        String farbe = nacht
                ? ",\"paint\":{\"raster-brightness-min\":0.78,\"raster-brightness-max\":0.06,"
                  + "\"raster-hue-rotate\":180,\"raster-saturation\":-0.45,\"raster-contrast\":0.05}"
                : "";
        return "{\"version\":8,\"sources\":{\"osm\":{\"type\":\"raster\",\"tiles\":[\"" + OSM_KACHELN + "\"],"
                + "\"tileSize\":256,\"maxzoom\":19,\"attribution\":\"© OpenStreetMap\"}},"
                + "\"layers\":[{\"id\":\"background\",\"type\":\"background\",\"paint\":{\"background-color\":\""
                + (nacht ? "#0b2236" : "#e8eef2") + "\"}},"
                + "{\"id\":\"osm\",\"type\":\"raster\",\"source\":\"osm\"" + farbe + "}]}";
    }
}
