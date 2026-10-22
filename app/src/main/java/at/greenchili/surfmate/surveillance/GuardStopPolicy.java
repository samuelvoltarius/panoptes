package at.greenchili.surfmate.surveillance;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Wächter-Stopp ("Wächter stoppt, wenn …", 21.10.2026), reine Entscheidung ohne Android.
 *
 * <p>Schema laut Koordination (Config-Abschnitt {@code guardStop}, gepflegt über
 * {@code /api/system/guardstop}):
 * {@code {enabled, socBelow 20, v12Below 12.2, v12Resume 12.5, maxParkHours 24, windows[],
 * useQuietZones, reenable, notify}}. 0 schaltet die jeweilige Schwelle ab.
 *
 * <p>Gründe (gleiche Kennungen wie die Oberfläche {@code waechter.html}): {@code v12}, {@code soc},
 * {@code park}, {@code window}, {@code quietzone}.
 *
 * <p>Wieder-Ein: {@code window} und {@code quietzone} sind Zeit- bzw. Ortsregeln und heben sich
 * immer selbst auf. {@code v12} und {@code soc} heben sich nur mit {@code reenable} auf (12 V über
 * {@code v12Resume}, Ladestand {@link #SOC_HYSTERESIS} Punkte über der Schwelle). {@code park}
 * gilt bis zur nächsten Fahrt. Jede Fahrt (ACC an) setzt alles zurück.
 */
public final class GuardStopPolicy {

    public static final String REASON_V12 = "v12";
    public static final String REASON_SOC = "soc";
    public static final String REASON_PARK = "park";
    public static final String REASON_WINDOW = "window";
    public static final String REASON_QUIET = "quietzone";

    static final double SOC_HYSTERESIS = 2.0;

    /** Gelesene Einstellungen mit Standardwerten. */
    public static final class Config {
        public boolean enabled = true;
        public double socBelow = 20;
        public double v12Below = 12.2;
        public double v12Resume = 12.5;
        public double maxParkHours = 24;
        public JSONArray windows = new JSONArray();
        public boolean useQuietZones = true;
        public boolean reenable = true;
        public boolean notify = true;

        public static Config from(JSONObject j) {
            return from(j, null);
        }

        /**
         * @param v12Standard {stopp, wieder} nach Akku-Chemie (AkkuChemie.v12Standard) oder null
         *                    für die Bleisäure-Werte des Koordinationsschemas
         */
        public static Config from(JSONObject j, double[] v12Standard) {
            Config c = new Config();
            if (v12Standard != null && v12Standard.length == 2) {
                c.v12Below = v12Standard[0];
                c.v12Resume = v12Standard[1];
            }
            if (j == null) return c;
            c.enabled = j.optBoolean("enabled", c.enabled);
            c.socBelow = j.optDouble("socBelow", c.socBelow);
            c.v12Below = j.optDouble("v12Below", c.v12Below);
            c.v12Resume = j.optDouble("v12Resume", c.v12Resume);
            c.maxParkHours = j.optDouble("maxParkHours", c.maxParkHours);
            JSONArray w = j.optJSONArray("windows");
            if (w != null) c.windows = w;
            c.useQuietZones = j.optBoolean("useQuietZones", c.useQuietZones);
            c.reenable = j.optBoolean("reenable", c.reenable);
            c.notify = j.optBoolean("notify", c.notify);
            if (c.v12Below > 0 && c.v12Resume <= c.v12Below) c.v12Resume = c.v12Below + 0.3;
            return c;
        }
    }

    /** Momentaufnahme der Eingänge für einen Takt. */
    public static final class Inputs {
        public long nowMs;
        /** ISO-Wochentag 1 = Montag … 7 = Sonntag. */
        public int isoDayOfWeek;
        public int minuteOfDay;
        public long parkedSinceMs;
        public double socPercent = Double.NaN;
        public Battery12vEvaluator.State v12 = Battery12vEvaluator.State.UNKNOWN;
        public boolean inQuietZone;
    }

    private boolean active;
    private String reason;
    private long sinceMs;

    public synchronized boolean isActive() { return active; }
    public synchronized String reason() { return active ? reason : null; }
    public synchronized long sinceMs() { return active ? sinceMs : 0L; }

    /** Fahrt (ACC an) oder Abschalten: alles zurück. */
    public synchronized void reset() {
        active = false;
        reason = null;
        sinceMs = 0L;
    }

    /**
     * Einen Takt auswerten.
     *
     * @return {@code true}, wenn sich {@link #isActive()} oder {@link #reason()} geändert hat.
     */
    public synchronized boolean step(Config c, Inputs in) {
        boolean wasActive = active;
        String wasReason = reason;
        if (c == null || !c.enabled) {
            reset();
            return wasActive;
        }
        String trigger = firstTrigger(c, in);
        if (!active) {
            if (trigger != null) {
                active = true;
                reason = trigger;
                sinceMs = in.nowMs;
            }
        } else if (trigger != null && rank(trigger) < rank(reason)) {
            // Ein dringenderer Grund ersetzt den alten (z. B. 12 V während der Ruhezeit).
            reason = trigger;
        } else if (released(c, in)) {
            if (trigger != null) {
                reason = trigger;
                sinceMs = in.nowMs;
            } else {
                reset();
            }
        }
        return wasActive != active || !eq(wasReason, reason);
    }

    private boolean released(Config c, Inputs in) {
        switch (reason) {
            case REASON_WINDOW:
                return !inAnyWindow(c.windows, in.isoDayOfWeek, in.minuteOfDay);
            case REASON_QUIET:
                return !(c.useQuietZones && in.inQuietZone);
            case REASON_V12:
                return c.reenable && (c.v12Below <= 0 || in.v12 == Battery12vEvaluator.State.OK);
            case REASON_SOC:
                return c.reenable && (c.socBelow <= 0
                        || (!Double.isNaN(in.socPercent) && in.socPercent >= c.socBelow + SOC_HYSTERESIS));
            case REASON_PARK:
            default:
                return c.maxParkHours <= 0;
        }
    }

    static String firstTrigger(Config c, Inputs in) {
        if (c.v12Below > 0 && in.v12 == Battery12vEvaluator.State.LOW) return REASON_V12;
        if (c.socBelow > 0 && !Double.isNaN(in.socPercent) && in.socPercent < c.socBelow) return REASON_SOC;
        if (c.maxParkHours > 0 && in.parkedSinceMs > 0L
                && in.nowMs - in.parkedSinceMs >= (long) (c.maxParkHours * 3_600_000L)) {
            return REASON_PARK;
        }
        if (inAnyWindow(c.windows, in.isoDayOfWeek, in.minuteOfDay)) return REASON_WINDOW;
        if (c.useQuietZones && in.inQuietZone) return REASON_QUIET;
        return null;
    }

    private static int rank(String r) {
        if (REASON_V12.equals(r)) return 0;
        if (REASON_SOC.equals(r)) return 1;
        if (REASON_PARK.equals(r)) return 2;
        if (REASON_WINDOW.equals(r)) return 3;
        return 4;
    }

    /**
     * Zeitfenster {@code {days:[1..7], from:"HH:MM", to:"HH:MM"}}. Über Mitternacht
     * (from > to) zählt der Teil nach Mitternacht zum Starttag. from == to = ganzer Tag.
     */
    static boolean inAnyWindow(JSONArray windows, int isoDay, int minuteOfDay) {
        if (windows == null) return false;
        for (int i = 0; i < windows.length(); i++) {
            JSONObject w = windows.optJSONObject(i);
            if (w == null) continue;
            int from = parseHhmm(w.optString("from", ""));
            int to = parseHhmm(w.optString("to", ""));
            JSONArray days = w.optJSONArray("days");
            if (from < 0 || to < 0 || days == null) continue;
            int prevDay = isoDay == 1 ? 7 : isoDay - 1;
            if (from == to) {
                if (hasDay(days, isoDay)) return true;
            } else if (from < to) {
                if (hasDay(days, isoDay) && minuteOfDay >= from && minuteOfDay < to) return true;
            } else {
                if (hasDay(days, isoDay) && minuteOfDay >= from) return true;
                if (hasDay(days, prevDay) && minuteOfDay < to) return true;
            }
        }
        return false;
    }

    private static boolean hasDay(JSONArray days, int d) {
        for (int i = 0; i < days.length(); i++) if (days.optInt(i, 0) == d) return true;
        return false;
    }

    static int parseHhmm(String s) {
        if (s == null || !s.matches("^([01]\\d|2[0-3]):[0-5]\\d$")) return -1;
        return Integer.parseInt(s.substring(0, 2)) * 60 + Integer.parseInt(s.substring(3, 5));
    }

    private static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }
}
