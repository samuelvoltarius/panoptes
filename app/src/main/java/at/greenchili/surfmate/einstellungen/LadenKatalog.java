package at.greenchili.surfmate.einstellungen;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Schema-Gruppe „laden“ (LADEN_VERTRAG.md Teil A, GRUNDSATZ „Das Auto ist die Quelle“):
 * Ladekarten (nur Karten-IDs aus dem Planer-Katalog, keine Preise), bevorzugte und gemiedene Ladenetze
 * (netzneutral), Umweg, nur geöffnet, Mindest-kW, Stecker, Ankunfts-Ladestand, Laden bis, Modus.
 *
 * <p>Jedes Schreiben setzt {@code laden.stand}: ab dann ist das Auto die Quelle und jede Planung bekommt
 * den vollständigen Satz mit. {@code laden.ankunftSocMin} ersetzt {@code planner.reserve} (Doppelung):
 * lesend gilt der Altwert, solange der neue fehlt; beim Schreiben wird der Altwert gelöscht.
 */
public final class LadenKatalog {

    private LadenKatalog() {}

    public static final String ABSCHNITT = "laden";
    public static final String STAND = "stand";
    public static final int MAX_KARTEN = 30;
    public static final int MAX_NETZE = 20;
    public static final String[] STECKER = {"ccs", "typ2", "chademo", "nacs"};

    public static List<Feld> felder() {
        List<Feld> l = new ArrayList<>();
        int r = 0;

        Feld karten = liste("laden.karten", r += 10, MAX_KARTEN, LadenKatalog::pruefeKarten);
        karten.listeVon = Katalog.obj("id", "int (tarif_id aus GET /ev/api/tarife)", "anzeige", "text<=80");
        l.add(karten);
        l.add(bool("laden.nurMeineKarten", r += 10));

        Feld netze = liste("laden.netze", r += 10, MAX_NETZE, LadenKatalog::pruefeNetze);
        netze.listeVon = Katalog.obj("cpo", "text<=60 (aus GET /ev/api/betreiber)", "anzeige", "text<=80");
        l.add(netze);
        Feld modusNetze = new Feld("laden.netzeModus", Feld.Typ.AUSWAHL);
        modusNetze.standard = "bevorzugen";
        modusNetze.optionen.add(new Feld.Option("bevorzugen", "settings.laden.netzeModus.bevorzugen"));
        modusNetze.optionen.add(new Feld.Option("nur", "settings.laden.netzeModus.nur"));
        l.add(gruppe(modusNetze, r += 10));
        l.add(gruppe(zahl("laden.umwegMaxMin", 10, 0, 60, "min", false), r += 10));
        l.add(bool("laden.nurOffen", r += 10));
        Feld meiden = liste("laden.meiden", r += 10, MAX_NETZE, LadenKatalog::pruefeNetze);
        meiden.listeVon = netze.listeVon;
        meiden.erweitert = true;
        l.add(meiden);

        l.add(gruppe(zahl("laden.minKw", null, 0, 350, "kW", true), r += 10));
        Feld stecker = liste("laden.stecker", r += 10, STECKER.length, LadenKatalog::pruefeStecker);
        stecker.standard = null;
        stecker.nullErlaubt = true;
        stecker.erweitert = true;
        for (String s : STECKER) stecker.optionen.add(new Feld.Option(s, "settings.laden.stecker." + s));
        l.add(stecker);
        Feld ankunft = gruppe(zahl("laden.ankunftSocMin", null, 0, 50, "%", true), r += 10);
        ankunft.zugriff = ankunftZugriff();
        l.add(ankunft);
        l.add(gruppe(zahl("laden.ladenBis", null, 50, 100, "%", true), r += 10));
        Feld modus = new Feld("laden.modus", Feld.Typ.AUSWAHL);
        modus.standard = null;
        modus.nullErlaubt = true;
        for (String m : new String[] {"billig", "kompromiss", "schnell"}) {
            modus.optionen.add(new Feld.Option(m, "settings.laden.modus." + m));
        }
        l.add(gruppe(modus, r += 10));
        // Planer-Fahrzeugprofil (GRUNDSATZ: „Fahrzeug“ gehört in die Gruppe laden). Eigene Auswahl in der Oberfläche
        // (Kandidaten aus /api/laden → fahrzeug); null = nicht gewählt, dann nur ein eindeutig erkanntes Profil.
        Feld fz = new Feld("laden.fahrzeug", Feld.Typ.TEXT);
        fz.standard = null;
        fz.nullErlaubt = true;
        fz.verborgen = true;
        fz.pruefer = v -> {
            if (!(v instanceof String)) throw new IllegalArgumentException("typ");
            String t = einzeilig((String) v);
            if (t.isEmpty() || t.length() > 80) throw new IllegalArgumentException("max 80");
            return t;
        };
        gruppe(fz, r += 10);
        fz.zugriff = Feld.standardZugriff("laden.fahrzeug");   // ohne laden.stand: das Fahrzeug macht das Auto nicht zur Vorlieben-Quelle
        l.add(fz);
        // Ladenachfrage „Wo hast du geladen?“ (LADENACHFRAGE_VERTRAG): Standard an; ohne laden.stand (keine Planer-Vorliebe)
        Feld nf = new Feld("laden.nachfragen", Feld.Typ.BOOL);
        nf.standard = true;
        gruppe(nf, r += 10);
        nf.zugriff = Feld.standardZugriff("laden.nachfragen");
        l.add(nf);
        // „Für alle AC-Ladungen ohne Ort gilt: <Preis>“ (je kWh; 0 = gratis, ausdrücklich gewählt). Nicht gesetzt = nachfragen.
        Feld acs = new Feld("laden.acStandard", Feld.Typ.ZAHL);
        acs.standard = null;
        acs.nullErlaubt = true;
        acs.min = 0.0; acs.max = 100.0; acs.schritt = 0.001; acs.einheit = "/kWh";
        gruppe(acs, r += 10);
        acs.zugriff = Feld.standardZugriff("laden.acStandard");
        l.add(acs);
        return l;
    }

