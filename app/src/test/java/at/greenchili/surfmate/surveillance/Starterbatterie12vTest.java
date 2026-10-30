package at.greenchili.surfmate.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.notifications.NotificationEvent;
import at.greenchili.surfmate.surveillance.Starterbatterie12v.Merker;
import at.greenchili.surfmate.surveillance.Starterbatterie12v.Stufe;
import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 0.1.28 P8 (Kern A): 12-V-Warnung vor dem Startproblem. Fehler aus 0.1.27: {@code notifyBattery12v} hatte keinen
 * Aufrufer, „Starterbatterie niedrig“ kam nie.
 */
public class Starterbatterie12vTest {

    private static final long MIN = 60_000L;
    private static final long TAG = 24 * 60 * MIN;
    private static final long T0 = 1_800_000_000_000L;

    @After
    public void melderZurueck() {
        GuardStopMonitor.melder12v = GuardStopMonitor::melden12v;
    }

    // ---------------------------------------------------------------- Bewertung

    @Test
    public void bleiKnappUeberSchwelleIstVorwarnung() {
        assertEquals(Stufe.KEINE, Starterbatterie12v.bewerten(12.6, 12.2, "blei", null, "2026-10-29"));
        assertEquals(Stufe.KEINE, Starterbatterie12v.bewerten(12.4, 12.2, "blei", null, "2026-10-29"));
        assertEquals(Stufe.VOR, Starterbatterie12v.bewerten(12.35, 12.2, "blei", null, "2026-10-29"));
        assertEquals(Stufe.NIEDRIG, Starterbatterie12v.bewerten(12.1, 12.2, "blei", null, "2026-10-29"));
    }

    @Test
    public void lfpHatKleinenAbstand() {
        assertEquals(Stufe.KEINE, Starterbatterie12v.bewerten(13.1, 12.8, "lfp", null, "2026-10-29"));
        assertEquals(Stufe.VOR, Starterbatterie12v.bewerten(12.9, 12.8, "lfp", null, "2026-10-29"));
        assertEquals(Stufe.NIEDRIG, Starterbatterie12v.bewerten(12.7, 12.8, "lfp", null, "2026-10-29"));
    }

    @Test
    public void unbekannteWerteWarnenNie() {
        assertEquals(Stufe.KEINE, Starterbatterie12v.bewerten(Double.NaN, 12.8, "lfp", null, "2026-10-29"));
        assertEquals(Stufe.KEINE, Starterbatterie12v.bewerten(0.0, 12.8, "lfp", null, "2026-10-29"));
        assertEquals(Stufe.KEINE, Starterbatterie12v.bewerten(12.0, 0.0, "lfp", null, "2026-10-29"));
    }

    /** LFP: Ruhe 13,1 V liegt noch weit genug weg, fällt aber jeden Tag ~0,1 V → Vorwarnung über den Trend. */
    @Test
    public void lfpTrendWarntVorDerSchwelle() throws Exception {
        JSONObject tage = new JSONObject()
                .put("2026-10-25", 13.45).put("2026-10-26", 13.35).put("2026-10-27", 13.25)
                .put("2026-10-28", 13.15).put("2026-10-29", 13.05);
        double s = Starterbatterie12v.trend(tage, "2026-10-29");
        assertEquals(-0.1, s, 1e-6);
        assertEquals(Stufe.VOR, Starterbatterie12v.bewerten(13.05, 12.8, "lfp", tage, "2026-10-29"));
    }

    @Test
    public void stabilerVerlaufWarntNicht() throws Exception {
        JSONObject tage = new JSONObject()
                .put("2026-10-26", 13.2).put("2026-10-27", 13.1).put("2026-10-28", 13.2).put("2026-10-29", 13.1);
        assertEquals(Stufe.KEINE, Starterbatterie12v.bewerten(13.1, 12.8, "lfp", tage, "2026-10-29"));
    }

