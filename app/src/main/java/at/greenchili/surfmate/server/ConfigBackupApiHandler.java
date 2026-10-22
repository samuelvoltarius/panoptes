package at.greenchili.surfmate.server;

import at.greenchili.surfmate.config.ConfigBackupService;
import at.greenchili.surfmate.updater.AppUpdater;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;

/**
 * Settings Backup / Restore API — the WEB surface of the backup feature.
 *
 * This handler runs IN the camera-daemon process (UID 2000, same as the HTTP
 * server), so it calls the shared {@link ConfigBackupService} core DIRECTLY —
 * exactly as {@link UpdateApiHandler} calls {@link AppUpdater} directly. The
 * EXPORT_CONFIG / REPLACE_CONFIG IPC commands in {@link SurveillanceIpcServer}
 * exist for the OTHER-process surfaces (app UID, Telegram daemon); all three
 * funnel through the one core, so nothing is duplicated.
 *
 * Endpoints (all require AuthMiddleware JWT — /api/backup/* is a non-public
 * path, so a token is enforced in EVERY access mode, tunnel included):
 *   GET  /api/backup/export          → the bundle JSON (UI saves it as a file)
 *        ?trips=true      → + Fahrten (Statistik)
 *        ?metadaten=true  → + Aufnahmen-Index, Park-/Wächter-Sitzungen, Fahrten-Zähler (0.1.11, nur Metadaten,
 *                           für update-pflicht: Bestand vor dem Update; wird nicht wiederhergestellt)
 *        ?orte=true       → + Ortsliste (0.1.11, enthält Standorte; ohne Haken wird sie aus dem Export entfernt)
 *   POST /api/backup/import/preview  → {valid, message, warnings[]} — no write
 *   POST /api/backup/import?confirm=true → applies the bundle atomically
 *
 * Backup/restore are allowed over the tunnel (public mode) by design choice —
 * they rely on JWT auth alone, unlike OTA install which additionally blocks
 * public mode. The export bundle DOES contain device key material + encrypted
 * credentials, so the UI warns the user to keep the file private.
 *
 * SAME-DEVICE only: the UI states this, validateBundle warns on model/firmware
 * mismatch, and the bundle carries the device-id so secrets survive a reset.
 */
