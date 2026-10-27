package at.greenchili.surfmate.server;

import at.greenchili.surfmate.daemon.CameraDaemon;
import at.greenchili.surfmate.logging.DaemonLogger;
import at.greenchili.surfmate.remote.RemoteDevViewBridgeAuth;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * IPC server for surveillance configuration.
 * Listens on port 19977 for surveillance config commands from the app UI.
 * 
 * SOTA: Uses thread pool to prevent thread exhaustion under load.
 */
public class SurveillanceIpcServer implements Runnable {
    private static final String TAG = "SurveillanceIPC";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private static final String DILINK5_INGRESS_SESSION = UUID.randomUUID().toString();
    private static final Map<String, Long> DILINK5_INGRESS_NONCES =
            new LinkedHashMap<>();
    private static final int DILINK5_INGRESS_NONCE_LIMIT = 512;
    
    private final int port;
    // volatile: written by the run() thread on every (re)bind, read+closed by
    // stop() on the shutdown thread. Without volatile, stop() could see a stale
    // reference and fail to close the LIVE listen socket — leaving accept()
    // blocked and the server thread leaked on shutdown. The rebind loop
    // reassigns this more often than the old single-shot bind, so safe
    // publication matters.
    private volatile ServerSocket serverSocket;
    private volatile boolean running = true;
    
    // Cached thread pool (not fixed): persistent clients (IMU + GPS sidecars,
    // peer daemons) now hold a worker blocked in readLine() for the life of
    // their connection, so a fixed-8 pool could be saturated by a handful of
    // long-lived holders + a burst of one-shot callers, starving the accept
    // dispatch. A cached pool grows on demand and reaps idle threads after 60s,
    // so steady state is ~one thread per live persistent connection plus
    // transient threads for one-shot commands — no artificial ceiling, no idle
    // cost when nothing is connected. Localhost JSON traffic, so thread count
    // stays small in practice (a few persistent + brief one-shots).
    private final ExecutorService threadPool = Executors.newCachedThreadPool();

    // ABRP integration references (set by CameraDaemon)
    private static volatile at.greenchili.surfmate.abrp.AbrpConfig abrpConfig;
    private static volatile at.greenchili.surfmate.abrp.AbrpTelemetryService abrpService;

    public static void setAbrpReferences(at.greenchili.surfmate.abrp.AbrpConfig config, at.greenchili.surfmate.abrp.AbrpTelemetryService service) {
        abrpConfig = config;
        abrpService = service;
    }

    /**
     * Reset path used by the bulk Reset Data feature: deletes the ABRP token,
     * persists the change, and stops the running telemetry service so cached
     * RAM credentials don't keep uploading after the user wiped them.
     * Mirrors the proven sequence in {@link #handleDeleteAbrpToken}.
     *
     * @return true if the reset ran (token cleared + persisted)
     */
    public static boolean resetAbrpForBulkWipe() {
        if (abrpConfig == null) return false;
        abrpConfig.deleteToken();
        abrpConfig.save();
        if (abrpService != null && abrpService.isRunning()) {
            abrpService.stop();
        }
        return true;
    }

    // MQTT integration reference (set by CameraDaemon)
    private static volatile at.greenchili.surfmate.mqtt.MqttConnectionManager mqttManager;

    public static void setMqttManager(at.greenchili.surfmate.mqtt.MqttConnectionManager manager) {
        mqttManager = manager;
    }

    /** MQTT-Verwaltung dieses Daemons oder null (Telemetrie-Schnappschuss für /api/ha/v1/zustand). */
    public static at.greenchili.surfmate.mqtt.MqttConnectionManager mqttManager() {
        return mqttManager;
    }

    /** Reconcile long-lived processes after any vehicle-mode transition. */
    public static void requestAppVehicleModeSync(
            String previousMode, String activeMode) {
        if (at.greenchili.surfmate.camera.dilink5.DiLink5Platform
                .isSameMode(previousMode, activeMode)) {
            return;
        }
        if (isDiLink5Mode(previousMode) && !isDiLink5Mode(activeMode)) {
            at.greenchili.surfmate.monitor.ChargingDetector.getInstance()
                    .clearExternalVerdict();
        }
        if (!isDiLink5Mode(previousMode) && isDiLink5Mode(activeMode)) {
            Thread grants = new Thread(() -> {
                if (!at.greenchili.surfmate.util.BydDataCacheWhitelist
                        .applyViaDaemonWhenReady()) {
                    logger.warn("DiLink 5 background access was not applied: "
                            + at.greenchili.surfmate.util.BydDataCacheWhitelist
                                    .getLastApplyFailure());
                }
            }, "DiLink5BackgroundAccess");
            grants.setDaemon(true);
            grants.start();
        }
        restartAccSentryForVehicleModeChange();
        try {
            new ProcessBuilder(
                    "am",
                    "start-foreground-service",
                    "-n",
                    "at.greenchili.surfmate/.services.DaemonKeepaliveService")
                    .start();
        } catch (Exception e) {
            logger.warn("Could not request app vehicle-mode sync: " + e.getMessage());
        }
    }

    private static boolean isDiLink5Mode(String mode) {
        return "dilink5".equalsIgnoreCase(mode);
    }

