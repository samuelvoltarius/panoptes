package at.greenchili.surfmate.recording;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.notifications.NotificationEvent;
import at.greenchili.surfmate.telegram.TelegramNotifier;
import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.Calendar;
import java.util.Iterator;

/**
 * 0.1.28 P1 „Nachricht nach Ereignis“ (Kern A): eigene Kategorie „Ereignisse“ statt CRITICAL, dazu Handy-Push.
 * Fehler aus 0.1.27: die Ereignis-Meldung lief als CRITICAL – wer „Abschreckung und Alarme“ ausschaltete, verlor sie.
 */
public class EreignisMeldungTest {

    private static final String SRC = "src/main/java/at/greenchili/surfmate/";

    private static String quelle(String rel) throws Exception {
        return Quelltext.lies(Paths.get(SRC + rel));
    }

    private static long zeit(int h, int m) {
        Calendar c = Calendar.getInstance();
        c.set(2026, Calendar.OCTOBER, 29, h, m, 0);
        return c.getTimeInMillis();
    }

    @Test
    public void pushHatEigeneKategorieZielUndKeinenOrt() throws Exception {
        long t = zeit(10, 35);
        NotificationEvent e = EreignisMeldung.pushEreignis(t);
        assertEquals("ereignis.gesichert", e.category);
        assertEquals(NotificationEvent.Severity.WARN, e.severity);
        assertEquals("/local/waechter.html", e.clickUrl);
        assertEquals("ereignis", e.data.getString("ziel"));
        assertEquals(t, e.data.getLong("zeit"));
        // Nur ziel + zeit: kein Ort, kein Dateiname, kein Video (Ereignis-Clips nie nach außen).
        Iterator<String> it = e.data.keys();
        int n = 0;
        while (it.hasNext()) {
            String k = it.next();
            assertTrue(k, k.equals("ziel") || k.equals("zeit"));
            n++;
        }
        assertEquals(2, n);
        assertTrue(e.title, e.title.contains("10:35"));
        JSONObject p = e.toPayloadJson();
        assertFalse(p.toString(), p.toString().contains("lat") || p.toString().contains(".mp4"));
    }

    @Test
    public void telegramTextNurUhrzeit() {
        String s = EreignisMeldung.telegramText(zeit(7, 5));
        assertTrue(s, s.contains("07:05"));
        assertFalse(s, s.contains("{0}"));
    }

    @Test
    public void telegramKategorieIstNichtCritical() {
        assertEquals("EREIGNIS", EreignisMeldung.TELEGRAM_KATEGORIE);
        assertNotNull(TelegramNotifier.Category.valueOf(EreignisMeldung.TELEGRAM_KATEGORIE));
    }

    /** Der Monitor schickt nicht mehr über CRITICAL und veröffentlicht den Push am Bus. */
    @Test
    public void monitorNutztEigeneKategorieUndBus() throws Exception {
        String m = quelle("recording/EreignisMonitor.java");
        int a = m.indexOf("private void gesichert(");
        int b = m.indexOf("private static void meldungAmDisplay");
        String g = m.substring(a, b);
        assertFalse(g, g.contains("\"CRITICAL\""));
        assertTrue(g, g.contains("EreignisMeldung.TELEGRAM_KATEGORIE"));
        assertTrue(g, g.contains("NotificationBus.get().publish(") && g.contains("EreignisMeldung.pushEreignis("));
    }

    /** Beide Telegram-Pforten kennen EREIGNIS – sonst fiele es im Daemon auf criticalAlerts zurück. */
    @Test
    public void telegramPfortenKennenEreignis() throws Exception {
        String n = quelle("telegram/TelegramNotifier.java");
        assertTrue(n.contains("case EREIGNIS:") && n.contains("EreignisClip.telegram("));
        String d = quelle("daemon/TelegramBotDaemon.java");
        int a = d.indexOf("private static boolean isCategoryEnabled(");
        int b = d.indexOf("static boolean isQuietNow()");
        assertTrue(d.substring(a, b).contains("case \"EREIGNIS\":     return ereignisTelegramAn();"));
        int q = d.indexOf("static boolean passesQuiet(");
        assertTrue(d.substring(q, q + 900).contains("case \"EREIGNIS\":"));
    }

    /** Telegram geht direkt (eigener Schalter) – die TelegramSink darf ereignis.* nicht noch einmal schicken. */
    @Test
    public void telegramSinkLaesstEreignisAus() throws Exception {
        String s = quelle("notifications/sinks/TelegramSink.java");
        int ausnahme = s.indexOf("startsWith(\"ereignis.\")");
        int kritisch = s.indexOf("TelegramNotifier.sendMessage(msg.toString(), \"CRITICAL\")");
        assertTrue(ausnahme > 0 && kritisch > ausnahme);
    }

    @Test
    public void registryHatKategorieEreignisse() throws Exception {
        JSONArray cats = new JSONObject(Quelltext.lies(Paths.get("src/main/assets/notifications-categories.json")))
                .getJSONArray("categories");
        JSONObject e = null;
        for (int i = 0; i < cats.length(); i++) {
            if (cats.getJSONObject(i).getString("id").equals(EreignisMeldung.PUSH_KATEGORIE)) e = cats.getJSONObject(i);
        }
        assertNotNull(e);
        assertEquals("Ereignisse", e.getString("group"));
        assertTrue(e.getBoolean("defaultEnabled"));
        assertEquals("/local/waechter.html", e.getString("defaultClickUrl"));
    }

    /** Texte in allen 5 Sprachen, mit Platzhalter. */
    @Test
    public void texteInAllenSprachen() throws Exception {
        for (String sp : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject c = new JSONObject(Quelltext.lies(Paths.get("src/main/assets/server-i18n/" + sp + ".json")));
            assertTrue(sp, c.getJSONObject("telegram").getJSONObject("ereignis").getString("gesichert").contains("{0}"));
            assertTrue(sp, c.getJSONObject("notifications").getString("ereignis_title").contains("{0}"));
            assertFalse(sp, c.getJSONObject("notifications").getString("ereignis_body").isEmpty());
            JSONObject w = new JSONObject(Quelltext.lies(Paths.get("src/main/assets/web/i18n/" + sp + ".json")));
            assertFalse(sp, w.getJSONObject("notif").getJSONObject("group").getString("ereignisse").isEmpty());
        }
    }
}
