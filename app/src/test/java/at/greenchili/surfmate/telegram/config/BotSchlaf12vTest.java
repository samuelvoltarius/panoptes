package at.greenchili.surfmate.telegram.config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 0.1.38.12 „Bot schläft bei 12-V-Stopp“: nur der fürs Abstellen gestartete Bot, nur bei frischem LOW der
 * Wächter-12-V-Auswertung, EINE Nachricht vorher, Sperrmarke gilt als Maschinen-Stopp (ACC an / nächstes Abstellen
 * starten normal).
 */
public class BotSchlaf12vTest {

    private static final long JETZT = 1_800_000_000_000L;

    private static JSONObject stand(boolean low, long alterMs) throws Exception {
        return new JSONObject().put("zyklusMs", JETZT - 3_600_000L).put("letzteMessungMs", JETZT - alterMs).put("low", low);
    }

    @Test
    public void nurFrischesLowZaehlt() throws Exception {
        assertTrue(BotSchlaf12v.low(stand(true, 60_000L), JETZT));
        assertFalse(BotSchlaf12v.low(stand(false, 60_000L), JETZT));
        assertFalse("alt", BotSchlaf12v.low(stand(true, BotSchlaf12v.FRISCH_MS + 1), JETZT));
        assertFalse("Zukunft", BotSchlaf12v.low(stand(true, -60_000L), JETZT));
        assertFalse(BotSchlaf12v.low(null, JETZT));
        assertFalse(BotSchlaf12v.low(new JSONObject().put("low", true), JETZT));
    }

    @Test
    public void jederAbgestelltLaufendeBotSchlaeft() throws Exception {
        assertTrue(BotSchlaf12v.sollSchlafen(stand(true, 0L), JETZT, true, false));
        // 0.1.38.14: auch der dauerhaft eingeschaltete Bot schläft
        assertTrue("dauerhaft eingeschaltet", BotSchlaf12v.sollSchlafen(stand(true, 0L), JETZT, true, true));
        assertTrue("nur dauerhaft", BotSchlaf12v.sollSchlafen(stand(true, 0L), JETZT, false, true));
        assertFalse("läuft abgestellt gar nicht", BotSchlaf12v.sollSchlafen(stand(true, 0L), JETZT, false, false));
        assertFalse("12 V gut", BotSchlaf12v.sollSchlafen(stand(false, 0L), JETZT, true, false));
    }

    @Test
    public void sperrmarkeIstMaschinenStoppNutzerStoppNicht() {
        assertTrue(BotSchlaf12v.maschinenStopp(BotSchlaf12v.SPERR_GRUND + " at Thu Nov 05 18:00:00 CET 2026"));
        assertTrue(BotSchlaf12v.maschinenStopp("disabled by ACC-on at x"));
        assertTrue(BotSchlaf12v.maschinenStopp("disabled by stopAllDaemons sweep"));
        assertFalse(BotSchlaf12v.maschinenStopp("disabled by ui at x"));
        assertFalse(BotSchlaf12v.maschinenStopp("disabled by telegram at x"));
        assertFalse(BotSchlaf12v.maschinenStopp(null));
    }

    private static String lies(String pfad) throws Exception {
        File b = new File("src/main").isDirectory() ? new File(".") : new File("app");
        return new String(Files.readAllBytes(new File(b, pfad).toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** Quellvertrag: Nachricht → Sperrmarke → Wachhund weg → Ende; App und AccSentry lesen dieselbe Liste. */
    @Test
    public void ablaufImBotUndGleicheListeUeberall() throws Exception {
        String d = lies("src/main/java/at/greenchili/surfmate/daemon/TelegramBotDaemon.java");
        int a = d.indexOf("private static void botSchlaf12v(long now)");
        assertTrue(a > 0);
        int e = d.indexOf("\n    }\n", a);
        String f = d.substring(a, e);
        int nachricht = f.indexOf("tr(\"erreichbar.schlaf_12v\")");
        int sperre = f.indexOf("BotSchlaf12v.SPERR_GRUND");
        int wachhund = f.indexOf("TELEGRAM_WACHHUND)).delete()");
        int ende = f.indexOf("System.exit(0);");
        assertTrue(nachricht > 0 && nachricht < sperre && sperre < wachhund && wachhund < ende);
        assertTrue("Quelle ist der Wächter-Stand", f.contains("GuardStopMonitor.v12StandRoh()"));
        assertTrue("im Poll-Takt", d.contains("botSchlaf12v(System.currentTimeMillis());"));
        assertTrue(lies("src/main/java/at/greenchili/surfmate/daemon/AccSentryDaemon.java")
                .contains("boolean machineStop = at.greenchili.surfmate.telegram.config.BotSchlaf12v.maschinenStopp(reason);"));
        assertTrue(lies("src/main/java/at/greenchili/surfmate/ui/daemon/DaemonStopGate.kt")
                .contains("Regex(\"ACC-on|stopAllDaemons\")"));   // 0.1.38.14: in der App Stopp bis Zündung an
    }

    @Test
    public void nachrichtInAllenSprachen() throws Exception {
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            String t = new JSONObject(lies("src/main/assets/server-i18n/" + l + ".json")).getJSONObject("telegram")
                    .getJSONObject("erreichbar").getString("schlaf_12v");
            assertTrue(l, t.startsWith("🔋") && t.length() > 30);
        }
    }
}
