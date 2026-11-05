package at.greenchili.surfmate.appupdate;

/**
 * 0.1.37 Stufe 4: Wann darf die Launcher-APK geladen und installiert werden? Rein, JVM-testbar.
 *
 * <p>Anders als das Panoptes-Update stoppt eine Launcher-Installation keine Daemons, darum braucht sie nicht „Auto
 * aus“. Es gilt dasselbe wie für jedes Bedienen im Launcher: nicht während der Fahrt (Tempo &gt; 0, Gang bekannt und
 * nicht P, oder eine Fahrsperre mit belegtem Grund). Ein unbekannter Zustand ohne Bewegungshinweis sperrt nicht,
 * sonst ginge es auf Autos ohne lesbares ACC nie. Mobilfunk wie beim Panoptes-Update ({@link UpdateRegel#wartetWlan},
 * 0.1.38.10): {@code update.mobilfunk} an = auch über Mobilnetz, aus = nur im WLAN oder mit Einwilligung für genau
 * diesen Download. Installiert wird nur auf Tipp, nie von selbst.
 */
public final class LauncherRegel {

    public static final String FAEHRT = "faehrt";
    public static final String NICHT_IN_P = "nicht_in_p";
    public static final String WARTET_WLAN = UpdateRegel.WARTET_WLAN;
    public static final String KEIN_LAUNCHER = "kein_launcher";
    public static final String LAEUFT_SCHON = "laeuft_schon";
    public static final String ANDERER_STAND = "anderer_stand";

    /** Zustand für eine Entscheidung. */
    public static final class Lage {
        public Freigabe.Launcher verfuegbar;
        public String fahrsperre;
        public Boolean gangP;
        public double kmh = Double.NaN;
        public String netz = UpdateRegel.NETZ_UNBEKANNT;
        /** {@code update.mobilfunk} aus (dieselbe Quelle wie {@link UpdateRegel.Lage#nurWlan}). */
        public boolean nurWlan;
        public boolean laeuft;
    }

    private LauncherRegel() {}

    /** Fahrt-Grund oder null. */
    public static String fahrt(String fahrsperre, Boolean gangP, double kmh) {
        if (!Double.isNaN(kmh) && kmh > 0.5) return FAEHRT;
        if (Boolean.FALSE.equals(gangP)) return NICHT_IN_P;
        if (fahrsperre != null && !"state_unknown".equals(fahrsperre)) return FAEHRT;
        return null;
    }

    /** null = darf jetzt laden und installieren, sonst Grund. {@code mobil} = Einwilligung für diesen Download. */
    public static String nichtWeil(Lage l, boolean mobil) {
        if (l.verfuegbar == null || l.verfuegbar.apkUrl() == null) return KEIN_LAUNCHER;
        if (l.laeuft) return LAEUFT_SCHON;
        String f = fahrt(l.fahrsperre, l.gangP, l.kmh);
        if (f != null) return f;
        if (UpdateRegel.wartetWlan(l.nurWlan, mobil, l.netz)) return WARTET_WLAN;
        return null;
    }

    /** Neuer als installiert (−1 = nicht installiert, dann ist jede Freigabe „neu“)? */
    public static boolean neuer(Freigabe.Launcher f, int installiertVc) {
        return f != null && f.versionCode > installiertVc;
    }
}
