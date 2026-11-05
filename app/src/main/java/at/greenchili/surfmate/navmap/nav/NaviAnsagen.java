package at.greenchili.surfmate.navmap.nav;

import org.json.JSONObject;

/**
 * Sprachansagen der Panoptes-Führung (0.1.39 G5, Regel A+): Das native Navi spricht nur, wenn der Nutzer
 * {@code navi.sprachansagen} selbst einschaltet. Standard AUS, auch für Bestand. EINE Quelle: Katalog
 * (Feld und Standard) und NavVoice lesen beide nur hier. Gesprochen wird über den Medienkanal, sonst kein Ton.
 */
public final class NaviAnsagen {

    private NaviAnsagen() {}

    public static final String ABSCHNITT = "navi";
    public static final String FELD = "sprachansagen";
    public static final String SCHLUESSEL = ABSCHNITT + "." + FELD;
    public static final boolean STANDARD = false;

    /** Wert aus der Config; nur ein ausdrückliches {@code true} schaltet die Ansagen ein. */
    public static boolean aus(JSONObject root) {
        JSONObject n = root == null ? null : root.optJSONObject(ABSCHNITT);
        return n != null && Boolean.TRUE.equals(n.opt(FELD));
    }

    /** Aktueller Wert; bei jedem Lesefehler aus (es wird dann nichts gesprochen). */
    public static boolean aktuell() {
        try {
            return aus(at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig());
        } catch (Throwable t) {
            return STANDARD;
        }
    }
}
