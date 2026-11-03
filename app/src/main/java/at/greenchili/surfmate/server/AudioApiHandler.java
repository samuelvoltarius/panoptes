package at.greenchili.surfmate.server;

import at.greenchili.surfmate.daemon.CameraDaemon;
import at.greenchili.surfmate.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.OutputStream;

/**
 * Audio API — head-unit audio library and playback.
 *
 * <p><b>Audio library + playback</b> ({@code /api/audio/library*}) — upload / list /
 *       delete user sound files and play them on the interior speakers via
 *       {@link at.greenchili.surfmate.byd.AudioPlaybackController}. Backs the "Play Audio"
 *       automation + key-mapping action. Productive exterior-speaker controls live in
 *       {@link VehicleSoundApiHandler}; diagnostic probes are deliberately unrouted.
 */
public class AudioApiHandler {

    private static final String TAG = "AudioApi";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String cleanPath = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;

        // ── Audio library (for the Play-Audio automation/keymap action) ──
        // GET  /api/audio/library         — list uploaded sounds [{name,path,size}]
        // POST /api/audio/library         — upload { filename, dataBase64 }
        // DELETE /api/audio/library?name= — delete one uploaded sound
        if (cleanPath.equals("/api/audio/library") && method.equals("GET")) {
            handleAudioLibraryList(out);
            return true;
        }
        if (cleanPath.equals("/api/audio/library") && method.equals("POST")) {
            handleAudioLibraryUpload(out, body);
            return true;
        }
        if (cleanPath.equals("/api/audio/library") && method.equals("DELETE")) {
            handleAudioLibraryDelete(out, path);
            return true;
        }
        // Raw bytes of one uploaded sound, streamed to the app-process media player.
        // The app UID (SELinux untrusted_app) CANNOT read /data/user_de/0/com.android.shell/surfmate/.surfmate
        // directly — daemon-written log/config files there are only reachable from the
        // app via a shell exec, and the locale/device-id managers document the same
        // cross-UID wall. So the app-process MediaPlaybackService/VideoPlaybackActivity
        // cannot open the library file by path; instead it streams the bytes from here
        // (authenticated localhost, same model the recordings player uses for /video/).
        if (cleanPath.equals("/api/audio/library/raw") && method.equals("GET")) {
            // Fallback only — HttpServer routes this path to handleRawRanged() first
            // (with the Range header). Reached here only if that route is bypassed;
            // serve without range so the endpoint still works.
            handleAudioLibraryRaw(out, path, null);
            return true;
        }
        // Preview an uploaded sound from the web UI (plays on the head unit).
        if (cleanPath.equals("/api/audio/library/play") && method.equals("POST")) {
            handleAudioLibraryPlay(out, body);
            return true;
        }
        if (cleanPath.equals("/api/audio/library/stop") && method.equals("POST")) {
            at.greenchili.surfmate.byd.AudioPlaybackController.stop();
            JSONObject r = new JSONObject();
            r.put("success", true);
            HttpResponse.sendJson(out, r.toString());
            return true;
        }

