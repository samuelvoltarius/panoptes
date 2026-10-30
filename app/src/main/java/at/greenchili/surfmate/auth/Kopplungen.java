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
            rechteStandHeben();
        }
        return geraete;
    }

    /**
     * 0.1.26.1: Rechte des Bestands einmal beim Laden auf {@link #RECHTE_STAND} heben und festschreiben, damit „update“
     * an der damals ältesten Handy-Kopplung hängt und nicht später zu einer anderen wandert.
     */
    private void rechteStandHeben() {
        boolean geaendert = false;
        java.util.List<Object[]> neu = new java.util.ArrayList<>();
        for (int i = 0; i < geraete.length(); i++) {
            JSONObject g = geraete.optJSONObject(i);
            if (g == null || !ART_HANDY.equals(g.optString("art"))) continue;
            if (g.optJSONArray("rechte") != null && g.optInt("rechteStand", 1) >= RECHTE_STAND) continue;
            neu.add(new Object[]{g, rechteVon(g)});
        }
        for (Object[] x : neu) {
            try {
                ((JSONObject) x[0]).put("rechte", x[1]).put("rechteStand", RECHTE_STAND);
                geaendert = true;
            } catch (Exception ignored) { }
        }
        if (geaendert) {
            try { speicher.speichere(geraete); } catch (Throwable ignored) { }
        }
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
            // 0.1.18 Handy-Vertrag §1: Standardrechte klima, zusperren, push – aufsperren erst per Web mit Login
            if (ART_HANDY.equals(art)) {
                // 0.1.32.1: auch die erste Handy-Kopplung ohne „update“: nur der Besitzer schaltet es ein
                g.put("rechte", new JSONArray(HANDY_STANDARD)).put("rechteStand", RECHTE_STAND);
            }
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
        String pushId = null;
        for (int i = 0; i < l.length(); i++) {
            JSONObject g = l.optJSONObject(i);
            if (g != null && id.equals(g.optString("id"))) {
                gefunden = true;
                pushId = g.optString("pushId", null);
                continue;
            }
            if (g != null) neu.put(g);
        }
        if (!gefunden) return UNBEKANNT;
        geraete = neu;
        at.greenchili.surfmate.ha.HaEreignisse.schliessen(id);   // offene HA-Streams dieser Kopplung sofort zu
        // 0.1.20: offene Web-Einmal-Codes verwerfen (der Web-JWT selbst fällt bei der nächsten Anfrage durch)
        WebSitzung.get().kopplungWeg(id);
        // 0.1.20: Brücken-Zugang des Handys am Server löschen und offene Kanäle schließen
        try {
            at.greenchili.surfmate.bruecke.Bruecke.kopplungWeg(id);
        } catch (Throwable ignored) { }
        // 0.1.18: Push-Abo der Handy-Kopplung mit weg (Schlüssel und Rechte gehen mit dem Eintrag)
        if (pushId != null && !pushId.isEmpty()) {
            try {
                at.greenchili.surfmate.handy.HandyPush.entfernen(pushId);
            } catch (Throwable ignored) { }
        }
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
                if (traegtFremdRechte(g.optString("art"))) o.put("rechte", rechteVon(g));   // 0.1.30
                if (ART_HANDY.equals(g.optString("art"))) {
                    o.put("rechte", rechteVon(g));
                    // nur ob ein Aufsperr-Schlüssel hinterlegt ist, nie der Schlüssel selbst
                    o.put("schluessel", !g.optString("geraeteSchluessel", "").isEmpty());
                    if (g.has("geraeteName")) o.put("geraeteName", g.optString("geraeteName"));
                    if (g.has("plattform")) o.put("plattform", g.optString("plattform"));
                    o.put("push", !g.optString("pushId", "").isEmpty());
                }
            } catch (Exception ignored) { }
            out.put(o);
        }
        return out;
    }

    public static final java.util.Set<String> ARTEN = java.util.Collections.unmodifiableSet(
            new java.util.HashSet<>(java.util.Arrays.asList("cockpit", "homeassistant", "handy", "web", "dienst")));

    /**
     * 0.1.30 Kern Sicherheit: fremder Dienst (z. B. Xaventra). Darf nur Wächter-Ereignisse hören und – mit Recht
     * {@code standbild} – ein Standbild je Ereignis holen; sonst nichts (ExternGuard, geringste Rechte).
     */
    public static final String ART_DIENST = "dienst";

    /** 0.1.21: Browser, angemeldet per Telegram-Erlauben – nur Web-Sitzung ohne Sicherheitsrechte (TelegramAnmeldung). */
    public static final String ART_WEB = "web";

    /**
     * Darf eine Web-Sitzung (scope handyweb, Stufe B) an dieser Kopplung hängen? Handy mit Recht „web“ oder ein per
     * Telegram angemeldeter Browser (Art web). Andere Arten nie.
     */
    public synchronized boolean darfWebSitzung(String id) {
        return ART_WEB.equals(art(id)) || hatRecht(id, RECHT_WEB);
    }

    // ---------------------------------------------------------------- 0.1.18 Handy (HANDY_API_VERTRAG.md)

    public static final String ART_HANDY = "handy";
    public static final String RECHT_KLIMA = "klima", RECHT_ZUSPERREN = "zusperren",
            RECHT_AUFSPERREN = "aufsperren", RECHT_PUSH = "push",
            RECHT_WEB = "web",   // 0.1.20 Web-Sitzung (Weboberfläche in der Handy-App, Stufe B)
            RECHT_KAMERA = "kamera",   // 0.1.25 Kamerabilder/Aufnahmen übers Internet (Brücke); Standard AUS (Passanten)
            RECHT_WALLBOX = "wallbox",   // 0.1.25 Wallbox starten/stoppen/Ladestrom vom Handy; Standard AUS
            RECHT_ROUTE = "route",       // 0.1.25 Route ins Auto schicken (nur Anzeige, nichts startet von selbst); Standard AN
            // 0.1.26.1 Panoptes aktualisieren (nur freigegebene Version des Updaters, nur geparkt). Standard AUS
            // (0.1.32.1), nur der Besitzer schaltet es ein; Bestand behält, was er hat
            RECHT_UPDATE = "update",
            // 0.1.28 P5 (Koordination 29.10.): Unfall-/Ereignis-Clips aufs Handy laden; Standard AUS, nur der Besitzer
            // schaltet es ein (Login/Display); zusätzlich zu „kamera“
            RECHT_EREIGNISCLIP = "ereignisclip",
            // 0.1.30 Kern Sicherheit: genau EIN Standbild je Wächter-Ereignis (nie Clip, nie Live); Standard AUS
            RECHT_STANDBILD = "standbild";
    public static final java.util.List<String> RECHTE = java.util.Collections.unmodifiableList(
            java.util.Arrays.asList(RECHT_KLIMA, RECHT_ZUSPERREN, RECHT_AUFSPERREN, RECHT_PUSH, RECHT_WEB, RECHT_KAMERA,
                    RECHT_WALLBOX, RECHT_ROUTE, RECHT_UPDATE, RECHT_EREIGNISCLIP, RECHT_STANDBILD));
    /**
     * 0.1.30 Kern Sicherheit: Rechte, die auch Kopplungen ohne Handy-Rechte (cockpit, homeassistant, dienst) tragen
     * können. Standard AUS, nie automatisch gesetzt (auch nicht bei der Umstellung); nur der Besitzer schaltet sie ein.
     */
    public static final java.util.List<String> FREMD_RECHTE = java.util.Collections.unmodifiableList(
            java.util.Arrays.asList(RECHT_KAMERA, RECHT_STANDBILD));

    /** Arten, die {@link #FREMD_RECHTE} tragen können (web und Alt-Kopplungen ohne Art nie). */
    static boolean traegtFremdRechte(String art) {
        return "cockpit".equals(art) || "homeassistant".equals(art) || ART_DIENST.equals(art);
    }
    /** Standard bei neuer Handy-Kopplung: aufsperren, kamera und wallbox AUS, bis der Besitzer sie mit Login einschaltet. */
    static final java.util.List<String> HANDY_STANDARD = java.util.Collections.unmodifiableList(
            java.util.Arrays.asList(RECHT_KLIMA, RECHT_ZUSPERREN, RECHT_PUSH, RECHT_WEB, RECHT_ROUTE));
    /**
     * Stand der Rechte-Liste: ab 2 (0.1.25) kennt die gespeicherte Liste „route“. Ältere Kopplungen bekommen das neue
     * Standard-AN-Recht „route“ beim Lesen dazu, bis der Besitzer die Rechte selbst setzt. Ab 3 (0.1.26.1) kennt sie
     * „update“: Bestand bekommt es beim Lesen nur für die älteste Handy-Kopplung (die erste des Besitzers).
     */
    static final int RECHTE_STAND = 3;

    public static final int SCHLUESSEL_GESPEICHERT = 1, SCHLUESSEL_SCHON = 2, SCHLUESSEL_FEHLER = 0,
            SCHLUESSEL_KEINE_HANDY_KOPPLUNG = 3;

    private JSONObject finde(String id) {
        if (id == null || id.isEmpty()) return null;
        JSONArray l = liste();
        for (int i = 0; i < l.length(); i++) {
            JSONObject g = l.optJSONObject(i);
            if (g != null && id.equals(g.optString("id"))) return g;
        }
        return null;
    }

    private JSONArray rechteVon(JSONObject g) {
        if (!ART_HANDY.equals(g.optString("art"))) {
            // 0.1.30: nur gespeicherte Fremd-Rechte, keine Standardwerte, keine Umstellung
            JSONArray out = new JSONArray();
            JSONArray r = traegtFremdRechte(g.optString("art")) ? g.optJSONArray("rechte") : null;
            for (int i = 0; r != null && i < r.length(); i++) {
                if (FREMD_RECHTE.contains(r.optString(i))) out.put(r.optString(i));
            }
            return out;
        }
        JSONArray r = g.optJSONArray("rechte");
        int stand = r == null ? 0 : g.optInt("rechteStand", 1);
        if (stand >= RECHTE_STAND) return r;
        JSONArray mit = new JSONArray();
        if (r == null) {
            for (String x : HANDY_STANDARD) mit.put(x);
        } else {
            for (int i = 0; i < r.length(); i++) mit.put(r.opt(i));
            if (stand < 2 && !enthaelt(mit, RECHT_ROUTE)) mit.put(RECHT_ROUTE);   // 0.1.25: neues Standard-AN-Recht für Bestand
        }
        // 0.1.26.1: „update“ für Bestand nur an der ältesten Handy-Kopplung
        if (!enthaelt(mit, RECHT_UPDATE) && g.optString("id").equals(aeltesteHandyId(null))) mit.put(RECHT_UPDATE);
        return mit;
    }

    private static boolean enthaelt(JSONArray r, String recht) {
        for (int i = 0; i < r.length(); i++) if (recht.equals(r.optString(i))) return true;
        return false;
    }

    /** id der ältesten Handy-Kopplung (außer {@code ohne}) oder null. */
    private String aeltesteHandyId(String ohne) {
        JSONArray l = liste();
        String best = null;
        long bestZeit = Long.MAX_VALUE;
        for (int i = 0; i < l.length(); i++) {
            JSONObject g = l.optJSONObject(i);
            if (g == null || !ART_HANDY.equals(g.optString("art")) || g.optString("id").equals(ohne)) continue;
            long t = g.optLong("angelegt", Long.MAX_VALUE - 1);
            if (best == null || t < bestZeit) {
                best = g.optString("id");
                bestZeit = t;
            }
        }
        return best;
    }

    /** Aktuelle Rechte einer Kopplung (Handy: alle; cockpit/homeassistant/dienst: nur {@link #FREMD_RECHTE}). */
    public synchronized java.util.List<String> rechte(String id) {
        java.util.List<String> out = new java.util.ArrayList<>();
        JSONObject g = finde(id);
        if (g == null) return out;
        JSONArray r = rechteVon(g);
        for (int i = 0; i < r.length(); i++) out.add(r.optString(i));
        return out;
    }

    /** Art der Kopplung oder null (unbekannt bzw. ohne Art). */
    public synchronized String art(String id) {
        JSONObject g = finde(id);
        return g == null || !g.has("art") ? null : g.optString("art");
    }

    public synchronized boolean istHandy(String id) {
        return ART_HANDY.equals(art(id));
    }

    /** Recht einer Kopplung. Nicht-Handy-Arten haben höchstens {@link #FREMD_RECHTE}, nie Handy-Rechte. */
    public synchronized boolean hatRecht(String id, String recht) {
        JSONObject g = finde(id);
        if (g == null) return false;
        JSONArray r = rechteVon(g);
        for (int i = 0; i < r.length(); i++) if (recht.equals(r.optString(i))) return true;
        return false;
    }

    /** Rechte setzen (nur aus Panoptes-Web mit Login). Unbekannte Rechte werden verworfen. */
    public synchronized boolean rechteSetzen(String id, java.util.Collection<String> rechte) {
        JSONObject g = finde(id);
        if (g == null) return false;
        boolean handy = ART_HANDY.equals(g.optString("art"));
        if (!handy && !traegtFremdRechte(g.optString("art"))) return false;
        JSONArray r = new JSONArray();
        for (String x : handy ? RECHTE : FREMD_RECHTE) if (rechte != null && rechte.contains(x)) r.put(x);
        try { g.put("rechte", r).put("rechteStand", RECHTE_STAND); } catch (Exception e) { return false; }
        return speichernSofort();
    }

    /**
     * Geräteschlüssel (Aufsperren) hinterlegen – first write wins. Ersetzen nur nach {@link #schluesselLoeschen}
     * aus Panoptes-Web mit Login.
     */
    public synchronized int schluesselMelden(String id, String oeffentlicherSchluessel, String plattform,
                                             String geraeteName) {
        JSONObject g = finde(id);
        if (g == null || !ART_HANDY.equals(g.optString("art"))) return SCHLUESSEL_KEINE_HANDY_KOPPLUNG;
        if (!g.optString("geraeteSchluessel", "").isEmpty()) return SCHLUESSEL_SCHON;
        try {
            g.put("geraeteSchluessel", oeffentlicherSchluessel);
            g.put("plattform", "ios".equals(plattform) ? "ios" : "android");
            g.put("geraeteName", bereinigeName(geraeteName));
        } catch (Exception e) {
            return SCHLUESSEL_FEHLER;
        }
        return speichernSofort() ? SCHLUESSEL_GESPEICHERT : SCHLUESSEL_FEHLER;
    }

    public synchronized String schluessel(String id) {
        JSONObject g = finde(id);
        String s = g == null ? "" : g.optString("geraeteSchluessel", "");
        return s.isEmpty() ? null : s;
    }

    /** Name für Meldungen: Gerätename aus der Schlüssel-Meldung, sonst der Kopplungsname. */
    public synchronized String anzeigeName(String id) {
        JSONObject g = finde(id);
        if (g == null) return "?";
        String n = g.optString("geraeteName", "");
        return n.isEmpty() ? g.optString("name", "?") : n;
    }

    /** Schlüssel entfernen (nur Panoptes-Web mit Login), z. B. nach neuem Fingerabdruck. */
    public synchronized boolean schluesselLoeschen(String id) {
        JSONObject g = finde(id);
        if (g == null) return false;
        g.remove("geraeteSchluessel");
        return speichernSofort();
    }

    public synchronized String pushId(String id) {
        JSONObject g = finde(id);
        String s = g == null ? "" : g.optString("pushId", "");
        return s.isEmpty() ? null : s;
    }

    public synchronized boolean pushSetzen(String id, String pushId) {
        JSONObject g = finde(id);
        if (g == null) return false;
        try {
            if (pushId == null) g.remove("pushId");
            else g.put("pushId", pushId);
        } catch (Exception e) {
            return false;
        }
        return speichernSofort();
    }

    private boolean speichernSofort() {
        return speicher.speichere(kopie(liste()));
    }

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
