package at.greenchili.surfmate.tacho;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 0.1.44 (E1) Freier Tacho-Editor: Format {@code panoptes-tacho-design} Version {@link #VERSION}. Statt fester Plaetze
 * liegen Elemente frei auf einer Flaeche im Format des Fahrerdisplays ({@link #BREITE} x {@link #HOEHE} Einheiten).
 * Version 1 ({@link TachoEigen}) bleibt ladbar und wird weiter gezeichnet.
 *
 * <p>Gleiche Regeln wie die Oberflaeche ({@code PanoptesTacho.pruefeFrei} in {@code web/shared/tacho-designs.js}),
 * Vertragstest {@code TachoFreiTest}:
 * <ul>
 *   <li>Nur Schluessel und Werte aus festen Erlaubnislisten, Farben nur {@code #RRGGBB}, Zahlen begrenzt. Unbekanntes faellt weg.</li>
 *   <li>Kein Element ausserhalb des Bildes (Lage und Groesse werden eingepasst).</li>
 *   <li>Geschwindigkeit ist Pflicht, genau einmal (Rundinstrument oder Zahl) und mindestens {@link #TEMPO_MIN_HOEHE} hoch.</li>
 *   <li>Jedes Element mit Information (Werte, Gang, Uhr, Navi) hat mindestens {@link TachoEigen#MIN_KONTRAST}:1 Kontrast
 *       zur Flaeche dahinter (Hintergrund oder deckende Form). Sonst wird es weiss oder schwarz.</li>
 *   <li>Nichts blinkt: das Format kennt keine Zeit-, Takt- oder Animationsfelder.</li>
 *   <li>Eigenes Hintergrundbild nur als kleine Bilddaten (JPEG/PNG, hoechstens {@link #MAX_BILD} Zeichen), nie als Pfad.
 *       Es liegt immer unter einem Schleier ({@link #SCHLEIER}) der Hintergrundfarbe; geprueft wird gegen den hellsten und
 *       den dunkelsten moeglichen Bildpunkt. Reicht der Kontrast so nicht, faellt das Bild weg.</li>
 * </ul>
 */
public final class TachoFrei {

    public static final int VERSION = 2;
    public static final int BREITE = 1000;
    public static final int HOEHE = 450;
    public static final int MIN_GROESSE = 4;
    public static final int MAX_ELEMENTE = 40;
    public static final int MAX_BILD = 24000;
    /** Anteil der Hintergrundfarbe ueber einem eigenen Bild. */
    public static final double SCHLEIER = 0.7;
    /** Tempo: Mindesthoehe und -breite (Einheiten der Flaeche). */
    public static final int TEMPO_MIN_HOEHE = 90;
    public static final int TEMPO_MIN_BREITE = 150;

    public static final List<String> TYPEN = liste("rund", "zahl", "balken", "gang", "navi", "uhr", "ring", "linie", "flaeche", "verlauf");
    /** Formen ohne Information (Schmuck); deckend sind nur flaeche und verlauf. */
    public static final List<String> FORMEN = liste("ring", "linie", "flaeche", "verlauf");
    public static final List<String> RUND_WERTE = liste("tempo", "leistung", "akku");
    public static final List<String> ZAHL_WERTE = liste("tempo", "leistung", "akku", "reichweite", "aussen", "kmstand");
    public static final List<String> BALKEN_WERTE = liste("leistung", "akku");
    public static final List<String> ZEIGERFORMEN = liste("nadel", "balken", "ring");
    public static final List<String> RICHTUNGEN = liste("waagrecht", "senkrecht");
    public static final List<String> AUSRICHTUNGEN = liste("links", "mitte", "rechts");
    public static final List<String> HG_ARTEN = liste("farbe", "verlauf");

    private static final Pattern FARBE = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    private static final Pattern BILD = Pattern.compile("^data:image/(jpeg|png);base64,[A-Za-z0-9+/]+={0,2}$");

    private TachoFrei() {}

    private static List<String> liste(String... s) { return Collections.unmodifiableList(Arrays.asList(s)); }

    /** Standard-Skala je Wert (min, max). */
    static int[] skala(String wert) {
        if ("tempo".equals(wert)) return new int[] {0, 200};
        if ("leistung".equals(wert)) return new int[] {-50, 150};
        return new int[] {0, 100};
    }

    /** Ein Element mit Information (wird auf Kontrast geprueft). */
    static boolean info(String typ) { return !FORMEN.contains(typ); }

    /** Pruefen wie {@link TachoEigen#pruefen}, fuer Version 2. */
    static TachoEigen.Ergebnis pruefen(JSONObject roh) {
        List<String> warn = new ArrayList<>();
        try {
            String basis = wahl(roh.optString("basis", ""), TachoAnsicht.DESIGNS, "klar");
            JSONObject rh = roh.optJSONObject("hintergrund");
            JSONObject hg = new JSONObject()
                    .put("art", wahl(rh == null ? "" : rh.optString("art", ""), HG_ARTEN, "farbe"))
                    .put("farbe", farbe(rh, "farbe", "#0E1620", warn))
                    .put("farbe2", farbe(rh, "farbe2", "#05090D", warn))
                    .put("richtung", wahl(rh == null ? "" : rh.optString("richtung", ""), RICHTUNGEN, "senkrecht"));
            String bild = null;
            Object rb = roh.opt("bild");
            if (rb instanceof String && !((String) rb).isEmpty()) {
                if (bildOk((String) rb)) bild = (String) rb; else warn.add("bild_ungueltig");
            }

            JSONArray re = roh.optJSONArray("elemente");
            List<JSONObject> el = new ArrayList<>();
            int n = re == null ? 0 : re.length();
            if (n > MAX_ELEMENTE) warn.add("zu_viele");
            for (int i = 0; i < n && el.size() < MAX_ELEMENTE; i++) {
                JSONObject e = element(re.optJSONObject(i), warn);
                if (e != null) el.add(e);
            }

            // Tempo: genau einmal, als Rundinstrument oder Zahl, gross genug.
            int tempo = -1;
            for (int i = 0; i < el.size(); i++) {
                if (!"tempo".equals(el.get(i).optString("wert"))) continue;
                if (tempo < 0) { tempo = i; continue; }
                el.remove(i--);
                if (!warn.contains("tempo_einmal")) warn.add("tempo_einmal");
            }
            if (tempo < 0) {
                el.add(new JSONObject().put("typ", "zahl").put("wert", "tempo").put("x", 700).put("y", 150)
                        .put("b", 260).put("h", 150).put("farbe", "#FFFFFF").put("schrift", "schmal").put("ausrichtung", "mitte"));
                warn.add("tempo_pflicht");
            } else {
                JSONObject t = el.get(tempo);
                int b = t.getInt("b"), h = t.getInt("h");
                if (h < TEMPO_MIN_HOEHE || b < TEMPO_MIN_BREITE) {
                    int nb = Math.max(b, TEMPO_MIN_BREITE), nh = Math.max(h, TEMPO_MIN_HOEHE);
                    int cx = t.getInt("x") + b / 2, cy = t.getInt("y") + h / 2;
                    t.put("b", nb).put("h", nh);
                    t.put("x", klemme(cx - nb / 2, 0, BREITE - nb)).put("y", klemme(cy - nh / 2, 0, HOEHE - nh));
                    warn.add("tempo_groesse");
                }
            }

            // Kontrast je Element zur Flaeche dahinter.
            boolean kontrastWarn = false;
            for (int runde = 0; runde < 2; runde++) {
                boolean bildWeg = false;
                for (int i = 0; i < el.size(); i++) {
                    JSONObject e = el.get(i);
                    String typ = e.getString("typ");
                    if (!info(typ)) continue;
                    String feld = "balken".equals(typ) ? "akzent" : "farbe";
                    List<String> hinten = dahinter(el, i, hg, bild != null);
                    if (minKontrast(e.getString(feld), hinten) >= TachoEigen.MIN_KONTRAST) continue;
                    String w = "#FFFFFF", s = "#000000";
                    String best = minKontrast(w, hinten) >= minKontrast(s, hinten) ? w : s;
                    if (minKontrast(best, hinten) < TachoEigen.MIN_KONTRAST) { bildWeg = true; break; }
                    e.put(feld, best);
                    kontrastWarn = true;
                }
                if (!bildWeg) break;
                bild = null;
                warn.add("bild_kontrast");
            }
            if (kontrastWarn) warn.add("kontrast");

            JSONArray ae = new JSONArray();
            for (JSONObject e : el) ae.put(e);
            JSONObject o = new JSONObject().put("format", TachoEigen.FORMAT).put("version", VERSION).put("basis", basis)
                    .put("hintergrund", hg);
            if (bild != null) o.put("bild", bild);
            o.put("elemente", ae);
            return new TachoEigen.Ergebnis(o, warn);
        } catch (Exception e) {
            return null;
        }
    }

    /** Ein Element nach der Erlaubnisliste seines Typs; unbekannter Typ oder Wert = null. */
    static JSONObject element(JSONObject r, List<String> warn) throws Exception {
        if (r == null) return null;
        String typ = wahl(r.optString("typ", ""), TYPEN, "");
        if (typ.isEmpty()) return null;
        JSONObject e = new JSONObject().put("typ", typ);
        if ("rund".equals(typ) || "zahl".equals(typ) || "balken".equals(typ)) {
            List<String> werte = "rund".equals(typ) ? RUND_WERTE : "zahl".equals(typ) ? ZAHL_WERTE : BALKEN_WERTE;
            String w = wahl(r.optString("wert", ""), werte, "");
            if (w.isEmpty()) return null;
            e.put("wert", w);
        }
        int b = klemme(ganz(r, "b", 200), MIN_GROESSE, BREITE);
        int h = klemme(ganz(r, "h", 120), MIN_GROESSE, HOEHE);
        e.put("x", klemme(ganz(r, "x", 0), 0, BREITE - b)).put("y", klemme(ganz(r, "y", 0), 0, HOEHE - h)).put("b", b).put("h", h);
        e.put("farbe", farbe(r, "farbe", "#FFFFFF", warn));
        switch (typ) {
            case "rund":
                e.put("form", wahl(r.optString("form", ""), ZEIGERFORMEN, "ring"));
                skalaPruefen(r, e);
                e.put("drehung", klemme(ganz(r, "drehung", 0), -180, 180));
                e.put("akzent", farbe(r, "akzent", "#B5F23D", warn));
                e.put("schrift", wahl(r.optString("schrift", ""), TachoEigen.SCHRIFTEN, "schmal"));
                break;
            case "balken":
                e.put("richtung", wahl(r.optString("richtung", ""), RICHTUNGEN, "waagrecht"));
                skalaPruefen(r, e);
                e.put("akzent", farbe(r, "akzent", "#B5F23D", warn));
                break;
            case "zahl":
            case "gang":
            case "uhr":
                e.put("schrift", wahl(r.optString("schrift", ""), TachoEigen.SCHRIFTEN, "schmal"));
                e.put("ausrichtung", wahl(r.optString("ausrichtung", ""), AUSRICHTUNGEN, "mitte"));
                break;
            case "navi":
                e.put("schrift", wahl(r.optString("schrift", ""), TachoEigen.SCHRIFTEN, "schmal"));
                break;
            case "ring":
            case "linie":
                e.put("dicke", klemme(ganz(r, "dicke", 4), 1, 60));
                break;
            case "flaeche":
                e.put("ecken", klemme(ganz(r, "ecken", 0), 0, 100));
                break;
            case "verlauf":
                e.put("farbe2", farbe(r, "farbe2", "#000000", warn));
                e.put("richtung", wahl(r.optString("richtung", ""), RICHTUNGEN, "waagrecht"));
                break;
            default:
                break;
        }
        return e;
    }

    private static void skalaPruefen(JSONObject r, JSONObject e) throws Exception {
        int[] s = skala(e.getString("wert"));
        int min = klemme(ganz(r, "min", s[0]), -1000, 1000), max = klemme(ganz(r, "max", s[1]), -1000, 1000);
        if (max <= min) { min = s[0]; max = s[1]; }
        e.put("min", min).put("max", max);
    }

    /**
     * Farben der Flaeche hinter dem Mittelpunkt von Element i: die oberste deckende Form darunter, sonst der
     * Hintergrund. Mit eigenem Bild: Hintergrundfarbe mit Schleier ueber Schwarz und ueber Weiss (beide Grenzen).
     */
    static List<String> dahinter(List<JSONObject> el, int i, JSONObject hg, boolean bild) throws Exception {
        JSONObject e = el.get(i);
        double cx = e.getInt("x") + e.getInt("b") / 2.0, cy = e.getInt("y") + e.getInt("h") / 2.0;
        for (int j = i - 1; j >= 0; j--) {
            JSONObject f = el.get(j);
            String typ = f.getString("typ");
            if (!"flaeche".equals(typ) && !"verlauf".equals(typ)) continue;
            int x = f.getInt("x"), y = f.getInt("y"), b = f.getInt("b"), h = f.getInt("h");
            if (cx < x || cx > x + b || cy < y || cy > y + h) continue;
            if ("flaeche".equals(typ)) return Collections.singletonList(f.getString("farbe"));
            double t = "senkrecht".equals(f.getString("richtung")) ? (cy - y) / h : (cx - x) / b;
            return Collections.singletonList(mische(f.getString("farbe"), f.getString("farbe2"), t));
        }
        String grund = hg.getString("farbe");
        if ("verlauf".equals(hg.getString("art"))) {
            double t = "waagrecht".equals(hg.getString("richtung")) ? cx / BREITE : cy / HOEHE;
            grund = mische(hg.getString("farbe"), hg.getString("farbe2"), t);
        }
        if (!bild) return Collections.singletonList(grund);
        return Arrays.asList(mische("#000000", grund, SCHLEIER), mische("#FFFFFF", grund, SCHLEIER));
    }

    static double minKontrast(String f, List<String> hinten) {
        double m = 21;
        for (String h : hinten) m = Math.min(m, TachoEigen.kontrast(f, h));
        return m;
    }

    /** Lineare Mischung von a nach b (t = 0..1), gerundet wie in der Oberflaeche. */
    static String mische(String a, String b, double t) {
        t = Math.max(0, Math.min(1, t));
        int x = Integer.parseInt(a.substring(1), 16), y = Integer.parseInt(b.substring(1), 16);
        StringBuilder s = new StringBuilder("#");
        for (int sh = 16; sh >= 0; sh -= 8) {
            int ka = (x >> sh) & 255, kb = (y >> sh) & 255;
            s.append(String.format(Locale.ROOT, "%02X", (int) Math.floor(ka + (kb - ka) * t + 0.5)));
        }
        return s.toString();
    }

    /** Nur kleine Bilddaten JPEG/PNG mit passender Kennung, nie ein Pfad oder eine Adresse. */
    static boolean bildOk(String s) {
        if (s == null || s.length() > MAX_BILD || !BILD.matcher(s).matches()) return false;
        try {
            byte[] d = java.util.Base64.getDecoder().decode(s.substring(s.indexOf(',') + 1));
            if (s.startsWith("data:image/jpeg"))
                return d.length > 3 && (d[0] & 255) == 0xFF && (d[1] & 255) == 0xD8 && (d[2] & 255) == 0xFF;
            return d.length > 8 && (d[0] & 255) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G';
        } catch (IllegalArgumentException x) {
            return false;
        }
    }

    private static String farbe(JSONObject r, String k, String sonst, List<String> warn) {
        String v = r == null ? "" : r.optString(k, "");
        if (FARBE.matcher(v).matches()) return v.toUpperCase(Locale.ROOT);
        if (r != null && r.has(k) && !warn.contains("farbe_ungueltig")) warn.add("farbe_ungueltig");
        return sonst;
    }

    private static int ganz(JSONObject r, String k, int sonst) {
        Object v = r.opt(k);
        if (!(v instanceof Number)) return sonst;
        double d = ((Number) v).doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) return sonst;
        return (int) Math.max(-100000, Math.min(100000, Math.round(d)));
    }

    static int klemme(int v, int min, int max) { return v < min ? min : v > max ? max : v; }

    private static String wahl(String v, List<String> erlaubt, String sonst) {
        String s = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
        return erlaubt.contains(s) ? s : sonst;
    }
}
