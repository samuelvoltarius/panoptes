package at.greenchili.surfmate.camping;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Tiermodus (Vorbild Teslas Dog Mode, 03.11.2026): reine Regeln, ohne Auto, ohne Dateien. Die Laufzeit steht in
 * {@link Tiermodus}.
 *
 * <p>Der Tiermodus ist <b>kein zweiter Klima-Regler</b>. Die Klimaanlage des Autos regelt selbst auf den Sollwert; Panoptes
 * schaltet sie nur über den vorhandenen Camping-Heizweg ({@link RouterHeizweg}) ein und wacht darüber. Hier steht nur,
 * wann gestartet werden darf, welche Probleme gerade bestehen und welche davon ein Schutz sind.
 */
public final class TiermodusRegel {

    private TiermodusRegel() {}

    // ------------------------------------------------------------------ Problem-Kennungen (Meldungen, Status)

    /** Auto an, aber die Klimaanlage meldet „aus“. */
    public static final String KLIMA_AUS = "klima_aus";
    /** Auto ist aus: die Klimaanlage läuft lokal nicht mehr. */
    public static final String AUTO_AUS = "auto_aus";
    /** Panoptes kann nicht prüfen, ob das Klima läuft (Zustand unbekannt oder zu alt). */
    public static final String KLIMA_UNBEKANNT = "klima_unbekannt";
    /** Gemessene Innentemperatur über dem Grenzwert. */
    public static final String ZU_WARM = "zu_warm";
    /** Schutz: Akku an oder unter der Untergrenze. */
    public static final String AKKU = "akku_grenze";
    /**
     * Die Innentemperatur war gemessen, ist aber veraltet (älter als {@code BydVehicleData.CABIN_TEMP_MAX_AGE_MS},
     * gleiche Frische-Regel wie {@code BydEvent}): der Überhitzungsschutz ist dann blind – warnen.
     */
    public static final String INNEN_VERALTET = "innen_veraltet";
    /** Schutz: 12-V-Starterbatterie mit LOW-Urteil ({@code Battery12vEvaluator}). */
    public static final String V12 = "v12_niedrig";

    /** Untergrenze für die Akku-Grenze: nie unter den SoC-Abschalt-Schutz ({@code SocCutoffMonitor}, 10 %). */
    public static final int SOC_GRENZE_MIN = 15, SOC_GRENZE_MAX = 80, SOC_GRENZE_STANDARD = 20;
    /** Alarm-Grenze innen (°C). */
    public static final int ALARM_MIN = 25, ALARM_MAX = 40, ALARM_STANDARD = 30;
    /** Sollwert in der SDK-Skala 17–33 (bei Modellen ohne Stufen-Skala = °C). */
    public static final int WUNSCH_MIN = 17, WUNSCH_MAX = 33, WUNSCH_STANDARD = 21;
    public static final int WIEDERHOLEN_MIN = 1, WIEDERHOLEN_MAX = 15, WIEDERHOLEN_STANDARD = 3;
    /** Gemessene Innentemperatur gilt so lange als frisch – dieselbe Grenze wie {@code BydEvent}/{@code hasFreshCabinTemperature}. */
    public static final long INNEN_MAX_ALTER_MS = at.greenchili.surfmate.byd.BydVehicleData.CABIN_TEMP_MAX_AGE_MS;
    /** So viele Takte in Folge ohne Klima-Zustand, dann „unbekannt“ (ein einzelner Lesefehler alarmiert nicht). */
    public static final int UNBEKANNT_TAKTE = 2;
    /** „0 km/h“: Tachowert unter 1 km/h. */
    public static final double STILL_KMH = 1.0;

    /** Einstellungen ({@code tiermodus.*}); Werte immer in den Grenzen. */
    public static final class Einstellung {
        public int wunsch = WUNSCH_STANDARD;
        public int alarmC = ALARM_STANDARD;
        public int socGrenze = SOC_GRENZE_STANDARD;
        public int wiederholenMinuten = WIEDERHOLEN_STANDARD;
        /** Bei Auto aus Fernklima über die BYD-Cloud versuchen (nur über CloudPowerGate). Standard aus. */
        public boolean cloud;

        public static Einstellung aus(org.json.JSONObject t) {
            Einstellung e = new Einstellung();
            if (t == null) return e;
            e.wunsch = klemme(t.optInt("wunsch", WUNSCH_STANDARD), WUNSCH_MIN, WUNSCH_MAX);
            e.alarmC = klemme(t.optInt("alarmC", ALARM_STANDARD), ALARM_MIN, ALARM_MAX);
            e.socGrenze = klemme(t.optInt("socGrenze", SOC_GRENZE_STANDARD), SOC_GRENZE_MIN, SOC_GRENZE_MAX);
            e.wiederholenMinuten = klemme(t.optInt("wiederholenMinuten", WIEDERHOLEN_STANDARD), WIEDERHOLEN_MIN, WIEDERHOLEN_MAX);
            e.cloud = t.optBoolean("cloud", false);
            return e;
        }
    }

