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
 * 0.1.38.14 P2 Abstell-Meldung: jeder Abbruch geloggt, PARKING gepuffert, erst nach Senden/Puffern gemeldet,
 * onOnly sendet vor parkTerminate, HA meldet bei Zündung aus keinen Gang „P“, 12-V-Schlaf bis Zündung an.
 */
public class AbstellMeldungP2Test {

    private static String lies(String pfad) throws Exception {
        File b = new File("src/main").isDirectory() ? new File(".") : new File("app");
        return new String(Files.readAllBytes(new File(b, pfad).toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    public void parkingWirdGepuffert() {
        assertTrue(TelegramNotifier.spoolbar(TelegramNotifier.Category.PARKING));
        assertTrue(TelegramNotifier.spoolbar(TelegramNotifier.Category.CRITICAL));
        assertFalse(TelegramNotifier.spoolbar(TelegramNotifier.Category.MOTION));
    }

    @Test
    public void erstNachSendenOderPuffernGemeldet() throws Exception {
        assertEquals(TelegramNotifier.Versand.GESENDET, TelegramNotifier.versand(new JSONObject().put("status", "ok"), false));
        assertEquals(TelegramNotifier.Versand.AUSGELASSEN,
                TelegramNotifier.versand(new JSONObject().put("status", "skipped"), false));
        assertEquals(TelegramNotifier.Versand.FEHLER, TelegramNotifier.versand(new JSONObject().put("status", "error"), false));
        assertEquals(TelegramNotifier.Versand.GEPUFFERT, TelegramNotifier.versand(null, true));
        assertEquals(TelegramNotifier.Versand.FEHLER, TelegramNotifier.versand(null, false));
        assertTrue(AbschaltBericht.alsGemeldet(TelegramNotifier.Versand.GESENDET));
        assertTrue(AbschaltBericht.alsGemeldet(TelegramNotifier.Versand.GEPUFFERT));
        assertTrue(AbschaltBericht.alsGemeldet(TelegramNotifier.Versand.AUSGELASSEN));
        assertFalse(AbschaltBericht.alsGemeldet(TelegramNotifier.Versand.FEHLER));
    }

    @Test
    public void vorParkEndeWartetNurDenRest() {
        long aus = 1_000_000L;
        assertEquals(AbschaltBericht.VERZOEGERUNG_MS, AbschaltBericht.restWarteMs(aus, aus));
        assertEquals(30_000L, AbschaltBericht.restWarteMs(aus, aus + AbschaltBericht.VERZOEGERUNG_MS - 30_000L));
        assertEquals(0L, AbschaltBericht.restWarteMs(aus, aus + 10 * AbschaltBericht.VERZOEGERUNG_MS));
        assertEquals(AbschaltBericht.VERZOEGERUNG_MS, AbschaltBericht.restWarteMs(aus, aus - 999_999L));
    }

    @Test
    public void ohneOffenenNachlaufPassiertNichts() {
        AbschaltBericht.zuruecksetzenFuerTest();
        AbschaltBericht.nachlaufEinmal(123L, true);   // kein offener Nachlauf: kein Lauf
        AbschaltBericht.vorParkEnde();                // kehrt sofort zurück, kein Warten
    }

    /** Quellvertrag: Logzeile je Abbruch, Vermerk erst nach dem Versand, onOnly ruft vorParkEnde vor dem Beenden. */
    @Test
    public void quellvertrag() throws Exception {
        String b = lies("src/main/java/at/greenchili/surfmate/telegram/AbschaltBericht.java");
        int a = b.indexOf("private static void nachlauf(long ausMs, boolean bericht)");
        String n = b.substring(a, b.indexOf("\n    }\n", a));
        for (String g : new String[] {"Zuendung inzwischen wieder an", "Schalter telegram.abschaltBericht aus",
                "kein Telegram-Bot eingerichtet", "Fahrten-Datenbank nicht bereit", "kein Zuendung-an in diesem Prozess",
                "keine ungemeldete Fahrt", "Fahrt zu kurz", "waehrend des Nachfassens", "Fehler im Nachlauf"}) {
            assertTrue(g, n.contains(g));
        }
        int senden = n.indexOf("sendMessageJetzt(text, KATEGORIE)");
        int vermerk = n.indexOf("if (alsGemeldet(v)) merkeGemeldet(d.letzteFahrtId);");
        assertTrue(senden > 0 && vermerk > senden);
        String cam = lies("src/main/java/at/greenchili/surfmate/daemon/CameraDaemon.java");
        int t = cam.indexOf("private static boolean parkTerminate(long expectedGeneration)");
        int v = cam.indexOf("AbschaltBericht.vorParkEnde();", t);
        int s = cam.indexOf("final boolean supersededAfterDrain;", t);
        assertTrue(t > 0 && v > t && v < s);
    }

    @Test
    public void haMeldetBeiZuendungAusKeinP() throws Exception {
        String m = lies("src/main/java/at/greenchili/surfmate/mqtt/MqttConnectionManager.java");
        assertTrue(m.contains("static final String GEAR_UNBEKANNT = \"UNKNOWN\";"));
        int a = m.indexOf("if (!accOnGear || GearMonitor.gangVeraltet()) {");
        assertTrue(a > 0 && m.indexOf("payload.put(\"gear\", GEAR_UNBEKANNT);", a) > a);
        assertTrue("wie /status", lies("src/main/java/at/greenchili/surfmate/server/HttpServer.java")
                .contains("GearMonitor.gangVeraltet() ? \"UNKNOWN\""));
    }

    @Test
    public void zwoelfVoltSchlafBisZuendungAn() throws Exception {
        assertTrue(lies("src/main/java/at/greenchili/surfmate/ui/daemon/DaemonStartupManager.kt")
                .contains("'disabled by 12-V-Schlaf'*) echo STOPPED;;"));
        String acc = lies("src/main/java/at/greenchili/surfmate/daemon/AccSentryDaemon.java");
        int a = acc.indexOf("private static boolean stopTelegramDaemonIfAutoStarted(");
        assertTrue(acc.indexOf("raeumeTelegram12vSchlaf();", a) > a);
        assertTrue(acc.contains("r.startsWith(at.greenchili.surfmate.telegram.config.BotSchlaf12v.SPERR_GRUND)"));
    }
}
