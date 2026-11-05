package at.greenchili.surfmate.telegram.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 0.1.38.11 T1 (05.11.): Wächter-Bilder gehen ohne Aktivieren an Telegram. Standard an, bewusst „aus“ bleibt aus,
 * der Bestand bekommt die Nachricht genau einmal, neu Gekoppelte keine.
 */
public class MedienStandardTest {

    private static JSONObject gekoppelt() throws Exception {
        return new JSONObject().put(UnifiedTelegramConfig.K_OWNER_CHAT_ID, 4711L).put("botUsername", "x");
    }

    @Test
    public void standardAnOhneSchluessel() throws Exception {
        assertTrue(MedienStandard.STANDARD);
        assertTrue(MedienStandard.an(gekoppelt()));
        assertTrue(MedienStandard.an(new JSONObject()));
    }

    @Test
    public void bewusstAusBleibtAus() throws Exception {
        assertFalse(MedienStandard.an(gekoppelt().put(UnifiedTelegramConfig.K_VIDEO_UPLOADS, false)));
        assertTrue(MedienStandard.an(gekoppelt().put(UnifiedTelegramConfig.K_VIDEO_UPLOADS, true)));
        // bewusst gewählt (egal ob an oder aus): keine Nachricht, nur Merker
        assertEquals(MedienStandard.STILL,
                MedienStandard.entscheide(gekoppelt().put(UnifiedTelegramConfig.K_VIDEO_UPLOADS, false)));
        assertEquals(MedienStandard.STILL,
                MedienStandard.entscheide(gekoppelt().put(UnifiedTelegramConfig.K_VIDEO_UPLOADS, true)));
    }

    @Test
    public void bestandBekommtDieNachrichtGenauEinmal() throws Exception {
        JSONObject t = gekoppelt();
        assertEquals(MedienStandard.HINWEIS, MedienStandard.entscheide(t));
        t.put(MedienStandard.K_HINWEIS, true);   // nach dem Versand gesetzt
        assertEquals(MedienStandard.NICHTS, MedienStandard.entscheide(t));
        // auch wenn später jemand aus- und wieder einschaltet: nie ein zweites Mal
        t.put(UnifiedTelegramConfig.K_VIDEO_UPLOADS, false);
        assertEquals(MedienStandard.NICHTS, MedienStandard.entscheide(t));
    }

    @Test
    public void nichtGekoppeltOderUnlesbarKeineNachricht() throws Exception {
        assertEquals(MedienStandard.STILL, MedienStandard.entscheide(new JSONObject().put("botUsername", "x")));
        assertEquals(MedienStandard.STILL, MedienStandard.entscheide(
                new JSONObject().put(UnifiedTelegramConfig.K_OWNER_CHAT_ID, -1L)));
        // leerer/kaputter Abschnitt: nichts entscheiden, kein Merker (sonst bekäme der Bestand später nie die Nachricht)
        assertEquals(MedienStandard.NICHTS, MedienStandard.entscheide(new JSONObject()));
        assertEquals(MedienStandard.NICHTS, MedienStandard.entscheide(null));
    }

    private static String lies(String pfad) throws Exception {
        File b = new File("src/main").isDirectory() ? new File(".") : new File("app");
        return new String(Files.readAllBytes(new File(b, pfad).toPath()), StandardCharsets.UTF_8);
    }

    /** Quellvertrag des Dienstes: Merker erst nach erfolgreichem Versand, in einem Prozess höchstens einmal. */
    @Test
    public void dienstSetztMerkerErstNachDemVersand() throws Exception {
        String d = lies("src/main/java/at/greenchili/surfmate/daemon/TelegramBotDaemon.java").replace("\r\n", "\n");
        int a = d.indexOf("private static void medienStandardHinweis(");
        assertTrue(a > 0);
        int e = d.indexOf("\n    }\n", a);
        assertTrue("Methodenende (Länge " + d.length() + ")", e > a);
        String f = d.substring(a, e);
        int senden = f.indexOf("sendMessageWithButtons(ownerChatId, tr(\"media.standard_an\")");
        int merker = f.indexOf("MedienStandard.K_HINWEIS, true");
        assertTrue("sendet die Nachricht", senden > 0);
        assertTrue("Merker nach dem Versand", merker > senden);
        assertTrue("nach Fehlschlag kein Merker", f.indexOf("return;", senden) < merker);
        assertTrue("einmal je Prozess", f.contains("medienHinweisErledigt = true;   // in diesem Prozess nie ein zweites Mal"));
        assertTrue("im Poll-Takt aufgerufen", d.contains("medienStandardHinweis(section, System.currentTimeMillis());"));
    }

    /** Nachtrag: Die Kopplungsnachricht trägt den Hinweis (nur wenn Bilder an sind) und setzt den Merker. */
    @Test
    public void kopplungsnachrichtTraegtDenHinweis() throws Exception {
        String d = lies("src/main/java/at/greenchili/surfmate/daemon/TelegramBotDaemon.java").replace("\r\n", "\n");
        int a = d.indexOf(": tr(\"pair.success\", mdEscape(firstName));");
        assertTrue(a > 0);
        int hinweis = d.indexOf("erfolg = erfolg + \"\\n\\n\" + tr(\"pair.medien_hinweis\");", a);
        int bedingung = d.indexOf("if (at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig.isVideoUploads()) {", a);
        int merker = d.indexOf("MedienStandard.K_HINWEIS, true);", a);
        int senden = d.indexOf("sendMessageWithButtons(chatId, erfolg", a);
        assertTrue("nur wenn Bilder an", bedingung > a && bedingung < hinweis);
        assertTrue("Hinweis vor dem Versand", hinweis > 0 && hinweis < senden);
        assertTrue("Merker gesetzt", merker > 0 && merker < senden);
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            String t = new JSONObject(lies("src/main/assets/server-i18n/" + l + ".json")).getJSONObject("telegram")
                    .getJSONObject("pair").getString("medien_hinweis");
            assertTrue(l, t.contains("/bilder aus") && t.contains("Telegram"));
        }
    }

    @Test
    public void texteInAllenSprachen() throws Exception {
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject tg = new JSONObject(lies("src/main/assets/server-i18n/" + l + ".json")).getJSONObject("telegram");
            assertTrue(l, tg.getJSONObject("media").getString("standard_an").contains("/bilder aus"));
            for (String k : new String[] {"stand_an", "stand_aus", "aus", "an", "an_in_panoptes", "fehler", "btn_aus"}) {
                assertFalse(l + " " + k, tg.getJSONObject("bilder").getString(k).isEmpty());
            }
            assertTrue(l, tg.getJSONObject("help").getString("bilder").startsWith("/bilder"));
            String hinweis = new JSONObject(lies("src/main/assets/web/i18n/" + l + ".json")).getJSONObject("tgp")
                    .getString("a_video_hint");
            assertFalse(l + ": Standard nicht mehr aus", hinweis.matches("(?i)^(Standard aus|Off by default|Désactivé|Disattivato).*"));
        }
    }
}
