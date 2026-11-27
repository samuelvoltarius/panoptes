package at.greenchili.surfmate.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 0.1.43.22 (Upstream OverDrive #1126): Autostart-Schalter nur glauben, wenn er frisch und checkbar gelesen wurde;
 * „zuletzt geprüft“ je Installation lesbar. Der BYD-Schalter ist eine SPERR-Liste: AN = gesperrt, AUS = erlaubt.
 */
public class AutostartBeobachtungTest {

    @Test
    public void nurGelesenerSchalterZaehlt() {
        assertEquals("erlaubt", AutostartBeobachtung.zustandAus(AutoStartEnabler.Result.SUCCESS));
        assertEquals("erlaubt", AutostartBeobachtung.zustandAus(AutoStartEnabler.Result.ALREADY_OFF));
        assertEquals("AN nach dem Klick = gesperrt", "gesperrt",
                AutostartBeobachtung.zustandAus(AutoStartEnabler.Result.STILL_ON));
        for (AutoStartEnabler.Result r : new AutoStartEnabler.Result[] {
                AutoStartEnabler.Result.FLIP_UNCONFIRMED, AutoStartEnabler.Result.NO_DIALOG,
                AutoStartEnabler.Result.NO_ROW, AutoStartEnabler.Result.NOT_THIS_FIRMWARE,
                AutoStartEnabler.Result.NICHT_BELEGT}) {
            assertEquals(r.name(), "unbekannt", AutostartBeobachtung.zustandAus(r));
        }
        assertEquals("unbekannt", AutostartBeobachtung.zustandAus(null));
        assertFalse("nicht belegte Plattform: nichts gelesen, nichts merken",
                AutostartBeobachtung.merkenswert(AutoStartEnabler.Result.NICHT_BELEGT));
        assertFalse(AutostartBeobachtung.merkenswert(null));
        assertTrue(AutostartBeobachtung.merkenswert(AutoStartEnabler.Result.FLIP_UNCONFIRMED));
    }

    @Test
    public void standGiltNurFuerDieseInstallation() {
        long jetzt = 1_800_000_000_000L;
        AutostartBeobachtung.Stand s = AutostartBeobachtung.bewerte(500L, 500L, jetzt - 60_000L, "erlaubt", "SUCCESS", jetzt);
        assertEquals("erlaubt", s.zustand);
        assertEquals(jetzt - 60_000L, s.geprueftZeit);
        assertEquals("geprueft=erlaubt geprueftZeit=" + (jetzt - 60_000L) + " geprueftErgebnis=SUCCESS", s.alsDaten());
        // Neue Installation setzt die BYD-Sperre zurück → alter Wert gilt nicht
        assertEquals("unbekannt", AutostartBeobachtung.bewerte(600L, 500L, jetzt - 1L, "erlaubt", "SUCCESS", jetzt).zustand);
        assertEquals(0L, AutostartBeobachtung.bewerte(600L, 500L, jetzt - 1L, "erlaubt", "SUCCESS", jetzt).geprueftZeit);
        assertEquals("unbekannt", AutostartBeobachtung.bewerte(0L, 0L, jetzt - 1L, "erlaubt", "", jetzt).zustand);
        assertEquals("Zeit in der Zukunft", "unbekannt",
                AutostartBeobachtung.bewerte(500L, 500L, jetzt + 5L, "gesperrt", "STILL_ON", jetzt).zustand);
        assertEquals("unbekannt", AutostartBeobachtung.bewerte(500L, 500L, 0L, "gesperrt", "STILL_ON", jetzt).zustand);
        assertEquals("fremder Text", "unbekannt",
                AutostartBeobachtung.bewerte(500L, 500L, jetzt - 1L, "frei", "", jetzt).zustand);
        assertEquals("gesperrt", AutostartBeobachtung.bewerte(500L, 500L, jetzt - 1L, "gesperrt", "STILL_ON", jetzt).zustand);
        assertEquals("geprueft=unbekannt geprueftZeit=0",
                AutostartBeobachtung.bewerte(1L, 2L, 3L, "x", "", jetzt).alsDaten());
    }

    /** Quelltext-Vertrag: Plattform-Bedingung unverändert, frisch + checkbar lesen vor dem Klick, STILL_ON. */
    @Test
    public void enablerVertrag() throws Exception {
        String e = lies("src/main/java/at/greenchili/surfmate/services/AutoStartEnabler.java");
        String versuch = e.substring(e.indexOf("public Result attemptOnce()"));
        int auffrischen = versuch.indexOf("if (!surfmateSwitch.refresh()) {");
        int checkbar = versuch.indexOf("if (surfmateSwitch == null || !surfmateSwitch.isCheckable()) {");
        int lesen = versuch.indexOf("boolean checkedBefore = surfmateSwitch.isChecked();");
        int klick = versuch.indexOf("boolean clicked = clickSwitch(surfmateSwitch);");
        assertTrue(auffrischen > 0 && auffrischen < checkbar && checkbar < lesen && lesen < klick);
        assertTrue(versuch.contains("surfmateSwitch.refresh() && surfmateSwitch.isCheckable() && !surfmateSwitch.isChecked()"));
        assertTrue(versuch.contains("freshSwitch != null && freshSwitch.isCheckable()"));
        assertTrue(versuch.contains("return Result.STILL_ON;"));
        // Sperrliste: AN = gesperrt bleibt die dokumentierte Bedeutung
        assertTrue(e.contains("(ON=blocked, OFF=autostart-allowed)"));
        // „belegt“-Bedingung unverändert (0.1.43.21)
        assertTrue(e.contains("return belegt ? null : Result.NICHT_BELEGT;"));
        String k = lies("src/main/java/at/greenchili/surfmate/services/KeepAliveAccessibilityService.java");
        int vorab = k.indexOf("result = AutoStartEnabler.vorab(p.getFirst(), p.getSecond());");
        int lauf = k.indexOf("if (result == null) result = enabler.run();");
        int merke = k.indexOf("AutostartBeobachtung.merke(getApplicationContext(), result);");
        assertTrue(vorab > 0 && vorab < lauf && lauf < merke);
        String n = lies("src/main/java/at/greenchili/surfmate/appupdate/AutostartNachUpdate.java");
        assertTrue(n.contains("(e.belegt ? \"Klick erlaubt\" : \"kein Klick\")"));
        String r = lies("src/main/java/at/greenchili/surfmate/receiver/AutoStartCheckReceiver.java");
        assertTrue(r.contains("setResult(fehlt ? 0 : 1, \"autostartMissing=\" + fehlt + \" \" + geprueft(app), null);"));
        assertTrue(r.contains("+ \" autostartMissing=\" + fehlt + \" \" + geprueft(app), null);"));
    }

    private static String lies(String pfad) throws Exception {
        return new String(Files.readAllBytes(new File(pfad).toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
