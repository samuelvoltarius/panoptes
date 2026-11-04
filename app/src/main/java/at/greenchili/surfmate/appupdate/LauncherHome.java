package at.greenchili.surfmate.appupdate;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Panoptes Launcher als Standard-Startbildschirm (0.1.38.7, LAUNCHER_HOME_BEFUND.md): Daemon-Seite. Läuft als
 * {@code shell} (kein Context) über feste {@code cmd}/{@code pm}/{@code am}-Wörter.
 *
 * <p>Wichtig (Emulator API 29, 04.11.2026): Die Shell darf Komponenten fremder Apps NICHT ein-/ausschalten
 * ("Shell cannot change component state"). Den Alias {@code .HomeAlias} schaltet daher der Launcher selbst (eigene
 * Komponente). Die Shell kann dagegen den Standard zurücksetzen ({@code set-home-activity} auf den BYD-Launcher) und
 * das Paket ausschalten ({@code pm disable-user}).
 *
 * <p>Grundsätze: Nur dieses Paket. Der BYD-Launcher ({@code com.android.launcher3}) wird nie deaktiviert, gelöscht oder
 * verändert, nur als Standard wieder eingesetzt. Kein freier Shell-Text, keine Parameter von außen.
 * {@link LauncherPaket} bleibt bewusst frei von allem, was den Standard-Startbildschirm berührt.
 */
public final class LauncherHome {

    public static final String PAKET = LauncherPaket.PAKET;

    static final String ABFRAGE =
            "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME";
    static final String AUFLOESEN =
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME";
    static final String STANDARD_SETZEN = "cmd package set-home-activity --user 0 " + PAKET;

    private static final Pattern KOMPONENTE = Pattern.compile("^\\s*([A-Za-z0-9_.]+)/(\\S+)\\s*$");

    private LauncherHome() {}

    /** Ein Startbildschirm-Kandidat des Systems. */
    public static final class Kandidat {
        public final String paket;
        public final String klasse;

        Kandidat(String paket, String klasse) {
            this.paket = paket;
            this.klasse = klasse;
        }

        public String komponente() {
            return paket + "/" + klasse;
        }

        boolean fallbackHome() {
            return klasse.endsWith("FallbackHome");
        }
    }

    /** Lage am Gerät. */
    public static final class Stand {
        /** Der HOME-Alias ist aktiv (das System listet uns als Startbildschirm). */
        public final boolean aliasAn;
        /** Das System löst HOME gerade auf uns auf (wir sind Standard). */
        public final boolean standard;
        /** Der erste fremde, echte Startbildschirm (BYD Launcher3), oder null. */
        public final Kandidat original;

        Stand(boolean aliasAn, boolean standard, Kandidat original) {
            this.aliasAn = aliasAn;
            this.standard = standard;
            this.original = original;
        }
    }

    /** Liest nur. Nie null. */
    public static Stand lesen(LauncherPaket.Geraet g) {
        List<Kandidat> alle = kandidaten(g.shell(ABFRAGE, 15));
        boolean aliasAn = false;
        Kandidat original = null;
        for (Kandidat k : alle) {
            if (PAKET.equals(k.paket)) {
                aliasAn = true;
            } else if (original == null && !"android".equals(k.paket) && !k.fallbackHome()
                    && !k.klasse.endsWith("ResolverActivity")) {
                original = k;
            }
        }
        List<Kandidat> aufgeloest = kandidaten(g.shell(AUFLOESEN, 15));
        boolean standard = !aufgeloest.isEmpty() && PAKET.equals(aufgeloest.get(0).paket);
        return new Stand(aliasAn, standard, original);
    }

    /**
     * Wir werden Standard. Nur nach ausdrücklichem Knopfdruck und nur, wenn der Launcher seinen Alias eingeschaltet
     * hat. Android bestimmt die Komponente selbst. null = ok.
     */
    public static String alsStandardSetzen(LauncherPaket.Geraet g) {
        String aus = g.shell(STANDARD_SETZEN, 15);
        return fehler(aus) ? "standard:" + LauncherPaket.kurz(aus) : null;
    }

