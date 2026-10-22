package at.greenchili.surfmate.appupdate;

/**
 * Reine Entscheidungen des In-App-Updaters (ohne Netz, ohne Dateien) – testbar.
 *
 * <p>Installiert wird nur, wenn das Auto nachweislich aus ist: {@code CloudPowerGate} (power_level 0 aus einer
 * Live-Lesung ≤ 180 s, ACC nicht an) und Geschwindigkeit 0. Läuft schon ein Update (Deploy-Sperre
 * {@code .deploy.lock} oder ein Updater-Skript), wird nie ein zweites gestartet.
 */
public final class UpdateRegel {

    private UpdateRegel() {}

    /** Auto muss mindestens so lange geparkt (aus) sein, bevor „automatisch“ installiert. */
    public static final long AUTOMATISCH_NACH_MS = 10 * 60_000L;
    /** Prüftakt im Hintergrund. */
    public static final long PRUEF_TAKT_MS = 6 * 3_600_000L;
    /** Zweiter Nachweis muss so lange stabil sein. */
    public static final long ZWEITER_NACHWEIS_MS = 15 * 60_000L;

    /** Frühestens so oft auf Knopfdruck. */
    public static final long SUCHEN_MIN_ABSTAND_MS = 60_000L;

    public static final class Lage {
        public int installiert;
        /** null = nichts Neueres bekannt. */
        public Freigabe verfuegbar;
        /** CloudPowerGate erlaubt (Auto aus, Lesung frisch). */
        public boolean autoAus;
        /** Grund-Kennung von CloudPowerGate, wenn nicht erlaubt. */
        public String gateGrund;
        /** km/h; NaN = unbekannt. */
        public double kmh;
        /**
         * Zweiter Nachweis „Auto aus“ für Varianten ohne frische power_level-Lesung (Koordination 21.10.):
         * ACC autoritativ aus UND 0 km/h (bekannt) UND beides seit ≥ {@link #ZWEITER_NACHWEIS_MS} ununterbrochen.
         */
        public boolean zweiterNachweis;
        public boolean laeuftSchon;
        /** versionCode, der zuletzt zurückgerollt wurde (oder −1). */
        public int rueckfallVc = -1;
        /** {@code update.mobilfunk} aus: nur im WLAN laden (21.10.: Standard an, viele Autos haben nur SIM). */
        public boolean nurWlan;
        /** "wlan" | "mobil" | "unbekannt". */
        public String netz = NETZ_UNBEKANNT;
    }

    public static final String NETZ_WLAN = "wlan";
    public static final String NETZ_MOBIL = "mobil";
    public static final String NETZ_UNBEKANNT = "unbekannt";

    /** Grund, warum jetzt nicht installiert werden kann, oder null (= installierbar). */
    public static String nichtWeil(Lage l) {
        if (l.verfuegbar == null || l.verfuegbar.versionCode <= l.installiert) return "kein_update";
        if (l.installiert < l.verfuegbar.minVersion) return "zu_alt";
        if (l.laeuftSchon) return "laeuft_schon";
        if (l.kmh > 0.5) return "faehrt";
        if (!l.autoAus) {
            // power_level sagt „an“ → nie überstimmen; nur fehlende/alte Lesung darf der zweite Nachweis ersetzen
            if ("cloud_gate_car_on".equals(l.gateGrund)) return "zuendung_an";
            if (l.zweiterNachweis) return null;
            return "zustand_unbekannt";
        }
        // „nur WLAN“: nur ein eindeutig erkanntes WLAN zählt (Ethernet/unbekannt kann die SIM der T-Box sein)
        if (l.nurWlan && !NETZ_WLAN.equals(l.netz)) return "wartet_wlan";
        return null;
    }

    /** Netzart aus NetworkMonitor ("wifi" | "cellular" | "ethernet" | …) für die Anzeige und „nur WLAN“. */
    public static String netz(String typ) {
        if ("wifi".equals(typ)) return NETZ_WLAN;
        if ("cellular".equals(typ)) return NETZ_MOBIL;
        return NETZ_UNBEKANNT;
    }

    /** Schalter aus der Config: nur ausdrücklich "false" schaltet ab (nicht gesetzt = Bestand = an). */
    public static boolean an(String wert) {
        return !"false".equals(wert);
    }

    /** Größe für Status/Telegram, z. B. "78 MB"; leer, wenn unbekannt. */
    public static String groesseText(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1_000_000L) return Math.max(1, Math.round(bytes / 1000.0)) + " KB";
        return Math.round(bytes / 1_000_000.0) + " MB";
    }

    /**
     * Zeitpunkt (ms), ab dem „automatisch“ installieren darf, oder −1 (gar nicht). {@code ausSeit} = seit wann das
     * Auto ununterbrochen installierbar war (−1 = gerade nicht).
     */
    public static long automatischAb(Lage l, boolean modusAutomatisch, long ausSeit) {
        return automatischAb(l, modusAutomatisch, true, ausSeit);
    }

    /** Wie oben; {@code pruefenAn} = {@code update.pruefen} (aus → nie von selbst). */
    public static long automatischAb(Lage l, boolean modusAutomatisch, boolean pruefenAn, long ausSeit) {
        if (!modusAutomatisch || !pruefenAn || ausSeit < 0) return -1;
        if (nichtWeil(l) != null) return -1;
        if (l.verfuegbar.versionCode == l.rueckfallVc) return -1;   // nie dieselbe Version erneut von selbst
        return ausSeit + AUTOMATISCH_NACH_MS;
    }

    /**
     * Fortschreibung des zweiten Nachweises: {@code seit} = seit wann ACC autoritativ aus und 0 km/h (−1 = gerade
     * nicht). Fehlt ein Wert (ACC nicht autoritativ, km/h unbekannt) → −1 (fail-closed).
     */
    public static long zweiterNachweisSeit(long seit, Boolean accAus, double kmh, long jetzt) {
        boolean ok = Boolean.TRUE.equals(accAus) && !Double.isNaN(kmh) && Math.abs(kmh) < 0.5;
        if (!ok) return -1;
        return seit < 0 ? jetzt : seit;
    }

    public static boolean zweiterNachweisGilt(long seit, long jetzt) {
        return seit >= 0 && jetzt - seit >= ZWEITER_NACHWEIS_MS;
    }

    /** Gültiger Kanal ("stabil" Standard). */
    public static String kanal(String wert) {
        return "test".equals(wert) ? "test" : "stabil";
    }

    /** Gültiger Modus ("melden" Standard; nur "automatisch" schaltet um). */
    public static boolean automatisch(String wert) {
        return "automatisch".equals(wert);
    }

    /** Neuere Freigabe der beiden (Kanal test sieht auch stabile Versionen). */
    public static Freigabe neuere(Freigabe a, Freigabe b) {
        if (a == null) return b;
        if (b == null) return a;
        return b.versionCode > a.versionCode ? b : a;
    }
}
