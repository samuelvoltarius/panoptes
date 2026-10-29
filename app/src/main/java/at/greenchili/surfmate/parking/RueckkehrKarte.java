package at.greenchili.surfmate.parking;

import at.greenchili.surfmate.server.Messages;
import at.greenchili.surfmate.surveillance.GuardStopPolicy;

/**
 * 0.1.28 Rückkehr-Karte am Display (Koordination 29.10., Projektinhaber: „der Wächter hat nichts angezeigt“). Bisher ging
 * das Ende einer Parkphase nur an Verlauf/Push/Telegram, ans Display nichts – obwohl der Wächter scharf war und
 * aufgenommen hatte. Diese Klasse entscheidet rein (ohne Android), welche Karte beim Einsteigen kommt:
 *
 * <ul>
 *   <li><b>{@link Art#AUFNAHMEN}</b> – mindestens eine Wächter-Aufnahme: große Karte „Während du weg warst:
 *       n Aufnahmen“ mit [Ansehen] (spielt die erste) und [Schließen]. Bleibt, bis man tippt.</li>
 *   <li><b>{@link Art#NICHTS}</b> – Wächter war scharf, 0 Aufnahmen: kurz „Wächter war scharf – nichts passiert“,
 *       verschwindet von selbst.</li>
 *   <li><b>{@link Art#NICHT_SCHARF}</b> – der Grund in Alltagssprache (Wächter aus, sicherer Ort, Akku schützen …),
 *       verschwindet von selbst.</li>
 *   <li><b>{@link Art#KEINE}</b> – nichts zeigen: Zahl unbekannt, Zustand unbekannt, kurzer Halt ohne Aufnahme.</li>
 * </ul>
 * Nie ein Ort, nie ein Dateiname im Text. Hupe/Licht/Alarm gibt es hier nicht (REGEL A+).
 */
public final class RueckkehrKarte {

    public enum Art { AUFNAHMEN, NICHTS, NICHT_SCHARF, KEINE }

    /** Kürzer geparkt und nichts aufgenommen: keine Karte (Tankstopp, Bäcker …). */
    static final long KURZ_MS = 5 * 60_000L;
    /** So lange darf eine Karte nach dem Ende der Parkphase noch kommen (Ende per Aufsperren, ACC erst später). */
    public static final long GILT_MS = 15 * 60_000L;
    /** Kurzkarten (nichts passiert / nicht scharf) verschwinden nach dieser Zeit. */
    public static final int KURZ_SICHTBAR_SEK = 8;
    /** Karte mit Aufnahmen: Sicherheitsnetz, falls niemand tippt. */
    public static final int AUFNAHMEN_SICHTBAR_SEK = 120;

    public final Art art;
    /** Anzahl Aufnahmen (nur bei {@link Art#AUFNAHMEN}). */
    public final int anzahl;
    /** Grund-Schlüssel unter {@code rueckkehr.grund_*} (nur bei {@link Art#NICHT_SCHARF}). */
    public final String grund;

    private RueckkehrKarte(Art art, int anzahl, String grund) {
        this.art = art;
        this.anzahl = anzahl;
        this.grund = grund;
    }

