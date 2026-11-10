package at.greenchili.surfmate.daemon.telegram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.rueckmeldung.FehlerMelden;
import at.greenchili.surfmate.testutil.Quelltext;
import at.greenchili.surfmate.ui.UiHaengerWaechter;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 0.1.38.14 (P3, Koordination 06.11.): Seal U DM-i hing beim Umschalten der Kartenquelle und in der Tacho-Einrichtung;
 * bei eingefrorener Oberfläche gab es keinen Weg zu einem Fehlerbericht. Vertrag: Abrufe mit Frist und Satz statt
 * grauem Knopf, Kartenquelle nie im UI-Thread lesen, kein Prefetch direkt nach dem Umschalten, Telegram {@code /fehler}
 * (nur Besitzer-Chat, derselbe Bericht), Bericht mit START_STUFE/Bildschirm/Kartenquelle/Tacho, Hänger-Erkennung,
 * keine setattr-Flut aus dem App-Prozess.
 */
public class HaengerFehlerberichtTest {

    private static String lies(String p) throws Exception {
        return Quelltext.lies(Paths.get("src/main/" + p));
    }

    private static final class Ctx implements CommandContext {
        final List<String> gesendet = new ArrayList<>();
        JSONObject antwort;
        String pfad;
        JSONObject body;

        @Override public String tr(String key, Object... args) {
            return args.length == 0 ? key : key + ":" + args[0];
        }
        @Override public boolean sendMessage(long chatId, String text) { gesendet.add(text); return true; }
        @Override public boolean sendMessageWithButtons(long chatId, String text, String[][][] b) { return true; }
        @Override public boolean sendVideo(long chatId, String videoPath, String caption) { return true; }
        @Override public JSONObject sendIpcCommand(int port, JSONObject command) { return null; }
        @Override public String execShell(String command) { return ""; }
        @Override public JSONObject daemonHttp(String method, String path, JSONObject b) {
            pfad = method + " " + path;
            body = b;
            return antwort;
        }
        @Override public void log(String message) {}
    }

    // ---------------------------------------------------------------- Telegram /fehler

    @Test
    public void fehlerBefehlAngemeldetOhneFreigabe() {
        assertEquals("/fehler", CommandRouter.canonical("/fehler"));
        assertEquals("/fehler", CommandRouter.canonical("/Bug@MeinBot"));
        assertEquals("/fehler", CommandRouter.canonical("/fehlerbericht"));
        assertNull(CommandRouter.requiredPermission("/fehler"));
        assertTrue(new FehlerCommandHandler().canHandle("/fehler"));
    }

    @Test
    public void fehlerBefehlSchicktDenBerichtUeberDenDaemon() throws Exception {
        Ctx c = new Ctx();
        c.antwort = new JSONObject().put("ok", true).put("stand", "gesendet").put("code", "AB12-CD34");
        new FehlerCommandHandler().handle(1L, new String[]{"/fehler", "Karte", "hängt"}, c);
        assertEquals("POST /api/rueckmeldung/fehler-telegram", c.pfad);
        assertEquals("Karte hängt", c.body.getString("text"));
        assertEquals("fehler.laeuft", c.gesendet.get(0));
        assertEquals("fehler.gesendet:AB12-CD34", c.gesendet.get(1));
        assertEquals(at.greenchili.surfmate.server.RueckmeldungApiHandler.TELEGRAM_PFAD, FehlerCommandHandler.PFAD);
    }

    @Test
    public void fehlerBefehlAntwortetEhrlich() throws Exception {
        Ctx c = new Ctx();
        assertEquals("fehler.nicht", FehlerCommandHandler.antwort(null, c));
        assertEquals("fehler.gemerkt", FehlerCommandHandler.antwort(new JSONObject().put("stand", "gemerkt"), c));
        assertEquals("fehler.zuviel", FehlerCommandHandler.antwort(new JSONObject().put("stand", "zuviel"), c));
        assertEquals("fehler.nicht", FehlerCommandHandler.antwort(new JSONObject().put("stand", "abgelehnt"), c));
        assertEquals("fehler.gesendet_ohne", FehlerCommandHandler.antwort(new JSONObject().put("stand", "gesendet"), c));
        assertEquals("", FehlerCommandHandler.text(new String[]{"/fehler"}));
    }

