package at.greenchili.surfmate.telegram.config;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.1.11 (Kern 7, Koordination 22.10.): Telegram nur noch über KATEGORIEN mit Ein/Aus. Die frühere Stufe
 * (Hinweis/Alarm/Kritisch) ist keine eigene Filterebene mehr, sondern drei Wächter-Kategorien; sie dient nur der
 * Sortierung bzw. Anzeige. Eine Quelle: Abschnitt {@code telegram} der Kern-Registry
 * ({@code assets/notifications-categories.json}); Web und Cockpit zeichnen daraus.
 *
 * <p>Die Kategorien schreiben in die bestehenden Schlüssel des Abschnitts {@code telegram} (tierCritical, tierAlerts,
 * tierNotices, criticalAlerts …) – alte Leser laufen unverändert, Bestandswerte bleiben erhalten.
 */
public final class TelegramKategorien {

    private TelegramKategorien() {}

    public static final class Kat {
        public final String id, key, gruppe, stufe, label, note, bestaetigung;
        public final boolean standard;

        Kat(JSONObject o) {
            id = o.optString("id");
            key = o.optString("key");
            gruppe = o.optString("gruppe", "system");
            stufe = o.optString("stufe", "info");
            standard = o.optBoolean("standard", true);
            label = o.optString("label", id);
            note = o.optString("note", "");
            bestaetigung = o.optString("bestaetigung", "");
        }
    }

    static final List<String> GRUPPEN = Arrays.asList("waechter", "fahrzeug", "laden", "parken", "system");
    static final List<String> STUFEN = Arrays.asList("critical", "warn", "info");

    /** Liest den Abschnitt {@code telegram} der Registry (leer, wenn er fehlt). Ungültige/doppelte Einträge fallen weg. */
    public static List<Kat> aus(String registryJson) {
        List<Kat> l = new ArrayList<>();
        try {
            JSONArray a = new JSONObject(registryJson).optJSONArray("telegram");
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Kat k = new Kat(o);
                if (k.id.isEmpty() || k.key.isEmpty() || !ids.add(k.id)) continue;
                l.add(k);
            }
        } catch (Exception ignored) {
        }
        return l;
    }

    /** Sortiert: Gruppe (feste Reihenfolge), dann Stufe kritisch → Hinweis, dann Registry-Reihenfolge. */
    static List<Kat> sortiert(List<Kat> l) {
        List<Kat> s = new ArrayList<>(l);
        Collections.sort(s, (a, b) -> {
            int g = rang(GRUPPEN, a.gruppe) - rang(GRUPPEN, b.gruppe);
            return g != 0 ? g : rang(STUFEN, a.stufe) - rang(STUFEN, b.stufe);
        });
        return s;
    }

    private static int rang(List<String> l, String v) {
        int i = l.indexOf(v);
        return i < 0 ? l.size() : i;
    }

    /** Übersetzer (server-i18n), im Test identisch. */
    public interface Texte {
        String text(String schluessel, String rueckfall);
    }

    /** Sicht für Web/Cockpit: [{id, key, gruppe, stufe, standard, an, label, note, bestaetigung?}]. */
    public static JSONArray sicht(List<Kat> kats, JSONObject telegram, Texte t) throws Exception {
        JSONArray a = new JSONArray();
        for (Kat k : sortiert(kats)) {
            String tk = "telegram.kategorie." + k.id.replace('.', '_');
            JSONObject o = new JSONObject()
                    .put("id", k.id).put("key", k.key).put("gruppe", k.gruppe).put("stufe", k.stufe)
                    .put("standard", k.standard)
                    .put("an", telegram != null && telegram.has(k.key) ? telegram.optBoolean(k.key, k.standard) : k.standard)
                    .put("label", t == null ? k.label : t.text(tk + ".label", k.label))
                    .put("note", t == null ? k.note : t.text(tk + ".note", k.note));
            if (!k.bestaetigung.isEmpty()) o.put("bestaetigung", k.bestaetigung);
            a.put(o);
        }
        return a;
    }

    /**
     * Wandelt {@code {kategorienId: bool}} in {@code {telegramKey: bool}}. Unbekannte Kennung oder kein Boolean →
     * IllegalArgumentException (nichts wird geschrieben).
     */
    public static Map<String, Boolean> schluessel(List<Kat> kats, JSONObject wunsch) {
        Map<String, Kat> nachId = new LinkedHashMap<>();
        for (Kat k : kats) nachId.put(k.id, k);
        Map<String, Boolean> out = new LinkedHashMap<>();
        java.util.Iterator<String> it = wunsch.keys();
        while (it.hasNext()) {
            String id = it.next();
            Kat k = nachId.get(id);
            if (k == null) throw new IllegalArgumentException("kategorie:" + id);
            Object v = wunsch.opt(id);
            if (!(v instanceof Boolean)) throw new IllegalArgumentException("wert:" + id);
            out.put(k.key, (Boolean) v);
        }
        return out;
    }

    /**
     * Einmalige Abbildung des alten Schalters {@code motionText} (Wächter-Texte gesamt) auf die drei Wächter-
     * Kategorien: war er aus, sind alle drei aus (Koordination: „War eine Stufe aus, sind die betroffenen
     * Kategorien aus“). Danach steht motionText fest auf an und filtert nicht mehr doppelt.
     *
     * @return Änderungen (leer = nichts zu tun)
     */
    public static JSONObject motionTextAbbilden(JSONObject telegram) throws Exception {
        JSONObject delta = new JSONObject();
        if (telegram == null || telegram.optBoolean(UnifiedTelegramConfig.K_MOTION_TEXT_ABGEBILDET, false)) return delta;
        if (telegram.has(UnifiedTelegramConfig.K_MOTION_TEXT)
                && !telegram.optBoolean(UnifiedTelegramConfig.K_MOTION_TEXT, true)) {
            delta.put(UnifiedTelegramConfig.K_TIER_NOTICES, false);
            delta.put(UnifiedTelegramConfig.K_TIER_ALERTS, false);
            delta.put(UnifiedTelegramConfig.K_TIER_CRITICAL, false);
            delta.put(UnifiedTelegramConfig.K_MOTION_TEXT, true);
        }
        delta.put(UnifiedTelegramConfig.K_MOTION_TEXT_ABGEBILDET, true);
        return delta;
    }
}
