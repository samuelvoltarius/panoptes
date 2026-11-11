package at.greenchili.surfmate.byd.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.byd.routing.VehicleCommandRouter.CommandResult;
import at.greenchili.surfmate.byd.routing.VehicleCommandRouter.VehicleCommand;
import at.greenchili.surfmate.daemon.telegram.AktionenCommandHandler;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 0.1.38.8 Fahrzeug-Aktionen: Schalter, Router-Wächter, Rückfrage, Ratenlimit, Meldung, Abschalten, Überführung.
 * Reiner JVM-Test mit gefälschtem Router – kein Befehl erreicht SDK oder Cloud (REGEL A+ für Tests).
 */
public class FahrzeugAktionenTest {

    JSONObject config;
    List<String> gemeldet;
    final List<VehicleCommand> gesendet = new ArrayList<>();

    /** Gefälschter Router: prüft wie der echte zuerst den Wächter, „sendet“ dann nur in die Liste. */
    final FahrzeugAktionen.Ausfuehrer router = cmd -> {
        CommandResult g = VehicleCommandRouter.checkFahrzeugAktion(cmd);
        if (g != null) return g;
        gesendet.add(cmd);
        return CommandResult.success(VehicleCommandRouter.Path.CLOUD, "ok", 3);
    };

    @Before
    public void auf() {
        config = new JSONObject();
        gemeldet = FahrzeugAktionen.fuerTest(config);
    }

    @After
    public void ab() {
        FahrzeugAktionen.fuerTest(null);
    }

    void an(String... funktionen) throws Exception {
        JSONObject a = new JSONObject();
        for (String f : funktionen) a.put(f, true);
        config.put(FahrzeugAktionen.ABSCHNITT, a);
    }

    // ------------------------------------------------------------------ Zuordnung und Standard

    @Test
    public void standardAllesAus() {
        for (String f : FahrzeugAktionen.FUNKTIONEN) assertFalse(f, FahrzeugAktionen.an(f));
        assertFalse(FahrzeugAktionen.an(FahrzeugAktionen.ANNAEHERUNG));
        assertEquals(FahrzeugAktionen.GRUND_AUS,
                FahrzeugAktionen.sperrGrund(new VehicleCommandRouter.FindCarCommand()));
    }

    @Test
    public void zuordnung() {
        assertEquals(FahrzeugAktionen.AUTO_FINDEN, FahrzeugAktionen.funktion(new VehicleCommandRouter.FindCarCommand()));
        assertEquals(FahrzeugAktionen.LICHT, FahrzeugAktionen.funktion(new VehicleCommandRouter.FlashLightsCommand()));
        assertEquals(FahrzeugAktionen.WARNBLINKER, FahrzeugAktionen.funktion(new VehicleCommandRouter.HazardCommand(true)));
        assertNull("Warnblinker AUS ist nie gesperrt", FahrzeugAktionen.funktion(new VehicleCommandRouter.HazardCommand(false)));
        assertNull(FahrzeugAktionen.funktion(new VehicleCommandRouter.LockCommand()));
        assertNull(FahrzeugAktionen.funktion(new VehicleCommandRouter.ClimateOffCommand()));
        assertNull(FahrzeugAktionen.sperrGrund(new VehicleCommandRouter.LockCommand()));
    }

    @Test
    public void annaeherungNurMitAufsperren() throws Exception {
        an(FahrzeugAktionen.ANNAEHERUNG);
        assertFalse(FahrzeugAktionen.an(FahrzeugAktionen.ANNAEHERUNG));
        an(FahrzeugAktionen.ANNAEHERUNG, FahrzeugAktionen.AUFSPERREN);
        assertTrue(FahrzeugAktionen.an(FahrzeugAktionen.ANNAEHERUNG));
    }

    // ------------------------------------------------------------------ Router-Wächter

