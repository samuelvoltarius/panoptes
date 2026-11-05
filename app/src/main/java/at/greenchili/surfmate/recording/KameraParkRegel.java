package at.greenchili.surfmate.recording;

/**
 * Kamera im Stand freigeben (Koordination 04.11.2026, 0.1.38.5-Folge): Die Pano-Kamera wird nur für den Totwinkel
 * warm gehalten, die HAL nimmt aber {@code setCameraFps} nicht an und läuft in P mit voller Rate (gemessen:
 * mm-qcamera 41 %, bmmcameraserver 22 %, byd_cam_daemon 26 %, zusammen etwa 1 Kern), während wir alle Bilder
 * verwerfen. Einziger Hebel ist, die Kamera zu schließen. Rein, ohne Android, testbar.
 *
 * <p>Geschlossen wird nur, wenn ALLES gilt: Zündung an, Gang P, keine Aufnahme, kein Wächter, kein Stream, keine
 * Kamera-Ansicht, Totwinkel-Ansicht versteckt und keine Totwinkel-Kalibrierung, und zwar seit {@link #KARENZ_MS}
 * (gegen Rangieren P ↔ R). DiLink 4/5 halten die Kamera ohnehin dauerhaft offen und sind ausgenommen. Sobald nur
 * eine Bedingung fällt, ist die Freigabe sofort weg (Gangwechsel, Stream, Wächter, Aufnahme).
 *
 * <p>0.1.38.11 (K1, ANALYSE_KAMERA_STALL_013810): Bei Zündung AUS gilt dieselbe Verbraucher-Liste ohne Schalter und
 * Gang ({@link #schliessenBeiAccAus}): Nach dem Entschärfen (Entriegeln) blieb die Pipeline im Leerlauf offen, niemand
 * schloss sie, und der Stall-Wächter öffnete die Kamera alle 10–15 s neu.
 */
public final class KameraParkRegel {

    /** So lange müssen alle Bedingungen ohne Unterbrechung gelten, bevor die Kamera geschlossen wird. */
    public static final long KARENZ_MS = 120_000L;

    /** Momentaufnahme der Zustände, die die Entscheidung brauchen. */
    public static final class Lage {
        public boolean schalterAn;
        public boolean zuendungAn;
        public boolean gangP;
        /** DiLink 4/5: Kamera bleibt laut OEM-Vorbild immer offen. */
        public boolean dauerhafteKamera;
        /** Aufnahmemodus besitzt die Kamera, es wird aufgenommen oder der Wächter ist scharf. */
        public boolean aufnahmeOderWaechter;
        public boolean streamAktiv;
        public boolean kameraAnsichtAktiv;
        public boolean totwinkelAnsichtSichtbar;
        public boolean totwinkelKalibrierung;
    }

    /** 0.1.38.11 (K1): So lange ohne Verbraucher bei Zündung aus, bevor die Pipeline geschlossen wird. */
    public static final long KARENZ_ACC_AUS_MS = 60_000L;

    private long seitMs = -1;
    private long seitAccAusMs = -1;

    /** EINE Liste: Niemand braucht die Kamera (keine Aufnahme/Wächter, kein Stream, keine Ansicht, kein Totwinkel). */
    static boolean keinVerbraucher(Lage l) {
        return l != null && !l.dauerhafteKamera
                && !l.aufnahmeOderWaechter && !l.streamAktiv && !l.kameraAnsichtAktiv
                && !l.totwinkelAnsichtSichtbar && !l.totwinkelKalibrierung;
    }

    /** Gelten alle Bedingungen jetzt (ohne Karenz)? */
    static boolean bedingungenErfuellt(Lage l) {
        return l != null && l.schalterAn && l.zuendungAn && l.gangP && keinVerbraucher(l);
    }

    /** 0.1.38.11 (K1): Zündung aus und kein Verbraucher (ohne Karenz)? DiLink 4/5 bleiben ausgenommen. */
    public static boolean ohneVerbraucherBeiAccAus(Lage l) {
        return l != null && !l.zuendungAn && keinVerbraucher(l);
    }

    /**
     * 0.1.38.11 (K1): true = bei Zündung aus seit {@link #KARENZ_ACC_AUS_MS} kein Verbraucher → Pipeline schließen.
     * Kein Schalter: eine offene Kamera ohne Verbraucher bei Zündung aus hat keinen Nutzen.
     */
    public synchronized boolean schliessenBeiAccAus(Lage l, long jetztMs) {
        if (!ohneVerbraucherBeiAccAus(l)) {
            seitAccAusMs = -1;
            return false;
        }
        if (seitAccAusMs < 0) seitAccAusMs = jetztMs;
        return jetztMs - seitAccAusMs >= KARENZ_ACC_AUS_MS;
    }

    /**
     * true = Kamera darf geschlossen bleiben bzw. jetzt geschlossen werden. Zustandsbehaftet (Karenz): fällt eine
     * Bedingung, beginnt die Karenz beim nächsten Erfüllen neu.
     */
    public synchronized boolean freigeben(Lage l, long jetztMs) {
        if (!bedingungenErfuellt(l)) {
            seitMs = -1;
            return false;
        }
        if (seitMs < 0) seitMs = jetztMs;
        return jetztMs - seitMs >= KARENZ_MS;
    }

    /** Karenz verwerfen (z. B. Zündung aus/an). */
    public synchronized void zuruecksetzen() {
        seitMs = -1;
        seitAccAusMs = -1;
    }
}
