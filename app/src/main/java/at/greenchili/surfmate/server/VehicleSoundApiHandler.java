package at.greenchili.surfmate.server;

import at.greenchili.surfmate.byd.AvasController;
import at.greenchili.surfmate.communication.VehicleCommunicationSafety;
import at.greenchili.surfmate.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.OutputStream;

/**
 * Narrow production boundary for controls which use the vehicle's exterior speaker.
 *
 * <p>Authentication, the external-device allowlist and the handy-web guard run in
 * {@link HttpServer} before this handler is reached. Only the three exact routes below
 * are accepted here. Diagnostic AVAS, microphone, spoofing and introspection endpoints
 * deliberately remain outside production routing in {@link AudioTestApiHandler}.</p>
 *
 * <p>Starting a tone/simulator or changing its preset requires a positive, current
 * Park signal. Stopping a tone and switching the simulator off remain available as
 * fail-safe deactivation actions.</p>
 */
public final class VehicleSoundApiHandler {

    private static final DaemonLogger LOGGER =
            DaemonLogger.getInstance("VehicleSoundApi");
    private VehicleSoundApiHandler() {}

    public static boolean handle(String method, String path, String body, OutputStream out)
            throws Exception {
        String cleanPath = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if ("/api/audio/engine-sound".equals(cleanPath)) {
            if ("GET".equals(method)) {
                sendEngineSoundState(out);
                return true;
            }
            if ("POST".equals(method)) {
                setEngineSound(body, out);
                return true;
            }
        }
        if ("POST".equals(method) && "/api/audio/avas-tone".equals(cleanPath)) {
            setAvasTone(body, out);
            return true;
        }
        return false;
    }

    private static void setAvasTone(String body, OutputStream out) throws Exception {
        JSONObject req = parseObject(body, out);
        if (req == null) return;

        Boolean stop = optionalBoolean(req, "stop");
        Integer pattern = optionalInteger(req, "pattern");
        if ((req.has("stop") && stop == null) || (req.has("pattern") && pattern == null)
                || (stop == null && pattern == null)
                || (Boolean.TRUE.equals(stop) && pattern != null)) {
            sendFailure(out, 400, "invalid_request");
            return;
        }

        // Deactivation is intentionally possible without a Park reading.
        if (Boolean.TRUE.equals(stop)) {
            AvasController.stop();
            sendSuccess(out, new JSONObject().put("action", "stopped"));
            return;
        }
        if (pattern == null || pattern < 0 || pattern >= AvasController.PATTERN_COUNT) {
            sendFailure(out, 400, "invalid_request");
            return;
        }
        if (!VehicleCommunicationSafety.isParked()) {
            sendFailure(out, 403, "park_required");
            return;
        }
        if (!AvasController.isAvailable()) {
            sendFailure(out, 503, "unavailable");
            return;
        }

        boolean ok = AvasController.playPattern(pattern);
        if (!ok) {
            sendFailure(out, 409, "command_failed");
            return;
        }
        LOGGER.info("AVAS pattern started: " + AvasController.patternName(pattern));
        sendSuccess(out, new JSONObject()
                .put("action", "playing")
                .put("pattern", pattern)
                .put("patternName", AvasController.patternName(pattern)));
    }

    private static void sendEngineSoundState(OutputStream out) throws Exception {
        try {
            boolean available = AvasController.isAvailable();
            JSONObject response = new JSONObject()
                    .put("success", true)
                    .put("avasAvailable", available)
                    .put("supported", available && AvasController.isEngineSoundSupported());
            if (available) {
                response.put("on", AvasController.isEngineSoundOn());
                response.put("preset", AvasController.getEngineSoundPreset());
            }
            HttpResponse.sendJsonNoCors(out, response.toString());
        } catch (Throwable error) {
            LOGGER.warn("Engine-sound state failed: " + error.getClass().getSimpleName());
            sendFailure(out, 503, "unavailable");
        }
    }

    private static void setEngineSound(String body, OutputStream out) throws Exception {
        JSONObject req = parseObject(body, out);
        if (req == null) return;

        Boolean on = optionalBoolean(req, "on");
        Integer preset = optionalInteger(req, "preset");
        if ((req.has("on") && on == null) || (req.has("preset") && preset == null)
                || (on == null && preset == null)) {
            sendFailure(out, 400, "invalid_request");
            return;
        }

        // Turning an active sound off is a fail-safe action and must not be blocked
        // merely because gear telemetry is unavailable.
        if (Boolean.FALSE.equals(on)) {
            if (!AvasController.isAvailable()) {
                sendFailure(out, 503, "unavailable");
                return;
            }
            boolean ok = AvasController.setEngineSound(false, -1);
            if (!ok) {
                sendFailure(out, 409, "command_failed");
                return;
            }
            sendSuccess(out, new JSONObject().put("on", false));
            return;
        }

        // The supported preset range is MCU/vehicle-specific. Reject non-positive
        // values, then rely on the capability probe and read-back clamping instead
        // of hard-coding one model's upper bound.
        if (preset != null && preset < 1) {
            sendFailure(out, 400, "invalid_request");
            return;
        }

        if (!VehicleCommunicationSafety.isParked()) {
            sendFailure(out, 403, "park_required");
            return;
        }
        if (!AvasController.isAvailable()) {
            sendFailure(out, 503, "unavailable");
            return;
        }
        if (!AvasController.isEngineSoundSupported()) {
            sendFailure(out, 409, "unsupported");
            return;
        }

        if (Boolean.TRUE.equals(on)) {
            boolean ok = AvasController.setEngineSound(true, preset == null ? -1 : preset);
            if (!ok) {
                sendFailure(out, 409, "command_failed");
                return;
            }
            sendSuccess(out, new JSONObject().put("on", true)
                    .put("preset", preset == null ? AvasController.getEngineSoundPreset() : preset));
            return;
        }

        int applied = AvasController.selectEngineSoundPreset(preset);
        if (applied < 1) {
            sendFailure(out, 409, "command_failed");
            return;
        }
        sendSuccess(out, new JSONObject().put("preset", applied));
    }

    private static JSONObject parseObject(String body, OutputStream out) throws Exception {
        if (body == null || body.trim().isEmpty()) {
            sendFailure(out, 400, "invalid_request");
            return null;
        }
        try {
            return new JSONObject(body);
        } catch (Exception ignored) {
            sendFailure(out, 400, "invalid_request");
            return null;
        }
    }

    private static Boolean optionalBoolean(JSONObject object, String key) {
        if (!object.has(key) || object.isNull(key)) return null;
        Object value = object.opt(key);
        return value instanceof Boolean ? (Boolean) value : null;
    }

    private static Integer optionalInteger(JSONObject object, String key) {
        if (!object.has(key) || object.isNull(key)) return null;
        Object value = object.opt(key);
        if (!(value instanceof Number)) return null;
        double number = ((Number) value).doubleValue();
        if (Double.isNaN(number) || Double.isInfinite(number) || number != Math.rint(number)
                || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) return null;
        return (int) number;
    }

    private static void sendSuccess(OutputStream out, JSONObject fields) throws Exception {
        fields.put("success", true);
        HttpResponse.sendJsonNoCors(out, fields.toString());
    }

    private static void sendFailure(OutputStream out, int status, String reason) throws Exception {
        HttpResponse.sendJsonNoCors(out, status, new JSONObject()
                .put("success", false)
                .put("reason", reason)
                .toString());
    }
}
