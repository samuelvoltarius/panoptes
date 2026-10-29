package at.greenchili.surfmate.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 0.1.28 (Koordination 29.10., ABSCHRECKUNG_TABELLE_20261029): Projektinhaber sah „YOU ARE ON CAMERA“ beim Hingehen nur jedes
 * 2.–3. Mal. (1) Eine Auslösung ohne Person (vorbeifahrendes Auto) verbrauchte die 120 s Ruhe; (2) Anzeige erst beim
 * Aufnahmestart (~4,2 s nach Sichtkontakt); (3) keine Logzeilen. Kein Ton, kein Licht, keine Hupe (REGEL A+).
 */
public class AbschreckungPersonTest {

    private static final long S = 1000L;

    @Test
    public void autoOhnePersonSperrtAlfredNicht() {
        // wie 29.10. 11:46:23 (nur Autos / verworfen) → 11:47:33 Projektinhaber kommt: vorher GESPERRT
        AbschreckungsRegel r = new AbschreckungsRegel();
        long auto = 0, alfred = 70 * S;
        assertTrue(r.darfBeginnen(auto, false));
        r.sitzungBeginnt(auto, false);
        r.sitzungEndet(auto + 15 * S);
        assertTrue(r.inRuhe(alfred));
        assertFalse("weiteres Auto bleibt in der Ruhe (kein Dauerwecken)", r.darfBeginnen(alfred, false));
        assertTrue("Person durchbricht die Ruhe eines Autos", r.darfBeginnen(alfred, true));
        assertFalse(r.ruheVonPerson());
    }

    @Test
    public void ruheNachPersonSperrtWeiterhin() {
        AbschreckungsRegel r = new AbschreckungsRegel();
        r.sitzungBeginnt(0, true);
        r.sitzungEndet(15 * S);
        assertTrue(r.ruheVonPerson());
        assertFalse("nach einer Anzeige mit Person bleibt die Ruhe wie bisher", r.darfBeginnen(60 * S, true));
        assertEquals(75 * S, r.ruheRestMs(60 * S));
        assertTrue(r.darfBeginnen(135 * S, true));
    }

    @Test
    public void erstAutoDannMenschBekommtVolleSitzung() {
        AbschreckungsRegel r = new AbschreckungsRegel();
        r.sitzungBeginnt(0, false);
        r.personDazu(25 * S);                        // Mensch kommt 25 s nach Sitzungsbeginn (Auto)
        assertTrue(r.sitzungMitPerson());
        assertTrue("neuer 30-s-Deckel ab dem Menschen", r.darfVerlaengern(50 * S));
        assertEquals(55 * S, r.sitzungsDeckel());
        r.sitzungEndet(55 * S);
        assertTrue("diese Ruhe stammt von einer Person", r.ruheVonPerson());
    }

    @Test
    public void dauerlaufOhnePersonWecktWeiterHoechstensAlle150s() {
        AbschreckungsRegel r = new AbschreckungsRegel();
        int sitzungen = 0;
        for (long t = 0; t < 3_600_000L; t += 1_000) {
            if (r.laufend()) {
                if (!r.darfVerlaengern(t)) r.sitzungEndet(t);
            } else if (r.darfBeginnen(t, false)) {
                r.sitzungBeginnt(t, false);
                sitzungen++;
            }
        }
        assertEquals(24, sitzungen);
    }

    @Test
    public void logzeileUnterdrueckt() {
        assertEquals("Abschreckung unterdrückt: Ruhezeit nach Anzeige mit Person, noch 75 s (Aufnahme, Person)",
                ScreenDeterrent.unterdruecktZeile("Aufnahme", true, true, 74_200));
        assertEquals("Abschreckung unterdrückt: Ruhezeit nach Anzeige ohne Person, noch 1 s (Nahzone, ohne Person)",
                ScreenDeterrent.unterdruecktZeile("Nahzone", false, false, 1));
    }

    @Test
    public void verdrahtung() throws Exception {
        String sd = lies("src/main/java/at/greenchili/surfmate/surveillance/ScreenDeterrent.java");
        int z = sd.indexOf("public void zeige(boolean person, String grund) {");
        String body = sd.substring(z, sd.indexOf("private void cleanup()", z));
        int acc = body.indexOf("if (isAccUnsafe())");
        assertTrue("bei ACC an weiterhin keine Anzeige – Sperre vor allem anderen", acc > 0 && acc < body.indexOf("regel."));
        assertTrue(body.contains("logger.info(\"Abschreckung gezeigt: \""));
        assertTrue(body.contains("logger.info(unterdruecktZeile("));
        assertTrue("Ton/Licht/Hupe nicht im Spiel", !body.contains("Horn") && !body.contains("flash") && !body.contains("Audio"));

        String eng = lies("src/main/java/at/greenchili/surfmate/surveillance/SurveillanceEngineGpu.java");
        assertTrue("beim ersten Personentreffer", eng.contains("ScreenDeterrent.getInstance().zeige(true, \"Person\");"));
        assertTrue("beim Eintritt in die Nahzone", eng.contains("ScreenDeterrent.getInstance().zeige(personInSequenz(), \"Nahzone\");"));
        assertTrue("beim Aufnahmestart mit Personen-Wissen", eng.contains("ScreenDeterrent.getInstance().zeige(personInSequenz(), \"Aufnahme\");"));
        int nah = eng.indexOf("\"Nahzone\"");
        assertTrue("Nahzone nur beim ersten NEAR der Sequenz",
                eng.lastIndexOf("if (!peakNearDuringSequence) {", nah) > eng.lastIndexOf("proxNear.tier == DistanceEstimator.Tier.NEAR", nah));
        assertTrue("Cloud-Abschreckung unverändert nur beim Aufnahmestart",
                !eng.substring(nah - 600, nah).contains("BydCloudDeterrent"));
    }

    private static String lies(String p) throws Exception {
        return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8);
    }
}
