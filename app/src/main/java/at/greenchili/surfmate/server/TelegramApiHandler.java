package at.greenchili.surfmate.server;

import at.greenchili.surfmate.logging.DaemonLogger;
import at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Telegram bot configuration HTTP endpoints.
 *
 * Reads and writes the {@code telegram} section of {@code UnifiedConfigManager}
 * — the same section the native settings fragment and the daemon use. The
 * bot token is encrypted at rest via {@code CredentialCipher}; we decrypt
 * here only when we need to call out to {@code api.telegram.org}.
 *
 * Endpoints:
 *   GET  /api/telegram/status     — bot + owner state for the UI
 *   POST /api/telegram/token      — validate via Telegram getMe + persist
 *   GET  /api/telegram/kategorien — 0.1.11: Kategorien aus der Kern-Registry (nur Ein/Aus, Stufe = Sortierung)
 *   POST /api/telegram/preferences — Meldungsarten, Ruhezeiten (quietHours),
 *                                    Fernbefehle (remote); nur gesendete Felder;
 *                                    0.1.11: kategorien {id: bool}
 *   POST /api/telegram/pin        — generate 6-digit pairing PIN (10-min TTL)
 *   POST /api/telegram/owner/clear — strip owner_* keys (re-pair flow)
 *   POST /api/telegram/test       — Testnachricht über den Bot-Dienst
 *   POST /api/telegram/clear      — wipe token + bot info + owner
 */
public class TelegramApiHandler {

    private static final String TAG = "TelegramApi";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private static final long PIN_TTL_MS = 10 * 60 * 1000L; // 10 minutes

    // SecureRandom (not Math.random()) — the PIN gates Telegram pairing,
    // which is the only authentication boundary between an attacker who
    // can reach the bot and our device. Use the same RNG flavor that
    // PairingManager uses for its in-memory PIN.
    private static final java.security.SecureRandom PIN_RNG =
            new java.security.SecureRandom();

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String cleanPath = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;

