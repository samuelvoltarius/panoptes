package at.greenchili.surfmate.appupdate;

import at.greenchili.surfmate.server.Messages;

import org.json.JSONArray;

/**
 * 0.1.31 Update-Hinweis am Auto-Display (30.10.: Nutzer ohne Telegram merken neue Versionen kaum). Reine
 * Entscheidungen, ohne Android – testbar.
 *
 * <p>Die Karte kommt nur bei Zündung an, in P, bei 0 km/h und nach {@link #RUHE_MS} ruhigem Stand (so ist die
 * Rückkehr-Karte vom Einsteigen schon durch). Je Version höchstens {@link #MAX_ANZEIGEN}-mal, frühestens nach
 * {@link #WIEDER_NACH_MS} wieder: Gezählt wird beim Zeigen, „Später“ und „nicht reagiert“ sind also dasselbe. Danach
 * bleibt nur das Abzeichen unter System → Updates. Kein Ton, kein Licht (REGEL A+), keine zusätzliche Abfrage nach
 * außen (REGEL NR. 2): Es zählt nur, was die vorhandene Update-Prüfung gefunden hat.
 *
 * <p>„Jetzt installieren“ bei Zündung an: Installieren geht erst, wenn das Auto aus ist, und dann ist das Display
 * dunkel. Darum merkt sich der Updater diese eine Version vor und installiert sie unter denselben Regeln wie
 * „automatisch“ ({@link #vorgemerktAb}). Der Tipp ist die Zustimmung (Koordination 30.10.); zurücknehmen geht direkt
 * auf der Karte und unter System → Updates. Die Vormerkung gilt nur für genau diese Version und höchstens
 * {@link #VORGEMERKT_GILT_MS}; danach und nach {@link #VORGEMERKT_MAX_FEHLVERSUCHE} Fehlversuchen verfällt sie.
 */
public final class UpdateHinweis {

    private UpdateHinweis() {}

    public static final long WIEDER_NACH_MS = 24 * 3_600_000L;
    public static final int MAX_ANZEIGEN = 3;
    /** So lange muss das Auto ruhig stehen, bevor die Karte kommt. */
    public static final long RUHE_MS = 30_000L;
    /** Sicherheitsnetz, falls niemand tippt. */
    public static final int SICHTBAR_SEK = 120;
    /** Karte kam nicht aufs Display (kein Overlay-Recht, andere Karte offen): nicht zählen, später noch einmal. */
    public static final long PAUSE_NACH_FEHLVERSUCH_MS = 5 * 60_000L;
    /** So lange gilt eine Vormerkung. */
    public static final long VORGEMERKT_GILT_MS = 7 * 24 * 3_600_000L;
    /** Abstand zwischen zwei Versuchen: nie eine Schleife, auch nicht bei einem Fehlschlag. */
    public static final long VORGEMERKT_PAUSE_MS = 3_600_000L;
    public static final int VORGEMERKT_MAX_FEHLVERSUCHE = 3;
    /** Text für die Karte (geht als Shell-Argument ans Overlay). */
    static final int NOTIZEN_MAX_ZEICHEN = 900;

    /** Dauerhafter Stand je Version (in {@code appupdate_stand.json}). */
    public static final class Stand {
        /** versionCode, für den {@link #anzahl} und {@link #zuletzt} gelten. */
        public int vc;
        public int anzahl;
        public long zuletzt;
        /** Diese Version soll installiert werden, sobald das Auto aus ist (0 = keine). */
        public int vorgemerktVc;
        public long vorgemerktAm;
        /** Frühester nächster Versuch. */
        public long versuchAb;
        public int fehlversuche;
    }

    /** Vormerkung gilt: genau diese Version, nicht abgelaufen (Uhr rückwärts = abgelaufen). */
    public static boolean vorgemerktGilt(Stand s, int versionCode, long jetzt) {
        return s.vorgemerktVc > 0 && s.vorgemerktVc == versionCode && jetzt >= s.vorgemerktAm
                && jetzt - s.vorgemerktAm < VORGEMERKT_GILT_MS;
    }

