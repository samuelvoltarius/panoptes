package at.greenchili.surfmate.einstellungen;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Logik hinter GET /api/einstellungen/schema, GET und PUT /api/einstellungen
 * (Vertrag EINSTELLUNGEN_SCHEMA.md v1.1). Rein: Config, Texte und Schreiben sind eingesteckt.
 *
 * <p>Regel A: hier laufen keine Befehle ans Auto, nur gespeicherte Werte. Regel B: Felder mit
 * nurLesendExtern sind über einen Kopplungs-Token nicht schreibbar. Gelesen wird ohne zu schreiben.
 */
public final class Einstellungen {

    /** Texte in der Anfragesprache (Server- bzw. Web-Katalog). */
    public interface Texte {
        String text(String key);
        /** Alle Texte unter einem Präfix (für bestaetigungText), leer wenn unbekannt. */
        JSONObject unter(String praefix);
    }

    /** Schreibt Werte je Config-Abschnitt; true bei Erfolg. */
    public interface Schreiber {
        boolean schreibe(String abschnitt, Map<String, Object> werte);
    }

    private Einstellungen() {}

    // ================================================================ Schema

    public static JSONObject schema(List<Feld> felder, JSONObject root, Katalog.Kontext k, Texte t) throws Exception {
        JSONObject res = new JSONObject();
        res.put("version", 1);
        JSONArray gruppen = new JSONArray();
        for (Katalog.Gruppe g : Katalog.GRUPPEN) {
            JSONObject go = new JSONObject();
            go.put("id", g.id);
            go.put("bereich", g.bereich);
            go.put("reihenfolge", g.reihenfolge);
            go.put("titelKey", "settings.group." + g.id);
            go.put("titel", t.text("settings.group." + g.id));
            JSONArray fs = new JSONArray();
            for (Feld f : felder) {
                if (!g.id.equals(f.gruppe)) continue;
                fs.put(feldJson(f, root, k, t));
            }
            go.put("felder", fs);
            gruppen.put(go);
        }
        res.put("gruppen", gruppen);
        res.put("etag", "s-" + hash(gruppen.toString()));
        return res;
    }

    static JSONObject feldJson(Feld f, JSONObject root, Katalog.Kontext k, Texte t) throws Exception {
        JSONObject o = new JSONObject();
        o.put("key", f.key);
        o.put("typ", f.typ.name().toLowerCase(java.util.Locale.ROOT));
        o.put("titelKey", f.titelKey);
        o.put("titel", t.text(f.titelKey));
        o.put("hilfeKey", f.hilfeKey);
        o.put("hilfe", t.text(f.hilfeKey));
        o.put("standard", f.standard == null ? JSONObject.NULL : f.standard);
        o.put("reihenfolge", f.reihenfolge);
        if (f.abschnitt != null) {
            JSONObject a = new JSONObject();
            a.put("id", f.abschnitt);
            a.put("titelKey", "settings.section." + f.abschnitt);
            a.put("titel", t.text("settings.section." + f.abschnitt));
            Integer ar = Katalog.ABSCHNITTE.get(f.abschnitt);
            a.put("reihenfolge", ar == null ? 0 : ar);
            o.put("abschnitt", a);
        }
        if (f.min != null) o.put("min", f.min);
        if (f.max != null) o.put("max", f.max);
        if (f.schritt != null) o.put("schritt", f.schritt);
        if (f.einheit != null) o.put("einheit", f.einheit);
        if (!f.optionen.isEmpty()) {
            JSONArray opts = new JSONArray();
            for (Feld.Option op : f.optionen) {
                JSONObject x = new JSONObject();
                x.put("wert", op.wert);
                x.put("titelKey", op.titelKey);
                x.put("titel", t.text(op.titelKey));
                x.put("gesperrt", op.gesperrt);
                if (op.gesperrtGrundKey != null) {
                    x.put("gesperrtGrundKey", op.gesperrtGrundKey);
                    x.put("gesperrtGrund", t.text(op.gesperrtGrundKey));
                }
                opts.put(x);
            }
            o.put("optionen", opts);
        }
        o.put("nurLesendExtern", f.nurLesendExtern || f.nurLesend);
        if (f.nurLesend) o.put("nurLesend", true);
        o.put("neustartNoetig", f.neustartNoetig);
        if (f.erweitert) o.put("erweitert", true);
        if (f.gefahr) o.put("gefahr", true);
        if (f.nullIstAus) o.put("nullIstAus", true);
        if (f.empfohlen != null) o.put("empfohlen", f.empfohlen);
        if (f.aktuell != null) o.put("aktuell", f.aktuell);
        if (f.bestaetigung != null) {
            o.put("bestaetigung", f.bestaetigung);
            JSONObject bt = t.unter(f.bestaetigung);
            if (bt != null && bt.length() > 0) o.put("bestaetigungText", bt);
        }
        if (f.nurWenn != null) o.put("nurWenn", f.nurWenn);
        o.put("sichtbar", sichtbar(f, root, k));
        if (f.modul != null) o.put("modul", f.modul);
        if (f.alias != null) o.put("alias", f.alias);
        if (f.listeVon != null) o.put("listeVon", f.listeVon);
        if (f.maxEintraege != null) o.put("maxEintraege", f.maxEintraege);
        return o;
    }