    @Test
    public void trendBrauchtDreiFrischeTage() throws Exception {
        JSONObject zwei = new JSONObject().put("2026-10-28", 13.4).put("2026-10-29", 13.0);
        assertTrue(Double.isNaN(Starterbatterie12v.trend(zwei, "2026-10-29")));
        // Alte Tage (älter als 7) zählen nicht.
        JSONObject alt = new JSONObject().put("2026-10-01", 14.0).put("2026-10-02", 13.9)
                .put("2026-10-28", 13.4).put("2026-10-29", 13.0);
        assertTrue(Double.isNaN(Starterbatterie12v.trend(alt, "2026-10-29")));
        assertTrue(Double.isNaN(Starterbatterie12v.trend(new JSONObject().put("kaputt", 1.0), "2026-10-29")));
    }

    @Test
    public void schwellenAusWaechterStoppSonstChemie() {
        double[] a = Starterbatterie12v.schwellen(12.4, 12.7, "lfp");
        assertEquals(12.4, a[0], 1e-9);
        assertEquals(12.7, a[1], 1e-9);
        double[] b = Starterbatterie12v.schwellen(0.0, 0.0, "lfp");   // 12-V-Regel im Wächter-Stopp aus
        assertEquals(12.8, b[0], 1e-9);
        assertEquals(13.1, b[1], 1e-9);
    }

    // ---------------------------------------------------------------- Merker (höchstens einmal je Stufe und Tag)

    @Test
    public void merkerMeldetEinmalJeStufeUndTag() {
        Merker m = Merker.leer();
        m = m.nach(Stufe.VOR, false, T0);
        assertTrue(m.melden);
        m = m.nach(Stufe.VOR, false, T0 + 60 * MIN);
        assertFalse(m.melden);
        m = m.nach(Stufe.NIEDRIG, false, T0 + 61 * MIN);          // höhere Stufe: sofort
        assertTrue(m.melden);
        m = m.nach(Stufe.VOR, false, T0 + 120 * MIN);             // leicht erholt: still, Stufe bleibt
        assertFalse(m.melden);
        assertEquals(Stufe.NIEDRIG, m.stufe);
        m = m.nach(Stufe.NIEDRIG, false, T0 + 61 * MIN + TAG);    // nach 24 h Erinnerung
        assertTrue(m.melden);
    }

    @Test
    public void erholungLoeschtMerker() {
        Merker m = Merker.leer().nach(Stufe.VOR, false, T0);
        m = m.nach(Stufe.KEINE, false, T0 + MIN);
        assertEquals(Stufe.VOR, m.stufe);
        m = m.nach(Stufe.KEINE, true, T0 + 2 * MIN);
        assertEquals(Stufe.KEINE, m.stufe);
        assertTrue(m.nach(Stufe.VOR, false, T0 + 3 * MIN).melden);
    }

    @Test
    public void merkerUeberlebtNeustart() {
        Merker m = Merker.leer().nach(Stufe.NIEDRIG, false, T0);
        Merker z = Merker.aus(m.json());
        assertEquals(Stufe.NIEDRIG, z.stufe);
        assertEquals(T0, z.zeit);
        assertFalse(z.melden);
        assertEquals(Stufe.KEINE, Merker.aus(new JSONObject()).stufe);
        assertEquals(Stufe.KEINE, Merker.aus(null).stufe);
    }

    // ---------------------------------------------------------------- Meldung

    @Test
    public void pushStufen() throws Exception {
        NotificationEvent v = Starterbatterie12v.pushEreignis(Stufe.VOR, 12.34, "de");
        assertEquals("vehicle.health.battery12v", v.category);
        assertEquals(NotificationEvent.Severity.WARN, v.severity);
        assertTrue(v.title, v.title.contains("12,3"));
        assertEquals("vor", v.data.getString("stufe"));
        NotificationEvent n = Starterbatterie12v.pushEreignis(Stufe.NIEDRIG, 12.1, "en");
        assertEquals(NotificationEvent.Severity.CRITICAL, n.severity);
        assertTrue(n.title, n.title.contains("12.1"));
        assertEquals("niedrig", Starterbatterie12v.telegramStufe(Stufe.NIEDRIG));
        assertEquals("vor", Starterbatterie12v.telegramStufe(Stufe.VOR));
    }

    // ---------------------------------------------------------------- Anbindung im Wächter-Stopp

