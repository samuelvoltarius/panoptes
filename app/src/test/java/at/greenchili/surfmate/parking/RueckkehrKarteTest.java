package at.greenchili.surfmate.parking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.surveillance.DetectionBaseline;
import at.greenchili.surfmate.surveillance.GuardStopPolicy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

/**
 * 0.1.28 Rückkehr-Karte am Display: ≥ 1 Aufnahme → große Karte; scharf und 0 → kurz „nichts passiert“; nicht scharf →
 * Grund in Alltagssprache; unklar → nichts. Je Parkphase höchstens einmal, auch bei Ende per Aufsperren.
 */
public class RueckkehrKarteTest {

    static final long STUNDE = 3_600_000L;

    // ------------------------------------------------------------------ Entscheidung (rein)

    @Test
    public void aufnahmenGehenVorAllem() {
        RueckkehrKarte k = RueckkehrKarte.entscheide(ParkingSession.SENTRY_ARMED, 3, null, STUNDE);
        assertEquals(RueckkehrKarte.Art.AUFNAHMEN, k.art);
        assertEquals(3, k.anzahl);
        assertEquals(RueckkehrKarte.AUFNAHMEN_SICHTBAR_SEK, k.sichtbarSek());
        // auch bei kurzem Halt und späterem Wächter-Stopp
        assertEquals(RueckkehrKarte.Art.AUFNAHMEN,
                RueckkehrKarte.entscheide(ParkingSession.SENTRY_LOCK_WAIT, 1, GuardStopPolicy.REASON_SOC, 60_000).art);
    }

    @Test
    public void scharfOhneAufnahmeKurzNichtsPassiert() {
        RueckkehrKarte k = RueckkehrKarte.entscheide(ParkingSession.SENTRY_ARMED, 0, null, STUNDE);
        assertEquals(RueckkehrKarte.Art.NICHTS, k.art);
        assertEquals(RueckkehrKarte.KURZ_SICHTBAR_SEK, k.sichtbarSek());
        assertEquals("Zahl unbekannt → nie „nichts passiert“", RueckkehrKarte.Art.KEINE,
                RueckkehrKarte.entscheide(ParkingSession.SENTRY_ARMED, -1, null, STUNDE).art);
    }

    @Test
    public void nichtScharfMitGrund() {
        assertGrund("aus", ParkingSession.SENTRY_SURVEILLANCE_OFF, null);
        assertGrund("sicherer_ort", ParkingSession.SENTRY_SUPPRESSED_SAFE_ZONE, null);
        assertGrund("zeit", ParkingSession.SENTRY_SUPPRESSED_SCHEDULE, null);
        assertGrund("nur_fahrt", ParkingSession.SENTRY_VEHICLE_ON_ONLY, null);
        // Wächter-Stopp während der Parkphase schlägt „armed“
        assertGrund("akku", ParkingSession.SENTRY_ARMED, GuardStopPolicy.REASON_SOC);
        assertGrund("v12", ParkingSession.SENTRY_ARMED, GuardStopPolicy.REASON_V12);
        assertGrund("lange_geparkt", ParkingSession.SENTRY_ARMED, GuardStopPolicy.REASON_PARK);
        assertGrund("zeit", ParkingSession.SENTRY_ARMED, GuardStopPolicy.REASON_WINDOW);
        assertGrund("sicherer_ort", ParkingSession.SENTRY_ARMED, GuardStopPolicy.REASON_QUIET);
    }

    private static void assertGrund(String grund, String sentry, String stopp) {
        RueckkehrKarte k = RueckkehrKarte.entscheide(sentry, 0, stopp, STUNDE);
        assertEquals(RueckkehrKarte.Art.NICHT_SCHARF, k.art);
        assertEquals(grund, k.grund);
        assertFalse("Text für " + grund, RueckkehrKarte.fallbackGrund(grund).isEmpty());
    }

    @Test
    public void unklarOderKurzKeineKarte() {
        // lock_wait / pipeline_down / unknown: nur der 90-s-Stand, nicht belegt → kein falscher Grund
        for (String s : new String[]{ParkingSession.SENTRY_LOCK_WAIT, ParkingSession.SENTRY_PIPELINE_DOWN,
                ParkingSession.SENTRY_UNKNOWN, null, "irgendwas"}) {
            assertEquals(String.valueOf(s), RueckkehrKarte.Art.KEINE, RueckkehrKarte.entscheide(s, 0, null, STUNDE).art);
        }
        assertEquals("unbekannter Stopp-Grund", RueckkehrKarte.Art.KEINE,
                RueckkehrKarte.entscheide(ParkingSession.SENTRY_ARMED, 0, "neu", STUNDE).art);
        assertEquals("kurzer Halt ohne Aufnahme", RueckkehrKarte.Art.KEINE,
                RueckkehrKarte.entscheide(ParkingSession.SENTRY_ARMED, 0, null, 2 * 60_000L).art);
    }