        return false;
    }

    // ==================== AUDIO LIBRARY ====================

    // Where uploaded sounds live. World-readable dir so the daemon (UID 2000) can
    // both write (here, running as the daemon) and play them back.
    private static final String AUDIO_DIR = at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/.surfmate/audio");
    private static final String[] AUDIO_EXTS = { "mp3", "wav", "mp4", "m4a", "aac", "ogg" };
    // 48 MB per file — enough for a short alert/deterrent video clip, while keeping the
    // base64-in-JSON upload's transient memory safe on the daemon (a 48 MB file is a
    // ~64 MB base64 body → ~128 MB UTF-16 String + 48 MB decoded ≈ 190 MB peak). Going
    // materially higher would need a stream-to-disk upload rewrite rather than the
    // current buffer-then-decode path. Must stay <= HttpServer's audio-endpoint body cap.
    private static final long MAX_AUDIO_BYTES = 48L * 1024 * 1024;

    private static boolean isAudioExt(String ext) {
        for (String e : AUDIO_EXTS) if (e.equals(ext)) return true;
        return false;
    }

    /** Sanitize a user filename to a safe basename (no path traversal, bounded). */
    private static String safeAudioName(String filename) {
        if (filename == null) return null;
        // Strip any directory components.
        String base = new java.io.File(filename).getName().trim();
        if (base.isEmpty() || base.length() > 80) return null;
        // Allow letters/digits/space/._- only; collapse anything else to '_'.
        StringBuilder sb = new StringBuilder(base.length());
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-' || c == ' ';
            sb.append(ok ? c : '_');
        }
        String cleaned = sb.toString();
        int dot = cleaned.lastIndexOf('.');
        if (dot <= 0 || dot >= cleaned.length() - 1) return null; // must have an extension
        if (!isAudioExt(cleaned.substring(dot + 1).toLowerCase())) return null;
        return cleaned;
    }

    /** GET /api/audio/library — list uploaded sounds. */
    private static void handleAudioLibraryList(OutputStream out) throws Exception {
        JSONObject resp = new JSONObject();
        org.json.JSONArray arr = new org.json.JSONArray();
        try {
            java.io.File dir = new java.io.File(AUDIO_DIR);
            java.io.File[] files = dir.exists() ? dir.listFiles() : null;
            if (files != null) {
                java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (java.io.File f : files) {
                    if (!f.isFile()) continue;
                    String name = f.getName();
                    int dot = name.lastIndexOf('.');
                    if (dot < 0 || !isAudioExt(name.substring(dot + 1).toLowerCase())) continue;
                    JSONObject o = new JSONObject();
                    o.put("name", name);
                    o.put("path", f.getAbsolutePath());
                    o.put("size", f.length());
                    arr.put(o);
                }
            }
            resp.put("success", true);
            resp.put("sounds", arr);
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, resp.toString());
    }

    /** POST /api/audio/library — upload { filename, dataBase64 }. Mirrors the
     *  screen-deterrent image upload (base64 body, atomic write, world-readable). */
    private static void handleAudioLibraryUpload(OutputStream out, String body) throws Exception {
        if (body == null || body.isEmpty()) { HttpResponse.sendJsonError(out, "Empty request body"); return; }
        JSONObject req;
        try { req = new JSONObject(body); } catch (Exception e) { HttpResponse.sendJsonError(out, "Invalid JSON"); return; }

        String name = safeAudioName(req.optString("filename", ""));
        if (name == null) { HttpResponse.sendJsonError(out, "Invalid filename (need .mp3/.wav/.mp4/.m4a/.aac/.ogg)"); return; }
        String dataB64 = req.optString("dataBase64", "");
        if (dataB64.isEmpty()) { HttpResponse.sendJsonError(out, "Missing dataBase64"); return; }
        int comma = dataB64.indexOf(',');
        if (dataB64.startsWith("data:") && comma > 0) dataB64 = dataB64.substring(comma + 1);

        byte[] data;
        try { data = android.util.Base64.decode(dataB64, android.util.Base64.DEFAULT); }
        catch (Exception e) { HttpResponse.sendJsonError(out, "Invalid base64"); return; }
        if (data.length == 0) { HttpResponse.sendJsonError(out, "Empty file"); return; }
        if (data.length > MAX_AUDIO_BYTES) { HttpResponse.sendJsonError(out, "File too large (max 48MB)"); return; }

        java.io.File dir = new java.io.File(AUDIO_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
            try { dir.setReadable(true, false); dir.setExecutable(true, false); } catch (Exception ignored) {}
        }
        // Sweep stale temp files before writing. A temp orphaned by an earlier
        // interrupted upload (daemon killed, power cut, the rename race below) is
        // INVISIBLE and UNDELETABLE through the API: the list filters on audio
        // extensions so ".tmp" never appears, and delete runs the name through
        // safeAudioName which rejects ".tmp". Without this sweep each orphan is
        // permanent, and they accumulate on the partition that also holds the
        // config, the H2 stores and the logs.
        sweepStaleTempFiles(dir);

        java.io.File outFile = new java.io.File(dir, name);
        // Note whether we are REPLACING an existing sound. Deliberately not an
        // error — re-uploading a corrected file under the same name is a normal
        // thing to do, and rejecting it would be worse. But it is worth telling
        // the caller, because safeAudioName collapses disallowed characters to
        // '_', so `a*b.mp3` and `a?b.mp3` both land on `a_b.mp3`: a user can
        // silently replace a sound they never intended to touch, and every
        // automation or key-mapping bound to that name then plays different audio
        // with no indication anything changed.
        boolean replaced = outFile.exists();

        // UNIQUE temp name. It used to be `name + ".tmp"`, derived purely from the
        // filename — so two concurrent uploads of the same name both opened the
        // SAME temp with O_TRUNC, each wrote from its own offset 0, and the winner
        // renamed a byte-level hybrid of two different files into place and
        // reported success. Worse, either request's failure-path delete() unlinked
        // the other's in-flight temp. A per-request suffix makes both uploads
        // independent; last rename wins cleanly, which is the expected semantic
        // for same-name uploads.
        java.io.File tmpFile = new java.io.File(dir,
            name + "." + java.util.UUID.randomUUID() + ".tmp");
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmpFile)) {
            fos.write(data);
            fos.getFD().sync();
        } catch (Throwable t) {
            try { tmpFile.delete(); } catch (Exception ignored) {}
            HttpResponse.sendJsonError(out, "Write failed: " + t.getMessage());
            return;
        }
        try { tmpFile.setReadable(true, false); } catch (Exception ignored) {}
        if (!tmpFile.renameTo(outFile)) {
            try { tmpFile.delete(); } catch (Exception ignored) {}
            HttpResponse.sendJsonError(out, "Rename failed");
            return;
        }
        try { outFile.setReadable(true, false); } catch (Exception ignored) {}

        if (replaced) {
            CameraDaemon.log("audio: upload REPLACED existing sound '" + name
                + "' — automations/key-mappings bound to this name now play the new file");
        }

        JSONObject resp = new JSONObject();
        resp.put("success", true);
        resp.put("name", name);
        resp.put("path", outFile.getAbsolutePath());
        // Lets the UI warn "replaced an existing sound" instead of the user only
        // discovering it when an automation plays something unexpected.
        resp.put("replaced", replaced);
        HttpResponse.sendJson(out, resp.toString());
    }

    /** Age after which an abandoned {@code *.tmp} in the audio dir is reapable.
     *  Comfortably longer than any real upload write (the bytes are already fully
     *  decoded in memory before the stream is opened, so the write is a single
     *  buffered dump), while still bounded. */
    private static final long STALE_TMP_AGE_MS = 5L * 60L * 1000L;

    /** Delete abandoned {@code *.tmp} uploads older than {@link #STALE_TMP_AGE_MS}.
     *  These are otherwise unreachable: the library listing filters on audio
     *  extensions so they never render, and DELETE routes the name through
     *  {@code safeAudioName}, which rejects a {@code .tmp} extension. Best-effort
     *  and never fatal — an upload must not fail because a cleanup did. */
    private static void sweepStaleTempFiles(java.io.File dir) {
        try {
            java.io.File[] files = dir.listFiles();
            if (files == null) return;
            long cutoff = System.currentTimeMillis() - STALE_TMP_AGE_MS;
            for (java.io.File f : files) {
                if (f == null || !f.isFile()) continue;
                if (!f.getName().endsWith(".tmp")) continue;
                if (f.lastModified() > cutoff) continue;   // possibly still being written
                long size = f.length();
                if (f.delete()) {
                    CameraDaemon.log("audio: reaped stale temp " + f.getName()
                        + " (" + size + " bytes)");
                }
            }
        } catch (Throwable ignored) {
            // Cleanup is opportunistic; never let it break an upload.
        }
    }

    /** DELETE /api/audio/library?name=xxx — remove one uploaded sound. */
    private static void handleAudioLibraryDelete(OutputStream out, String path) throws Exception {
        JSONObject resp = new JSONObject();
        String name = null;
        int q = path.indexOf("?name=");
        if (q >= 0) {
            name = java.net.URLDecoder.decode(path.substring(q + "?name=".length()), "UTF-8");
        }
        String safe = safeAudioName(name);
        if (safe == null) { HttpResponse.sendJsonError(out, "Invalid name"); return; }
        java.io.File f = new java.io.File(AUDIO_DIR, safe);
        boolean deleted = f.exists() && f.delete();
        resp.put("success", deleted);
        if (!deleted) resp.put("error", "Not found");
        HttpResponse.sendJson(out, resp.toString());
    }

    /** POST /api/audio/library/play — preview { name, channel? } on the head unit. */
    private static void handleAudioLibraryPlay(OutputStream out, String body) throws Exception {
        JSONObject resp = new JSONObject();
        // Guarded parse, mirroring the upload handler. Unguarded, a malformed body
        // threw JSONException all the way out to HttpServer's catch, which closes
        // the socket WITHOUT writing a response — the client saw a network error
        // instead of a clean {"success":false}.
        JSONObject req;
        try {
            req = (body == null || body.isEmpty()) ? new JSONObject() : new JSONObject(body);
        } catch (Exception e) {
            HttpResponse.sendJsonError(out, "Invalid JSON");
            return;
        }
        String safe = safeAudioName(req.optString("name", ""));
        if (safe == null) { HttpResponse.sendJsonError(out, "Invalid name"); return; }
        String channel = req.optString("channel", "media");
        java.io.File f = new java.io.File(AUDIO_DIR, safe);
        // Verify the sound still EXISTS before claiming success. dispatchPlay is
        // fire-and-forget (it shells `am` and returns true unconditionally), so a
        // sound the user has since deleted — or an automation still holding a stale
        // name — reported {"success":true} while nothing played, with only logcat
        // showing the app-side 404. Fail honestly instead.
        if (!f.isFile() || f.length() == 0) {
            HttpResponse.sendJsonError(out, "Sound not found: " + safe);
            return;
        }
        boolean ok = at.greenchili.surfmate.byd.AudioPlaybackController.play(f.getAbsolutePath(), channel);
        resp.put("success", ok);
        if (!ok) resp.put("error", "Could not play");
        HttpResponse.sendJson(out, resp.toString());
    }

    /**
     * GET /api/audio/library/raw?name=xxx — stream one uploaded sound/video's raw bytes,
     * with HTTP Range support. Routed directly from {@link HttpServer} (ahead of the
     * generic handler) so the {@code rangeHeader} reaches us. Consumed by the app-process
     * media player (which can't open the file by path; see the route comment). The name is
     * sanitized to a basename, so this can only ever serve a file that already exists
     * inside {@link #AUDIO_DIR} — no traversal.
     *
     * <p>Range matters for VIDEO: a streaming MediaPlayer/VideoView issues Range requests
     * to locate a non-faststart MP4's trailing {@code moov} atom; without a 206 reply the
     * extractor stalls and "Play Video" showed nothing (audio still worked because MP3
     * streams linearly). {@link HttpResponse#sendMediaFileRanged} serves 206 on a Range
     * request and a plain 200 otherwise.
     */
    public static boolean handleRawRanged(String path, String rangeHeader, OutputStream out) throws Exception {
        handleAudioLibraryRaw(out, path, rangeHeader);
        return true;
    }

    private static void handleAudioLibraryRaw(OutputStream out, String path, String rangeHeader) throws Exception {
        String name = null;
        int q = path.indexOf("?name=");
        if (q >= 0) {
            name = java.net.URLDecoder.decode(path.substring(q + "?name=".length()), "UTF-8");
        }
        String safe = safeAudioName(name);
        if (safe == null) { HttpResponse.sendError(out, 400, "Invalid name"); return; }
        java.io.File f = new java.io.File(AUDIO_DIR, safe);
        if (!f.exists() || !f.isFile()) { HttpResponse.sendError(out, 404, "Not found"); return; }
        String ext = safe.substring(safe.lastIndexOf('.') + 1).toLowerCase();
        HttpResponse.sendMediaFileRanged(out, f, mimeForAudioExt(ext), rangeHeader);
    }

    /** MIME type for a validated audio/video extension (used by the raw stream). */
    private static String mimeForAudioExt(String ext) {
        switch (ext) {
            case "mp3":  return "audio/mpeg";
            case "wav":  return "audio/wav";
            case "m4a":  return "audio/mp4";
            case "aac":  return "audio/aac";
            case "ogg":  return "audio/ogg";
            case "mp4":  return "video/mp4";
            default:      return "application/octet-stream";
        }
    }

}
