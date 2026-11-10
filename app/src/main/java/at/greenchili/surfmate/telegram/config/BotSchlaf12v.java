package at.greenchili.surfmate.telegram.config;

import org.json.JSONObject;

/**
 * 0.1.38.12 „Bot schläft bei 12-V-Stopp“: Läuft der Telegram-Bot beim abgestellten Auto ({@code autoStartAccOff},
 * seit 0.1.38.14 auch {@code daemonEnabled}) und urteilt die 12-V-Auswertung des Wächters LOW, schickt der Bot EINE Nachricht und
 * beendet sich bis zur nächsten Fahrt. Keine eigene Schwelle: Quelle ist der Stand, den
 * {@link at.greenchili.surfmate.surveillance.GuardStopMonitor} je Parken schreibt (bei ACC an zurückgesetzt).
 *
 * <p>Der Bot hinterlässt die Sperrmarke {@link #SPERR_GRUND}. Sie ist ein Maschinen-Stopp wie „ACC-on“: Der nächste
 * Start nach dem Abstellen räumt sie weg ({@link #maschinenStopp}). Bei ACC an läuft alles wie vorher.
 */
public final class BotSchlaf12v {

    private BotSchlaf12v() {}

    /** Nur eine frische Messung zählt (der Stand wird mindestens alle 10 min geschrieben). */
    public static final long FRISCH_MS = 30 * 60_000L;

    /** Text der Sperrmarke telegram_bot_daemon.disabled. */
    public static final String SPERR_GRUND = "disabled by 12-V-Schlaf";

    /** 12-V-Stand des Wächters LOW und frisch? {@code stand} = {zyklusMs, letzteMessungMs, low} oder null. */
    public static boolean low(JSONObject stand, long now) {
        if (stand == null || !stand.optBoolean("low", false)) return false;
        long letzte = stand.optLong("letzteMessungMs", 0L);
        return letzte > 0L && now - letzte >= 0L && now - letzte <= FRISCH_MS;
    }

    /**
     * Schlafen legen? 0.1.38.14: jeder Bot, der beim abgestellten Auto läuft – fürs Abstellen gestartet
     * ({@code autoStartAccOff}) ODER dauerhaft eingeschaltet ({@code daemonEnabled}). Bei laufendem Auto gibt es kein
     * LOW (der Stand wird bei ACC an zurückgesetzt).
     */
    public static boolean sollSchlafen(JSONObject stand, long now, boolean autoStartAccOff, boolean daemonEnabled) {
        return (autoStartAccOff || daemonEnabled) && low(stand, now);
    }

    /** Sperrmarke, die Panoptes selbst gesetzt hat (kein Nutzer-Stopp): Der Start nach dem Abstellen darf sie räumen. */
    public static boolean maschinenStopp(String grund) {
        return grund != null && (grund.contains("ACC-on") || grund.contains("stopAllDaemons")
                || grund.contains("12-V-Schlaf"));
    }
}
