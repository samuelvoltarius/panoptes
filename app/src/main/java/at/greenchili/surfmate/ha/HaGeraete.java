package at.greenchili.surfmate.ha;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Smarte Geräte aus Home Assistant (HA_VERTRAG v1.1 Teil D, Weg A). Rein, testbar.
 *
 * <p>Kein HA-Token im Auto: Die HA-Integration sammelt die Kandidaten in HA und meldet sie per
 * {@code POST /api/ha/v1/geraete} (nur Kopplung der Art {@code homeassistant} mit Recht {@code geraete}, Standard AUS).
 * Ausgewählt wird in Panoptes; Steuern ist je Gerät Standard AUS ({@code steuern:false}) und kommt erst ab Stufe 2.
 *
 * <p>Zweite Sperre neben der Integration: Alarm, Sirene und Melder werden hier verworfen, ebenso Domänen, die diese
 * Version nicht kann ({@link #DOMAENEN}). Gerätenamen und Räume sind privat: sie liegen nur in der eigenen Datei
 * ({@link HaGeraeteSpeicher}), nie in der Gesamt-Config, nie im Log.
 */
public final class HaGeraete {

    private HaGeraete() {}

    public static final int VERSION = 1;
    public static final int MAX_GERAETE = 200;
    public static final int MAX_BYTES = 64 * 1024;

    /** Stufe 1 (nur lesen): diese Domänen kann diese Version anzeigen. Tor/Schloss (Stufe 5), Wallbox (Stufe 4) fehlen. */
    public static final Set<String> DOMAENEN = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "switch", "input_boolean", "light", "fan", "climate")));

    /** Nie (04.11.): auch wenn eine fremde Integration sie meldet. */
    static final Set<String> NIE_DOMAENEN = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "alarm_control_panel", "siren")));
    static final Set<String> NIE_KLASSEN = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "smoke", "carbon_monoxide", "gas", "safety")));

    static final Set<String> ARTEN = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "schalter", "licht", "luefter", "klima")));

    static final Pattern ENTITAET = Pattern.compile("[a-z_]{1,32}\\.[a-z0-9_]{1,128}");
    static final Pattern KURZ = Pattern.compile("[a-z0-9_]{0,32}");

    /** Ergebnis einer Meldung. */
    public static final class Meldung {
        public final JSONArray geraete;
        public final int verworfen;

        Meldung(JSONArray geraete, int verworfen) {
            this.geraete = geraete;
            this.verworfen = verworfen;
        }
    }

    /** Domäne aus der Entitäts-Kennung. */
    static String domaene(String entity) {
        int i = entity.indexOf('.');
        return i < 0 ? "" : entity.substring(0, i);
    }

    /**
     * Prüft den Rumpf von {@code POST /api/ha/v1/geraete}. Wirft bei kaputtem Rumpf; einzelne unpassende Geräte werden
     * nur gezählt ({@code verworfen}).
     */
    public static Meldung pruefe(String body) throws JSONException {
        if (body == null || body.length() > MAX_BYTES) throw new JSONException("zu_gross");
        JSONObject o = new JSONObject(body);
        if (o.optInt("v", 0) != VERSION) throw new JSONException("version");
        JSONArray roh = o.optJSONArray("geraete");
        if (roh == null) throw new JSONException("geraete");
        JSONArray gut = new JSONArray();
        Set<String> gesehen = new HashSet<>();
        int verworfen = 0;
        for (int i = 0; i < roh.length(); i++) {
            JSONObject g = roh.optJSONObject(i);
            JSONObject k = g == null ? null : bereinige(g);
            if (k == null || !gesehen.add(k.getString("entity")) || gut.length() >= MAX_GERAETE) {
                verworfen++;
                continue;
            }
            gut.put(k);
        }
        return new Meldung(gut, verworfen);
    }

    /** Bereinigte Kopie eines Geräts oder null (nicht angeboten). Nur bekannte Felder, Längen begrenzt. */
    static JSONObject bereinige(JSONObject g) throws JSONException {
        String entity = g.optString("entity", "");
        if (!ENTITAET.matcher(entity).matches()) return null;
        String d = domaene(entity);
        String klasse = g.optString("deviceClass", "");
        if (NIE_DOMAENEN.contains(d) || NIE_KLASSEN.contains(klasse)) return null;
        if (!DOMAENEN.contains(d)) return null;
        String art = g.optString("art", "");
        if (!ARTEN.contains(art)) return null;
        JSONObject k = new JSONObject();
        k.put("entity", entity);
        k.put("domaene", d);
        k.put("art", art);
        k.put("titel", text(g.optString("titel", entity), 60));
        String raum = g.isNull("raum") ? "" : text(g.optString("raum", ""), 40);
        if (!raum.isEmpty()) k.put("raum", raum);
        String plattform = g.optString("plattform", "");
        if (KURZ.matcher(plattform).matches()) k.put("plattform", plattform);
        if (!klasse.isEmpty() && KURZ.matcher(klasse).matches()) k.put("deviceClass", klasse);
        k.put("zustand", g.isNull("zustand") ? JSONObject.NULL : text(g.optString("zustand", ""), 32));
        k.put("faehigkeiten", faehigkeiten(d, g.optJSONObject("faehigkeiten")));
        return k;
    }

    /** Nur Felder, die Panoptes zum Anzeigen braucht. */
    static JSONObject faehigkeiten(String d, JSONObject f) throws JSONException {
        JSONObject out = new JSONObject();
        if (f == null) return out;
        if ("light".equals(d) && f.has("helligkeit")) out.put("helligkeit", f.optBoolean("helligkeit", false));
        if ("climate".equals(d)) {
            JSONArray modi = f.optJSONArray("modi");
            if (modi != null) {
                JSONArray m = new JSONArray();
                for (int i = 0; i < modi.length() && m.length() < 10; i++) {
                    String s = modi.optString(i, "");
                    if (KURZ.matcher(s).matches() && !s.isEmpty()) m.put(s);
                }
                out.put("modi", m);
            }
            for (String z : new String[]{"minTemp", "maxTemp", "schritt", "soll", "ist"}) {
                Object v = f.opt(z);
                if (v instanceof Number) {
                    double x = ((Number) v).doubleValue();
                    if (!Double.isNaN(x) && x > -100 && x < 200) out.put(z, x);
                }
            }
        }
        return out;
    }

    /** Ohne Steuerzeichen, gekürzt. */
    static String text(String s, int max) {
        String t = s == null ? "" : s.replaceAll("\\p{Cntrl}", " ").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /**
     * Neuer Bestand: die gemeldeten Geräte, je Gerät die Freigabe des Nutzers aus dem alten Bestand ({@code steuern},
     * Standard false). Nicht mehr gemeldete Geräte fallen weg (mit ihrer Freigabe – neu gemeldet heißt wieder AUS).
     */
    public static JSONObject zusammenfuehren(JSONObject alt, Meldung neu, long jetztMs) throws JSONException {
        JSONObject freigaben = alt == null ? null : alt.optJSONObject("freigaben");
        JSONObject f = new JSONObject();
        JSONArray liste = new JSONArray();
        for (int i = 0; i < neu.geraete.length(); i++) {
            JSONObject g = neu.geraete.getJSONObject(i);
            String e = g.getString("entity");
            if (freigaben != null && freigaben.optBoolean(e, false)) f.put(e, true);
            liste.put(g);
        }
        return new JSONObject().put("v", VERSION).put("zeit", jetztMs).put("geraete", liste).put("freigaben", f);
    }

    /** Antwort für {@code GET /api/ha/v1/geraete}: was diese Version kann und was freigegeben ist (ohne Namen). */
    public static JSONObject fuerIntegration(JSONObject bestand) throws JSONException {
        JSONArray domaenen = new JSONArray();
        for (String d : new java.util.TreeSet<>(DOMAENEN)) domaenen.put(d);
        JSONArray fr = new JSONArray();
        JSONObject f = bestand == null ? null : bestand.optJSONObject("freigaben");
        if (f != null) {
            for (Iterator<String> it = new java.util.TreeSet<>(keys(f)).iterator(); it.hasNext(); ) {
                String e = it.next();
                if (f.optBoolean(e, false)) fr.put(new JSONObject().put("entity", e).put("steuern", true));
            }
        }
        return new JSONObject().put("ok", true).put("v", VERSION).put("domaenen", domaenen).put("freigaben", fr);
    }

    /** Liste für die Panoptes-Oberfläche (mit Login): Geräte mit Freigabe-Stand. Steuern gibt es erst ab Stufe 2. */
    public static JSONObject fuerAnzeige(JSONObject bestand) throws JSONException {
        JSONArray out = new JSONArray();
        JSONArray l = bestand == null ? null : bestand.optJSONArray("geraete");
        JSONObject f = bestand == null ? null : bestand.optJSONObject("freigaben");
        for (int i = 0; l != null && i < l.length(); i++) {
            JSONObject g = new JSONObject(l.getJSONObject(i).toString());
            g.put("steuern", f != null && f.optBoolean(g.optString("entity"), false));
            out.put(g);
        }
        return new JSONObject().put("ok", true).put("stufe", 1).put("geraete", out)
                .put("zeit", bestand == null ? JSONObject.NULL : bestand.opt("zeit"));
    }

    private static Set<String> keys(JSONObject o) {
        Set<String> s = new HashSet<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) s.add(it.next());
        return s;
    }
}