        if (cleanPath.equals("/api/telegram/status") && method.equals("GET")) {
            handleStatus(out);
            return true;
        }
        if (cleanPath.equals("/api/telegram/token") && method.equals("POST")) {
            handleToken(out, body);
            return true;
        }
        if (cleanPath.equals("/api/telegram/kategorien") && method.equals("GET")) {
            handleKategorien(out);
            return true;
        }
        if (cleanPath.equals("/api/telegram/preferences") && method.equals("POST")) {
            handlePreferences(out, body);
            return true;
        }
        if (cleanPath.equals("/api/telegram/pin") && method.equals("POST")) {
            handlePin(out);
            return true;
        }
        if (cleanPath.equals("/api/telegram/owner/clear") && method.equals("POST")) {
            handleOwnerClear(out);
            return true;
        }
        if (cleanPath.equals("/api/telegram/media-notice/ack") && method.equals("POST")) {
            // Pflichttext E bestätigt/gelesen: nur mediaNoticeAckAt, nie videoUploads.
            JSONObject response = new JSONObject();
            response.put("success", UnifiedTelegramConfig.setMediaNoticeAckNow());
            response.put("mediaNoticeAckAt", UnifiedTelegramConfig.getMediaNoticeAckAt());
            HttpResponse.sendJson(out, response.toString());
            return true;
        }
        if (cleanPath.equals("/api/telegram/test") && method.equals("POST")) {
            handleTest(out);
            return true;
        }
        if (cleanPath.equals("/api/telegram/clear") && method.equals("POST")) {
            handleClear(out);
            return true;
        }
        return false;
    }

    // ────────────────────────────── Endpoints ──────────────────────────────

    /**
     * GET /api/telegram/status — combines token-presence + pairing state +
     * preferences into a single response so the web UI can paint without
     * three round trips.
     */
    private static void handleStatus(OutputStream out) throws Exception {
        // forceReload before the read so a token/owner write made by the
        // app process (different UID, different mtime tick) is reflected
        // in the web tab without waiting on cache eviction. The cost is
        // one re-parse of a small JSON file per status hit.
        at.greenchili.surfmate.config.UnifiedConfigManager.forceReload();
        boolean configured = UnifiedTelegramConfig.hasBotToken();
        // Self-heal users who were left with orphan bot identity (and/or
        // owner) after a failed migration or partial clear: if there's
        // no token, neither bot info nor owner is meaningful — wipe them
        // so the UI doesn't show "Configured as @Foo" / "Paired with X"
        // alongside a Not-set-up state. Idempotent: same delta becomes a
        // no-op once cleaned.
        if (!configured) {
            UnifiedTelegramConfig.clearOrphanIdentityIfTokenMissing();
        }
        long ownerChatId = UnifiedTelegramConfig.getOwnerChatId();
        boolean paired = configured && ownerChatId > 0;

        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("configured", configured);
        response.put("paired", paired);
        response.put("enabled", configured && paired);
        // Don't echo the raw token back — the UI never needs it after first
        // validation. Length is enough to confirm the field is set.
        response.put("hasToken", configured);
        if (paired) {
            response.put("ownerChatId", ownerChatId);
            response.put("ownerUsername", UnifiedTelegramConfig.getOwnerUsername());
            response.put("ownerFirstName", UnifiedTelegramConfig.getOwnerFirstName());
        }
        // Cached bot info from last validation (so the UI can show
        // "Connected as @Foo" without an extra round trip to Telegram).
        // Only emit when a token is actually configured — orphan bot
        // identity (username/first_name without a token, possible after
        // a half-completed migration or a failed clear) confused users
        // who saw "Configured as @Foo" while the integration card said
        // "Not set up". Tying the emit to `configured` keeps the UI
        // consistent with hasBotToken().
        if (configured) {
            String botUsername = UnifiedTelegramConfig.getBotUsername();
            String botFirstName = UnifiedTelegramConfig.getBotFirstName();
            if (!botUsername.isEmpty()) response.put("botUsername", botUsername);
            if (!botFirstName.isEmpty()) response.put("botFirstName", botFirstName);
        }

        // Preferences — single source of truth in the unified config.
        response.put("videoUploads",        UnifiedTelegramConfig.isVideoUploads());
        response.put("autoStartAccOff",     UnifiedTelegramConfig.isAutoStartAccOff());
        response.put("tyreAlerts",          UnifiedTelegramConfig.isTyreAlerts());
        response.put("parkingMessages",     UnifiedTelegramConfig.isParkingMessages());
        // The native Daemons-screen switch ("keep the bot running") is a separate,
        // stronger signal than the parked-only autoStartAccOff toggle above. Both
        // feed the ACC-off start gate, so the web UI needs to know when this one
        // is set — otherwise turning autoStartAccOff off looks like it did
        // nothing. Read-only here; only the native screen owns it.
        response.put("daemonAlwaysOn",      UnifiedTelegramConfig.isDaemonEnabled());
        response.put("criticalAlerts",      UnifiedTelegramConfig.isCriticalAlerts());
        response.put("connectivityUpdates", UnifiedTelegramConfig.isConnectivity());
        response.put("motionText",          UnifiedTelegramConfig.isMotionText());
        response.put("chargingAlerts",      UnifiedTelegramConfig.isChargingAlerts());
        response.put("batteryAlerts",       UnifiedTelegramConfig.isBatteryAlerts());
        response.put("guardStopAlerts",     UnifiedTelegramConfig.isGuardStopAlerts());
        response.put("healthAlerts",        UnifiedTelegramConfig.isHealthAlerts());
        response.put("updateAlerts",        UnifiedTelegramConfig.isUpdateAlerts());
        response.put("campingAlerts",       UnifiedTelegramConfig.isCampingAlerts());
        response.put("quietHours",          UnifiedTelegramConfig.getQuietHours());
        response.put("remote",              UnifiedTelegramConfig.getRemote());
        response.put("mediaNoticeAckAt",    UnifiedTelegramConfig.getMediaNoticeAckAt());
        // Der Bot spricht die Sprache der App (LocaleManager), Standard Deutsch.
        response.put("language",            LocaleManager.get());

        // Live pairing PIN (if a PIN flow is in-progress and unexpired).
        String pin = UnifiedTelegramConfig.getPairPin();
        long pinExpiry = UnifiedTelegramConfig.getPairPinExpiry();
        if (!pin.isEmpty() && pinExpiry > System.currentTimeMillis()) {
            response.put("pendingPin", pin);
            response.put("pendingPinExpiresAt", pinExpiry);
        }

        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * POST /api/telegram/token — body { "token": "..." }. Validates via
     * https://api.telegram.org/bot&lt;token&gt;/getMe and persists on success.
     */
    private static void handleToken(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = new JSONObject(body == null ? "{}" : body);
            String token = req.optString("token", "").trim();
            if (token.isEmpty()) {
                response.put("success", false);
                response.put("error", "Token is empty");
                response.put("code", "token_empty");
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            // Quick format sanity — Telegram tokens are "<digits>:<base64ish>".
            if (!token.contains(":") || token.length() < 30) {
                response.put("success", false);
                response.put("error", "Invalid token format");
                response.put("code", "token_format");
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            GetMeResult gm = telegramGetMe(token);
            if (gm.result == null) {
                response.put("success", false);
                // Distinguish "Telegram rejected the token" (the user's token is
                // wrong/revoked) from "couldn't reach Telegram" (network/proxy)
                // so the user knows what to fix instead of a vague combined msg.
                response.put("code", gm.rejectedByTelegram ? "token_rejected" : "unreachable");
                if (gm.rejectedByTelegram) {
                    response.put("error", gm.description != null && !gm.description.isEmpty()
                            ? ("Telegram rejected this token: " + gm.description)
                            : "Telegram rejected this token — check it's correct and not revoked");
                } else {
                    response.put("error", "Couldn't reach Telegram. Check the head unit's "
                            + "internet/proxy connection and try again.");
                }
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            JSONObject botInfo = gm.result;

            UnifiedTelegramConfig.setBotToken(
                    token,
                    botInfo.optLong("id", -1),
                    botInfo.optString("username", ""),
                    botInfo.optString("first_name", "")
            );

            // Mirror the native settings flow: as soon as a valid token is
            // saved, start the bot daemon so the user can immediately send
            // `/pair <PIN>`. Without this, the web tab leaves them with
            // "configured but no process running" until they generate a PIN
            // (which the native flow would also have caught).
            try {
                boolean launched = at.greenchili.surfmate.daemon.telegram
                        .TelegramDaemonLauncher.launchIfNotRunning();
                response.put("daemonLaunched", launched);
            } catch (Exception e) {
                // Don't fail the token-save just because the launch helper
                // tripped — the user can still trigger it manually from
                // the Daemons tab.
                logger.warn("daemon launch after token save failed: " + e.getMessage());
            }

            response.put("success", true);
            response.put("botInfo", botInfo);
            HttpResponse.sendJson(out, response.toString());
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
    }

    /**
     * POST /api/telegram/preferences — body keys are partial: only fields
     * present in the body are written. Mirrors every toggle the native
     * TelegramSettingsFragment exposes:
     *   videoUploads        → videoUploads        (TelegramBotDaemon reads)
     *   autoStartAccOff     → autoStartAccOff     (AccSentryDaemon reads)
     *   criticalAlerts      → criticalAlerts
     *   connectivityUpdates → connectivity
     *   motionText          → motionText
     *   tyreAlerts          → tyreAlerts          (TelegramSink reads)
     */
    /** Einschalten (aus → an) ohne Quittung des Pflichttexts E? Bestand, der schon an ist, bleibt unberührt. */
    static boolean mediaBestaetigungFehlt(boolean willAn, boolean istAn, long quittungMs) {
        return willAn && !istAn && quittungMs <= 0;
    }

    /** 0.1.11: Registry-Abschnitt telegram, in Nutzersprache, mit aktuellem Ein/Aus. */
    private static void handleKategorien(OutputStream out) throws Exception {
        String reg = NotificationApiHandler.registryJson();
        if (reg == null) {
            HttpResponse.sendError(out, 503, "Notifications not initialized");
            return;
        }
        JSONObject r = new JSONObject();
        r.put("success", true);
        r.put("kategorien", at.greenchili.surfmate.telegram.config.TelegramKategorien.sicht(
                at.greenchili.surfmate.telegram.config.TelegramKategorien.aus(reg), UnifiedTelegramConfig.load(),
                (k, f) -> Messages.text(k, f)));
        HttpResponse.sendJson(out, r.toString());
    }

    /** 0.1.11: {kategorien:{id: bool}} → Telegram-Schlüssel in den Body falten (prüft vorher alles). */
    static void kategorienFalten(JSONObject req, String registry) throws Exception {
        JSONObject k = req.optJSONObject("kategorien");
        if (k == null) return;
        if (registry == null) throw new IllegalStateException("registry");
        java.util.Map<String, Boolean> m = at.greenchili.surfmate.telegram.config.TelegramKategorien.schluessel(
                at.greenchili.surfmate.telegram.config.TelegramKategorien.aus(registry), k);
        req.remove("kategorien");
        for (java.util.Map.Entry<String, Boolean> e : m.entrySet()) {
            // Body-Namen der alten Felder: videoUploads/connectivityUpdates/…; Stufen direkt als Schlüssel
            String feld = UnifiedTelegramConfig.K_CONNECTIVITY.equals(e.getKey()) ? "connectivityUpdates" : e.getKey();
            req.put(feld, e.getValue().booleanValue());
        }
    }

    private static void handlePreferences(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = new JSONObject(body == null ? "{}" : body);
            try {
                kategorienFalten(req, NotificationApiHandler.registryJson());
            } catch (IllegalArgumentException | IllegalStateException bad) {
                response.put("success", false);
                response.put("error", bad.getMessage());
                HttpResponse.sendJson(out, 422, response.toString());
                return;
            }
            // 0.1.11: altes „Wächter-Texte aus“ = alle drei Wächter-Kategorien aus; motionText bleibt an
            if (req.has("motionText")) {
                if (!req.optBoolean("motionText", true)) {
                    req.put("tierNotices", false).put("tierAlerts", false).put("tierCritical", false);
                }
                req.remove("motionText");
            }
            // F2 (0.1.9.1, Recht): Bilder/Videos an Telegram nur nach Pflichttext E. Einschalten ohne Quittung
            // (mediaNoticeAckAt) wird abgelehnt – egal von welcher Seite. Nichts aus der Anfrage wird gespeichert.
            if (mediaBestaetigungFehlt(req.has("videoUploads") && req.optBoolean("videoUploads", false),
                    UnifiedTelegramConfig.isVideoUploads(), UnifiedTelegramConfig.getMediaNoticeAckAt())) {
                response.put("success", false);
                response.put("error", "bestaetigung_fehlt");
                response.put("bestaetigung", "telegramMedien");
                HttpResponse.sendJson(out, 409, response.toString());
                return;
            }
            String[][] map = new String[][]{
                { "videoUploads",        UnifiedTelegramConfig.K_VIDEO_UPLOADS },
                { "autoStartAccOff",     UnifiedTelegramConfig.K_AUTO_START },
                { "criticalAlerts",      UnifiedTelegramConfig.K_CRITICAL_ALERTS },
                { "connectivityUpdates", UnifiedTelegramConfig.K_CONNECTIVITY },
                { "motionText",          UnifiedTelegramConfig.K_MOTION_TEXT },
                { "tyreAlerts",          UnifiedTelegramConfig.K_TYRE_ALERTS },
                { "parkingMessages",     UnifiedTelegramConfig.K_PARKING_MESSAGES },
                { "chargingAlerts",      UnifiedTelegramConfig.K_CHARGING_ALERTS },
                { "batteryAlerts",       UnifiedTelegramConfig.K_BATTERY_ALERTS },
                { "guardStopAlerts",     UnifiedTelegramConfig.K_GUARD_STOP_ALERTS },
                { "healthAlerts",        UnifiedTelegramConfig.K_HEALTH_ALERTS },
                { "updateAlerts",        UnifiedTelegramConfig.K_UPDATE_ALERTS },
                { "campingAlerts",       UnifiedTelegramConfig.K_CAMPING_ALERTS },
                // 0.1.11: die drei Wächter-Kategorien (früher „Stufen“)
                { "tierNotices",         UnifiedTelegramConfig.K_TIER_NOTICES },
                { "tierAlerts",          UnifiedTelegramConfig.K_TIER_ALERTS },
                { "tierCritical",        UnifiedTelegramConfig.K_TIER_CRITICAL }
            };
            for (int i = 0; i < map.length; i++) {
                String jsonKey = map[i][0];
                String unifiedKey = map[i][1];
                if (req.has(jsonKey)) {
                    UnifiedTelegramConfig.setBoolean(unifiedKey, req.optBoolean(jsonKey, false));
                }
            }
            JSONObject quiet = req.optJSONObject("quietHours");
            if (quiet != null) {
                JSONObject cur = UnifiedTelegramConfig.getQuietHours();
                UnifiedTelegramConfig.setQuietHours(
                        quiet.optBoolean("enabled", cur.optBoolean("enabled", false)),
                        quiet.optString("from", cur.optString("from")),
                        quiet.optString("to", cur.optString("to")));
            }
            JSONObject remote = req.optJSONObject("remote");
            if (remote != null) UnifiedTelegramConfig.setRemote(remote);
            // This endpoint owns autoStartAccOff (parked-only mode) and must NOT
            // touch daemonEnabled, the cross-UID mirror of the native
            // Daemons-screen switch. "Run the daemon" is a strictly stronger
            // request than "start it when parked", so a native-enabled bot
            // correctly keeps running while parked even with this toggle off.
            // An earlier revision cleared daemonEnabled here so the web toggle
            // would visibly take effect; that silently killed a natively-enabled
            // daemon while the native UI still displayed ON, and nothing on
            // either surface could repair it. The status response now reports
            // daemonAlwaysOn instead so the web UI can EXPLAIN the state rather
            // than destroy it.
            response.put("success", true);
            HttpResponse.sendJson(out, response.toString());
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
    }

    /**
     * POST /api/telegram/pin — generate a fresh 6-digit pairing PIN with a
     * 10-minute TTL. Daemon's /pair handler validates against pairPin +
     * pairPinExpiry on the same unified config. Owner not set yet ⇒ user
     * sends "/pair &lt;PIN&gt;" to the bot; daemon writes owner_* on match.
     */
    private static void handlePin(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        try {
            if (!UnifiedTelegramConfig.hasBotToken()) {
                response.put("success", false);
                response.put("error", "Set the bot token first");
                response.put("code", "no_token");
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            // Generate a 6-digit zero-padded PIN.
            int n = PIN_RNG.nextInt(1_000_000);
            String pin = String.format(java.util.Locale.US, "%06d", n);
            long expiry = System.currentTimeMillis() + PIN_TTL_MS;
            UnifiedTelegramConfig.setPairPin(pin, expiry);

            response.put("success", true);
            response.put("pin", pin);
            response.put("expiresAt", expiry);
            response.put("ttlSeconds", PIN_TTL_MS / 1000);
            HttpResponse.sendJson(out, response.toString());
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
    }

    /**
     * POST /api/telegram/test — schickt eine Testnachricht über den laufenden
     * Bot-Dienst (IPC 19980), damit man beim Einrichten sofort sieht, ob es
     * ankommt. Antwort: {success, code: ok|no_owner|daemon_down|send_failed}.
     */
    private static void handleTest(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        if (!UnifiedTelegramConfig.hasBotToken() || !UnifiedTelegramConfig.hasOwner()) {
            response.put("success", false);
            response.put("code", "no_owner");
            HttpResponse.sendJson(out, response.toString());
            return;
        }
        JSONObject cmd = new JSONObject();
        cmd.put("cmd", "sendMessage");
        cmd.put("category", "CRITICAL");
        cmd.put("alarm", true);
        cmd.put("text", Messages.get("telegram.test.message"));
        String code;
        try (java.net.Socket sock = new java.net.Socket()) {
            sock.connect(new java.net.InetSocketAddress("127.0.0.1", 19980), 2000);
            sock.setSoTimeout(15000);
            java.io.PrintWriter w = new java.io.PrintWriter(new java.io.OutputStreamWriter(
                    sock.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8), true);
            w.println(cmd.toString());
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    sock.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            String line = r.readLine();
            JSONObject resp = line == null ? new JSONObject() : new JSONObject(line);
            code = "ok".equals(resp.optString("status")) ? "ok" : "send_failed";
        } catch (java.net.ConnectException | java.net.SocketTimeoutException e) {
            code = "daemon_down";
        } catch (Exception e) {
            code = "send_failed";
        }
        if ("daemon_down".equals(code)) {
            try {
                at.greenchili.surfmate.daemon.telegram.TelegramDaemonLauncher.launchIfNotRunning();
            } catch (Exception ignored) {}
        }
        response.put("success", "ok".equals(code));
        response.put("code", code);
        HttpResponse.sendJson(out, response.toString());
    }

    /** POST /api/telegram/owner/clear — unpair without losing the bot token. */
    private static void handleOwnerClear(OutputStream out) throws Exception {
        UnifiedTelegramConfig.clearOwner();

        JSONObject response = new JSONObject();
        response.put("success", true);
        HttpResponse.sendJson(out, response.toString());
    }

    /** POST /api/telegram/clear — full wipe (token + bot info + owner). */
    private static void handleClear(OutputStream out) throws Exception {
        UnifiedTelegramConfig.clearAll();

        JSONObject response = new JSONObject();
        response.put("success", true);
        HttpResponse.sendJson(out, response.toString());
    }

    // ────────────────────────────── Telegram API ──────────────────────────────

    /**
     * Hit https://api.telegram.org/bot&lt;token&gt;/getMe. Returns the parsed
     * {@code result} object on success, or null on any failure. 5-second
     * connect / read timeout so a flaky network doesn't hang the HTTP worker.
     */
    /**
     * Outcome of a getMe validation. {@code result} non-null = success.
     * {@code rejectedByTelegram} true = Telegram answered but said the token is
     * bad (HTTP 401 / ok:false) — the user's token is wrong, NOT the network.
     * false = we never got a verdict from Telegram (transport/proxy failure).
     */
    private static final class GetMeResult {
        JSONObject result;          // non-null on success
        boolean rejectedByTelegram; // true => token is bad (not a network issue)
        String description;         // Telegram's "description" on rejection
    }

    private static GetMeResult telegramGetMe(String token) {
        // Proxy-aware, mirroring AppUpdater + the Telegram DAEMON's own client:
        // the head unit's egress to api.telegram.org usually REQUIRES the
        // sing-box/Tailscale proxy (CN firmware / captive SIM). The previous
        // bare url.openConnection() went DIRECT, so token validation failed
        // even on a perfectly valid token whenever direct egress was blocked —
        // while the daemon (which DOES proxy) worked fine once saved. Try the
        // proxy first, then fall back to a direct attempt.
        java.net.Proxy proxy = at.greenchili.surfmate.mqtt.ProxyHelper.getHttpProxy();
        boolean haveProxy = proxy != null && proxy.type() != java.net.Proxy.Type.DIRECT;

        GetMeResult r = haveProxy ? getMeOnce(token, proxy) : null;
        if (r != null && (r.result != null || r.rejectedByTelegram)) return r;
        // Proxy attempt didn't reach a verdict (or no proxy): invalidate the
        // shared probe cache (proxy may be down) and retry DIRECT.
        if (haveProxy) at.greenchili.surfmate.mqtt.ProxyHelper.invalidateCache();
        GetMeResult direct = getMeOnce(token, java.net.Proxy.NO_PROXY);
        return direct != null ? direct : new GetMeResult();
    }

    /**
     * Single getMe attempt over an explicit proxy (or NO_PROXY). 10s
     * connect/read (raised from 5s) since a proxied hop to Telegram can be
     * slower than a direct one. Sets rejectedByTelegram when Telegram answers
     * with a non-2xx or ok:false (definitive bad-token verdict).
     */
    private static GetMeResult getMeOnce(String token, java.net.Proxy proxy) {
        GetMeResult out = new GetMeResult();
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://api.telegram.org/bot" + token + "/getMe");
            conn = (HttpURLConnection) (proxy != null
                    ? url.openConnection(proxy)
                    : url.openConnection());
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            int code = conn.getResponseCode();
            String pl = (proxy != null ? proxy.type().toString() : "DIRECT");
            // We GOT an HTTP response from Telegram — read the body either way
            // so a 401/400 carries its "description".
            java.io.InputStream in = (code >= 200 && code < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            String bodyStr = "";
            if (in != null) {
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[2048];
                int n;
                while ((n = in.read(buf)) >= 0) baos.write(buf, 0, n);
                in.close();
                bodyStr = baos.toString("UTF-8");
            }
            JSONObject json = bodyStr.isEmpty() ? new JSONObject() : new JSONObject(bodyStr);
            if (code == 200 && json.optBoolean("ok", false)) {
                out.result = json.getJSONObject("result");
                return out;
            }
            // Telegram answered but rejected — definitive bad-token verdict.
            out.rejectedByTelegram = true;
            out.description = json.optString("description", "");
            logger.warn("getMe rejected HTTP " + code + " (proxy=" + pl + "): " + out.description);
            return out;
        } catch (Exception e) {
            // Never reached a verdict — transport/proxy failure.
            logger.warn("getMe exception (proxy="
                    + (proxy != null ? proxy.type() : "DIRECT") + "): " + e.getMessage());
            return out; // rejectedByTelegram stays false
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Exception ignored) {}
        }
    }
}
