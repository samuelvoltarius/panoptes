package at.greenchili.surfmate.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

/** Koordination 21.10. (0.1.9): Cockpit liest Wächter-Aufnahmen per Kopplungs-Token – nur lesend. */
public class ExternGuardAufnahmenTest {

    private static final String ID = "0123456789abcdef0123456789abcdef";

    @Test
    public void listeTageZahlenClipUndVorschauLesbar() {
        assertTrue(ExternGuard.erlaubt("GET", "/api/recordings"));
        assertTrue(ExternGuard.erlaubt("GET", "/api/recordings?page=1&pageSize=30"));
        assertTrue(ExternGuard.erlaubt("GET", "/api/recordings/dates"));
        assertTrue(ExternGuard.erlaubt("GET", "/api/recordings/stats"));
        assertTrue(ExternGuard.erlaubt("GET", "/video/id/" + ID));
        assertTrue(ExternGuard.erlaubt("HEAD", "/video/id/" + ID));
        assertTrue(ExternGuard.erlaubt("GET", "/thumb/id/" + ID));
    }

    @Test
    public void nichtsSchreibenKeinStreamKeinCamview() {
        assertFalse(ExternGuard.erlaubt("DELETE", "/api/recordings/id/" + ID));
        assertFalse(ExternGuard.erlaubt("POST", "/api/recordings/batch-delete"));
        assertFalse(ExternGuard.erlaubt("GET", "/api/recordings/inflight/x.mp4"));
        assertFalse(ExternGuard.erlaubt("GET", "/api/recordings/places"));
        assertFalse(ExternGuard.erlaubt("GET", "/video/legacy_name.mp4"));
        assertFalse(ExternGuard.erlaubt("GET", "/video/id/" + ID + "/../x"));
        assertFalse(ExternGuard.erlaubt("GET", "/video/id/ZZZ"));
        assertFalse(ExternGuard.erlaubt("GET", "/thumb/clip.jpg"));
        assertFalse(ExternGuard.erlaubt("POST", "/api/events/keep"));
        for (String p : new String[] {"/api/stream", "/api/stream/start", "/api/camview", "/api/camview/1",
                "/h264/1", "/snapshot/1", "/ws/stream"}) {
            assertFalse(p, ExternGuard.erlaubt("GET", p));
            assertFalse(p, ExternGuard.erlaubt("POST", p));
        }
    }

    @Test
    public void ohnePfadeUndRohkoordinaten() throws Exception {
        JSONObject r = new JSONObject().put("id", ID).put("path", "/storage/x/clip.mp4").put("startLat", 48.3)
                .put("startLng", 14.235).put("deleteUrl", "/api/recordings/id/" + ID).put("videoUrl", "/video/id/" + ID)
                .put("volumeId", "usb").put("timestamp", 1L);
        List<JSONObject> k = RecordingsApiHandler.fuerExtern(Collections.singletonList(r));
        JSONObject o = k.get(0);
        assertTrue(o.has("videoUrl") && o.has("timestamp") && o.has("id"));
        for (String f : RecordingsApiHandler.NICHT_EXTERN) assertFalse(f, o.has(f));
        assertTrue("Original unverändert", r.has("path"));
    }
}
