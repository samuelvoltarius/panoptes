package at.greenchili.surfmate.tacho.oem;

import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import at.greenchili.surfmate.tacho.TachoFaehigkeit;

/**
 * 0.1.44 (N2a): zweiter Ausgang fuer die BYD-Kombi-Navikarte („Einfach“). Statt AutoContainer direkt
 * anzusprechen, bekommt der BYD-Systemdienst AmapService den oeffentlichen Amap-Auto-Broadcast
 * {@code AUTONAVI_STANDARD_BROADCAST_SEND} – genau der Eingang, den die BYD-Amap-Navi selbst benutzt.
 *
 * <p>Vertrag (Fremdquellen, am Fahrzeug von uns NICHT abgenommen, siehe docs/OEM_NAVIGATIONSPFEILE.md):
 * {@code KEY_TYPE=10001} Fuehrung, {@code TYPE=0} echte Fuehrung, {@code IS_BYD_MAP=false},
 * {@code NEW_ICON} Gaode-Symbol, {@code SEG_REMAIN_DIS_AUTO}/{@code ROUTE_REMAIN_*_AUTO} als Text,
 * {@code ROUNG_ABOUT_NUM} (Schreibweise des Protokolls). Ende: {@code KEY_TYPE=10019}, {@code EXTRA_STATE=9}.
 *
 * <p>Nie: Teststeuerung oder Abschalt-Aktionen des Dienstes, Strassenname, Ziel, Position. Immer gezielt per
 * {@code setPackage} an das erkannte Paket, nie ein offener Broadcast.
 */
public final class AmapServiceBroadcast {
    public static final String AKTION = "AUTONAVI_STANDARD_BROADCAST_SEND";
    /** DiLink 3 (Android 10) – BYDMate #301. */
    public static final String PAKET_DILINK3 = "com.example.amapservice";
    /** DiLink 5 (Android 12) – waze-for-byd. */
    public static final String PAKET_DILINK5 = "com.byd.amapservice";
    static final String[] PAKETE = {PAKET_DILINK3, PAKET_DILINK5};

    static final int KEY_FUEHRUNG = 10001;
    static final int KEY_ZUSTAND = 10019;
    static final int ZUSTAND_ENDE = 9;
    static final int TYP_ECHT = 0;

    /**
     * Freigabe-Tabelle. DiLink 3/4 ab Werk. DiLink 5 erst, wenn ein Tester abgenommen hat: dann reicht der
     * Schalter {@code navMap.kombiNaviDiLink5} (nicht in der Oberflaeche; ab Werk aus). Single-OS/Surf nie.
     */
    static final String SCHALTER_DILINK5 = "kombiNaviDiLink5";

    /** Sendeweg; im Daemon ein Context.sendBroadcast, im Test eine Liste. */
    public interface Sender {
        boolean sende(String paket, Map<String, Object> extras);
    }

    /** Paket-Erkennung; im Daemon der PackageManager. */
    public interface Installiert {
        boolean ist(String paket);
    }

    private AmapServiceBroadcast() {}

