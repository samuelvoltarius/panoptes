package at.greenchili.surfmate.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 0.1.24 Meldung „Neues Gerät verbunden“ nach jeder Kopplung mit Code (Koordination 28.10.). */
public class NeueKopplungMeldungTest {

    static String lies(String pfad) throws Exception {
        File f = new File(pfad);
        if (!f.isFile()) f = new File("app/" + pfad);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void nameWirdEntschaerft() {
        assertEquals("?", NeueKopplungMeldung.anzeigeName(null));
        assertEquals("?", NeueKopplungMeldung.anzeigeName("  "));
        assertEquals("Pixel  Alf", NeueKopplungMeldung.anzeigeName("Pixel\n*Alf"));
        String lang = NeueKopplungMeldung.anzeigeName("x".repeat(100));
        assertEquals(25, lang.length());
        assertTrue(lang.endsWith("…"));
    }

    @Test
    public void codeKopplungMeldetUndTexteInAllenSprachen() throws Exception {
        String k = lies("src/main/java/at/greenchili/surfmate/server/AuthApiHandler.java");
        int erfolg = k.indexOf("log(\"Kopplung erfolgreich\");");
        int melden = k.indexOf("NeueKopplungMeldung.melden(name, kid);");
        assertTrue("nach dem Erfolg, vor der Antwort", erfolg > 0 && melden > erfolg
                && melden < k.indexOf("HttpResponse.sendJson(out, response.toString());", erfolg));
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject n = new JSONObject(lies("src/main/assets/server-i18n/" + l + ".json")).getJSONObject("notifications");
            assertTrue(l, n.getString("neue_kopplung_titel").contains("{0}"));
            // Band: höchstens ~35 Zeichen je Zeile, sonst schneidet das Display ab (Befund Kern 12)
            assertTrue(l, n.getString("neue_kopplung_band_titel").replace("{0}", "x".repeat(25)).length() <= 45);
            assertTrue(l, n.getString("neue_kopplung_band_text").length() <= 35);
            String t = n.getString("neue_kopplung_text");
            assertFalse(l + ": kein ASCII-Apostroph (MessageFormat)", t.contains("'"));
            assertFalse(l + ": keine Fachwörter", t.toLowerCase(java.util.Locale.ROOT).contains("brücke")
                    || t.toLowerCase(java.util.Locale.ROOT).contains("bruecke"));
        }
    }

    @Test
    public void entfernenGriffEinmalUndBefristet() {
        String r = NeueKopplungMeldung.griff("k-1", 1000);
        assertTrue(r.matches("^[0-9a-f]{16}$"));
        assertFalse("nie die Kopplungs-id im Griff", r.contains("k-1"));
        assertEquals("k-1", NeueKopplungMeldung.einloesen(r, 2000));
        assertEquals("nur einmal", null, NeueKopplungMeldung.einloesen(r, 2000));
        String r2 = NeueKopplungMeldung.griff("k-2", 1000);
        assertEquals("nach 24 h abgelaufen", null, NeueKopplungMeldung.einloesen(r2, 1000 + NeueKopplungMeldung.GRIFF_MS));
        assertEquals(null, NeueKopplungMeldung.einloesen("nicht-da", 0));
        // nie mehr als MAX_GRIFFE im Speicher: der älteste fliegt
        String erster = NeueKopplungMeldung.griff("k-alt", 0);
        for (int i = 0; i < NeueKopplungMeldung.MAX_GRIFFE; i++) NeueKopplungMeldung.griff("k-" + i, 0);
        assertEquals(null, NeueKopplungMeldung.einloesen(erster, 1));
    }

    @Test
    public void telegramKnopfUndEndpunkt() throws Exception {
        assertEquals("ke:0123456789abcdef", at.greenchili.surfmate.telegram.EntfernenKnopf.daten("0123456789abcdef"));
        assertEquals("0123456789abcdef", at.greenchili.surfmate.telegram.EntfernenKnopf.lies("ke:0123456789abcdef"));
        assertEquals(null, at.greenchili.surfmate.telegram.EntfernenKnopf.lies("ke:k-1"));
        assertEquals(null, at.greenchili.surfmate.telegram.EntfernenKnopf.lies("kp:0123456789abcdef:j"));
        assertEquals("kopplung.entfernt", at.greenchili.surfmate.telegram.EntfernenKnopf.ergebnisSchluessel(200));
        assertEquals("kopplung.entfernen_abgelaufen", at.greenchili.surfmate.telegram.EntfernenKnopf.ergebnisSchluessel(410));
        String e = lies("src/main/java/at/greenchili/surfmate/server/EinstellungenApiHandler.java");
        int i = e.indexOf("/api/kopplung/entfernen/");
        assertTrue("nur mit Login", i > 0 && e.indexOf("if (AuthMiddleware.isExternRequest()) return verboten(out);", i) > i
                && e.indexOf("if (AuthMiddleware.isExternRequest()) return verboten(out);", i) < e.indexOf("einloesen(", i));
        String bot = lies("src/main/java/at/greenchili/surfmate/daemon/TelegramBotDaemon.java");
        assertTrue(bot.contains("case \"notifyNeueKopplung\""));
        assertTrue("Besitzer-Prüfung", bot.contains("EntfernenKnopf.lies(data);")
                && bot.indexOf("KopplungsKnopf.berechtigt(userId, chatId, ownerChatId)", bot.indexOf("EntfernenKnopf.lies(data);")) > 0);
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject t = new JSONObject(lies("src/main/assets/server-i18n/" + l + ".json")).getJSONObject("telegram").getJSONObject("kopplung");
            for (String k : new String[] {"neu", "knopf_entfernen", "entfernt", "entfernen_abgelaufen"}) assertTrue(l + k, t.has(k));
        }
    }
}
