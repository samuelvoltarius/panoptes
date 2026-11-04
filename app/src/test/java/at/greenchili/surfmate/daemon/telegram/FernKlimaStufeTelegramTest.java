package at.greenchili.surfmate.daemon.telegram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 0.1.38.7 (Handy-App-Befund): {@code fern.stufe} ist die Balkenstufe 1–17 (Mitte 9). Im Text steht der Abstand zur Mitte:
 * Stufe 12 heißt „Heizen Stufe 3“, nicht „Heizen Stufe 12“. Telegram {@code /status} (GET_FERNKLIMA).
 */
public class FernKlimaStufeTelegramTest {

    /** Gibt Schlüssel und Argumente zurück; nur {@code tr} wird gebraucht. */
    static class Ctx implements CommandContext {
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
        @Override public JSONObject daemonHttp(String method, String path, JSONObject body) { return null; }
    }

    private static JSONObject fern(int stufe, String art) throws Exception {
        return new JSONObject().put("aktiv", true).put("laut", "befehl").put("gestartet", 1_800_000_000_000L)
                .put("bis", 1_800_000_600_000L).put("stufe", stufe).put("art", art).put("beendet", false);
    }

    @Test
    public void stufeZwoelfIstHeizenStufeDrei() throws Exception {
        String z = SurveillanceCommandHandler.fernKlimaLine(fern(12, "heizen"), new Ctx());
        assertTrue(z, z.startsWith("status.fernklima_laeuft|"));
        assertTrue("Stufe 12 = Heizen 3, nicht 12: " + z, z.endsWith("status.fern_heizen|3"));
    }

    @Test
    public void kuehlenUndNeutralUndGrenzen() throws Exception {
        Ctx c = new Ctx();
        assertTrue(SurveillanceCommandHandler.fernKlimaLine(fern(6, "kuehlen"), c).endsWith("status.fern_kuehlen|3"));    // Stufe 6 = Kühlen 3
        assertTrue(SurveillanceCommandHandler.fernKlimaLine(fern(1, "kuehlen"), c).endsWith("status.fern_kuehlen|8"));    // Stufe 1 = Kühlen 8
        assertTrue(SurveillanceCommandHandler.fernKlimaLine(fern(17, "heizen"), c).endsWith("status.fern_heizen|8"));     // Stufe 17 = Heizen 8
        assertTrue(SurveillanceCommandHandler.fernKlimaLine(fern(10, "heizen"), c).endsWith("status.fern_heizen|1"));     // Stufe 10 = Heizen 1
        assertTrue(SurveillanceCommandHandler.fernKlimaLine(fern(9, "neutral"), c).endsWith("status.fern_neutral"));
        // ohne Stufe (anderes Modell) keine Zahl erfinden
        JSONObject ohne = fern(12, "heizen");
        ohne.put("stufe", JSONObject.NULL).put("art", JSONObject.NULL);
        assertTrue(SurveillanceCommandHandler.fernKlimaLine(ohne, c).endsWith("|"));
        assertNull(SurveillanceCommandHandler.fernKlimaLine(new JSONObject(), c));
        assertNull(SurveillanceCommandHandler.fernKlimaLine(null, c));
    }
}