    @Test
    public void textOhneOrtUndMitZahl() {
        RueckkehrKarte k = RueckkehrKarte.entscheide(ParkingSession.SENTRY_ARMED, 3, null, STUNDE);
        assertTrue(k.text(), k.text().contains("3"));
        assertFalse(k.titel().isEmpty());
        assertEquals("", RueckkehrKarte.entscheide(ParkingSession.SENTRY_ARMED, 0, null, STUNDE).text());
    }

    // ------------------------------------------------------------------ Ablauf im Controller

    private File tmp;
    private ParkingControllerLifecycleTest.FakeSource source;
    private ParkingControllerLifecycleTest.FakeEnv env;
    private ParkingController controller;

    @Before
    public void setUp() throws Exception {
        tmp = Files.createTempDirectory("rueckkehr-test").toFile();
        source = new ParkingControllerLifecycleTest.FakeSource();
        env = new ParkingControllerLifecycleTest.FakeEnv(tmp);
        final String url = "jdbc:h2:mem:rk_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        controller = new ParkingController(source, env, () -> new ParkingStore(url));
        source.set("enabled", true);
        controller.attach();
        warte(controller::isStarted);
    }

    @After
    public void tearDown() {
        controller.detach();
        ParkingHooks.setListener(null);
        DetectionBaseline.setGlobalListener(null);
        ParkingController.deleteAssets(tmp);
    }

    private static void warte(java.util.function.BooleanSupplier b) throws Exception {
        long ende = System.currentTimeMillis() + 3000;
        while (!b.getAsBoolean()) {
            if (System.currentTimeMillis() > ende) throw new AssertionError("Zeit abgelaufen");
            Thread.sleep(10);
        }
    }

    private void parken(long generation) throws Exception {
        ParkingHooks.onAccOff(generation);
        warte(() -> controller.currentSession() != null);
    }

    @Test
    public void accAnNachParkenMitAufnahmenZeigtKarteEinmal() throws Exception {
        parken(1);
        env.sentryEvents = 2;
        env.now += 2 * STUNDE;
        env.accOn = true;
        controller.handleAccOn(2);
        assertEquals(1, env.returnCards.size());
        assertEquals(RueckkehrKarte.Art.AUFNAHMEN, env.returnCards.get(0).art);
        assertEquals(2, env.returnCards.get(0).anzahl);
        controller.handleAccOn(3);   // weiteres ACC an ohne neue Parkphase
        assertEquals("nie zweimal", 1, env.returnCards.size());
    }

    @Test
    public void scharfOhneAufnahmeKurzeKarte() throws Exception {
        parken(1);
        env.sentryEvents = 0;
        env.now += STUNDE;
        controller.handleAccOn(2);
        assertEquals(RueckkehrKarte.Art.NICHTS, env.returnCards.get(0).art);
    }

    @Test
    public void waechterStoppZeigtGrund() throws Exception {
        parken(1);
        env.sentryEvents = 0;
        env.guardStop = GuardStopPolicy.REASON_SOC;
        env.now += 5 * STUNDE;
        controller.handleAccOn(2);
        assertEquals(RueckkehrKarte.Art.NICHT_SCHARF, env.returnCards.get(0).art);
        assertEquals("akku", env.returnCards.get(0).grund);
    }

    @Test
    public void endePerAufsperrenKarteErstBeiAccAn() throws Exception {
        source.set("endTrigger", ParkingConfig.END_TRIGGER_RETURN);
        parken(1);
        env.sentryEvents = 1;
        env.now += STUNDE;
        controller.handleReturnSignal(ParkingSession.END_UNLOCK);
        assertNull("Standard „return“: Aufsperren beendet die Parkphase", controller.currentSession());
        assertTrue("noch keine Karte beim Aufsperren", env.returnCards.isEmpty());
        env.now += 60_000;
        controller.handleAccOn(2);
        assertEquals(1, env.returnCards.size());
        assertEquals(RueckkehrKarte.Art.AUFNAHMEN, env.returnCards.get(0).art);
    }

    @Test
    public void kurzerHaltOhneAufnahmeKeineKarte() throws Exception {
        parken(1);
        env.sentryEvents = 0;
        env.now += 60_000;
        controller.handleAccOn(2);
        assertTrue(env.returnCards.isEmpty());
        assertNull(controller.currentSession());
    }
}
