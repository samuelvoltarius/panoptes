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
 * 0.1.39 S1 (Surf 06.11.: „Abgestellt“ 18:32 gespoolt, Netz 19:10, nach 38 min verworfen): Park-Meldungen werden bis
 * 12 h nachgeholt und als verspätet gekennzeichnet; Wächter-Fotos/Motion bleiben bei 30 min; jeder Verwurf geloggt.
 */
public class SpoolParkNachholenTest {

    private static JSONObject abgestellt() throws Exception {
        return new JSONObject().put("cmd", "sendMessage").put("category", "PARKING").put("text", "🅿️ Abgestellt");
    }

    @Test
    public void parkMeldungenBisZwoelfStunden() throws Exception {
        assertTrue(TelegramSpool.istPark(abgestellt()));
        assertTrue(TelegramSpool.istPark(new JSONObject().put("cmd", "notifyParking").put("title", "Abgestellt")));
        assertEquals(TelegramSpool.MAX_AGE_PARK_MS, TelegramSpool.maxAlterMs(abgestellt()));
        // der Surf-Fall: 38 min alt → wird jetzt nachgeholt
        assertTrue(38L * 60_000L <= TelegramSpool.maxAlterMs(abgestellt()));
        assertEquals(12L * 3_600_000L, TelegramSpool.MAX_AGE_PARK_MS);
    }

    @Test
    public void waechterFotosBleibenBeiDreissigMinuten() throws Exception {
        for (String c : new String[] {"notifyMotion", "notifyMotionFinalized", "notifyCritical"}) {
            JSONObject cmd = new JSONObject().put("cmd", c);
            assertFalse(c, TelegramSpool.istPark(cmd));
            assertEquals(c, TelegramSpool.MAX_AGE_MS, TelegramSpool.maxAlterMs(cmd));
        }
        assertEquals(TelegramSpool.MAX_AGE_MS,
                TelegramSpool.maxAlterMs(new JSONObject().put("cmd", "sendMessage").put("category", "CRITICAL")));
    }

    @Test
    public void verspaetetGekennzeichnet() throws Exception {
        JSONObject m = abgestellt();
        TelegramSpool.kennzeichne(m, 38L * 60_000L, "⏱ (verspätet, 18:32)");
        assertEquals("⏱ (verspätet, 18:32)\n🅿️ Abgestellt", m.getString("text"));
        JSONObject p = new JSONObject().put("cmd", "notifyParking").put("title", "Abgestellt");
        TelegramSpool.kennzeichne(p, 38L * 60_000L, "⏱ (verspätet, 18:32)");
        assertEquals("⏱ (verspätet, 18:32)\nAbgestellt", p.getString("title"));
        // frisch nachgeholt: kein Hinweis; Wächter-Foto: nie
        JSONObject f = abgestellt();
        TelegramSpool.kennzeichne(f, 60_000L, "⏱ (verspätet, 18:32)");
        assertEquals("🅿️ Abgestellt", f.getString("text"));
        JSONObject w = new JSONObject().put("cmd", "notifyMotion").put("text", "x");
        TelegramSpool.kennzeichne(w, 38L * 60_000L, "⏱ (verspätet, 18:32)");
        assertEquals("x", w.getString("text"));
    }

    private static String lies(String pfad) throws Exception {
        File b = new File("src/main").isDirectory() ? new File(".") : new File("app");
        return new String(Files.readAllBytes(new File(b, pfad).toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** Vertrag: Altersgrenze je Eintrag, jeder Verwurf mit Grund geloggt, Text in 5 Sprachen. */
    @Test
    public void vertrag() throws Exception {
        String s = lies("src/main/java/at/greenchili/surfmate/telegram/TelegramSpool.java");
        assertTrue(s.contains("if (stamped > 0 && age > maxAlterMs(cmd)) {"));
        assertFalse("kein pauschales 30-min-Limit mehr", s.contains("age > MAX_AGE_MS) {"));
        for (String g : new String[] {"Dropping unreadable spool entry", "Dropping stale spooled cmd",
                "Spool replay parse failed", "Could not claim spool entry", "Spool replay not delivered",
                "Spool replay send error", "Spool full"}) {
            assertTrue(g, s.contains(g));
        }
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            String t = new JSONObject(lies("src/main/assets/server-i18n/" + l + ".json")).getJSONObject("telegram")
                    .getJSONObject("spool").getString("verspaetet");
            assertTrue(l, t.contains("{0}"));
        }
    }
}