    public static void vormerken(Stand s, int versionCode, long jetzt) {
        s.vorgemerktVc = versionCode;
        s.vorgemerktAm = jetzt;
        s.versuchAb = 0;
        s.fehlversuche = 0;
    }

    public static void vergessen(Stand s) {
        vormerken(s, 0, 0);
    }

    /** Versuch ist vorbei: Pause; nach zu vielen Fehlschlägen verfällt die Vormerkung (die Karte kommt wieder). */
    public static void versuchVorbei(Stand s, boolean gescheitert, long jetzt) {
        s.versuchAb = jetzt + VORGEMERKT_PAUSE_MS;
        if (gescheitert && ++s.fehlversuche >= VORGEMERKT_MAX_FEHLVERSUCHE) vergessen(s);
    }

    /** Vorabversion nur, wenn der Nutzer den Kanal test gewählt hat. */
    static boolean kanalPasst(Freigabe f, String kanal) {
        return "stabil".equals(f.kanal) || "test".equals(UpdateRegel.kanal(kanal));
    }

    public static final class Lage {
        /** {@code update.hinweis}. */
        public boolean schalterAn;
        /** {@code update.pruefen}. */
        public boolean pruefenAn;
        /** Modus „automatisch“: installiert sowieso von selbst, kein Hinweis nötig (außer Vorabversion). */
        public boolean automatisch;
        public String kanal;
        public int installiert;
        public Freigabe verfuegbar;
        public int rueckfallVc = -1;
        /** Ein Update läuft (Phase, Sperre, eigener Ablauf). */
        public boolean laeuftSchon;
        /** Seit wann Zündung an + P + 0 km/h + kein Wächter-Ereignis (−1 = gerade nicht). */
        public long ruhigSeit = -1;
        public long jetzt;
    }

    /** Grund, warum die Karte jetzt nicht kommt, oder null (= zeigen). */
    public static String nichtWeil(Lage l, Stand s) {
        if (!l.schalterAn) return "schalter_aus";
        if (!l.pruefenAn) return "pruefen_aus";
        Freigabe f = l.verfuegbar;
        if (f == null || f.versionCode <= l.installiert) return "kein_update";
        if (!kanalPasst(f, l.kanal)) return "kanal";
        if (l.automatisch && "stabil".equals(f.kanal)) return "automatisch";
        if (l.installiert < f.minVersion) return "zu_alt";
        if (f.versionCode == l.rueckfallVc) return "zurueckgerollt";
        if (vorgemerktGilt(s, f.versionCode, l.jetzt)) return "vorgemerkt";
        if (l.laeuftSchon) return "laeuft_schon";
        if (l.ruhigSeit < 0 || l.jetzt - l.ruhigSeit < RUHE_MS) return "nicht_ruhig";
        if (s.vc == f.versionCode) {
            if (s.anzahl >= MAX_ANZEIGEN) return "genug";
            if (l.jetzt >= s.zuletzt && l.jetzt - s.zuletzt < WIEDER_NACH_MS) return "wartet";
        }
        return null;
    }

    /** Karte wurde wirklich gezeigt: zählen (neue Version beginnt von vorn). */
    public static void gezeigt(Stand s, int versionCode, long jetzt) {
        if (s.vc != versionCode) {
            s.vc = versionCode;
            s.anzahl = 0;
        }
        s.anzahl++;
        s.zuletzt = jetzt;
    }

    /** Ruhiger Stand: nur Zündung an (autoritativ), keine Fahrsperre, 0 km/h (bekannt), kein Wächter-Ereignis. */
    public static boolean ruhig(Boolean accAus, String fahrsperre, double kmh, boolean waechterEreignis) {
        return Boolean.FALSE.equals(accAus) && fahrsperre == null && !Double.isNaN(kmh) && Math.abs(kmh) < 0.5
                && !waechterEreignis;
    }

