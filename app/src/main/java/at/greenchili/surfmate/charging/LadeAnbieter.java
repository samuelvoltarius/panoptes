package at.greenchili.surfmate.charging;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Eigene Ladeanbieter (Wunsch Projektinhaber 18.11.2026, 0.1.43.15): in der Ladenachfrage „Wo hast du geladen?“ einen
 * Anbieter wählen oder gleich neu anlegen. Rein, ohne Datenbank und ohne Netz.
 *
 * <p>Gespeichert im eigenen Config-Abschnitt {@value #ABSCHNITT} ({@code {"liste": [...]}}), getrennt von den
 * Lade-Vorlieben ({@code laden.karten} gehen an den Planer, {@code POST /api/laden/zuruecksetzen} leert „laden“).
 * Eintrag: {@code {id:"a1", name, preisAc|null, preisDc|null, grundgebuehr|null, karte:bool}}.
 *
 * <p>Preise kommen nur vom Nutzer – Panoptes kennt und erfindet keine Anbieterpreise. Die Grundgebühr (je Monat)
 * ist nur eine Notiz; Ladungen werden mit dem Preis je kWh bepreist.
 */
public final class LadeAnbieter {

    private LadeAnbieter() {}

    public static final String ABSCHNITT = "ladeanbieter";
    public static final String LISTE = "liste";
    public static final int MAX = 30;
    public static final int MAX_NAME = 60;
    /** Obergrenze Preis je kWh (wie {@code laden.acStandard}); PreisPlausibel warnt schon viel früher. */
    public static final double MAX_PREIS = 100.0;
    public static final double MAX_GRUNDGEBUEHR = 1000.0;

    /** Gespeicherte Anbieter (bereinigt; Unbrauchbares fällt weg). Nie null. */
    public static JSONArray liste(JSONObject cfg) {
        JSONObject a = cfg == null ? null : cfg.optJSONObject(ABSCHNITT);
        JSONArray roh = a == null ? null : a.optJSONArray(LISTE);
        JSONArray out = new JSONArray();
        for (int i = 0; roh != null && i < roh.length() && out.length() < MAX; i++) {
            JSONObject e = roh.optJSONObject(i);
            if (e == null) continue;
            String id = e.optString("id", "").trim();
            String name = name(e.opt("name"));
            if (id.isEmpty() || name == null) continue;
            try {
                JSONObject n = new JSONObject();
                n.put("id", id);
                n.put("name", name);
                n.put("preisAc", zahlOderNull(e.opt("preisAc"), MAX_PREIS));
                n.put("preisDc", zahlOderNull(e.opt("preisDc"), MAX_PREIS));
                n.put("grundgebuehr", zahlOderNull(e.opt("grundgebuehr"), MAX_GRUNDGEBUEHR));
                n.put("karte", e.optBoolean("karte", false));
                out.put(n);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static JSONObject finde(JSONArray liste, String id) {
        if (id == null || id.isEmpty()) return null;
        for (int i = 0; liste != null && i < liste.length(); i++) {
            JSONObject e = liste.optJSONObject(i);
            if (e != null && id.equals(e.optString("id"))) return e;
        }
        return null;
    }

    /**
     * Preis je kWh für eine Ladung: DC → preisDc, AC → preisAc. Stromart unbekannt: der einzige gesetzte Preis bzw.
     * beide gleich; sonst null (Nutzer trägt ein). Fehlt der passende Preis, null.
     */
    public static Double preisFuer(JSONObject anbieter, int isDc) {
        if (anbieter == null) return null;
        Double ac = zahl(anbieter.opt("preisAc")), dc = zahl(anbieter.opt("preisDc"));
        if (isDc == 1) return dc;
        if (isDc == 0) return ac;
        if (ac == null) return dc;
        if (dc == null || ac.equals(dc)) return ac;
        return null;
    }

    /** Ergebnis von {@link #anlegen}: neue Liste und der angelegte bzw. geänderte Eintrag; sonst Fehlergrund. */
    public static final class Ergebnis {
        public final JSONArray liste;
        public final JSONObject anbieter;
        public final boolean neu;
        public final String grund;

        Ergebnis(JSONArray liste, JSONObject anbieter, boolean neu, String grund) {
            this.liste = liste;
            this.anbieter = anbieter;
            this.neu = neu;
            this.grund = grund;
        }

        public boolean ok() { return grund == null; }
    }

    /**
     * Anbieter anlegen: {@code {name, preisAc?, preisDc?, grundgebuehr?, karte?}}. Mindestens ein Preis (0 = gratis).
     * Gleicher Name (Groß/klein egal) ändert den bestehenden Eintrag, statt einen zweiten anzulegen; nicht
     * mitgeschickte Preise bleiben dann wie sie waren.
     */
    public static Ergebnis anlegen(JSONArray bisher, JSONObject req) {
        try {
            if (req == null) return fehler("json");
            String name = name(req.opt("name"));
            if (name == null) return fehler("name");
            Object[] ac = feld(req, "preisAc", MAX_PREIS);
            Object[] dc = feld(req, "preisDc", MAX_PREIS);
            Object[] gg = feld(req, "grundgebuehr", MAX_GRUNDGEBUEHR);
            if (ac == null) return fehler("preisAc");
            if (dc == null) return fehler("preisDc");
            if (gg == null) return fehler("grundgebuehr");
            if (req.has("karte") && !req.isNull("karte") && !(req.opt("karte") instanceof Boolean)) return fehler("karte");

            JSONArray liste = new JSONArray(bisher == null ? "[]" : bisher.toString());
            JSONObject alt = null;
            int maxNr = 0;
            for (int i = 0; i < liste.length(); i++) {
                JSONObject e = liste.optJSONObject(i);
                if (e == null) continue;
                String id = e.optString("id", "");
                if (id.startsWith("a")) {
                    try { maxNr = Math.max(maxNr, Integer.parseInt(id.substring(1))); } catch (NumberFormatException ignored) {}
                }
                if (alt == null && e.optString("name", "").toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))) {
                    alt = e;
                }
            }
            JSONObject e = alt != null ? alt : new JSONObject();
            if (alt == null) {
                if (liste.length() >= MAX) return fehler("max " + MAX);
                e.put("id", "a" + (maxNr + 1));
                e.put("preisAc", JSONObject.NULL);
                e.put("preisDc", JSONObject.NULL);
                e.put("grundgebuehr", JSONObject.NULL);
                e.put("karte", false);
            }
            e.put("name", name);
            if ((Boolean) ac[0]) e.put("preisAc", ac[1]);
            if ((Boolean) dc[0]) e.put("preisDc", dc[1]);
            if ((Boolean) gg[0]) e.put("grundgebuehr", gg[1]);
            if (req.opt("karte") instanceof Boolean) e.put("karte", req.optBoolean("karte"));
            if (zahl(e.opt("preisAc")) == null && zahl(e.opt("preisDc")) == null) return fehler("preis");
            if (alt == null) liste.put(e);
            return new Ergebnis(liste, e, alt == null, null);
        } catch (Exception ex) {
            return fehler("json");
        }
    }

    /**
     * Vorschlag aus der erkannten Säule (LadesaeuleZuordnung/LadesaeuleDienst speichern den Betreiber an der
     * Ladung): ein eigener Anbieter, dessen Name im Betreibernamen steckt oder umgekehrt. null = keiner.
     */
    public static JSONObject zuBetreiber(JSONArray liste, String betreiber) {
        String b = betreiber == null ? "" : betreiber.trim().toLowerCase(Locale.ROOT);
        if (b.length() < 3) return null;
        for (int i = 0; liste != null && i < liste.length(); i++) {
            JSONObject e = liste.optJSONObject(i);
            if (e == null) continue;
            String n = e.optString("name", "").trim().toLowerCase(Locale.ROOT);
            if (n.length() >= 3 && (b.contains(n) || n.contains(b))) return e;
        }
        return null;
    }

    // ---------------------------------------------------------------- Hilfen

    /** {gesetzt?, Wert (Number oder NULL)} oder null bei ungültigem Wert. Nicht mitgeschickt = nicht gesetzt. */
    private static Object[] feld(JSONObject req, String key, double max) {
        if (!req.has(key)) return new Object[] {false, null};
        Object v = req.opt(key);
        if (v == null || v == JSONObject.NULL) return new Object[] {true, JSONObject.NULL};
        if (!(v instanceof Number)) return null;
        double d = ((Number) v).doubleValue();
        if (!Double.isFinite(d) || d < 0 || d > max) return null;
        return new Object[] {true, rund(d)};
    }

    private static Object zahlOderNull(Object v, double max) {
        Double d = zahl(v);
        return d == null || d > max ? JSONObject.NULL : (Object) rund(d);
    }

    static Double zahl(Object v) {
        if (!(v instanceof Number)) return null;
        double d = ((Number) v).doubleValue();
        return Double.isFinite(d) && d >= 0 ? d : null;
    }

    private static double rund(double d) {
        return Math.round(d * 10000.0) / 10000.0;
    }

    static String name(Object v) {
        if (!(v instanceof String)) return null;
        String t = LadesaeuleZuordnung.einzeilig(((String) v).replaceAll("\\p{Cntrl}+", " "), MAX_NAME);
        return t.isEmpty() ? null : t;
    }

    private static Ergebnis fehler(String grund) {
        return new Ergebnis(null, null, false, grund);
    }
}
