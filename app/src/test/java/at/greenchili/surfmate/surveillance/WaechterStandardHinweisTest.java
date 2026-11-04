package at.greenchili.surfmate.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.einstellungen.Katalog;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

/**
 * 0.1.38.8 Wächter Standard an, Phase 3: der Bestand wird beim ersten Start auf der neuen Version EINMAL gefragt (nie still
 * eingeschaltet). Done-Marker je versionCode; bewusst aus oder schon eingerichtet bleibt unangetastet.
 */
public class WaechterStandardHinweisTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static JSONObject bestand(JSONObject surveillance) throws Exception {
        JSONObject root = new JSONObject().put("einrichtung",
                new JSONObject().put("art", "bestand").put("schritte", new JSONObject().put("fertig", "erledigt")));
        if (surveillance != null) root.put("surveillance", surveillance);
        return root;
    }

    private static JSONObject neu(String fertig, JSONObject surveillance) throws Exception {
        JSONObject root = new JSONObject().put("einrichtung",
                new JSONObject().put("art", "neu").put("schritte", new JSONObject().put("fertig", fertig)));
        if (surveillance != null) root.put("surveillance", surveillance);
        return root;
    }

    @Test
    public void bestandOhneWahlWirdEinmalGefragt() throws Exception {
        assertEquals(WaechterStandardHinweis.FRAGEN, WaechterStandardHinweis.entscheide(bestand(null), 70, 0));
        assertEquals(WaechterStandardHinweis.FRAGEN,
                WaechterStandardHinweis.entscheide(bestand(new JSONObject().put("surveillanceEnabled", false)), 70, 69));
    }

    @Test
    public void markerJeVersionCodeFragtNieZweimal() throws Exception {
        assertEquals(WaechterStandardHinweis.NICHTS, WaechterStandardHinweis.entscheide(bestand(null), 70, 70));
        assertEquals(WaechterStandardHinweis.NICHTS, WaechterStandardHinweis.entscheide(bestand(null), 70, 71));
        assertEquals("unbekannte Version", WaechterStandardHinweis.NICHTS, WaechterStandardHinweis.entscheide(bestand(null), 0, 0));
    }

    @Test
    public void bewusstAusUndSchonEingerichtetBleibtUnangetastet() throws Exception {
        assertEquals(WaechterStandardHinweis.STILL, WaechterStandardHinweis.entscheide(
                bestand(new JSONObject().put("disabledByUser", true)), 70, 0));
        assertEquals("gültiger triggerMode (auch „aus“) = eingerichtet", WaechterStandardHinweis.STILL,
                WaechterStandardHinweis.entscheide(bestand(new JSONObject().put("triggerMode", "ai_private")), 70, 0));
        assertEquals(WaechterStandardHinweis.STILL,
                WaechterStandardHinweis.entscheide(bestand(new JSONObject().put("triggerMode", "aus")), 70, 0));
    }

    @Test
    public void ohneEinrichtungsAbschnittUndWaehrendDesAssistentenWirdGewartet() throws Exception {
        assertEquals(WaechterStandardHinweis.NICHTS, WaechterStandardHinweis.entscheide(new JSONObject(), 70, 0));
        assertEquals(WaechterStandardHinweis.NICHTS, WaechterStandardHinweis.entscheide(null, 70, 0));
        assertEquals("Neuinstallation: der Assistent fragt den Wächter selbst", WaechterStandardHinweis.NICHTS,
                WaechterStandardHinweis.entscheide(neu("offen", null), 70, 0));
        // der Assistent war durch und hat den Wächter beantwortet → still erledigt
        assertEquals(WaechterStandardHinweis.STILL, WaechterStandardHinweis.entscheide(
                neu("erledigt", new JSONObject().put("triggerMode", "shock")), 70, 0));
    }

    @Test
    public void bannerUndFrageSindNurOffenSolangeNichtsGewaehltIst() throws Exception {
        assertTrue(WaechterStandardHinweis.fragenErlaubt(bestand(null)));
        assertFalse(WaechterStandardHinweis.fragenErlaubt(bestand(new JSONObject().put("triggerMode", "shock"))));
        assertFalse(WaechterStandardHinweis.fragenErlaubt(bestand(new JSONObject().put("disabledByUser", true))));
        assertFalse(WaechterStandardHinweis.fragenErlaubt(neu("offen", null)));
        assertFalse(WaechterStandardHinweis.fragenErlaubt(null));
    }

    @Test
    public void einschaltenBrauchtDenRechtshinweisUndSchaltetNieEineVeralteteFrageEin() throws Exception {
        JSONObject offen = bestand(null);
        assertNull(WaechterStandardHinweis.standardAnPruefen(offen, Katalog.GUARD_LEGAL_VERSION));
        assertEquals("rechtshinweis", WaechterStandardHinweis.standardAnPruefen(offen, ""));
        assertEquals("rechtshinweis", WaechterStandardHinweis.standardAnPruefen(offen, "alt"));
        assertEquals("nicht_mehr_noetig", WaechterStandardHinweis.standardAnPruefen(
                bestand(new JSONObject().put("disabledByUser", true)), Katalog.GUARD_LEGAL_VERSION));
        assertEquals("nicht_mehr_noetig", WaechterStandardHinweis.standardAnPruefen(
                bestand(new JSONObject().put("triggerMode", "aus")), Katalog.GUARD_LEGAL_VERSION));
        Map<String, Object> w = WaechterStandardHinweis.standardAnWerte();
        assertEquals("shock", w.get("triggerMode"));
        assertEquals(Katalog.GUARD_LEGAL_VERSION, w.get("guardLegalVersion"));
        assertEquals(Boolean.FALSE, w.get("disabledByUser"));
    }

    @Test
    public void markerUeberlebtNeustart() throws Exception {
        File basis = tmp.newFolder("b");
        assertEquals(0, WaechterStandardHinweis.ladeMarker(basis));
        WaechterStandardHinweis.speichereMarker(basis, 70);
        assertEquals(70, WaechterStandardHinweis.ladeMarker(basis));
    }

    // ------------------------------------------------------------- Verdrahtung (Versand ist daemon-nah)

    private static String lies(String rel) throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/" + rel.replaceFirst("^app/src/main/", ""))),
                StandardCharsets.UTF_8);
    }

    @Test
    public void dreiKanaeleUndEinschaltWegSindVerdrahtet() throws Exception {
        String k = lies("app/src/main/java/at/greenchili/surfmate/surveillance/WaechterStandardKanaele.java");
        assertTrue("Telegram", k.contains("TelegramNotifier.notifyWaechterStandard()"));
        assertTrue("Handy und Verlauf", k.contains("NotificationBus.get().publish") && k.contains("\"system.waechter\""));
        assertTrue("Display", k.contains("MessageOverlayController.showDialog"));
        String d = lies("app/src/main/java/at/greenchili/surfmate/appupdate/AppUpdateDienst.java");
        assertTrue(d.contains("waechterHinweisTakt(now);"));
        assertTrue(d.contains("WaechterStandardKanaele.senden();"));
        String sink = lies("app/src/main/java/at/greenchili/surfmate/notifications/sinks/TelegramSink.java");
        assertTrue("Telegram kommt direkt, nicht doppelt", sink.contains("\"system.waechter\".equals(event.category)"));
        String bot = lies("app/src/main/java/at/greenchili/surfmate/daemon/TelegramBotDaemon.java");
        assertTrue(bot.contains("case \"notifyWaechterStandard\":") && bot.contains("cmd:/waechter standard"));
        String h = lies("app/src/main/java/at/greenchili/surfmate/daemon/telegram/SurveillanceCommandHandler.java");
        assertTrue(h.contains("\"/api/surveillance/standard-an\"") && h.contains("GUARD_LEGAL_VERSION"));
        String api = lies("app/src/main/java/at/greenchili/surfmate/server/SurveillanceApiHandler.java");
        assertTrue(api.contains("\"/api/surveillance/standard-an\"") && api.contains("standardAnPruefen"));
        String html = lies("app/src/main/assets/web/local/waechter.html");
        assertTrue(html.contains("/api/surveillance/standard-an") && html.contains("s.standardFrage"));
        JSONObject kat = new JSONObject(lies("app/src/main/assets/notifications-categories.json"));
        boolean da = false;
        for (int i = 0; i < kat.getJSONArray("categories").length(); i++) {
            da |= "system.waechter".equals(kat.getJSONArray("categories").getJSONObject(i).getString("id"));
        }
        assertTrue("Push-Kategorie", da);
    }

    /** Koordination 04.11.: Ereignis-Clip gilt nach Landes-Standard, der Assistent schickt ihn nicht mit (und die Prüfung verlangt ihn nicht). */
    @Test
    public void assistentSendetKeinenEreignisClipUndPruefungVerlangtIhnNicht() throws Exception {
        String k = lies("app/src/main/java/at/greenchili/surfmate/onboarding/schritte/Pflichtschritte.kt");
        assertTrue(k.contains("put(\"modus\", modus).put(\"ton\", false))"));
        assertFalse(k.contains("ereignisClip\", false)"));
        String m = lies("app/src/main/java/at/greenchili/surfmate/onboarding/schritte/EinrichtungModell.kt");
        assertFalse("keine Pflichtangabe", m.contains("f.add(\"ereignisClip\")"));
    }

    @Test
    public void texteInFuenfSprachen() throws Exception {
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject s = new JSONObject(lies("app/src/main/assets/server-i18n/" + l + ".json"));
            JSONObject n = s.getJSONObject("notifications");
            for (String key : new String[] {"waechter_standard_titel", "waechter_standard_text", "waechter_standard_display"}) {
                assertFalse(l + " " + key, n.optString(key, "").isEmpty());
            }
            JSONObject tg = s.getJSONObject("telegram");
            assertFalse(l, tg.getJSONObject("guard").optString("standard_frage", "").isEmpty());
            assertFalse(l, tg.getJSONObject("guard").optString("standard_an_ok", "").isEmpty());
            assertFalse(l, tg.getJSONObject("guard").optString("standard_an_veraltet", "").isEmpty());
            assertFalse(l, tg.getJSONObject("buttons").optString("guard_standard_an", "").isEmpty());
            JSONObject web = new JSONObject(lies("app/src/main/assets/web/i18n/" + l + ".json")).getJSONObject("guard");
            for (String key : new String[] {"standard_titel", "standard_text", "standard_knopf", "standard_ok",
                    "standard_veraltet", "standard_fehler"}) {
                assertFalse(l + " " + key, web.optString(key, "").isEmpty());
            }
            // der Rechtshinweis steht in der Frage selbst (Telegram und Seite)
            assertTrue(l, tg.getJSONObject("guard").getString("standard_frage").length() > 200);
            assertTrue(l, web.getString("standard_text").length() > 200);
        }
    }
}