    private static final class Still implements GuardStopMonitor.Surveillance {
        public boolean isRunning() { return false; }
        public void stop() { }
        public void start() { }
    }

    private static GuardStopPolicy.Config cfg(double below, double resume) {
        GuardStopPolicy.Config c = new GuardStopPolicy.Config();
        c.v12Below = below;
        c.v12Resume = resume;
        return c;
    }

    @Test
    public void monitorMeldetNurOberkanteUndNurEinmal() {
        List<String> gemeldet = new ArrayList<>();
        GuardStopMonitor.melder12v = (s, v) -> gemeldet.add(s + "@" + Math.round(v * 10) / 10.0);
        GuardStopMonitor m = new GuardStopMonitor(new Still(), Battery12vEvaluator.Profile.DEFAULT);
        m.merker12vForTest(Merker.leer());
        long park = T0;
        // 11 h geparkt, kein Nachladen gesehen: Oberkante noch nicht sicher → nichts
        long t = park + 11 * MIN;
        for (; t <= park + 11 * 60 * MIN; t += 5 * MIN) m.v12ForTest(12.3, t, park, 12.2, 12.5);
        m.warnung12vPruefen(t, cfg(12.2, 12.5), "blei", null);
        assertEquals(0, gemeldet.size());
        // 13 h: Oberkante 12,3 V knapp über 12,2 → Vorwarnung, genau einmal
        for (; t <= park + 13 * 60 * MIN; t += 5 * MIN) m.v12ForTest(12.3, t, park, 12.2, 12.5);
        m.warnung12vPruefen(t, cfg(12.2, 12.5), "blei", null);
        m.warnung12vPruefen(t + MIN, cfg(12.2, 12.5), "blei", null);
        assertEquals("[VOR@12.3]", gemeldet.toString());
    }

    @Test
    public void monitorLadenderWandlerWarntNicht() {
        List<String> gemeldet = new ArrayList<>();
        GuardStopMonitor.melder12v = (s, v) -> gemeldet.add(s.name());
        GuardStopMonitor m = new GuardStopMonitor(new Still(), Battery12vEvaluator.Profile.DEFAULT);
        m.merker12vForTest(Merker.leer());
        long park = T0;
        m.v12ForTest(13.9, park + 69 * MIN, park, 12.2, 12.5);   // DC-DC lädt
        m.v12ForTest(13.9, park + 70 * MIN, park, 12.2, 12.5);
        m.warnung12vPruefen(park + 70 * MIN, cfg(12.2, 12.5), "blei", null);
        assertEquals(0, gemeldet.size());
    }

    /** Die verwaiste Telegram-Meldung hat jetzt einen Aufrufer, mit Stufe; der Daemon wählt den Text danach. */
    @Test
    public void notifyBattery12vHatAufrufer() throws Exception {
        String g = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/surveillance/GuardStopMonitor.java"));
        assertTrue(g.contains("TelegramNotifier.notifyBattery12v(volt, Starterbatterie12v.telegramStufe(stufe))"));
        assertTrue(g.contains("warnung12vPruefen(now, cfg,"));
        String d = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/daemon/TelegramBotDaemon.java"));
        assertTrue(d.contains("\"alerts.battery12v_sehr_niedrig\" : \"alerts.battery12v_low\""));
        String s = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/notifications/sinks/TelegramSink.java"));
        assertTrue(s.contains("event.category.equals(\"vehicle.health.battery12v\")"));
    }

    @Test
    public void texteInAllenSprachen() throws Exception {
        for (String sp : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject c = new JSONObject(Quelltext.lies(Paths.get("src/main/assets/server-i18n/" + sp + ".json")));
            assertTrue(sp, c.getJSONObject("telegram").getJSONObject("alerts").getString("battery12v_sehr_niedrig").contains("{0}"));
            JSONObject n = c.getJSONObject("notifications");
            for (String k : new String[] {"battery12v_vor_title", "battery12v_niedrig_title"}) assertTrue(sp + k, n.getString(k).contains("{0}"));
            for (String k : new String[] {"battery12v_vor_body", "battery12v_niedrig_body"}) assertFalse(sp + k, n.getString(k).isEmpty());
        }
    }
}