    static boolean sichtbar(Feld f, JSONObject root, Katalog.Kontext k) {
        if (f.verborgen) return false;
        if (f.nurWenn == null) return true;
        String fae = f.nurWenn.optString("faehigkeit", "");
        if ("cluster".equals(fae)) return k.cluster;
        if ("hud".equals(fae)) return k.hud;
        String key = f.nurWenn.optString("key", "");
        if (!key.isEmpty()) {
            Object v = Feld.standardZugriff(key).lese(root);
            Object gleich = f.nurWenn.opt("gleich");
            return v != null && gleich != null && String.valueOf(v).equals(String.valueOf(gleich));
        }
        return true;
    }

    // ================================================================ Werte

    public static JSONObject werte(List<Feld> felder, JSONObject root, Katalog.Kontext k) throws Exception {
        JSONObject w = new JSONObject();
        for (Feld f : felder) w.put(f.key, wertJson(f, root, k));
        JSONObject res = new JSONObject();
        res.put("werte", w);
        res.put("etag", "w-" + hash(w.toString()));
        return res;
    }

    static JSONObject wertJson(Feld f, JSONObject root, Katalog.Kontext k) throws Exception {
        JSONObject o = new JSONObject();
        Object v = f.zugriff.lese(root);
        boolean gesetzt = v != null;
        if (f.typ == Feld.Typ.GEHEIM) {
            o.put("gesetzt", gesetzt && !String.valueOf(v).isEmpty());
            return o;
        }
        o.put("wert", gesetzt ? v : (f.standard == null ? JSONObject.NULL : f.standard));
        o.put("gesetzt", gesetzt);
        if ("surveillance.triggerMode".equals(f.key)) {
            JSONArray z = root == null || root.optJSONObject("surveillance") == null ? null
                    : root.optJSONObject("surveillance").optJSONArray("privateZones");
            o.put("wirksam", TriggerModus.wirksam(gesetzt ? String.valueOf(v) : null, k.land,
                    z != null && z.length() > 0));
            o.put("status", TriggerModus.status(gesetzt ? String.valueOf(v) : null));
        }
        return o;
    }

    // ================================================================ PUT

    public static final class Ergebnis {
        public int status = 200;
        public final JSONObject gespeichert = new JSONObject();
        public final JSONObject fehler = new JSONObject();
        public String etag;
        public String grund;
        public boolean ok() { return status == 200 && fehler.length() == 0; }
    }