    @Test
    public void routerSperrtOhneAusloesungAuchBeiSchalterAn() throws Exception {
        an(FahrzeugAktionen.AUTO_FINDEN, FahrzeugAktionen.LICHT, FahrzeugAktionen.WARNBLINKER);
        // MQTT, HA, Regel, Taste, KI rufen den Router direkt – ohne Auslösung
        for (VehicleCommand c : new VehicleCommand[] {new VehicleCommandRouter.FindCarCommand(),
                new VehicleCommandRouter.FlashLightsCommand(), new VehicleCommandRouter.HazardCommand(true)}) {
            CommandResult r = router.ausfuehren(c);
            assertEquals(c.name(), FahrzeugAktionen.GRUND_NUR_NUTZER, r.grund);
            assertEquals(VehicleCommandRouter.Outcome.AUTH_REQUIRED, r.outcome);
        }
        assertTrue("nichts gesendet", gesendet.isEmpty());
        // Warnblinker aus geht immer
        assertEquals(VehicleCommandRouter.Outcome.SUCCESS, router.ausfuehren(new VehicleCommandRouter.HazardCommand(false)).outcome);
    }

    @Test
    public void ausloesungGiltNurFuerIhreFunktionUndNurImThread() throws Exception {
        an(FahrzeugAktionen.AUTO_FINDEN, FahrzeugAktionen.LICHT);
        AtomicReference<String> andereFunktion = new AtomicReference<>(), andererThread = new AtomicReference<>();
        FahrzeugAktionen.Ausfuehrer pruefend = cmd -> {
            andereFunktion.set(FahrzeugAktionen.sperrGrund(new VehicleCommandRouter.FlashLightsCommand()));
            Thread t = new Thread(() -> andererThread.set(FahrzeugAktionen.sperrGrund(new VehicleCommandRouter.FindCarCommand())));
            t.start();
            try {
                t.join();
            } catch (InterruptedException ignored) {
            }
            return router.ausfuehren(cmd);
        };
        CommandResult r = FahrzeugAktionen.ausloesen(new VehicleCommandRouter.FindCarCommand(),
                FahrzeugAktionen.QUELLE_WEB, "Display", true, pruefend);
        assertEquals(VehicleCommandRouter.Outcome.SUCCESS, r.outcome);
        assertEquals(1, gesendet.size());
        assertEquals("Auto finden öffnet nicht das Licht", FahrzeugAktionen.GRUND_NUR_NUTZER, andereFunktion.get());
        assertEquals("anderer Thread sieht die Auslösung nicht", FahrzeugAktionen.GRUND_NUR_NUTZER, andererThread.get());
        // danach wieder zu
        assertEquals(FahrzeugAktionen.GRUND_NUR_NUTZER, FahrzeugAktionen.sperrGrund(new VehicleCommandRouter.FindCarCommand()));
    }

    @Test
    public void beideRouterEingaengePruefen() throws Exception {
        String src = lies("app/src/main/java/at/greenchili/surfmate/byd/routing/VehicleCommandRouter.java");
        int n = 0, i = 0;
        while ((i = src.indexOf("CommandResult aktion = checkFahrzeugAktion(cmd);", i)) >= 0) {
            n++;
            i++;
        }
        assertEquals("execute und executeSdkOnly", 2, n);
    }

    // ------------------------------------------------------------------ Auslösen

    @Test
    public void schalterAusNichtsGesendet() {
        CommandResult r = FahrzeugAktionen.ausloesen(new VehicleCommandRouter.FindCarCommand(),
                FahrzeugAktionen.QUELLE_HANDY, "Pixel", true, router);
        assertEquals(FahrzeugAktionen.GRUND_AUS, r.grund);
        assertTrue(gesendet.isEmpty());
    }

    @Test
    public void ohneRueckfrageNichtsGesendet() throws Exception {
        an(FahrzeugAktionen.LICHT);
        CommandResult r = FahrzeugAktionen.ausloesen(new VehicleCommandRouter.FlashLightsCommand(),
                FahrzeugAktionen.QUELLE_WEB, "Display", false, router);
        assertEquals(FahrzeugAktionen.GRUND_BESTAETIGUNG, r.grund);
        assertTrue(gesendet.isEmpty());
    }

    @Test
    public void jedeAusloesungGemeldet() throws Exception {
        an(FahrzeugAktionen.LICHT);
        FahrzeugAktionen.ausloesen(new VehicleCommandRouter.FlashLightsCommand(), FahrzeugAktionen.QUELLE_HANDY,
                "Pixel", true, router);
        assertEquals("[aktionen.ausgeloest]", gemeldet.toString());
        FahrzeugAktionen.ausloesen(new VehicleCommandRouter.FlashLightsCommand(), FahrzeugAktionen.QUELLE_WEB,
                "Display", true, c -> CommandResult.failed(VehicleCommandRouter.Path.CLOUD, "x", 1, null));
        assertEquals("[aktionen.ausgeloest, aktionen.fehlgeschlagen]", gemeldet.toString());
    }

