package at.greenchili.surfmate.tacho;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 0.1.41 „Tacho selbst gestalten“: eigenes Tacho-Design aus Farben, Schrift, Zeigerform und fester Anordnung
 * (Raster mit festen Plaetzen, kein freies Verschieben). Gespeichert im Kern, gezeichnet vom selben Renderer
 * {@code web/shared/tacho-designs.js} (Design {@link #NAME}).
 *
 * <p>Jede Eingabe (Speichern aus der Oberflaeche und Datei-Import) geht durch {@link #pruefen}: nur Schluessel und
 * Werte aus festen Erlaubnislisten, Farben nur als {@code #RRGGBB}. Unbekanntes faellt weg. Es gibt keine Pfade,
 * Orte oder Zugangsdaten im Format.
 *
 * <p>Sicherheit: Die Geschwindigkeit ist Pflicht und steht immer auf einem grossen Platz. Ziffern (Tempo und Gang)
 * brauchen mindestens {@link #MIN_KONTRAST}:1 Kontrast zum Hintergrund, sonst gelten die Standardfarben und es
 * gibt die Warnung {@code kontrast}. Speichern geht nur im Stand (TachoApiHandler, Fahrsperre).
 *
 * <p>0.1.44 (E1): Version 2 = freier Editor ({@link TachoFrei}). Beide Versionen sind ladbar; {@link #pruefen}
 * waehlt die Regeln nach {@code version}. {@link #standard()} bleibt Version 1.
 */
public final class TachoEigen {

    public static final String NAME = "eigen";
    public static final String FORMAT = "panoptes-tacho-design";
    public static final int VERSION = 1;
    static final String KEY = "ansichtEigen";
    /**
     * Groesste Datei in Zeichen. 0.1.44 (E1): 40000 statt 4096 – Version 2 hat bis zu {@link TachoFrei#MAX_ELEMENTE}
     * Elemente und ein kleines Hintergrundbild ({@link TachoFrei#MAX_BILD}).
     */
    public static final int MAX_ZEICHEN = 40000;
    /** WCAG-Kontrast fuer normalen Text. Tempo und Gang muessen das mindestens erreichen. */
    public static final double MIN_KONTRAST = 4.5;

    public static final List<String> FARBEN = Collections.unmodifiableList(
            Arrays.asList("hintergrund", "ziffern", "zeiger", "akzent"));
    public static final List<String> SCHRIFTEN = Collections.unmodifiableList(
            Arrays.asList("schmal", "normal", "breit"));
    public static final List<String> ZEIGER = Collections.unmodifiableList(
            Arrays.asList("nadel", "balken", "zahl"));
    /** Grosse Plaetze (Instrumente) und kleine Plaetze (Zeilen oben und unten). */
    public static final List<String> GROSS = Collections.unmodifiableList(
            Arrays.asList("links", "mitte", "rechts"));
    public static final List<String> KLEIN = Collections.unmodifiableList(
            Arrays.asList("oben_links", "oben_mitte", "oben_rechts", "unten_links", "unten_mitte", "unten_rechts"));
    /** Nur Anzeigen mit vorhandener Datenquelle (TachoAnsicht.antwort, Uhr am Geraet, Panoptes-Navigation). */
    public static final List<String> ANZEIGEN = Collections.unmodifiableList(
            Arrays.asList("tempo", "akku", "reichweite", "leistung", "gang", "uhr", "aussen", "navi", "kmstand", "leer"));
    /** Nur auf grossen Plaetzen: Tempo (Pflicht, gut sichtbar) und Navi (braucht Platz fuer den Pfeil). */
    static final List<String> NUR_GROSS = Collections.unmodifiableList(Arrays.asList("tempo", "navi"));

    private static final Pattern FARBE = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    private TachoEigen() {}

    /** Standard (wie Design „klar“): Ziel bei zu wenig Kontrast und Ausgangspunkt ohne gespeichertes Design. */
    public static JSONObject standard() {
        try {
            JSONObject f = new JSONObject().put("hintergrund", "#0E1620").put("ziffern", "#EEF4F8")
                    .put("zeiger", "#B5F23D").put("akzent", "#7FE3C4");
            return new JSONObject().put("format", FORMAT).put("version", VERSION).put("basis", "klar")
                    .put("farben", f).put("schrift", "schmal").put("zeiger", "balken").put("plaetze", standardPlaetze());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static JSONObject standardPlaetze() throws Exception {
        return new JSONObject().put("links", "leistung").put("mitte", "navi").put("rechts", "tempo")
                .put("oben_links", "uhr").put("oben_mitte", "gang").put("oben_rechts", "aussen")
                .put("unten_links", "akku").put("unten_mitte", "reichweite").put("unten_rechts", "kmstand");
    }

    /** Ergebnis von {@link #pruefen}: das bereinigte Design und Warnungen (z. B. kontrast, tempo_pflicht). */
    public static final class Ergebnis {
        public final JSONObject eigen;
        public final List<String> warnungen;

        Ergebnis(JSONObject eigen, java.util.List<String> warnungen) {
            this.eigen = eigen;
            this.warnungen = Collections.unmodifiableList(warnungen);
        }
    }

    /**
     * Prueft ein Design gegen die festen Erlaubnislisten. null = kein Design-Objekt oder falsches Format
     * (dann wird nichts gespeichert). Sonst immer ein vollstaendiges, sicheres Design.
     */
    public static Ergebnis pruefen(JSONObject roh) {
        if (roh == null) return null;
        String format = roh.optString("format", FORMAT);
        if (!FORMAT.equals(format)) return null;
        int version = roh.optInt("version", VERSION);
        if (version == TachoFrei.VERSION) return TachoFrei.pruefen(roh);
        if (version != VERSION) return null;
        List<String> warn = new java.util.ArrayList<>();
        try {
            JSONObject std = standard();
            String basis = wahl(roh.optString("basis", ""), TachoAnsicht.DESIGNS, "klar");

            JSONObject f = new JSONObject();
            JSONObject rf = roh.optJSONObject("farben");
            JSONObject sf = std.getJSONObject("farben");
            boolean farbeFalsch = false;
            for (String k : FARBEN) {
                String v = rf == null ? "" : rf.optString(k, "");
                if (FARBE.matcher(v).matches()) f.put(k, v.toUpperCase(Locale.ROOT));
                else { f.put(k, sf.getString(k)); if (rf != null && rf.has(k)) farbeFalsch = true; }
            }
            if (farbeFalsch) warn.add("farbe_ungueltig");
            if (kontrast(f.getString("ziffern"), f.getString("hintergrund")) < MIN_KONTRAST) {
                f = new JSONObject(sf.toString());
                warn.add("kontrast");
            }

            String schrift = wahl(roh.optString("schrift", ""), SCHRIFTEN, std.getString("schrift"));
            String zeiger = wahl(roh.optString("zeiger", ""), ZEIGER, std.getString("zeiger"));

            JSONObject rp = roh.optJSONObject("plaetze");
            Map<String, String> p = new LinkedHashMap<>();
            java.util.Set<String> belegt = new java.util.HashSet<>();
            for (String platz : alle()) {
                String a = rp == null ? "leer" : rp.optString(platz, "leer").trim().toLowerCase(Locale.ROOT);
                if (!ANZEIGEN.contains(a)) a = "leer";
                if (KLEIN.contains(platz) && NUR_GROSS.contains(a)) a = "leer";
                if (!"leer".equals(a) && !belegt.add(a)) a = "leer";   // jede Anzeige hoechstens einmal
                p.put(platz, a);
            }
            if (!belegt.contains("tempo")) {
                // Pflichtfeld: Tempo kommt auf seinen Standardplatz, das bisherige dort weicht.
                p.put("rechts", "tempo");
                warn.add("tempo_pflicht");
            }
            JSONObject plaetze = new JSONObject();
            for (Map.Entry<String, String> e : p.entrySet()) plaetze.put(e.getKey(), e.getValue());

            JSONObject o = new JSONObject().put("format", FORMAT).put("version", VERSION).put("basis", basis)
                    .put("farben", f).put("schrift", schrift).put("zeiger", zeiger).put("plaetze", plaetze);
            return new Ergebnis(o, warn);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Import aus Dateitext: zu gross, kein JSON oder keine echte Tacho-Design-Datei = null.
     * 0.1.42 (E2E G3): Eine Datei muss Kennung {@link #FORMAT} und {@link #VERSION} ausdruecklich tragen;
     * fremdes JSON wie {@code {"foo":1}} wird nicht mehr still zu einem leeren Design.
     */
    public static Ergebnis pruefenText(String text) {
        if (text == null || text.length() > MAX_ZEICHEN) return null;
        try {
            JSONObject roh = new JSONObject(text.trim());
            if (!roh.has("format") || !roh.has("version")) return null;
            Object v = roh.opt("version");
            double nr = v instanceof Number ? ((Number) v).doubleValue() : -1;
            if (!FORMAT.equals(roh.opt("format")) || (nr != VERSION && nr != TachoFrei.VERSION)) return null;
            return pruefen(roh);
        } catch (Exception e) {
            return null;
        }
    }

    static List<String> alle() {
        List<String> l = new java.util.ArrayList<>(GROSS);
        l.addAll(KLEIN);
        return l;
    }

    private static String wahl(String v, List<String> erlaubt, String sonst) {
        String s = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
        return erlaubt.contains(s) ? s : sonst;
    }

    /** WCAG-2-Kontrastverhaeltnis zweier Farben #RRGGBB (1..21). Ungueltig = 1. */
    public static double kontrast(String a, String b) {
        if (a == null || b == null || !FARBE.matcher(a).matches() || !FARBE.matcher(b).matches()) return 1;
        double la = helligkeit(a), lb = helligkeit(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    static double helligkeit(String hex) {
        int n = Integer.parseInt(hex.substring(1), 16);
        return 0.2126 * kanal((n >> 16) & 255) + 0.7152 * kanal((n >> 8) & 255) + 0.0722 * kanal(n & 255);
    }

    private static double kanal(int c) {
        double s = c / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    // ------------------------------------------------------------ Speicher (Kern-Konfiguration)

    private static String letzterText = null;
    private static JSONObject letztesDesign = null;

    /**
     * Gespeichertes Design (geprueft) oder null, wenn keines gespeichert ist. 0.1.44 (E1): Die Pruefung laeuft nur,
     * wenn sich der gespeicherte Text geaendert hat (die Tacho-Ansicht fragt 4x pro Sekunde).
     */
    public static synchronized JSONObject gespeichert() {
        try {
            String s = TachoNaviApp.sektion().optString(KEY, "");
            if (s.isEmpty()) return null;
            if (!s.equals(letzterText)) {
                Ergebnis e = pruefenText(s);
                letztesDesign = e == null ? null : e.eigen;
                letzterText = s;
            }
            return letztesDesign == null ? null : new JSONObject(letztesDesign.toString());
        } catch (Throwable t) {
            return null;
        }
    }

    /** 0.1.44 (E1): Kennung des gespeicherten Designs; die Ansicht holt das Design nur, wenn sie sich aendert. */
    public static String stand(JSONObject eigen) {
        return eigen == null ? "" : Integer.toHexString(eigen.toString().hashCode());
    }

    public static boolean speichern(JSONObject eigen) {
        Map<String, Object> w = new java.util.HashMap<>();
        w.put(KEY, eigen == null ? "" : eigen.toString());
        try {
            return at.greenchili.surfmate.config.UnifiedConfigManager.updateValues(TachoNaviApp.SEKTION, w);
        } catch (Throwable t) {
            return false;
        }
    }
}