    public static Ergebnis aendern(List<Feld> felder, JSONObject root, Katalog.Kontext k, JSONObject body,
                                   boolean extern, Schreiber schreiber, long jetztMs) throws Exception {
        Ergebnis e = new Ergebnis();
        String aktuell = werte(felder, root, k).getString("etag");
        String verlangt = body.optString("etag", "");
        if (!verlangt.isEmpty() && !verlangt.equals(aktuell)) {
            e.status = 409;
            e.grund = "veraltet";
            e.etag = aktuell;
            return e;
        }
        JSONObject neu = body.optJSONObject("werte");
        if (neu == null) neu = new JSONObject();
        Set<String> bestaetigt = new HashSet<>();
        JSONArray b = body.optJSONArray("bestaetigt");
        for (int i = 0; b != null && i < b.length(); i++) bestaetigt.add(b.optString(i));

        // Arbeitskopie, damit Aliasse auf denselben Wert (deterrentAction) nacheinander stimmen.
        JSONObject arbeit = root == null ? new JSONObject() : new JSONObject(root.toString());
        Map<String, Map<String, Object>> schreiben = new HashMap<>();
        java.util.Iterator<String> it = neu.keys();
        List<String> keys = new ArrayList<>();
        while (it.hasNext()) keys.add(it.next());
        java.util.Collections.sort(keys);
        Set<String> aktionGesetzt = new HashSet<>();
        for (String key : keys) {
            Feld f = Katalog.finde(felder, key);
            if (f == null) { e.fehler.put(key, "unbekannt"); continue; }
            if (f.nurLesend || (extern && f.nurLesendExtern)) { e.fehler.put(key, "nurLesendExtern"); continue; }
            Object roh = neu.opt(key);
            Object wert;
            try {
                wert = pruefe(f, roh == JSONObject.NULL ? null : roh);
            } catch (IllegalArgumentException ex) {
                e.fehler.put(key, ex.getMessage());
                continue;
            }
            // Ausschalten (BOOL false) braucht nie eine Bestätigung, z. B. Blitzer-Rechtshinweis nur beim Einschalten.
            boolean ausschalten = f.typ == Feld.Typ.BOOL && Boolean.FALSE.equals(wert);
            if (f.bestaetigung != null && !ausschalten && !bestaetigt.contains(key)) {
                e.fehler.put(key, "bestaetigungFehlt");
                continue;
            }
            if ("surveillance.deterrentAction".equals(f.alias) && Boolean.TRUE.equals(wert)) {
                if (!aktionGesetzt.isEmpty()) { e.fehler.put(key, "konflikt"); continue; }
                aktionGesetzt.add(key);
            }
            Map<String, Map<String, Object>> w = f.zugriff.schreibe(wert, arbeit);
            for (Map.Entry<String, Map<String, Object>> s : w.entrySet()) {
                JSONObject sec = arbeit.optJSONObject(s.getKey());
                if (sec == null) { sec = new JSONObject(); arbeit.put(s.getKey(), sec); }
                for (Map.Entry<String, Object> kv : s.getValue().entrySet()) sec.put(kv.getKey(), kv.getValue());
                schreiben.computeIfAbsent(s.getKey(), x -> new HashMap<>()).putAll(s.getValue());
            }
            if (f.bestaetigungZeitKey != null) {
                String sec = f.bestaetigungZeitKey.substring(0, f.bestaetigungZeitKey.indexOf('.'));
                String name = f.bestaetigungZeitKey.substring(f.bestaetigungZeitKey.indexOf('.') + 1);
                schreiben.computeIfAbsent(sec, x -> new HashMap<>()).put(name, iso(jetztMs));
            }
            e.gespeichert.put(key, f.typ == Feld.Typ.GEHEIM ? JSONObject.NULL : (wert == null ? JSONObject.NULL : wert));
        }
        for (Map.Entry<String, Map<String, Object>> s : schreiben.entrySet()) {
            if (!schreiber.schreibe(s.getKey(), s.getValue())) {
                // Nichts schönreden: alle Felder dieses Abschnitts als Fehler melden.
                for (String key : keys) {
                    Feld f = Katalog.finde(felder, key);
                    if (f != null && e.gespeichert.has(key) && s.getKey().equals(abschnittVon(f, arbeit))) {
                        e.gespeichert.remove(key);
                        e.fehler.put(key, "speichernFehlgeschlagen");
                    }
                }
            }
        }
        e.etag = werte(felder, arbeit, k).getString("etag");
        return e;
    }

    private static String abschnittVon(Feld f, JSONObject root) {
        Map<String, Map<String, Object>> w = f.zugriff.schreibe(null, root);
        return w.isEmpty() ? f.abschnittConfig() : w.keySet().iterator().next();
    }

    /** Prüft und normalisiert einen Wert; IllegalArgumentException mit Fehlergrund. */
    public static Object pruefe(Feld f, Object v) {
        if (v == null && f.nullErlaubt) return null;
        if (f.pruefer != null) return f.pruefer.pruefe(v);
        switch (f.typ) {
            case BOOL:
                if (v instanceof Boolean) return v;
                throw new IllegalArgumentException("typ");
            case INT: {
                if (!(v instanceof Number)) throw new IllegalArgumentException("typ");
                double d = ((Number) v).doubleValue();
                if (d != Math.rint(d)) throw new IllegalArgumentException("typ");
                grenzen(f, d);
                return (int) d;
            }
            case ZAHL: {
                if (!(v instanceof Number)) throw new IllegalArgumentException("typ");
                double d = ((Number) v).doubleValue();
                grenzen(f, d);
                if (f.schritt != null && f.schritt > 0) d = Math.round(d / f.schritt) * f.schritt;
                return Math.round(d * 1000.0) / 1000.0;
            }
            case TEXT:
            case ZEIT:
            case FARBE: {
                if (!(v instanceof String)) throw new IllegalArgumentException("typ");
                String s = (String) v;
                if (s.length() > 200) throw new IllegalArgumentException("max 200");
                if (f.typ == Feld.Typ.ZEIT && !s.matches("^([01]\\d|2[0-3]):[0-5]\\d$")) throw new IllegalArgumentException("typ");
                return s;
            }
            case GEHEIM:
                if (v == null) return null;
                if (!(v instanceof String) || ((String) v).length() > 500) throw new IllegalArgumentException("typ");
                return v;
            case AUSWAHL: {
                if (!(v instanceof String)) throw new IllegalArgumentException("typ");
                for (Feld.Option o : f.optionen) {
                    if (o.wert.equals(v)) {
                        if (o.gesperrt) throw new IllegalArgumentException("gesperrt");
                        return v;
                    }
                }
                throw new IllegalArgumentException("option");
            }
            case GEOZONE:
                return pruefeZonen(f, v);
            case ZEITFENSTER:
                return pruefeFenster(f, v);
            default:
                throw new IllegalArgumentException("typ");
        }
    }

