package at.greenchili.surfmate.sprache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 0.1.44 (P2) Sprachbefehle ohne eigenen KI-Schlüssel: feste Kommandos, offline erkannt (Vosk, Apache-2.0, Grammatik
 * nur aus diesen Sätzen). Rein, ohne Android – Vertragstest {@code SprachBefehleTest}.
 *
 * <ul>
 *   <li>Echte Erkennung nur in {@link #ERKENNUNG} (Deutsch und Englisch, Modelle {@code vosk-model-small-de-0.15} und
 *       {@code vosk-model-small-en-us-0.15}, beide Apache-2.0). Französisch, Italienisch und Niederländisch haben die
 *       Sätze schon, sind aber ehrlich „noch nicht“ ({@link #erkennbar}).</li>
 *   <li>Fahrzeugbefehle (Klima) nur nach Rückfrage ({@link #brauchtRueckfrage}); der Kern verlangt dafür eine Marke
 *       ({@code SprachApiHandler}). Klima nur in Stufen: wärmer/kälter = eine Stufe, nie eine Gradzahl.</li>
 *   <li>Regel A+: Hupe, Blinker, Licht, Alarm sind nie ein Befehl. Werden sie gesagt, antwortet Panoptes nur „geht
 *       per Sprache nicht“ ({@link #VERBOTEN}).</li>
 * </ul>
 */
public final class SprachBefehle {

    private SprachBefehle() {}

    public static final String SCHALTER = "sprache.befehle";
    public static final String SCHALTER_WECKWORT = "sprache.weckwort";
    public static final String ABSCHNITT = "sprache";

    /** Sprachen mit Sätzen (Oberfläche 5 Sprachen). */
    public static final List<String> SPRACHEN = liste("de", "en", "fr", "it", "nl");
    /** Sprachen mit echter Erkennung (Modell mit Apache-2.0 belegt). */
    public static final List<String> ERKENNUNG = liste("de", "en");
    /** Modellordner je Sprache unter {@code getExternalFilesDir("sprache")}. */
    public static final Map<String, String> MODELL;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("de", "vosk-model-small-de-0.15");
        m.put("en", "vosk-model-small-en-us-0.15");
        MODELL = Collections.unmodifiableMap(m);
    }

    // ---- Befehle
    public static final String KLIMA_AN = "klima_an";
    public static final String KLIMA_AUS = "klima_aus";
    public static final String WAERMER = "waermer";
    public static final String KAELTER = "kaelter";
    public static final String NAVI_HEIM = "navi_heim";
    public static final String MUSIK_WEITER = "musik_weiter";
    public static final String MUSIK_ZURUECK = "musik_zurueck";
    public static final String MUSIK_PAUSE = "musik_pause";
    public static final String WO_BIN_ICH = "wo_bin_ich";
    public static final String REICHWEITE = "reichweite";
    public static final String JA = "ja";
    public static final String NEIN = "nein";
    /** Regel A+: gesagt, aber nie ausgeführt. */
    public static final String VERBOTEN = "verboten";

    /** Alle Befehle mit Wirkung. */
    public static final List<String> BEFEHLE = liste(KLIMA_AN, KLIMA_AUS, WAERMER, KAELTER, NAVI_HEIM,
            MUSIK_WEITER, MUSIK_ZURUECK, MUSIK_PAUSE, WO_BIN_ICH, REICHWEITE);
    /** Fahrzeugbefehle: nur nach Rückfrage, im Kern nur mit gültiger Marke. */
    public static final List<String> FAHRZEUG = liste(KLIMA_AN, KLIMA_AUS, WAERMER, KAELTER);
    /** Befehle, die der Kern ausführt bzw. beantwortet ({@code POST /api/sprache/v1/befehl}); „nach Hause“ öffnet die App selbst. */
    public static final List<String> KERN = liste(KLIMA_AN, KLIMA_AUS, WAERMER, KAELTER, MUSIK_WEITER, MUSIK_ZURUECK,
            MUSIK_PAUSE, WO_BIN_ICH, REICHWEITE);
    /** Kern-Befehl → Klima-Aktion des Stufen-Modells (gleiche Regeln wie die Launcher-Klima-Kachel). */
    public static final Map<String, String> KLIMA_AKTION;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(KLIMA_AN, "an");
        m.put(KLIMA_AUS, "aus");
        m.put(WAERMER, "temp_hoch");
        m.put(KAELTER, "temp_runter");
        KLIMA_AKTION = Collections.unmodifiableMap(m);
    }
    /** Kern-Befehl → Medientaste. */
    public static final Map<String, String> MEDIENTASTE;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(MUSIK_WEITER, "next");
        m.put(MUSIK_ZURUECK, "previous");
        m.put(MUSIK_PAUSE, "pause");
        MEDIENTASTE = Collections.unmodifiableMap(m);
    }

    /** Weckwort je Sprache (Stufe 1 hat „hallo auto“ gemessen). */
    public static final Map<String, String> WECKWORT;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("de", "hallo auto");
        m.put("en", "hello car");
        WECKWORT = Collections.unmodifiableMap(m);
    }

    /** Sätze je Sprache und Befehl (klein, ohne Satzzeichen; so liefert sie die Vosk-Grammatik zurück). */
    static final Map<String, Map<String, List<String>>> SAETZE = new LinkedHashMap<>();
    static {
        Map<String, List<String>> de = new LinkedHashMap<>();
        de.put(KLIMA_AN, liste("klima an", "klima einschalten", "klimaanlage an", "klimaanlage einschalten"));
        de.put(KLIMA_AUS, liste("klima aus", "klima ausschalten", "klimaanlage aus", "klimaanlage ausschalten"));
        de.put(WAERMER, liste("wärmer", "etwas wärmer", "mach es wärmer"));
        de.put(KAELTER, liste("kälter", "etwas kälter", "kühler", "mach es kühler"));
        de.put(NAVI_HEIM, liste("navi nach hause", "nach hause", "fahr nach hause"));
        de.put(MUSIK_WEITER, liste("nächstes lied", "musik weiter", "nächster titel"));
        de.put(MUSIK_ZURUECK, liste("voriges lied", "vorheriges lied", "musik zurück"));
        de.put(MUSIK_PAUSE, liste("pause", "musik pause", "musik stopp"));
        de.put(WO_BIN_ICH, liste("wo bin ich"));
        de.put(REICHWEITE, liste("reichweite", "wie weit komme ich"));
        de.put(JA, liste("ja", "ja bitte", "genau"));
        de.put(NEIN, liste("nein", "abbrechen", "nein danke"));
        de.put(VERBOTEN, liste("hupe", "hupen", "blinker", "licht", "licht an", "fernlicht", "alarm", "warnblinker"));
        SAETZE.put("de", de);
        Map<String, List<String>> en = new LinkedHashMap<>();
        en.put(KLIMA_AN, liste("climate on", "air conditioning on", "turn on the air conditioning"));
        en.put(KLIMA_AUS, liste("climate off", "air conditioning off", "turn off the air conditioning"));
        en.put(WAERMER, liste("warmer", "a bit warmer", "make it warmer"));
        en.put(KAELTER, liste("colder", "cooler", "a bit cooler", "make it cooler"));
        en.put(NAVI_HEIM, liste("navigate home", "take me home", "drive home"));
        en.put(MUSIK_WEITER, liste("next song", "next track", "skip"));
        en.put(MUSIK_ZURUECK, liste("previous song", "previous track", "go back"));
        en.put(MUSIK_PAUSE, liste("pause", "pause music", "stop music"));
        en.put(WO_BIN_ICH, liste("where am i"));
        en.put(REICHWEITE, liste("range", "how far can i go"));
        en.put(JA, liste("yes", "yes please", "okay"));
        en.put(NEIN, liste("no", "cancel", "no thanks"));
        en.put(VERBOTEN, liste("horn", "honk", "indicator", "lights", "headlights", "alarm", "hazard lights"));
        SAETZE.put("en", en);
        // Noch ohne Erkennung (kein Modell mit belegter Lizenz im Paket): Sätze stehen bereit, die Oberfläche sagt „noch nicht“.
        Map<String, List<String>> fr = new LinkedHashMap<>();
        fr.put(KLIMA_AN, liste("allume la clim"));
        fr.put(KLIMA_AUS, liste("éteins la clim"));
        fr.put(WAERMER, liste("plus chaud"));
        fr.put(KAELTER, liste("plus frais"));
        fr.put(NAVI_HEIM, liste("rentrer à la maison"));
        fr.put(MUSIK_WEITER, liste("chanson suivante"));
        fr.put(MUSIK_ZURUECK, liste("chanson précédente"));
        fr.put(MUSIK_PAUSE, liste("pause"));
        fr.put(WO_BIN_ICH, liste("où suis je"));
        fr.put(REICHWEITE, liste("autonomie"));
        fr.put(JA, liste("oui"));
        fr.put(NEIN, liste("non", "annuler"));
        fr.put(VERBOTEN, liste("klaxon", "clignotant", "phares", "alarme"));
        SAETZE.put("fr", fr);
        Map<String, List<String>> it = new LinkedHashMap<>();
        it.put(KLIMA_AN, liste("accendi il clima"));
        it.put(KLIMA_AUS, liste("spegni il clima"));
        it.put(WAERMER, liste("più caldo"));
        it.put(KAELTER, liste("più fresco"));
        it.put(NAVI_HEIM, liste("portami a casa"));
        it.put(MUSIK_WEITER, liste("brano successivo"));
        it.put(MUSIK_ZURUECK, liste("brano precedente"));
        it.put(MUSIK_PAUSE, liste("pausa"));
        it.put(WO_BIN_ICH, liste("dove sono"));
        it.put(REICHWEITE, liste("autonomia"));
        it.put(JA, liste("sì"));
        it.put(NEIN, liste("no", "annulla"));
        it.put(VERBOTEN, liste("clacson", "freccia", "luci", "allarme"));
        SAETZE.put("it", it);
        Map<String, List<String>> nl = new LinkedHashMap<>();
        nl.put(KLIMA_AN, liste("airco aan"));
        nl.put(KLIMA_AUS, liste("airco uit"));
        nl.put(WAERMER, liste("warmer"));
        nl.put(KAELTER, liste("koeler"));
        nl.put(NAVI_HEIM, liste("naar huis"));
        nl.put(MUSIK_WEITER, liste("volgend nummer"));
        nl.put(MUSIK_ZURUECK, liste("vorig nummer"));
        nl.put(MUSIK_PAUSE, liste("pauze"));
        nl.put(WO_BIN_ICH, liste("waar ben ik"));
        nl.put(REICHWEITE, liste("actieradius"));
        nl.put(JA, liste("ja"));
        nl.put(NEIN, liste("nee", "annuleren"));
        nl.put(VERBOTEN, liste("toeter", "knipperlicht", "lichten", "alarm"));
        SAETZE.put("nl", nl);
    }

    private static List<String> liste(String... s) { return Collections.unmodifiableList(Arrays.asList(s)); }

    /** Sprache auf eine der fünf abbilden; Unbekanntes = Englisch (wie die Oberfläche). */
    public static String sprache(String code) {
        String s = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
        if (s.length() > 2) s = s.substring(0, 2);
        return SPRACHEN.contains(s) ? s : "en";
    }

    // ---- 0.1.43.3 (Rang 7) „Was kann ich sagen?“ im Launcher: Kategorien und ehrliche Art je Befehl

    /** Kategorie je Befehl (nur Gruppen, die es wirklich gibt). */
    public static final Map<String, String> KATEGORIE;
    /** Art je Befehl: lesen (nur lesen), rueckfrage (Fahrzeugbefehl, immer mit Ja/Nein), vorschau (Navi), medien. */
    public static final Map<String, String> ART;
    public static final List<String> KATEGORIEN = liste("klima", "navigation", "medien", "allgemein");
    static {
        Map<String, String> k = new LinkedHashMap<>(), a = new LinkedHashMap<>();
        for (String b : FAHRZEUG) { k.put(b, "klima"); a.put(b, "rueckfrage"); }
        k.put(NAVI_HEIM, "navigation"); a.put(NAVI_HEIM, "vorschau");
        for (String b : new String[] {MUSIK_WEITER, MUSIK_ZURUECK, MUSIK_PAUSE}) { k.put(b, "medien"); a.put(b, "medien"); }
        for (String b : new String[] {WO_BIN_ICH, REICHWEITE}) { k.put(b, "allgemein"); a.put(b, "lesen"); }
        KATEGORIE = Collections.unmodifiableMap(k);
        ART = Collections.unmodifiableMap(a);
    }

    /**
     * Katalog für „Was kann ich sagen?“: je Befehl Kategorie, Art und die Sätze der Sprache (eine Quelle mit der
     * Erkennung). {@code nie}: Beispiele, die per Sprache nie gehen (Regel A+). Nur lesen, keine Wirkung.
     */
    public static JSONObject katalog(String spracheCode) {
        String sp = sprache(spracheCode);
        JSONObject o = new JSONObject();
        try {
            JSONArray bef = new JSONArray();
            for (String b : BEFEHLE) {
                JSONArray s = new JSONArray();
                for (String x : saetze(sp, b)) s.put(x);
                bef.put(new JSONObject().put("befehl", b).put("kategorie", KATEGORIE.get(b)).put("art", ART.get(b)).put("saetze", s));
            }
            JSONArray nie = new JSONArray();
            for (String x : saetze(sp, VERBOTEN)) nie.put(x);
            o.put("sprache", sp).put("erkennbar", erkennbar(sp)).put("befehle", bef).put("nie", nie);
        } catch (Exception ignored) {
            // nur Zeichenketten und Listen – kann nicht fehlschlagen
        }
        return o;
    }

    /** Gibt es in dieser Sprache eine echte Erkennung? */
    public static boolean erkennbar(String sprache) { return ERKENNUNG.contains(sprache(sprache)); }

    public static boolean brauchtRueckfrage(String befehl) { return FAHRZEUG.contains(befehl); }

    /** Sätze einer Sprache für einen Befehl (für Hilfe-Anzeige und Tests). */
    public static List<String> saetze(String sprache, String befehl) {
        Map<String, List<String>> m = SAETZE.get(sprache(sprache));
        List<String> l = m == null ? null : m.get(befehl);
        return l == null ? Collections.<String>emptyList() : l;
    }

    /**
     * Vosk-Grammatik (JSON-Liste) einer Sprache. {@code nurJaNein}: für die Rückfrage nur Ja/Nein. Alles andere hört
     * Vosk als {@code [unk]} – freie Sätze werden nie zu einem Befehl.
     */
    public static String grammatik(String sprache, boolean nurJaNein) {
        JSONArray a = new JSONArray();
        Map<String, List<String>> m = SAETZE.get(sprache(sprache));
        if (m != null) {
            for (Map.Entry<String, List<String>> e : m.entrySet()) {
                boolean jn = JA.equals(e.getKey()) || NEIN.equals(e.getKey());
                if (nurJaNein && !jn) continue;
                for (String s : e.getValue()) a.put(s);
            }
        }
        a.put("[unk]");
        return a.toString();
    }

    /** Grammatik nur für das Weckwort. */
    public static String weckGrammatik(String sprache) {
        String w = WECKWORT.get(sprache(sprache));
        JSONArray a = new JSONArray();
        if (w != null) a.put(w);
        a.put("[unk]");
        return a.toString();
    }

    /** Text aus einem Vosk-Ergebnis ({@code {"text":"..."}}); leer bei allem Unklaren. */
    public static String text(String voskJson) {
        if (voskJson == null || voskJson.isEmpty()) return "";
        try {
            return norm(new JSONObject(voskJson).optString("text", ""));
        } catch (Exception e) {
            return "";
        }
    }

    static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replace("[unk]", " ").replaceAll("[^\\p{L}\\p{N} ]", " ")
                .replaceAll("\\s+", " ").trim();
    }

    /**
     * Befehl zu einem erkannten Text, oder null. Nur ganze Sätze aus {@link #SAETZE} zählen (genau gleich nach dem
     * Normalisieren). In Sprachen ohne Erkennung immer null.
     */
    public static String befehl(String sprache, String text) {
        String sp = sprache(sprache);
        if (!erkennbar(sp)) return null;
        String t = norm(text);
        if (t.isEmpty()) return null;
        Map<String, List<String>> m = SAETZE.get(sp);
        for (Map.Entry<String, List<String>> e : m.entrySet()) {
            for (String s : e.getValue()) if (t.equals(norm(s))) return e.getKey();
        }
        return null;
    }

    /** Ist das Weckwort gefallen? */
    public static boolean istWeckwort(String sprache, String voskJson) {
        String w = WECKWORT.get(sprache(sprache));
        return w != null && text(voskJson).equals(w);
    }
}
