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
 * 0.1.38.12 T2: „Auch bei abgestelltem Auto erreichbar“ ab Werk an, sobald gekoppelt; bewusst aus bleibt aus;
 * Bestand bekommt die Nachricht einmal, nur wenn es dadurch neu an ist; Kopplungsnachricht trägt den Hinweis.
 */
public class ErreichbarStandardTest {

    private static JSONObject gekoppelt() throws Exception {
        return new JSONObject().put(UnifiedTelegramConfig.K_OWNER_CHAT_ID, 4711L).put("botUsername", "x");
    }

    @Test
    public void standardAnNurWennGekoppelt() throws Exception {
        assertTrue(ErreichbarStandard.an(gekoppelt()));
        assertFalse("ohne Kopplung kein Bot beim Abstellen", ErreichbarStandard.an(new JSONObject().put("botUsername", "x")));
        assertFalse(ErreichbarStandard.an(null));
    }

    @Test
    public void bewusstAusBleibtAus() throws Exception {
        assertFalse(ErreichbarStandard.an(gekoppelt().put(UnifiedTelegramConfig.K_AUTO_START, false)));
        assertTrue(ErreichbarStandard.an(new JSONObject().put(UnifiedTelegramConfig.K_AUTO_START, true)));
        assertEquals(MedienStandard.STILL,
                ErreichbarStandard.entscheide(gekoppelt().put(UnifiedTelegramConfig.K_AUTO_START, false)));
    }

    @Test
    public void bestandEinmalUndNurWennNeuAn() throws Exception {
        JSONObject t = gekoppelt();
        assertEquals(MedienStandard.HINWEIS, ErreichbarStandard.entscheide(t));
        t.put(ErreichbarStandard.K_HINWEIS, true);
        assertEquals(MedienStandard.NICHTS, ErreichbarStandard.entscheide(t));
        // lief schon dauerhaft (App-Schalter): nichts neu an, keine Nachricht
        assertEquals(MedienStandard.STILL,
                ErreichbarStandard.entscheide(gekoppelt().put(UnifiedTelegramConfig.K_DAEMON_ENABLED, true)));
        assertEquals(MedienStandard.STILL, ErreichbarStandard.entscheide(new JSONObject().put("botUsername", "x")));
        assertEquals(MedienStandard.NICHTS, ErreichbarStandard.entscheide(new JSONObject()));
    }

    private static String lies(String pfad) throws Exception {
        File b = new File("src/main").isDirectory() ? new File(".") : new File("app");
        return new String(Files.readAllBytes(new File(b, pfad).toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    public void dienstUndKopplung() throws Exception {
        String d = lies("src/main/java/at/greenchili/surfmate/daemon/TelegramBotDaemon.java");
        assertTrue(d.contains("erreichbarStandardHinweis(section, System.currentTimeMillis());"));
        int a = d.indexOf("private static void erreichbarStandardHinweis(");
        String f = d.substring(a, d.indexOf("\n    }\n", a));
        int senden = f.indexOf("tr(\"erreichbar.standard_an\")");
        int merker = f.indexOf("ErreichbarStandard.K_HINWEIS, true");
        assertTrue(senden > 0 && merker > senden && f.indexOf("return;", senden) < merker);
        assertTrue(f.contains("\"cmd:/erreichbar aus\""));
        int k = d.indexOf("boolean erreichbarStandard = ");
        int h = d.indexOf("erfolg = erfolg + \"\\n\\n\" + tr(\"erreichbar.hinweis_kopplung\");", k);
        int m = d.indexOf("ErreichbarStandard.K_HINWEIS, true);", k);
        int s = d.indexOf("sendMessage(chatId, erfolg);", k);
        assertTrue(k > 0 && h > k && m > h && s > m);
        String u = lies("src/main/java/at/greenchili/surfmate/telegram/config/UnifiedTelegramConfig.java");
        assertTrue(u.contains("return ErreichbarStandard.an(load());"));
        assertTrue(u.contains("return ErreichbarStandard.an(c) || c.optBoolean(K_DAEMON_ENABLED, false);"));
    }

    @Test
    public void texteInAllenSprachen() throws Exception {
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject e = new JSONObject(lies("src/main/assets/server-i18n/" + l + ".json")).getJSONObject("telegram")
                    .getJSONObject("erreichbar");
            assertTrue(l, e.getString("standard_an").contains("/erreichbar aus"));
            assertTrue(l, e.getString("hinweis_kopplung").contains("/erreichbar aus") && e.getString("hinweis_kopplung").contains("12"));
            assertFalse(l, e.getString("btn_aus").isEmpty());
            assertTrue(l, new JSONObject(lies("src/main/assets/web/i18n/" + l + ".json")).getJSONObject("tgp")
                    .getString("autostart_hint").contains("12"));
        }
    }
}
