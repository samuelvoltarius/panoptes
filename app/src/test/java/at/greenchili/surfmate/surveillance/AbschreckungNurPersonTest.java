package at.greenchili.surfmate.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 0.1.29.1 (Projektinhaber 29.10. „ja“, Befund Deploy 2 29.10. 16:30 – 30.10. 00:18): Die Abschreckung zeigte ~33× für 15 s,
 * meist „Nahzone (ohne Person)“ alle 10–30 min. Ab jetzt beginnt eine Anzeige nur mit Personentreffer (alle Autos);
 * die frühe Anzeige mit Person vor der Aufnahme bleibt.
 */
public class AbschreckungNurPersonTest {

    @Test
    public void neueAnzeigeNurMitPerson() {
        assertFalse("Nahzone ohne Person: nichts", AbschreckungsRegel.darfAnzeigen(false, false));
        assertTrue("Personentreffer", AbschreckungsRegel.darfAnzeigen(false, true));
        assertTrue("laufende Sitzung (mit Person) darf weiterlaufen", AbschreckungsRegel.darfAnzeigen(true, false));
        assertTrue(AbschreckungsRegel.darfAnzeigen(true, true));
    }

    @Test
    public void nachtMitAutosWecktNie() {
        // 29.10. abends: alle 10–30 min eine Nahzone ohne Person → 0 Anzeigen; um 16:49 kommt eine Person → Anzeige
        AbschreckungsRegel r = new AbschreckungsRegel();
        int anzeigen = 0;
        for (long t = 0; t < 8 * 3_600_000L; t += 15 * 60_000L) {
            if (AbschreckungsRegel.darfAnzeigen(r.laufend(), false) && r.darfBeginnen(t, false)) anzeigen++;
        }
        assertEquals(0, anzeigen);
        assertTrue(AbschreckungsRegel.darfAnzeigen(r.laufend(), true) && r.darfBeginnen(8 * 3_600_000L, true));
    }

    @Test
    public void logzeile() {
        assertEquals("Abschreckung nicht gezeigt: keine Person (Nahzone)", ScreenDeterrent.nichtGezeigtZeile("Nahzone"));
        assertEquals(60_000L, ScreenDeterrent.OHNE_PERSON_LOG_MS);
    }

    @Test
    public void verdrahtung() throws Exception {
        String sd = lies("src/main/java/at/greenchili/surfmate/surveillance/ScreenDeterrent.java");
        int z = sd.indexOf("public void zeige(boolean person, String grund) {");
        String body = sd.substring(z, sd.indexOf("private void cleanup()", z));
        int acc = body.indexOf("if (isAccUnsafe())");
        int sperre = body.indexOf("if (!AbschreckungsRegel.darfAnzeigen(laufend, person)) {");
        assertTrue("ACC-Sperre bleibt zuerst", acc > 0 && acc < sperre);
        assertTrue("Personen-Sperre vor Ruhe, Verlängerung und Beginn",
                sperre > 0 && sperre < body.indexOf("regel.personDazu(") && sperre < body.indexOf("regel.sitzungBeginnt("));
        assertTrue(body.contains("logger.info(nichtGezeigtZeile(grund));"));
        // frühe Anzeige mit Person vor der Aufnahme unverändert
        String eng = lies("src/main/java/at/greenchili/surfmate/surveillance/SurveillanceEngineGpu.java");
        assertTrue(eng.contains("ScreenDeterrent.getInstance().zeige(true, \"Person\");"));
    }

    private static String lies(String p) throws Exception {
        return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8);
    }
}
