package at.greenchili.surfmate.server;

import at.greenchili.surfmate.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.OutputStream;

/**
 * RoadSense-Karte: Projektion ins Kombiinstrument (start / stop / status).
 *
 * <p>G3 (05.11.2026): Die frueheren Routing-Schluessel-Endpunkte {@code /api/navmap/routing/status|setup|clear}
 * (Stadia/BYOK) sind entfernt. Geroutet wird ueber den eigenen Kern ({@code /api/navi/fuehrung} -> Panoptes-Planer),
 * im Auto liegt kein Routing-Schluessel mehr. Ein frueher gespeicherter Schluessel ({@code navMap.routingApiKey},
 * verschluesselt) bleibt unveraendert in der Konfiguration liegen und wird nicht mehr gelesen - nichts geht verloren.
 *
 * Endpoints:
 *  - POST /api/navmap/cluster/start  -> {success, available, projecting}
 *  - POST /api/navmap/cluster/stop   -> {success, available, projecting}
 *  - GET  /api/navmap/cluster/status -> {success, available, projecting}
 */
public class NavMapApiHandler {

    private static final String TAG = "NavMapApiHandler";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    /**
     * Handle RoadSense Map API requests.
     * @return true if handled
     */
    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String cleanPath = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;

        // Cluster projection: start / stop / status for projecting the map onto
        // the driver-cluster display (a SUSTAINED holder of ClusterProjectionController).
        if (cleanPath.equals("/api/navmap/cluster/start") && method.equals("POST")) {
            boolean accepted = at.greenchili.surfmate.navmap.ClusterMapProjector.start();
            sendClusterStatus(out, accepted);
            return true;
        }
        if (cleanPath.equals("/api/navmap/cluster/stop") && method.equals("POST")) {
            at.greenchili.surfmate.navmap.ClusterMapProjector.stop();
            sendClusterStatus(out);
            return true;
        }
        if (cleanPath.equals("/api/navmap/cluster/status") && method.equals("GET")) {
            sendClusterStatus(out);
            return true;
        }
        return false;
    }

    /** Current cluster-projection state. */
    private static void sendClusterStatus(OutputStream out) throws Exception {
        sendClusterStatus(out, true);
    }

    private static void sendClusterStatus(OutputStream out, boolean operationAccepted)
            throws Exception {
        JSONObject response = new JSONObject();
        try {
            response.put("success", operationAccepted);
            response.put("available", at.greenchili.surfmate.tacho.TachoFaehigkeit.karteVerfuegbar());
            response.put("projecting", at.greenchili.surfmate.navmap.ClusterMapProjector.isActive());
            if (!operationAccepted) response.put("error", "cluster_not_available_or_not_admitted");
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }
}
