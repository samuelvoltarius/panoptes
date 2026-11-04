package at.greenchili.surfmate.daemon.telegram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.1.38.2 Updates per Telegram: Rückfrage vor jedem Start, Größe ohne WLAN, nur über {@code /api/appupdate/fern}, nie
 * still über mobile Daten; Hauptmenü mit großen Knöpfen, ausgeschaltete Fernbefehle unsichtbar; Text ohne „/“ → Menü.
 */
public class TelegramUpdateKnopfTest {

    private static final long CHAT = 42L;

    /** Kontext mit Attrappe des Kamera-Daemons. */
    static class Ctx implements CommandContext {
        final List<String> texte = new ArrayList<>();
        final List<String[][][]> knoepfe = new ArrayList<>();
        final List<String> anfragen = new ArrayList<>();
        final List<JSONObject> koerper = new ArrayList<>();
        JSONObject fern;
        JSONObject antwort;

        @Override public String tr(String key, Object... args) {
            StringBuilder b = new StringBuilder("tr:").append(key);
            if (args != null) for (Object a : args) b.append('|').append(a);
            return b.toString();
        }
        @Override public boolean sendMessage(long chatId, String text) { texte.add(text); knoepfe.add(null); return true; }
        @Override public boolean sendMessageWithButtons(long chatId, String text, String[][][] buttons) {
            texte.add(text); knoepfe.add(buttons); return true;
        }
        @Override public boolean sendVideo(long chatId, String videoPath, String caption) { return true; }
        @Override public JSONObject sendIpcCommand(int port, JSONObject command) { return null; }
        @Override public String execShell(String command) { return ""; }
        @Override public void log(String message) { }
        @Override public JSONObject daemonHttp(String method, String path, JSONObject body) {
            anfragen.add(method + " " + path);
            koerper.add(body);
            return "GET".equals(method) ? fern : antwort;
        }

        String[][][] letzteKnoepfe() { return knoepfe.get(knoepfe.size() - 1); }
        String letzterText() { return texte.get(texte.size() - 1); }
    }

    static JSONObject fern(boolean panoptesNeu, boolean launcherNeu, boolean mobil) throws Exception {
        JSONObject p = new JSONObject().put("ok", true).put("installiert", "0.1.38.1")
                .put("neueste", panoptesNeu ? new JSONObject().put("version", "0.1.38.2").put("groesseBytes", 71_000_000L)
                        : JSONObject.NULL)
                .put("zustand", JSONObject.NULL).put("mobilFrage", mobil).put("grund", JSONObject.NULL)
                .put("text", "Panoptes ist aktuell.");
        JSONObject l = new JSONObject().put("ok", true)
                .put("neueste", launcherNeu ? new JSONObject().put("version", "0.1.38.2").put("groesseBytes", JSONObject.NULL)
                        : JSONObject.NULL)
                .put("zustand", JSONObject.NULL).put("mobilFrage", mobil).put("grund", JSONObject.NULL)
                .put("text", "Der Launcher ist auf dem neuesten Stand.");
        return new JSONObject().put("ok", true).put("darf", true).put("panoptes", p).put("launcher", l);
    }

    @Test
    public void nurGueltigeKnoepfe() {
        assertTrue(UpdateCommandHandler.gueltig("up:p"));
        assertTrue(UpdateCommandHandler.gueltig("up:lj:0.1.38.2:1"));
        assertTrue(UpdateCommandHandler.gueltig("up:pj:0.1.38.2:0"));
        assertFalse(UpdateCommandHandler.gueltig("up:pj:0.1.38.2"));
        assertFalse(UpdateCommandHandler.gueltig("up:pj:0.1.38.2:2"));
        assertFalse(UpdateCommandHandler.gueltig("up:pj:../../x:1"));
        assertFalse(UpdateCommandHandler.gueltig("up:xx"));
        assertFalse(UpdateCommandHandler.gueltig("cmd:/updates"));
    }

    @Test
    public void uebersichtZeigtKnoepfeFuerBeide() throws Exception {
        Ctx c = new Ctx();
        c.fern = fern(true, true, false);
        assertTrue(new CommandRouter(c).route(CHAT, "/updates"));
        String[][][] k = c.letzteKnoepfe();
        assertEquals("up:p", k[0][0][1]);
        assertEquals("up:l", k[1][0][1]);
        assertEquals("up:such", k[2][0][1]);
        assertTrue(c.letzterText().contains("tr:updates.panoptes_neu"));
        assertEquals("GET /api/appupdate/fern", c.anfragen.get(0));
    }

    @Test
    public void aktuellOhneInstallierKnopf() throws Exception {
        Ctx c = new Ctx();
        c.fern = fern(false, false, false);
        UpdateCommandHandler.knopf(CHAT, "up:s", c);
        assertEquals("up:such", c.letzteKnoepfe()[0][0][1]);
        assertTrue(c.letzterText().contains("tr:updates.panoptes_aktuell"));
        assertTrue(c.letzterText().contains("tr:updates.launcher_aktuell"));
    }

    @Test
    public void tippFragtZuerstUndStartetNichts() throws Exception {
        Ctx c = new Ctx();
        c.fern = fern(true, false, false);
        UpdateCommandHandler.knopf(CHAT, "up:p", c);
        assertTrue(c.letzterText().startsWith("tr:updates.frage_panoptes"));
        assertFalse(c.letzterText().contains("frage_mobil"));
        assertEquals("up:pj:0.1.38.2:0", c.letzteKnoepfe()[0][0][1]);
        assertEquals("up:n", c.letzteKnoepfe()[0][1][1]);
        for (String a : c.anfragen) assertTrue(a.startsWith("GET"));
    }

