package at.greenchili.surfmate.power;

/**
 * 0.1.13 (Kern 7, Koordination 22.10.: 12 V, A+, Regel 1): globale Android-Einstellungen, die Panoptes früher
 * dauerhaft gesetzt hat, und ihre Rücknahme. Bericht: SYSTEMEINGRIFFE_BERICHT.md (Koordinationsordner).
 *
 * <ul>
 *   <li>{@code stay_on_while_plugged_in=7} (Display-Policy „an“, solange Strom anliegt – im Auto praktisch immer)
 *       setzt Panoptes NICHT mehr. Bei ACC an ist das Display ohnehin an; im Stand hält der AccSentry-Keep-alive den
 *       AP gezielt wach (userActivity ohne Licht + periodisches wakeUp, abschaltbar über „USB im Stand versorgen“).</li>
 *   <li>Der Ursprungswert wird beim ersten Lauf von 0.1.13 einmal gesichert. Stand er da schon auf 7, ist er
 *       „unbekannt“ (vermutlich von einer älteren Panoptes-Version gesetzt).</li>
 *   <li>Beim Parken setzt der Park-Reaper einen von uns stammenden Wert (7, Ursprung nicht 7) auf den Ursprung
 *       zurück bzw. auf 0, wenn der Ursprung unbekannt ist – wie bisher {@code svc power stayon false}.</li>
 *   <li>{@code wifi_sleep_policy=2} entspricht dem Android-Standard (WIFI_SLEEP_POLICY_NEVER) und wird nicht mehr
 *       geschrieben. Doze ({@code dumpsys deviceidle}) wird nicht mehr global abgeschaltet; ein früher abgeschaltetes
 *       Doze bleibt im Stand unangetastet (Regel B: Fernzugang nicht gefährden) und lässt sich ausdrücklich über
 *       {@code POST /api/system/eingriffe/zuruecksetzen} wieder einschalten.</li>
 *   <li>0.1.19: WLAN. Ursprung von {@code ensureWifiEnabled}: Ausgangscode (Overdrive, Basis 4c4d1c31), nicht von
 *       Panoptes erdacht. Wirksam sind der Stand-Keep-alive im AccSentryDaemon ({@code svc wifi enable} je Takt) und
 *       {@code SentryDaemon.enableWifi} beim Daemon-Start (beide
 *       abschaltbar über eine Funk-Regel „WLAN aus“ = {@code isWifiKeepAliveSuppressed}). Die Launcher-Fassung mit
 *       dauerhaften Einstellungen ({@code byd_wifi_always_on}, {@code byd_wifi_keep_alive}, {@code wifi_scan_throttle},
 *       {@code wifi_sleep_policy}) hatte schon im Ausgangscode keinen Aufrufer und ist entfernt. Der WLAN-Zustand vor
 *       dem ersten Einschalten durch Panoptes wird einmal als {@code global.wifi_on} gesichert (nur Nachweis; es wird
 *       nichts automatisch zurückgesetzt, weil das die Erreichbarkeit im Stand verschlechtern würde).</li>
 * </ul>
 * Alles als Shell-Text, weil sentry_daemon (UID 1000), Park-Reaper (Shell-Skript) und App (adb-Shell) es ausführen.
 */
public final class SystemEingriffe {

    private SystemEingriffe() {}

    public static final String URSPRUNG_DATEI = at.greenchili.surfmate.util.DaemonStorage.rebase(
            "/data/user_de/0/com.android.shell/surfmate/systemeingriffe_ursprung");
    static final String SCHLUESSEL_STAY_ON = "global.stay_on_while_plugged_in";
    /** Wert, den ältere Panoptes-Versionen gesetzt haben. */
    static final String PANOPTES_STAY_ON = "7";
    public static final String UNBEKANNT = "unbekannt";
    static final String SCHLUESSEL_WIFI_ON = "global.wifi_on";

    /** Rein: Was als Ursprung gilt, wenn beim ersten Sichern {@code aktuell} steht. */
    public static String ursprungAus(String aktuell) {
        String v = aktuell == null ? "" : aktuell.trim();
        if (v.isEmpty() || "null".equals(v) || PANOPTES_STAY_ON.equals(v)) return UNBEKANNT;
        return gueltig(v) ? v : UNBEKANNT;
    }