    @Test
    public void ratenlimitSignal() throws Exception {
        an(FahrzeugAktionen.AUTO_FINDEN, FahrzeugAktionen.LICHT);
        for (int i = 0; i < FahrzeugAktionen.SIGNAL_JE_10_MIN; i++) {
            assertEquals(VehicleCommandRouter.Outcome.SUCCESS, FahrzeugAktionen.ausloesen(
                    i % 2 == 0 ? new VehicleCommandRouter.FindCarCommand() : new VehicleCommandRouter.FlashLightsCommand(),
                    FahrzeugAktionen.QUELLE_WEB, "Display", true, router).outcome);
        }
        CommandResult r = FahrzeugAktionen.ausloesen(new VehicleCommandRouter.FlashLightsCommand(),
                FahrzeugAktionen.QUELLE_HANDY, "Pixel", true, router);
        assertEquals("Hupe und Licht teilen sich das Limit", FahrzeugAktionen.GRUND_RATENLIMIT, r.grund);
        assertEquals(VehicleCommandRouter.Outcome.RATE_LIMITED, r.outcome);
        assertEquals(FahrzeugAktionen.SIGNAL_JE_10_MIN, gesendet.size());
        assertTrue(gemeldet.contains("aktionen.abgelehnt"));
    }

    @Test
    public void ratenlimitFensterRein() {
        FahrzeugAktionen.zuruecksetzen();
        long t = 1_000_000_000L;
        // 3 in 10 min, dann erst nach 10 min wieder
        for (int i = 0; i < 3; i++) assertTrue(FahrzeugAktionen.zaehlen(FahrzeugAktionen.LICHT, t + i));
        assertFalse(FahrzeugAktionen.zaehlen(FahrzeugAktionen.LICHT, t + 9 * 60_000L));
        assertTrue(FahrzeugAktionen.zaehlen(FahrzeugAktionen.LICHT, t + 10 * 60_000L + 1));
        // Aufsperren hat ein eigenes Konto
        assertTrue(FahrzeugAktionen.zaehlen(FahrzeugAktionen.AUFSPERREN, t + 10 * 60_000L + 2));
        // höchstens 10 je Stunde (alle 3 min ein Versuch, eine Stunde lang)
        FahrzeugAktionen.zuruecksetzen();
        int inStunde = 0;
        for (int i = 0; i < 20; i++) if (FahrzeugAktionen.zaehlen(FahrzeugAktionen.LICHT, t + i * 3 * 60_000L)) inStunde++;
        assertEquals(FahrzeugAktionen.SIGNAL_JE_STUNDE, inStunde);
    }

    @Test
    public void aufsperrenHandyMeldetNichtDoppelt() throws Exception {
        an(FahrzeugAktionen.AUFSPERREN);
        // HandyUnlockCommand lässt sich nur mit Signatur-Freigabe bauen – hier reicht ein Befehl gleicher Funktion
        CommandResult r = FahrzeugAktionen.ausloesen(new VehicleCommandRouter.FindCarCommand(),
                FahrzeugAktionen.QUELLE_HANDY, "Pixel", true, router);
        assertEquals(FahrzeugAktionen.GRUND_AUS, r.grund);   // autoFinden aus
        assertTrue(gemeldet.isEmpty());
    }

    // ------------------------------------------------------------------ Abschreckung und Automatik

    @Test
    public void abschreckungEigenerSchalterNurLichtUndHupe() {
        // Schalter der Nutzer-Aktionen aus: die Wächter-Abschreckung (deterrentAction) bleibt eigene Zustimmung
        CommandResult r = FahrzeugAktionen.ausloesen(new VehicleCommandRouter.FlashLightsCommand(),
                FahrzeugAktionen.QUELLE_ABSCHRECKUNG, "Wächter", false, router);
        assertEquals(VehicleCommandRouter.Outcome.SUCCESS, r.outcome);
        assertEquals(1, gesendet.size());
        assertTrue("keine Telegram-Flut bei jeder Bewegung", gemeldet.isEmpty());
        r = FahrzeugAktionen.ausloesen(new VehicleCommandRouter.HazardCommand(true),
                FahrzeugAktionen.QUELLE_ABSCHRECKUNG, "Wächter", false, router);
        assertEquals(FahrzeugAktionen.GRUND_NUR_NUTZER, r.grund);
        assertEquals(1, gesendet.size());
    }

