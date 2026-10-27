package at.greenchili.surfmate.orte;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ORTE_VERTRAG v1 (0.1.11, Kern 7): eine Ortsliste mit Eigenschaften. Reine Logik ohne Android/Config, damit
 * Migration und Abgleich mit echten Daten testbar sind.
 *
 * <p>„Alt“ = die bisherigen Speicher in Normalform:
 * {@code {ruhe:[{id,name,lat,lon,r,an}], privat:[{lat,lon,r}], tarife:[{id,label,lat,lon,r,acRate,dcRate,acGratis,dcGratis,waehrung}]}}.
 * Ein Ort: {@code {id,name,lat,lon,r,eigenschaften:{privat,ruhezone,tarif|null,zuhause,favorit},verweise:{ruhe?,tarif?},herkunft[],angelegt,geaendert}}.
 * In 0.1.11 ist {@code orte} die Quelle; die alten Speicher werden daraus gespiegelt ({@link #soll}), und was alte
 * Wege direkt schreiben, wird übernommen ({@link #aufnehmen}). Nichts davon löscht einen Ort.
 */
public final class Orte {

    private Orte() {}

    public static final int VERSION = 1;
    /** Koordination 22.10.: höchstens 50 Orte. */
    public static final int MAX_ORTE = 50;
    /** Zusammenführen: Mittelpunkte ≤ 30 m und Radien ≤ 30 m auseinander. */
    public static final double TOLERANZ_M = 30;

    /** Radiusgrenzen je Eigenschaft (Grenzen der alten Leser, solange sie aktiv sind). */
    public static final int R_MIN = 15, R_MAX = 2000;
    public static final int PRIVAT_MIN = 20, PRIVAT_MAX = 500;
    public static final int RUHE_MIN = 15, RUHE_MAX = 500;
    public static final int TARIF_MIN = 25, TARIF_MAX = 2000;

    // ------------------------------------------------------------------ Hilfen

    public static double meter(double lat1, double lon1, double lat2, double lon2) {
        double r = 6_371_000.0, dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    static boolean nah(JSONObject a, double lat, double lon, double r) {
        return meter(a.optDouble("lat"), a.optDouble("lon"), lat, lon) <= TOLERANZ_M
                && Math.abs(a.optDouble("r") - r) <= TOLERANZ_M;
    }

    static JSONObject eig(JSONObject ort) throws Exception {
        JSONObject e = ort.optJSONObject("eigenschaften");
        if (e == null) { e = leereEigenschaften(); ort.put("eigenschaften", e); }
        return e;
    }

    static JSONObject verweise(JSONObject ort) throws Exception {
        JSONObject v = ort.optJSONObject("verweise");
        if (v == null) { v = new JSONObject(); ort.put("verweise", v); }
        return v;
    }

    static JSONObject leereEigenschaften() throws Exception {
        return new JSONObject().put("privat", false).put("ruhezone", false).put("tarif", JSONObject.NULL)
                .put("zuhause", false).put("favorit", false);
    }

    public static JSONObject neuerOrt(String name, double lat, double lon, double r, long jetzt) throws Exception {
        return new JSONObject().put("id", "o_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8))
                .put("name", name == null || name.isEmpty() ? "Ort" : name)
                .put("lat", lat).put("lon", lon).put("r", (int) Math.round(r))
                .put("eigenschaften", leereEigenschaften()).put("verweise", new JSONObject())
                .put("herkunft", new JSONArray()).put("angelegt", jetzt).put("geaendert", jetzt);
    }

    static void herkunft(JSONObject ort, String h) throws Exception {
        JSONArray a = ort.optJSONArray("herkunft");
        if (a == null) { a = new JSONArray(); ort.put("herkunft", a); }
        for (int i = 0; i < a.length(); i++) if (h.equals(a.optString(i))) return;
        a.put(h);
    }

    /** Hat der Ort eine Eigenschaft außer den genannten? */
    static boolean hatAndere(JSONObject ort, String... ausser) throws Exception {
        JSONObject e = eig(ort);
        java.util.Set<String> aus = new java.util.HashSet<>(java.util.Arrays.asList(ausser));
        if (!aus.contains("privat") && e.optBoolean("privat")) return true;
        if (!aus.contains("ruhezone") && e.optBoolean("ruhezone")) return true;
        if (!aus.contains("zuhause") && e.optBoolean("zuhause")) return true;
        if (!aus.contains("favorit") && e.optBoolean("favorit")) return true;
        return !aus.contains("tarif") && e.optJSONObject("tarif") != null;
    }

    static JSONObject findeVerweis(JSONArray liste, String art, String id) {
        if (id == null || id.isEmpty()) return null;
        for (int i = 0; i < liste.length(); i++) {
            JSONObject o = liste.optJSONObject(i);
            JSONObject v = o == null ? null : o.optJSONObject("verweise");
            if (v != null && id.equals(v.optString(art, null))) return o;
        }
        return null;
    }

    /** Zusammenführungs-Kandidat: naher Ort, der für diese Art noch keinen Verweis hat. */
    static JSONObject findeNah(JSONArray liste, double lat, double lon, double r, String art) throws Exception {
        for (int i = 0; i < liste.length(); i++) {
            JSONObject o = liste.optJSONObject(i);
            if (o == null || !nah(o, lat, lon, r)) continue;
            if (art != null && verweise(o).has(art)) continue;
            if ("privat".equals(art) && eig(o).optBoolean("privat")) continue;
            return o;
        }
        return null;
    }

    static JSONObject tarifEig(JSONObject t) throws Exception {
        return new JSONObject()
                .put("label", t.optString("label", ""))
                .put("acRate", t.has("acRate") && !t.isNull("acRate") ? t.get("acRate") : JSONObject.NULL)
                .put("dcRate", t.has("dcRate") && !t.isNull("dcRate") ? t.get("dcRate") : JSONObject.NULL)
                .put("acGratis", t.optBoolean("acGratis", false)).put("dcGratis", t.optBoolean("dcGratis", false))
                .put("waehrung", t.optString("waehrung", ""));
    }

    // ------------------------------------------------------------------ Migration

    /** Einmalige Übernahme aus den alten Speichern (Reihenfolge laut Vertrag §4). Löscht nichts. */
    public static JSONArray migrieren(JSONObject alt, long jetzt) throws Exception {
        JSONArray liste = new JSONArray();
        JSONObject leer = new JSONObject().put("ruhe", new JSONArray()).put("privat", new JSONArray()).put("tarife", new JSONArray());
        aufnehmen(liste, leer, alt, jetzt);
        return liste;
    }

    // ------------------------------------------------------------------ Aufnehmen (alte Wege → orte)

    /**
     * Übernimmt, was sich in den alten Speichern seit dem letzten Spiegel ({@code vorher}) geändert hat. Neue Einträge
     * werden mit nahen Orten zusammengeführt oder als neue Orte angelegt; entfernte Einträge nehmen nur die Eigenschaft
     * weg (der Ort bleibt); verschobene Einträge mit weiteren Eigenschaften am Ort werden abgetrennt.
     *
     * @return Anzahl Änderungen
     */
    public static int aufnehmen(JSONArray liste, JSONObject vorher, JSONObject jetztAlt, long jetzt) throws Exception {
        int n = 0;
        // --- Ruhezonen (id) ---
        Map<String, JSONObject> rv = nachId(vorher.optJSONArray("ruhe")), rj = nachId(jetztAlt.optJSONArray("ruhe"));
        for (String id : rv.keySet()) {
            if (rj.containsKey(id)) continue;
            JSONObject o = findeVerweis(liste, "ruhe", id);
            if (o != null) {
                verweise(o).remove("ruhe");
                eig(o).put("ruhezone", false).put("zuhause", false);
                o.put("geaendert", jetzt);
                n++;
            }
        }
        for (Map.Entry<String, JSONObject> e : rj.entrySet()) {
            JSONObject z = e.getValue();
            JSONObject alt = rv.get(e.getKey());
            JSONObject o = findeVerweis(liste, "ruhe", e.getKey());
            if (o == null) {
                o = findeNah(liste, z.optDouble("lat"), z.optDouble("lon"), z.optDouble("r"), "ruhe");
                if (o == null) { o = neuerOrt(z.optString("name"), z.optDouble("lat"), z.optDouble("lon"), z.optDouble("r"), jetzt); liste.put(o); }
                verweise(o).put("ruhe", e.getKey());
                eig(o).put("ruhezone", z.optBoolean("an", true)).put("zuhause", true);
                herkunft(o, "ruhe:" + e.getKey());
                o.put("geaendert", jetzt);
                n++;
                continue;
            }
            if (alt != null && gleich(alt, z)) continue;
            n += aendern(liste, o, z, "ruhe", jetzt);
            eig(findeVerweis(liste, "ruhe", e.getKey())).put("ruhezone", z.optBoolean("an", true));
        }
        // --- private Orte (nur Koordinaten) ---
        Map<String, Integer> pv = zaehle(vorher.optJSONArray("privat")), pj = zaehle(jetztAlt.optJSONArray("privat"));
        Map<String, JSONObject> pRoh = new HashMap<>();
        JSONArray pa = jetztAlt.optJSONArray("privat");
        for (int i = 0; pa != null && i < pa.length(); i++) pRoh.put(schluessel(pa.getJSONObject(i)), pa.getJSONObject(i));
        JSONArray pvRoh = vorher.optJSONArray("privat");
        for (int i = 0; pvRoh != null && i < pvRoh.length(); i++) pRoh.putIfAbsent(schluessel(pvRoh.getJSONObject(i)), pvRoh.getJSONObject(i));
        for (Map.Entry<String, Integer> e : pv.entrySet()) {
            int weg = e.getValue() - pj.getOrDefault(e.getKey(), 0);
            JSONObject z = pRoh.get(e.getKey());
            for (int k = 0; k < weg; k++) {
                for (int i = 0; i < liste.length(); i++) {
                    JSONObject o = liste.getJSONObject(i);
                    if (eig(o).optBoolean("privat") && nah(o, z.optDouble("lat"), z.optDouble("lon"), z.optDouble("r"))) {
                        eig(o).put("privat", false);
                        o.put("geaendert", jetzt);
                        n++;
                        break;
                    }
                }
            }
        }
        for (Map.Entry<String, Integer> e : pj.entrySet()) {
            int dazu = e.getValue() - pv.getOrDefault(e.getKey(), 0);
            JSONObject z = pRoh.get(e.getKey());
            for (int k = 0; k < dazu; k++) {
                JSONObject o = findeNah(liste, z.optDouble("lat"), z.optDouble("lon"), z.optDouble("r"), "privat");
                if (o == null) {
                    o = neuerOrt("Privater Ort", z.optDouble("lat"), z.optDouble("lon"), z.optDouble("r"), jetzt);
                    liste.put(o);
                }
                eig(o).put("privat", true);
                herkunft(o, "privat");
                o.put("geaendert", jetzt);
                n++;
            }
        }
        // --- Tarif-Ortskreise (id) ---
        Map<String, JSONObject> tv = nachId(vorher.optJSONArray("tarife")), tj = nachId(jetztAlt.optJSONArray("tarife"));
        for (String id : tv.keySet()) {
            if (tj.containsKey(id)) continue;
            JSONObject o = findeVerweis(liste, "tarif", id);
            if (o != null) {
                verweise(o).remove("tarif");
                eig(o).put("tarif", JSONObject.NULL);
                o.put("geaendert", jetzt);
                n++;
            }
        }
        for (Map.Entry<String, JSONObject> e : tj.entrySet()) {
            JSONObject t = e.getValue();
            JSONObject alt = tv.get(e.getKey());
            JSONObject o = findeVerweis(liste, "tarif", e.getKey());
            if (o == null) {
                o = findeNah(liste, t.optDouble("lat"), t.optDouble("lon"), t.optDouble("r"), "tarif");
                if (o == null) { o = neuerOrt(t.optString("label"), t.optDouble("lat"), t.optDouble("lon"), t.optDouble("r"), jetzt); liste.put(o); }
                verweise(o).put("tarif", e.getKey());
                eig(o).put("tarif", tarifEig(t));
                herkunft(o, "tarif:" + e.getKey());
                o.put("geaendert", jetzt);
                n++;
                continue;
            }
            if (alt != null && gleich(alt, t)) continue;
            n += aendern(liste, o, t, "tarif", jetzt);
            JSONObject ziel = findeVerweis(liste, "tarif", e.getKey());
            eig(ziel).put("tarif", tarifEig(t));
        }
        return n;
    }

    /** Verschobener/umbenannter alter Eintrag: Ort mitziehen oder (bei weiteren Eigenschaften) abtrennen. */
    private static int aendern(JSONArray liste, JSONObject o, JSONObject z, String art, long jetzt) throws Exception {
        double lat = z.optDouble("lat"), lon = z.optDouble("lon"), r = z.optDouble("r");
        String name = "ruhe".equals(art) ? z.optString("name") : z.optString("label");
        boolean nur = "ruhe".equals(art) ? !hatAndere(o, "ruhezone", "zuhause") : !hatAndere(o, "tarif");
        if (nah(o, lat, lon, r) || nur) {
            if (nur) {
                o.put("lat", lat).put("lon", lon).put("r", (int) Math.round(r));
                if (!name.isEmpty()) o.put("name", name);
            }
            o.put("geaendert", jetzt);
            return 1;
        }
        // abtrennen: neuer Ort trägt diese Eigenschaft, der alte verliert sie
        JSONObject neu = neuerOrt(name, lat, lon, r, jetzt);
        String id = verweise(o).optString(art);
        verweise(o).remove(art);
        verweise(neu).put(art, id);
        if ("ruhe".equals(art)) {
            eig(neu).put("ruhezone", eig(o).optBoolean("ruhezone")).put("zuhause", eig(o).optBoolean("zuhause"));
            eig(o).put("ruhezone", false).put("zuhause", false);
        } else {
            eig(neu).put("tarif", eig(o).opt("tarif"));
            eig(o).put("tarif", JSONObject.NULL);
        }
        herkunft(neu, art + ":" + id);
        o.put("geaendert", jetzt);
        liste.put(neu);
        return 1;
    }

    static Map<String, JSONObject> nachId(JSONArray a) {
        Map<String, JSONObject> m = new LinkedHashMap<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && !o.optString("id").isEmpty()) m.put(o.optString("id"), o);
        }
        return m;
    }

    static String schluessel(JSONObject p) {
        return Math.round(p.optDouble("lat") * 1e6) + "|" + Math.round(p.optDouble("lon") * 1e6) + "|" + Math.round(p.optDouble("r"));
    }

    static Map<String, Integer> zaehle(JSONArray a) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) m.merge(schluessel(o), 1, Integer::sum);
        }
        return m;
    }

    static boolean gleich(JSONObject a, JSONObject b) {
        return a.toString().equals(b.toString());
    }

    // ------------------------------------------------------------------ Spiegel (orte → alte Speicher)

    /**
     * Soll-Zustand der alten Speicher aus der Ortsliste. Einträge ohne {@code id} sind neu anzulegen; {@code ort} nennt
     * den Ort, der danach den Verweis bekommt. Ruhezonen-Datei: Ort mit ruhezone ODER zuhause (Monatsbericht liest dort),
     * {@code an} = ruhezone.
     */
    public static JSONObject soll(JSONArray liste) throws Exception {
        JSONArray ruhe = new JSONArray(), privat = new JSONArray(), tarife = new JSONArray();
        for (int i = 0; i < liste.length(); i++) {
            JSONObject o = liste.getJSONObject(i);
            JSONObject e = eig(o), v = verweise(o);
            double lat = o.optDouble("lat"), lon = o.optDouble("lon");
            int r = o.optInt("r");
            if (e.optBoolean("ruhezone") || e.optBoolean("zuhause")) {
                JSONObject z = new JSONObject().put("name", o.optString("name")).put("lat", lat).put("lon", lon)
                        .put("r", r).put("an", e.optBoolean("ruhezone")).put("ort", o.optString("id"));
                if (v.has("ruhe")) z.put("id", v.optString("ruhe"));
                ruhe.put(z);
            }
            if (e.optBoolean("privat")) privat.put(new JSONObject().put("lat", lat).put("lon", lon).put("r", r));
            JSONObject t = e.optJSONObject("tarif");
            if (t != null) {
                JSONObject z = new JSONObject().put("label", t.optString("label", "").isEmpty() ? o.optString("name") : t.optString("label"))
                        .put("lat", lat).put("lon", lon).put("r", r)
                        .put("acRate", t.opt("acRate") == null ? JSONObject.NULL : t.get("acRate"))
                        .put("dcRate", t.opt("dcRate") == null ? JSONObject.NULL : t.get("dcRate"))
                        .put("acGratis", t.optBoolean("acGratis")).put("dcGratis", t.optBoolean("dcGratis"))
                        .put("waehrung", t.optString("waehrung", "")).put("ort", o.optString("id"));
                if (v.has("tarif")) z.put("id", v.optString("tarif"));
                tarife.put(z);
            }
        }
        return new JSONObject().put("ruhe", ruhe).put("privat", privat).put("tarife", tarife);
    }

    // ------------------------------------------------------------------ Prüfen

    /** Fehlercode oder null. {@code {error, …}} für 422. */
    public static JSONObject pruefen(JSONObject ort) throws Exception {
        String name = ort.optString("name", "").trim();
        if (name.isEmpty() || name.length() > 64) return fehler("name");
        double lat = ort.optDouble("lat", Double.NaN), lon = ort.optDouble("lon", Double.NaN);
        if (!(lat >= -90 && lat <= 90) || !(lon >= -180 && lon <= 180) || (lat == 0 && lon == 0)) return fehler("koordinaten");
        double r = ort.optDouble("r", Double.NaN);
        if (!(r >= R_MIN && r <= R_MAX)) return fehler("radius").put("min", R_MIN).put("max", R_MAX);
        JSONObject e = eig(ort);
        if (e.optBoolean("privat") && (r < PRIVAT_MIN || r > PRIVAT_MAX)) return radiusEig("privat", PRIVAT_MIN, PRIVAT_MAX);
        if ((e.optBoolean("ruhezone") || e.optBoolean("zuhause")) && (r < RUHE_MIN || r > RUHE_MAX))
            return radiusEig(e.optBoolean("ruhezone") ? "ruhezone" : "zuhause", RUHE_MIN, RUHE_MAX);
        JSONObject t = e.optJSONObject("tarif");
        if (t != null) {
            if (r < TARIF_MIN || r > TARIF_MAX) return radiusEig("tarif", TARIF_MIN, TARIF_MAX);
            boolean ac = t.has("acRate") && !t.isNull("acRate") && t.optDouble("acRate", -1) > 0;
            boolean dc = t.has("dcRate") && !t.isNull("dcRate") && t.optDouble("dcRate", -1) > 0;
            if (!ac && !dc && !t.optBoolean("acGratis") && !t.optBoolean("dcGratis")) return fehler("tarif_ohne_preis");
            for (String k : new String[] {"acRate", "dcRate"}) {
                if (t.has(k) && !t.isNull(k) && !(t.optDouble(k, -1) >= 0 && t.optDouble(k) < 100)) return fehler("tarif_preis");
            }
        }
        return null;
    }

    private static JSONObject fehler(String c) throws Exception {
        return new JSONObject().put("error", c);
    }

    private static JSONObject radiusEig(String eigenschaft, int min, int max) throws Exception {
        return fehler("radius_eigenschaft").put("eigenschaft", eigenschaft).put("min", min).put("max", max);
    }

    /** Für die Cockpit-Kopplung (Koordination): Name, Eigenschaften, „im Kreis“ – nie Koordinaten oder Radius. */
    public static JSONObject ohneKoordinaten(JSONObject ort, Double posLat, Double posLon) throws Exception {
        JSONObject o = new JSONObject().put("id", ort.optString("id")).put("name", ort.optString("name"));
        JSONObject e = new JSONObject(eig(ort).toString());
        o.put("eigenschaften", e);
        if (posLat != null && posLon != null) {
            o.put("imKreis", meter(posLat, posLon, ort.optDouble("lat"), ort.optDouble("lon")) <= ort.optDouble("r"));
        }
        return o;
    }

    /**
     * 0.1.12 (Leser-Umstellung): private Orte im Format der alten Leser ({@code [{lat, lon, radiusM}]}).
     */
    public static JSONArray privatZonen(JSONArray liste) throws Exception {
        JSONArray a = new JSONArray();
        for (int i = 0; liste != null && i < liste.length(); i++) {
            JSONObject o = liste.optJSONObject(i);
            if (o == null || !eig(o).optBoolean("privat")) continue;
            a.put(new JSONObject().put("lat", o.optDouble("lat")).put("lon", o.optDouble("lon")).put("radiusM", o.optInt("r")));
        }
        return a;
    }

    /** Orte, deren Kreis die Position enthält. */
    public static List<JSONObject> hier(JSONArray liste, double lat, double lon) {
        List<JSONObject> l = new ArrayList<>();
        for (int i = 0; i < liste.length(); i++) {
            JSONObject o = liste.optJSONObject(i);
            if (o != null && meter(lat, lon, o.optDouble("lat"), o.optDouble("lon")) <= o.optDouble("r")) l.add(o);
        }
        return l;
    }

    // ------------------------------------------------------------------ Leser (0.1.19, Vertrag §5)

    /**
     * 0.1.19 (Leser-Umstellung): Ruhezonen für SafeLocationManager aus der Ortsliste, Normalform
     * {@code [{id,name,lat,lon,r,an}]}. Wie der Spiegel ({@link #soll}): Ort mit ruhezone ODER zuhause, {@code an} =
     * ruhezone. Was ein alter Weg seit dem letzten Spiegel geschrieben hat und der Abgleich (höchstens 60 s) noch nicht
     * aufgenommen hat, gilt sofort: geänderte Zone → alte Fassung, gelöschte → weg, neue → dazu.
     *
     * @param alt     Ruhezonen der Datei (Normalform)
     * @param spiegel Ruhezonen beim letzten Spiegel (Abschnitt {@code orte.spiegel.ruhe}), darf null sein
     */
    public static JSONArray ruheZonen(JSONArray liste, JSONArray alt, JSONArray spiegel) throws Exception {
        Map<String, JSONObject> a = nachId(alt), sp = nachId(spiegel);
        JSONArray out = new JSONArray();
        java.util.Set<String> gesehen = new java.util.HashSet<>();
        JSONArray soll = soll(liste == null ? new JSONArray() : liste).getJSONArray("ruhe");
        String[] felder = {"name", "lat", "lon", "r", "an"};
        for (int i = 0; i < soll.length(); i++) {
            JSONObject z = soll.getJSONObject(i);
            String id = z.has("id") ? z.optString("id") : null;
            if (id != null) {
                gesehen.add(id);
                if (sp.containsKey(id)) {
                    if (!a.containsKey(id)) continue;                                   // alter Weg hat gelöscht
                    if (!gleicheFelder(a.get(id), sp.get(id), felder)) { out.put(a.get(id)); continue; }  // geändert
                }
            }
            out.put(new JSONObject().put("id", id != null ? id : "ort:" + z.optString("ort"))
                    .put("name", z.optString("name")).put("lat", z.optDouble("lat")).put("lon", z.optDouble("lon"))
                    .put("r", z.optInt("r")).put("an", z.optBoolean("an")));
        }
        for (Map.Entry<String, JSONObject> e : a.entrySet()) {
            // neu über den alten Weg (noch ohne Ort); im Spiegel, aber ohne Ort = über orte entfernt → nicht mehr
            if (!gesehen.contains(e.getKey()) && !sp.containsKey(e.getKey())) out.put(e.getValue());
        }
        return out;
    }

    /**
     * 0.1.19 (Leser-Umstellung): Kreis je Tarif-id ({@code verweise.tarif}) aus der Ortsliste für den TariffManager:
     * {@code id → {lat, lon, r}}. Preis und Verlauf bleiben am Tarif (Kostenhistorie, Neubepreisung). Hat ein alter Weg
     * den Kreis seit dem letzten Spiegel geändert, fehlt die id hier (der Tarif nimmt bis zum Abgleich seinen eigenen).
     */
    public static Map<String, double[]> tarifKreise(JSONArray liste, JSONArray alt, JSONArray spiegel) throws Exception {
        Map<String, JSONObject> a = nachId(alt), sp = nachId(spiegel);
        Map<String, double[]> m = new HashMap<>();
        String[] felder = {"lat", "lon", "r"};
        for (int i = 0; liste != null && i < liste.length(); i++) {
            JSONObject o = liste.optJSONObject(i);
            if (o == null || eig(o).optJSONObject("tarif") == null) continue;
            String id = verweise(o).optString("tarif", "");
            if (id.isEmpty()) continue;
            if (sp.containsKey(id) && (!a.containsKey(id) || !gleicheFelder(a.get(id), sp.get(id), felder))) continue;
            double lat = o.optDouble("lat", Double.NaN), lon = o.optDouble("lon", Double.NaN), r = o.optDouble("r", Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lon) || Double.isNaN(r)) continue;
            m.put(id, new double[] {lat, lon, r});
        }
        return m;
    }

    static boolean gleicheFelder(JSONObject a, JSONObject b, String[] felder) {
        for (String f : felder) {
            Object x = a.opt(f), y = b.opt(f);
            if (x instanceof Number && y instanceof Number) {
                if (Math.abs(((Number) x).doubleValue() - ((Number) y).doubleValue()) > 1e-9) return false;
            } else if (!String.valueOf(x).equals(String.valueOf(y))) {
                return false;
            }
        }
        return true;
    }
}
