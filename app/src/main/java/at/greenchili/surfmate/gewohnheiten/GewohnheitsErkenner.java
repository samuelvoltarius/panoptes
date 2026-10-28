package at.greenchili.surfmate.gewohnheiten;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 0.1.22 Gewohnheiten E2 (KONZEPT §3): einfache, nachvollziehbare Regeln, keine Black Box.
 *
 * <p>Eine Gewohnheit ist dieselbe Handlung mit demselben Wert am selben Ort und vom selben Tagestyp (werktags bzw.
 * am Wochenende), an mindestens {@link #MIN_TAGE} verschiedenen Tagen innerhalb von {@link #FENSTER_TAGE} Tagen, alle
 * innerhalb ±{@link #TOLERANZ_MIN} min um den Median der Uhrzeit. Gezählt wird herkunftsübergreifend, aber nur, was
 * ein Mensch getan hat ({@link Herkunft#zaehlt}); eine Handlung, die binnen {@link #ZURUECK_MS} durch eine neuere
 * derselben Art ersetzt wurde (Fehlgriff, Drehen am Rad), zählt nicht. „Weg“ und unbekannte Orte werden nie
 * vorgeschlagen.
 *
 * <p>Eine Zusatzbedingung nur, wenn sie für alle Treffer gilt (höchstens eine): wärmende Handlungen „unter X °C“,
 * Wallbox „wenn angesteckt“. Jeder Vorschlag trägt seine Belege (Zeitpunkte) und die häufigste Herkunft, wenn sie
 * mindestens zwei Drittel der Treffer stellt.
 */
public final class GewohnheitsErkenner {

    public static final int MIN_TAGE = 3, FENSTER_TAGE = 14, TOLERANZ_MIN = 30;
    static final long ZURUECK_MS = 120_000L;
    static final long TAG_MS = 86_400_000L;

    /** Schon von einer Automation abgedeckt (gleiche Handlung, Uhrzeit ±30 min) – nie doppelt vorschlagen. */
    public static final class Abdeckung {
        public final String aktion;
        public final int minute;

        public Abdeckung(String aktion, int minute) {
            this.aktion = aktion;
            this.minute = minute;
        }
    }

    private GewohnheitsErkenner() {}

    public static List<JSONObject> erkenne(List<JSONObject> eintraege, long jetzt, ZoneId zone,
                                           Set<String> gesperrt, Collection<Abdeckung> abgedeckt) throws JSONException {
        List<JSONObject> menschlich = new ArrayList<>();
        for (JSONObject e : eintraege) {
            if (!Herkunft.zaehlt(e.optString("quelle")) || !Aktionen.MITSCHREIBEN.contains(e.optString("aktion"))) continue;
            long t = e.optLong("t", 0);
            if (t <= jetzt - FENSTER_TAGE * TAG_MS || t > jetzt) continue;
            menschlich.add(e);
        }
        Collections.sort(menschlich, (a, b) -> Long.compare(a.optLong("t"), b.optLong("t")));

        // gültig = nicht binnen 2 min durch eine neuere Handlung derselben Art ersetzt, vorschlagbar, an einem Ort
        Map<String, List<JSONObject>> gruppen = new LinkedHashMap<>();
        for (int i = 0; i < menschlich.size(); i++) {
            JSONObject e = menschlich.get(i);
            if (ersetzt(menschlich, i)) continue;
            if (!Aktionen.VORSCHLAGBAR.contains(e.optString("aktion"))) continue;
            JSONObject k = e.optJSONObject("kontext");
            String ort = k == null ? Kontext.UNBEKANNT : k.optString("ort", Kontext.UNBEKANNT);
            if (Kontext.WEG.equals(ort) || Kontext.UNBEKANNT.equals(ort)) continue;
            String tag = k.optString("tag", "");
            if (!Kontext.WERKTAG.equals(tag) && !Kontext.WOCHENENDE.equals(tag)) continue;
            gruppen.computeIfAbsent(e.optString("aktion") + "|" + wertSchluessel(e.optJSONObject("wert")) + "|" + tag + "|" + ort,
                    x -> new ArrayList<>()).add(e);
        }

        List<JSONObject> aus = new ArrayList<>();
        for (List<JSONObject> g : gruppen.values()) {
            JSONObject v = vorschlag(g, zone);
            if (v == null || gesperrt != null && gesperrt.contains(v.getString("schluessel"))) continue;
            if (abgedecktVon(abgedeckt, v.getString("aktion"), v.getInt("minute"))) continue;
            aus.add(v);
        }
        Collections.sort(aus, (a, b) -> a.optInt("anzahl") != b.optInt("anzahl")
                ? Integer.compare(b.optInt("anzahl"), a.optInt("anzahl"))
                : Long.compare(b.optLong("zuletzt"), a.optLong("zuletzt")));
        return aus;
    }

    /** Wurde Eintrag i binnen 2 min durch einen neueren derselben Art ersetzt? */
    static boolean ersetzt(List<JSONObject> l, int i) {
        JSONObject e = l.get(i);
        String art = art(e);
        long t = e.optLong("t");
        for (int j = i + 1; j < l.size(); j++) {
            JSONObject n = l.get(j);
            if (n.optLong("t") - t > ZURUECK_MS) break;
            if (art.equals(art(n))) return true;
        }
        return false;
    }

    /** Art einer Handlung: an/aus, Start/Stopp gehören zusammen; Temperatur je Zone, Sitze je Sitz. */
    static String art(JSONObject e) {
        String a = e.optString("aktion");
        JSONObject w = e.optJSONObject("wert");
        switch (a) {
            case Aktionen.KLIMA_AN:
            case Aktionen.KLIMA_AUS:
                return "klima";
            case Aktionen.KLIMA_TEMP:
                return a + ":" + (w == null ? "" : w.optString("zone"));
            case Aktionen.SITZHEIZUNG:
            case Aktionen.SITZLUEFTUNG:
                return a + ":" + (w == null ? "" : w.optString("sitz"));
            case Aktionen.WALLBOX_START:
            case Aktionen.WALLBOX_STOPP:
                return "wallbox_modus";
            case Aktionen.WAECHTER_AN:
            case Aktionen.WAECHTER_AUS:
                return "waechter";
            case Aktionen.CAMPING_AN:
            case Aktionen.CAMPING_AUS:
                return "camping";
            default:
                return a;
        }
    }

    /** Wert in fester Schlüsselreihenfolge (gleicher Wert = gleicher Text). */
    static String wertSchluessel(JSONObject w) {
        if (w == null || w.length() == 0) return "";
        TreeMap<String, Object> m = new TreeMap<>();
        for (java.util.Iterator<String> it = w.keys(); it.hasNext(); ) {
            String k = it.next();
            m.put(k, w.opt(k));
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** Größte Häufung (±30 min um den Median, ein Treffer je Tag) einer Gruppe, oder null unter {@link #MIN_TAGE}. */
    static JSONObject vorschlag(List<JSONObject> g, ZoneId zone) throws JSONException {
        // ein Treffer je Tag (der erste)
        Map<LocalDate, JSONObject> jeTag = new LinkedHashMap<>();
        for (JSONObject e : g) jeTag.putIfAbsent(Instant.ofEpochMilli(e.optLong("t")).atZone(zone).toLocalDate(), e);
        List<JSONObject> l = new ArrayList<>(jeTag.values());
        if (l.size() < MIN_TAGE) return null;
        Collections.sort(l, (a, b) -> Integer.compare(minute(a), minute(b)));

        List<JSONObject> best = null;
        for (int i = 0; i < l.size(); i++) {
            List<JSONObject> f = new ArrayList<>();
            for (int j = i; j < l.size() && minute(l.get(j)) - minute(l.get(i)) <= 2 * TOLERANZ_MIN; j++) f.add(l.get(j));
            int m = median(f);
            List<JSONObject> drin = new ArrayList<>();
            for (JSONObject e : f) if (Math.abs(minute(e) - m) <= TOLERANZ_MIN) drin.add(e);
            if (best == null || drin.size() > best.size()) best = drin;
        }
        if (best == null || best.size() < MIN_TAGE) return null;

        JSONObject erster = best.get(0);
        String aktion = erster.optString("aktion");
        JSONObject k = erster.optJSONObject("kontext");
        String tag = k.optString("tag"), ort = k.optString("ort");
        int m = median(best);
        int gerundet = Math.min(23 * 60 + 55, (int) Math.round(m / 5.0) * 5);

        JSONArray treffer = new JSONArray();
        long zuletzt = 0;
        Map<String, Integer> quellen = new HashMap<>();
        boolean alleAussen = true, alleAngesteckt = true;
        int maxAussen = Integer.MIN_VALUE;
        List<JSONObject> zeitlich = new ArrayList<>(best);
        Collections.sort(zeitlich, (a, b) -> Long.compare(a.optLong("t"), b.optLong("t")));
        for (JSONObject e : zeitlich) {
            long t = e.optLong("t");
            treffer.put(new JSONObject().put("t", t).put("quelle", e.optString("quelle")));
            zuletzt = Math.max(zuletzt, t);
            quellen.merge(e.optString("quelle"), 1, Integer::sum);
            JSONObject ke = e.optJSONObject("kontext");
            if (ke == null || !ke.has("aussen")) alleAussen = false;
            else maxAussen = Math.max(maxAussen, ke.optInt("aussen"));
            if (ke == null || !ke.optBoolean("angesteckt", false)) alleAngesteckt = false;
        }

        JSONObject v = new JSONObject();
        v.put("schluessel", schluessel(aktion, erster.optJSONObject("wert"), tag, ort, gerundet / 60));
        v.put("aktion", aktion);
        v.put("wert", erster.optJSONObject("wert") == null ? new JSONObject() : new JSONObject(erster.getJSONObject("wert").toString()));
        v.put("tag", tag);
        v.put("ort", ort);
        v.put("minute", gerundet);
        v.put("uhrzeit", String.format(java.util.Locale.ROOT, "%02d:%02d", gerundet / 60, gerundet % 60));
        v.put("anzahl", best.size());
        v.put("treffer", treffer);
        v.put("zuletzt", zuletzt);
        if (Aktionen.WAERMEN.contains(aktion) && alleAussen && maxAussen < 10) {
            v.put("bedingung", new JSONObject().put("aussen_unter", (Math.floorDiv(maxAussen, 5) + 1) * 5));
        } else if (Aktionen.WALLBOX.contains(aktion) && alleAngesteckt) {
            v.put("bedingung", new JSONObject().put("angesteckt", true));
        }
        String meist = null;
        int n = 0;
        for (Map.Entry<String, Integer> e : quellen.entrySet()) {
            if (e.getValue() > n) {
                n = e.getValue();
                meist = e.getKey();
            }
        }
        if (meist != null && n * 3 >= best.size() * 2) v.put("quelle_meist", meist);
        return v;
    }

    /** Sperr-Schlüssel („Nein“ = nie wieder): Aktion, Wert, Tagestyp, Ort, Stunde. */
    public static String schluessel(String aktion, JSONObject wert, String tag, String ort, int stunde) {
        return aktion + "|" + wertSchluessel(wert) + "|" + tag + "|" + ort + "|" + stunde;
    }

    static boolean abgedecktVon(Collection<Abdeckung> abgedeckt, String aktion, int minute) {
        if (abgedeckt == null) return false;
        for (Abdeckung a : abgedeckt) {
            if (a.aktion.equals(aktion) && Math.abs(a.minute - minute) <= TOLERANZ_MIN) return true;
        }
        return false;
    }

    private static int minute(JSONObject e) {
        JSONObject k = e.optJSONObject("kontext");
        return k == null ? 0 : k.optInt("min");
    }

    private static int median(List<JSONObject> l) {
        List<Integer> m = new ArrayList<>();
        for (JSONObject e : l) m.add(minute(e));
        Collections.sort(m);
        int n = m.size();
        return n % 2 == 1 ? m.get(n / 2) : (m.get(n / 2 - 1) + m.get(n / 2)) / 2;
    }

    /** Für Tests und den Status: die gesperrten Schlüssel aus einem JSON-Array. */
    public static Set<String> menge(JSONArray a) {
        Set<String> s = new HashSet<>();
        for (int i = 0; a != null && i < a.length(); i++) s.add(a.optString(i));
        return s;
    }
}
