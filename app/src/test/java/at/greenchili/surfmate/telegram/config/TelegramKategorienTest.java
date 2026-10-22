package at.greenchili.surfmate.telegram.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/** 0.1.11 (Kern 7): Telegram nur Kategorien – Registry-Abschnitt, Sicht, Schreiben, verlustfreie Abbildung. */
public class TelegramKategorienTest {

    private static String asset(String rel) throws Exception {
        File f = new File("src/main/assets/" + rel);
        if (!f.isFile()) f = new File("app/src/main/assets/" + rel);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void registryHatAlleTelegramSchalterGenauEinmal() throws Exception {
        List<TelegramKategorien.Kat> k = TelegramKategorien.aus(asset("notifications-categories.json"));
        assertEquals(13, k.size());
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (TelegramKategorien.Kat x : k) assertTrue("doppelter Schlüssel " + x.key, keys.add(x.key));
        for (String s : new String[] {UnifiedTelegramConfig.K_TIER_CRITICAL, UnifiedTelegramConfig.K_TIER_ALERTS,
                UnifiedTelegramConfig.K_TIER_NOTICES, UnifiedTelegramConfig.K_VIDEO_UPLOADS,
                UnifiedTelegramConfig.K_CRITICAL_ALERTS, UnifiedTelegramConfig.K_TYRE_ALERTS,
                UnifiedTelegramConfig.K_PARKING_MESSAGES, UnifiedTelegramConfig.K_CONNECTIVITY,
                UnifiedTelegramConfig.K_CHARGING_ALERTS, UnifiedTelegramConfig.K_BATTERY_ALERTS,
                UnifiedTelegramConfig.K_GUARD_STOP_ALERTS, UnifiedTelegramConfig.K_HEALTH_ALERTS,
                UnifiedTelegramConfig.K_UPDATE_ALERTS}) {
            assertTrue("fehlt: " + s, keys.contains(s));
        }
    }

    @Test
    public void standardsWieDieGetterUndKritischAn() throws Exception {
        JSONArray s = TelegramKategorien.sicht(TelegramKategorien.aus(asset("notifications-categories.json")),
                new JSONObject(), null);
        Map<String, Boolean> erwartet = new java.util.HashMap<>();
        erwartet.put("waechter.kritisch", true);
        erwartet.put("waechter.alarm", true);
        erwartet.put("waechter.hinweis", false);
        erwartet.put("waechter.medien", false);
        erwartet.put("system.verbindung", false);
        erwartet.put("fahrzeug.kritisch", true);
        for (int i = 0; i < s.length(); i++) {
            JSONObject o = s.getJSONObject(i);
            if (erwartet.containsKey(o.getString("id"))) {
                assertEquals(o.getString("id"), erwartet.get(o.getString("id")), o.getBoolean("an"));
            }
        }
        // Sortierung: Wächter zuerst, darin kritisch vor Alarm vor Hinweis
        assertEquals("waechter.kritisch", s.getJSONObject(0).getString("id"));
        assertEquals("waechter.alarm", s.getJSONObject(1).getString("id"));
        assertEquals("telegramMedien", find(s, "waechter.medien").getString("bestaetigung"));
    }

    @Test
    public void bestandswerteBleibenSichtbar() throws Exception {
        JSONObject tg = new JSONObject().put("tierCritical", false).put("tierNotices", true).put("connectivity", true);
        JSONArray s = TelegramKategorien.sicht(TelegramKategorien.aus(asset("notifications-categories.json")), tg, null);
        assertFalse(find(s, "waechter.kritisch").getBoolean("an"));
        assertTrue(find(s, "waechter.hinweis").getBoolean("an"));
        assertTrue(find(s, "system.verbindung").getBoolean("an"));
    }

    @Test
    public void schreibenPrueftAllesVorher() throws Exception {
        List<TelegramKategorien.Kat> k = TelegramKategorien.aus(asset("notifications-categories.json"));
        Map<String, Boolean> m = TelegramKategorien.schluessel(k,
                new JSONObject().put("waechter.hinweis", true).put("laden.status", false));
        assertEquals(Boolean.TRUE, m.get("tierNotices"));
        assertEquals(Boolean.FALSE, m.get("chargingAlerts"));
        try {
            TelegramKategorien.schluessel(k, new JSONObject().put("gibtsnicht", true));
            fail();
        } catch (IllegalArgumentException ok) {
        }
        try {
            TelegramKategorien.schluessel(k, new JSONObject().put("waechter.alarm", "ja"));
            fail();
        } catch (IllegalArgumentException ok) {
        }
    }

    @Test
    public void altesMotionTextAusSchaltetDieDreiWaechterKategorienAus() throws Exception {
        JSONObject d = TelegramKategorien.motionTextAbbilden(new JSONObject().put("motionText", false).put("tierCritical", true));
        assertFalse(d.getBoolean("tierCritical"));
        assertFalse(d.getBoolean("tierAlerts"));
        assertFalse(d.getBoolean("tierNotices"));
        assertTrue(d.getBoolean("motionText"));
        assertTrue(d.getBoolean(UnifiedTelegramConfig.K_MOTION_TEXT_ABGEBILDET));
        // motionText an oder nie gesetzt: nur Merker, keine Kategorie ändert sich
        JSONObject an = TelegramKategorien.motionTextAbbilden(new JSONObject().put("motionText", true));
        assertEquals(1, an.length());
        JSONObject nie = TelegramKategorien.motionTextAbbilden(new JSONObject());
        assertEquals(1, nie.length());
        // schon abgebildet: nichts
        assertEquals(0, TelegramKategorien.motionTextAbbilden(new JSONObject().put("motionText", false)
                .put(UnifiedTelegramConfig.K_MOTION_TEXT_ABGEBILDET, true)).length());
    }

    @Test
    public void textJederKategorieInFuenfSprachen() throws Exception {
        List<TelegramKategorien.Kat> k = TelegramKategorien.aus(asset("notifications-categories.json"));
        for (String spr : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject d = new JSONObject(asset("server-i18n/" + spr + ".json")).getJSONObject("telegram")
                    .getJSONObject("kategorie");
            for (TelegramKategorien.Kat x : k) {
                JSONObject t = d.getJSONObject(x.id.replace('.', '_'));
                assertFalse(spr + " " + x.id, t.getString("label").isEmpty());
                assertFalse(spr + " " + x.id, t.getString("note").isEmpty());
            }
        }
    }

    private static JSONObject find(JSONArray a, String id) throws Exception {
        for (int i = 0; i < a.length(); i++) if (id.equals(a.getJSONObject(i).getString("id"))) return a.getJSONObject(i);
        throw new AssertionError(id);
    }
}
