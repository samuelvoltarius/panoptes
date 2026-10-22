package at.greenchili.surfmate.auth;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;

/**
 * Liste der gekoppelten Geräte (EV-Cockpit u. a.) mit Widerruf (Koordination 21.10.2026, Regel B).
 *
 * <p>Jeder Kopplungs-JWT trägt {@code kid} = Kopplungs-id und gilt nur, solange der Eintrag hier
 * steht und das Gerät in den letzten {@link #GUELTIG_OHNE_NUTZUNG_MS} benutzt wurde (gleitend).
 * Widerruf löscht den Eintrag: Das Gerät ist bei der nächsten Anfrage draußen. Alfreds normale
 * Anmeldung (JWT ohne scope) und andere Kopplungen bleiben unberührt; regenerateToken ist nicht nötig.
 *
 * <p>„Zuletzt gesehen“ wird im Speicher sofort, auf Platte höchstens alle {@link #SPEICHER_TAKT_MS}
 * geschrieben, damit nicht jede Anfrage die Config umschreibt.
 */
public final class Kopplungen {

    public static final long GUELTIG_OHNE_NUTZUNG_MS = 30L * 24 * 3600 * 1000;
    static final long SPEICHER_TAKT_MS = 10L * 60 * 1000;
    public static final int MAX_GERAETE = 20;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Ablage (Config-Abschnitt "kopplungen"); in Tests ersetzbar. */
    public interface Speicher {
        JSONArray lade();
        boolean speichere(JSONArray geraete);
    }

    private final Speicher speicher;
    private JSONArray geraete;
    private long zuletztGespeichert;

    public Kopplungen(Speicher speicher) {
        this.speicher = speicher;
    }

    private JSONArray liste() {
        if (geraete == null) {
            JSONArray l = null;
            try { l = speicher.lade(); } catch (Throwable ignored) { }
            geraete = l == null ? new JSONArray() : l;
        }
        return geraete;
    }

    /** Neue Kopplung anlegen; liefert die id oder null (Liste voll / Speichern fehlgeschlagen). */
    public synchronized String anlegen(String name, long nowMs) {
        return anlegen(name, null, nowMs);
    }

    /** Wie oben, mit Geräte-Art ({@code cockpit} | {@code homeassistant}), sonst ohne Feld. */
    public synchronized String anlegen(String name, String art, long nowMs) {
        JSONArray l = liste();
        if (l.length() >= MAX_GERAETE) return null;
        String id = neueId();
        JSONObject g = new JSONObject();
        try {
            g.put("id", id);
            g.put("name", bereinigeName(name));
            g.put("angelegt", nowMs);
            g.put("zuletzt", nowMs);
            if (art != null && ARTEN.contains(art)) g.put("art", art);
        } catch (Exception ignored) { }
        JSONArray neu = kopie(l);
        neu.put(g);
        if (!speicher.speichere(neu)) return null;
        geraete = neu;
        zuletztGespeichert = nowMs;
        return id;
    }

    /**
     * Prüft eine Kopplungs-id bei jeder Anfrage. true, wenn eingetragen und in den letzten 30 Tagen
     * benutzt; frischt dabei „zuletzt gesehen“ auf.
     */
    public synchronized boolean pruefe(String id, long nowMs) {
        if (id == null || id.isEmpty()) return false;
        JSONArray l = liste();
        for (int i = 0; i < l.length(); i++) {
            JSONObject g = l.optJSONObject(i);
            if (g == null || !id.equals(g.optString("id"))) continue;
            long zuletzt = g.optLong("zuletzt", g.optLong("angelegt", 0));
            if (nowMs - zuletzt > GUELTIG_OHNE_NUTZUNG_MS) return false;
            try { g.put("zuletzt", nowMs); } catch (Exception ignored) { }
            if (nowMs - zuletztGespeichert >= SPEICHER_TAKT_MS) {
                if (speicher.speichere(kopie(l))) zuletztGespeichert = nowMs;
            }
            return true;
        }
        return false;
    }

    public static final int UNBEKANNT = 0, WIDERRUFEN = 1, NUR_IM_SPEICHER = 2;

    /**
     * Widerruf genau dieser Kopplung. Wirkt sofort (Speicher), auch wenn das Schreiben auf Platte
     * scheitert; dann {@link #NUR_IM_SPEICHER} (gilt bis zum nächsten Neustart, Oberfläche meldet es).
     */
    public synchronized int widerrufen(String id) {
        if (id == null || id.isEmpty()) return UNBEKANNT;
        JSONArray l = liste();
        JSONArray neu = new JSONArray();
        boolean gefunden = false;
        for (int i = 0; i < l.length(); i++) {
            JSONObject g = l.optJSONObject(i);
            if (g != null && id.equals(g.optString("id"))) { gefunden = true; continue; }
            if (g != null) neu.put(g);
        }
        if (!gefunden) return UNBEKANNT;
        geraete = neu;
        at.greenchili.surfmate.ha.HaEreignisse.schliessen(id);   // offene HA-Streams dieser Kopplung sofort zu
        return speicher.speichere(kopie(neu)) ? WIDERRUFEN : NUR_IM_SPEICHER;
    }

    /** Liste für die Oberfläche (ohne Geheimnisse; es gibt keine). */
    public synchronized JSONArray alle(long nowMs) {
        JSONArray out = new JSONArray();
        JSONArray l = liste();
        for (int i = 0; i < l.length(); i++) {
            JSONObject g = l.optJSONObject(i);
            if (g == null) continue;
            JSONObject o = new JSONObject();
            try {
                o.put("id", g.optString("id"));
                o.put("name", g.optString("name"));
                o.put("angelegt", g.optLong("angelegt"));
                o.put("zuletzt", g.optLong("zuletzt"));
                o.put("abgelaufen", nowMs - g.optLong("zuletzt", 0) > GUELTIG_OHNE_NUTZUNG_MS);
                if (g.has("art")) o.put("art", g.optString("art"));
            } catch (Exception ignored) { }
            out.put(o);
        }
        return out;
    }

    public static final java.util.Set<String> ARTEN = java.util.Collections.unmodifiableSet(
            new java.util.HashSet<>(java.util.Arrays.asList("cockpit", "homeassistant")));

    static String neueId() {
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder("k-");
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static String bereinigeName(String name) {
        String n = name == null ? "" : name.replaceAll("[\\p{Cntrl}<>]", "").trim();
        if (n.isEmpty()) n = "Gerät";
        return n.length() > 40 ? n.substring(0, 40) : n;
    }

    private static JSONArray kopie(JSONArray l) {
        try {
            return new JSONArray(l.toString());
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    // ---------------------------------------------------------------- Laufzeit (Daemon)

    private static volatile Kopplungen instanz;

    public static Kopplungen get() {
        Kopplungen k = instanz;
        if (k == null) {
            synchronized (Kopplungen.class) {
                if (instanz == null) instanz = new Kopplungen(new UcmSpeicher());
                k = instanz;
            }
        }
        return k;
    }

    private static final class UcmSpeicher implements Speicher {
        public JSONArray lade() {
            JSONObject s = at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig().optJSONObject("kopplungen");
            return s == null ? new JSONArray() : s.optJSONArray("geraete");
        }
        public boolean speichere(JSONArray geraete) {
            try {
                JSONObject s = new JSONObject();
                s.put("geraete", geraete);
                return at.greenchili.surfmate.config.UnifiedConfigManager.updateSection("kopplungen", s);
            } catch (Exception e) {
                return false;
            }
        }
    }
}
