package at.greenchili.surfmate.daemon.telegram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** 0.1.21 Telegram /wo: Ort nur mit „Position teilen“, sonst ehrlicher Hinweis. */
public class WoPositionFreigabeTest {

    /** Übersetzt nicht, liefert den Schlüssel (und hängt Argumente an). */
    private static final CommandContext CTX = new CommandContext() {
        @Override public String tr(String key, Object... args) {
            StringBuilder b = new StringBuilder(key);
            if (args != null) for (Object a : args) b.append('|').append(a);
            return b.toString();
        }
        @Override public boolean sendMessage(long chatId, String text) { return true; }
        @Override public boolean sendMessageWithButtons(long chatId, String text, String[][][] buttons) { return true; }
        @Override public boolean sendVideo(long chatId, String videoPath, String caption) { return true; }
        @Override public JSONObject sendIpcCommand(int port, JSONObject command) { return null; }
        @Override public String execShell(String command) { return ""; }
        @Override public void log(String message) { }
    };

    private static JSONObject sitzung() throws Exception {
        return new JSONObject().put("startedMs", 1L).put("eventCount", 2)
                .put("place", new JSONObject().put("short", "Hauptplatz 1"))
                .put("gps", new JSONObject().put("quality", "FRESH").put("lat", 47.8).put("lng", 13.04));
    }

    @Test
    public void ohneFreigabeKeinOrtUndHinweis() throws Exception {
        JSONObject r = new JSONObject().put("enabled", true).put("current", sitzung());
        at.greenchili.surfmate.server.WoFreigabeZugang.anwenden(r, false, null);
        String a = WhereCommandHandler.antwort(r, CTX);
        assertFalse(a, a.contains("Hauptplatz"));
        assertFalse(a, a.contains("47.8"));
        assertFalse(a, a.contains("osm_link"));
        assertTrue(a, a.contains("parking.where.counts|2|0"));
        assertTrue(a, a.endsWith("parking.where.position_off"));

        JSONObject leer = new JSONObject().put("enabled", true);
        at.greenchili.surfmate.server.WoFreigabeZugang.anwenden(leer, false, new JSONObject().put("lat", 1.0).put("lon", 2.0));
        assertFalse("ohne Freigabe nie die aktuelle Position", leer.has("jetzt"));
        assertEquals("parking.where.position_off", WhereCommandHandler.antwort(leer, CTX));
    }

    @Test
    public void mitFreigabeOrtUndJetztPosition() throws Exception {
        JSONObject r = new JSONObject().put("enabled", true).put("current", sitzung());
        at.greenchili.surfmate.server.WoFreigabeZugang.anwenden(r, true, null);
        String a = WhereCommandHandler.antwort(r, CTX);
        assertTrue(a, a.contains("Hauptplatz 1"));
        assertTrue(a, a.contains("osm_link"));
        assertFalse(a, a.contains("position_off"));

        JSONObject ohneParken = new JSONObject().put("enabled", false);
        at.greenchili.surfmate.server.WoFreigabeZugang.anwenden(ohneParken, true, new JSONObject().put("lat", 47.8).put("lon", 13.04));
        String b = WhereCommandHandler.antwort(ohneParken, CTX);
        assertTrue(b, b.startsWith("parking.where.now"));
        assertTrue(b, b.contains("47.8"));
    }

    @Test
    public void aeltererDaemonOhneFeldBleibtWieBisher() throws Exception {
        JSONObject r = new JSONObject().put("enabled", true).put("current", sitzung());
        String a = WhereCommandHandler.antwort(r, CTX);
        assertTrue(a.contains("Hauptplatz 1"));
        assertEquals("parking.where.disabled", WhereCommandHandler.antwort(new JSONObject(), CTX));
    }
}