    // ---------------------------------------------------------------- Bausteine

    private static Feld gruppe(Feld f, int reihenfolge) {
        f.gruppe = ABSCHNITT;
        f.reihenfolge = reihenfolge;
        f.zugriff = mitStand(f.key);
        return f;
    }

    private static Feld bool(String key, int reihenfolge) {
        Feld f = new Feld(key, Feld.Typ.BOOL);
        f.standard = false;
        return gruppe(f, reihenfolge);
    }

    private static Feld zahl(String key, Integer std, int min, int max, String einheit, boolean nullErlaubt) {
        Feld f = new Feld(key, Feld.Typ.INT);
        f.standard = std;
        f.min = (double) min;
        f.max = (double) max;
        f.schritt = 1.0;
        f.einheit = einheit;
        f.nullErlaubt = nullErlaubt;
        return f;
    }

    /** Listen zeichnet die Oberfläche in einem eigenen Dialog (Katalog vom Planer). */
    private static Feld liste(String key, int reihenfolge, int max, Feld.Pruefer p) {
        Feld f = new Feld(key, Feld.Typ.LISTE);
        f.standard = new JSONArray();
        f.maxEintraege = max;
        f.verborgen = true;
        f.nullErlaubt = true;   // null = zurück auf leer
        f.pruefer = p;
        return gruppe(f, reihenfolge);
    }

    /** Schreibt den Wert und den Merker {@code laden.stand} (ab dann ist das Auto die Quelle). */
    static Feld.Zugriff mitStand(final String key) {
        final Feld.Zugriff basis = Feld.standardZugriff(key);
        return new Feld.Zugriff() {
            public Object lese(JSONObject root) { return basis.lese(root); }
            public Map<String, Map<String, Object>> schreibe(Object wert, JSONObject root) {
                Map<String, Map<String, Object>> out = basis.schreibe(wert, root);
                out.get(ABSCHNITT).put(STAND, Einstellungen.iso(System.currentTimeMillis()));
                return out;
            }
        };
    }