    @Test
    public void nurBesitzerChatUndNurLokal() throws Exception {
        String bot = lies("java/at/greenchili/surfmate/daemon/TelegramBotDaemon.java");
        int pruef = bot.indexOf("if (chatId != ownerChatId) {");
        int route = bot.indexOf("commandRouter.route(chatId, text);");
        assertTrue("Router erst nach der Besitzer-Prüfung", pruef > 0 && route > pruef);
        String h = lies("java/at/greenchili/surfmate/server/RueckmeldungApiHandler.java");
        int extern = h.indexOf("if (AuthMiddleware.isExternRequest()) {");
        int tg = h.indexOf("if (telegram && method.equals(\"POST\")) {");
        assertTrue("fehler-telegram hinter der Extern-Sperre (403)", extern > 0 && tg > extern);
        String hilfe = lies("java/at/greenchili/surfmate/daemon/telegram/SystemCommandHandler.java");
        assertTrue(hilfe.contains("ctx.tr(\"help.fehler\")"));
    }

    // ---------------------------------------------------------------- Fehlerbericht

    @Test
    public void startStufenImAuszug() {
        String roh = "2026-11-06 21:00:01 app START_STUFE stufe=ZUGANG\n"
                + "irgendwas anderes\n"
                + "2026-11-06 21:00:03 app START_STUFE stufe=OBERFLAECHE lat=47.80123\r\n";
        String s = FehlerMelden.startStufen(roh, FehlerMelden.START_MAX);
        assertTrue(s.startsWith("[start]\n"));
        assertTrue(s.contains("stufe=ZUGANG"));
        assertTrue(s.contains("stufe=OBERFLAECHE"));
        assertFalse("geschwärzt", s.contains("47.80123"));
        assertFalse(s.contains("irgendwas"));
        assertEquals("", FehlerMelden.startStufen("nichts\n", 8));
        StringBuilder viel = new StringBuilder();
        for (int i = 0; i < 20; i++) viel.append("START_STUFE stufe=").append(i).append('\n');
        String k = FehlerMelden.startStufen(viel.toString(), 3);
        assertFalse(k.contains("stufe=16\n"));
        assertTrue(k.contains("stufe=19"));
    }

    @Test
    public void bildschirmNurPlausibel() throws Exception {
        JSONObject b = FehlerMelden.bildschirm(1920, 1080, 240);
        assertNotNull(b);
        assertEquals(1920, b.getInt("breite"));
        assertEquals(240, b.getInt("dpi"));
        assertNull(FehlerMelden.bildschirm(0, 1080, 240));
        assertNull(FehlerMelden.bildschirm(1920, 1080, 5000));
    }

    @Test
    public void berichtTraegtStartBildschirmKarteTacho() throws Exception {
        String h = lies("java/at/greenchili/surfmate/server/RueckmeldungApiHandler.java");
        assertTrue(h.contains("return waechterZeile() + startZeilen()"));
        assertTrue(h.contains("String text = waechterZeile() + startZeilen()"));
        assertTrue(h.contains("a.put(\"bildschirm\", bildschirmAngaben()).put(\"karte\", karteQuelle(root));"));
        String seite = lies("assets/web/local/fehler-melden.html");
        assertTrue(seite.contains("id=\"fmTacho\""));
        assertTrue(seite.contains("id=\"fmBildschirm\""));
        assertTrue(seite.contains("id=\"fmKarte\""));
        assertTrue(seite.contains("$('fmTacho').textContent = tachoText(d.tacho || {});"));
    }

    // ---------------------------------------------------------------- Karte / Hänger

    @Test
    public void kartenquelleMitFristUndSatz() throws Exception {
        String js = lies("assets/web/shared/kartenquelle.js");
        int helfer = js.indexOf("function holeMitFrist(url, opt)");
        assertTrue(helfer > 0);
        // außer im Helfer selbst kein direkter fetch-Aufruf mehr
        String ohne = js.replace("fetch(url, opt)", "");
        assertFalse("jeder Abruf über holeMitFrist", ohne.matches("(?s).*[^.a-zA-Z]fetch\\(.*"));
        assertTrue(js.contains("letzterFehlerZeit ? 'zeit' : 'fehler'"));
        assertTrue(js.contains("zeit: '"));
        // ES5 (WebView 74): keine Pfeile, kein let/const
        assertFalse(js.contains("=>"));
        assertFalse(js.matches("(?s).*\\b(let|const) .*"));
    }

