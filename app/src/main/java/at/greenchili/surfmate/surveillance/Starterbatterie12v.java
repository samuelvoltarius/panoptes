package at.greenchili.surfmate.surveillance;

import at.greenchili.surfmate.einstellungen.AkkuChemie;
import at.greenchili.surfmate.notifications.NotificationEvent;

import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 0.1.28 P8: Warnung, BEVOR die Starterbatterie (12 V) das Auto nicht mehr starten lässt.
 *
 * <p>Ausgewertet wird nur die Ruhespannung (≥ {@link AkkuChemie#RUHE_NACH_MS} geparkt, der DC-DC-Wandler lädt
 * nicht). Seit 0.1.31 ist das die Oberkante des Sägezahns ({@link Battery12vEvaluator#ruheOben()}) – dieselbe,
 * die {@link GuardStopMonitor} je Tag merkt –, denn der Tiefpunkt vor dem Nachladen sagt nichts über die
 * Batterie. Zwei Stufen:
 * <ul>
 *   <li>{@link Stufe#VOR}: Oberkante knapp über der Stopp-Schwelle (Abstand je Chemie) <b>oder</b> sie fällt über
 *       die letzten Standtage so, dass sie in {@link #TREND_VORSCHAU_TAGE} Tagen unter der Schwelle wäre. Bei LFP
 *       ist der Abstand Ruhe (13,1 V) → Stopp (12,8 V) klein, dort trägt vor allem der Trend.</li>
 *   <li>{@link Stufe#NIEDRIG}: nur wenn {@link Battery12vEvaluator} {@code LOW} urteilt, siehe {@link #bestaetigt}.</li>
 * </ul>
 * Meldung höchstens einmal je Stufe und Tag; eine höhere Stufe meldet sofort. Erholt sich die Batterie (Ruhe ≥
 * Wieder-Ein-Schwelle), ist der Merker gelöscht. Die Schwellen sind die des Wächter-Stopps (Einstellung, sonst
 * Standard der Chemie); ist die 12-V-Regel dort abgeschaltet, gelten die Chemie-Standards – die Warnung selbst
 * hängt nur am Telegram-Schalter „Akku“ bzw. an der Push-Kategorie.
 */
public final class Starterbatterie12v {

    public enum Stufe { KEINE, VOR, NIEDRIG }

    public static final String PUSH_KATEGORIE = "vehicle.health.battery12v";

    /** Abstand über der Stopp-Schwelle, ab dem vorgewarnt wird (Volt). */
    static final double ABSTAND_LFP = 0.15;
    static final double ABSTAND_BLEI = 0.20;
    /** Trend: Standtage der letzten {@link #TREND_TAGE} Tage, mindestens {@link #TREND_MIN_PUNKTE}. */
    static final int TREND_TAGE = 7;
    static final int TREND_MIN_PUNKTE = 3;
    /** Erst ab so viel Abfall je Tag gilt es als Trend (Messrauschen 0,1-V-Auflösung). */
    static final double TREND_MIN_ABFALL = 0.03;
    static final int TREND_VORSCHAU_TAGE = 3;
    static final long WIEDERHOLEN_MS = 24 * 60 * 60_000L;

    private Starterbatterie12v() {}

    /** Schwellen {stopp unter, wieder ab}: Einstellung des Wächter-Stopps, sonst Standard der Chemie. */
    static double[] schwellen(double v12Below, double v12Resume, String chemie) {
        if (v12Below > 0) return new double[] {v12Below, v12Resume > v12Below ? v12Resume : v12Below + 0.3};
        return AkkuChemie.v12Standard(chemie);
    }

    static double abstand(String chemie) {
        return AkkuChemie.LFP.equals(chemie) ? ABSTAND_LFP : ABSTAND_BLEI;
    }

    /**
     * @param ruhe     aktuelle Ruhespannung (Median), NaN = unbekannt / lädt / noch nicht lange genug geparkt
     * @param ruheTage {yyyy-MM-dd: kleinste Ruhespannung des Tages} (darf null sein)
     * @param heute    yyyy-MM-dd
     */
    public static Stufe bewerten(double ruhe, double stoppUnter, String chemie, JSONObject ruheTage, String heute) {
        if (Double.isNaN(ruhe) || !(ruhe > 5 && ruhe < 20) || stoppUnter <= 0) return Stufe.KEINE;
        if (ruhe < stoppUnter) return Stufe.NIEDRIG;
        if (ruhe < stoppUnter + abstand(chemie) - 1e-9) return Stufe.VOR;
        double steigung = trend(ruheTage, heute);
        if (!Double.isNaN(steigung) && steigung <= -TREND_MIN_ABFALL
                && ruhe + steigung * TREND_VORSCHAU_TAGE < stoppUnter) {
            return Stufe.VOR;
        }
        return Stufe.KEINE;
    }

    /**
     * „Sehr schwach“ (CRITICAL) braucht das Urteil der Auswertung: Ohne {@code LOW} ist es höchstens eine Vorwarnung,
     * mit {@code LOW} immer {@link Stufe#NIEDRIG}.
     */
    static Stufe bestaetigt(Stufe stufe, boolean v12Low) {
        if (v12Low) return Stufe.NIEDRIG;
        return stufe == Stufe.NIEDRIG ? Stufe.VOR : stufe;
    }

    /** Steigung (Volt je Tag, kleinste Quadrate) der Ruhetage im Fenster; NaN = zu wenig Punkte. */
    static double trend(JSONObject ruheTage, String heute) {
        if (ruheTage == null || heute == null) return Double.NaN;
        long h = tagNr(heute);
        if (h == Long.MIN_VALUE) return Double.NaN;
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        int n = 0;
        Iterator<String> it = ruheTage.keys();
        while (it.hasNext()) {
            String k = it.next();
            long t = tagNr(k);
            double v = ruheTage.optDouble(k, Double.NaN);
            if (t == Long.MIN_VALUE || Double.isNaN(v) || t > h || h - t >= TREND_TAGE) continue;
            double x = t - h;
            sx += x; sy += v; sxx += x * x; sxy += x * v;
            n++;
        }
        if (n < TREND_MIN_PUNKTE) return Double.NaN;
        double nenner = n * sxx - sx * sx;
        if (Math.abs(nenner) < 1e-9) return Double.NaN;
        return (n * sxy - sx * sy) / nenner;
    }

    private static long tagNr(String tag) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            f.setLenient(false);
            return f.parse(tag).getTime() / (24 * 60 * 60_000L);
        } catch (ParseException | RuntimeException e) {
            return Long.MIN_VALUE;
        }
    }

    // ------------------------------------------------------------------ Merker (wann zuletzt gewarnt)

    /** Gemerkte letzte Warnung: Stufe + Zeit. Unveränderlich; {@link #nach} liefert den Folgezustand. */
    public static final class Merker {
        public final Stufe stufe;
        public final long zeit;
        /** true = jetzt melden. */
        public final boolean melden;

        Merker(Stufe stufe, long zeit, boolean melden) {
            this.stufe = stufe;
            this.zeit = zeit;
            this.melden = melden;
        }

        public static Merker leer() {
            return new Merker(Stufe.KEINE, 0L, false);
        }

        /**
         * Nächster Zustand nach einer Bewertung.
         *
         * @param erholt Ruhespannung hat die Wieder-Ein-Schwelle erreicht (Merker löschen)
         */
        public Merker nach(Stufe neu, boolean erholt, long jetzt) {
            if (neu == Stufe.KEINE) {
                return erholt ? leer() : new Merker(stufe, zeit, false);
            }
            if (neu.ordinal() > stufe.ordinal()) return new Merker(neu, jetzt, true);
            if (neu == stufe && jetzt - zeit >= WIEDERHOLEN_MS) return new Merker(neu, jetzt, true);
            return new Merker(stufe, zeit, false);
        }

        public JSONObject json() {
            JSONObject j = new JSONObject();
            try {
                j.put("stufe", stufe.name());
                j.put("zeit", zeit);
            } catch (Exception ignored) {
            }
            return j;
        }

        public static Merker aus(JSONObject j) {
            if (j == null) return leer();
            try {
                return new Merker(Stufe.valueOf(j.optString("stufe", "KEINE")), j.optLong("zeit", 0L), false);
            } catch (IllegalArgumentException e) {
                return leer();
            }
        }
    }

    // ------------------------------------------------------------------ Meldung

    /** Telegram-Stufe für {@code notifyBattery12v}: vor | niedrig. */
    public static String telegramStufe(Stufe s) {
        return s == Stufe.NIEDRIG ? "niedrig" : "vor";
    }

    /** Push fürs Handy: Vorwarnung WARN, sehr schwach CRITICAL (kommt auch in der Ruhezeit). */
    public static NotificationEvent pushEreignis(Stufe s, double volt, String sprache) {
        String v = String.format(Locale.forLanguageTag(sprache == null ? "de" : sprache), "%.1f", volt);
        boolean niedrig = s == Stufe.NIEDRIG;
        JSONObject data = new JSONObject();
        try {
            data.put("stufe", telegramStufe(s));
            data.put("volt", Math.round(volt * 10) / 10.0);
        } catch (Exception ignored) {
        }
        String t = niedrig ? "notifications.battery12v_niedrig_title" : "notifications.battery12v_vor_title";
        String b = niedrig ? "notifications.battery12v_niedrig_body" : "notifications.battery12v_vor_body";
        return new NotificationEvent(PUSH_KATEGORIE,
                niedrig ? NotificationEvent.Severity.CRITICAL : NotificationEvent.Severity.WARN,
                at.greenchili.surfmate.server.Messages.text(t,
                        niedrig ? "12 V battery very weak: {0} V" : "12 V battery getting weaker: {0} V", v),
                at.greenchili.surfmate.server.Messages.text(b, niedrig
                        ? "It no longer recovers by itself. The car may soon not start. Go for a drive today or plug it in."
                        : "If it drops further, the car may not start. Go for a drive or plug it in soon."),
                "battery12v", "/local/waechter.html", data);
    }
}
