package at.greenchili.surfmate.services;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Koordination 21.10. (0.1.9): nach dem Umschalten nicht die BYD-Seite vorne stehen lassen – per Intent zurück. */
public class AutoStartRuecksprungTest {

    @Test
    public void zielPanoptesOderHome() {
        assertTrue(AutoStartEnabler.zurueckZuPanoptes("at.greenchili.surfmate", "at.greenchili.surfmate"));
        assertFalse(AutoStartEnabler.zurueckZuPanoptes("com.byd.launcher", "at.greenchili.surfmate"));
        assertFalse(AutoStartEnabler.zurueckZuPanoptes(null, "at.greenchili.surfmate"));
    }

    @Test
    public void amEndeIntentStattTaste() throws Exception {
        String s = new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/services/AutoStartEnabler.java")), StandardCharsets.UTF_8);
        int fin = s.indexOf("} finally {", s.indexOf("public Result run()"));
        int ende = s.indexOf("RUNNING.set(false);", fin);
        String block = s.substring(fin, ende);
        assertTrue(block.contains("zurueckspringen();"));
        assertFalse("keine Tastensimulation am Ende", block.contains("closeDialog()"));
        assertTrue(s.contains("i.addCategory(Intent.CATEGORY_HOME);"));
    }
}
