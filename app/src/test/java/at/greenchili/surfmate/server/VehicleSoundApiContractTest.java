package at.greenchili.surfmate.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** Static production-boundary checks; no vehicle binder is touched on the JVM. */
public class VehicleSoundApiContractTest {

    @Test
    public void onlyNarrowSoundRoutesAreProductRouted() throws Exception {
        String server = WebHandyGuardTest.quelle(
                "java/at/greenchili/surfmate/server/HttpServer.java");
        String handler = WebHandyGuardTest.quelle(
                "java/at/greenchili/surfmate/server/VehicleSoundApiHandler.java");
        String audio = WebHandyGuardTest.quelle(
                "java/at/greenchili/surfmate/server/AudioApiHandler.java");

        assertTrue(server.contains("return VehicleSoundApiHandler.handle(method, path, body, out);"));
        assertFalse("diagnostic handler must never be wired",
                server.contains("AudioTestApiHandler.handle"));
        int authentication = server.indexOf("if (!authenticated) {");
        int externalGuard = server.indexOf("if (AuthMiddleware.isExternRequest() && ExternGuard.blocks(");
        int routing = server.indexOf("if (routeToHandlers(method, path, body, rangeHeader, ifNoneMatchHeader, out)) {");
        assertTrue("authentication and external allowlist must precede routing",
                authentication >= 0 && externalGuard > authentication && routing > externalGuard);

        int handleStart = audio.indexOf("public static boolean handle(");
        int libraryStart = audio.indexOf("// ==================== AUDIO LIBRARY", handleStart);
        String dispatch = audio.substring(handleStart, libraryStart);
        assertFalse(dispatch.contains("test-avas"));
        assertFalse(dispatch.contains("probe-mic"));
        assertFalse(dispatch.contains("avas-state"));
        assertFalse(dispatch.contains("avas-tone"));
        assertFalse(dispatch.contains("engine-sound"));

        assertTrue(handler.contains("\"GET\".equals(method)"));
        assertTrue(handler.contains("\"POST\".equals(method)"));
        assertTrue(handler.contains("\"/api/audio/engine-sound\".equals(cleanPath)"));
        assertTrue(handler.contains("\"/api/audio/avas-tone\".equals(cleanPath)"));
        assertFalse(handler.contains("probe-mic"));
        assertFalse(handler.contains("test-avas"));
        assertFalse(handler.contains("avas-state"));
    }

    @Test
    public void actuationIsCapabilityAndParkGuardedFailClosed() throws Exception {
        String handler = WebHandyGuardTest.quelle(
                "java/at/greenchili/surfmate/server/VehicleSoundApiHandler.java");
        assertTrue(handler.contains("VehicleCommunicationSafety.isParked()"));
        assertTrue(handler.contains("AvasController.isAvailable()"));
        assertTrue(handler.contains("AvasController.isEngineSoundSupported()"));
        assertTrue(handler.contains("sendFailure(out, 403, \"park_required\")"));
        assertTrue(handler.contains("sendFailure(out, 400, \"invalid_request\")"));
        assertTrue(handler.contains("Boolean.FALSE.equals(on)"));
        assertTrue(handler.contains("Boolean.TRUE.equals(stop)"));
        assertTrue("fail-safe off must run before the Park gate",
                handler.indexOf("Boolean.FALSE.equals(on)")
                        < handler.lastIndexOf("VehicleCommunicationSafety.isParked()"));
        assertTrue("fail-safe stop must run before the Park gate",
                handler.indexOf("Boolean.TRUE.equals(stop)")
                        < handler.indexOf("VehicleCommunicationSafety.isParked()"));
        assertFalse("binder exception details must not cross the API",
                handler.contains("put(\"error\", error.getMessage())"));
    }

    @Test
    public void externalPairingCannotOperateExteriorSpeaker() {
        assertTrue(ExternGuard.blocks("GET", "/api/audio/engine-sound"));
        assertTrue(ExternGuard.blocks("POST", "/api/audio/engine-sound"));
        assertTrue(ExternGuard.blocks("POST", "/api/audio/avas-tone"));
    }

    @Test
    public void allWebLanguagesContainSoundSafetyErrors() throws Exception {
        for (String language : new String[] { "de", "en", "fr", "it", "nl" }) {
            JSONObject vehicle = new JSONObject(WebHandyGuardTest.quelle(
                    "assets/web/i18n/" + language + ".json")).getJSONObject("vehicle");
            assertFalse(language, vehicle.getString("avas_park_required").trim().isEmpty());
            assertFalse(language, vehicle.getString("avas_invalid_request").trim().isEmpty());
        }
        String ui = WebHandyGuardTest.quelle("assets/web/shared/vehicle-control.js");
        assertTrue(ui.contains("park_required: 'vehicle.avas_park_required'"));
        assertTrue(ui.contains("invalid_request: 'vehicle.avas_invalid_request'"));
    }
}
