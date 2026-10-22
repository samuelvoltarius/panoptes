package at.greenchili.surfmate.power;

/**
 * 0.1.13 (Kern 7, Bericht WEG2_TAILSCALE_WIEDERBELEBEN.md, GO Koordination 22.10.): Entscheidung, ob Panoptes die
 * fremde Tailscale-App (Weg 2) mit ihrem offiziellen Broadcast {@code com.tailscale.ipn.CONNECT_VPN} wieder
 * anstößt. Reine Logik ohne Android.
 *
 * <p>Nur wenn ALLES gilt: Auto steht · Tailscale-App ist vom Nutzer als Always-on-VPN eingetragen · Weg 2 war seit
 * dem Daemon-Start schon aktiv · Weg 2 hat kein VPN-Netz (tun) – ein Prozess allein zählt NICHT als lebendig: nach
 * BYDs forceStop beim Abstellen bleibt ein leerer Prozess ohne VPN-Dienst bis ~30 min stehen (Deploy, logcat 22.10.) ·
 * erster Versuch nach dem Parken sofort, sonst erst nach {@link #TOT_MIN_MS} tot ·
 * Display ist tatsächlich aus (sonst könnte ein Hinweis der Tailscale-App sichtbar werden, A+) · Bremsen frei
 * (höchstens 1 Versuch je 30 min, 6 je Tag, nach 2 Fehlschlägen für den Tag aus) · Schalter an.
 */
public final class Weg2Regel {

    public static final long TOT_MIN_MS = 10 * 60_000L;
    public static final long ABSTAND_MS = 30 * 60_000L;
    public static final int MAX_JE_TAG = 6;
    public static final int MAX_FEHLSCHLAEGE_JE_TAG = 2;

    public static final String PAKET = "com.tailscale.ipn";

    /** Momentaufnahme der Eingänge. */
    public static final class Lage {
        public boolean schalterAn = true;
        public boolean autoSteht;
        public String alwaysOnPaket;
        public boolean warSchonAktiv;
        public boolean prozessDa;
        public boolean vpnNetzDa;
        public long totSeitMs;          // 0 = lebt bzw. unbekannt
        /** In diesem Park noch kein Versuch: dann sofort (typischer Fall: BYD beendet die App beim Abstellen). */
        public boolean ersterVersuchDiesesParks;
        public boolean displayAus;
        public long jetztMs;
        public long letzterVersuchMs;
        public int versucheHeute;
        public int fehlschlaegeHeute;
    }

    /** null = senden; sonst der Grund, warum nicht (für Status/Protokoll). */
    public static String grund(Lage l) {
        if (!l.schalterAn) return "aus";
        if (!PAKET.equals(l.alwaysOnPaket == null ? "" : l.alwaysOnPaket.trim())) return "kein_always_on";
        if (!l.autoSteht) return "auto_nicht_geparkt";
        if (l.vpnNetzDa) return "lebt";
        if (!l.warSchonAktiv) return "nie_aktiv";
        if (!l.ersterVersuchDiesesParks && (l.totSeitMs <= 0 || l.jetztMs - l.totSeitMs < TOT_MIN_MS)) {
            return "noch_nicht_lange_tot";
        }
        if (!l.displayAus) return "display_an";
        if (l.fehlschlaegeHeute >= MAX_FEHLSCHLAEGE_JE_TAG) return "heute_aufgegeben";
        if (l.versucheHeute >= MAX_JE_TAG) return "tageslimit";
        if (l.letzterVersuchMs > 0 && l.jetztMs - l.letzterVersuchMs < ABSTAND_MS) return "abstand";
        return null;
    }

    /** Der einzige Eingriff: der von der Tailscale-App dafür vorgesehene, exportierte Broadcast (keine Activity). */
    public static String befehl() {
        return "am broadcast --include-stopped-packages -n " + PAKET + "/.IPNReceiver -a " + PAKET + ".CONNECT_VPN";
    }

    /** Rein: Display aus laut {@code dumpsys power} (Zeile „Display Power: state=OFF“). Unbekannt = nicht aus. */
    public static boolean displayAus(String dumpsysPower) {
        if (dumpsysPower == null) return false;
        for (String z : dumpsysPower.split("\n")) {
            String t = z.trim();
            if (t.startsWith("Display Power: state=")) return t.endsWith("state=OFF");
        }
        return false;
    }
}
