package at.greenchili.surfmate.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import at.greenchili.surfmate.logging.DaemonLogger;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 0.1.43.14 (E2E_014313 B1): Fehlschlag-Pfad nach einem hängenden
 * Hardware-Encoder. Ein Encoder-Aufruf, der in nativem Code hängt, darf weder
 * endlos weiterlaufen (Aufsicht → trip-sicherer Kern-Neustart) noch durch
 * neue Versuche vervielfacht werden (Sperre + dauerhafter Backoff).
 */
public class EncoderHaengerTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private DaemonLogger.Config vorher;
    private final AtomicLong uhr = new AtomicLong(1_800_000_000_000L);
    private final AtomicInteger neustartAnforderungen = new AtomicInteger();
    private final AtomicInteger dringendAnforderungen = new AtomicInteger();
    private long eskalation = 60_000L;
    private final AtomicBoolean neustartLaeuft = new AtomicBoolean(false);
    private File datei;
    private final EncoderHaenger.Neustart neustart = new EncoderHaenger.Neustart() {
        @Override
        public void anfordern(String grund) {
            neustartAnforderungen.incrementAndGet();
            neustartLaeuft.set(true);
        }

        @Override
        public void dringend(String grund) {
            dringendAnforderungen.incrementAndGet();
        }

        @Override
        public boolean laeuftSchon() {
            return neustartLaeuft.get();
        }
    };

    @Before
    public void setUp() throws Exception {
        vorher = DaemonLogger.getConfig();
        DaemonLogger.configure(new DaemonLogger.Config()
                .withConsoleLog(false).withFileLog(false).withStdoutLog(false));
        datei = new File(tmp.newFolder(), EncoderHaenger.DATEI);
        reset(150L, 10L);
    }

    @After
    public void tearDown() {
        DaemonLogger.configure(vorher);
    }

    private void reset(long nachfristMs, long taktMs) {
        EncoderHaenger.fuerTestZuruecksetzen(datei, uhr::get, neustart, nachfristMs, taktMs, eskalation);
    }

    private static void warteBis(String was, java.util.function.BooleanSupplier b) throws Exception {
        long ende = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!b.getAsBoolean()) {
            if (System.nanoTime() > ende) fail("Zeitüberschreitung: " + was);
            Thread.sleep(5);
        }
    }

    // ---- Regel ----

    @Test
    public void backoffSteigtBegrenztAn() {
        assertEquals(0L, EncoderHaenger.backoffMs(0));
        assertEquals(30_000L, EncoderHaenger.backoffMs(1));
        assertEquals(120_000L, EncoderHaenger.backoffMs(2));
        assertEquals(300_000L, EncoderHaenger.backoffMs(3));
        assertEquals(600_000L, EncoderHaenger.backoffMs(4));
        assertEquals("nie länger als 10 min", 600_000L, EncoderHaenger.backoffMs(50));
    }

    @Test
    public void restSperreUndUhrRueckwaerts() {
        long t0 = 1_000_000L;
        assertEquals(0L, EncoderHaenger.restSperreMs(0, t0, t0));
        assertEquals(30_000L, EncoderHaenger.restSperreMs(1, t0, t0));
        assertEquals(1_000L, EncoderHaenger.restSperreMs(1, t0, t0 + 29_000L));
        assertEquals(0L, EncoderHaenger.restSperreMs(1, t0, t0 + 30_000L));
        assertEquals("Uhr zurückgestellt sperrt nicht dauerhaft",
                0L, EncoderHaenger.restSperreMs(4, t0, t0 - 5_000L));
    }

    // ---- zeitbegrenzt ----

    @Test
    public void schnellerAufrufLiefertErgebnisOhneMeldung() throws Exception {
        Object o = new Object();
        Object r = EncoderHaenger.zeitbegrenzt("EncoderCreate", 2_000L, () -> o, x -> fail("kein Freigeben"));
        assertSame(o, r);
        assertFalse(EncoderHaenger.haengtNoch());
        assertNull(EncoderHaenger.sperrgrund());
        assertEquals(0, EncoderHaenger.anzahlFuerTest());
    }

    @Test
    public void fehlerImAufrufWirdDurchgereicht() throws Exception {
        try {
            EncoderHaenger.zeitbegrenzt("EncoderCreate", 2_000L,
                    () -> { throw new java.io.IOException("kein Codec"); }, null);
            fail("IOException erwartet");
        } catch (java.io.IOException e) {
            assertEquals("kein Codec", e.getMessage());
        }
        assertEquals("ein normaler Fehler ist kein Hänger", 0, EncoderHaenger.anzahlFuerTest());
    }

    @Test
    public void haengenderAufrufWirdGemeldetGesperrtUndSpaetesErgebnisFreigegeben() throws Exception {
        reset(60_000L, 10L); // Aufsicht soll in diesem Test nicht neu starten
        CountDownLatch los = new CountDownLatch(1);
        Object spaet = new Object();
        AtomicReference<Object> freigegeben = new AtomicReference<>();
        try {
            EncoderHaenger.zeitbegrenzt("EncoderCreate", 50L, () -> {
                // Simuliert den nativen Hänger: interrupt() wird ignoriert.
                while (true) {
                    try {
                        if (los.await(10, TimeUnit.MILLISECONDS)) break;
                    } catch (InterruptedException ignored) {}
                }
                return spaet;
            }, freigegeben::set);
            fail("EncoderHaengerException erwartet");
        } catch (EncoderHaenger.EncoderHaengerException e) {
            assertEquals("EncoderCreate", e.phase);
        }
        assertTrue("Thread lebt trotz interrupt weiter", EncoderHaenger.haengtNoch());
        assertNotNull("neuer Encoder-Aufbau gesperrt", EncoderHaenger.sperrgrund());
        assertTrue(EncoderHaenger.sperrgrund().contains("Kern startet neu"));
        assertEquals(1, EncoderHaenger.anzahlFuerTest());
        assertTrue("Aufsicht läuft", EncoderHaenger.aufsichtLaeuftFuerTest());

        los.countDown();
        warteBis("spätes Ergebnis freigegeben", () -> freigegeben.get() == spaet);
        warteBis("Thread beendet", () -> !EncoderHaenger.haengtNoch());
        warteBis("Aufsicht beendet", () -> !EncoderHaenger.aufsichtLaeuftFuerTest());
        assertEquals("kein Neustart, wenn der Thread doch fertig wird", 0, neustartAnforderungen.get());

        // Backoff gilt weiter, bis die Zeit um ist.
        assertNotNull(EncoderHaenger.sperrgrund());
        assertTrue(EncoderHaenger.sperrgrund().contains("nächster Versuch in 30 s"));
        uhr.addAndGet(30_000L);
        assertNull(EncoderHaenger.sperrgrund());
    }

    @Test
    public void aufsichtFordertNeustartAnWennThreadWeiterHaengt() throws Exception {
        CountDownLatch los = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            while (true) {
                try {
                    if (los.await(10, TimeUnit.MILLISECONDS)) return;
                } catch (InterruptedException ignored) {}
            }
        }, "EncoderCreate");
        worker.setDaemon(true);
        worker.start();
        try {
            EncoderHaenger.melden(worker, "EncoderCreate");
            warteBis("Neustart angefordert", () -> neustartAnforderungen.get() >= 1);
            Thread.sleep(100);
            assertEquals("nur eine Anforderung, solange der Neustart läuft",
                    1, neustartAnforderungen.get());
            assertEquals("Eskalationsfrist noch nicht um", 0, dringendAnforderungen.get());
            assertTrue(EncoderHaenger.haengtNoch());
        } finally {
            los.countDown();
        }
        warteBis("Aufsicht beendet", () -> !EncoderHaenger.aufsichtLaeuftFuerTest());
    }

    @Test
    public void eskaliertAufNotfallNeustartWennTripSichererWegNichtGreift() throws Exception {
        eskalation = 150L;
        reset(100L, 10L);
        CountDownLatch los = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            while (true) {
                try {
                    if (los.await(10, TimeUnit.MILLISECONDS)) return;
                } catch (InterruptedException ignored) {}
            }
        }, "EncoderCreate");
        worker.setDaemon(true);
        worker.start();
        try {
            EncoderHaenger.melden(worker, "EncoderCreate");
            warteBis("Notfall-Neustart angefordert", () -> dringendAnforderungen.get() >= 1);
            assertEquals(1, neustartAnforderungen.get());
            Thread.sleep(100);
            assertEquals("Notfall-Neustart nur einmal", 1, dringendAnforderungen.get());
        } finally {
            los.countDown();
        }
        warteBis("Aufsicht beendet", () -> !EncoderHaenger.aufsichtLaeuftFuerTest());
    }

    @Test
    public void keinNeustartVorAblaufDerNachfrist() throws Exception {
        reset(60_000L, 10L);
        CountDownLatch los = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try { los.await(); } catch (InterruptedException ignored) {}
        }, "EncoderConfig");
        worker.setDaemon(true);
        worker.start();
        EncoderHaenger.melden(worker, "EncoderConfig");
        Thread.sleep(200);
        assertEquals(0, neustartAnforderungen.get());
        los.countDown();
        warteBis("Aufsicht beendet", () -> !EncoderHaenger.aufsichtLaeuftFuerTest());
        assertEquals(0, neustartAnforderungen.get());
    }

    // ---- Dauerhafter Zustand ----

    @Test
    public void backoffUeberlebtKernNeustartUndErfolgSetztZurueck() throws Exception {
        EncoderHaenger.melden(null, "EncoderCreate");
        EncoderHaenger.melden(null, "EncoderCreate");
        assertTrue(datei.isFile());
        String json = new String(Files.readAllBytes(datei.toPath()), StandardCharsets.UTF_8);
        assertTrue(json, json.contains("\"anzahl\":2"));

        // Neuer Prozess: Zustand nur aus der Datei.
        reset(150L, 10L);
        assertEquals(2, EncoderHaenger.anzahlFuerTest());
        String grund = EncoderHaenger.sperrgrund();
        assertNotNull(grund);
        assertTrue(grund, grund.contains("nächster Versuch in 120 s"));
        uhr.addAndGet(120_000L);
        assertNull(EncoderHaenger.sperrgrund());

        // Emulator-Nachweis: der erste Encoder nach dem Neustart entsteht
        // problemlos, erst der Neuaufbau hängt. Das darf den Backoff nicht
        // zurücksetzen, sonst Neustart alle ~75 s ohne Ende.
        EncoderHaenger.erfolg();
        assertEquals("Erfolg kurz nach einem Hänger setzt nicht zurück",
                2, EncoderHaenger.anzahlFuerTest());
        EncoderHaenger.melden(null, "EncoderCreate");
        assertTrue("dritter Hänger in Folge: 5 min",
                EncoderHaenger.sperrgrund().contains("in 300 s"));

        uhr.addAndGet(EncoderHaenger.ERHOLUNG_MS);
        EncoderHaenger.erfolg();
        assertEquals(0, EncoderHaenger.anzahlFuerTest());
        assertFalse("Datei nach Erfolg entfernt", datei.exists());
        uhr.addAndGet(1L);
        EncoderHaenger.melden(null, "EncoderCreate");
        assertTrue("Zähler beginnt nach Erfolg wieder bei 1",
                EncoderHaenger.sperrgrund().contains("in 30 s"));
    }

    @Test
    public void kaputteDateiSperrtNicht() throws Exception {
        Files.write(datei.toPath(), "{kaputt".getBytes(StandardCharsets.UTF_8));
        reset(150L, 10L);
        assertNull(EncoderHaenger.sperrgrund());
        assertEquals(0, EncoderHaenger.anzahlFuerTest());
    }

    // ---- Verdrahtung (Quellvertrag) ----

    @Test
    public void verdrahtungImKern() throws Exception {
        String rec = lies("app/src/main/java/at/greenchili/surfmate/surveillance/HardwareEventRecorderGpu.java");
        assertTrue(rec.contains("EncoderHaenger.zeitbegrenzt(\"EncoderCreate\""));
        assertTrue("spätes Ergebnis wird freigegeben", rec.contains("late.release()"));
        assertFalse("kein nackter createThread mehr", rec.contains("Thread createThread"));
        assertTrue(rec.contains("EncoderHaenger.melden(configThread, \"EncoderConfig\")"));
        assertTrue(rec.contains("EncoderHaenger.melden(surfaceThread, \"EncoderSurface\")"));
        assertTrue(rec.contains("EncoderHaenger.melden(startThread, \"EncoderStart\")"));
        assertTrue(rec.contains("EncoderHaenger.erfolg()"));
        int sperre = rec.indexOf("String haengerSperre = EncoderHaenger.sperrgrund();");
        int initOnce = rec.indexOf("initOnce();", sperre);
        assertTrue("Sperre vor dem ersten initOnce()", sperre > 0 && initOnce > sperre);
        assertTrue("kein AVC-Neuversuch nach einem Hänger",
                rec.contains("if (hevcFailure instanceof EncoderHaenger.EncoderHaengerException) throw hevcFailure;"));

        String pipe = lies("app/src/main/java/at/greenchili/surfmate/surveillance/GpuSurveillancePipeline.java");
        int gate = pipe.indexOf("logger.warn(\"Refusing start() — \" + haengerSperre);");
        int claim = pipe.indexOf("starting = true;  // Block concurrent starts");
        assertTrue("start() verweigert vor dem Claim", gate > 0 && claim > gate);

        String api = lies("app/src/main/java/at/greenchili/surfmate/server/StreamingApiHandler.java");
        assertTrue(api.contains("EncoderHaenger.aufsichtSicherstellen();"));
        assertTrue(api.contains("handed to encoder supervisor"));
        assertTrue(api.contains("blocked.put(\"starting\", false);"));
        assertTrue(api.contains("\"encoder_blocked\""));

        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            String i18n = lies("app/src/main/assets/web/i18n/" + l + ".json");
            assertTrue(l, i18n.contains("\"encoder_blocked\":"));
        }
    }

    private static String lies(String rel) throws Exception {
        Path p = Paths.get(rel);
        if (!Files.exists(p)) p = Paths.get("..").resolve(rel);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
