package at.greenchili.surfmate.appupdate;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * 0.1.38 (Befund am Auto, 0.1.37): „Öffnen“ verschwand, obwohl der Launcher installiert war. Jetzt immer sichtbar,
 * wenn installiert, mit Hinweis „am Bildschirm im Auto“ und Rückmeldung nach dem Tipp. „phase“ bleibt der reine
 * Installationsstand und wird im Kern nach jedem Lauf geleert (nicht „Prozess läuft“ oder „vorne sichtbar“).
 */
public class LauncherKarteOeffnenTest {

    @Test
    public void oeffnenImmerWennInstalliert() throws Exception {
        String js = lies("app/src/main/assets/web/shared/launcher-karte.js");
        assertTrue(js.contains("$('lncOpen').style.display = inst ? '' : 'none';"));
        assertFalse(js.contains("$('lncOpen').style.display = inst && !laeuft"));
        assertTrue("Rückmeldung ok", js.contains("system.lnc_opened"));
        assertTrue("Rückmeldung abgelehnt in Klartext", js.contains("system.lnc_open_fail"));
        String html = lies("app/src/main/assets/web/local/display.html");
        assertTrue(html.contains("id=\"lncOpenHint\""));
        assertTrue(html.contains("launcher-karte.js?v=3"));
        // 0.1.38 Handy-Einbettung: Karte nicht mehr fest ausgeblendet; aus der Ferne nur „Öffnen“, ohne Recht der Satz des Kerns
        assertFalse(html.contains("html[data-einbettung=\"handy\"] #launcherKarte"));
        assertTrue(js.contains("var amAuto = !fern();"));
        assertTrue(js.contains("$('lncInstall').style.display = amAuto && "));
        assertTrue(js.contains("$('lncRemove').style.display = amAuto && "));
        assertTrue(js.contains("j.recht === 'launcher'"));
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            String k = lies("app/src/main/assets/web/i18n/" + l + ".json");
            assertTrue(l, k.contains("\"lnc_open_hint\"") && k.contains("\"lnc_opened\"") && k.contains("\"lnc_open_fail\""));
        }
    }

    @Test
    public void phaseIstNurDerInstallationsstand() throws Exception {
        String dienst = lies("app/src/main/java/at/greenchili/surfmate/appupdate/AppUpdateDienst.java");
        assertTrue(dienst.contains("o.put(\"phase\", launcherPhase == null ? JSONObject.NULL : launcherPhase);"));
        int fin = dienst.indexOf("launcherAusfuehren(f, mobil);");
        assertTrue(fin > 0 && dienst.indexOf("launcherPhase = null;", fin) > fin);
        String api = lies("app/src/main/java/at/greenchili/surfmate/server/AppUpdateApiHandler.java");
        assertTrue("Öffnen geht über den Kern-Weg", api.contains("LauncherPaket.oeffnen(g)"));
    }

    private static String lies(String rel) throws Exception {
        Path p = Paths.get(rel);
        if (!Files.exists(p)) p = Paths.get("..").resolve(rel);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