    /**
     * @param sentryState     Zustand der Parkphase ({@code ParkingSession.SENTRY_*})
     * @param aufnahmen       Wächter-Aufnahmen in der Parkphase, −1 = unbekannt
     * @param waechterStopp   Grund eines Wächter-Stopps während der Parkphase ({@code GuardStopPolicy.REASON_*}) oder null
     * @param dauerMs         Dauer der Parkphase, ≤ 0 = unbekannt
     */
    public static RueckkehrKarte entscheide(String sentryState, int aufnahmen, String waechterStopp, long dauerMs) {
        if (aufnahmen > 0) return new RueckkehrKarte(Art.AUFNAHMEN, aufnahmen, null);
        if (dauerMs > 0 && dauerMs < KURZ_MS) return keine();
        if (waechterStopp != null) {
            String g = stoppGrund(waechterStopp);
            return g == null ? keine() : new RueckkehrKarte(Art.NICHT_SCHARF, 0, g);
        }
        if (sentryState == null) return keine();
        switch (sentryState) {
            case ParkingSession.SENTRY_ARMED:
                // „nichts passiert“ nur, wenn die Zahl wirklich bekannt ist
                return aufnahmen == 0 ? new RueckkehrKarte(Art.NICHTS, 0, null) : keine();
            case ParkingSession.SENTRY_SURVEILLANCE_OFF:
                return new RueckkehrKarte(Art.NICHT_SCHARF, 0, "aus");
            case ParkingSession.SENTRY_SUPPRESSED_SAFE_ZONE:
                return new RueckkehrKarte(Art.NICHT_SCHARF, 0, "sicherer_ort");
            case ParkingSession.SENTRY_SUPPRESSED_SCHEDULE:
                return new RueckkehrKarte(Art.NICHT_SCHARF, 0, "zeit");
            case ParkingSession.SENTRY_VEHICLE_ON_ONLY:
                return new RueckkehrKarte(Art.NICHT_SCHARF, 0, "nur_fahrt");
            // lock_wait / pipeline_down / unknown sind nur der Stand nach 90 s und werden bei jedem Beleg auf „armed“
            // hochgestuft (ParkingController.markSentryArmed). Blieben sie stehen, ist unklar, was war → keine Karte
            // statt eines falschen Grunds.
            default:
                return keine();
        }
    }

    static RueckkehrKarte keine() {
        return new RueckkehrKarte(Art.KEINE, 0, null);
    }

    /** Wächter-Stopp-Grund → Text-Schlüssel, null = unbekannt (dann keine Karte statt eines falschen Grunds). */
    static String stoppGrund(String reason) {
        switch (reason) {
            case GuardStopPolicy.REASON_SOC: return "akku";
            case GuardStopPolicy.REASON_V12: return "v12";
            case GuardStopPolicy.REASON_PARK: return "lange_geparkt";
            case GuardStopPolicy.REASON_WINDOW: return "zeit";
            case GuardStopPolicy.REASON_QUIET: return "sicherer_ort";
            default: return null;
        }
    }

    /** Sichtbar in Sekunden; 0 = bleibt bis zum Tipp (mit Sicherheitsnetz). */
    public int sichtbarSek() {
        return art == Art.AUFNAHMEN ? AUFNAHMEN_SICHTBAR_SEK : KURZ_SICHTBAR_SEK;
    }

    // ------------------------------------------------------------------ Texte (Nutzersprache, Rückfall Englisch)

    public String titel() {
        switch (art) {
            case AUFNAHMEN:
                return Messages.text("rueckkehr.titel_aufnahmen", "While you were away");
            case NICHTS:
                return Messages.text("rueckkehr.titel_nichts", "Guard was on – nothing happened");
            case NICHT_SCHARF:
                return Messages.text("rueckkehr.titel_nicht_scharf", "Guard was not on");
            default:
                return "";
        }
    }

    public String text() {
        switch (art) {
            case AUFNAHMEN:
                return anzahl == 1
                        ? Messages.text("rueckkehr.eine_aufnahme", "1 recording")
                        : Messages.text("rueckkehr.n_aufnahmen", "{0} recordings", anzahl);
            case NICHT_SCHARF:
                return Messages.text("rueckkehr.grund_" + grund, fallbackGrund(grund));
            default:
                return "";
        }
    }

    public static String knopfAnsehen() {
        return Messages.text("rueckkehr.ansehen", "Watch");
    }

    public static String knopfSchliessen() {
        return Messages.text("rueckkehr.schliessen", "Close");
    }

    static String fallbackGrund(String g) {
        switch (g == null ? "" : g) {
            case "aus": return "The guard is switched off.";
            case "sicherer_ort": return "Safe place – the guard takes a break here.";
            case "zeit": return "Outside the guard times you set.";
            case "nur_fahrt": return "Recording is set to driving only.";
            case "akku": return "Stopped to protect the battery.";
            case "v12": return "Stopped to protect the small 12 V battery.";
            case "lange_geparkt": return "Stopped after a long time parked, to save power.";
            default: return "";
        }
    }
}
