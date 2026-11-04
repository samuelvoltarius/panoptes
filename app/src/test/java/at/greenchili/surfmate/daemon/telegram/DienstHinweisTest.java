package at.greenchili.surfmate.daemon.telegram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 0.1.38.8 Community-Meldung: „Kamera-Dienst antwortet nicht“ direkt nach der Einrichtung. Die Meldung kommt nur noch,
 * wenn der Hauptdienst wirklich ausfällt – nicht in den ersten Minuten, nicht bei bewusst ausgeschaltetem Dienst – und
 * ohne Fachwort. Dazu die Frage „auch bei abgestelltem Auto erreichbar?“ nach der Kopplung.
 */
public class DienstHinweisTest {

    static final long T = 1_800_000_000_000L;
    static final String[] SPRACHEN = {"de", "en", "fr", "it", "nl"};

    @Test
    public void anlaufNachStartUndKopplungKeinAlarm() {
        assertEquals(DienstHinweis.STARTET, DienstHinweis.schluessel(T + 60_000L, T, 0, false));
        assertEquals(DienstHinweis.STARTET, DienstHinweis.schluessel(T + DienstHinweis.ANLAUF_MS - 1, T, 0, false));
        assertEquals(DienstHinweis.AUSFALL, DienstHinweis.schluessel(T + DienstHinweis.ANLAUF_MS, T, 0, false));
        // Bot läuft schon lange, aber gerade gekoppelt: auch Anlauf
        long lange = T - 3_600_000L;
        assertEquals(DienstHinweis.STARTET, DienstHinweis.schluessel(T + 30_000L, lange, T, false));
        assertEquals(DienstHinweis.AUSFALL, DienstHinweis.schluessel(T + DienstHinweis.ANLAUF_MS + 1, lange, T, false));
    }

    @Test
    public void bewusstAusgeschaltetIstKeinAusfall() {
        assertEquals(DienstHinweis.AUS, DienstHinweis.schluessel(T + DienstHinweis.ANLAUF_MS * 10, T, 0, true));
        assertEquals(DienstHinweis.AUS, DienstHinweis.schluessel(T + 1, T, 0, true));
    }

    @Test
    public void uhrSpruengeZaehlenAlsAusfallNichtAlsEwigerAnlauf() {
        // Uhr vor dem Start (Zeitsprung zurück): nicht „startet“ für immer
        assertEquals(DienstHinweis.AUSFALL, DienstHinweis.schluessel(T - 10_000L, T, 0, false));
    }

    @Test
    public void erreichbarNurMitAusdruecklichemAn() {
        assertEquals("an", ErreichbarCommandHandler.was("an"));
        assertEquals("an", ErreichbarCommandHandler.was("ja"));
        assertEquals("aus", ErreichbarCommandHandler.was("aus"));
        assertEquals("nein", ErreichbarCommandHandler.was("nein"));
        for (String w : new String[] {"", "status", "vielleicht", "dauerhaft"}) {
            assertEquals(w, "status", ErreichbarCommandHandler.was(w));
        }
        String[][][] k = ErreichbarCommandHandler.knoepfe(s -> s);
        assertEquals("cmd:/erreichbar an", k[0][0][1]);
        assertEquals("cmd:/erreichbar nein", k[0][1][1]);
        assertEquals("/erreichbar", CommandRouter.canonical("/erreichbar"));
        assertEquals("/erreichbar", CommandRouter.canonical("/reachable"));
    }

    @Test
    public void befehleNutzenDenHinweisStattDesAltenTexts() throws Exception {
        for (String f : new String[] {"SurveillanceCommandHandler", "CameraCommandHandler", "WhereCommandHandler",
                "BackupCommandHandler"}) {
            String s = lies("app/src/main/java/at/greenchili/surfmate/daemon/telegram/" + f + ".java");
            assertTrue(f, s.contains("DienstHinweis.text(ctx)"));
            assertFalse(f, s.contains("camera_service_down"));
        }
        String bot = lies("app/src/main/java/at/greenchili/surfmate/daemon/TelegramBotDaemon.java");
        assertTrue(bot.contains("DienstHinweis.botGestartet("));
        assertTrue(bot.contains("DienstHinweis.gekoppelt("));
        // nach der Kopplung nur fragen, wenn der Bot nicht ohnehin abgestellt erreichbar ist; Standard bleibt aus
        assertTrue(bot.contains("if (!at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig.shouldStartOnAccOff())"));
    }

    @Test
    public void texteInAllenSprachenOhneFachwort() throws Exception {
        for (String l : SPRACHEN) {
            JSONObject tg = new JSONObject(lies("app/src/main/assets/server-i18n/" + l + ".json")).getJSONObject("telegram");
            for (String k : new String[] {"startet", "aus", "antwortet_nicht"}) {
                String t = tg.getJSONObject("dienst").getString(k);
                assertFalse(l + " " + k, t.trim().isEmpty());
                assertFalse(l + " " + k + " ohne Fachwort", t.toLowerCase().contains("kamera-dienst")
                        || t.toLowerCase().contains("daemon") || t.toLowerCase().contains("ipc"));
            }
            for (String k : new String[] {"frage", "btn_ja", "btn_nein", "an", "aus", "nein", "stand_an", "fehler"}) {
                assertFalse(l + " " + k, tg.getJSONObject("erreichbar").getString(k).trim().isEmpty());
            }
            assertTrue(l, tg.getJSONObject("erreichbar").getString("frage").contains("12"));
            JSONObject w = new JSONObject(lies("app/src/main/assets/web/i18n/" + l + ".json"));
            for (String k : new String[] {"reach_q", "reach_hint", "reach_on"}) assertTrue(l + k, w.getJSONObject("tgp").has(k));
            for (String k : new String[] {"handy_unterwegs_q", "handy_unterwegs_strom", "handy_unterwegs_an"}) {
                assertTrue(l + k, w.getJSONObject("system").has(k));
            }
        }
    }

    @Test
    public void webFragtNurWennAus() throws Exception {
        String tg = lies("app/src/main/assets/web/local/telegram.html");
        assertTrue(tg.contains("$('reachBox').hidden = !(step === 3 && !s.autoStartAccOff && !s.daemonAlwaysOn);"));
        String sys = lies("app/src/main/assets/web/local/system.html");
        assertTrue(sys.contains("if (d.ok && was === 'erlauben' && art === 'handy') self.unterwegsAnbieten();"));
        // Brücke nur nach Rückfrage, die die Zustimmung enthält
        assertTrue(sys.contains("if (!window.confirm(frage)) return;"));
        assertTrue(sys.contains("ein: true, zustimmung: true, quelle: 'kopplung'"));
    }

    static String lies(String rel) throws Exception {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (p != null) {
            for (Path c : new Path[] {p.resolve(rel), p.resolve(rel.replaceFirst("^app/", ""))}) {
                if (Files.isRegularFile(c)) return new String(Files.readAllBytes(c), StandardCharsets.UTF_8);
            }
            p = p.getParent();
        }
        throw new AssertionError(rel);
    }
}
