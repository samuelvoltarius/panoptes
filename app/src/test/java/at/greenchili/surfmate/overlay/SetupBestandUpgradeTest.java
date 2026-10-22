package at.greenchili.surfmate.overlay;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.TimeZone;

/**
 * Upgrade-Pfad 0.1.7 → 0.1.8 (Koordination 21.10.): Bestandsnutzer sehen nie „Die ersten Schritte“,
 * höchstens einen Einzelhinweis für das, was nach der Installation wirklich fehlt.
 */
public class SetupBestandUpgradeTest {

    private static final TimeZone TZ = TimeZone.getTimeZone("Europe/Vienna");
    private static final long INSTALL_017 = 1_760_000_000_000L;
    private static final long INSTALL_018 = INSTALL_017 + 86_400_000L;

    private static SetupNeeds needs(long install, long enablerOk, boolean overlay) {
        return SetupNeeds.evaluate(install, enablerOk, null, true, overlay, TZ);
    }

    @Test
    public void bestandNachUpdateMitFehlendemAutostartBekommtNurEinzelhinweis() {
        // 0.1.7: Dialog gesehen, Autostart bestätigt. Update auf 0.1.8 → BYD hat Autostart zurückgesetzt.
        SetupNeeds n = needs(INSTALL_018, INSTALL_017, true);
        assertEquals(SetupNeeds.Anzeige.EINZELHINWEIS,
                SetupNeeds.anzeige(INSTALL_018, INSTALL_017, INSTALL_017, true, n));
    }

    @Test
    public void bestandOhneFehlendesSiehtNichts() {
        SetupNeeds n = needs(INSTALL_018, INSTALL_018, true);
        assertEquals(SetupNeeds.Anzeige.NICHTS,
                SetupNeeds.anzeige(INSTALL_018, INSTALL_017, INSTALL_018, true, n));
    }

    @Test
    public void bestandNurUeberOnboardingErkanntAuchOhneDialogMarker() {
        // z. B. Deploy hat Autostart bestätigt, Dialog nie gesehen, Onboarding fertig.
        SetupNeeds n = needs(INSTALL_018, 0L, false);
        assertEquals(SetupNeeds.Anzeige.EINZELHINWEIS,
                SetupNeeds.anzeige(INSTALL_018, 0L, 0L, true, n));
    }

    @Test
    public void neueInstallationBekommtErsteSchritte() {
        SetupNeeds n = needs(INSTALL_018, 0L, false);
        assertEquals(SetupNeeds.Anzeige.ERSTE_SCHRITTE,
                SetupNeeds.anzeige(INSTALL_018, 0L, 0L, false, n));
    }

    /** Nur die Überlagerung fehlt: weiterhin einmal je Installation. */
    @Test
    public void gleicheInstallationNichtNochmal() {
        SetupNeeds n = needs(INSTALL_018, INSTALL_018, false);
        assertEquals(SetupNeeds.Anzeige.NICHTS,
                SetupNeeds.anzeige(INSTALL_018, INSTALL_018, INSTALL_018, false, n));
    }

    /**
     * 0.1.10 Teil K: Autostart fehlt nach einem BYD-Update ⇒ Hinweis kommt erneut, auch wenn er für diese
     * Installation früher „gesehen“ war (bis 0.1.9: einmal je Installation, „Später“ zählte als gesehen).
     */
    @Test
    public void autostartFehltKommtJedenStartWieder() {
        SetupNeeds n = needs(INSTALL_018, 0L, true);
        assertEquals(SetupNeeds.Anzeige.EINZELHINWEIS,
                SetupNeeds.anzeige(INSTALL_018, INSTALL_018, INSTALL_017, true, n, INSTALL_018 + 1_000L, 0L));
    }

    @Test
    public void heuteNichtMehrBisMitternacht() {
        SetupNeeds n = needs(INSTALL_018, 0L, true);
        long jetzt = 1_792_656_000_000L;   // 2026-10-22 08:00 UTC = 10:00 Wien
        long bis = SetupNeeds.heuteNichtMehrBis(jetzt, TZ);
        assertEquals(1_792_706_400_000L, bis);   // 2026-10-23 00:00 Wien (MESZ, UTC+2)
        assertEquals(SetupNeeds.Anzeige.NICHTS,
                SetupNeeds.anzeige(INSTALL_018, INSTALL_018, INSTALL_017, true, n, jetzt + 3_600_000L, bis));
        assertEquals("nach Mitternacht wieder", SetupNeeds.Anzeige.EINZELHINWEIS,
                SetupNeeds.anzeige(INSTALL_018, INSTALL_018, INSTALL_017, true, n, bis + 1L, bis));
    }
}