public class ConfigBackupApiHandler {

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        if (path.startsWith("/api/backup/export") && method.equals("GET")) {
            handleExport(path, out);
            return true;
        }
        if (path.equals("/api/backup/import/preview") && method.equals("POST")) {
            handleImport(body, out, /* preview */ true, path);
            return true;
        }
        if (path.startsWith("/api/backup/import") && method.equals("POST")) {
            handleImport(body, out, /* preview */ false, path);
            return true;
        }
        return false;
    }

    // ================== export ==================

    private static void handleExport(String path, OutputStream out) throws Exception {
        // JWT-gated (AuthMiddleware) in every mode. Allowed over the tunnel by
        // design — the bundle holds credentials, so the UI warns to keep it
        // private, but no public-mode block here (owner's choice).
        // ?trips=true opts the (large-ish, location-bearing) trip history into
        // the bundle; default is settings-only.
        boolean includeTrips = hasParamTrue(path, "trips");
        boolean metadaten = hasParamTrue(path, "metadaten");
        // 0.1.11 ORTE_VERTRAG: ?orte=true – Ortsliste mitnehmen (enthält Standorte; Download Standard aus,
        // Update-Sicherungen von update-pflicht immer mit orte=true)
        boolean orte = hasParamTrue(path, "orte");
        try {
            JSONObject bundle = ConfigBackupService.buildBundle(
                    AppUpdater.getInstalledVersion(),
                    deviceModel(),
                    System.currentTimeMillis(),
                    includeTrips,
                    orte);
            if (metadaten) {
                bundle.put("metadaten", metadatenSammeln());
                JSONObject manifest = bundle.optJSONObject("manifest");
                JSONArray cats = manifest == null ? null : manifest.optJSONArray("categories");
                if (cats != null) cats.put("metadaten");
            }
            HttpResponse.sendJson(out, bundle.toString());
        } catch (Exception e) {
            HttpResponse.sendJsonError(out, "Could not build backup: " + e.getMessage());
        }
    }

    // ================== import / preview ==================

    private static void handleImport(String body, OutputStream out, boolean preview, String path)
            throws Exception {
        // JWT-gated (AuthMiddleware) in every mode; allowed over the tunnel by
        // design. A destructive write still needs an explicit confirm=true
        // (mirrors /api/update/install). Preview never writes, so it's exempt.
        // Parse the query param properly (not a substring match) so a stray
        // "confirm=true" inside another value can't satisfy the gate.
        if (!preview && !hasConfirmTrue(path)) {
            HttpResponse.sendJsonError(out, "Restore requires confirmation.");
            return;
        }
        if (body == null || body.trim().isEmpty()) {
            HttpResponse.sendJsonError(out, "No backup data received.");
            return;
        }

        JSONObject bundle;
        try {
            // Body is the bundle JSON directly (the UI POSTs the parsed file).
            bundle = new JSONObject(body);
        } catch (Exception e) {
            HttpResponse.sendJsonError(out, "File is not a valid Panoptes backup.");
            return;
        }

        String appVer = AppUpdater.getInstalledVersion();
        String model = deviceModel();

        ConfigBackupService.ApplyResult result = preview
                ? ConfigBackupService.validateBundle(bundle, appVer, model)
                : ConfigBackupService.applyBundle(bundle, appVer, model);

        JSONObject r = new JSONObject();
        r.put("valid", result.getSuccess());
        r.put("success", result.getSuccess());
        r.put("message", result.getMessage());
        r.put("warnings", new JSONArray(result.getWarnings()));
        r.put("restartRequired", result.getRestartRequired());
        if (!preview && result.getSuccess()) {
            // Kept for older web clients. A restored vehicle-mode change is separately marked
            // restartRequired and remains fenced to the old active mode until that restart.
            r.put("reloadRecommended", true);
        }
        HttpResponse.sendJson(out, r.toString());
    }

    /** 0.1.11: Metadaten aus den Diensten dieses Prozesses (Daemon). Fehlt ein Dienst, steht verfuegbar:false. */
    private static JSONObject metadatenSammeln() throws Exception {
        BackupMetadaten.Seiten aufnahmen = null;
        try {
            RecordingsIndex idx = RecordingsIndex.getInstance();
            if (idx.isAvailable()) aufnahmen = idx::exportMetadaten;
        } catch (Throwable ignored) {
        }
        at.greenchili.surfmate.parking.ParkingStore tmp = null;
        BackupMetadaten.Seiten parken = null;
        try {
            at.greenchili.surfmate.parking.ParkingController c =
                    at.greenchili.surfmate.daemon.CameraDaemon.getParkingController();
            at.greenchili.surfmate.parking.ParkingStore st = c == null ? null : c.store();
            if (st == null || !st.isOpen()) {
                // Funktion aus: Verlauf aus einer kurz geöffneten DB lesen, nie eine neue anlegen
                if (new java.io.File(at.greenchili.surfmate.parking.ParkingStore.DEFAULT_DB_PATH + ".mv.db").isFile()) {
                    tmp = new at.greenchili.surfmate.parking.ParkingStore();
                    st = tmp.open() ? tmp : null;
                } else {
                    st = null;
                }
            }
            final at.greenchili.surfmate.parking.ParkingStore store = st;
            if (store != null) {
                parken = (limit, offset) -> {
                    java.util.List<JSONObject> l = new java.util.ArrayList<>();
                    for (at.greenchili.surfmate.parking.ParkingSession ps : store.listSessions(0, 0, limit, offset)) {
                        l.add(ps.toJson());
                    }
                    return l;
                };
            }
            int fahrten = -1, fehlt = 0;
            try {
                at.greenchili.surfmate.trips.TripAnalyticsManager tam =
                        at.greenchili.surfmate.daemon.CameraDaemon.getTripAnalyticsManager();
                at.greenchili.surfmate.trips.TripDatabase db = tam == null ? null : tam.getDatabase();
                if (db != null && db.isAvailable()) {
                    fahrten = db.getTripCount();
                    fehlt = db.getAnzahlDateiFehlt();
                }
            } catch (Throwable ignored) {
            }
            return BackupMetadaten.bauen(aufnahmen, parken, fahrten, fehlt, System.currentTimeMillis());
        } finally {
            if (tmp != null) tmp.close();
        }
    }

    /** Exakter Abfrageparameter {@code name=true} (kein Teilstring-Treffer). */
    static boolean hasParamTrue(String path, String name) {
        if (path == null) return false;
        int q = path.indexOf('?');
        if (q < 0) return false;
        for (String param : path.substring(q + 1).split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && name.equals(kv[0]) && "true".equals(kv[1])) return true;
        }
        return false;
    }

    /** Exact-match the {@code confirm=true} query parameter (no substring match). */
    private static boolean hasConfirmTrue(String path) {
        if (path == null) return false;
        int q = path.indexOf('?');
        if (q < 0) return false;
        for (String param : path.substring(q + 1).split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && "confirm".equals(kv[0]) && "true".equals(kv[1])) {
                return true;
            }
        }
        return false;
    }

    private static String deviceModel() {
        try {
            String m = android.os.Build.MODEL;
            return (m == null || m.isEmpty()) ? "unknown" : m;
        } catch (Exception e) {
            return "unknown";
        }
    }
}