    private static void restartAccSentryForVehicleModeChange() {
        Thread restart = new Thread(() -> {
            String command =
                    "if [ ! -x /data/user_de/0/com.android.shell/surfmate/start_acc_sentry.sh ] "
                    + "|| [ -f /data/user_de/0/com.android.shell/surfmate/acc_sentry_daemon.disabled ] "
                    + "|| [ -f /data/user_de/0/com.android.shell/surfmate/surfmate_parked_shutdown ]; then exit 0; fi; "
                    + "PIDS=\"$(ps -A -o PID,NAME 2>/dev/null "
                    + "| awk '$2==\"acc_sentry_daemon\" {print $1}')\"; "
                    + "[ -z \"$PIDS\" ] && exit 0; "
                    + "kill -9 $PIDS 2>/dev/null; sleep 3; "
                    + "RUNNING=\"$(ps -A -o PID,NAME 2>/dev/null "
                    + "| awk '$2==\"acc_sentry_daemon\" {print $1}')\"; "
                    + "[ -n \"$RUNNING\" ] && exit 0; "
                    + "nohup sh /data/user_de/0/com.android.shell/surfmate/start_acc_sentry.sh >/dev/null 2>&1 &";
            try {
                Process process = new ProcessBuilder("sh", "-c", command).start();
                if (!process.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    logger.warn("ACC worker mode-sync restart timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception failed) {
                logger.warn("Could not restart ACC worker for vehicle-mode change: "
                        + failed.getMessage());
            }
        }, "VehicleModeAccRestart");
        restart.setDaemon(true);
        restart.start();
    }
    
    public SurveillanceIpcServer(int port) {
        this.port = port;
    }
    
    @Override
    public void run() {
        // DURABILITY: bind in a RETRY loop, never let a bind failure kill the
        // server thread. This is the config-write + sidecar lifeline (port 19977):
        // app-process config writes route through it for atomic durability, and
        // the IMU/GPS sidecars + peer daemons hold persistent connections to it.
        // If the daemon crashes and respawns into a transient EADDRINUSE (the old
        // listen socket / a client connection still in TIME_WAIT from the killed
        // predecessor), a single-shot bind would throw, the thread would return,
        // and 19977 would stay DEAD for the entire life of this daemon process —
        // every app config write would silently fall back to the truncation-prone
        // local path and the sidecars could never reconnect. So we mirror the
        // proven TcpCommandServer/HttpServer pattern: outer while(running) loop,
        // setReuseAddress(true) BEFORE bind (the immediate-bind constructor can't
        // do that), BindException → sleep+retry, and a listen-socket error breaks
        // the inner accept loop to rebind rather than dying. Idempotent: the next
        // iteration closes any half-open serverSocket before re-binding.
        while (running) {
            try {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    try { serverSocket.close(); } catch (Exception ignored) {}
                }
                ServerSocket ss = new ServerSocket();   // unbound — lets us set reuse first
                ss.setReuseAddress(true);
                ss.bind(new java.net.InetSocketAddress(
                        InetAddress.getByName("127.0.0.1"), port), 50);
                serverSocket = ss;
                logger.info("Surveillance IPC server listening on 127.0.0.1:" + port);

                while (running && !serverSocket.isClosed()) {
                    try {
                        Socket client = serverSocket.accept();
                        // Offload to the cached thread pool (persistent clients
                        // hold a worker for their connection lifetime). If stop()
                        // raced us and called threadPool.shutdownNow(), execute()
                        // throws RejectedExecutionException — close the just-
                        // accepted client so its FD isn't leaked (matters only if
                        // stop() is ever used for an in-process restart; on the
                        // current SIGKILL/process-exit shutdown the kernel reclaims
                        // it anyway, but this keeps the pattern correct).
                        try {
                            threadPool.execute(() -> handleClient(client));
                        } catch (java.util.concurrent.RejectedExecutionException rej) {
                            try { client.close(); } catch (Exception ignored) {}
                        }
                    } catch (java.net.SocketException se) {
                        // Listen socket closed/errored (shutdown, or transient) —
                        // leave the accept loop and let the outer loop rebind
                        // (unless we're shutting down).
                        if (running) {
                            logger.warn("IPC accept socket error — rebinding: " + se.getMessage());
                        }
                        break;
                    } catch (Exception e) {
                        // Per-accept transient (e.g. EMFILE) — log and keep accepting.
                        if (running) {
                            logger.error("Error accepting client", e);
                        }
                    }
                }
                // Inner accept loop exited via a listen-socket SocketException
                // (not shutdown). Back off before the outer loop rebinds so a
                // recurring transient listener error can't hot-spin a core
                // (close→bind→accept→throw→repeat). Mirrors TcpCommandServer's
                // post-inner-loop sleep. Skipped on shutdown (running==false).
                if (running) {
                    sleepQuietly(1000);
                }
            } catch (java.net.BindException be) {
                // Port still held by a just-killed predecessor's TIME_WAIT, or a
                // racing respawn. Retry — do NOT die. The watchdog's respawn delay
                // plus this backoff comfortably exceeds localhost TIME_WAIT.
                if (running) {
                    logger.warn("IPC port " + port + " in use — retrying in 3s: " + be.getMessage());
                    sleepQuietly(3000);
                }
            } catch (Exception e) {
                if (running) {
                    logger.error("IPC server bind/loop error — retrying in 3s", e);
                    sleepQuietly(3000);
                }
            }
        }
        // Clean shutdown exit.
        if (serverSocket != null && !serverSocket.isClosed()) {
            try { serverSocket.close(); } catch (Exception ignored) {}
        }
        logger.info("Surveillance IPC server stopped");
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
    
    private void handleClient(Socket client) {
        try {
            // Read timeout: bounds how long a worker waits for the NEXT line on
            // an idle-but-open connection. A client that opens the socket and
            // never sends would otherwise pin a worker forever. 30s (raised
            // from 5s) accommodates persistent clients — the hot sidecars (IMU
            // ~10/s, GPS ~1/s) now hold ONE connection and stream many commands
            // over it instead of reconnecting per message, so the worker legitimately
            // blocks between bursts; 30s is well above their inter-message gap
            // yet still reaps a truly dead half-open socket. Per-message clients
            // are unaffected — they send one line, read the reply, close, and
            // our readLine() then returns null (EOF) so we exit the loop at once.
            client.setSoTimeout(30_000);
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            PrintWriter out = new PrintWriter(client.getOutputStream(), true);

            // Loop over newline-delimited commands so a single persistent socket
            // can carry a stream of requests. Backward-compatible with the old
            // one-shot clients: they close after one exchange → readLine() == null
            // → loop ends. This converts ~10 TCP connect/teardown per second
            // (IMU) + ~1/s (GPS) into ZERO once the connection is established.
            String line;
            while ((line = in.readLine()) != null) {
                // Skip blank/whitespace lines instead of feeding them to the
                // JSON parser. A connect-then-send-empty-line probe (and the
                // brief window between a client's connect and its first write)
                // yields "" here; new JSONObject("") throws
                // "End of input at character 0", which under the per-connection
                // loop was logged ~3x/sec as "Error handling client". Treat an
                // empty line as a keep-alive no-op, not a malformed command.
                if (line.trim().isEmpty()) {
                    continue;
                }
                // A SINGLE malformed command must not tear down the whole
                // (now persistent) connection or spam the log — catch per-line,
                // reply with an error, and keep serving the next command.
                try {
                    JSONObject request = new JSONObject(line);
                    boolean diLink5Authenticated = false;
                    if (request.has("signature") && request.has("request")) {
                        RemoteDevViewBridgeAuth.AuthenticatedRequest authenticated =
                                RemoteDevViewBridgeAuth.verify(line);
                        if (!rememberNonce(
                                authenticated.nonce,
                                authenticated.timestampMs)) {
                            throw new SecurityException("vehicle ingress replay rejected");
                        }
                        request = authenticated.request;
                        String signedCommand = request.optString("command", "");
                        if (!"DILINK5_TELEMETRY".equals(signedCommand)
                                && !"DILINK5_VEHICLE_EVENT".equals(signedCommand)
                                && !"DILINK5_HELLO".equals(signedCommand)) {
                            throw new SecurityException("signed command is not a vehicle ingress");
                        }
                        diLink5Authenticated = true;
                    }
                    if ("DETERRENT_INPUT_CAPTURE".equals(
                            request.optString("command", ""))) {
                        handleDeterrentInputCapture(client, in, out, request);
                        return;
                    }
                    JSONObject response = handleCommand(request, diLink5Authenticated);
                    // handleCommand returns null for fire-and-forget streaming
                    // commands (IMU_BATCH ~10/s, UPDATE_GPS ~1/s) whose sidecar
                    // clients never read the reply — skip the per-message
                    // JSONObject build + toString + write syscall on the hot path,
                    // and stop accumulating unread ack bytes in the socket buffer.
                    if (response != null) {
                        out.println(response.toString());
                    }
                } catch (Exception invalid) {
                    logger.debug("IPC: ignoring invalid command line: "
                            + invalid.getMessage());
                }
            }

            client.close();
        } catch (java.net.SocketTimeoutException ste) {
            // Idle persistent client (no command within the window) or a stuck
            // half-open socket — close and free the worker. The client's
            // reconnect-on-failure path re-establishes when it next has data.
            try { client.close(); } catch (Exception ignored) {}
        } catch (Exception e) {
            logger.error("Error handling client", e);
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Holds one authenticated Activity connection for the visual session.
     * EOF is the process/focus-death signal; DISMISS is the in-band tap signal.
     * No config file heartbeat is needed.
     */
    private void handleDeterrentInputCapture(
            Socket client, BufferedReader in, PrintWriter out, JSONObject request) {
        long captureId = 0;
        try {
            captureId = at.greenchili.surfmate.surveillance.ScreenDeterrent
                    .openInputCapture(request.optString("token", ""));
            JSONObject response = new JSONObject();
            response.put("success", captureId != 0);
            out.println(response.toString());
            if (captureId == 0 || out.checkError()) return;

            // Poll daemon-side ownership so cleanup closes the peer even when
            // the persisted UCM gate cannot be cleared. The Activity then
            // releases input after its short visual-teardown grace instead of
            // blocking the screen until its 60s absolute ceiling.
            client.setSoTimeout(1_000);
            while (at.greenchili.surfmate.surveillance.ScreenDeterrent
                    .isInputCaptureActive(captureId)) {
                try {
                    String message = in.readLine();
                    if (message == null) break;
                    if ("DISMISS".equals(message)) {
                        at.greenchili.surfmate.surveillance.ScreenDeterrent
                                .dismissInputCapture(captureId);
                    }
                } catch (java.net.SocketTimeoutException idle) {
                    // Expected while the focused Activity holds an idle socket.
                }
            }
        } catch (Exception e) {
            logger.debug("Deterrent input-capture socket closed: " + e.getMessage());
        } finally {
            if (captureId != 0) {
                at.greenchili.surfmate.surveillance.ScreenDeterrent
                        .closeInputCapture(captureId);
            }
            try { client.close(); } catch (Exception ignored) {}
        }
    }
    
    private JSONObject handleCommand(
            JSONObject request, boolean diLink5Authenticated) {
        JSONObject response = new JSONObject();
        
        try {
            String command = request.optString("command", "");
            
            switch (command) {
                case "DILINK5_HELLO":
                    if (!diLink5Authenticated) return null;
                    response.put("success", true);
                    response.put("session", DILINK5_INGRESS_SESSION);
                    return response;

                case "DILINK5_TELEMETRY": {
                    if (!diLink5Authenticated
                            || !hasCurrentDiLink5IngressSession(request)) {
                        return null;
                    }
                    JSONObject data = request.optJSONObject("data");
                    if (request.optInt("protocol", -1) == 1 && data != null) {
                        JSONArray encodedDoorStates = request.optJSONArray("doorStates");
                        int[] doorStates = null;
                        if (encodedDoorStates != null) {
                            doorStates = new int[encodedDoorStates.length()];
                            for (int i = 0; i < doorStates.length; i++) {
                                doorStates[i] = encodedDoorStates.optInt(
                                        i,
                                        at.greenchili.surfmate.byd.BydVehicleData.UNAVAILABLE);
                            }
                        }
                        at.greenchili.surfmate.byd.BydVehicleData snapshot =
                                at.greenchili.surfmate.byd.BydVehicleData.fromJson(data);
                        at.greenchili.surfmate.byd.BydDataCollector.getInstance()
                                .acceptDiLink5Telemetry(
                                        snapshot,
                                        request.optLong("sourceEpoch", 0L),
                                        request.optLong("sequence", 0L),
                                        request.optLong("sentElapsedMs", 0L),
                                        request.optLong(
                                                "dynamicsObservedElapsedMs",
                                                0L),
                                        request.optBoolean("charging", false),
                                        request.optInt("energyFeedback", -1),
                                        request.optInt(
                                                "doorLockState",
                                                at.greenchili.surfmate.byd.BydDataCollector
                                                        .DOOR_STATE_INVALID),
                                        doorStates);
                    }
                    return null;
                }

                case "DILINK5_VEHICLE_EVENT": {
                    if (!diLink5Authenticated
                            || !hasCurrentDiLink5IngressSession(request)) {
                        return null;
                    }
                    if (request.optInt("protocol", -1) == 1) {
                        at.greenchili.surfmate.byd.BydDataCollector.getInstance()
                                .acceptDiLink5VehicleEvent(
                                        request.optString("event", ""),
                                        request.optInt("area", 0),
                                        request.optInt("value", 0),
                                        request.optLong("sourceEpoch", 0L),
                                        request.optLong("sequence", 0L),
                                        request.optLong("sentElapsedMs", 0L));
                    }
                    return null;
                }

                // ==================== TELEGRAM DAEMON COMMANDS ====================
                // These commands are sent by TelegramBotDaemon for remote control
                
                case "START": {
                    // Start surveillance (from Telegram /start command).
                    // Persist result is load-bearing — the ACC-OFF arm dispatch
                    // re-reads it, so a failed write means nothing arms. Telling
                    // the user "enabled" there is the bug this reports instead.
                    boolean accOn = at.greenchili.surfmate.monitor.AccMonitor.isAccOn();
                    if (!at.greenchili.surfmate.config.UnifiedConfigManager.setSurveillanceEnabled(true)) {
                        logger.warn("Failed to persist surveillanceEnabled=true via Telegram IPC");
                        response.put("success", false);
                        response.put("error", "Failed to save the surveillance setting");
                        break;
                    }
                    if (!accOn) {
                        CameraDaemon.enableSurveillance();   // fires OEM recalc
                        logger.info("Surveillance started via Telegram IPC");
                    } else {
                        logger.info("Surveillance preference saved via Telegram — will activate on ACC OFF");
                        // OEM resolver reads isSurveillanceEnabled into survSuppressed
                        try { at.greenchili.surfmate.server.OemDashcamApiHandler.scheduleLifecycleRecalc(); }
                        catch (Throwable ignored) {}
                    }
                    response.put("success", true);
                    response.put("enabled", true);
                    response.put("deferred", accOn);
                    response.put("message", accOn
                            ? "Surveillance will start when the vehicle is next turned off"
                            : "Surveillance enabled");
                    break;
                }

                case "STOP": {
                    // Stop surveillance (from Telegram /stop command). ACC-gated:
                    // nothing is armed while ACC is ON, and the pipeline call
                    // would re-apply the dashcam layout under a live recording.
                    boolean accOn = at.greenchili.surfmate.monitor.AccMonitor.isAccOn();
                    if (!at.greenchili.surfmate.config.UnifiedConfigManager.setSurveillanceEnabled(false)) {
                        logger.warn("Failed to persist surveillanceEnabled=false via Telegram IPC");
                        response.put("success", false);
                        response.put("error", "Failed to save the surveillance setting");
                        break;
                    }
                    if (!accOn) {
                        CameraDaemon.disableSurveillance();   // fires OEM recalc
                    } else {
                        try { at.greenchili.surfmate.server.OemDashcamApiHandler.scheduleLifecycleRecalc(); }
                        catch (Throwable ignored) {}
                    }
                    logger.info("Surveillance stopped via Telegram IPC");
                    response.put("success", true);
                    response.put("enabled", false);
                    response.put("deferred", accOn);
                    response.put("message", "Surveillance stopped");
                    break;
                }
                    
                case "CAMPING": {
                    // 0.1.16: Camping aus Telegram (/camping). Panoptes-Funktion, Stufe 2a ohne Heizwirkung.
                    String aktion = request.optString("aktion", "status");
                    JSONObject r;
                    if ("an".equals(aktion)) r = at.greenchili.surfmate.camping.Camping.ein("telegram");
                    else if ("aus".equals(aktion)) r = at.greenchili.surfmate.camping.Camping.aus("telegram");
                    else if ("rueckmeldung".equals(aktion)) {
                        r = at.greenchili.surfmate.camping.Camping.rueckmeldung(request.optString("art", ""));
                    } else r = null;
                    if (r != null && !r.optBoolean("ok", false)) {
                        response.put("success", false);
                        response.put("fehler", r.optString("fehler", ""));
                        break;
                    }
                    response.put("success", true);
                    if (r != null) response.put("uebernommen", r.optBoolean("uebernommen", false));
                    response.put("data", at.greenchili.surfmate.camping.Camping.status());
                    break;
                }

                case "STATUS": {
                    // Get surveillance status (from Telegram /status command)
                    // Read from persisted config (not in-memory flag which can get stale)
                    boolean enabled = at.greenchili.surfmate.config.UnifiedConfigManager.isSurveillanceEnabled();
                    boolean active = CameraDaemon.isSentryArmed();   // wirklich scharf, nicht nur Pipeline läuft
                    response.put("success", true);
                    response.put("enabled", enabled);
                    response.put("active", active);
                    response.put("recording", active);
                    logger.info("Status requested via Telegram IPC: enabled=" + enabled + ", active=" + active);
                    break;
                }
                
                // ==================== APP UI COMMANDS ====================
                // These commands are sent by the app UI for configuration
                
                case "ENABLE_SURVEILLANCE":
                    // Persist preference only — surveillance will auto-start on next ACC OFF
                    if (!at.greenchili.surfmate.config.UnifiedConfigManager.setSurveillanceEnabled(true)) {
                        logger.warn("Failed to persist surveillanceEnabled=true via app-UI IPC");
                        response.put("success", false);
                        response.put("error", "Failed to save the surveillance setting");
                        break;
                    }
                    // OEM resolver: survSuppressed depends on master toggle.
                    try { at.greenchili.surfmate.server.OemDashcamApiHandler.scheduleLifecycleRecalc(); }
                    catch (Throwable ignored) {}
                    logger.info("Surveillance preference set to ENABLED (will activate on ACC OFF)");
                    response.put("success", true);
                    response.put("enabled", true);
                    // This door NEVER arms directly (persist-only by design), so the
                    // effect is always deferred to the next ACC OFF.
                    response.put("deferred", true);
                    break;

                case "DISABLE_SURVEILLANCE": {
                    // Persist preference and stop if currently running. ACC-gated
                    // teardown — see the STOP case above for why.
                    boolean accOn = at.greenchili.surfmate.monitor.AccMonitor.isAccOn();
                    if (!at.greenchili.surfmate.config.UnifiedConfigManager.setSurveillanceEnabled(false)) {
                        logger.warn("Failed to persist surveillanceEnabled=false via app-UI IPC");
                        response.put("success", false);
                        response.put("error", "Failed to save the surveillance setting");
                        break;
                    }
                    if (!accOn) {
                        CameraDaemon.disableSurveillance();   // fires OEM recalc internally
                    } else {
                        try { at.greenchili.surfmate.server.OemDashcamApiHandler.scheduleLifecycleRecalc(); }
                        catch (Throwable ignored) {}
                    }
                    logger.info("Surveillance preference set to DISABLED and stopped");
                    response.put("success", true);
                    response.put("enabled", false);
                    response.put("deferred", accOn);
                    break;
                }
                
                case "GET_CONFIG":
                    response.put("success", true);
                    response.put("config", getDefaultConfig());
                    break;

                // Device-local About screen: return the VIN already held by the daemon's
                // bodywork snapshot over the existing localhost IPC boundary. This deliberately
                // has no HTTP route, so the identifier is not exposed through a tunnel/web UI.
                // The response is never logged; the app masks it until an explicit reveal.
                case "GET_VEHICLE_IDENTITY": {
                    at.greenchili.surfmate.byd.BydVehicleData data =
                            at.greenchili.surfmate.byd.BydDataCollector.getInstance().getData();
                    response.put("success", data != null);
                    if (data != null && data.vin != null && !data.vin.trim().isEmpty()) {
                        response.put("vin", data.vin.trim());
                    }
                    break;
                }
                    
                case "SET_CONFIG": {
                    JSONObject config = request.optJSONObject("config");
                    String applyError = (config != null) ? applyConfig(config) : null;
                    if (applyError != null) {
                        // A load-bearing write failed (see applyConfig). AccSentryDaemon
                        // keys its retry loop on success=false, so reporting honestly
                        // here is what lets the arm actually be retried.
                        response.put("success", false);
                        response.put("error", applyError);
                        break;
                    }
                    response.put("success", true);
                    response.put("message", "Config updated");
                    break;
                }

                // Generic config write forwarded by the APP process (UID 10xxx)
                // via UnifiedConfigManager.routeWriteIfApp. The app cannot do an
                // atomic tmp+rename in sticky /data/user_de/0/com.android.shell/surfmate/, so it hands the
                // write to us (shell UID 2000) and we apply it in-process, where
                // saveConfigInternal takes the atomic path. This is the fix for
                // the truncation-on-kill total-settings-wipe.
                case "UPDATE_SECTION": {
                    String s = request.optString("section", "");
                    JSONObject data = request.optJSONObject("data");
                    boolean ok = false;
                    if (!s.isEmpty() && data != null) {
                        synchronized (CONFIG_LOCK) {
                            // forceReload BEFORE merge so an app-forwarded delta
                            // merges against fresh on-disk state, preserving the
                            // forceReload-before-merge invariant the cross-UID
                            // writers (RoadSenseOverlayService, UcmVisualSink)
                            // rely on — ext4 mtime has 1s granularity and our
                            // cache could otherwise hide a peer daemon's write.
                            at.greenchili.surfmate.config.UnifiedConfigManager.forceReload();
                            ok = at.greenchili.surfmate.config.UnifiedConfigManager
                                    .updateSection(s, data);
                            if (ok && "nativeShell".equals(s) && data.has("locale")) {
                                LocaleManager.invalidateCaches();
                            }
                        }
                    }
                    response.put("success", ok);
                    break;
                }

                case "UPDATE_VALUES": {
                    String s = request.optString("section", "");
                    JSONObject values = request.optJSONObject("values");
                    boolean ok = false;
                    if (!s.isEmpty() && values != null) {
                        java.util.Map<String, Object> map = new java.util.HashMap<>();
                        java.util.Iterator<String> it = values.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            map.put(k, values.get(k));
                        }
                        synchronized (CONFIG_LOCK) {
                            at.greenchili.surfmate.config.UnifiedConfigManager.forceReload();
                            ok = at.greenchili.surfmate.config.UnifiedConfigManager
                                    .updateValues(s, map);
                            if (ok && "nativeShell".equals(s)
                                    && map.containsKey("locale")) {
                                LocaleManager.invalidateCaches();
                            }
                        }
                    }
                    response.put("success", ok);
                    break;
                }

                // ==================== CONFIG BACKUP / RESTORE ====================
                // Single core lives in ConfigBackupService; web + app + Telegram
                // all reach it through these two commands (no duplicated logic).

                // Build a backup bundle (settings + device-id snapshot). Read-only.
                // NOT public-mode gated (unlike update install): the owner opted to
                // allow backup/restore over the tunnel, relying on JWT auth alone —
                // /api/backup/* is a non-public path so AuthMiddleware enforces a
                // token in every mode. The bundle carries credentials, so the UI
                // warns to keep the file private.
                case "EXPORT_CONFIG": {
                    // includeTrips opts the (location-bearing) trip history into
                    // the bundle; default settings-only.
                    boolean includeTrips = request.optBoolean("includeTrips", false);
                    JSONObject bundle = at.greenchili.surfmate.config.ConfigBackupService.buildBundle(
                            at.greenchili.surfmate.updater.AppUpdater.getInstalledVersion(),
                            deviceModelString(),
                            System.currentTimeMillis(),
                            includeTrips);
                    response.put("success", true);
                    response.put("bundle", bundle);
                    break;
                }

                // Transactional whole-config restore. The atomic write + single
                // coordinated listener reload live in ConfigBackupService /
                // UnifiedConfigManager.saveConfig. CONFIG_LOCK serialises against
                // peer UPDATE_SECTION/UPDATE_VALUES writers in this JVM; the
                // file lock inside saveConfig serialises across daemon JVMs.
                case "REPLACE_CONFIG": {
                    // NOT public-mode gated — owner opted to allow remote restore
                    // over the tunnel (JWT-gated; confirm=true still required by the
                    // HTTP handler).
                    JSONObject bundle = request.optJSONObject("bundle");
                    if (bundle == null) {
                        response.put("success", false);
                        response.put("error", "missing bundle");
                        break;
                    }
                    at.greenchili.surfmate.config.ConfigBackupService.ApplyResult res;
                    synchronized (CONFIG_LOCK) {
                        at.greenchili.surfmate.config.UnifiedConfigManager.forceReload();
                        res = at.greenchili.surfmate.config.ConfigBackupService.applyBundle(
                                bundle,
                                at.greenchili.surfmate.updater.AppUpdater.getInstalledVersion(),
                                deviceModelString());
                    }
                    response.put("success", res.getSuccess());
                    response.put("message", res.getMessage());
                    response.put("warnings", new org.json.JSONArray(res.getWarnings()));
                    response.put("restartRequired", res.getRestartRequired());
                    break;
                }

                case "GET_STATUS":
                    response.put("success", true);
                    response.put("status", getSurveillanceStatus());
                    break;
                    
                // ==================== VEHICLE DATA COMMANDS ====================
                // These commands provide access to BYD vehicle telemetry
                
                case "GET_VEHICLE_DATA":
                    response.put("success", true);
                    response.put("data", getVehicleData());
                    break;
                    
                case "GET_BATTERY_VOLTAGE":
                    response.put("success", true);
                    response.put("data", getBatteryVoltageData());
                    break;
                    
                case "GET_BATTERY_POWER":
                    response.put("success", true);
                    response.put("data", getBatteryPowerData());
                    break;
                    
                case "GET_BATTERY_SOC":
                    response.put("success", true);
                    response.put("data", getBatterySocData());
                    break;
                    
                case "GET_CHARGING_STATE":
                    response.put("success", true);
                    response.put("data", getChargingStateData());
                    break;
                    
                case "GET_CHARGING_POWER":
                    response.put("success", true);
                    response.put("data", getChargingPowerData());
                    break;
                    
                case "GET_DRIVING_RANGE":
                    response.put("success", true);
                    response.put("data", getDrivingRangeData());
                    break;
                    
                case "GET_ROI":
                    response.put("success", true);
                    response.put("roi", getRoiData());
                    break;
                    
                case "SET_ROI":
                    JSONObject roiData = request.optJSONObject("roi");
                    if (roiData != null) {
                        applyRoi(roiData);
                    }
                    response.put("success", true);
                    response.put("message", "ROI updated");
                    break;
                
                // ==================== SAFE LOCATION COMMANDS ====================
                
                case "GET_SAFE_LOCATIONS":
                    response = at.greenchili.surfmate.surveillance.SafeLocationManager.getInstance().getStatusJson();
                    response.put("success", true);
                    break;
                    
                case "ADD_SAFE_LOCATION": {
                    JSONObject zoneData = request.optJSONObject("zone");
                    if (zoneData != null) {
                        at.greenchili.surfmate.surveillance.SafeLocation zone =
                            at.greenchili.surfmate.surveillance.SafeLocationManager.getInstance().addZone(
                                zoneData.optString("name", "Unnamed"),
                                zoneData.optDouble("lat", 0),
                                zoneData.optDouble("lng", 0),
                                zoneData.optInt("radiusM", 150));
                        response.put("success", zone != null);
                        if (zone != null) response.put("zone", zone.toJson());
                        else response.put("error", Messages.get("errors.zones_max_reached", 10));
                    } else {
                        response.put("success", false);
                        response.put("error", Messages.get("errors.zones_missing_data"));
                    }
                    break;
                }
                    
                case "UPDATE_SAFE_LOCATION": {
                    String zoneId = request.optString("id", null);
                    JSONObject updates = request.optJSONObject("updates");
                    if (zoneId != null && updates != null) {
                        boolean updated = at.greenchili.surfmate.surveillance.SafeLocationManager.getInstance()
                            .updateZone(zoneId, updates);
                        response.put("success", updated);
                    } else {
                        response.put("success", false);
                        response.put("error", Messages.get("errors.missing_id_or_updates"));
                    }
                    break;
                }
                    
                case "DELETE_SAFE_LOCATION": {
                    String zoneId = request.optString("id", null);
                    if (zoneId != null) {
                        boolean removed = at.greenchili.surfmate.surveillance.SafeLocationManager.getInstance()
                            .removeZone(zoneId);
                        response.put("success", removed);
                    } else {
                        response.put("success", false);
                        response.put("error", Messages.get("errors.missing_id"));
                    }
                    break;
                }
                    
                case "TOGGLE_SAFE_LOCATIONS": {
                    boolean enabled = request.optBoolean("enabled", true);
                    at.greenchili.surfmate.surveillance.SafeLocationManager.getInstance().setFeatureEnabled(enabled);
                    response.put("success", true);
                    response.put("enabled", enabled);
                    break;
                }
                
                // ==================== GPS SIDECAR COMMANDS ====================
                // GPS data from LocationSidecarService (app writes via IPC, daemon writes to file)
                
                case "UPDATE_GPS":
                    handleGpsUpdate(request);
                    // Fire-and-forget: LocationSidecarService writes the fix and
                    // never reads the ack. Return null so handleClient skips the
                    // reply write (saves a serialize + write syscall ~1/s).
                    return null;

                // ==================== ROADSENSE ====================
                // Batched IMU frames from the app-side RoadSense sidecar (D-023).
                // Feed straight into the daemon-side RoadSenseController pipeline.
                case at.greenchili.surfmate.roadsense.detect.ImuFrameCodec.COMMAND: { // "IMU_BATCH"
                    at.greenchili.surfmate.roadsense.RoadSenseController rs =
                            at.greenchili.surfmate.daemon.CameraDaemon.getRoadSense();
                    if (rs != null) {
                        // Pass the ALREADY-PARSED request straight to the codec —
                        // no request.toString() + re-parse round-trip of the
                        // nested-array batch (~10/s on the IPC reader thread).
                        rs.onImuBatch(request);
                    }
                    // Wächter-Erschütterung im Parkmodus (nur aktiv, wenn scharf + ACC aus).
                    at.greenchili.surfmate.surveillance.ShockMonitor.onImuBatch(request);
                    // Fire-and-forget: RoadSenseImuSidecarService never reads the
                    // ack. Return null so handleClient skips the reply write
                    // (saves a JSONObject build + serialize + write ~10/s).
                    return null;
                }

                // ==================== ABRP COMMANDS ====================
                case "SET_ABRP_CONFIG":
                    handleSetAbrpConfig(request, response);
                    break;

                case "GET_ABRP_CONFIG":
                    handleGetAbrpConfig(response);
                    break;

                case "GET_ABRP_STATUS":
                    handleGetAbrpStatus(response);
                    break;

                case "DELETE_ABRP_TOKEN":
                    handleDeleteAbrpToken(response);
                    break;

                // ==================== MQTT COMMANDS ====================
                case "GET_MQTT_CONNECTIONS":
                    handleGetMqttConnections(response);
                    break;

                case "ADD_MQTT_CONNECTION":
                    handleAddMqttConnection(request, response);
                    break;

                case "UPDATE_MQTT_CONNECTION":
                    handleUpdateMqttConnection(request, response);
                    break;

                case "DELETE_MQTT_CONNECTION":
                    handleDeleteMqttConnection(request, response);
                    break;

                case "GET_MQTT_STATUS":
                    handleGetMqttStatus(response);
                    break;

                case "GET_MQTT_TELEMETRY":
                    handleGetMqttTelemetry(response);
                    break;

                // ==================== TELEMETRY OVERLAY COMMANDS ====================

                case "SET_TELEMETRY_OVERLAY": {
                    boolean enabled = request.optBoolean("enabled", false);
                    JSONObject overlayConfig = new JSONObject();
                    overlayConfig.put("enabled", enabled);
                    at.greenchili.surfmate.config.UnifiedConfigManager.setTelemetryOverlay(overlayConfig);
                    // Notify pipeline
                    at.greenchili.surfmate.surveillance.GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
                    if (pipeline != null) {
                        pipeline.setOverlayEnabled(enabled);
                    }
                    response.put("success", true);
                    response.put("enabled", enabled);
                    break;
                }

                case "GET_TELEMETRY_OVERLAY": {
                    JSONObject overlayConfig = at.greenchili.surfmate.config.UnifiedConfigManager.getTelemetryOverlay();
                    response.put("success", true);
                    response.put("enabled", overlayConfig.optBoolean("enabled", false));
                    break;
                }

                // ==================== UPDATE COMMANDS ====================
                // Telegram daemon delegates here because AppUpdater needs the
                // app Context (PackageManager, SharedPreferences) which only
                // exists in CameraDaemon's process.

                case "CHECK_UPDATE":
                case "LIST_VERSIONS":
                case "GET_CHANNEL":
                case "SET_CHANNEL":
                case "INSTALL_UPDATE":
                case "GET_UPDATE_PROGRESS":
                    // Alter Updater entfernt (0.1.9): Updates nur über /api/appupdate/*
                    response.put("success", false);
                    response.put("error", "removed: use /api/appupdate");
                    break;






                case "UPLOAD_LOG":
                    // Log-Upload zu einem fremden Cloudflare Worker entfernt (0.1.9, Regel 2)
                    response.put("success", false);
                    response.put("error", "removed");
                    break;

                case "PARKING_STATUS":
                    // Telegram /where: the open (or latest) parking session.
                    // Same JSON the HTTP API serves, so both surfaces agree.
                    ParkingApiHandler.fillStatus(response);
                    // 0.1.21: Ort nur mit „Position teilen“ (ha.positionTeilen); sonst verlässt kein Ort den Daemon
                    HaApiHandler.woFreigabe(response);
                    response.put("success", true);
                    break;

                case "TELEGRAM_STILL": {
                    // Telegram /kamera: ein Standbild aller Kameras (Mosaik),
                    // nur wenn die Kameras gerade laufen. Die Datei ist für
                    // den Bot-Dienst (andere UID) lesbar.
                    byte[] jpeg = null;
                    at.greenchili.surfmate.surveillance.GpuSurveillancePipeline pipe =
                            CameraDaemon.getGpuPipeline();
                    if (pipe != null && pipe.getCamera() != null) {
                        jpeg = pipe.getCamera().getLatestJpegFrame(0);
                        if (jpeg == null || jpeg.length == 0) {
                            jpeg = pipe.getCamera().sampleFullResMosaicJpeg();
                        }
                    }
                    if (jpeg == null || jpeg.length == 0) {
                        response.put("success", false);
                        response.put("error", "cameras_off");
                        break;
                    }
                    java.io.File f = new java.io.File(at.greenchili.surfmate.util.DaemonStorage.rebase(
                            "/data/user_de/0/com.android.shell/surfmate/telegram_still.jpg"));
                    java.io.File tmp = new java.io.File(f.getAbsolutePath() + ".tmp");
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) {
                        fos.write(jpeg);
                    }
                    tmp.setReadable(true, false);
                    if (!tmp.renameTo(f)) {
                        tmp.delete();
                        response.put("success", false);
                        response.put("error", "write_failed");
                        break;
                    }
                    response.put("success", true);
                    response.put("path", f.getAbsolutePath());
                    break;
                }

                default:
                    logger.warn("Unknown IPC command: " + command);
                    response.put("success", false);
                    response.put("error", Messages.get("errors.unknown_command", command));
            }
        } catch (Exception e) {
            logger.error("Error handling IPC command", e);
            try {
                response.put("success", false);
                response.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        
        return response;
    }

    private static boolean hasCurrentDiLink5IngressSession(JSONObject request) {
        return request != null
                && DILINK5_INGRESS_SESSION.equals(
                        request.optString("session", ""));
    }

    static synchronized boolean rememberNonce(String nonce, long timestampMs) {
        long cutoff = System.currentTimeMillis()
                - RemoteDevViewBridgeAuth.MAX_CLOCK_SKEW_MS;
        Iterator<Map.Entry<String, Long>> entries =
                DILINK5_INGRESS_NONCES.entrySet().iterator();
        while (entries.hasNext()) {
            if (entries.next().getValue() >= cutoff) break;
            entries.remove();
        }
        if (nonce == null || nonce.isEmpty()
                || DILINK5_INGRESS_NONCES.containsKey(nonce)) {
            return false;
        }
        DILINK5_INGRESS_NONCES.put(nonce, timestampMs);
        while (DILINK5_INGRESS_NONCES.size() > DILINK5_INGRESS_NONCE_LIMIT) {
            Iterator<String> oldest = DILINK5_INGRESS_NONCES.keySet().iterator();
            if (!oldest.hasNext()) break;
            oldest.next();
            oldest.remove();
        }
        return true;
    }
    
    /**
     * Apply configuration changes to surveillance system.
     * Updates both the running engine (if available) AND persists to config file.
     * Config is ALWAYS persisted even if surveillance is not running.
     *
     * synchronized: the IPC server runs an 8-thread pool; without serialization,
     * two concurrent SET_CONFIG requests (web UI + Telegram daemon, say) could
     * each load + mutate + save their own snapshot, losing the other's update.
     * Lock granularity is class-wide (CONFIG_LOCK) because the persisted file
     * is shared state, not instance state.
     */
    private static final Object CONFIG_LOCK = new Object();

    /**
     * @return null on success, or a human-readable reason when a LOAD-BEARING
     *         write failed and the caller must not acknowledge the change.
     *         Only the surveillance master toggle reports this way today: the
     *         ACC-OFF arm dispatch re-reads the persisted flag, so a silently
     *         dropped write means surveillance never arms on the next park.
     */
    private String applyConfig(JSONObject config) {
        synchronized (CONFIG_LOCK) { return applyConfigLocked(config); }
    }

    private String applyConfigLocked(JSONObject config) {
        try {
            at.greenchili.surfmate.surveillance.GpuSurveillancePipeline pipeline =
                CameraDaemon.getGpuPipeline();
            
            // Sentry may be null if surveillance is not running - that's OK
            at.greenchili.surfmate.surveillance.SurveillanceEngineGpu sentry = null;
            if (pipeline != null) {
                sentry = pipeline.getSentry();
            }
            
            // Get or create SurveillanceConfig for persistence
            // Even if sentry is null, we still want to persist the config
            at.greenchili.surfmate.surveillance.SurveillanceConfig sentryConfig = null;
            if (sentry != null) {
                sentryConfig = sentry.getConfig();
            }
            if (sentryConfig == null) {
                // Load from file or create new
                try {
                    at.greenchili.surfmate.surveillance.SurveillanceConfigManager configManager =
                        new at.greenchili.surfmate.surveillance.SurveillanceConfigManager();
                    if (configManager.configExists()) {
                        sentryConfig = configManager.loadConfig();
                        logger.info("Loaded existing config from file for update");
                    } else {
                        sentryConfig = new at.greenchili.surfmate.surveillance.SurveillanceConfig();
                        logger.info("Created new config for persistence");
                    }
                } catch (Exception e) {
                    sentryConfig = new at.greenchili.surfmate.surveillance.SurveillanceConfig();
                    logger.error("Failed to load config, using defaults", e);
                }
            }
            
            boolean configChanged = false;
            
            // Handle surveillance storage type change (INTERNAL, SD_CARD, USB)
            if (config.has("surveillanceStorageType")) {
                String typeStr = config.getString("surveillanceStorageType").toUpperCase();
                at.greenchili.surfmate.storage.StorageManager storageManager =
                    at.greenchili.surfmate.storage.StorageManager.getInstance();
                at.greenchili.surfmate.storage.StorageManager.StorageType type;
                switch (typeStr) {
                    case "SD_CARD": type = at.greenchili.surfmate.storage.StorageManager.StorageType.SD_CARD; break;
                    case "USB":     type = at.greenchili.surfmate.storage.StorageManager.StorageType.USB;     break;
                    default:        type = at.greenchili.surfmate.storage.StorageManager.StorageType.INTERNAL;
                }
                boolean success = storageManager.setSurveillanceStorageType(type);
                if (success) {
                    logger.info("Surveillance storage type set to " + type + " via IPC");
                    if (sentry != null) {
                        sentry.setEventOutputDir(storageManager.getSurveillanceDir());
                        logger.info("Updated sentry output dir: " + storageManager.getSurveillanceDir().getAbsolutePath());
                    }
                } else {
                    logger.warn("Failed to set surveillance storage to " + type + " - not available");
                }
            }
            
            // Handle surveillance storage limit change
            if (config.has("surveillanceLimitMb")) {
                long limitMb = config.getLong("surveillanceLimitMb");
                at.greenchili.surfmate.storage.StorageManager storageManager =
                    at.greenchili.surfmate.storage.StorageManager.getInstance();
                storageManager.setSurveillanceLimitMb(limitMb);
                logger.info("Surveillance limit set to " + storageManager.getSurveillanceLimitMb() + " MB via IPC");
                // Trigger async cleanup
                new Thread(() -> storageManager.ensureSurveillanceSpace(0), "SurvLimitCleanup").start();
            }
            
            // Check if enabled state changed
            if (config.has("enabled")) {
                boolean enabled = config.getBoolean("enabled");
                // Persist to unified config so ACC OFF respects user preference.
                // A failed write means the ACC-OFF arm dispatch (which re-reads the
                // persisted flag) will not see this intent — surface it rather than
                // acknowledging a change that won't survive.
                if (!at.greenchili.surfmate.config.UnifiedConfigManager.setSurveillanceEnabled(enabled)) {
                    logger.warn("Failed to persist surveillanceEnabled=" + enabled + " via IPC");
                    return "Failed to save the surveillance setting";
                }
                if (enabled) {
                    // RACE CONDITION FIX: Only enable surveillance if ACC is actually OFF.
                    // AccSentryDaemon's retry loop may send this IPC after ACC turned ON.
                    if (!at.greenchili.surfmate.monitor.AccMonitor.isAccOn()) {
                        CameraDaemon.enableSurveillance();   // fires OEM recalc
                        logger.info("Surveillance enabled via IPC");
                    } else {
                        logger.info("Surveillance preference saved via IPC — but ACC is ON, not activating");
                        try { at.greenchili.surfmate.server.OemDashcamApiHandler.scheduleLifecycleRecalc(); }
                        catch (Throwable ignored) {}
                    }
                } else if (!at.greenchili.surfmate.monitor.AccMonitor.isAccOn()) {
                    // ACC-gated like the other preference doors: nothing is armed
                    // while ACC is ON. (The stopSurveillance primitive below is a
                    // session stop, not a preference change, and stays unconditional
                    // so battery protection can always tear a live sentry down.)
                    CameraDaemon.disableSurveillance();   // fires OEM recalc
                    logger.info("Surveillance disabled via IPC");
                } else {
                    logger.info("Surveillance preference disabled via IPC — ACC is ON, nothing armed to stop");
                    try { at.greenchili.surfmate.server.OemDashcamApiHandler.scheduleLifecycleRecalc(); }
                    catch (Throwable ignored) {}
                }
            }
            
            // Stop surveillance without persisting preference (battery protection, session stop)
            if (config.has("stopSurveillance") && config.getBoolean("stopSurveillance")) {
                CameraDaemon.disableSurveillance();
                logger.info("Surveillance stopped via IPC (preference preserved)");
            }
            
            // Handle ACC state if provided
            if (config.has("accOff")) {
                boolean accOff = config.getBoolean("accOff");
                CameraDaemon.onAccStateChanged(accOff);
                logger.info("ACC state changed via IPC: " + (accOff ? "OFF" : "ON"));
            }
            
            // Handle gear state if provided
            if (config.has("gear")) {
                int gear = config.getInt("gear");
                CameraDaemon.onGearChanged(gear);
                logger.info("Gear changed via IPC: " + at.greenchili.surfmate.recording.RecordingModeManager.gearToString(gear));
            }
            
            // Handle sensitivity setting (maps to minObjectSize)
            if (config.has("sensitivity")) {
                String sensitivity = config.optString("sensitivity", "MEDIUM").toUpperCase();
                float minSize;
                switch (sensitivity) {
                    case "LOW":
                        minSize = 0.02f;  // 2% - detect distant objects
                        break;
                    case "HIGH":
                        minSize = 0.15f;  // 15% - only close objects
                        break;
                    case "MEDIUM":
                    default:
                        minSize = 0.08f;  // 8% - balanced
                        break;
                }
                float confidence = (float) config.optDouble("aiConfidence", sentryConfig.getAiConfidence());
                boolean detectPerson = config.optBoolean("detectPerson", sentryConfig.isDetectPerson());
                boolean detectCar = config.optBoolean("detectCar", sentryConfig.isDetectCar());
                boolean detectBike = config.optBoolean("detectBike", sentryConfig.isDetectBike());
                boolean detectAnimal = config.optBoolean("detectAnimal", sentryConfig.isDetectAnimal());

                // Apply to running engine if available
                if (sentry != null) {
                    sentry.setObjectFilters(minSize, confidence, detectPerson, detectCar, detectBike, detectAnimal);
                }

                // Update config object for persistence
                sentryConfig.setMinObjectSize(minSize);
                sentryConfig.setAiConfidence(confidence);
                sentryConfig.setDetectPerson(detectPerson);
                sentryConfig.setDetectCar(detectCar);
                sentryConfig.setDetectBike(detectBike);
                sentryConfig.setDetectAnimal(detectAnimal);
                configChanged = true;

                logger.info("Sensitivity set to " + sensitivity + " (minObjectSize=" + minSize + ")");
            }
            
            // Apply object detection filters (direct minObjectSize override)
            if (config.has("minObjectSize") || config.has("aiConfidence") ||
                config.has("detectPerson") || config.has("detectCar") || config.has("detectBike") ||
                config.has("detectAnimal")) {

                float minSize = (float) config.optDouble("minObjectSize", sentryConfig.getMinObjectSize());
                float confidence = (float) config.optDouble("aiConfidence", sentryConfig.getAiConfidence());
                boolean detectPerson = config.optBoolean("detectPerson", sentryConfig.isDetectPerson());
                boolean detectCar = config.optBoolean("detectCar", sentryConfig.isDetectCar());
                boolean detectBike = config.optBoolean("detectBike", sentryConfig.isDetectBike());
                boolean detectAnimal = config.optBoolean("detectAnimal", sentryConfig.isDetectAnimal());

                // Apply to running engine if available
                if (sentry != null) {
                    sentry.setObjectFilters(minSize, confidence, detectPerson, detectCar, detectBike, detectAnimal);
                }

                // Update config object for persistence
                sentryConfig.setMinObjectSize(minSize);
                sentryConfig.setAiConfidence(confidence);
                sentryConfig.setDetectPerson(detectPerson);
                sentryConfig.setDetectCar(detectCar);
                sentryConfig.setDetectBike(detectBike);
                sentryConfig.setDetectAnimal(detectAnimal);
                configChanged = true;

                logger.info("Object filters applied (sentry " + (sentry != null ? "running" : "not running") + ")");
            }
            
            // Handle pre/post record seconds
            if (config.has("preRecordSeconds") || config.has("preEventBufferSeconds")) {
                int preRecordSeconds = config.has("preRecordSeconds") 
                    ? config.optInt("preRecordSeconds", 5)
                    : config.optInt("preEventBufferSeconds", 5);
                
                // Apply to running engine if available
                if (sentry != null) {
                    sentry.setPreRecordSeconds(preRecordSeconds);
                }
                
                sentryConfig.setPreRecordSeconds(preRecordSeconds);
                configChanged = true;
                logger.info("Pre-record seconds set to: " + preRecordSeconds);
            }
            
            if (config.has("postRecordSeconds") || config.has("postEventBufferSeconds")) {
                int postRecordSeconds = config.has("postRecordSeconds")
                    ? config.optInt("postRecordSeconds", 10)
                    : config.optInt("postEventBufferSeconds", 10);
                
                // Apply to running engine if available
                if (sentry != null) {
                    sentry.setPostRecordSeconds(postRecordSeconds);
                }
                
                sentryConfig.setPostRecordSeconds(postRecordSeconds);
                configChanged = true;
                logger.info("Post-record seconds set to: " + postRecordSeconds);
            }
            
            // Handle recording quality / legacy bitrate setting.
            // Prefer the canonical `recordingQuality` (ECONOMY..MAX); accept the
            // legacy `bitrate` (LOW/MEDIUM/HIGH) only as a fallback.
            String tierIn = null;
            if (config.has("recordingQuality")) {
                tierIn = config.optString("recordingQuality", "").toUpperCase();
            } else if (config.has("bitrate")) {
                String legacy = config.optString("bitrate", "").toUpperCase();
                switch (legacy) {
                    case "LOW":    tierIn = "ECONOMY"; break;
                    case "MEDIUM": tierIn = "STANDARD"; break;
                    case "HIGH":   tierIn = "HIGH"; break;
                    default:       tierIn = null; break;
                }
            }
            if (tierIn != null && !tierIn.isEmpty()) {
                CameraDaemon.setRecordingQuality(tierIn);
                HttpServer.setRecordingBitrateStatic(tierIn);  // legacy alias setter; takes any string
                logger.info("Recording quality set to: " + tierIn);
            }
            
            // Handle codec setting
            if (config.has("codec")) {
                String codec = config.optString("codec", "H264").toUpperCase();
                if (codec.equals("H264") || codec.equals("H265")) {
                    CameraDaemon.setRecordingCodec(codec);
                    // Also update HttpServer's static setting for web UI sync
                    HttpServer.setRecordingCodecStatic(codec);
                    logger.info("Recording codec set to: " + codec);
                }
            }
            
            // Persist recording settings to file so web UI can read them
            if (config.has("recordingQuality") || config.has("bitrate") || config.has("codec")) {
                HttpServer.persistSettingsStatic();
            }
            
            // SOTA: Handle flash immunity setting (0=OFF, 1=LOW, 2=MEDIUM, 3=HIGH)
            if (config.has("flashImmunity")) {
                int flashImmunity = config.optInt("flashImmunity", 2);
                
                // Apply to running engine if available
                if (sentry != null) {
                    sentry.setFlashImmunity(flashImmunity);
                }
                
                sentryConfig.setFlashImmunity(flashImmunity);
                configChanged = true;
                logger.info("Flash immunity set to: " + flashImmunity);
            }
            
            // SOTA: Handle distance preset (1-5 slider value)
            // Distance ONLY controls minObjectSize (AI detection range)
            // Block size is LOCKED at 32. Motion sensitivity is handled separately.
            if (config.has("distance")) {
                int distance = config.optInt("distance", 3);
                
                // Map slider value to minObjectSize for AI detection
                // 1 = Close (~3m, 25%), 2 = Near (~5m, 18%), 3 = Medium (~8m, 12%), 
                // 4 = Far (~10m, 8%), 5 = Very Far (~15m, 5%)
                float minObjectSize;
                String distanceLabel;
                switch (distance) {
                    case 1: minObjectSize = 0.25f; distanceLabel = "CLOSE (~3m)"; break;
                    case 2: minObjectSize = 0.18f; distanceLabel = "NEAR (~5m)"; break;
                    case 3: minObjectSize = 0.12f; distanceLabel = "MEDIUM (~8m)"; break;
                    case 4: minObjectSize = 0.08f; distanceLabel = "FAR (~10m)"; break;
                    case 5: minObjectSize = 0.05f; distanceLabel = "VERY_FAR (~15m)"; break;
                    default: minObjectSize = 0.12f; distanceLabel = "MEDIUM (~8m)"; break;
                }
                
                // Only update minObjectSize - don't touch motion sensitivity settings
                sentryConfig.setMinObjectSize(minObjectSize);
                configChanged = true;
                
                // Apply to running engine if available
                if (sentry != null) {
                    float confidence = sentryConfig.getAiConfidence();
                    boolean detectPerson = sentryConfig.isDetectPerson();
                    boolean detectCar = sentryConfig.isDetectCar();
                    boolean detectBike = sentryConfig.isDetectBike();
                    boolean detectAnimal = sentryConfig.isDetectAnimal();
                    sentry.setObjectFilters(minObjectSize, confidence, detectPerson, detectCar, detectBike, detectAnimal);
                }
                
                logger.info(String.format("Distance set via IPC: %s (minObjectSize=%.0f%%)",
                    distanceLabel, minObjectSize * 100));
            }
            
            // SOTA: Handle motion sensitivity slider (1-5) - SEPARATE from distance
            // Controls requiredBlocks and densityThreshold for motion detection
            // Block size is LOCKED at 32
            if (config.has("sensitivity") && config.optInt("sensitivity", -1) >= 1 && config.optInt("sensitivity", -1) <= 5) {
                int sensitivityLevel = config.optInt("sensitivity", 3);
                
                // Map slider value to motion detection thresholds
                // Production table with block size locked at 32:
                // 1=Strict (req=4, density=48), 2=Conservative (req=3, density=40), 
                // 3=Default (req=2, density=32), 4=Sensitive (req=2, density=16), 5=Aggressive (req=1, density=12)
                int requiredBlocks;
                
                switch (sensitivityLevel) {
                    case 1:  // Strict - large objects only
                        requiredBlocks = 4;
                        break;
                    case 2:  // Conservative - solid objects
                        requiredBlocks = 3;
                        break;
                    case 3:  // Default - balanced
                        requiredBlocks = 2;
                        break;
                    case 4:  // Sensitive - catches motion quickly
                        requiredBlocks = 2;
                        break;
                    case 5:  // Aggressive - any motion
                        requiredBlocks = 1;
                        break;
                    default:
                        requiredBlocks = 2;
                        break;
                }
                
                // Convert slider 1-5 to percentage 20-100 for unified sensitivity
                int sensitivityPercent = sensitivityLevel * 20;
                
                // Update config for persistence
                sentryConfig.setRequiredBlocks(requiredBlocks);
                sentryConfig.setUnifiedSensitivity(sensitivityPercent);
                configChanged = true;
                
                // Apply to running engine if available
                if (sentry != null) {
                    sentry.setUnifiedSensitivity(sensitivityPercent);
                    sentry.setRequiredActiveBlocks(requiredBlocks);
                }
                
                logger.info(String.format("Motion sensitivity set to level %d (%d%%, alarm=%d blocks)",
                    sensitivityLevel, sensitivityPercent, requiredBlocks));
            }
            
            // Handle block sensitivity (grid motion detection) - skip if distance was set
            if (config.has("blockSensitivity") && !config.has("distance")) {
                float blockSens = (float) config.optDouble("blockSensitivity", 0.04);
                
                // Apply to running engine if available
                if (sentry != null) {
                    sentry.setBlockSensitivity(blockSens);
                }
                
                sentryConfig.setSensitivity(blockSens);
                configChanged = true;
                logger.info("Block sensitivity set to: " + blockSens);
            }
            
            // Handle required active blocks - skip if distance was set
            if (config.has("requiredActiveBlocks") && !config.has("distance")) {
                int reqBlocks = config.optInt("requiredActiveBlocks", 2);
                
                // Apply to running engine if available
                if (sentry != null) {
                    sentry.setRequiredActiveBlocks(reqBlocks);
                }
                
                sentryConfig.setRequiredBlocks(reqBlocks);
                configChanged = true;
                logger.info("Required active blocks set to: " + reqBlocks);
            }
            
            // Apply updated config to engine (if running) and ALWAYS persist to file
            if (configChanged) {
                // Apply config to running engine (syncs internal state like preRecordMs)
                if (sentry != null) {
                    sentry.setConfig(sentryConfig);
                }
                
                // ALWAYS persist to file - this is critical for config to survive restarts
                try {
                    at.greenchili.surfmate.surveillance.SurveillanceConfigManager configManager =
                        new at.greenchili.surfmate.surveillance.SurveillanceConfigManager();
                    configManager.saveConfig(sentryConfig);
                    logger.info("Surveillance config persisted to file (sentry " + 
                        (sentry != null ? "running" : "not running") + ")");
                } catch (Exception e) {
                    logger.error("Failed to persist surveillance config", e);
                }
            }
            
            // Note: minObjectHeight filter is now applied in C++ (yolo_detector.cpp)
            // Height filter (10% of frame) is applied BEFORE NMS in native code for efficiency
            
            // ========================================================================
            // V2 Pipeline Settings
            // ========================================================================
            
            // Environment preset (outdoor/garage/street) — sets slider defaults
            if (config.has("environmentPreset")) {
                String preset = config.optString("environmentPreset", "outdoor").toLowerCase();
                sentryConfig.setEnvironmentPreset(preset);
                if (sentry != null) {
                    sentry.applyV2EnvironmentPreset(preset);
                }
                configChanged = true;
                logger.info("V2 environment preset: " + preset);
            }
            
            // Sensitivity level (1-5)
            if (config.has("sensitivityLevel")) {
                int level = config.optInt("sensitivityLevel", 3);
                sentryConfig.setSensitivityLevel(level);
                if (sentry != null) {
                    sentry.applyV2Sensitivity(level);
                }
                configChanged = true;
                logger.info("V2 sensitivity level: " + level);
            }
            
            // Detection zone (close/normal/extended)
            if (config.has("detectionZone")) {
                String zone = config.optString("detectionZone", "normal").toLowerCase();
                sentryConfig.setDetectionZone(zone);
                configChanged = true;
                logger.info("V2 detection zone: " + zone);
            }
            
            // Loitering time (seconds)
            if (config.has("loiteringTime")) {
                int seconds = config.optInt("loiteringTime", 3);
                sentryConfig.setLoiteringTimeSeconds(seconds);
                if (sentry != null) {
                    sentry.setV2LoiteringTime(seconds);
                }
                configChanged = true;
                logger.info("V2 loitering time: " + seconds + "s");
            }
            
            // Shadow filter mode (0=OFF, 1=LIGHT, 2=NORMAL, 3=AGGRESSIVE)
            if (config.has("shadowFilter")) {
                int mode = config.optInt("shadowFilter", 2);
                sentryConfig.setShadowFilterMode(mode);
                if (sentry != null) {
                    sentry.setV2ShadowFilterMode(mode);
                }
                configChanged = true;
                String[] modeNames = {"OFF", "LIGHT", "NORMAL", "AGGRESSIVE"};
                logger.info("V2 shadow filter: " + (mode >= 0 && mode <= 3 ? modeNames[mode] : "invalid"));
            }
            
            // Per-camera enable/disable
            // Quadrant mapping: Q0=front, Q1=right, Q2=rear, Q3=left
            if (config.has("cameraFront")) {
                boolean enabled = config.optBoolean("cameraFront", true);
                sentryConfig.setCameraEnabled(0, enabled);
                if (sentry != null) sentry.setV2QuadrantEnabled(0, enabled);
                configChanged = true;
            }
            if (config.has("cameraRight")) {
                boolean enabled = config.optBoolean("cameraRight", true);
                sentryConfig.setCameraEnabled(1, enabled);
                if (sentry != null) sentry.setV2QuadrantEnabled(1, enabled);
                configChanged = true;
            }
            if (config.has("cameraRear")) {
                boolean enabled = config.optBoolean("cameraRear", true);
                sentryConfig.setCameraEnabled(2, enabled);
                if (sentry != null) sentry.setV2QuadrantEnabled(2, enabled);
                configChanged = true;
            }
            if (config.has("cameraLeft")) {
                boolean enabled = config.optBoolean("cameraLeft", true);
                sentryConfig.setCameraEnabled(3, enabled);
                if (sentry != null) sentry.setV2QuadrantEnabled(3, enabled);
                configChanged = true;
            }
            
            // Developer toggles
            if (config.has("motionHeatmap")) {
                sentryConfig.setMotionHeatmapEnabled(config.optBoolean("motionHeatmap", false));
                configChanged = true;
            }
            if (config.has("filterDebugLog")) {
                boolean debugEnabled = config.optBoolean("filterDebugLog", false);
                sentryConfig.setFilterDebugLogEnabled(debugEnabled);
                if (sentry != null) {
                    sentry.setFilterDebugEnabled(debugEnabled);
                }
                configChanged = true;
            }
            if (config.has("telegramSendStartPing")) {
                sentryConfig.setTelegramSendStartPing(
                        config.optBoolean("telegramSendStartPing", false));
                configChanged = true;
            }
            // Per-tier filter is persisted on the telegram section now — see
            // UnifiedTelegramConfig.K_TIER_*. Don't flip configChanged: the
            // surveillance config is unaffected, and writing through
            // setBoolean already triggers an updateSection write that the
            // gate's forceReload() will pick up on the next event.
            if (config.has("telegramNotices")) {
                at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig.setBoolean(
                        at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig.K_TIER_NOTICES,
                        config.optBoolean("telegramNotices", false));
            }
            if (config.has("telegramAlerts")) {
                at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig.setBoolean(
                        at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig.K_TIER_ALERTS,
                        config.optBoolean("telegramAlerts", true));
            }
            if (config.has("telegramCritical")) {
                at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig.setBoolean(
                        at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig.K_TIER_CRITICAL,
                        config.optBoolean("telegramCritical", true));
            }

        } catch (Exception e) {
            logger.error("Failed to apply config", e);
        }
        return null;
    }

    /**
     * Apply ROI configuration to surveillance system.
     */
    private void applyRoi(JSONObject roiData) {
        try {
            at.greenchili.surfmate.surveillance.GpuSurveillancePipeline pipeline =
                CameraDaemon.getGpuPipeline();
            
            if (pipeline == null || pipeline.getSentry() == null) {
                logger.warn("Cannot apply ROI - surveillance not initialized");
                return;
            }
            
            // Parse polygon points
            org.json.JSONArray pointsArray = roiData.optJSONArray("points");
            if (pointsArray == null || pointsArray.length() < 3) {
                // Clear ROI
                pipeline.getSentry().setRoiMask(null);
                logger.info("ROI cleared");
                return;
            }
            
            // Convert to float array
            float[][] points = new float[pointsArray.length()][2];
            for (int i = 0; i < pointsArray.length(); i++) {
                org.json.JSONArray point = pointsArray.getJSONArray(i);
                points[i][0] = (float) point.getDouble(0);
                points[i][1] = (float) point.getDouble(1);
            }
            
            // Apply to surveillance engine
            pipeline.getSentry().setRoiFromPolygon(points);
            logger.info("ROI applied with " + points.length + " vertices");
            
        } catch (Exception e) {
            logger.error("Failed to apply ROI", e);
        }
    }
    
    /**
     * Get current ROI data.
     */
    private JSONObject getRoiData() {
        JSONObject roi = new JSONObject();
        try {
            // For now, return empty - would need to store ROI points
            roi.put("enabled", false);
            roi.put("points", new org.json.JSONArray());
        } catch (Exception e) {
            logger.error("Failed to get ROI data", e);
        }
        return roi;
    }
    
    private JSONObject getDefaultConfig() throws Exception {
        JSONObject config = new JSONObject();
        
        // Read persisted preference (not runtime state) for the UI toggle
        boolean enabled = at.greenchili.surfmate.config.UnifiedConfigManager.isSurveillanceEnabled();
        
        config.put("enabled", enabled);
        config.put("noiseThreshold", 0.0001);
        config.put("lightThreshold", 0.4);
        config.put("aiEnabled", true);
        config.put("scheduleEnabled", false);
        config.put("recordingQuality", CameraDaemon.getRecordingQuality());
        config.put("codec", CameraDaemon.getRecordingCodec());
        
        // Get actual values from sentry config if available
        at.greenchili.surfmate.surveillance.GpuSurveillancePipeline pipeline =
            CameraDaemon.getGpuPipeline();
        
        at.greenchili.surfmate.surveillance.SurveillanceConfig sentryConfig = null;
        
        if (pipeline != null && pipeline.getSentry() != null) {
            at.greenchili.surfmate.surveillance.SurveillanceEngineGpu sentry = pipeline.getSentry();
            sentryConfig = sentry.getConfig();
        }
        
        // If sentry not running, try to load from file
        if (sentryConfig == null) {
            try {
                at.greenchili.surfmate.surveillance.SurveillanceConfigManager configManager =
                    new at.greenchili.surfmate.surveillance.SurveillanceConfigManager();
                if (configManager.configExists()) {
                    sentryConfig = configManager.loadConfig();
                    logger.info("Loaded config from file for GET_CONFIG (sentry not running)");
                }
            } catch (Exception e) {
                logger.error("Failed to load config from file", e);
            }
        }
        
        if (sentryConfig != null) {
            // Read from config object
            config.put("minObjectSize", sentryConfig.getMinObjectSize());
            config.put("aiConfidence", sentryConfig.getAiConfidence());
            config.put("detectPerson", sentryConfig.isDetectPerson());
            config.put("detectCar", sentryConfig.isDetectCar());
            config.put("detectBike", sentryConfig.isDetectBike());
            config.put("detectAnimal", sentryConfig.isDetectAnimal());
            config.put("flashImmunity", sentryConfig.getFlashImmunity());
            config.put("preEventBufferSeconds", sentryConfig.getPreRecordSeconds());
            config.put("postEventBufferSeconds", sentryConfig.getPostRecordSeconds());
            config.put("blockSensitivity", sentryConfig.getSensitivity());
            config.put("requiredActiveBlocks", sentryConfig.getRequiredBlocks());
            
            // SOTA: Return sensitivity as slider value (1-5) based on requiredBlocks
            // Maps: 1=Strict(req=4), 2=Conservative(req=3), 3=Default(req=2), 4=Sensitive(req=2,density=16), 5=Aggressive(req=1)
            int reqBlocks = sentryConfig.getRequiredBlocks();
            int sensitivityLevel;
            if (reqBlocks >= 4) {
                sensitivityLevel = 1;  // Strict
            } else if (reqBlocks == 3) {
                sensitivityLevel = 2;  // Conservative
            } else if (reqBlocks == 2) {
                sensitivityLevel = 3;  // Default (could be 3 or 4, default to 3)
            } else {
                sensitivityLevel = 5;  // Aggressive
            }
            config.put("sensitivity", sensitivityLevel);
            
            // SOTA: Return distance as slider value (1-5) based on minObjectSize
            float minSize = sentryConfig.getMinObjectSize();
            int distanceLevel;
            if (minSize >= 0.22f) {
                distanceLevel = 1;  // ~3m (near)
            } else if (minSize >= 0.15f) {
                distanceLevel = 2;  // ~5m
            } else if (minSize >= 0.10f) {
                distanceLevel = 3;  // ~8m
            } else if (minSize >= 0.06f) {
                distanceLevel = 4;  // ~10m
            } else {
                distanceLevel = 5;  // ~15m (far)
            }
            config.put("distance", distanceLevel);
        } else {
            // Defaults when no config available
            config.put("sensitivity", 3);  // Default (slider value 1-5)
            config.put("distance", 3);     // ~8m (slider value 1-5)
            config.put("minObjectSize", 0.08);
            config.put("aiConfidence", 0.6);
            config.put("detectPerson", true);
            config.put("detectCar", true);
            config.put("detectBike", true);
            config.put("detectAnimal", false);
            config.put("flashImmunity", 2);  // Default MEDIUM
            config.put("preEventBufferSeconds", 5);
            config.put("postEventBufferSeconds", 10);
            config.put("blockSensitivity", 0.04);
            config.put("requiredActiveBlocks", 2);
        }
        
        // SOTA: Add lastModified timestamp for web UI sync detection
        config.put("lastModified", at.greenchili.surfmate.config.UnifiedConfigManager.getLastModified());
        
        return config;
    }
    
    /** Device model for the backup-bundle manifest (same-device advisory). */
    private static String deviceModelString() {
        try {
            String m = android.os.Build.MODEL;
            return (m == null || m.isEmpty()) ? "unknown" : m;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private JSONObject getSurveillanceStatus() throws Exception {
        JSONObject status = new JSONObject();
        
        // Read from persisted config (not in-memory flag)
        boolean enabled = at.greenchili.surfmate.config.UnifiedConfigManager.isSurveillanceEnabled();
        boolean active = CameraDaemon.isSentryArmed();   // wirklich scharf, nicht nur Pipeline läuft
        
        status.put("enabled", enabled);
        status.put("active", active);
        status.put("recording", false);
        
        return status;
    }
    
    // ==================== VEHICLE DATA HELPERS ====================
    
    private JSONObject getVehicleData() throws Exception {
        at.greenchili.surfmate.monitor.VehicleDataMonitor monitor =
            at.greenchili.surfmate.monitor.VehicleDataMonitor.getInstance();
        return monitor.getAllData();
    }
    
    private JSONObject getBatteryVoltageData() throws Exception {
        at.greenchili.surfmate.monitor.VehicleDataMonitor monitor =
            at.greenchili.surfmate.monitor.VehicleDataMonitor.getInstance();
        at.greenchili.surfmate.monitor.BatteryVoltageData data = monitor.getBatteryVoltage();
        
        if (data == null) throw new Exception("Battery voltage data not available");
        
        JSONObject json = new JSONObject();
        json.put("level", data.level);
        json.put("levelName", data.levelName);
        json.put("isWarning", data.isWarning);
        json.put("timestamp", data.timestamp);
        return json;
    }
    
    private JSONObject getBatteryPowerData() throws Exception {
        at.greenchili.surfmate.monitor.VehicleDataMonitor monitor =
            at.greenchili.surfmate.monitor.VehicleDataMonitor.getInstance();
        at.greenchili.surfmate.monitor.BatteryPowerData data = monitor.getBatteryPower();
        
        if (data == null) throw new Exception("Battery power data not available");
        
        JSONObject json = new JSONObject();
        json.put("voltageVolts", data.voltageVolts);
        json.put("isWarning", data.isWarning);
        json.put("isCritical", data.isCritical);
        json.put("healthStatus", data.getHealthStatus());
        json.put("timestamp", data.timestamp);
        return json;
    }
    
    private JSONObject getBatterySocData() throws Exception {
        at.greenchili.surfmate.monitor.VehicleDataMonitor monitor =
            at.greenchili.surfmate.monitor.VehicleDataMonitor.getInstance();
        at.greenchili.surfmate.monitor.BatterySocData data = monitor.getBatterySoc();
        
        if (data == null) throw new Exception("Battery SOC data not available");
        
        JSONObject json = new JSONObject();
        json.put("socPercent", data.socPercent);
        json.put("isLow", data.isLow);
        json.put("isCritical", data.isCritical);
        json.put("status", data.getStatus());
        json.put("timestamp", data.timestamp);
        return json;
    }
    
    private JSONObject getChargingStateData() throws Exception {
        at.greenchili.surfmate.monitor.VehicleDataMonitor monitor =
            at.greenchili.surfmate.monitor.VehicleDataMonitor.getInstance();
        at.greenchili.surfmate.monitor.ChargingStateData data = monitor.getChargingState();
        
        if (data == null) throw new Exception("Charging state data not available");
        
        JSONObject json = new JSONObject();
        json.put("stateCode", data.stateCode);
        json.put("stateName", data.stateName);
        json.put("status", data.status.name());
        json.put("isError", data.isError);
        json.put("errorType", data.errorType);
        json.put("chargingPowerKW", data.chargingPowerKW);
            // isEstimated travels WITH the number. getChargingState() substitutes a nominal
            // 3.3/7.0 kW placeholder (or an inferred engine-power figure) whenever nothing has
            // resolved a real rate, so a consumer receiving the value alone cannot tell a
            // measurement from a guess and will render the guess as measured power.
            json.put("isEstimated", data.isEstimated);
        json.put("isDischarging", data.isDischarging);
        json.put("timestamp", data.timestamp);
        return json;
    }
    
    private JSONObject getChargingPowerData() throws Exception {
        at.greenchili.surfmate.monitor.VehicleDataMonitor monitor =
            at.greenchili.surfmate.monitor.VehicleDataMonitor.getInstance();
        at.greenchili.surfmate.monitor.ChargingStateData data = monitor.getChargingState();
        
        if (data == null) throw new Exception("Charging power data not available");
        
        JSONObject json = new JSONObject();
        json.put("chargingPowerKW", data.chargingPowerKW);
            // isEstimated travels WITH the number. getChargingState() substitutes a nominal
            // 3.3/7.0 kW placeholder (or an inferred engine-power figure) whenever nothing has
            // resolved a real rate, so a consumer receiving the value alone cannot tell a
            // measurement from a guess and will render the guess as measured power.
            json.put("isEstimated", data.isEstimated);
        json.put("isDischarging", data.isDischarging);
        json.put("timestamp", data.timestamp);
        return json;
    }
    
    private JSONObject getDrivingRangeData() throws Exception {
        at.greenchili.surfmate.monitor.VehicleDataMonitor monitor =
            at.greenchili.surfmate.monitor.VehicleDataMonitor.getInstance();
        at.greenchili.surfmate.monitor.DrivingRangeData data = monitor.getDrivingRange();
        
        if (data == null) throw new Exception("Driving range data not available");
        
        JSONObject json = new JSONObject();
        json.put("elecRangeKm", data.elecRangeKm);
        json.put("fuelRangeKm", data.fuelRangeKm);
        json.put("totalRangeKm", data.totalRangeKm);
        json.put("isLow", data.isLow);
        json.put("isCritical", data.isCritical);
        json.put("status", data.getStatus());
        json.put("isPureEV", data.isPureEV());
        if (data.hasFuelPercent()) {
            json.put("fuelPercent", data.fuelPercent);
        }
        json.put("timestamp", data.timestamp);
        return json;
    }
    
    // ==================== GPS SIDECAR HANDLER ====================
    
    /**
     * Handle GPS update from LocationSidecarService.
     * Directly updates GpsMonitor's cached values - no file needed.
     */
    private void handleGpsUpdate(JSONObject request) {
        try {
            double lat = request.optDouble("lat", 0.0);
            double lng = request.optDouble("lng", 0.0);
            float speed = (float) request.optDouble("speed", 0.0);
            float heading = (float) request.optDouble("heading", 0.0);
            float accuracy = (float) request.optDouble("accuracy", 0.0);
            double altitude = request.optDouble("altitude", 0.0);
            long time = request.optLong("time", System.currentTimeMillis());
            // Monotonic since-boot fix timestamp for geo-tagging staleness — distinct
            // from "time" (send-time). Older sidecars omit it → sentinel 0, which the
            // daemon-side gate treats as "no monotonic basis" and falls back to the
            // send-time age (prior behavior, no regression).
            long fixElapsedMs = request.optLong("fixElapsedMs", 0L);
            // Vertical accuracy + altitude source (elevation pipeline). Older
            // sidecars omit them → 0/false, meaning "unreported / ellipsoidal".
            float verticalAccuracy = (float) request.optDouble("vAcc", 0.0);
            boolean altitudeIsMsl = request.optBoolean("altMsl", false);

            // Directly update GpsMonitor
            at.greenchili.surfmate.monitor.GpsMonitor.getInstance()
                .updateFromIpc(lat, lng, speed, heading, accuracy, time, altitude, fixElapsedMs,
                        verticalAccuracy, altitudeIsMsl);
            
        } catch (Exception e) {
            logger.error("Failed to update GPS", e);
        }
    }

    // ==================== ABRP COMMAND HANDLERS ====================

    private void handleSetAbrpConfig(JSONObject request, JSONObject response) throws Exception {
        if (abrpConfig == null) {
            response.put("success", false);
            response.put("error", Messages.get("errors.abrp_not_initialized"));
            return;
        }

        String token = request.optString("token", null);
        if (token != null && !token.isEmpty()) {
            abrpConfig.setUserToken(token);
        }

        if (request.has("car_model")) {
            abrpConfig.setCarModel(request.optString("car_model", null));
        }
        if (request.has("api_key")) {
            String k = request.optString("api_key", "").trim();
            abrpConfig.setApiKey(k.isEmpty() ? null : k);
        }

        boolean wasEnabled = abrpConfig.isEnabled();
        if (request.has("enabled")) {
            abrpConfig.setEnabled(request.optBoolean("enabled", false));
        }

        // Data-saving + app-gate options (applied live; the service reads config each cycle)
        if (request.has("change_only")) abrpConfig.setChangeOnly(request.optBoolean("change_only", true));
        if (request.has("min_interval_seconds")) abrpConfig.setMinIntervalSeconds(request.optInt("min_interval_seconds", 5));
        if (request.has("max_interval_seconds")) abrpConfig.setMaxIntervalSeconds(request.optInt("max_interval_seconds", 120));
        if (request.has("gate_on_app")) abrpConfig.setGateOnApp(request.optBoolean("gate_on_app", false));
        if (request.has("app_package")) abrpConfig.setAppPackage(request.optString("app_package", "com.iternio.abrpapp"));
        if (request.has("app_active_mode")) abrpConfig.setAppActiveMode(request.optString("app_active_mode", "foreground"));
        if (request.has("app_grace_seconds")) abrpConfig.setAppGraceSeconds(request.optInt("app_grace_seconds", 90));

        abrpConfig.save();

        // Start or stop service if enabled state changed
        boolean nowEnabled = abrpConfig.isEnabled();
        if (abrpService != null && wasEnabled != nowEnabled) {
            if (nowEnabled && abrpConfig.isConfigured()) {
                abrpService.start();
                logger.info("ABRP service started via IPC");
            } else {
                abrpService.stop();
                logger.info("ABRP service stopped via IPC");
            }
        }

        response.put("success", true);
        response.put("message", "ABRP config updated");
    }

    private void handleGetAbrpConfig(JSONObject response) throws Exception {
        if (abrpConfig == null) {
            response.put("success", false);
            response.put("error", Messages.get("errors.abrp_not_initialized"));
            return;
        }

        response.put("success", true);
        response.put("config", abrpConfig.toJson());
    }

    private void handleGetAbrpStatus(JSONObject response) throws Exception {
        if (abrpService == null) {
            // Return basic status when service is not initialized
            JSONObject status = new JSONObject();
            status.put("running", false);
            status.put("totalUploads", 0);
            status.put("failedUploads", 0);
            status.put("lastUploadTime", 0);
            status.put("consecutiveFailures", 0);
            status.put("currentInterval", 0);
            response.put("success", true);
            response.put("status", status);
            return;
        }

        response.put("success", true);
        response.put("status", abrpService.getStatus());
    }

    private void handleDeleteAbrpToken(JSONObject response) throws Exception {
        if (abrpConfig == null) {
            response.put("success", false);
            response.put("error", Messages.get("errors.abrp_not_initialized"));
            return;
        }

        abrpConfig.deleteToken();
        abrpConfig.save();

        if (abrpService != null && abrpService.isRunning()) {
            abrpService.stop();
            logger.info("ABRP service stopped after token deletion");
        }

        response.put("success", true);
        response.put("message", "ABRP token deleted");
    }

    // ==================== MQTT HANDLER METHODS ====================

    private void handleGetMqttConnections(JSONObject response) throws Exception {
        if (mqttManager == null) {
            response.put("success", false);
            response.put("error", Messages.get("errors.mqtt_not_initialized"));
            return;
        }
        response.put("success", true);
        response.put("connections", mqttManager.getAllStatus());
        response.put("maxConnections", at.greenchili.surfmate.mqtt.MqttConnectionStore.MAX_CONNECTIONS);
    }

    private void handleAddMqttConnection(JSONObject request, JSONObject response) throws Exception {
        if (mqttManager == null) {
            response.put("success", false);
            response.put("error", Messages.get("errors.mqtt_not_initialized"));
            return;
        }

        at.greenchili.surfmate.mqtt.MqttConnectionConfig added = mqttManager.addConnection(request);
        if (added != null) {
            response.put("success", true);
            response.put("connection", added.toSafeJson());
            response.put("message", "MQTT connection added");
        } else {
            response.put("success", false);
            response.put("error", Messages.get("errors.max_connections_reached", at.greenchili.surfmate.mqtt.MqttConnectionStore.MAX_CONNECTIONS));
        }
    }

    private void handleUpdateMqttConnection(JSONObject request, JSONObject response) throws Exception {
        if (mqttManager == null) {
            response.put("success", false);
            response.put("error", Messages.get("errors.mqtt_not_initialized"));
            return;
        }

        String id = request.optString("id", null);
        if (id == null || id.isEmpty()) {
            response.put("success", false);
            response.put("error", Messages.get("errors.missing_connection_id"));
            return;
        }

        boolean updated = mqttManager.updateConnection(id, request);
        response.put("success", updated);
        if (updated) {
            response.put("message", "MQTT connection updated");
        } else {
            response.put("error", Messages.get("errors.connection_not_found", id));
        }
    }

    private void handleDeleteMqttConnection(JSONObject request, JSONObject response) throws Exception {
        if (mqttManager == null) {
            response.put("success", false);
            response.put("error", Messages.get("errors.mqtt_not_initialized"));
            return;
        }

        String id = request.optString("id", null);
        if (id == null || id.isEmpty()) {
            response.put("success", false);
            response.put("error", Messages.get("errors.missing_connection_id"));
            return;
        }

        boolean deleted = mqttManager.deleteConnection(id);
        response.put("success", deleted);
        if (deleted) {
            response.put("message", "MQTT connection deleted");
        } else {
            response.put("error", Messages.get("errors.connection_not_found", id));
        }
    }

    private void handleGetMqttStatus(JSONObject response) throws Exception {
        if (mqttManager == null) {
            response.put("success", false);
            response.put("error", Messages.get("errors.mqtt_not_initialized"));
            return;
        }
        response.put("success", true);
        response.put("connections", mqttManager.getAllStatus());
    }

    private void handleGetMqttTelemetry(JSONObject response) throws Exception {
        if (mqttManager == null) {
            response.put("success", false);
            response.put("error", Messages.get("errors.mqtt_not_initialized"));
            return;
        }
        response.put("success", true);
        response.put("telemetry", mqttManager.getLatestTelemetry());
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception e) {
            logger.error("Error stopping IPC server", e);
        }
        // SOTA FIX: Kill all active connections immediately on shutdown
        threadPool.shutdownNow();
    }
}
