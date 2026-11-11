package at.greenchili.surfmate.onboarding.schritte;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 0.1.39 A1 (Koordination 10.11.): Der Fertig-Schritt bietet keine „Kurze Führung durch Panoptes?“ mehr an. */
public class KeineFuehrungTest {

    private static final String B = "src/main/java/at/greenchili/surfmate/onboarding/";

    @Test
    public void fertigSchrittOhneFuehrung() throws Exception {
        String w = lies(B + "schritte/WeitereSchritte.kt");
        assertFalse("kein Knopf „Ja, zeig mir Panoptes“", w.contains("R.string.einr_rundgang_ja"));
        assertFalse(w.contains("R.string.einr_rundgang_frage"));
        assertFalse(w.contains("rundgangFrage"));
        assertFalse("kein Host-Weg mehr", w.contains("fun rundgang()"));
        int f = w.indexOf("class FertigSchritt");
        String fs = w.substring(f);
        assertTrue("Abschluss direkt nach dem Speichern",
                fs.contains("if (!ok) { fertig(false, text); return@speichereAntwort }\n            a.beenden(true)")
                        || fs.contains("if (!ok) { fertig(false, text); return@speichereAntwort }\r\n            a.beenden(true)"));
        assertFalse(lies(B + "schritte/AssistentAnbindung.kt").contains("rundgang"));
        assertFalse(lies(B + "OnboardingHost.kt").contains("rundgangStarten"));
    }

    private static String lies(String rel) throws Exception {
        Path p = Paths.get(rel);
        if (!Files.exists(p)) p = Paths.get("app").resolve(rel);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