    /**
     * RÜCKWEG, braucht den Launcher nicht: Der Standard wird auf den echten BYD-Startbildschirm zurückgesetzt
     * ({@code set-home-activity}), danach wird er gestartet. Prüft zurück, dass wir nicht mehr Standard sind.
     * null = ok.
     */
    public static String zurueckZumOriginal(LauncherPaket.Geraet g, Stand s) {
        if (s == null || s.original == null) return "kein_original";
        String aus = g.shell("cmd package set-home-activity --user 0 " + s.original.paket, 15);
        if (fehler(aus)) return "zurueck:" + LauncherPaket.kurz(aus);
        if (lesen(g).standard) return "zurueck:noch_standard";
        return originalOeffnen(g, s);
    }

    /** Stärkste Stufe: das Launcher-Paket ausschalten ({@code pm disable-user}), BYD bleibt unberührt; null = ok. */
    public static String paketAus(LauncherPaket.Geraet g) {
        String aus = g.shell("pm disable-user --user 0 " + PAKET, 15);
        return aus != null && aus.contains("disabled") ? null : "paket_aus:" + LauncherPaket.kurz(aus);
    }

    /** Ist das Launcher-Paket ausgeschaltet (letzte Rückfall-Stufe, {@code pm disable-user})? Liest nur. */
    public static boolean paketDeaktiviert(LauncherPaket.Geraet g) {
        String aus = g.shell("pm list packages -d", 15);
        if (aus == null) return false;
        for (String z : aus.split("\\r?\\n")) if (z.trim().equals("package:" + PAKET)) return true;
        return false;
    }

    /** Das Launcher-Paket wieder einschalten ({@code pm enable}); null = ok. */
    public static String paketAn(LauncherPaket.Geraet g) {
        String aus = g.shell("pm enable " + PAKET, 15);
        return aus != null && aus.contains("enabled") ? null : "paket_an:" + LauncherPaket.kurz(aus);
    }

    /**
     * 0.1.38.8 (04.11.: „das brauchen wir auch für den Launcher?“): nach einem Launcher-Update. Nur wenn der
     * Nutzer den Launcher als Startbildschirm gewählt UND behalten hat ({@code wunsch}, {@code behalten}) und das Paket
     * nicht von der Rückfall-Stufe ausgeschaltet ist: Sind wir nicht mehr Standard, aber der Alias ist an, wird der
     * Standard wie beim „Behalten“ neu gesetzt und zurückgelesen. Den Alias schaltet nur der Launcher selbst; den
     * BYD-Startbildschirm fasst das nie an. null = nichts zu tun oder wiederhergestellt, sonst Grund (→ eine Meldung).
     */
    public static String nachUpdate(LauncherPaket.Geraet g, boolean wunsch, boolean behalten) {
        if (!wunsch || !behalten || paketDeaktiviert(g)) return null;
        Stand s = lesen(g);
        if (s.standard) return null;
        if (!s.aliasAn) return "alias_aus";
        String f = alsStandardSetzen(g);
        if (f != null) return f;
        return lesen(g).standard ? null : "noch_nicht_standard";
    }

    /** Startet den echten BYD-Startbildschirm (Komponente aus {@link #lesen}), nie uns selbst; null = ok. */
    public static String originalOeffnen(LauncherPaket.Geraet g, Stand s) {
        if (s == null || s.original == null) return "kein_original";
        String aus = g.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME -n "
                + s.original.komponente(), 15);
        return aus != null && !aus.contains("Error") && !aus.contains("Exception") ? null : "start:" + LauncherPaket.kurz(aus);
    }

    private static boolean fehler(String aus) {
        return aus == null || aus.contains("Error") || aus.contains("Exception") || aus.contains("Unknown");
    }

    /** Zeilen "paket/Klasse" aus {@code cmd package … --brief}; Kopfzeilen und Zahlen werden übergangen. */
    static List<Kandidat> kandidaten(String ausgabe) {
        List<Kandidat> r = new ArrayList<>();
        if (ausgabe == null) return r;
        for (String zeile : ausgabe.split("\\r?\\n")) {
            Matcher m = KOMPONENTE.matcher(zeile);
            if (!m.matches()) continue;
            String klasse = m.group(2);
            if (klasse.startsWith(".")) klasse = m.group(1) + klasse;
            r.add(new Kandidat(m.group(1), klasse));
        }
        return r;
    }
}
