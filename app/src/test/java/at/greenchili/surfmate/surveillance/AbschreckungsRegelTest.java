package at.greenchili.surfmate.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Abschreckung: Ruhegrenze, Modus a (Erschütterung), Wahrheitsregel (Koordination 21.10.2026). */
public class AbschreckungsRegelTest {

    @Test
    public void sessionCappedAndQuietAfterwards() {
        AbschreckungsRegel r = new AbschreckungsRegel();
        assertTrue(r.darfBeginnen(0));
        r.sitzungBeginnt(1_000);
        assertTrue(r.laufend());
        assertTrue(r.darfVerlaengern(20_000));
        assertFalse("höchstens 30 s, auch bei Dauerbewegung", r.darfVerlaengern(31_000));
        assertEquals(31_000, r.sitzungsDeckel());
        r.sitzungEndet(31_000);
        assertFalse(r.laufend());
        assertFalse("Ruhezeit 120 s", r.darfBeginnen(31_000 + 119_000));
        assertTrue(r.darfBeginnen(31_000 + 120_000));
    }

    @Test
    public void fehlalarmDauerlaufWecktDasDisplayHoechstensAlle150s() {
        // Lesebericht 21.10.: jede Minute ein Ereignis. Mit Ruhegrenze: Sitzung ≤ 30 s + 120 s Ruhe.
        AbschreckungsRegel r = new AbschreckungsRegel();
        int sitzungen = 0;
        for (long t = 0; t < 3_600_000L; t += 1_000) {           // eine Stunde, jede Sekunde Bewegung
            if (r.laufend()) {
                if (!r.darfVerlaengern(t)) r.sitzungEndet(t);
            } else if (r.darfBeginnen(t)) {
                r.sitzungBeginnt(t);
                sitzungen++;
            }
        }
        assertEquals(24, sitzungen);   // 3600 s / 150 s
    }

    @Test
    public void quietSecondsClamped() {
        AbschreckungsRegel r = new AbschreckungsRegel();
        r.setRuheSekunden(5);
        assertEquals(AbschreckungsRegel.RUHE_MIN_S, r.ruheSekunden());
        r.setRuheSekunden(99_999);
        assertEquals(AbschreckungsRegel.RUHE_MAX_S, r.ruheSekunden());
        r.sitzungEndet(0);   // ohne laufende Sitzung keine Ruhezeit
        assertTrue(r.darfBeginnen(1));
    }

    @Test
    public void wiring() throws Exception {
        String sd = lies("src/main/java/at/greenchili/surfmate/surveillance/ScreenDeterrent.java");
        int m = sd.indexOf("public void onMotionDetected() {");
        String body = sd.substring(m, sd.indexOf("private void cleanup()", m));
        assertTrue(body.contains("if (!laufend && !regel.darfBeginnen(nowElapsed)) {"));
        assertTrue(body.contains("if (laufend && !regel.darfVerlaengern(nowElapsed)) {"));
        assertTrue(body.contains("if (!laufend) regel.sitzungBeginnt(nowElapsed);"));
        int acc = body.indexOf("if (isAccUnsafe())");
        assertTrue("ACC-Sperre bleibt vor allem anderen", acc > 0 && acc < body.indexOf("regel."));
        int c = sd.indexOf("private void cleanup()");
        String cl = sd.substring(c, sd.indexOf("refreshHotCacheFromDisk();", c));
        assertEquals("Ruhezeit beginnt an beiden Enden der Sitzung", 2, cl.split("regel.sitzungEndet\\(", -1).length - 1);
        assertTrue(sd.contains("regel.setRuheSekunden(s.optInt(\"deterrentQuietSeconds\""));
        assertTrue("eigener Text nur bei echter Aufnahme",
                sd.contains("String headline = recordingNow\n                ? readMessage(")
                        || sd.contains("String headline = recordingNow\r\n                ? readMessage("));

        String eng = lies("src/main/java/at/greenchili/surfmate/surveillance/SurveillanceEngineGpu.java");
        int s = eng.indexOf("public boolean triggerShockEvent(");
        String shock = eng.substring(s, eng.indexOf("private long startRecording()", s));
        assertTrue("Modus a: Abschreckung bei Erschütterung", shock.contains("ScreenDeterrent.getInstance().onMotionDetected();"));
        assertTrue("nur wenn die Aufnahme wirklich startete", shock.indexOf("if (gestartet)") < shock.indexOf("ScreenDeterrent"));
    }

    private static String lies(String p) throws Exception {
        return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8);
    }
}
