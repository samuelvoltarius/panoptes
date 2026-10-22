package at.greenchili.surfmate.gesundheit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Paths;

/** Dauerbetrieb: jeder Ausfalltyp → richtige Stufe, Sperre, Backoff, Obergrenze, Neustart-fest. */
public class GesundheitTest {

    static final long T = 10_000_000L;

    /** Gesund: soll und ist scharf, Bilder frisch, KI da, MQTT frisch. */
    static Gesundheit.Fakten gesund(long now) {
        Gesundheit.Fakten f = new Gesundheit.Fakten();
        f.now = now;
        f.sollScharf = true;
        f.istScharf = true;
        f.letztesBild = now - 1_000;
        f.kiVerlangt = true;
        f.kiGeladen = true;
        f.mqttEingerichtet = true;
        f.mqttVerbunden = true;
        f.letzteTelemetrie = now - 30_000;
        return f;
    }

    static Gesundheit.Fakten nichtScharf(long now, long seit) {
        Gesundheit.Fakten f = gesund(now);
        f.istScharf = false;
        f.sollScharfSeit = seit;
        return f;
    }

    @Test
    public void healthyMeansNoFindingsNoAction() {
        Gesundheit g = new Gesundheit();
        Gesundheit.Entscheidung e = g.takt(gesund(T));
        assertTrue(e.gesund());
        assertEquals(Gesundheit.Stufe.KEINE, e.stufe);
    }

    @Test
    public void notArmedGetsGraceThenRearm() {
        Gesundheit g = new Gesundheit();
        assertTrue("Anlauf 1 min ist kein Befund", g.takt(nichtScharf(T, T - 60_000)).gesund());
        Gesundheit.Entscheidung e = g.takt(nichtScharf(T, T - 2 * 60_000));
        assertEquals(java.util.Arrays.asList("nicht_scharf"), e.befunde);
        assertEquals(Gesundheit.Stufe.SCHARF, e.stufe);
    }

    @Test
    public void noFramesOrMissingAiRestartDaemon() {
        Gesundheit.Fakten f = gesund(T);
        f.letztesBild = T - 61_000;
        Gesundheit.Entscheidung e = new Gesundheit().takt(f);
        assertTrue(e.befunde.contains("keine_bilder"));
        assertEquals(Gesundheit.Stufe.DAEMON, e.stufe);
        Gesundheit.Fakten k = gesund(T);
        k.kiGeladen = false;
        assertEquals(Gesundheit.Stufe.DAEMON, new Gesundheit().takt(k).stufe);
        Gesundheit.Fakten ohneKi = gesund(T);
        ohneKi.kiVerlangt = false;
        ohneKi.kiGeladen = false;
        assertTrue("KI nicht verlangt → kein Befund", new Gesundheit().takt(ohneKi).gesund());
        Gesundheit.Fakten aus = gesund(T);
        aus.istScharf = false;
        aus.sollScharf = false;
        aus.letztesBild = 0;
        assertTrue("Wächter gewollt aus → keine Bilder sind normal", new Gesundheit().takt(aus).gesund());
    }

    @Test
    public void mqttProblemsAreReportedNotHealed() {
        Gesundheit.Fakten f = gesund(T);
        f.mqttVerbunden = false;
        Gesundheit.Entscheidung e = new Gesundheit().takt(f);
        assertEquals(java.util.Arrays.asList("mqtt_getrennt"), e.befunde);
        assertEquals(Gesundheit.Stufe.KEINE, e.stufe);
        Gesundheit.Fakten t = gesund(T);
        t.letzteTelemetrie = T - 3 * 60_000;
        assertEquals(java.util.Arrays.asList("telemetrie_alt"), new Gesundheit().takt(t).befunde);
        Gesundheit.Fakten keinMqtt = gesund(T);
        keinMqtt.mqttEingerichtet = false;
        keinMqtt.mqttVerbunden = false;
        assertTrue("ohne MQTT-Verbindung eingerichtet kein Befund", new Gesundheit().takt(keinMqtt).gesund());
    }

    @Test
    public void ladderEscalatesWithBackoffUpToApp() {
        Gesundheit g = new Gesundheit();
        long seit = T - 10 * 60_000;
        assertEquals(Gesundheit.Stufe.SCHARF, g.takt(nichtScharf(T, seit)).stufe);
        assertEquals("abwarten", g.takt(nichtScharf(T + 60_000, seit)).grund);
        assertEquals(Gesundheit.Stufe.DAEMON, g.takt(nichtScharf(T + 2 * 60_000, seit)).stufe);
        assertEquals(Gesundheit.Stufe.KEINE, g.takt(nichtScharf(T + 7 * 60_000, seit)).stufe);
        assertEquals(Gesundheit.Stufe.APP, g.takt(nichtScharf(T + 12 * 60_000, seit)).stufe);
        assertEquals("nach APP 30 min Ruhe", Gesundheit.Stufe.KEINE, g.takt(nichtScharf(T + 30 * 60_000, seit)).stufe);
        assertEquals("bleibt bei APP", Gesundheit.Stufe.APP, g.takt(nichtScharf(T + 42 * 60_000, seit)).stufe);
        assertTrue(g.takt(gesund(T + 43 * 60_000)).gesund());
        assertEquals("gesund → Leiter von vorn", Gesundheit.Stufe.SCHARF,
                g.takt(nichtScharf(T + 50 * 60_000, T + 47 * 60_000)).stufe);
    }