    /**
     * Wie {@link UpdateRegel#automatischAb}, aber für die eine vorgemerkte Version: gleiche Sicherheitsregeln und
     * Wartezeit. Eine Vorabversion nur im gewählten Kanal, nie eine zurückgerollte. {@code frei} =
     * {@link #installFrei}, bei jedem Takt neu geprüft. −1 = jetzt nicht, später erneut.
     */
    public static long vorgemerktAb(UpdateRegel.Lage l, Stand s, String kanal, boolean frei, long jetzt, long ausSeit) {
        if (ausSeit < 0 || !frei || UpdateRegel.nichtWeil(l) != null) return -1;
        Freigabe f = l.verfuegbar;
        if (!vorgemerktGilt(s, f.versionCode, jetzt) || f.versionCode == l.rueckfallVc || !kanalPasst(f, kanal)) return -1;
        return Math.max(ausSeit + UpdateRegel.AUTOMATISCH_NACH_MS, s.versuchAb);
    }

    /**
     * Zusätzliche Prüfung vor einer vorgemerkten Installation: keine Fahrsperre, Gang nicht bekannt außerhalb P
     * (unbekannt zählt nicht: bei Auto aus liefern nicht alle Modelle den Gang), kein Wächter-Ereignis, keine Aufnahme.
     */
    public static boolean installFrei(String fahrsperre, Boolean gangP, boolean waechterEreignis, boolean aufnahme) {
        return fahrsperre == null && !Boolean.FALSE.equals(gangP) && !waechterEreignis && !aufnahme;
    }

    /** Darf nach diesem Grund aus {@link FernUpdate#starten} vorgemerkt werden (das Auto muss nur noch aus sein)? */
    public static boolean vormerkbar(String grund) {
        return "zuendung_an".equals(grund) || "zustand_unbekannt".equals(grund) || "wartet_wlan".equals(grund);
    }

    /** Stichpunkte für die Karte, gekürzt. */
    public static String notizenText(JSONArray notizen) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; notizen != null && i < notizen.length(); i++) {
            String z = notizen.optString(i, "").trim();
            if (z.isEmpty()) continue;
            if (b.length() + z.length() + 3 > NOTIZEN_MAX_ZEICHEN) break;
            if (b.length() > 0) b.append('\n');
            b.append("• ").append(z);
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ Texte (Nutzersprache, Rückfall Englisch)

    public static String titel(String version) {
        return Messages.text("update_hinweis.titel", "New version {0}", version);
    }

    public static String frage() {
        return Messages.text("update_hinweis.frage", "Update Panoptes now?");
    }

    public static String knopfInstallieren() {
        return Messages.text("update_hinweis.installieren", "Install now");
    }

    public static String knopfSpaeter() {
        return Messages.text("update_hinweis.spaeter", "Later");
    }

    public static String knopfWasNeu() {
        return Messages.text("update_hinweis.was_neu", "What's new?");
    }

    public static String knopfSchliessen() {
        return Messages.text("update_hinweis.schliessen", "Close");
    }

    public static String knopfZuruecknehmen() {
        return Messages.text("update_hinweis.zuruecknehmen", "Undo");
    }

    public static String textFehler() {
        return Messages.text("update_hinweis.fehler", "That didn't work. Please try again later.");
    }

    public static String textVorgemerkt(boolean wartetWlan) {
        return wartetWlan
                ? Messages.text("update_hinweis.vorgemerkt_wlan",
                        "Panoptes installs the update about 10 minutes after you park the car, as soon as it has "
                                + "Wi-Fi. The guard is off for a few minutes while it does.")
                : Messages.text("update_hinweis.vorgemerkt",
                        "Panoptes installs the update about 10 minutes after you park the car. "
                                + "The guard is off for a few minutes while it does.");
    }

    public static String geraetName() {
        return Messages.text("update_hinweis.geraet", "car display");
    }
}