    @Test
    public void kartenquelleNieImUiThread() throws Exception {
        String a = lies("java/at/greenchili/surfmate/navmap/RoadSenseMapActivity.kt");
        assertEquals("nur im Hintergrund-Faden", 1, zaehle(a, "Kartenquelle.aktuell()"));
        int faden = a.indexOf("private fun kartenquelleLesen(");
        assertTrue(a.indexOf("Kartenquelle.aktuell()") > faden);
        assertTrue(a.contains("mlMap.setStyle(styleBuilderForTheme(q))"));
        assertTrue(a.contains("MapTilePrefetcher.quelleGewechselt()"));
        String p = lies("java/at/greenchili/surfmate/navmap/MapTilePrefetcher.kt");
        assertTrue(p.contains("if (gesperrt(gesperrtBisMs, android.os.SystemClock.elapsedRealtime())) return@Runnable"));
        assertTrue(p.contains("const val SPERRE_NACH_WECHSEL_MS = 120_000L"));
        // Auftrag aus navi.html: Vorschau mit Start, Suche ohne Fokus und ohne Tastatur
        int restore = a.indexOf("private fun restoreTripIfAny()");
        assertTrue(a.indexOf("sucheOhneFokus()", restore) > restore);
        assertTrue(a.contains("input.clearFocus()"));
    }

    @Test
    public void keineSetattrFlutAusDerApp() throws Exception {
        String u = lies("java/at/greenchili/surfmate/config/UnifiedConfigManager.kt");
        int lock = u.indexOf("lock = channel.lock()");
        int guard = u.indexOf("if (android.os.Process.myUid() == SHELL_DAEMON_UID) {", lock);
        int setR = u.indexOf("lockFile.setReadable(true, false)", lock);
        assertTrue(lock > 0 && guard > lock && setR > guard);
    }

    @Test
    public void haengerErkennung() throws Exception {
        assertFalse(UiHaengerWaechter.haengt(0L, 100_000L, UiHaengerWaechter.SCHWELLE_MS));
        assertFalse(UiHaengerWaechter.haengt(1_000L, 1_000L + UiHaengerWaechter.SCHWELLE_MS, UiHaengerWaechter.SCHWELLE_MS));
        assertTrue(UiHaengerWaechter.haengt(1_000L, 1_001L + UiHaengerWaechter.SCHWELLE_MS, UiHaengerWaechter.SCHWELLE_MS));
        assertTrue(UiHaengerWaechter.meldenErlaubt(0L, 5L));
        assertFalse(UiHaengerWaechter.meldenErlaubt(1_000L, 1_000L + UiHaengerWaechter.MELDE_ABSTAND_MS - 1));
        assertTrue(UiHaengerWaechter.meldenErlaubt(1_000L, 1_000L + UiHaengerWaechter.MELDE_ABSTAND_MS));
        String app = lies("java/at/greenchili/surfmate/SurfMateApplication.kt");
        assertTrue(app.contains("at.greenchili.surfmate.ui.UiHaengerWaechter.starten(this)"));
        String w = lies("java/at/greenchili/surfmate/ui/UiHaengerWaechter.kt");
        assertTrue("Tiefschlaf zählt nicht", w.contains("SystemClock.uptimeMillis()"));
        assertTrue(w.contains("TelegramNotifier.sendMessage(text, \"HEALTH\")"));
    }

    // ---------------------------------------------------------------- Texte

    @Test
    public void neueTexteInFuenfSprachen() throws Exception {
        for (String l : new String[]{"de", "en", "fr", "it", "nl"}) {
            JSONObject s = new JSONObject(lies("assets/server-i18n/" + l + ".json")).getJSONObject("telegram");
            assertTrue(l, s.getJSONObject("help").getString("fehler").startsWith("/fehler"));
            JSONObject f = s.getJSONObject("fehler");
            for (String k : new String[]{"laeuft", "gesendet", "gesendet_ohne", "gemerkt", "zuviel", "nicht"}) {
                assertFalse(l + " " + k, f.getString(k).isEmpty());
            }
            assertTrue(f.getString("gesendet").contains("{0}"));
            JSONObject w = new JSONObject(lies("assets/web/i18n/" + l + ".json"));
            assertFalse(w.getJSONObject("kartenquelle").getString("zeit").isEmpty());
            JSONObject r = w.getJSONObject("rueckmeldung");
            for (String k : new String[]{"auto_bildschirm", "auto_tacho", "auto_karte", "tacho_profil",
                    "tacho_bestaetigt", "tacho_offen", "tacho_vorschlag"}) {
                assertFalse(l + " " + k, r.getString(k).isEmpty());
            }
            String res = lies("res/values" + ("en".equals(l) ? "" : "-" + l) + "/strings.xml");
            assertTrue(l, res.contains("name=\"ui_haenger_telegram\"") && res.contains("%1$d"));
        }
    }

    private static int zaehle(String s, String teil) {
        int n = 0, i = 0;
        while ((i = s.indexOf(teil, i)) >= 0) { n++; i += teil.length(); }
        return n;
    }
}