    @Test
    public void noActionDuringDeployLockOrUpdate() {
        Gesundheit g = new Gesundheit();
        Gesundheit.Fakten f = nichtScharf(T, T - 5 * 60_000);
        f.sperre = true;
        Gesundheit.Entscheidung e = g.takt(f);
        assertFalse(e.gesund());
        assertEquals(Gesundheit.Stufe.KEINE, e.stufe);
        assertEquals("sperre", e.grund);
        assertEquals(0, g.eingriffeLetzteStunde());
        assertEquals("nach der Sperre normal", Gesundheit.Stufe.SCHARF, g.takt(nichtScharf(T + 1, T - 5 * 60_000)).stufe);
    }

    @Test
    public void atMostSixInterventionsPerHour() {
        Gesundheit g = new Gesundheit();
        int n = 0;
        for (long t = T; t < T + 3600_000L; t += 60_000L) {
            Gesundheit.Entscheidung e = g.takt(nichtScharf(t, T - 5 * 60_000));
            if (e.stufe != Gesundheit.Stufe.KEINE) n++;
            // zwischendurch kurz gesund, damit die Leiter von vorn beginnt (häufigster Missbrauchsfall)
            if (t % (4 * 60_000L) == 0) g.takt(gesund(t + 1));
        }
        assertTrue("höchstens 6/h, war " + n, n <= Gesundheit.MAX_JE_STUNDE);
    }

    @Test
    public void ladderSurvivesDaemonRestart() {
        Gesundheit g = new Gesundheit();
        long seit = T - 10 * 60_000;
        g.takt(nichtScharf(T, seit));
        g.takt(nichtScharf(T + 2 * 60_000, seit));   // DAEMON → Prozess startet neu
        JSONObject gesichert = g.sichern();
        Gesundheit neu = new Gesundheit();
        neu.laden(gesichert, T + 3 * 60_000);
        assertEquals(Gesundheit.Stufe.DAEMON, neu.letzteStufe());
        assertEquals(2, neu.eingriffeLetzteStunde());
        assertEquals("weiter mit APP statt von vorn", Gesundheit.Stufe.APP,
                neu.takt(nichtScharf(T + 12 * 60_000, seit)).stufe);
        Gesundheit alt = new Gesundheit();
        alt.laden(gesichert, T + 3 * 3600_000L);
        assertEquals("alter Stand wird verworfen", Gesundheit.Stufe.KEINE, alt.letzteStufe());
    }

    @Test
    public void outageDurationForRepairedMessage() {
        Gesundheit g = new Gesundheit();
        g.takt(nichtScharf(T, T - 3 * 60_000));
        assertEquals(T, g.ausfallSeit());
        g.takt(gesund(T + 5 * 60_000));
        assertEquals(T, g.gesundGeworden());
        assertEquals(-1, g.ausfallSeit());
    }

    @Test
    public void executorRebuiltAfterTwoMinutesAtMostEveryTenMinutes() {
        AusfuehrerWaechter w = new AusfuehrerWaechter();
        long t = T;
        for (int i = 0; i < 3; i++) assertFalse(w.ergebnis(false, false, t += 30_000));
        assertTrue("4. Fehlschlag = 2 min", w.ergebnis(false, false, t += 30_000));
        for (int i = 0; i < 8; i++) assertFalse("Abstand 10 min", w.ergebnis(false, false, t += 30_000));
        assertFalse(w.ergebnis(true, false, t += 30_000));
        assertEquals(0, w.fehler());
        AusfuehrerWaechter s = new AusfuehrerWaechter();
        for (int i = 0; i < 10; i++) assertFalse("nie während Sperre", s.ergebnis(false, true, T + i * 30_000L));
    }

    @Test
    public void wiring() throws Exception {
        String d = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/gesundheit/GesundheitsDienst.java"));
        assertTrue(d.contains("case SCHARF:\n                CameraDaemon.enableSurveillance();"));
        assertTrue(d.contains("CameraDaemon.requestProcessRestartPreservingTrip(\"Gesundheit: \" + grund);"));
        assertTrue(d.contains("\".gesundheit-app-neustart\""));
        assertTrue(d.contains("new File(basis(), \".deploy.lock\").exists()"));
        String w = Quelltext.lies(Paths.get("../deploy/sm-watchdog.sh"));
        int i = w.indexOf("REQ=$NEW/.gesundheit-app-neustart");
        assertTrue(i > 0);
        String block = w.substring(i, w.indexOf("# Notnagel", i));
        assertTrue("nie während Update", block.contains("if [ -f \"$REQ\" ] && ! update_running; then"));
        assertTrue("Mindestabstand", block.contains("if [ \"$SINCE\" -lt \"$RESTART_GAP\" ]; then"));
        assertTrue("Grund ins Protokoll", block.contains("notiz \"EINGRIFF: SurfMate neu gestartet auf Anforderung des Gesundheitswächters"));
        String cam = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/daemon/CameraDaemon.java"));
        assertTrue(cam.contains("at.greenchili.surfmate.gesundheit.GesundheitsDienst.start();"));
        String dsm = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/ui/daemon/DaemonStartupManager.kt"));
        assertTrue(dsm.contains("ausfuehrerWaechter.ergebnis(snapshot != null"));
        assertTrue(dsm.contains("adbLauncher.adbShellExecutor.closeConnection()"));
        String tg = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/TelegramApiHandler.java"));
        assertTrue("Schalter healthAlerts lesbar", tg.contains("response.put(\"healthAlerts\""));
        assertTrue("Schalter healthAlerts speicherbar", tg.contains("{ \"healthAlerts\",        UnifiedTelegramConfig.K_HEALTH_ALERTS }"));
    }
}
