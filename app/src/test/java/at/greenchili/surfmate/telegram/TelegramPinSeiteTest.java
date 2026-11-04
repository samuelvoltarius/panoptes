package at.greenchili.surfmate.telegram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 0.1.38.7 Befund am Auto: telegram.html zählte eine PIN weiter, die der Kern nach {@code /api/telegram/clear} schon
 * gelöscht hatte („Es gibt gerade keine PIN“ im Bot). Dazu: 409 beim Abruf verständlich melden, Bot-Texte mit dem
 * richtigen Menüweg.
 */
public class TelegramPinSeiteTest {

    private static String lies(String pfad) throws Exception {
        File f = new File(pfad);
        if (!f.isFile()) f = new File("app/" + pfad);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static final String SEITE = "src/main/assets/web/local/telegram.html";

    @Test
    public void pollKonfliktNurBeimWechselGeschrieben() throws Exception {
        File dir = Files.createTempDirectory("tgpoll").toFile();
        File f = new File(dir, PollKonflikt.DATEI);
        try {
            assertEquals(0, PollKonflikt.seit(f));
            PollKonflikt.abruf(true, 1000L, f);
            assertEquals(1000L, PollKonflikt.seit(f));
            PollKonflikt.abruf(true, 5000L, f);          // weiter 409: Beginn bleibt, kein neues Schreiben
            assertEquals(1000L, PollKonflikt.seit(f));
            PollKonflikt.abruf(false, 6000L, f);         // erfolgreicher Abruf: weg
            assertFalse(f.exists());
            assertEquals(0, PollKonflikt.seit(f));
            PollKonflikt.abruf(false, 7000L, f);         // bleibt weg
            assertFalse(f.exists());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            //noinspection ResultOfMethodCallIgnored
            dir.delete();
        }
    }

    @Test
    public void kaputteDateiGiltAlsKeinKonflikt() throws Exception {
        File dir = Files.createTempDirectory("tgpoll2").toFile();
        File f = new File(dir, PollKonflikt.DATEI);
        try {
            Files.write(f.toPath(), "unsinn".getBytes(StandardCharsets.UTF_8));
            assertEquals(0, PollKonflikt.seit(f));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            //noinspection ResultOfMethodCallIgnored
            dir.delete();
        }
    }

    @Test
    public void daemonUndStatusNutzenDenMerker() throws Exception {
        String d = lies("src/main/java/at/greenchili/surfmate/daemon/TelegramBotDaemon.java");
        assertTrue(d.contains("if (code == 409) at.greenchili.surfmate.telegram.PollKonflikt.abruf(true, System.currentTimeMillis());"));
        assertTrue(d.contains("at.greenchili.surfmate.telegram.PollKonflikt.abruf(false, System.currentTimeMillis());"));
        String h = lies("src/main/java/at/greenchili/surfmate/server/TelegramApiHandler.java");
        assertTrue(h.contains("response.put(\"pollKonflikt\", konfliktSeit > 0);"));
        // neuer Schlüssel und Bot entfernen setzen den Konflikt zurück
        int token = h.indexOf("private static void handleToken(");
        int clear = h.indexOf("private static void handleClear(");
        assertTrue(h.indexOf("PollKonflikt.zuruecksetzen();", token) > token);
        assertTrue(h.indexOf("PollKonflikt.zuruecksetzen();", clear) > clear);
    }

    @Test
    public void seiteVerwirftPinBeiClearUnpairUndNeuemSchluessel() throws Exception {
        String s = lies(SEITE);
        assertTrue(s.contains("pinVerwerfen();\n            post('/api/telegram/clear')"));
        assertTrue(s.contains("pinVerwerfen();\n            post('/api/telegram/owner/clear')"));
        int tok = s.indexOf("function saveToken()");
        int ende = s.indexOf("// ---------------- Schritt 2", tok);
        String save = s.substring(tok, ende);
        assertTrue(save.contains("pinVerwerfen();"));
        // nach neuem Schlüssel gleich eine frische PIN (ein Tipp bis zur Kopplung)
        assertTrue(save.contains("if (last && last.configured && !last.paired) startPairing();"));
    }

    @Test
    public void seitePrueftBeimAbfragenObDiePinNochGilt() throws Exception {
        String s = lies(SEITE);
        int poll = s.indexOf("function startPoll()");
        String p = s.substring(poll, s.indexOf("// ---------------- Schritt 3", poll));
        assertTrue(p.contains("} else if (!pinGiltNoch(s, shownPin)) {"));
        assertTrue(s.contains("if (shownPin && !pinGiltNoch(s, shownPin)) pinUngueltig(step === 2);"));
        // gültig nur, wenn der Kern genau diese PIN noch kennt und sie nicht abgelaufen ist
        String g = s.substring(s.indexOf("function pinGiltNoch("), s.indexOf("function pinVerwerfen("));
        assertTrue(g.contains("s.pendingPin === pin"));
        assertTrue(g.contains("s.pendingPinExpiresAt > Date.now()"));
        assertTrue(g.contains("!s.paired"));
        assertTrue(s.contains("$('pollKonflikt').hidden = !s.pollKonflikt;"));
    }

    @Test
    public void textePinUngueltigUndKonfliktInAllenSprachen() throws Exception {
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject tgp = new JSONObject(lies("src/main/assets/web/i18n/" + l + ".json")).getJSONObject("tgp");
            assertFalse(l, tgp.optString("pin_invalid", "").isEmpty());
            assertFalse(l, tgp.optString("poll_conflict", "").isEmpty());
        }
    }

    /** Bot-Texte: der alte Weg „System → Telegram…“ stimmt seit dem Menü 0.1.38.6 nicht mehr. */
    @Test
    public void botTexteZeigenDenRichtigenWeg() throws Exception {
        String[][] weg = {{"de", "Regeln → Meldungen → Telegram-Meldungen"}, {"en", "Rules → Alerts → Telegram alerts"},
                {"fr", "Règles → Alertes → Alertes Telegram"}, {"it", "Regole → Avvisi → Avvisi Telegram"},
                {"nl", "Regels → Meldingen → Telegram-meldingen"}};
        for (String[] w : weg) {
            String roh = lies("src/main/assets/server-i18n/" + w[0] + ".json");
            JSONObject pair = new JSONObject(roh).getJSONObject("telegram").getJSONObject("pair");
            assertTrue(w[0], pair.getString("no_pin").contains(w[1]));
            assertTrue(w[0], pair.getString("no_owner").contains(w[1]));
            assertTrue(w[0], pair.getString("expired").contains(w[1]));
            for (String alt : new String[] {"System → Telegram", "Système → Alertes Telegram", "Système → Messages Telegram",
                    "Sistema → Avvisi Telegram", "Sistema → Messaggi Telegram", "Systeem → Telegram"}) {
                assertFalse(w[0] + ": " + alt, roh.contains(alt));
            }
            // Menüweg passt zur Navigation (Abschnitt, Teil, Seite)
            JSONObject web = new JSONObject(lies("src/main/assets/web/i18n/" + w[0] + ".json"));
            String erwartet = web.getJSONObject("shell").getString("sec_automationen") + " → "
                    + web.getJSONObject("shell").getString("tab_meldungen") + " → " + web.getJSONObject("nav").getString("telegram");
            assertEquals(w[0], erwartet, w[1]);
        }
    }
}
