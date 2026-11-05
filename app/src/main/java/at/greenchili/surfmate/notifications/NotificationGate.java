package at.greenchili.surfmate.notifications;

import at.greenchili.surfmate.surveillance.Actor;
import at.greenchili.surfmate.surveillance.SurveillanceConfig;

/**
 * NotificationGate — Decides whether a recording's peak severity warrants a
 * push notification, given user-configured tier toggles.
 *
 * Single decision function so the rule lives in one place. Defaults match the
 * SurveillanceConfig defaults: NOTICE off, ALERT on, CRITICAL on.
 */
public final class NotificationGate {

    private NotificationGate() {}

    public static boolean shouldPush(Actor.Severity sev, SurveillanceConfig cfg) {
        if (cfg == null) {
            // Null config means "config not yet loaded" — never silently
            // drop a notification on the user, especially since the
            // start-stage banner already published unconditionally on
            // a null config. Suppressing the final stage would leave the
            // user with a stale "Recording in progress…" banner that
            // never gets replaced.
            return true;
        }
        if (sev == null) return true;  // unknown severity → don't drop
        switch (sev) {
            case CRITICAL: return cfg.isPushCritical();
            case ALERT:    return cfg.isPushAlerts();
            case NOTICE:   return cfg.isPushNotices();
            default:       return false;
        }
    }

    /**
     * Convenience: derive recording-level severity from a list of actors and
     * decide whether to push.
     */
    public static boolean shouldPush(java.util.List<Actor> actors, SurveillanceConfig cfg) {
        return shouldPush(maxSeverity(actors), cfg);
    }

    /**
     * Telegram-tier gate. Reads the per-severity tier toggles
     * (tierNotices/tierAlerts/tierCritical) from
     * {@link at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig} so
     * the daemon process and the app process see the same value the
     * moment the user flips the toggle — no daemon restart needed.
     *
     * The {@code SurveillanceConfig} parameter is ignored and kept only
     * so existing call sites don't have to change argument shape.
     * forceReload() bypasses the per-process mtime cache so a write from
     * the OTHER UID is picked up immediately.
     *
     * Unknown-severity-allows-through is preserved — never silently drop
     * a notification of an unrecognised severity.
     */
    public static boolean shouldTelegram(Actor.Severity sev, SurveillanceConfig cfg) {
        if (sev == null) return true;
        try {
            at.greenchili.surfmate.config.UnifiedConfigManager.forceReload();
            switch (sev) {
                case CRITICAL: return at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig.isTierCritical();
                case ALERT:    return at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig.isTierAlerts();
                case NOTICE:   return at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig.isTierNotices();
                default:       return false;
            }
        } catch (Exception e) {
            // Read failure shouldn't suppress an alert — the user-visible
            // failure mode for "nothing arrives" is much worse than
            // "something arrives that the user didn't want".
            return true;
        }
    }

    public static boolean shouldTelegram(java.util.List<Actor> actors, SurveillanceConfig cfg) {
        return shouldTelegram(maxSeverity(actors), cfg);
    }

    /** 0.1.38.10 (Wächter-KI B2): unter dieser Sichtdauer gilt eine Person auf CLOSE als
     *  „geht nah vorbei“. Feld 02.–04.11.: Vorbeigehende 0–4 s, Verweilende ≥ 8 s. */
    public static final long VORBEIGEHEN_MAX_MS = 6_000L;

    /**
     * B2: Ob eine Person nur nah am Auto vorbeiging. Dann meldet der Wächter sie als NOTICE
     * statt ALERT — Aufnahme, Ereignis und angezeigte Stufe bleiben unverändert.
     * Nur ALERT auf CLOSE ohne Annäherung und kürzer als {@link #VORBEIGEHEN_MAX_MS};
     * VERY_CLOSE/CRITICAL, Annäherung und Verweilen bleiben laut.
     *
     * @param sichtbarMs Sichtdauer über das GANZE Ereignis (erstes bis letztes Sehen). Die
     *                   gemerkte Spitzen-Kopie eines Akteurs friert lastSeen beim Spitzenmoment
     *                   ein und würde einen Verweilenden kurz aussehen lassen — darum liefert der
     *                   Aufrufer die Dauer separat. {@code < 0} = unbekannt → nie dämpfen.
     */
    public static boolean istVorbeigehen(Actor a, long sichtbarMs) {
        if (a == null || sichtbarMs < 0) return false;
        return a.classGroup == Actor.ClassGroup.PERSON
                && a.peakSeverity == Actor.Severity.ALERT
                && a.peakProximity == Actor.Proximity.CLOSE
                && a.trend != Actor.Trend.APPROACHING
                && sichtbarMs < VORBEIGEHEN_MAX_MS;
    }

    /**
     * B2: höchste Stufe für die MELDUNG. Wie {@link #maxSeverity}, aber ein nur nah
     * vorbeigehender Mensch zählt als NOTICE. {@code sichtbarMs} liefert je Akteur die
     * Sichtdauer über das Ereignis (oder -1, wenn unbekannt). Senkt nur ALERT auf NOTICE.
     */
    public static Actor.Severity maxSeverityGedaempft(java.util.List<Actor> actors,
                                                      java.util.function.ToLongFunction<Actor> sichtbarMs) {
        if (actors == null || actors.isEmpty()) return Actor.Severity.NOTICE;
        Actor.Severity max = Actor.Severity.NOTICE;
        for (Actor a : actors) {
            if (a.peakSeverity == null) continue;
            long dauer;
            try {
                dauer = sichtbarMs != null ? sichtbarMs.applyAsLong(a) : -1L;
            } catch (RuntimeException e) {
                dauer = -1L;  // im Zweifel laut lassen
            }
            Actor.Severity s = istVorbeigehen(a, dauer) ? Actor.Severity.NOTICE : a.peakSeverity;
            if (s.ordinal() > max.ordinal()) max = s;
        }
        return max;
    }

    public static Actor.Severity maxSeverity(java.util.List<Actor> actors) {
        if (actors == null || actors.isEmpty()) return Actor.Severity.NOTICE;
        Actor.Severity max = Actor.Severity.NOTICE;
        for (Actor a : actors) {
            if (a.peakSeverity != null && a.peakSeverity.ordinal() > max.ordinal()) {
                max = a.peakSeverity;
            }
        }
        return max;
    }
}
