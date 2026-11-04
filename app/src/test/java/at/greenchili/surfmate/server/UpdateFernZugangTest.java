package at.greenchili.surfmate.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.auth.Kopplungen;

import org.junit.Test;

/**
 * 0.1.38.2 (UPDATE_API_CLIENTS.md): {@code /api/appupdate/fern} nur für Handy und Cockpit (Kopplung) bzw. Handy-Web;
 * Home Assistant und Dienste nie; Starten nur mit Recht „update“, das das Cockpit jetzt tragen kann (Standard aus).
 */
public class UpdateFernZugangTest {

    private static final String P = AppUpdateApiHandler.FERN_PFAD;

    @Test
    public void nurHandyUndCockpit() {
        assertFalse(ExternGuard.blocks("GET", P, "handy"));
        assertFalse(ExternGuard.blocks("POST", P, "handy"));
        assertFalse(ExternGuard.blocks("GET", P, "cockpit"));
        assertFalse(ExternGuard.blocks("POST", P + "?sprache=de", "cockpit"));
        assertTrue(ExternGuard.blocks("POST", P, "homeassistant"));
        assertTrue(ExternGuard.blocks("GET", P, Kopplungen.ART_DIENST));
        assertTrue(ExternGuard.blocks("POST", P, null));
        assertTrue(ExternGuard.blocks("PUT", P, "handy"));
        assertTrue(ExternGuard.blocks("POST", P + "/../installieren", "handy"));
    }

    @Test
    public void installierenAmLauncherBleibtFuerKopplungenZu() {
        // Launcher-Installieren von außen nur über /fern; /api/appupdate/launcher lässt nur „oeffnen“ durch
        assertFalse(AppUpdateApiHandler.nurOeffnen("{\"aktion\":\"installieren\"}"));
        assertTrue(ExternGuard.blocks("POST", "/api/appupdate/installieren", "handy"));
        assertTrue(ExternGuard.blocks("POST", "/api/appupdate/installieren", "cockpit"));
    }

    @Test
    public void handyWebLesenImmerStartenNurMitRecht() {
        assertTrue(WebHandyGuard.updateErlaubt("GET", P, false));
        assertFalse(WebHandyGuard.updateErlaubt("POST", P, false));
        assertTrue(WebHandyGuard.updateErlaubt("POST", P, true));
        assertFalse(WebHandyGuard.updateErlaubt("POST", "/api/appupdate/launcher", true));
    }

    @Test
    public void nurHandyUndCockpitAlsArt() {
        assertTrue(AppUpdateApiHandler.darfArt("handy"));
        assertTrue(AppUpdateApiHandler.darfArt("cockpit"));
        assertFalse(AppUpdateApiHandler.darfArt("homeassistant"));
        assertFalse(AppUpdateApiHandler.darfArt(Kopplungen.ART_DIENST));
        assertFalse(AppUpdateApiHandler.darfArt(null));
    }

    @Test
    public void cockpitKannUpdateTragenHomeAssistantNicht() {
        assertTrue(Kopplungen.COCKPIT_RECHTE.contains(Kopplungen.RECHT_UPDATE));
        assertFalse(Kopplungen.FREMD_RECHTE.contains(Kopplungen.RECHT_UPDATE));
    }
}
