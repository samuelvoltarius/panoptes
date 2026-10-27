package at.greenchili.surfmate.camping;

import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * Geplanter Camping-Start und -Ende (0.1.17, Koordination 27.10.2026; Cockpit plant über die Kopplung).
 *
 * <p>Zeitangaben: ISO-8601 (mit Offset oder Z, sonst Ortszeit des Autos) oder {@code HH:MM} = nächstes Vorkommen
 * nach dem Bezugszeitpunkt (Start: nach jetzt, Ende: nach dem Start).
 *
 * <p><b>Ende-Regel:</b> ausdrückliches Plan-Ende, sonst Weckzeit + {@code camping.nachlaufMinuten}; in jedem Fall
 * spätestens nach {@code camping.hoechstdauerStunden} – je nachdem, was zuerst kommt. Losfahren beendet ohnehin.
 */
public final class CampingPlan {

    private CampingPlan() {}

    /** Ein Plan, der länger als so verpasst wurde (z. B. Haupteinheit war aus), startet nicht mehr. */
    public static final long VERPASST_MS = 30 * 60_000L;
    /** Höchstens so weit im Voraus planbar. */
    public static final long VORAUS_MAX_MS = 7L * 24 * 3_600_000L;

    public static final class Ergebnis {
        public final long startMs, endeMs;
        public final String fehler;
        Ergebnis(long s, long e, String f) { startMs = s; endeMs = e; fehler = f; }
        public boolean ok() { return fehler == null; }
    }

    /**
     * Plan aus dem API-Text prüfen.
     *
     * @param start Pflicht
     * @param ende  optional (null = Ende-Regel ohne ausdrückliches Ende)
     */
    public static Ergebnis pruefen(String start, String ende, long jetztMs, TimeZone tz) {
        if (start == null || start.trim().isEmpty()) return new Ergebnis(0, 0, "start");
        long s = zeit(start.trim(), jetztMs, tz);
        if (s <= 0) return new Ergebnis(0, 0, "start_format");
        if (s < jetztMs - 60_000L) return new Ergebnis(0, 0, "start_vergangen");
        if (s > jetztMs + VORAUS_MAX_MS) return new Ergebnis(0, 0, "start_zu_weit");
        long e = 0;
        if (ende != null && !ende.trim().isEmpty()) {
            e = zeit(ende.trim(), s, tz);
            if (e <= 0) return new Ergebnis(0, 0, "ende_format");
            if (e <= s) return new Ergebnis(0, 0, "ende_vor_start");
        }
        return new Ergebnis(s, e, null);
    }

    /** Zeitpunkt in ms, oder −1. {@code HH:MM} = nächstes Vorkommen echt nach {@code nachMs}. */
    static long zeit(String t, long nachMs, TimeZone tz) {
        if (t.matches("\\d{1,2}:\\d{2}")) {
            String[] p = t.split(":");
            int h = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]);
            if (h > 23 || m > 59) return -1;
            Calendar c = Calendar.getInstance(tz);
            c.setTimeInMillis(nachMs);
            c.set(Calendar.HOUR_OF_DAY, h);
            c.set(Calendar.MINUTE, m);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            if (c.getTimeInMillis() <= nachMs) c.add(Calendar.DAY_OF_MONTH, 1);
            return c.getTimeInMillis();
        }
        try {
            return OffsetDateTime.parse(t).toInstant().toEpochMilli();
        } catch (Exception ignored) { }
        try {
            return Instant.parse(t).toEpochMilli();
        } catch (Exception ignored) { }
        try {
            return LocalDateTime.parse(t).atZone(ZoneId.of(tz.getID())).toInstant().toEpochMilli();
        } catch (Exception ignored) { }
        return -1;
    }

    /** Ende einer laufenden Sitzung und Grund (plan | weckzeit | hoechstdauer). */
    public static final class Ende {
        public final long ms;
        public final String grund;
        Ende(long ms, String grund) { this.ms = ms; this.grund = grund; }
    }

    /**
     * Ende-Regel.
     *
     * @param aktivSeitMs     Beginn der Sitzung
     * @param planEndeMs      ausdrückliches Ende (0 = keins)
     * @param weckMinute      Weckzeit als Minute des Tages (−1 = keine)
     * @param nachlaufMinuten Nachlauf nach der Weckzeit
     * @param hoechstStunden  Höchstdauer (≤ 0 = keine)
     */
    public static Ende ende(long aktivSeitMs, long planEndeMs, int weckMinute, int nachlaufMinuten,
                            int hoechstStunden, TimeZone tz) {
        long kandidat = 0;
        String grund = null;
        if (planEndeMs > 0) {
            kandidat = planEndeMs;
            grund = "plan";
        } else if (weckMinute >= 0) {
            Calendar c = Calendar.getInstance(tz);
            c.setTimeInMillis(aktivSeitMs);
            c.set(Calendar.HOUR_OF_DAY, weckMinute / 60);
            c.set(Calendar.MINUTE, weckMinute % 60);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            if (c.getTimeInMillis() <= aktivSeitMs) c.add(Calendar.DAY_OF_MONTH, 1);
            kandidat = c.getTimeInMillis() + Math.max(0, nachlaufMinuten) * 60_000L;
            grund = "weckzeit";
        }
        if (hoechstStunden > 0) {
            long h = aktivSeitMs + hoechstStunden * 3_600_000L;
            if (kandidat <= 0 || h < kandidat) {
                kandidat = h;
                grund = "hoechstdauer";
            }
        }
        return kandidat <= 0 ? null : new Ende(kandidat, grund);
    }

    /** Für den Status: {start, ende} in ms (ende null, wenn keins). */
    public static JSONObject alsJson(long startMs, long endeMs) {
        JSONObject o = new JSONObject();
        try {
            o.put("start", startMs).put("ende", endeMs > 0 ? (Object) endeMs : JSONObject.NULL);
        } catch (Exception ignored) { }
        return o;
    }
}
