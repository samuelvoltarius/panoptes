package at.greenchili.surfmate.services;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import at.greenchili.surfmate.overlay.SetupGuideDialog;

/**
 * 0.1.43.22 (Upstream OverDrive #1126 „expose last verified state“, MIT, übertragen): der zuletzt
 * GELESENE Zustand des BYD-Autostart-Schalters für diese Installation.
 *
 * <p>Der BYD-Schalter ist eine SPERR-Liste: AN = Start gesperrt, AUS = Start erlaubt. Gemerkt wird nur, was der
 * {@link AutoStartEnabler} am Schalter wirklich gelesen hat:
 * <ul>
 *   <li>{@code erlaubt}: Schalter AUS gelesen ({@code SUCCESS}, {@code ALREADY_OFF})</li>
 *   <li>{@code gesperrt}: Schalter nach dem Klick erneut AN gelesen ({@code STILL_ON})</li>
 *   <li>{@code unbekannt}: alles andere (kein Dialog, keine Zeile, nicht lesbar, nicht belegte Plattform)</li>
 * </ul>
 * Kein Live-Lesen der Firmware: der Wert ist so alt wie {@code geprueftZeit}. Eine neue Installation (BYD setzt dabei
 * die Sperre zurück) macht jeden alten Wert ungültig → {@code unbekannt}. Laufende Daemons oder ein erfolgreiches
 * Update sind KEIN Beleg und setzen hier nichts.
 */
public final class AutostartBeobachtung {

    private static final String TAG = "PanoptesAutostart";
    private static final String PREFS = "autostart_beobachtung";
    private static final String K_INSTALL = "installTime";
    private static final String K_ZEIT = "geprueftZeit";
    private static final String K_ZUSTAND = "zustand";
    private static final String K_ERGEBNIS = "ergebnis";

    public static final String ERLAUBT = "erlaubt";
    public static final String GESPERRT = "gesperrt";
    public static final String UNBEKANNT = "unbekannt";

    private AutostartBeobachtung() {}

    /** Was ein Enabler-Ergebnis über den Schalter belegt. Rein, testbar. */
    public static String zustandAus(AutoStartEnabler.Result r) {
        if (r == null) return UNBEKANNT;
        switch (r) {
            case SUCCESS:
            case ALREADY_OFF:
                return ERLAUBT;
            case STILL_ON:
                return GESPERRT;
            default:
                return UNBEKANNT;
        }
    }

    /** Hat der Enabler überhaupt am Schalter gearbeitet? (null/NICHT_BELEGT: nichts gelesen, nichts merken.) */
    static boolean merkenswert(AutoStartEnabler.Result r) {
        return r != null && r != AutoStartEnabler.Result.NICHT_BELEGT;
    }

    /** Ergebnis eines gelesenen Stands, gültig nur für die laufende Installation. */
    public static final class Stand {
        public final String zustand;
        public final long geprueftZeit;
        public final String ergebnis;

        Stand(String zustand, long geprueftZeit, String ergebnis) {
            this.zustand = zustand;
            this.geprueftZeit = geprueftZeit;
            this.ergebnis = ergebnis == null ? "" : ergebnis;
        }

        /** Für die Datenzeile von {@code am broadcast} und das Log: {@code geprueft=… geprueftZeit=… geprueftErgebnis=…}. */
        public String alsDaten() {
            return "geprueft=" + zustand + " geprueftZeit=" + geprueftZeit
                    + (ergebnis.isEmpty() ? "" : " geprueftErgebnis=" + ergebnis);
        }
    }

    /**
     * Rein, testbar: gespeicherter Wert → gültiger Stand. Andere Installation, fehlende/zukünftige Zeit oder
     * unbekannter Text → {@code unbekannt} (Zeit 0).
     */
    static Stand bewerte(long installJetzt, long installGemerkt, long zeit, String zustand, String ergebnis, long jetzt) {
        if (installJetzt <= 0L || installGemerkt != installJetzt || zeit <= 0L || zeit > jetzt) {
            return new Stand(UNBEKANNT, 0L, "");
        }
        String z = ERLAUBT.equals(zustand) || GESPERRT.equals(zustand) ? zustand : UNBEKANNT;
        return new Stand(z, zeit, ergebnis);
    }

    /** Nach einem Enabler-Lauf (Hintergrund-Thread). Schreibt nichts für nicht belegte Plattform / keinen Lauf. */
    public static void merke(Context context, AutoStartEnabler.Result r) {
        if (context == null || !merkenswert(r)) return;
        try {
            long install = SetupGuideDialog.installTime(context);
            if (install <= 0L) return;
            String z = zustandAus(r);
            long jetzt = System.currentTimeMillis();
            prefs(context).edit()
                    .putLong(K_INSTALL, install)
                    .putLong(K_ZEIT, jetzt)
                    .putString(K_ZUSTAND, z)
                    .putString(K_ERGEBNIS, r.name())
                    .apply();
            Log.i(TAG, "zuletzt geprüft: " + z + " (ergebnis=" + r.name() + ", Schalter AN = gesperrt) installTime="
                    + install + " geprueftZeit=" + jetzt);
        } catch (Throwable t) {
            Log.w(TAG, "Beobachtung nicht gespeichert: " + t.getClass().getSimpleName());
        }
    }

    /** Zuletzt geprüfter Stand dieser Installation, sonst {@code unbekannt}. Nie ein Firmware-Lesen. */
    public static Stand lies(Context context) {
        try {
            SharedPreferences p = prefs(context);
            return bewerte(SetupGuideDialog.installTime(context), p.getLong(K_INSTALL, 0L), p.getLong(K_ZEIT, 0L),
                    p.getString(K_ZUSTAND, UNBEKANNT), p.getString(K_ERGEBNIS, ""), System.currentTimeMillis());
        } catch (Throwable t) {
            return new Stand(UNBEKANNT, 0L, "");
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
