package at.greenchili.surfmate.notifications;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 0.1.28 P4: eine Seite für alle Meldungen. Je Thema ein Schalter „Handy“ und „Telegram“.
 *
 * <p>Quelle: Abschnitt {@code themen} der Kern-Registry ({@code assets/notifications-categories.json}). Ein Thema
 * bündelt Push-Kategorien ({@code push[]}, gelten für ALLE angemeldeten Geräte) und Telegram-Schalter
 * ({@code telegram[]} = Schlüssel im Abschnitt {@code telegram}, oder {@code telegramEinstellung} =
 * „abschnitt.schluessel“, z. B. {@code recording.ereignisTelegram}). Die beiden Systeme (Push-Register je Gerät,
 * Telegram-Konfiguration) bleiben getrennt; diese Klasse zeigt sie nur gemeinsam und schaltet sie gesammelt.
 * Andere Schlüssel als die der Themen werden nie geschrieben.
 */
public final class MeldungsThemen {

    private MeldungsThemen() {}

    public static final class Thema {
        public final String id, label;
        public final List<String> push;
        public final List<String> telegram;
        /** „abschnitt.schluessel“ oder leer. */
        public final String telegramEinstellung;

        Thema(JSONObject o) {
            id = o.optString("id", "");
            label = o.optString("label", id);
            push = liste(o.optJSONArray("push"));
            telegram = liste(o.optJSONArray("telegram"));
            telegramEinstellung = o.optString("telegramEinstellung", "");
        }

        public boolean hatTelegram() {
            return !telegram.isEmpty() || !telegramEinstellung.isEmpty();
        }

        private static List<String> liste(JSONArray a) {
            List<String> l = new ArrayList<>();
            for (int i = 0; a != null && i < a.length(); i++) {
                String s = a.optString(i, "");
                if (!s.isEmpty()) l.add(s);
            }
            return Collections.unmodifiableList(l);
        }
    }