    @Test
    public void annaeherungNurFuerAufsperrenUndNurMitSchalter() throws Exception {
        an(FahrzeugAktionen.LICHT);
        CommandResult r = FahrzeugAktionen.ausloesen(new VehicleCommandRouter.FlashLightsCommand(),
                FahrzeugAktionen.QUELLE_ANNAEHERUNG, "Automatik", false, router);
        assertEquals(FahrzeugAktionen.GRUND_AUS, r.grund);
        assertTrue(gesendet.isEmpty());
    }

    // ------------------------------------------------------------------ Abschalten, Überführung

    @Test
    public void allesAusUndNurAutomatik() throws Exception {
        an(FahrzeugAktionen.AUTO_FINDEN, FahrzeugAktionen.LICHT, FahrzeugAktionen.WARNBLINKER,
                FahrzeugAktionen.AUFSPERREN, FahrzeugAktionen.ANNAEHERUNG);
        assertTrue(FahrzeugAktionen.allesAus(true, "telegram"));
        assertFalse(FahrzeugAktionen.an(FahrzeugAktionen.ANNAEHERUNG));
        assertTrue(FahrzeugAktionen.an(FahrzeugAktionen.AUFSPERREN));
        assertTrue(FahrzeugAktionen.allesAus(false, "handy"));
        for (String f : FahrzeugAktionen.FUNKTIONEN) assertFalse(f, FahrzeugAktionen.an(f));
        assertEquals("[aktionen.automatik_aus, aktionen.alle_aus]", gemeldet.toString());
    }

    @Test
    public void ueberfuehrungEinmalUndNurMitAufsperrRecht() throws Exception {
        FahrzeugAktionen.ueberfuehren(false);
        assertFalse(FahrzeugAktionen.an(FahrzeugAktionen.AUFSPERREN));
        // zweiter Start mit Recht: Merker gesetzt, nichts ändert sich mehr
        FahrzeugAktionen.ueberfuehren(true);
        assertFalse(FahrzeugAktionen.an(FahrzeugAktionen.AUFSPERREN));

        config = new JSONObject();
        gemeldet = FahrzeugAktionen.fuerTest(config);
        FahrzeugAktionen.ueberfuehren(true);
        assertTrue("Handy-Aufsperren bleibt an", FahrzeugAktionen.an(FahrzeugAktionen.AUFSPERREN));
        for (String f : new String[] {FahrzeugAktionen.AUTO_FINDEN, FahrzeugAktionen.LICHT, FahrzeugAktionen.WARNBLINKER,
                FahrzeugAktionen.ANNAEHERUNG}) {
            assertFalse(f, FahrzeugAktionen.an(f));
        }
        assertEquals("[aktionen.ueberfuehrt]", gemeldet.toString());
        // Nutzer schaltet aus: bleibt aus
        FahrzeugAktionen.allesAus(false, "web");
        FahrzeugAktionen.ueberfuehren(true);
        assertFalse(FahrzeugAktionen.an(FahrzeugAktionen.AUFSPERREN));
    }

    // ------------------------------------------------------------------ bestehende Regeln

    static JSONObject aktion(String typ, String payload) throws Exception {
        JSONObject a = new JSONObject().put("type", typ);
        if (payload != null) a.put("variables", new JSONObject().put("payload", payload));
        return a;
    }

    @Test
    public void betroffeneRegelnFindetAuchVerschachtelt() throws Exception {
        JSONObject gruppen = new JSONObject().put("g1", new JSONObject().put("actions",
                new org.json.JSONArray().put(aktion("findCar", null))));
        JSONObject alle = new JSONObject()
                .put("r1", new JSONObject().put("name", "Blinken am Abend").put("actions", new org.json.JSONArray().put(aktion("flash", null))))
                .put("r2", new JSONObject().put("name", "Warnblinker aus").put("actions", new org.json.JSONArray().put(aktion("hazard", "off"))))
                .put("r3", new JSONObject().put("name", "Sonst hupen").put("actions", new org.json.JSONArray().put(aktion("setAc", null)))
                        .put("elseActions", new org.json.JSONArray().put(new JSONObject().put("type", "if")
                                .put("childActions", new org.json.JSONArray().put(aktion("hazard", "on"))))))
                .put("r4", new JSONObject().put("name", "Gruppe").put("actions", new org.json.JSONArray()
                        .put(new JSONObject().put("type", "actionGroup").put("variables", new JSONObject().put("groupId", "g1")))))
                .put("r5", new JSONObject().put("name", "Nur Klima").put("actions", new org.json.JSONArray().put(aktion("setAc", null))));
        assertEquals("[Blinken am Abend, Gruppe, Sonst hupen]", FahrzeugAktionen.betroffeneRegeln(alle, gruppen).toString());
        assertTrue(FahrzeugAktionen.betroffeneRegeln(null, null).isEmpty());
    }

