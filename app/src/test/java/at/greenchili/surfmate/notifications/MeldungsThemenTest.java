package at.greenchili.surfmate.notifications;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 0.1.28 P4 (Kern A): eine Seite für alle Meldungen – je Thema „Handy“ und „Telegram“. */
public class MeldungsThemenTest {

    private static String registry() throws Exception {
        return Quelltext.lies(Paths.get("src/main/assets/notifications-categories.json"));
    }

    private static JSONObject zeile(JSONArray a, String id) throws Exception {
        for (int i = 0; i < a.length(); i++) if (a.getJSONObject(i).getString("id").equals(id)) return a.getJSONObject(i);
        throw new AssertionError(id);
    }

    /** Jede Push-Kategorie und jeder Telegram-Schlüssel eines Themas gibt es wirklich (Tippfehler = stiller Schalter). */
    @Test
    public void themenZeigenAufVorhandeneSchalter() throws Exception {
        String reg = registry();
        List<MeldungsThemen.Thema> themen = MeldungsThemen.aus(reg);
        assertTrue(themen.size() >= 10);
        Set<String> kats = new HashSet<>();
        JSONArray c = new JSONObject(reg).getJSONArray("categories");
        for (int i = 0; i < c.length(); i++) kats.add(c.getJSONObject(i).getString("id"));
        Map<String, Boolean> tg = MeldungsThemen.telegramStandards(reg);
        for (MeldungsThemen.Thema t : themen) {
            assertTrue(t.id, !t.push.isEmpty() || t.hatTelegram());
            for (String k : t.push) assertTrue(t.id + ": " + k, kats.contains(k));
            for (String k : t.telegram) assertTrue(t.id + ": " + k, tg.containsKey(k));
        }
        MeldungsThemen.Thema e = MeldungsThemen.finde(themen, "ereignis");
        assertNotNull(e);
        assertEquals(Arrays.asList("ereignis.gesichert"), e.push);
        assertEquals(at.greenchili.surfmate.recording.EreignisClip.ABSCHNITT + "."
                + at.greenchili.surfmate.recording.EreignisClip.TELEGRAM, e.telegramEinstellung);
        assertEquals(Arrays.asList("batteryAlerts"), MeldungsThemen.finde(themen, "batterie12v").telegram);
    }

    /**
     * Bilder/Videos an Telegram brauchen den Pflichttext E (Quittung) – die Sammelseite darf das nie einschalten.
     * Ebenso keine Fernbefehle oder Kopplungs-Schlüssel.
     */
    @Test
    public void keinThemaSchaltetMedienOderAnderes() throws Exception {
        for (MeldungsThemen.Thema t : MeldungsThemen.aus(registry())) {
            assertFalse(t.id, t.telegram.contains("videoUploads"));
            for (Map.Entry<String, JSONObject> d : MeldungsThemen.telegramDelta(t, true).entrySet()) {
                assertTrue(t.id, d.getKey().equals("telegram") || d.getKey().equals("recording"));
                java.util.Iterator<String> it = d.getValue().keys();
                while (it.hasNext()) {
                    String k = it.next();
                    assertTrue(t.id + ": " + k, t.telegram.contains(k) || t.telegramEinstellung.endsWith("." + k));
                }
            }
        }
    }

    @Test
    public void sichtHandyAlleTeilweiseKeinGeraet() throws Exception {
        String reg = registry();
        List<MeldungsThemen.Thema> themen = MeldungsThemen.aus(reg);
        Map<String, Boolean> std = MeldungsThemen.telegramStandards(reg);
        // ohne Gerät: handy = null
        JSONArray a = MeldungsThemen.sicht(themen, null, std, Collections.<Set<String>>emptyList(), false);
        assertTrue(zeile(a, "waechter").isNull("handy"));
        assertTrue(zeile(a, "waechter").isNull("telegram"));   // Telegram nicht verbunden
        // zwei Geräte, eines hat „Wächter – Warnung“ stumm
        List<Set<String>> geraete = new ArrayList<>();
        geraete.add(new HashSet<String>());
        geraete.add(new HashSet<>(Collections.singletonList("surveillance.motion.alert")));
        a = MeldungsThemen.sicht(themen, null, std, geraete, true);
        JSONObject w = zeile(a, "waechter");
        assertFalse(w.getBoolean("handy"));
        assertTrue(w.getBoolean("handyTeilweise"));
        assertTrue(zeile(a, "ereignis").getBoolean("handy"));
        // Präfix-Stummschaltung wie PushSubscription
        geraete.set(1, new HashSet<>(Collections.singletonList("vehicle.charging.*")));
        a = MeldungsThemen.sicht(themen, null, std, geraete, true);
        assertTrue(zeile(a, "laden").getBoolean("handyTeilweise"));
        // Thema ohne Telegram / ohne Push
        assertFalse(zeile(a, "tueren").getBoolean("telegramMoeglich"));
        assertTrue(zeile(a, "tueren").isNull("telegram"));
        assertFalse(zeile(a, "updates").getBoolean("handyMoeglich"));
    }

