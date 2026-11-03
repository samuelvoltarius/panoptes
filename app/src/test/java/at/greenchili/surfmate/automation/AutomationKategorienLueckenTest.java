package at.greenchili.surfmate.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import org.junit.Test;

/**
 * 0.1.38: Die elf Regel-Aktionen, die bisher unter „Sonstiges“ lagen (UNGENUTZTE_FUNKTIONEN_0137 §3), haben eine
 * Kategorie. Die neue Kategorie „charging“ steht in Java, Web und beiden Sprachkatalogen.
 */
public class AutomationKategorienLueckenTest {

    @Test
    public void elfAktionenNichtMehrUnterSonstiges() {
        for (String id : new String[] {"windowsVent", "remoteClimateSchedule", "remoteClimateScheduleUpdate",
                "remoteClimateScheduleDelete", "smartCharging", "chargingSchedule", "startChargingNow",
                "wallboxModus", "wallboxStrom", "camping", "ladeplan"}) {
            assertNotEquals(id, AutomationCategories.OTHER, AutomationCategories.forId(id));
        }
        assertEquals(AutomationCategories.CHARGING, AutomationCategories.forId("wallboxStrom"));
        assertEquals(AutomationCategories.CLIMATE, AutomationCategories.forId("remoteClimateSchedule"));
        assertEquals(AutomationCategories.WINDOWS_BODY, AutomationCategories.forId("windowsVent"));
    }

    @Test
    public void ladenKategorieUeberallBekannt() throws Exception {
        assertTrue(Arrays.asList(AutomationCategories.ORDER).contains("charging"));
        for (String f : new String[] {"web/shared/automations.js", "web/shared/key-mapping.js"}) {
            assertTrue(f, lies("app/src/main/assets/" + f).contains("'climate', 'charging', 'windows_body'"));
        }
        for (String lang : new String[] {"de", "en", "fr", "it", "nl"}) {
            assertTrue(lang, lies("app/src/main/assets/web/i18n/" + lang + ".json").contains("\"category_charging\""));
            assertTrue(lang, lies("app/src/main/assets/server-i18n/" + lang + ".json").contains("\"category_charging\""));
        }
    }

    private static String lies(String rel) throws Exception {
        Path p = Paths.get(rel);
        if (!Files.exists(p)) p = Paths.get("..").resolve(rel);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