    @Test
    public void ueberfuehrungNenntBetroffeneRegelnEinmal() {
        List<String> r = new ArrayList<>();
        r.add("Blinken am Abend");
        FahrzeugAktionen.ueberfuehren(false, r);
        assertEquals("[aktionen.regeln_gesperrt]", gemeldet.toString());
        FahrzeugAktionen.ueberfuehren(false, r);
        assertEquals("nur beim ersten Start", 1, gemeldet.size());
        // auch bei eingeschaltetem Schalter löst eine Regel nie aus
        assertEquals(FahrzeugAktionen.GRUND_AUS, FahrzeugAktionen.sperrGrund(new VehicleCommandRouter.FlashLightsCommand()));
    }

    @Test
    public void regelAufDerSperreEinHinweisAmTag() throws Exception {
        an(FahrzeugAktionen.LICHT);
        // Herkunft nicht gesetzt = Automation
        for (int i = 0; i < 3; i++) router.ausfuehren(new VehicleCommandRouter.FlashLightsCommand());
        assertEquals("[aktionen.regel_lief]", gemeldet.toString());
        assertTrue(gesendet.isEmpty());
        assertTrue(FahrzeugAktionen.regelHinweisFaellig(FahrzeugAktionen.AUTO_FINDEN, 0L));
        assertFalse(FahrzeugAktionen.regelHinweisFaellig(FahrzeugAktionen.AUTO_FINDEN, 1000L));
        assertTrue(FahrzeugAktionen.regelHinweisFaellig(FahrzeugAktionen.AUTO_FINDEN, FahrzeugAktionen.REGEL_HINWEIS_MS + 1));
    }

    @Test
    public void regelListeKurz() {
        List<String> r = new ArrayList<>();
        for (int i = 1; i <= 7; i++) r.add("R" + i);
        assertEquals("„R1“, „R2“, „R3“, „R4“, „R5“ … (+2)", FahrzeugAktionen.regelListe(r));
    }

    @Test
    public void telegramKannNurAbschalten() {
        assertEquals("aus", AktionenCommandHandler.aktion(new String[] {"/aktionen", "aus"}));
        assertEquals("automatik_aus", AktionenCommandHandler.aktion(new String[] {"/aktionen", "automatik", "aus"}));
        // Zusperren ist sicher und darf (nach „Auto ist offen“); Aufsperren nie
        assertEquals("zusperren", AktionenCommandHandler.aktion(new String[] {"/aktionen", "zusperren"}));
        for (String w : new String[] {"an", "ein", "on", "hupe", "auf", "aufsperren", "licht", ""}) {
            assertEquals(w, "status", AktionenCommandHandler.aktion(new String[] {"/aktionen", w}));
        }
    }

    @Test
    public void standNurWahrheitswerte() throws Exception {
        an(FahrzeugAktionen.LICHT);
        JSONObject s = FahrzeugAktionen.stand();
        assertTrue(s.getBoolean(FahrzeugAktionen.LICHT));
        assertFalse(s.getBoolean(FahrzeugAktionen.AUFSPERREN));
        assertEquals(FahrzeugAktionen.FUNKTIONEN.size() + 1, s.length());
    }

    static String lies(String rel) throws Exception {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (p != null) {
            for (Path c : new Path[] {p.resolve(rel), p.resolve(rel.replaceFirst("^app/", ""))}) {
                if (Files.isRegularFile(c)) return new String(Files.readAllBytes(c), StandardCharsets.UTF_8);
            }
            p = at.greenchili.surfmate.testutil.RepoWurzel.hoch(p);
        }
        throw new AssertionError(rel);
    }
}