    @Test
    public void sichtTelegramStandardsUndEinstellung() throws Exception {
        String reg = registry();
        List<MeldungsThemen.Thema> themen = MeldungsThemen.aus(reg);
        Map<String, Boolean> std = MeldungsThemen.telegramStandards(reg);
        List<Set<String>> keine = Collections.emptyList();
        // leere Konfiguration: Standards der Registry; Ereignis-Telegram Standard aus
        JSONArray a = MeldungsThemen.sicht(themen, new JSONObject(), std, keine, true);
        assertTrue(zeile(a, "waechter").getBoolean("telegram"));
        assertTrue(zeile(a, "batterie12v").getBoolean("telegram"));
        assertFalse(zeile(a, "ereignis").getBoolean("telegram"));
        // gesetzt: Ereignis an, eine von zwei Wächter-Kategorien aus
        JSONObject root = new JSONObject()
                .put("recording", new JSONObject().put("ereignisTelegram", true))
                .put("telegram", new JSONObject().put("tierAlerts", false));
        a = MeldungsThemen.sicht(themen, root, std, keine, true);
        assertTrue(zeile(a, "ereignis").getBoolean("telegram"));
        assertFalse(zeile(a, "waechter").getBoolean("telegram"));
        assertTrue(zeile(a, "waechter").getBoolean("telegramTeilweise"));
    }

    @Test
    public void handySetzenSchaltetNurDasThema() throws Exception {
        MeldungsThemen.Thema laden = MeldungsThemen.finde(MeldungsThemen.aus(registry()), "laden");
        Set<String> stumm = new HashSet<>(Arrays.asList("vehicle.security.door.opened"));
        assertTrue(MeldungsThemen.handySetzen(laden, stumm, false));
        assertEquals(new HashSet<>(Arrays.asList("vehicle.security.door.opened", "vehicle.charging.full",
                "vehicle.charging.fault")), stumm);
        assertFalse(MeldungsThemen.handySetzen(laden, stumm, false));   // schon aus
        assertTrue(MeldungsThemen.handySetzen(laden, stumm, true));
        assertEquals(new HashSet<>(Arrays.asList("vehicle.security.door.opened")), stumm);
        // Präfix-Stummschalter wird beim Einschalten aufgelöst
        Set<String> p = new HashSet<>(Arrays.asList("vehicle.charging.*"));
        assertTrue(MeldungsThemen.handySetzen(laden, p, true));
        assertTrue(p.isEmpty());
    }

    @Test
    public void telegramDeltaTrenntAbschnitte() throws Exception {
        List<MeldungsThemen.Thema> themen = MeldungsThemen.aus(registry());
        Map<String, JSONObject> e = MeldungsThemen.telegramDelta(MeldungsThemen.finde(themen, "ereignis"), true);
        assertEquals(1, e.size());
        assertTrue(e.get("recording").getBoolean("ereignisTelegram"));
        Map<String, JSONObject> w = MeldungsThemen.telegramDelta(MeldungsThemen.finde(themen, "waechter_pause"), false);
        assertFalse(w.get("telegram").getBoolean("guardStopAlerts"));
        assertFalse(w.get("telegram").getBoolean("healthAlerts"));
        assertEquals(2, w.get("telegram").length());
    }

    @Test
    public void ungueltigeThemenFallenWeg() {
        String reg = "{\"themen\":[{\"id\":\"a\",\"push\":[\"x\"]},{\"id\":\"a\"},{\"id\":\"\"},"
                + "{\"id\":\"b\",\"telegramEinstellung\":\"ohnepunkt\"}]}";
        List<MeldungsThemen.Thema> l = MeldungsThemen.aus(reg);
        assertEquals(1, l.size());
        assertEquals("a", l.get(0).id);
        assertTrue(MeldungsThemen.aus("kaputt").isEmpty());
    }

    @Test
    public void minuten() {
        assertEquals(22 * 60, MeldungsThemen.minuten("22:00"));
        assertEquals(7 * 60 + 5, MeldungsThemen.minuten("7:05"));
        assertEquals(-1, MeldungsThemen.minuten("24:00"));
        assertEquals(-1, MeldungsThemen.minuten("x"));
        assertEquals(-1, MeldungsThemen.minuten(null));
    }

    // ---------------------------------------------------------------- Seite, Route, Texte

    @Test
    public void seiteHatRouteUndLinks() throws Exception {
        String server = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/HttpServer.java"));
        assertTrue(server.contains("path.equals(\"/meldungen.html\")") && server.contains("\"local/meldungen.html\""));
        // /api/notifications/themen läuft über die bestehende Weiterleitung /api/notifications*
        assertTrue(server.contains("path.startsWith(\"/api/notifications\")"));
        String h = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/NotificationApiHandler.java"));
        assertTrue(h.contains("logPath.equals(\"/api/notifications/themen\")"));
        String seite = Quelltext.lies(Paths.get("src/main/assets/web/local/meldungen.html"));
        assertTrue(seite.contains("/api/notifications/themen"));
        for (String p : new String[] {"notifications.html", "telegram.html"}) {
            assertTrue(p, Quelltext.lies(Paths.get("src/main/assets/web/local/" + p)).contains("href=\"meldungen.html\""));
        }
    }

    @Test
    public void themenTexteInAllenSprachen() throws Exception {
        List<MeldungsThemen.Thema> themen = MeldungsThemen.aus(registry());
        String[] seite = {"title", "lead", "handy", "telegram", "teilweise", "kein_handy", "handy_verbinden",
                "kein_telegram", "telegram_verbinden", "ruhe_title", "ruhe_text", "ruhe_an", "von", "bis",
                "gespeichert", "fehler", "laden", "genauer"};
        for (String sp : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject m = new JSONObject(Quelltext.lies(Paths.get("src/main/assets/web/i18n/" + sp + ".json")))
                    .getJSONObject("meldungen");
            for (String k : seite) assertFalse(sp + ": " + k, m.getString(k).trim().isEmpty());
            for (MeldungsThemen.Thema t : themen) {
                JSONObject o = m.getJSONObject("thema").getJSONObject(t.id);
                assertFalse(sp + ": " + t.id, o.getString("label").isEmpty());
                assertFalse(sp + ": " + t.id, o.getString("note").isEmpty());
            }
        }
    }
}
