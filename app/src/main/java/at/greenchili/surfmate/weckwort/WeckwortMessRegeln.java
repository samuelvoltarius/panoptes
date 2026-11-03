package at.greenchili.surfmate.weckwort;

import org.json.JSONObject;

/**
 * Regeln der Weckwort-Messung Stufe 1 (rein, ohne Android).
 *
 * <ul>
 *   <li>Schalter {@link #SCHALTER} ist ab Werk AUS. Ohne Schalter startet nichts.</li>
 *   <li>Start nur am Autobildschirm (JavaScript-Brücke der App-WebView auf einer lokalen Seite), nie per Netz,
 *       Telegram, Handy, Cockpit oder Automation.</li>
 *   <li>Ohne sichtbare Anzeige „Mikrofon an (Messung)“ kein Mikrofon.</li>
 *   <li>Läuft eine Panoptes-Tonaufnahme (Dashcam-Ton, Mithören), startet die Messung nicht bzw. hört auf.</li>
 *   <li>Feste Höchstdauer, danach Schluss.</li>
 * </ul>
 * Keine Verbindung zu Fahrzeugbefehlen, Automationen oder dem KI-Assistenten.
 */
public final class WeckwortMessRegeln {

    private WeckwortMessRegeln() {}

    /** Config-Schlüssel (Abschnitt „sprache“, Feld „weckwortMessung“). Standard AUS. */
    public static final String SCHALTER = "sprache.weckwortMessung";
    public static final String ABSCHNITT = "sprache";
    public static final String FELD = "weckwortMessung";

    public static final int MINUTEN_STANDARD = 30;
    public static final int MINUTEN_MAX = 120;

    /** Festes Messwort (im Wortschatz von vosk-model-small-de-0.15). Nur zum Zählen, löst nichts aus. */
    public static final String MESSWORT = "hallo auto";

    /** Ordner unter getExternalFilesDir: Ergebnis und (zum Messen) das Modell. */
    public static final String ORDNER = "weckwort";
    public static final String ERGEBNIS_DATEI = "messung.json";
    public static final String MODELL_ORDNER = "modell";

    /** Pfad, unter dem der Kamera-Daemon (uid shell) das Ergebnis liest. */
    public static final String ERGEBNIS_PFAD_DAEMON =
            "/storage/emulated/0/Android/data/at.greenchili.surfmate/files/" + ORDNER + "/" + ERGEBNIS_DATEI;

    /** Schalter aus der Gesamt-Config lesen; alles Unklare zählt als AUS. */
    public static boolean schalterAn(JSONObject config) {
        if (config == null) return false;
        JSONObject s = config.optJSONObject(ABSCHNITT);
        return s != null && s.optBoolean(FELD, false);
    }

    public static int minuten(int gewuenscht) {
        if (gewuenscht <= 0) return MINUTEN_STANDARD;
        return Math.min(MINUTEN_MAX, gewuenscht);
    }

    /**
     * Grund, warum die Messung NICHT starten darf, oder null.
     *
     * @param lokaleSeite     Aufruf kommt von einer lokalen Seite in der App-WebView (Autobildschirm)
     * @param schalterAn      {@link #SCHALTER}
     * @param anzeigeMoeglich Overlay-Recht da (sonst keine sichtbare Anzeige)
     * @param mikrofonRecht   RECORD_AUDIO erteilt
     * @param panoptesTon     Panoptes nimmt gerade selbst Ton auf (Dashcam-Ton, Mithören)
     * @param laeuftSchon     Messung läuft bereits
     */
    public static String startVerweigert(boolean lokaleSeite, boolean schalterAn, boolean anzeigeMoeglich,
                                         boolean mikrofonRecht, boolean panoptesTon, boolean laeuftSchon) {
        if (!lokaleSeite) return "nur_am_autobildschirm";
        if (!schalterAn) return "schalter_aus";
        if (laeuftSchon) return "laeuft_schon";
        if (!anzeigeMoeglich) return "keine_anzeige";
        if (!mikrofonRecht) return "kein_mikrofonrecht";
        if (panoptesTon) return "panoptes_ton_aktiv";
        return null;
    }

    /** Grund, warum eine laufende Messung jetzt enden muss, oder null. */
    public static String stoppGrund(long jetztMs, long endeMs, boolean schalterAn, boolean panoptesTon,
                                    boolean anzeigeDa) {
        if (!schalterAn) return "schalter_aus";
        if (!anzeigeDa) return "anzeige_weg";
        if (panoptesTon) return "panoptes_ton_aktiv";
        if (jetztMs >= endeMs) return "zeit_um";
        return null;
    }

    /** Ist das Ergebnis der Erkennung das Messwort? Erwartet Vosk-JSON {"text": "..."}; nur der Vergleich zählt. */
    public static boolean istMesswort(String voskErgebnisJson) {
        if (voskErgebnisJson == null || voskErgebnisJson.isEmpty()) return false;
        try {
            String text = new JSONObject(voskErgebnisJson).optString("text", "");
            return text.trim().replaceAll("\\s+", " ").contains(MESSWORT);
        } catch (Exception e) {
            return false;
        }
    }

    /** Vosk-Grammatik: nur das Messwort, alles andere als [unk]. */
    public static String grammatik() {
        return "[\"" + MESSWORT + "\", \"[unk]\"]";
    }
}
