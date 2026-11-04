package at.greenchili.surfmate.launcher;

/**
 * Rückfall-Regel für den Panoptes Launcher als Standard-Startbildschirm (0.1.38.7). Rein, ohne Android, mit Uhr als
 * Parameter testbar. Der Wächter im Daemon (überlebt den Launcher) füttert sie alle paar Sekunden.
 *
 * <p>Zurück zum BYD-Startbildschirm gibt es in genau diesen Fällen:
 * <ol>
 *   <li><b>Schalter aus</b>, wir sind aber noch Standard (der Launcher war gerade tot oder hat den Alias nicht mehr
 *       abgeschaltet): sofort zurück.</li>
 *   <li><b>Totmann:</b> Schalter an, der Nutzer hat nach {@link #TOTMANN_MS} noch nicht „Behalten“ getippt: zurück, Schalter aus.</li>
 *   <li><b>Absturz-Schleife:</b> Schalter an, wir sind Standard und der Launcher-Prozess ist binnen {@link #FENSTER_MS}
 *       {@link #NEUSTARTS} Mal neu gestartet worden: zurück, Schalter aus.</li>
 * </ol>
 * Nichts anderes löst einen Rückfall aus; es wird nie etwas abgeschaltet, was dem BYD-Launcher gehört.
 */
public final class HomeWaechterRegel {

    public static final long TOTMANN_MS = 5 * 60_000L;
    public static final long FENSTER_MS = 60_000L;
    /** So viele Neustarts des Launcher-Prozesses im Fenster gelten als Absturz-Schleife. */
    public static final int NEUSTARTS = 2;

    public enum Aktion { NICHTS, ZURUECK_SCHALTER_AUS, ZURUECK_TOTMANN, ZURUECK_ABSTURZ }

    private final long[] neustartZeiten = new long[16];
    private int anzahl;
    private String letztePid = "";
    private long eingeschaltetSeit = -1;

    /**
     * Zustand zur Entscheidung.
     *
     * @param wunsch   Schalter „Launcher als Startbildschirm“
     * @param behalten Nutzer hat „Behalten“ getippt
     * @param standard das System löst HOME auf uns auf
     * @param pid      PID des Launcher-Prozesses oder "" (läuft nicht)
     * @param jetztMs  Uhr
     */
    public synchronized Aktion pruefe(boolean wunsch, boolean behalten, boolean standard, String pid, long jetztMs) {
        // Neustart = neue, andere PID als zuletzt gesehen (die erste gesehene PID ist nur der Ausgangspunkt)
        String p = pid == null ? "" : pid;
        if (!p.isEmpty()) {
            if (!letztePid.isEmpty() && !p.equals(letztePid)) merke(jetztMs);
            letztePid = p;
        }

        if (!wunsch) {
            eingeschaltetSeit = -1;
            anzahl = 0;
            return standard ? Aktion.ZURUECK_SCHALTER_AUS : Aktion.NICHTS;
        }
        if (eingeschaltetSeit < 0) eingeschaltetSeit = jetztMs;
        if (!behalten && jetztMs - eingeschaltetSeit >= TOTMANN_MS) return Aktion.ZURUECK_TOTMANN;
        if (standard && neustartsImFenster(jetztMs) >= NEUSTARTS) return Aktion.ZURUECK_ABSTURZ;
        return Aktion.NICHTS;
    }

    /** Nach einem Rückfall neu beginnen. */
    public synchronized void zuruecksetzen() {
        anzahl = 0;
        letztePid = "";
        eingeschaltetSeit = -1;
    }

    private void merke(long jetztMs) {
        if (anzahl == neustartZeiten.length) {
            System.arraycopy(neustartZeiten, 1, neustartZeiten, 0, anzahl - 1);
            anzahl--;
        }
        neustartZeiten[anzahl++] = jetztMs;
    }

    private int neustartsImFenster(long jetztMs) {
        int n = 0;
        for (int i = 0; i < anzahl; i++) if (jetztMs - neustartZeiten[i] <= FENSTER_MS) n++;
        return n;
    }
}