    /** Themen in Registry-Reihenfolge; ungültige oder doppelte fallen weg. */
    public static List<Thema> aus(String registryJson) {
        List<Thema> l = new ArrayList<>();
        try {
            JSONArray a = new JSONObject(registryJson).optJSONArray("themen");
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Thema t = new Thema(o);
                if (t.id.isEmpty() || !ids.add(t.id)) continue;
                if (!t.telegramEinstellung.isEmpty() && t.telegramEinstellung.indexOf('.') <= 0) continue;
                l.add(t);
            }
        } catch (Exception ignored) {
        }
        return l;
    }

    public static Thema finde(List<Thema> themen, String id) {
        for (Thema t : themen) if (t.id.equals(id)) return t;
        return null;
    }

    /** Standard je Telegram-Schlüssel aus dem Abschnitt {@code telegram} der Registry (fehlt = an). */
    public static Map<String, Boolean> telegramStandards(String registryJson) {
        Map<String, Boolean> m = new HashMap<>();
        for (at.greenchili.surfmate.telegram.config.TelegramKategorien.Kat k
                : at.greenchili.surfmate.telegram.config.TelegramKategorien.aus(registryJson)) {
            m.put(k.key, k.standard);
        }
        return m;
    }

    /** Zustand mehrerer Schalter: true = alle an, false = alle aus, null = keiner; teilweise = gemischt. */
    static final class Zustand {
        int an, aus;

        void zaehle(boolean b) {
            if (b) an++; else aus++;
        }

        void in(JSONObject o, String feld) throws Exception {
            if (an + aus == 0) {
                o.put(feld, JSONObject.NULL);
                return;
            }
            o.put(feld, aus == 0);
            if (an > 0 && aus > 0) o.put(feld + "Teilweise", true);
        }
    }

    /**
     * Sicht für die Seite.
     *
     * @param root           ganze Konfiguration (Abschnitte telegram, recording …); null = Standards
     * @param gemuteteJeGeraet je angemeldetem Push-Gerät die stummen Kategorien (Präfix „x.*“ wie PushSubscription)
     * @param telegramVerbunden Bot gekoppelt (sonst telegram = null)
     */
    public static JSONArray sicht(List<Thema> themen, JSONObject root, Map<String, Boolean> telegramStandards,
                                  Collection<Set<String>> gemuteteJeGeraet, boolean telegramVerbunden) throws Exception {
        JSONObject tg = root == null ? null : root.optJSONObject("telegram");
        JSONArray a = new JSONArray();
        for (Thema t : themen) {
            JSONObject o = new JSONObject().put("id", t.id).put("label", t.label);
            Zustand handy = new Zustand();
            if (!t.push.isEmpty()) {
                for (Set<String> stumm : gemuteteJeGeraet) {
                    for (String k : t.push) handy.zaehle(!stumm(stumm, k));
                }
            }
            handy.in(o, "handy");
            o.put("handyMoeglich", !t.push.isEmpty());
            Zustand telegram = new Zustand();
            if (telegramVerbunden) {
                for (String k : t.telegram) {
                    boolean std = telegramStandards == null || !telegramStandards.containsKey(k) || telegramStandards.get(k);
                    telegram.zaehle(tg != null && tg.has(k) ? tg.optBoolean(k, std) : std);
                }
                if (!t.telegramEinstellung.isEmpty()) telegram.zaehle(einstellung(root, t.telegramEinstellung));
            }
            telegram.in(o, "telegram");
            o.put("telegramMoeglich", t.hatTelegram());
            a.put(o);
        }
        return a;
    }

    /** Wie {@code PushSubscription.isMuted}: genaue Kategorie oder Präfix „x.*“. */
    static boolean stumm(Set<String> stumm, String kategorie) {
        if (stumm.contains(kategorie)) return true;
        for (String m : stumm) {
            if (m.endsWith(".*") && kategorie.startsWith(m.substring(0, m.length() - 1))) return true;
        }
        return false;
    }

    /** Wert von „abschnitt.schluessel“ (fehlt = aus: Meldung nur mit Zustimmung). */
    static boolean einstellung(JSONObject root, String pfad) {
        int p = pfad.indexOf('.');
        JSONObject s = root == null ? null : root.optJSONObject(pfad.substring(0, p));
        return s != null && s.optBoolean(pfad.substring(p + 1), false);
    }

    /**
     * Handy-Schalter auf ein Gerät anwenden: alle Push-Kategorien des Themas stumm bzw. nicht stumm. Ein
     * Präfix-Stummschalter („x.*“), der eine Kategorie des Themas trifft, wird beim Einschalten entfernt.
     *
     * @return true, wenn sich etwas geändert hat
     */
    public static boolean handySetzen(Thema t, Set<String> stumm, boolean an) {
        boolean geaendert = false;
        for (String k : t.push) {
            if (an) {
                geaendert |= stumm.remove(k);
                java.util.Iterator<String> it = stumm.iterator();
                while (it.hasNext()) {
                    String m = it.next();
                    if (m.endsWith(".*") && k.startsWith(m.substring(0, m.length() - 1))) {
                        it.remove();
                        geaendert = true;
                    }
                }
            } else if (!stumm(stumm, k)) {
                stumm.add(k);
                geaendert = true;
            }
        }
        return geaendert;
    }

    /** Telegram-Schalter: {abschnitt: {schluessel: wert}} – nur die Schlüssel des Themas. */
    public static Map<String, JSONObject> telegramDelta(Thema t, boolean an) throws Exception {
        Map<String, JSONObject> m = new HashMap<>();
        if (!t.telegram.isEmpty()) {
            JSONObject d = new JSONObject();
            for (String k : t.telegram) d.put(k, an);
            m.put("telegram", d);
        }
        if (!t.telegramEinstellung.isEmpty()) {
            int p = t.telegramEinstellung.indexOf('.');
            String abschnitt = t.telegramEinstellung.substring(0, p);
            JSONObject d = m.containsKey(abschnitt) ? m.get(abschnitt) : new JSONObject();
            d.put(t.telegramEinstellung.substring(p + 1), an);
            m.put(abschnitt, d);
        }
        return m;
    }

    /** „HH:MM“ → Minuten seit Mitternacht, -1 bei ungültig. */
    public static int minuten(String hhmm) {
        if (hhmm == null || !hhmm.matches("\\d{1,2}:\\d{2}")) return -1;
        int h = Integer.parseInt(hhmm.substring(0, hhmm.indexOf(':')));
        int m = Integer.parseInt(hhmm.substring(hhmm.indexOf(':') + 1));
        return h < 24 && m < 60 ? h * 60 + m : -1;
    }
}