    private static void grenzen(Feld f, double d) {
        if (f.min != null && d < f.min) throw new IllegalArgumentException("min " + zahl(f.min));
        if (f.max != null && d > f.max) throw new IllegalArgumentException("max " + zahl(f.max));
    }

    private static String zahl(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static JSONArray pruefeZonen(Feld f, Object v) {
        if (!(v instanceof JSONArray)) throw new IllegalArgumentException("typ");
        JSONArray in = (JSONArray) v;
        if (f.maxEintraege != null && in.length() > f.maxEintraege) throw new IllegalArgumentException("max " + f.maxEintraege);
        JSONArray out = new JSONArray();
        for (int i = 0; i < in.length(); i++) {
            JSONObject z = in.optJSONObject(i);
            if (z == null) throw new IllegalArgumentException("typ");
            double lat = z.optDouble("lat", Double.NaN), lon = z.optDouble("lon", Double.NaN);
            double r = z.optDouble("radiusM", Double.NaN);
            if (Double.isNaN(lat) || lat < -90 || lat > 90 || Double.isNaN(lon) || lon < -180 || lon > 180) {
                throw new IllegalArgumentException("typ");
            }
            if (Double.isNaN(r) || (f.min != null && r < f.min) || (f.max != null && r > f.max)) {
                throw new IllegalArgumentException("radiusM " + zahl(f.min) + "-" + zahl(f.max));
            }
            String name = z.optString("name", "").trim();
            if (name.length() > 40) throw new IllegalArgumentException("max 40");
            String id = z.optString("id", "");
            if (id.isEmpty() || id.length() > 40) id = "z-" + Integer.toHexString((lat + "," + lon + "," + i).hashCode());
            JSONObject c = new JSONObject();
            try {
                c.put("id", id);
                c.put("name", name);
                c.put("lat", Math.round(lat * 1e6) / 1e6);
                c.put("lon", Math.round(lon * 1e6) / 1e6);
                c.put("radiusM", (int) Math.round(r));
            } catch (Exception ignored) {
            }
            out.put(c);
        }
        return out;
    }

    private static JSONArray pruefeFenster(Feld f, Object v) {
        if (!(v instanceof JSONArray)) throw new IllegalArgumentException("typ");
        JSONArray in = (JSONArray) v;
        if (f.maxEintraege != null && in.length() > f.maxEintraege) throw new IllegalArgumentException("max " + f.maxEintraege);
        JSONArray out = new JSONArray();
        for (int i = 0; i < in.length(); i++) {
            JSONObject w = in.optJSONObject(i);
            if (w == null) throw new IllegalArgumentException("typ");
            String from = w.optString("from", ""), to = w.optString("to", "");
            if (!from.matches("^([01]\\d|2[0-3]):[0-5]\\d$") || !to.matches("^([01]\\d|2[0-3]):[0-5]\\d$")) {
                throw new IllegalArgumentException("typ");
            }
            JSONArray days = w.optJSONArray("days");
            JSONArray d = new JSONArray();
            for (int j = 0; days != null && j < days.length(); j++) {
                int x = days.optInt(j, 0);
                if (x < 1 || x > 7) throw new IllegalArgumentException("typ");
                d.put(x);
            }
            if (d.length() == 0) throw new IllegalArgumentException("typ");
            JSONObject c = new JSONObject();
            try {
                c.put("days", d);
                c.put("from", from);
                c.put("to", to);
            } catch (Exception ignored) {
            }
            out.put(c);
        }
        return out;
    }

    static String iso(long ms) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.ROOT);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(ms));
    }

    static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