    /** Was Panoptes gerade über das Auto weiß. */
    public static final class Lage {
        public long jetztMs;
        public CampingRegler.Fahrt fahrt = CampingRegler.Fahrt.UNBEKANNT;
        /** Gang P (live bzw. letzter Schnappschuss). */
        public boolean gangP;
        /** Tempo nach {@code byd.Stillstand}, NaN = unbekannt. */
        public double kmh = Double.NaN;
        /** power_level (0 aus, 1 ACC, 2 an; −1 unbekannt) und Alter der Lesung. */
        public int powerLevel = -1;
        public long powerLevelAlterMs = Long.MAX_VALUE;
        public double soc = Double.NaN;
        public boolean v12Low;
        /** Läuft die Klimaanlage? Nur bei Auto an lesbar; null = unbekannt. */
        public Boolean klimaLaeuft;
        /** Gemessene, frische Innentemperatur ({@code insideTempC} aus dem AC-Gerät), NaN = keine oder veraltet. */
        public double innenC = Double.NaN;
        /** Es gab einen gemessenen Wert, er ist aber nicht mehr frisch. */
        public boolean innenVeraltet;
        public double aussenC = Double.NaN;

        CampingRegler.Lage alsCampingLage() {
            CampingRegler.Lage l = new CampingRegler.Lage();
            l.jetztMs = jetztMs;
            l.fahrt = fahrt;
            l.powerLevel = powerLevel;
            l.powerLevelAlterMs = powerLevelAlterMs;
            l.soc = soc;
            l.v12Low = v12Low;
            l.klimaLaeuft = klimaLaeuft;
            l.aussenC = aussenC;
            return l;
        }
    }

    static boolean autoAn(Lage l) {
        return l.powerLevel == 2 && l.powerLevelAlterMs >= 0 && l.powerLevelAlterMs <= RouterHeizweg.AN_MAX_ALTER_MS;
    }

    static boolean autoAus(Lage l) {
        return l.powerLevel == 0 && l.powerLevelAlterMs >= 0
                && l.powerLevelAlterMs <= at.greenchili.surfmate.byd.routing.CloudPowerGate.MAX_AGE_MS;
    }

    /**
     * Darf der Tiermodus jetzt starten? null = ja, sonst der Grund. Nur in P, bei 0 km/h, mit eingeschaltetem Auto
     * (nur dann hält die Klimaanlage dauerhaft), nicht neben Camping und nicht, wenn ein Schutz schon greift.
     */
    public static String startFehler(Einstellung e, Lage l, boolean campingAktiv) {
        if (campingAktiv) return "camping_laeuft";
        if (l.fahrt == CampingRegler.Fahrt.FAEHRT) return "nicht_geparkt";
        if (!l.gangP || Double.isNaN(l.kmh) || l.kmh < 0) return "fahrzustand_unbekannt";
        if (l.kmh >= STILL_KMH) return "nicht_geparkt";
        if (l.v12Low) return V12;
        if (!Double.isNaN(l.soc) && l.soc <= e.socGrenze) return AKKU;
        if (!autoAn(l)) return "auto_nicht_an";
        return null;
    }

    /**
     * Alle Probleme in dieser Lage. {@code unbekanntTakte} = so viele Takte in Folge war der Klima-Zustand bei Auto an
     * unbekannt. {@code cloudLaeuft} = eine von BYD bestätigte Cloud-Sitzung läuft noch (Auto aus).
     */
    public static Set<String> probleme(Einstellung e, Lage l, int unbekanntTakte, boolean cloudLaeuft) {
        Set<String> p = new LinkedHashSet<>();
        if (l.v12Low) p.add(V12);
        if (!Double.isNaN(l.soc) && l.soc <= e.socGrenze) p.add(AKKU);
        if (!Double.isNaN(l.innenC) && l.innenC >= e.alarmC) p.add(ZU_WARM);
        if (Double.isNaN(l.innenC) && l.innenVeraltet) p.add(INNEN_VERALTET);
        if (autoAn(l)) {
            if (Boolean.FALSE.equals(l.klimaLaeuft)) p.add(KLIMA_AUS);
            else if (l.klimaLaeuft == null && unbekanntTakte >= UNBEKANNT_TAKTE) p.add(KLIMA_UNBEKANNT);
        } else if (autoAus(l)) {
            // Auto aus: die Klimaanlage läuft lokal nicht. Eine Cloud-Sitzung ist zeitlich begrenzt (10–30 min, dann
            // mindestens 45 min Pause) – auch mit ihr bleibt es ein Problem, nur die Meldung nennt sie.
            p.add(AUTO_AUS);
        } else {
            // ACC, unbekannt oder zu alt: Panoptes weiß nicht, ob das Klima läuft
            p.add(KLIMA_UNBEKANNT);
        }
        return p;
    }

    /** Greift ein Schutz (12 V, Akku)? Dann schaltet der Tiermodus das Klima aus – nie still. */
    public static boolean schutz(Set<String> probleme) {
        return probleme.contains(AKKU) || probleme.contains(V12);
    }

    /** Darf das Display „Mein Tier ist okay“ zeigen? Nur ohne Problem und mit bestätigt laufendem Klima. */
    public static boolean anzeigeOk(Set<String> probleme, Lage l) {
        // Ein veralteter Innenwert ist ein Hinweis an den Besitzer, kein „Klima aus“ am Fenster
        for (String p : probleme) if (!INNEN_VERALTET.equals(p)) return false;
        return autoAn(l) && Boolean.TRUE.equals(l.klimaLaeuft);
    }

    /** Titel der Alarm-Meldung: Klima-Ausfall und Schutz = „Klima AUS – Tier prüfen!“. */
    public static String alarmTitel(Set<String> probleme) {
        if (probleme.contains(V12) || probleme.contains(AKKU) || probleme.contains(KLIMA_AUS)
                || probleme.contains(AUTO_AUS)) return "alarm_klima_aus";
        if (probleme.contains(ZU_WARM)) return "alarm_zu_warm";
        if (probleme.contains(INNEN_VERALTET) && probleme.size() == 1) return "alarm_innen_veraltet";
        return "alarm_unklar";
    }

    static int klemme(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
