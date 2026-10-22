package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/**
 * Koordination 0.1.9 (#8, SPRACHEN_LUECKEN_018_code.md): Kern-Texte, die bisher fest englisch waren, kommen aus
 * den Katalogen – in allen fünf Sprachen.
 */
public class KernSprachLueckenTest {

    static final List<String> SPRACHEN = Arrays.asList("de", "en", "fr", "it", "nl");

    static JSONObject katalog(String sprache) throws Exception {
        return new JSONObject(Quelltext.lies(Paths.get("src/main/assets/server-i18n/" + sprache + ".json")));
    }

    static String wert(JSONObject cat, String key) {
        Object cur = cat;
        for (String p : key.split("\\.")) {
            if (!(cur instanceof JSONObject)) return null;
            cur = ((JSONObject) cur).opt(p);
        }
        return cur instanceof String ? (String) cur : null;
    }

    static final String[] SCHLUESSEL = {
            "errors.remote.voice_disabled", "errors.remote.voice_disabled_hint", "errors.remote.busy",
            "errors.remote.busy_hint", "errors.remote.messages_disabled", "errors.remote.messages_disabled_hint",
            "errors.remote.listener_disabled", "errors.remote.listener_disabled_hint", "errors.remote.listener_busy",
            "errors.remote.listener_busy_hint", "errors.remote.car_off", "errors.remote.car_off_hint",
            "errors.remote.emergency_disabled", "errors.remote.emergency_disabled_hint",
            "errors.remote.overlay_permission", "errors.remote.overlay_permission_hint",
            "notifications.hotspot_limit_title", "notifications.hotspot_limit_body",
            "notifications.test_title", "notifications.test_body",
            "parking.notify.energy_charged_soc", "parking.notify.energy_used_soc", "parking.notify.energy_charged",
            "parking.notify.energy_used", "parking.notify.soc_suffix",
            "genai.routine.routine_learning_disabled", "genai.routine.invalid_decision",
            "genai.routine.suggestion_not_found", "genai.routine.suggestion_unavailable",
            "genai.routine.decision_failed", "genai.routine.routine_persist_failed",
            "genai.routine.invalid_automation", "genai.routine.automation_persist_failed", "genai.routine.reset_done",
            "genai.automation_run_accepted", "genai.automation_created_manual",
            "genai.insight_title.vehicle_state", "genai.insight_title.latest_trip",
            "genai.insight_title.trip_consumption", "genai.insight_title.recent_events",
            "genai.insight_title.roadsense", "genai.insight_title.charging", "genai.insight_title.diagnostics",
            "genai.insight_title.vehicle_brief", "genai.insight_title.fallback",
            "errors.vehicle_data_unavailable",
    };

    @Test
    public void neueSchluesselInAllenSprachen() throws Exception {
        for (String s : SPRACHEN) {
            JSONObject cat = katalog(s);
            for (String k : SCHLUESSEL) {
                String v = wert(cat, k);
                assertNotNull(s + ": " + k, v);
                assertFalse(s + ": " + k, v.trim().isEmpty());
            }
        }
        // Platzhalter bleiben erhalten
        for (String s : SPRACHEN) {
            JSONObject cat = katalog(s);
            assertTrue(s, wert(cat, "notifications.hotspot_limit_body").contains("{0}"));
            assertTrue(s, wert(cat, "parking.notify.energy_used").contains("{0}")
                    && wert(cat, "parking.notify.energy_used").contains("{1}"));
            assertTrue(s, wert(cat, "parking.notify.soc_suffix").contains("{1}"));
        }
    }

    /** Jede Kategorie der Registry hat Titel und Hinweis in allen Sprachen. */
    @Test
    public void registryVollstaendigUebersetzt() throws Exception {
        JSONArray cats = new JSONObject(Quelltext.lies(Paths.get("src/main/assets/notifications-categories.json")))
                .getJSONArray("categories");
        for (String s : SPRACHEN) {
            JSONObject cat = katalog(s);
            for (int i = 0; i < cats.length(); i++) {
                JSONObject c = cats.getJSONObject(i);
                String k = "notifications.registry." + c.getString("id").replace('.', '_');
                assertNotNull(s + ": " + k, wert(cat, k + ".label"));
                if (c.has("note")) assertNotNull(s + ": " + k, wert(cat, k + ".note"));
            }
        }
    }

    /** Übersetzen ändert nie id oder group (das Web nutzt group als Schlüssel). */
    @Test
    public void uebersetzenLaesstGroupUndId() throws Exception {
        JSONArray a = new JSONArray().put(new JSONObject().put("id", "vehicle.charging.full")
                .put("group", "Charging").put("label", "Charging complete").put("note", "x"));
        NotificationApiHandler.uebersetzeKategorien(a);
        assertEquals("vehicle.charging.full", a.getJSONObject(0).getString("id"));
        assertEquals("Charging", a.getJSONObject(0).getString("group"));
        assertFalse(a.getJSONObject(0).getString("label").isEmpty());
    }

    @Test
    public void textFaelltAufEnglischZurueck() {
        assertEquals("Hello 5", Messages.text("gibt.es.nicht", "Hello {0}", 5));
        assertEquals("plain", Messages.text("gibt.es.nicht", "plain"));
    }

    /** Die gemeldeten Stellen enthalten den englischen Text nur noch als Rückfall, nicht als festen Text. */
    @Test
    public void keineFestenEnglischenTexteMehr() throws Exception {
        String[][] faelle = {
                {"overlay/MessageOverlayService.java", "setContentTitle(\"On-screen message\")"},
                {"roadsense/overlay/RoadSenseOverlayService.kt", "setContentText(\"Hazard overlay active\")"},
                {"roadsense/sidecar/RoadSenseImuSidecarService.kt", "setContentText(\"Road sensing active\")"},
                {"services/HotspotBridgeService.kt", "setContentTitle(\"Network & Hotspot\")"},
                {"services/LocationSidecarService.java", ".setContentTitle(\"Location Active\")"},
                {"services/MediaPlaybackService.java", "setContentTitle(\"Playing audio\")"},
                {"services/RoadSenseChimePlaybackService.java", ".setContentTitle(\"RoadSense warning\")"},
                {"services/VehicleActuatorService.java", "setContentTitle(\"Vehicle control\")"},
                {"services/RemoteVoiceService.java", "\"Remote voice\", NotificationManager"},
                {"overlay/StatusOverlayService.java", "\"Status Overlay\", NotificationManager"},
                {"server/VehicleControlApiHandler.java", "Messages.get(\"vehicle_data_unavailable\")"},
        };
        for (String[] f : faelle) {
            String q = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/" + f[0]));
            assertFalse(f[0], q.contains(f[1]));
        }
        String res = Quelltext.lies(Paths.get("src/main/res/values-fr/strings.xml"));
        for (String n : new String[] {"notif_onscreen_title", "notif_location_channel_desc", "notif_status_channel"}) {
            assertTrue(n, res.contains("name=\"" + n + "\""));
        }
    }
}