    /** {@code laden.ankunftSocMin}, lesend mit Rückfall auf {@code planner.reserve} (> 0); Schreiben löscht den Altwert. */
    static Feld.Zugriff ankunftZugriff() {
        final Feld.Zugriff neu = mitStand("laden.ankunftSocMin");
        return new Feld.Zugriff() {
            public Object lese(JSONObject root) {
                Object v = neu.lese(root);
                if (v != null) return v;
                JSONObject p = root == null ? null : root.optJSONObject("planner");
                if (p == null || !p.has("reserve") || p.isNull("reserve")) return null;
                double alt = p.optDouble("reserve", 0);
                return alt > 0 ? (int) Math.round(Math.min(50, alt)) : null;
            }
            public Map<String, Map<String, Object>> schreibe(Object wert, JSONObject root) {
                Map<String, Map<String, Object>> out = neu.schreibe(wert, root);
                JSONObject p = root == null ? null : root.optJSONObject("planner");
                if (p != null && p.has("reserve")) {
                    Map<String, Object> alt = new HashMap<>();
                    alt.put("reserve", JSONObject.NULL);
                    out.put("planner", alt);
                }
                return out;
            }
        };
    }

    // ---------------------------------------------------------------- Prüfungen

    static JSONArray pruefeKarten(Object v) {
        JSONArray in = array(v, MAX_KARTEN);
        JSONArray out = new JSONArray();
        Set<Integer> gesehen = new HashSet<>();
        try {
            for (int i = 0; i < in.length(); i++) {
                Object e = in.get(i);
                Object idRoh = e instanceof JSONObject ? ((JSONObject) e).opt("id") : e;
                if (!(idRoh instanceof Number)) throw new IllegalArgumentException("typ");
                double d = ((Number) idRoh).doubleValue();
                if (d != Math.rint(d) || d < 1 || d > Integer.MAX_VALUE) throw new IllegalArgumentException("id");
                int id = (int) d;
                if (!gesehen.add(id)) continue;
                JSONObject k = new JSONObject();
                k.put("id", id);
                String anz = e instanceof JSONObject ? anzeige(((JSONObject) e).opt("anzeige")) : null;
                if (anz != null) k.put("anzeige", anz);
                out.put(k);
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("typ");
        }
        return out;
    }

    static JSONArray pruefeNetze(Object v) {
        JSONArray in = array(v, MAX_NETZE);
        JSONArray out = new JSONArray();
        Set<String> gesehen = new HashSet<>();
        try {
            for (int i = 0; i < in.length(); i++) {
                Object e = in.get(i);
                Object roh = e instanceof JSONObject ? ((JSONObject) e).opt("cpo") : e;
                if (!(roh instanceof String)) throw new IllegalArgumentException("typ");
                String cpo = einzeilig((String) roh);
                if (cpo.isEmpty() || cpo.length() > 60) throw new IllegalArgumentException("cpo");
                if (!gesehen.add(cpo.toLowerCase(Locale.ROOT))) continue;
                JSONObject n = new JSONObject();
                n.put("cpo", cpo);
                String anz = e instanceof JSONObject ? anzeige(((JSONObject) e).opt("anzeige")) : null;
                if (anz != null) n.put("anzeige", anz);
                out.put(n);
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("typ");
        }
        return out;
    }

    /** Leere Liste = nicht gesetzt (Planer-Standard). */
    static JSONArray pruefeStecker(Object v) {
        JSONArray in = array(v, STECKER.length);
        Set<String> erlaubt = new HashSet<>(java.util.Arrays.asList(STECKER));
        JSONArray out = new JSONArray();
        Set<String> gesehen = new HashSet<>();
        for (int i = 0; i < in.length(); i++) {
            Object e = in.opt(i);
            if (!(e instanceof String) || !erlaubt.contains(e)) throw new IllegalArgumentException("option");
            if (gesehen.add((String) e)) out.put(e);
        }
        return out.length() == 0 ? null : out;
    }

    private static JSONArray array(Object v, int max) {
        if (!(v instanceof JSONArray)) throw new IllegalArgumentException("typ");
        JSONArray a = (JSONArray) v;
        if (a.length() > max) throw new IllegalArgumentException("max " + max);
        return a;
    }

    private static String anzeige(Object o) {
        if (!(o instanceof String)) return null;
        String s = einzeilig((String) o);
        if (s.isEmpty()) return null;
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    private static String einzeilig(String s) {
        return s.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
    }
}
