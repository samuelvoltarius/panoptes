package at.greenchili.surfmate.sprache;

import at.greenchili.surfmate.R;

/**
 * 0.1.44 (P2) Wann darf das Mikrofon für Sprachbefehle auf? Rein (bis auf die Text-Kennungen), Vertragstest
 * {@code SprachBefehleTest}.
 *
 * <ul>
 *   <li>Schalter {@value SprachBefehle#SCHALTER} ab Werk aus; ohne ihn nichts.</li>
 *   <li>Mikrofon nur mit Erlaubnis des Nutzers (Android-Abfrage in {@link SprachActivity}); nie still erteilt.</li>
 *   <li>Nimmt Panoptes selbst Ton auf (Dashcam-Ton, Mithören), bleibt das Mikrofon zu.</li>
 *   <li>Nur Sprachen mit echter Erkennung und vorhandenem Modell; sonst ein ehrlicher Hinweis.</li>
 *   <li>Weckwort zusätzlich mit eigenem Schalter {@value SprachBefehle#SCHALTER_WECKWORT} (ab Werk aus), sichtbarer
 *       Anzeige „Mikrofon hört auf …“ und Höchstdauer {@link #WECK_MAX_MIN} Minuten; danach Schluss.</li>
 * </ul>
 * Kein Ton wird gespeichert oder gesendet; der Puffer wird nach jedem Zuhören genullt.
 */
public final class SprachRegeln {

    private SprachRegeln() {}

    /** Wie lange ein Sprachbefehl höchstens zuhört (dann Schluss ohne Befehl). */
    public static final int HOEREN_MS = 7000;
    /** Rückfrage: so lange auf Ja/Nein hören; keine Antwort = Nein. */
    public static final int RUECKFRAGE_MS = 6000;
    /** Weckwort hört höchstens so lange am Stück (eine Fahrt), dann aus; neu einschalten per Tipp. */
    public static final int WECK_MAX_MIN = 120;

    public static String startVerweigert(boolean schalter, boolean panoptesTon) {
        if (!schalter) return "schalter_aus";
        if (panoptesTon) return "panoptes_ton_aktiv";
        return null;
    }

    /** Im Sprach-Prozess: Mikrofonrecht, Sprache mit Erkennung, Modell da. */
    public static String hoerenVerweigert(boolean mikrofonRecht, String sprache, boolean modellDa) {
        if (!SprachBefehle.erkennbar(sprache)) return "keine_erkennung";
        if (!modellDa) return "kein_modell";
        if (!mikrofonRecht) return "kein_mikrofonrecht";
        return null;
    }

    public static String weckVerweigert(boolean schalter, boolean weckSchalter, boolean panoptesTon, boolean weckSprache) {
        String g = startVerweigert(schalter, panoptesTon);
        if (g != null) return g;
        if (!weckSchalter) return "weckwort_aus";
        if (!weckSprache) return "keine_erkennung";
        return null;
    }

    /** Grund, warum das Weckwort jetzt aufhören muss, oder null. */
    public static String weckStopp(long jetztMs, long endeMs, boolean schalter, boolean weckSchalter, boolean anzeigeDa) {
        if (!schalter || !weckSchalter) return "schalter_aus";
        if (!anzeigeDa) return "anzeige_weg";
        if (jetztMs >= endeMs) return "zeit_um";
        return null;
    }

    /** Text-Kennung zum Grund (5 Sprachen in strings_sprache.xml). */
    public static int textZuGrund(String grund) {
        if ("schalter_aus".equals(grund)) return R.string.sprache_schalter_aus;
        if ("panoptes_ton_aktiv".equals(grund)) return R.string.sprache_mikrofon_belegt;
        if ("weckwort_aus".equals(grund)) return R.string.sprache_weckwort_aus;
        if ("keine_erkennung".equals(grund)) return R.string.sprache_noch_nicht;
        if ("kein_modell".equals(grund)) return R.string.sprache_kein_modell;
        if ("kein_mikrofonrecht".equals(grund)) return R.string.sprache_kein_mikrofon;
        if ("keine_anzeige".equals(grund)) return R.string.sprache_keine_anzeige;
        return R.string.sprache_fehler;
    }
}
