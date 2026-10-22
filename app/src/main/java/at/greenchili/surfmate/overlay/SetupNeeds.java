package at.greenchili.surfmate.overlay;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Was fehlt nach einer Installation wirklich? Reine Entscheidung (getestet), damit der
 * Einrichtungsdialog nur erscheint, wenn es etwas zu tun gibt (Koordination 21.10.2026:
 * am 21.10. stand ein englischer Dialog im Vordergrund, obwohl die App lief).
 *
 * <p>Autostart (BYD „Deaktiver Autostart“ wird bei jeder Installation zurückgesetzt) gilt für
 * die aktuelle Installation als erledigt, wenn
 * <ul>
 *   <li>der AutoStartEnabler für genau diese Installation Erfolg gemeldet hat, oder</li>
 *   <li>das Deploy-Skript ({@code deploy/autostart-check.sh}) den Schalter im BYD-Dialog
 *       geprüft bzw. umgelegt hat und das in {@code Settings.Global panoptes_autostart_ok}
 *       mit dem {@code lastUpdateTime} der Installation bestätigt, oder</li>
 *   <li>es auf dem Gerät keine BYD-Autostart-Verwaltung gibt (DiLink 5 bzw. App fehlt).</li>
 * </ul>
 */
public final class SetupNeeds {

    /** Settings.Global-Schlüssel, den das Deploy-Skript nach erfolgreicher Prüfung setzt. */
    public static final String GLOBAL_AUTOSTART_OK = "panoptes_autostart_ok";
    /** Erlaubte Abweichung zwischen dumpsys-Zeitstempel (Sekunden) und PackageInfo (ms). */
    static final long INSTALL_TIME_TOLERANCE_MS = 2_000L;

    public final boolean autostartMissing;
    public final boolean overlayMissing;

    private SetupNeeds(boolean autostartMissing, boolean overlayMissing) {
        this.autostartMissing = autostartMissing;
        this.overlayMissing = overlayMissing;
    }

    public boolean anything() { return autostartMissing || overlayMissing; }

    /** Was nach dem Start gezeigt wird. */
    public enum Anzeige { NICHTS, EINZELHINWEIS, ERSTE_SCHRITTE }

    /**
     * Bestandsnutzer (Koordination 21.10.: nach dem Update auf 0.1.7.2 sah Projektinhaber „Die ersten
     * Schritte“ erneut) bekommen nie den ganzen Ablauf, höchstens einen Einzelhinweis für das,
     * was wirklich fehlt. Bestand = der Dialog wurde schon einmal für eine frühere Installation
     * gesehen, oder Autostart war schon einmal bestätigt, oder das Onboarding ist abgeschlossen.
     *
     * @param installTimeMs     PackageInfo.lastUpdateTime der laufenden Installation
     * @param lastSeenMs        zuletzt gesehene Installation (0 = nie)
     * @param enablerOkMs       Installation mit bestätigtem Autostart (0 = nie)
     * @param onboardingFertig  OnboardingState.onboardingComplete
     */
    public static Anzeige anzeige(long installTimeMs, long lastSeenMs, long enablerOkMs,
                                  boolean onboardingFertig, SetupNeeds needs) {
        return anzeige(installTimeMs, lastSeenMs, enablerOkMs, onboardingFertig, needs,
                System.currentTimeMillis(), 0L);
    }

    /**
     * 0.1.10 Teil K (SETUP_WIZARD „Erinnerungen“): Fehlt der Autostart (BYD setzt ihn bei jedem Auto- bzw.
     * App-Update zurück), kommt der Hinweis bei JEDEM Start – auch wenn er für diese Installation schon
     * gesehen war. Wegdrücken geht nur „heute nicht mehr“ (bis Mitternacht, {@link #heuteNichtMehrBis}),
     * nie dauerhaft. Fehlt nur die Überlagerung, bleibt es bei einmal je Installation.
     *
     * @param jetztMs            aktuelle Zeit
     * @param heuteNichtBisMs    gespeichertes „heute nicht mehr“ (0 = nie gewählt)
     */
    public static Anzeige anzeige(long installTimeMs, long lastSeenMs, long enablerOkMs,
                                  boolean onboardingFertig, SetupNeeds needs, long jetztMs, long heuteNichtBisMs) {
        if (needs.autostartMissing) {
            if (jetztMs < heuteNichtBisMs) return Anzeige.NICHTS;
            boolean bestand = lastSeenMs > 0 || enablerOkMs > 0 || onboardingFertig;
            return bestand ? Anzeige.EINZELHINWEIS : Anzeige.ERSTE_SCHRITTE;
        }
        if (installTimeMs > 0 && installTimeMs <= lastSeenMs) return Anzeige.NICHTS;
        if (!needs.anything()) return Anzeige.NICHTS;
        boolean bestand = lastSeenMs > 0 || enablerOkMs > 0 || onboardingFertig;
        return bestand ? Anzeige.EINZELHINWEIS : Anzeige.ERSTE_SCHRITTE;
    }

    /**
     * @param installTimeMs          PackageInfo.lastUpdateTime der laufenden Installation
     * @param enablerOkForInstallMs  gespeicherter Installationszeitpunkt eines Enabler-Erfolgs (0 = nie)
     * @param deployConfirmation     Wert von {@link #GLOBAL_AUTOSTART_OK} (dumpsys-Format
     *                               „yyyy-MM-dd HH:mm:ss“ in Gerätezeitzone) oder null
     * @param autostartManagerPresent ob es die BYD-Autostart-Verwaltung überhaupt gibt
     */
    public static SetupNeeds evaluate(long installTimeMs, long enablerOkForInstallMs,
                                      String deployConfirmation, boolean autostartManagerPresent,
                                      boolean overlayGranted, TimeZone tz) {
        boolean autostartOk = !autostartManagerPresent
                || (installTimeMs > 0 && enablerOkForInstallMs == installTimeMs)
                || confirmsInstall(deployConfirmation, installTimeMs, tz);
        return new SetupNeeds(!autostartOk, !overlayGranted);
    }

    /** „Heute nicht mehr“: unterdrückt bis zur nächsten lokalen Mitternacht. */
    public static long heuteNichtMehrBis(long jetztMs, TimeZone tz) {
        java.util.Calendar c = java.util.Calendar.getInstance(tz == null ? TimeZone.getDefault() : tz);
        c.setTimeInMillis(jetztMs);
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        c.add(java.util.Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    static boolean confirmsInstall(String confirmation, long installTimeMs, TimeZone tz) {
        if (confirmation == null || installTimeMs <= 0) return false;
        String s = confirmation.trim();
        if (s.isEmpty()) return false;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
            f.setLenient(false);
            f.setTimeZone(tz == null ? TimeZone.getDefault() : tz);
            long t = f.parse(s).getTime();
            return Math.abs(t - installTimeMs) < INSTALL_TIME_TOLERANCE_MS;
        } catch (ParseException e) {
            return false;
        }
    }
}