    /** Rein: Wert, auf den beim Parken bzw. Abschalten zurückgesetzt wird (unbekannt → 0 wie bisher). */
    public static String zurueckWert(String ursprung) {
        return ursprung != null && gueltig(ursprung.trim()) ? ursprung.trim() : "0";
    }

    /** Rein: Muss beim Parken zurückgesetzt werden? Nur ein von uns stammender Wert (7, Ursprung nicht 7). */
    public static boolean zuruecksetzen(String aktuell, String ursprung) {
        return PANOPTES_STAY_ON.equals(aktuell == null ? "" : aktuell.trim())
                && !PANOPTES_STAY_ON.equals(zurueckWert(ursprung));
    }

    static boolean gueltig(String v) {
        try {
            int i = Integer.parseInt(v);
            return i >= 0 && i <= 15;   // Bitmaske AC|USB|WIRELESS|DOCK
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Shell: Ursprung einmalig sichern (vorhandene Sicherung bleibt). */
    public static String sichernSkript() {
        String f = URSPRUNG_DATEI;
        return "F='" + f + "'; grep -q '^" + SCHLUESSEL_STAY_ON + "=' \"$F\" 2>/dev/null || { "
                + "v=$(settings get global stay_on_while_plugged_in 2>/dev/null); "
                + "case \"$v\" in ''|null|" + PANOPTES_STAY_ON + ") v=" + UNBEKANNT + ";; esac; "
                + "echo \"" + SCHLUESSEL_STAY_ON + "=$v\" >> \"$F\"; }; " + wlanSichernSkript();
    }

    /**
     * Shell: WLAN-Ursprung ({@code settings get global wifi_on}) einmalig sichern, bevor Panoptes es zum ersten Mal
     * einschaltet. Vorhandene Sicherung bleibt; leer/null → unbekannt.
     */
    public static String wlanSichernSkript() {
        String f = URSPRUNG_DATEI;
        return "F='" + f + "'; grep -q '^" + SCHLUESSEL_WIFI_ON + "=' \"$F\" 2>/dev/null || { "
                + "w=$(settings get global wifi_on 2>/dev/null); "
                + "case \"$w\" in ''|null) w=" + UNBEKANNT + ";; esac; "
                + "echo \"" + SCHLUESSEL_WIFI_ON + "=$w\" >> \"$F\"; }";
    }

    /**
     * Shell für den Park-Reaper (ersetzt {@code svc power stayon false}): nur ein von uns stammendes 7 auf den
     * Ursprung (bzw. 0) zurücksetzen; ein vom Nutzer bzw. Hersteller gesetzter anderer Wert bleibt.
     */
    public static String parkSkript() {
        String f = URSPRUNG_DATEI;
        return sichernSkript() + "; "
                + "a=$(settings get global stay_on_while_plugged_in 2>/dev/null); "
                + "u=$(sed -n 's/^" + SCHLUESSEL_STAY_ON + "=//p' '" + f + "' 2>/dev/null | head -1); "
                + "case \"$u\" in ''|" + UNBEKANNT + ") u=0;; esac; "
                + "if [ \"$a\" = \"" + PANOPTES_STAY_ON + "\" ] && [ \"$u\" != \"" + PANOPTES_STAY_ON + "\" ]; then "
                + "settings put global stay_on_while_plugged_in \"$u\"; fi";
    }

    /** Shell für „zurücksetzen“ (ausdrücklich, über die API): stay_on auf den Ursprung, Doze wieder an. */
    public static String zuruecksetzenSkript() {
        String f = URSPRUNG_DATEI;
        return "u=$(sed -n 's/^" + SCHLUESSEL_STAY_ON + "=//p' '" + f + "' 2>/dev/null | head -1); "
                + "case \"$u\" in ''|" + UNBEKANNT + ") u=0;; esac; "
                + "settings put global stay_on_while_plugged_in \"$u\"; "
                + "dumpsys deviceidle enable >/dev/null 2>&1; echo ok";
    }
}