    /** Erstes installiertes AmapService-Paket oder null. */
    public static String erkennePaket(Installiert installiert) {
        if (installiert == null) return null;
        for (String p : PAKETE) {
            try {
                if (installiert.ist(p)) return p;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    /**
     * Darf der Broadcast-Ausgang auf dieser Plattform senden? Fail-closed: Surf/Single-OS nie, unbekanntes OS nie,
     * DiLink 5 nur mit Schalter, und nur wenn ein AmapService-Paket da ist.
     */
    public static boolean freigegeben(TachoFaehigkeit.Eingaben input, JSONObject p, String paket,
                                      boolean dilink5Schalter) {
        if (input == null || p == null || paket == null) return false;
        String dilink = p.optString("dilink", "");
        if ("5".equals(dilink)) return dilink5Schalter;
        String rawSingleOs = input.singleOs == null ? "" : input.singleOs.trim();
        boolean explicitDualOs = "0".equals(rawSingleOs) || "false".equalsIgnoreCase(rawSingleOs);
        return explicitDualOs
                && !p.optBoolean("singleOs", true)
                && ("3".equals(dilink) || "4".equals(dilink));
    }

    /** Valhalla-Manoever → Gaode {@code NEW_ICON}. Im Zweifel geradeaus (9), nie eine erfundene Richtung. */
    public static int gaodeSymbol(int valhalla) {
        switch (valhalla) {
            case 4: case 5: case 6: return 15;                          // Ziel
            case 9: case 18: case 20: case 23: case 37: return 5;        // rechts halten/Ausfahrt rechts
            case 10: case 19: case 21: case 24: case 38: return 4;       // links halten/Ausfahrt links
            case 11: return 3;                                           // rechts
            case 15: return 2;                                           // links
            case 12: return 7;                                           // scharf rechts
            case 16: return 6;                                           // scharf links
            case 13: return 19;                                          // Wenden rechts
            case 14: return 8;                                           // Wenden links
            case 26: return 11;                                          // in den Kreisverkehr
            case 27: return 12;                                          // aus dem Kreisverkehr
            default: return 9;                                           // geradeaus
        }
    }

    /** Entfernung als Text, Dezimalzeichen nach Systemsprache: „350 m“, „1,2 km“ / „1.2 km“. */
    public static String entfernung(int meter, Locale locale) {
        if (meter < 0) return "";
        if (meter < 1000) return (meter < 100 ? meter : (meter / 10) * 10) + " m";
        if (meter >= 100_000) return (meter / 1000) + " km";
        return String.format(locale == null ? Locale.ROOT : locale, "%.1f km", meter / 1000.0);
    }

    /** Restzeit als Text: „12 min“, „1 h 05 min“. Die Einheiten sind in de/en/fr/it/nl gleich. */
    public static String dauer(int sekunden) {
        if (sekunden < 0) return "";
        int min = (sekunden + 59) / 60;
        if (min < 60) return min + " min";
        return (min / 60) + " h " + String.format(Locale.ROOT, "%02d", min % 60) + " min";
    }

    /** Extras fuer einen Fuehrungsschritt. {@code mitRest=false}: Restweg/-zeit unbekannt (Fremd-Navi). */
    public static Map<String, Object> fuehrung(int valhalla, int meterBisManoever, int restMeter, int restSekunden,
                                               int ausfahrt, boolean mitRest, Locale locale) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("KEY_TYPE", KEY_FUEHRUNG);
        m.put("TYPE", TYP_ECHT);
        m.put("IS_BYD_MAP", Boolean.FALSE);
        m.put("NEW_ICON", gaodeSymbol(valhalla));
        m.put("SEG_REMAIN_DIS_AUTO", entfernung(meterBisManoever, locale));
        if (mitRest) {
            m.put("ROUTE_REMAIN_DIS_AUTO", entfernung(restMeter, locale));
            m.put("ROUTE_REMAIN_TIME_AUTO", dauer(restSekunden));
        }
        m.put("ROUNG_ABOUT_NUM", OemTurnIcon.roundaboutExit(valhalla, ausfahrt));
        return Collections.unmodifiableMap(m);
    }

    /** Extras fuer das Ende der Fuehrung. */
    public static Map<String, Object> ende() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("KEY_TYPE", KEY_ZUSTAND);
        m.put("EXTRA_STATE", ZUSTAND_ENDE);
        return Collections.unmodifiableMap(m);
    }

    /** Daemon-Sender: gezielt per setPackage, nie offen. */
    public static Sender androidSender(final android.content.Context context) {
        return (paket, extras) -> {
            if (context == null || paket == null || extras == null) return false;
            if (!PAKET_DILINK3.equals(paket) && !PAKET_DILINK5.equals(paket)) return false;
            android.content.Intent i = new android.content.Intent(AKTION);
            i.setPackage(paket);
            for (Map.Entry<String, Object> e : extras.entrySet()) {
                Object v = e.getValue();
                if (v instanceof Integer) i.putExtra(e.getKey(), (Integer) v);
                else if (v instanceof Boolean) i.putExtra(e.getKey(), (Boolean) v);
                else i.putExtra(e.getKey(), String.valueOf(v));
            }
            context.sendBroadcast(i);
            return true;
        };
    }

    /** Daemon-Erkennung ueber den PackageManager. */
    public static Installiert androidInstalliert(final android.content.Context context) {
        return paket -> {
            if (context == null) return false;
            try {
                context.getPackageManager().getPackageInfo(paket, 0);
                return true;
            } catch (Throwable t) {
                return false;
            }
        };
    }
}