    @Test
    public void ohneWlanNenntDieRueckfrageDieGroesse() throws Exception {
        Ctx c = new Ctx();
        c.fern = fern(true, true, true);
        UpdateCommandHandler.knopf(CHAT, "up:p", c);
        assertTrue(c.letzterText().contains("tr:updates.frage_mobil|71 MB"));
        assertEquals("up:pj:0.1.38.2:1", c.letzteKnoepfe()[0][0][1]);
        UpdateCommandHandler.knopf(CHAT, "up:l", c);
        assertTrue(c.letzterText().startsWith("tr:updates.frage_launcher"));
        assertTrue(c.letzterText().contains("tr:updates.frage_mobil|21 MB"));   // Größe unbekannt: aufgerundet
        assertEquals("up:lj:0.1.38.2:1", c.letzteKnoepfe()[0][0][1]);
    }

    @Test
    public void jaStartetUeberDenKern() throws Exception {
        Ctx c = new Ctx();
        c.antwort = new JSONObject().put("ok", true).put("vorgemerkt", true).put("text", "Vorgemerkt.");
        UpdateCommandHandler.knopf(CHAT, "up:pj:0.1.38.2:0", c);
        assertEquals("POST /api/appupdate/fern", c.anfragen.get(0));
        JSONObject b = c.koerper.get(0);
        assertEquals("panoptes", b.getString("was"));
        assertEquals("0.1.38.2", b.getString("version"));
        assertTrue(b.getBoolean("bestaetigt"));
        assertFalse(b.getBoolean("mobil"));
        assertEquals("telegram", b.getString("geraet"));
        assertTrue(c.letzterText().contains("Vorgemerkt."));
    }

    @Test
    public void launcherJaMitMobilerEinwilligung() throws Exception {
        Ctx c = new Ctx();
        c.antwort = new JSONObject().put("ok", true).put("zustand", "laeuft").put("text", "Läuft.");
        UpdateCommandHandler.knopf(CHAT, "up:lj:0.1.38.2:1", c);
        JSONObject b = c.koerper.get(0);
        assertEquals("launcher", b.getString("was"));
        assertTrue(b.getBoolean("mobil"));
    }

    @Test
    public void nieStillUeberMobileDaten() throws Exception {
        // Beim Ja war noch WLAN da, jetzt nicht mehr: erst neu fragen (mit Größe), nichts weiter senden
        Ctx c = new Ctx();
        c.antwort = new JSONObject().put("ok", false).put("grund", "wartet_wlan").put("mobilFrage", true)
                .put("text", "Kein WLAN.");
        c.fern = fern(true, false, true);
        UpdateCommandHandler.knopf(CHAT, "up:pj:0.1.38.2:0", c);
        assertEquals(1, countPosts(c));
        assertTrue(c.letzterText().startsWith("tr:updates.frage_panoptes"));
        assertEquals("up:pj:0.1.38.2:1", c.letzteKnoepfe()[0][0][1]);
    }

    @Test
    public void neinStartetNichts() {
        Ctx c = new Ctx();
        UpdateCommandHandler.knopf(CHAT, "up:n", c);
        assertTrue(c.anfragen.isEmpty());
        assertEquals("tr:updates.abgebrochen", c.letzterText());
    }

    @Test
    public void kernNichtErreichbarSagtEsEhrlich() {
        Ctx c = new Ctx();
        UpdateCommandHandler.knopf(CHAT, "up:p", c);
        assertEquals("tr:updates.nicht_bereit", c.letzterText());
    }

    @Test
    public void ungueltigerKnopfStartetNichts() {
        Ctx c = new Ctx();
        UpdateCommandHandler.knopf(CHAT, "up:pj:x y:1", c);
        assertTrue(c.anfragen.isEmpty());
    }

    @Test
    public void menueUndAliase() {
        assertEquals("/menue", CommandRouter.canonical("/menu"));
        assertEquals("/menue", CommandRouter.canonical("/menü"));
        assertEquals("/updates", CommandRouter.canonical("/aktualisieren"));
        assertEquals("/mehr", CommandRouter.canonical("/more"));
        assertNull(CommandRouter.requiredPermission("/updates"));
        Ctx c = new Ctx();
        assertTrue(new CommandRouter(c).route(CHAT, "/menue"));
        String[][][] k = c.letzteKnoepfe();
        assertEquals("cmd:/status", k[0][0][1]);
        assertEquals("cmd:/waechter", k[0][1][1]);
        boolean updates = false;
        for (String[][] row : k) {
            assertTrue("höchstens zwei Knöpfe je Reihe", row.length <= 2);
            for (String[] b : row) if ("cmd:/updates".equals(b[1])) updates = true;
        }
        assertTrue(updates);
    }

    @Test
    public void startGibtFestenMenueKnopfUndMenue() {
        final List<String> taste = new ArrayList<>();
        Ctx c = new Ctx() {
            @Override public boolean sendMessageWithMenuKey(long chatId, String text, String keyLabel) {
                taste.add(keyLabel);
                return true;
            }
        };
        assertTrue(new CommandRouter(c).route(CHAT, "/start"));
        assertEquals("tr:menu.btn_menu", taste.get(0));
        assertTrue(c.letzterText().startsWith("tr:menu.title"));
    }

    private static int countPosts(Ctx c) {
        int n = 0;
        for (String a : c.anfragen) if (a.startsWith("POST")) n++;
        return n;
    }
}
