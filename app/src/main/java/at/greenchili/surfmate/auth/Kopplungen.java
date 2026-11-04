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
            besitzerStandHeben();
        }
        return geraete;
    }

    /**
     * 0.1.38.8 Besitzer-Handy, einmal beim Laden: jede vorhandene Kopplung behält ihr Schreibrecht auf die Einstellungen
     * ({@code einstellungenBestand}, Bestandsschutz – Cockpit/HA brechen nicht). Neue Kopplungen tragen die Marke ohne
     * Bestandsschutz.
     *
     * <p>0.1.38.9 (Befund am Auto: die älteste „handy“-Kopplung war ein Brillen-Relay, nicht das iPhone): Die Migration
     * macht KEINEN Besitzer mehr. Stattdessen fragt Panoptes einmal am Display bzw. im Login „Welches Gerät ist dein
     * Handy?“ ({@link #besitzerFrage}); bis dahin gibt es keinen Besitzer, die Rechte bleiben wie vorher. Wer unter
     * 0.1.38.8 automatisch Besitzer wurde ({@code besitzerAusMigration}), bekommt dieselbe Frage einmal zur Bestätigung.
     */
    private void besitzerStandHeben() {
        boolean geaendert = false;
        boolean vor01388 = false;
        for (int i = 0; i < geraete.length(); i++) {
            JSONObject g = geraete.optJSONObject(i);
            if (g == null || g.has(MARKE_01388)) continue;
            try {
                g.put("einstellungenBestand", true).put(MARKE_01388, 1);
                geaendert = true;
                vor01388 = true;
            } catch (Exception ignored) { }
        }
        boolean frage = false;
        // 0.1.38.8 hat genau die älteste Handy-Kopplung des Bestands automatisch zum Besitzer gemacht. Ein Besitzer, den
        // jemand danach am Display/mit Login gesetzt hat (anderes Gerät), gilt als bestätigt: keine Frage.
        String aelteste = aeltesteHandyId(null);
        for (int i = 0; i < geraete.length(); i++) {
            JSONObject g = geraete.optJSONObject(i);
            if (g == null || g.has(MARKE_01389)) continue;
            try {
                g.put(MARKE_01389, 1);
                geaendert = true;
                if (!vor01388 && ART_HANDY.equals(g.optString("art")) && g.optString("id").equals(aelteste)
                        && g.optBoolean("einstellungenBestand", false)
                        && g.optJSONArray("rechte") != null && enthaelt(g.optJSONArray("rechte"), RECHT_BESITZER)) {
                    g.put("besitzerAusMigration", true);
                    frage = true;
                }
            } catch (Exception ignored) { }
        }
        if (!geaendert) return;
        // Frage nur, wenn es Handy-Kopplungen gibt: ohne Besitzer (Bestand) oder mit Besitzer aus der Migration
        if (frage || (vor01388 && besitzerIds().isEmpty() && aeltesteHandyId(null) != null)) {
            for (int i = 0; i < geraete.length(); i++) {
                JSONObject g = geraete.optJSONObject(i);
                if (g != null && ART_HANDY.equals(g.optString("art"))) {
                    try { g.put(FRAGE, true); } catch (Exception ignored) { }
                }
            }
        }
        try { speicher.speichere(geraete); } catch (Throwable ignored) { }
        if (besitzerFrageOffen()) {
            java.util.function.Consumer<Boolean> m = besitzerFrageMelder;
            if (m != null) {
                try { m.accept(frage); } catch (Throwable ignored) { }
            }
        }
    }

    static final String MARKE_01388 = "stand01388", MARKE_01389 = "stand01389", FRAGE = "besitzerFrage";

    /** 0.1.38.9: Hinweis „Welches Gerät ist dein Handy?“ (true = Bestätigung eines automatisch gesetzten Besitzers). */
    public static volatile java.util.function.Consumer<Boolean> besitzerFrageMelder;

    /** 0.1.38.9: Ist die einmalige Frage „Welches Gerät ist dein Handy?“ offen? */
    public synchronized boolean besitzerFrageOffen() {
        JSONArray l = liste();
        for (int i = 0; i < l.length(); i++) {
            JSONObject g = l.optJSONObject(i);
            if (g != null && g.optBoolean(FRAGE, false)) return true;
        }
        return false;
    }

    /** Auswahl für die Frage: Handy-Kopplungen mit Name, Anlagedatum, ob Besitzer und ob automatisch gesetzt. */
    public synchronized JSONObject besitzerFrage() {
        JSONObject o = new JSONObject();
        JSONArray a = new JSONArray();
        try {
            JSONArray l = liste();
            boolean bestaetigen = false;
            for (int i = 0; i < l.length(); i++) {
                JSONObject g = l.optJSONObject(i);
                if (g == null || !ART_HANDY.equals(g.optString("art"))) continue;
                boolean auto = g.optBoolean("besitzerAusMigration", false);
                bestaetigen |= auto;
                a.put(new JSONObject().put("id", g.optString("id")).put("name", anzeigeName(g.optString("id")))
                        .put("angelegt", g.optLong("angelegt")).put("besitzer", istBesitzer(g.optString("id")))
                        .put("automatisch", auto));
            }
            o.put("offen", besitzerFrageOffen()).put("bestaetigen", bestaetigen).put("geraete", a);
        } catch (Exception ignored) { }
        return o;
    }

    public static final int FRAGE_OK = 1, FRAGE_UNBEKANNT = 2, FRAGE_FEHLER = 0;

    /**
     * 0.1.38.9 Antwort auf die Frage (nur Display/Login): {@code id} = dieses Handy ist der Besitzer (ein automatisch
     * gesetzter Besitzer verliert das Recht, wenn er es nicht ist); {@code keins} = keines davon (automatisch gesetzter
     * Besitzer weg, die nächste Display-Kopplung wird Besitzer); beides leer = Später (nichts ändern). Danach ist die
     * Frage zu.
     */
    public synchronized int besitzerFrageBeantworten(String id, boolean keins) {
        JSONArray l = liste();
        JSONObject wahl = id == null || id.isEmpty() ? null : finde(id);
        if (id != null && !id.isEmpty() && (wahl == null || !ART_HANDY.equals(wahl.optString("art")))) return FRAGE_UNBEKANNT;
        for (int i = 0; i < l.length(); i++) {
            JSONObject g = l.optJSONObject(i);
            if (g == null) continue;
            boolean auto = g.optBoolean("besitzerAusMigration", false);
            if (auto && (keins || (wahl != null && wahl != g))) besitzerWeg(g);
            g.remove(FRAGE);
            g.remove("besitzerAusMigration");
        }
        if (wahl != null) besitzerDazu(wahl);
        boolean ok = speichernSofort();
        String was = wahl != null ? "gewaehlt" : keins ? "keins" : "spaeter";
        at.greenchili.surfmate.server.BefehlsLog.ereignis("kopplung.besitzer.frage." + was, ok ? "gesetzt" : "fehler",
                wahl == null ? null : id, wahl == null ? null : anzeigeName(id));
        if (ok && wahl != null) besitzerGemeldet(id, "gewaehlt");
        return ok ? FRAGE_OK : FRAGE_FEHLER;
    }

    private static void besitzerWeg(JSONObject g) {
        JSONArray r = g.optJSONArray("rechte");
        if (r == null) return;
        JSONArray neu = new JSONArray();
        for (int i = 0; i < r.length(); i++) if (!RECHT_BESITZER.equals(r.optString(i))) neu.put(r.optString(i));
        try { g.put("rechte", neu); } catch (Exception ignored) { }
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
        return anlegen(name, art, nowMs, false);
    }

    /**
     * 0.1.38.8: wie {@link #anlegen(String, String, long)}; {@code darfBesitzerWerden} nur für Kopplungen, die am Display
     * bzw. mit Login entstehen (Code vom Display, erlaubte Anfrage). Eine Handy-Kopplung wird dann Besitzer, wenn es
     * gerade keinen Besitzer gibt (nie aussperren, nie zwei automatisch).
     */
    public synchronized String anlegen(String name, String art, long nowMs, boolean darfBesitzerWerden) {
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
            g.put(MARKE_01388, 1).put(MARKE_01389, 1);   // 0.1.38.8: neue Kopplungen ohne Bestandsschutz für Einstellungen
        } catch (Exception ignored) { }
        boolean besitzer = darfBesitzerWerden && ART_HANDY.equals(art) && besitzerIds().isEmpty();
        if (besitzer) {
            besitzerDazu(g);
            // 0.1.38.9: die erste Display-Kopplung nach dem Update beantwortet die offene Frage
            for (int i = 0; i < l.length(); i++) {
                JSONObject x = l.optJSONObject(i);
                if (x != null) x.remove(FRAGE);
            }
        }
        JSONArray neu = kopie(l);
        neu.put(g);
        if (!speicher.speichere(neu)) return null;
        geraete = neu;
        zuletztGespeichert = nowMs;
        // 0.1.34 Befehls-Log: neue Kopplung mit Art (an der laufenden Anfrage oder mit Herkunft des Threads)
        at.greenchili.surfmate.server.BefehlsLog.ereignis("kopplung.angelegt" + (art != null && ARTEN.contains(art) ? "." + art : ""),
                "ok", id, g.optString("name", null));
        if (besitzer) besitzerGemeldet(id, "erste");
        return id;
    }

    /**
     * 0.1.38.8 Koppeln vom Besitzer-Handy: neue Kopplung mit genau der Art und den Rechten aus dem Code (nur Rechte, die
     * diese Art tragen kann; {@code besitzer} nie). Kein Bestandsschutz, nie automatisch Besitzer.
     */
    public synchronized String anlegenGebunden(String name, String art, java.util.Collection<String> rechte, long nowMs) {
        return anlegenGebunden(name, art, rechte, nowMs, 0L);
    }

    /**
     * Wie {@link #anlegenGebunden(String, String, java.util.Collection, long)}; {@code ablaufFest} &gt; 0 = gilt nur bis
     * dahin (0.1.39 PC „Nur heute“). Art {@code pc} nur bei eingeschaltetem {@link PcZugang#SCHALTER} und höchstens
     * {@link PcZugang#MAX_PC} je Auto (sonst null).
     */
    public synchronized String anlegenGebunden(String name, String art, java.util.Collection<String> rechte, long nowMs,
                                               long ablaufFest) {
        if (art == null || !ARTEN.contains(art) || ART_WEB.equals(art)) return null;
        if (ART_PC.equals(art) && (!PcZugang.erlaubt(nowMs) || pcIds().size() >= PcZugang.MAX_PC)) return null;
        String id = anlegen(name, art, nowMs, false);
        JSONObject g = id == null ? null : finde(id);
        if (g == null) return id;
        if (ablaufFest > 0) {
            try { g.put("ablaufFest", ablaufFest); } catch (Exception ignored) { }
        }
        JSONArray r = new JSONArray();
        java.util.List<String> erlaubt = rechteDerArt(art);
        for (String x : erlaubt) {
            // 0.1.38.8 Nachzug: nie besitzer, nie Fahrzeug-Aktionen/geraete_steuern per Code (zweite Sperre zum Handler)
            if (rechte != null && rechte.contains(x) && !RECHT_BESITZER.equals(x) && !NUR_DISPLAY_RECHTE.contains(x)) r.put(x);
        }
        try { g.put("rechte", r).put("rechteStand", RECHTE_STAND); } catch (Exception ignored) { }
        speichernSofort();
        return id;
    }

    // ---------------------------------------------------------------- 0.1.38.8 Besitzer

    /** Wird gerufen, wenn eine Kopplung Besitzer wird ({@code wie}: erste|bestand|weitergegeben). Produktion: Telegram. */
    public static volatile java.util.function.BiConsumer<String, String> besitzerMelder;

    private void besitzerGemeldet(String id, String wie) {
        at.greenchili.surfmate.server.BefehlsLog.ereignis("kopplung.besitzer." + wie, "gesetzt", id, anzeigeName(id));
        java.util.function.BiConsumer<String, String> m = besitzerMelder;
        if (m == null) return;
        try { m.accept(anzeigeName(id), wie); } catch (Throwable ignored) { }
    }

    private static void besitzerDazu(JSONObject g) {
        if (g == null) return;
        JSONArray r = g.optJSONArray("rechte");
        try {
            if (r == null) g.put("rechte", r = new JSONArray(HANDY_STANDARD)).put("rechteStand", RECHTE_STAND);
            if (!enthaelt(r, RECHT_BESITZER)) r.put(RECHT_BESITZER);
        } catch (Exception ignored) { }
    }

    /** Alle Kopplungen mit Recht {@code besitzer} (nur Art handy). */
    public synchronized java.util.List<String> besitzerIds() {
        java.util.List<String> out = new java.util.ArrayList<>();
        JSONArray l = liste();
        for (int i = 0; i < l.length(); i++) {
            JSONObject g = l.optJSONObject(i);
            if (g == null || !ART_HANDY.equals(g.optString("art"))) continue;
            JSONArray r = g.optJSONArray("rechte");
            if (r != null && enthaelt(r, RECHT_BESITZER)) out.add(g.optString("id"));
        }
        return out;
    }

    public synchronized boolean istBesitzer(String id) {
        return id != null && besitzerIds().contains(id);
    }

    /**
     * Darf diese Kopplung Einstellungen schreiben ({@code /api/einstellungen})? Besitzer, Recht {@code einstellungen}
     * (Standard AUS) oder Bestand vor 0.1.38.8.
     */
    public synchronized boolean einstellungenSchreiben(String id) {
        JSONObject g = finde(id);
        if (g == null) return false;
        return istBesitzer(id) || hatRecht(id, RECHT_EINSTELLUNGEN) || g.optBoolean("einstellungenBestand", false);
    }

    public static final int BESITZER_OK = 1, BESITZER_NICHT_ERLAUBT = 2, BESITZER_UNBEKANNT = 3, BESITZER_LETZTER = 4,
            BESITZER_FEHLER = 0;

    /** Besitzer gibt das Recht an eine andere Handy-Kopplung weiter (nur hinzufügen). */
    public synchronized int besitzerWeitergeben(String von, String zu) {
        if (!istBesitzer(von)) return BESITZER_NICHT_ERLAUBT;
        JSONObject g = finde(zu);
        if (g == null || !ART_HANDY.equals(g.optString("art"))) return BESITZER_UNBEKANNT;
        if (istBesitzer(zu)) return BESITZER_OK;
        besitzerDazu(g);
        if (!speichernSofort()) return BESITZER_FEHLER;
        besitzerGemeldet(zu, "weitergegeben");
        return BESITZER_OK;
    }

    /** Eigenes Besitzer-Recht abgeben – nur, wenn danach noch ein Besitzer bleibt (nie aussperren). */
    public synchronized int besitzerAbgeben(String id) {
        if (!istBesitzer(id)) return BESITZER_NICHT_ERLAUBT;
        if (besitzerIds().size() < 2) return BESITZER_LETZTER;
        JSONArray r = finde(id).optJSONArray("rechte");
        JSONArray neu = new JSONArray();
        for (int i = 0; i < r.length(); i++) if (!RECHT_BESITZER.equals(r.optString(i))) neu.put(r.optString(i));
        try { finde(id).put("rechte", neu); } catch (Exception e) { return BESITZER_FEHLER; }
        boolean ok = speichernSofort();
        at.greenchili.surfmate.server.BefehlsLog.ereignis("kopplung.besitzer.abgegeben", ok ? "gesetzt" : "fehler", id, anzeigeName(id));
        return ok ? BESITZER_OK : BESITZER_FEHLER;
    }

    /**
     * 0.1.38.8: Besitzer schaltet eigene Rechte aus {@link #BESITZER_SELBST} ein oder aus (nur für sich selbst, nie
     * Fahrzeug-Aktionen, nie andere Kopplungen). Andere Rechte bleiben unverändert.
     */
    public synchronized int besitzerSelbstRechte(String id, java.util.Collection<String> an) {
        if (!istBesitzer(id)) return BESITZER_NICHT_ERLAUBT;
        if (an != null) for (String x : an) if (!BESITZER_SELBST.contains(x)) return BESITZER_UNBEKANNT;
        JSONObject g = finde(id);
        JSONArray alt = g.optJSONArray("rechte");
        JSONArray neu = new JSONArray();
        for (int i = 0; alt != null && i < alt.length(); i++) {
            if (!BESITZER_SELBST.contains(alt.optString(i))) neu.put(alt.optString(i));
        }
        for (String x : BESITZER_SELBST) if (an != null && an.contains(x)) neu.put(x);
        try { g.put("rechte", neu); } catch (Exception e) { return BESITZER_FEHLER; }
        boolean ok = speichernSofort();
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < neu.length(); i++) s.append(i == 0 ? "" : "+").append(neu.optString(i));
        at.greenchili.surfmate.server.BefehlsLog.ereignis("kopplung.rechte=" + s, ok ? "gesetzt" : "fehler", id, anzeigeName(id));
        return ok ? BESITZER_OK : BESITZER_FEHLER;
    }

    /** Wie Telegram „Entfernen“ an „Neues Gerät verbunden“: höchstens so alt darf eine fremde Kopplung sein. */
    public static final long JUNG_MS = 24L * 3600 * 1000;

    /**
     * Besitzer-Handy entfernt eine NEUE fremde Kopplung (jünger als {@link #JUNG_MS}, nicht er selbst, kein Besitzer).
     * Ältere fremde Kopplungen entfernt nur das Display oder der Telegram-Besitzer-Chat.
     */
    public synchronized int jungEntfernen(String von, String ziel, long nowMs) {
        if (!istBesitzer(von) || ziel == null || ziel.equals(von)) return BESITZER_NICHT_ERLAUBT;
        JSONObject g = finde(ziel);
        if (g == null) return BESITZER_UNBEKANNT;
        if (istBesitzer(ziel) || nowMs - g.optLong("angelegt", 0) > JUNG_MS) return BESITZER_NICHT_ERLAUBT;
        return widerrufen(ziel) == UNBEKANNT ? BESITZER_UNBEKANNT : BESITZER_OK;
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
            // 0.1.39 PC über die Brücke: nur solange „Am PC anmelden“ an ist und „Nur heute“ nicht vorbei
            if (ART_PC.equals(g.optString("art")) && (!PcZugang.erlaubt(nowMs)
                    || (g.optLong("ablaufFest", 0) > 0 && nowMs >= g.optLong("ablaufFest", 0)))) return false;
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
        String pushId = null, name = null;
        boolean naehe = false;
        for (int i = 0; i < l.length(); i++) {
            JSONObject g = l.optJSONObject(i);
            if (g != null && id.equals(g.optString("id"))) {
                gefunden = true;
                naehe = !g.optString("naeheSchluessel", "").isEmpty();
                pushId = g.optString("pushId", null);
                name = g.optString("geraeteName", "").isEmpty() ? g.optString("name", null) : g.optString("geraeteName");
                at.greenchili.surfmate.handy.PasskeyFenster.get().schliessen(id);   // 0.1.33
                continue;
            }
            if (g != null) neu.put(g);
        }
        if (!gefunden) {
            at.greenchili.surfmate.server.BefehlsLog.ereignis("kopplung.widerrufen", "unbekannt", id, null);
            return UNBEKANNT;
        }
        geraete = neu;
        at.greenchili.surfmate.ha.HaEreignisse.schliessen(id);   // offene HA-Streams dieser Kopplung sofort zu
        geraeteVergessen(id);   // 0.1.39: gemeldete HA-Geräte dieser Kopplung mit weg
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
        int r = speicher.speichere(kopie(neu)) ? WIDERRUFEN : NUR_IM_SPEICHER;
        // 0.1.38.8: Kopplung mit Nähe-Schlüssel weg → Aufsperren beim Annähern schaltet sich selbst ab
        if (naehe) {
            try {
                at.greenchili.surfmate.handy.Annaeherung.selbstAus("kopplung_entfernt");
            } catch (Throwable ignored) { }
        }
        // 0.1.34 Befehls-Log
        at.greenchili.surfmate.server.BefehlsLog.ereignis("kopplung.widerrufen", r == WIDERRUFEN ? "widerrufen" : "nur_im_speicher",
                id, name);
        return r;
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
                if (g.optLong("ablaufFest", 0) > 0) {   // 0.1.39 PC „Nur heute“
                    o.put("ablaufFest", g.optLong("ablaufFest"));
                    if (nowMs >= g.optLong("ablaufFest")) o.put("abgelaufen", true);
                }
                if (g.optBoolean("einstellungenBestand", false)) o.put("einstellungenBestand", true);   // 0.1.38.8
                if (traegtFremdRechte(g.optString("art"))) o.put("rechte", rechteVon(g));   // 0.1.30
                if (ART_HANDY.equals(g.optString("art"))) {
                    o.put("rechte", rechteVon(g));
                    // nur ob ein Aufsperr-Schlüssel hinterlegt ist, nie der Schlüssel selbst
                    o.put("schluessel", !g.optString("geraeteSchluessel", "").isEmpty());
                    if (g.has("geraeteName")) o.put("geraeteName", g.optString("geraeteName"));
                    if (g.has("plattform")) o.put("plattform", g.optString("plattform"));
                    o.put("push", !g.optString("pushId", "").isEmpty());
                    // 0.1.33 Web-Aufsperren: nur Zustand, nie Schlüssel oder credentialId
                    o.put("nurPasskey", g.optBoolean("nurPasskey", false));
                    if (g.optBoolean("synchronisiert", false)) o.put("synchronisiert", true);
                    // 0.1.38.8 Annäherung: nur ob ein Nähe-Schlüssel gilt, nie Schlüssel oder Bake-Geheimnis
                    o.put("naehe", g.optLong("naeheBis", 0) > nowMs && !g.optString("naeheSchluessel", "").isEmpty());
                    JSONObject f = at.greenchili.surfmate.handy.PasskeyFenster.get().zustand(g.optString("id"), nowMs);
                    if (f != null) o.put("passkeyFenster", f);
                }
            } catch (Exception ignored) { }
            out.put(o);
        }
        return out;
    }

    public static final java.util.Set<String> ARTEN = java.util.Collections.unmodifiableSet(
            new java.util.HashSet<>(java.util.Arrays.asList("cockpit", "homeassistant", "handy", "web", "dienst", "pc")));

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
            // (0.1.32.1), nur der Besitzer schaltet es ein; Bestand behält, was er hat. 0.1.38.2: auch den Launcher,
            // und auch für das Cockpit setzbar (COCKPIT_RECHTE)
            RECHT_UPDATE = "update",
            // 0.1.28 P5 (Koordination 29.10.): Unfall-/Ereignis-Clips aufs Handy laden; Standard AUS, nur der Besitzer
            // schaltet es ein (Login/Display); zusätzlich zu „kamera“
            RECHT_EREIGNISCLIP = "ereignisclip",
            // 0.1.30 Kern Sicherheit: genau EIN Standbild je Wächter-Ereignis (nie Clip, nie Live); Standard AUS
            RECHT_STANDBILD = "standbild",
            // 0.1.34 Befehls-Log (Koordination + Projektinhaber 03.11.): ganzes Protokoll lesen, also auch was ANDERE Geräte am
            // Auto gemacht haben. Standard AUS, nie automatisch (auch nicht für Bestand), nur der Besitzer schaltet es ein.
            // Ohne das Recht sieht ein Handy nur seine eigenen Einträge.
            RECHT_PROTOKOLL = "protokoll",
            // Dateien zwischen Handy und Auto (/api/dateien): Standard AUS, nie automatisch (auch nicht für Bestand),
            // nur der Besitzer schaltet es ein (am Auto oder im Web mit Login).
            RECHT_DATEIEN = "dateien",
            // 0.1.37 „Was zeigt der Tacho?“ (/api/tacho/auswahl, /api/tacho/normal): Standard AUS, nie automatisch
            // (auch nicht für Bestand), nur der Besitzer schaltet es ein. Auch für das Cockpit setzbar.
            RECHT_TACHO = "tacho",
            // 0.1.38 Launcher-Layout lesen und ändern (/api/launcher/v1/layout): Standard AUS, auch für Handy, nie
            // automatisch (auch nicht für Bestand); nur der Besitzer schaltet es unter Gekoppelte Geräte ein.
            RECHT_LAUNCHER = "launcher",
            // 0.1.39 HA-Geräte (HA_VERTRAG v1.1 Teil D, Weg A): Home Assistant darf seine smarten Geräte melden und
            // Freigaben lesen (/api/ha/v1/geraete). Nur Art homeassistant, Standard AUS, nie automatisch.
            RECHT_GERAETE = "geraete",
            // 0.1.38.8 Hupe/Licht/Warnblinker/„Auto finden“ vom Handy (FahrzeugAktionen): Standard AUS, nie automatisch
            // (auch nicht für Bestand), nur der Besitzer schaltet es ein. Wirkt nur, wenn der Schalter der Funktion an ist.
            RECHT_SIGNAL = "signal",
            // 0.1.38.8 HA-Geräte Stufe 2: Home Assistant darf freigegebene Tore und Schlösser schalten (Befehle über den
            // SSE-Stream, Echo /api/ha/v1/geraete/echo). Getrennt vom Lese-Recht, nur Art homeassistant, Standard AUS.
            RECHT_GERAETE_STEUERN = "geraete_steuern",
            // 0.1.38.8 Besitzer-Handy: bündelt alle Lese- und Einstellungsrechte (BESITZER_BUENDEL), nie Fahrzeug-Aktionen.
            // Automatisch nur für die erste Handy-Kopplung am Display (bzw. wenn kein Besitzer mehr da ist) und für die
            // älteste beim Update; sonst Display/Login oder Weitergabe vom Besitzer-Handy. Nur Art handy.
            RECHT_BESITZER = "besitzer",
            // 0.1.38.8 Einstellungen über die Kopplung schreiben (/api/einstellungen): Standard AUS für neue Kopplungen
            // (Regel Nr. 2); Kopplungen von vor 0.1.38.8 behalten es (einstellungenBestand).
            RECHT_EINSTELLUNGEN = "einstellungen";
    public static final java.util.List<String> RECHTE = java.util.Collections.unmodifiableList(
            java.util.Arrays.asList(RECHT_KLIMA, RECHT_ZUSPERREN, RECHT_AUFSPERREN, RECHT_PUSH, RECHT_WEB, RECHT_KAMERA,
                    RECHT_WALLBOX, RECHT_ROUTE, RECHT_UPDATE, RECHT_EREIGNISCLIP, RECHT_STANDBILD, RECHT_PROTOKOLL,
                    RECHT_DATEIEN, RECHT_TACHO, RECHT_LAUNCHER, RECHT_SIGNAL, RECHT_BESITZER, RECHT_EINSTELLUNGEN));
    /**
     * 0.1.38.8: Rechte, die {@code besitzer} mit einschließt. Nie Fahrzeug-Aktionen (klima, zusperren, aufsperren,
     * wallbox und die Aktionen aus Paket B) und nie die Rechte, die nie automatisch kommen dürfen
     * ({@link #BESITZER_SELBST}: Passanten-Bilder, Protokoll, Dateien, Tacho, Launcher) – die schaltet der Besitzer für
     * sich selbst bewusst ein ({@link #besitzerSelbstRechte}).
     */
    public static final java.util.List<String> BESITZER_BUENDEL = java.util.Collections.unmodifiableList(
            java.util.Arrays.asList(RECHT_PUSH, RECHT_WEB, RECHT_ROUTE, RECHT_UPDATE, RECHT_EINSTELLUNGEN));

    /**
     * 0.1.38.8 Sicherheits-Nachzug (Koordination 04.11. Nacht): Fahrzeug-Aktions-Rechte und {@code geraete_steuern}
     * vergibt nur das Display (direkt am Auto). Nie per Kopplungs-Code, nie über {@code /besitzer/rechte}, nie über einen
     * Login aus der Ferne (Brücke, Tailscale); Fehler {@code recht_nur_display}. Entziehen geht überall.
     */
    public static final java.util.List<String> NUR_DISPLAY_RECHTE = java.util.Collections.unmodifiableList(
            java.util.Arrays.asList(RECHT_KLIMA, RECHT_ZUSPERREN, RECHT_AUFSPERREN, RECHT_WALLBOX, RECHT_SIGNAL,
                    RECHT_GERAETE_STEUERN));

    /** Die Rechte aus {@code neu}, die nur das Display vergibt und die {@code id} noch nicht hat (leer = nichts dergleichen). */
    public synchronized java.util.List<String> neueDisplayRechte(String id, java.util.Collection<String> neu) {
        java.util.List<String> hat = rechte(id);
        java.util.List<String> out = new java.util.ArrayList<>();
        if (neu != null) for (String x : neu) if (NUR_DISPLAY_RECHTE.contains(x) && !hat.contains(x) && !out.contains(x)) out.add(x);
        return out;
    }

    /** 0.1.38.8: darf das Besitzer-Handy für sich selbst ein- und ausschalten (mit Rückfrage), nie automatisch. */
    public static final java.util.List<String> BESITZER_SELBST = java.util.Collections.unmodifiableList(
            java.util.Arrays.asList(RECHT_KAMERA, RECHT_STANDBILD, RECHT_EREIGNISCLIP, RECHT_PROTOKOLL, RECHT_DATEIEN,
                    RECHT_TACHO, RECHT_LAUNCHER));
    /**
     * 0.1.30 Kern Sicherheit: Rechte, die auch Kopplungen ohne Handy-Rechte (cockpit, homeassistant, dienst) tragen
     * können. Standard AUS, nie automatisch gesetzt (auch nicht bei der Umstellung); nur der Besitzer schaltet sie ein.
     */
    public static final java.util.List<String> FREMD_RECHTE = java.util.Collections.unmodifiableList(
            java.util.Arrays.asList(RECHT_KAMERA, RECHT_STANDBILD, RECHT_TACHO, RECHT_EINSTELLUNGEN));

    /**
     * 0.1.38: das Cockpit trägt zusätzlich {@code launcher} (Home Assistant und Dienste nie), {@code tacho} wie 0.1.37.
     * 0.1.38.2: auch {@code update} (Panoptes und Launcher aktualisieren, {@code /api/appupdate/fern}). Standard AUS.
     */
    public static final java.util.List<String> COCKPIT_RECHTE = java.util.Collections.unmodifiableList(
            java.util.Arrays.asList(RECHT_KAMERA, RECHT_STANDBILD, RECHT_TACHO, RECHT_LAUNCHER, RECHT_UPDATE,
                    RECHT_EINSTELLUNGEN));

    /**
     * 0.1.38.8: PC/Browser über die Brücke (Login im Browser entwirft eine eigene Sitzung). Vorerst nur Lesen, Web,
     * Einstellungen, Aufnahmen und Protokoll – keine Fahrzeugrechte, kein Besitzer. Alles Standard AUS.
     */
    public static final String ART_PC = "pc";
    public static final java.util.List<String> PC_RECHTE = java.util.Collections.unmodifiableList(
            java.util.Arrays.asList(RECHT_WEB, RECHT_EINSTELLUNGEN, RECHT_KAMERA, RECHT_STANDBILD, RECHT_PROTOKOLL));

    /** 0.1.39: Liste der HA-Geräte löschen, wenn sie von dieser Kopplung stammt. Nie eine Ausnahme nach außen. */
    static void geraeteVergessen(String id) {
        try {
            at.greenchili.surfmate.ha.HaGeraeteSpeicher sp = at.greenchili.surfmate.ha.HaGeraeteSpeicher.get();
            org.json.JSONObject b = sp.lies();
            if (b != null && id != null && id.equals(b.optString("kid"))) sp.loeschen();
        } catch (Throwable ignored) { }
    }

    /**
     * Home Assistant: wie {@link #FREMD_RECHTE} (0.1.38.8 mit {@code einstellungen}), dazu {@code geraete} und
     * {@code geraete_steuern} (Standard AUS).
     */
    public static final java.util.List<String> HA_RECHTE = java.util.Collections.unmodifiableList(
            java.util.Arrays.asList(RECHT_KAMERA, RECHT_STANDBILD, RECHT_TACHO, RECHT_EINSTELLUNGEN, RECHT_GERAETE,
                    RECHT_GERAETE_STEUERN));

    /**
     * 0.1.39 (PLAN_ENTFLECHTEN_0139, Fernzugang): die EINE Quelle „Art → Rechte, die sie tragen kann“ – für Kopplungen,
     * Besitzer-Codes, HA und PC. Was davon nur am Display vergeben werden darf, sagt {@link #NUR_DISPLAY_RECHTE}.
     */
    public static java.util.List<String> rechteDerArt(String art) {
        return ART_HANDY.equals(art) ? RECHTE : fremdRechte(art);
    }

    /** Rechte, die eine Nicht-Handy-Art tragen kann. */
    static java.util.List<String> fremdRechte(String art) {
        if ("homeassistant".equals(art)) return HA_RECHTE;
        if (ART_PC.equals(art)) return PC_RECHTE;
        return "cockpit".equals(art) ? COCKPIT_RECHTE : FREMD_RECHTE;
    }

    /** Arten, die {@link #FREMD_RECHTE} tragen können (web und Alt-Kopplungen ohne Art nie). */
    static boolean traegtFremdRechte(String art) {
        return "cockpit".equals(art) || "homeassistant".equals(art) || ART_DIENST.equals(art) || ART_PC.equals(art);
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
            SCHLUESSEL_KEINE_HANDY_KOPPLUNG = 3, SCHLUESSEL_NUR_PASSKEY = 4;

    /** 0.1.33: Kopplung, die nur mit Passkey aufsperrt. Das Einrichten am Auto schreibt {@code plattform:"web"}. */
    public static final String PLATTFORM_WEB = "web";

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
                if (fremdRechte(g.optString("art")).contains(r.optString(i))) out.put(r.optString(i));
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

    /** 0.1.39: Kopplung der Art {@code pc} (Browser über die Brücke). */
    public synchronized boolean istPc(String id) {
        return ART_PC.equals(art(id));
    }

    /** 0.1.39: alle PC-Kopplungen. */
    public synchronized java.util.List<String> pcIds() {
        java.util.List<String> out = new java.util.ArrayList<>();
        JSONArray l = liste();
        for (int i = 0; i < l.length(); i++) {
            JSONObject g = l.optJSONObject(i);
            if (g != null && ART_PC.equals(g.optString("art"))) out.add(g.optString("id"));
        }
        return out;
    }

    /** 0.1.39: „Am PC anmelden“ ausgeschaltet → alle PC-Kopplungen widerrufen. Anzahl der widerrufenen. */
    public int pcAlleWiderrufen() {
        int n = 0;
        for (String id : pcIds()) if (widerrufen(id) != UNBEKANNT) n++;
        return n;
    }

    /**
     * 0.1.39 (Koordination 04.11.): Das Besitzer-Handy darf jede PC-Kopplung abmelden, egal wie alt (Widerrufen ist die
     * sichere Richtung, das Besitzer-Handy bleibt). Andere Kopplungen nur über {@link #jungEntfernen}.
     */
    public synchronized int pcEntfernen(String von, String ziel) {
        if (!istBesitzer(von) || ziel == null) return BESITZER_NICHT_ERLAUBT;
        if (finde(ziel) == null) return BESITZER_UNBEKANNT;
        if (!istPc(ziel)) return BESITZER_NICHT_ERLAUBT;
        return widerrufen(ziel) == UNBEKANNT ? BESITZER_UNBEKANNT : BESITZER_OK;
    }

    /** Recht einer Kopplung. Nicht-Handy-Arten haben höchstens {@link #FREMD_RECHTE}, nie Handy-Rechte. */
    public synchronized boolean hatRecht(String id, String recht) {
        JSONObject g = finde(id);
        if (g == null) return false;
        JSONArray r = rechteVon(g);
        for (int i = 0; i < r.length(); i++) if (recht.equals(r.optString(i))) return true;
        // 0.1.38.8: der Besitzer hat alle Lese-/Einstellungsrechte, nie Fahrzeug-Aktionen
        return ART_HANDY.equals(g.optString("art")) && enthaelt(r, RECHT_BESITZER) && BESITZER_BUENDEL.contains(recht);
    }

    /** 0.1.38.8 Überführung der Fahrzeug-Aktionen: trägt irgendeine Kopplung dieses Recht? */
    public synchronized boolean irgendeineMitRecht(String recht) {
        JSONArray l = liste();
        for (int i = 0; i < l.length(); i++) {
            JSONObject g = l.optJSONObject(i);
            if (g == null) continue;
            JSONArray r = rechteVon(g);
            for (int j = 0; j < r.length(); j++) if (recht.equals(r.optString(j))) return true;
        }
        return false;
    }

    /** Rechte setzen (nur aus Panoptes-Web mit Login). Unbekannte Rechte werden verworfen. */
    public synchronized boolean rechteSetzen(String id, java.util.Collection<String> rechte) {
        JSONObject g = finde(id);
        if (g == null) return false;
        boolean handy = ART_HANDY.equals(g.optString("art"));
        if (!handy && !traegtFremdRechte(g.optString("art"))) return false;
        JSONArray r = new JSONArray();
        for (String x : handy ? RECHTE : fremdRechte(g.optString("art"))) if (rechte != null && rechte.contains(x)) r.put(x);
        try { g.put("rechte", r).put("rechteStand", RECHTE_STAND); } catch (Exception e) { return false; }
        // 0.1.38.9: Besitzer am Display/mit Login gesetzt = bestätigt, die einmalige Frage ist damit beantwortet
        if (handy && enthaelt(r, RECHT_BESITZER)) {
            JSONArray l = liste();
            for (int i = 0; i < l.length(); i++) {
                JSONObject x = l.optJSONObject(i);
                if (x == null) continue;
                x.remove(FRAGE);
                x.remove("besitzerAusMigration");
            }
        }
        boolean ok = speichernSofort();
        // 0.1.39: Recht „geraete“ weg → gemeldete HA-Geräte (Namen, Räume) dieser Kopplung sofort löschen
        if ("homeassistant".equals(g.optString("art")) && (rechte == null || !rechte.contains(RECHT_GERAETE))) {
            geraeteVergessen(id);
        }
        // 0.1.34 Befehls-Log: welche Rechte jetzt gelten (Namen aus RECHTE, keine Werte)
        StringBuilder neu = new StringBuilder();
        for (int i = 0; i < r.length(); i++) neu.append(i == 0 ? "" : "+").append(r.optString(i));
        at.greenchili.surfmate.server.BefehlsLog.ereignis("kopplung.rechte=" + (neu.length() == 0 ? "keine" : neu),
                ok ? "gesetzt" : "fehler", id, anzeigeName(id));
        return ok;
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
        // 0.1.33: Der Code der Web-App kommt vom Server. Dieser Weg darf den Passkey samt Rückfrage am Auto nie umgehen
        if (PLATTFORM_WEB.equals(plattform) || g.optBoolean("nurPasskey", false)) return SCHLUESSEL_NUR_PASSKEY;
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
        g.remove("credentialId");
        g.remove("rpId");
        g.remove("zaehler");
        g.remove("synchronisiert");
        if (PLATTFORM_WEB.equals(g.optString("plattform"))) g.remove("plattform");
        // 0.1.38.8: der Nähe-Schlüssel hängt an diesem Biometrie-Schlüssel und geht mit
        g.remove("naeheSchluessel");
        g.remove("naeheBis");
        g.remove("naeheBake");
        return speichernSofort();
    }

    // ---------------------------------------------------------------- 0.1.38.8 Aufsperren beim Annähern

    /**
     * Nähe-Schlüssel (P-256, ohne Biometrie je Nutzung) und Bake-Geheimnis hinterlegen; ersetzt einen alten. Nur
     * Handy-Kopplung mit Geräteschlüssel, die Anmeldung selbst ist mit dem Biometrie-Schlüssel signiert (HandyApiHandler).
     */
    public synchronized boolean naeheSetzen(String id, String schluessel, long bisMs, String bakeBase64) {
        JSONObject g = finde(id);
        if (g == null || !ART_HANDY.equals(g.optString("art")) || g.optString("geraeteSchluessel", "").isEmpty()) {
            return false;
        }
        // 0.1.38.8 Integration: das Bake-Geheimnis liegt nur verschlüsselt in der Config (neues Feld, kein Rückfall-Klartext
        // nötig). Klappt das Verschlüsseln nicht, wird nichts gespeichert – nie Klartext.
        String bakeEnc = bakeBase64 == null || bakeBase64.isEmpty() ? "" : bakeVer.apply(bakeBase64);
        if (bakeEnc == null || bakeEnc.isEmpty() || !bakeEnc.startsWith(BAKE_PRAEFIX)) return false;
        try {
            g.put("naeheSchluessel", schluessel).put("naeheBis", bisMs).put("naeheBake", bakeEnc);
        } catch (Exception e) {
            return false;
        }
        boolean ok = speichernSofort();
        at.greenchili.surfmate.server.BefehlsLog.ereignis("kopplung.naehe", ok ? "gesetzt" : "fehler", id, anzeigeName(id));
        return ok;
    }

    /** Gültiger Nähe-Schlüssel oder null (fehlt bzw. abgelaufen). */
    public synchronized String naeheSchluessel(String id, long jetztMs) {
        JSONObject g = finde(id);
        if (g == null || g.optLong("naeheBis", 0) <= jetztMs) return null;
        String s = g.optString("naeheSchluessel", "");
        return s.isEmpty() ? null : s;
    }

    /** Ablauf des Nähe-Schlüssels (0 = keiner). */
    public synchronized long naeheBis(String id) {
        JSONObject g = finde(id);
        return g == null || g.optString("naeheSchluessel", "").isEmpty() ? 0 : g.optLong("naeheBis", 0);
    }

    /** Präfix von {@link at.greenchili.surfmate.byd.cloud.crypto.CredentialCipher}-Werten. */
    static final String BAKE_PRAEFIX = "ENC:";
    /** Ver-/Entschlüsseln des Bake-Geheimnisses; in JVM-Tests ersetzbar (dort fehlt android.util.Base64). */
    static java.util.function.UnaryOperator<String> bakeVer =
            at.greenchili.surfmate.byd.cloud.crypto.CredentialCipher::encrypt;
    static java.util.function.UnaryOperator<String> bakeEnt =
            at.greenchili.surfmate.byd.cloud.crypto.CredentialCipher::decrypt;

    /** Nur Tests: Ver-/Entschlüsseln des Bake-Geheimnisses ersetzen; {@code null} = wieder CredentialCipher. */
    public static void bakeCipherFuerTest(java.util.function.UnaryOperator<String> ver,
                                          java.util.function.UnaryOperator<String> ent) {
        bakeVer = ver != null ? ver : at.greenchili.surfmate.byd.cloud.crypto.CredentialCipher::encrypt;
        bakeEnt = ent != null ? ent : at.greenchili.surfmate.byd.cloud.crypto.CredentialCipher::decrypt;
    }

    /** Bake-Geheimnis (32 Byte) oder null. */
    public synchronized byte[] naeheBake(String id) {
        JSONObject g = finde(id);
        if (g == null) return null;
        try {
            String gespeichert = g.optString("naeheBake", "");
            if (!gespeichert.startsWith(BAKE_PRAEFIX)) return null;   // nur verschlüsselt gültig
            String klar = bakeEnt.apply(gespeichert);
            if (klar == null || klar.isEmpty()) return null;
            byte[] b = java.util.Base64.getDecoder().decode(klar);
            return b.length >= 16 ? b : null;
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized boolean naeheLoeschen(String id) {
        JSONObject g = finde(id);
        if (g == null) return false;
        g.remove("naeheSchluessel");
        g.remove("naeheBis");
        g.remove("naeheBake");
        return speichernSofort();
    }

    // ---------------------------------------------------------------- 0.1.33 Web-Aufsperren

    /** Bestätigten Passkey hinterlegen, first write wins. Nur nach „Stimmt“ am Auto. */
    public synchronized int passkeyHinterlegen(String id, at.greenchili.surfmate.handy.WebAuthn.Anlage a,
                                               String geraeteName) {
        JSONObject g = finde(id);
        if (g == null || !ART_HANDY.equals(g.optString("art"))) return SCHLUESSEL_KEINE_HANDY_KOPPLUNG;
        if (!g.optString("geraeteSchluessel", "").isEmpty()) return SCHLUESSEL_SCHON;
        try {
            g.put("geraeteSchluessel", a.schluessel);
            g.put("plattform", PLATTFORM_WEB);
            g.put("credentialId", a.credentialId);
            g.put("rpId", at.greenchili.surfmate.handy.WebAuthn.RP_ID);
            g.put("zaehler", a.zaehler);
            g.put("nurPasskey", true);
            if (a.synchronisiert) g.put("synchronisiert", true);
            g.put("geraeteName", bereinigeName(geraeteName));
        } catch (Exception e) {
            return SCHLUESSEL_FEHLER;
        }
        return speichernSofort() ? SCHLUESSEL_GESPEICHERT : SCHLUESSEL_FEHLER;
    }

    /** true, wenn diese Kopplung einen Passkey hinterlegt hat. */
    public synchronized boolean istPasskey(String id) {
        JSONObject g = finde(id);
        return g != null && PLATTFORM_WEB.equals(g.optString("plattform"))
                && !g.optString("geraeteSchluessel", "").isEmpty();
    }

    public synchronized String credentialId(String id) {
        JSONObject g = finde(id);
        return g == null ? null : g.optString("credentialId", null);
    }

    public synchronized long zaehler(String id) {
        JSONObject g = finde(id);
        return g == null ? 0L : g.optLong("zaehler", 0L);
    }

    /** Neuer Signaturzähler; false = nicht auf Platte, dann wird nicht aufgesperrt. */
    public synchronized boolean zaehlerSetzen(String id, long zaehler) {
        JSONObject g = finde(id);
        if (g == null) return false;
        try { g.put("zaehler", zaehler); } catch (Exception e) { return false; }
        return speichernSofort();
    }

    public synchronized boolean nurPasskey(String id) {
        JSONObject g = finde(id);
        return g != null && g.optBoolean("nurPasskey", false);
    }

    /**
     * Sperrt den alten Schlüssel-Weg dieser Kopplung. Einschalten darf auch das Gerät selbst (Einbahnstraße),
     * zurücknehmen nur der Besitzer am Auto.
     */
    public synchronized boolean nurPasskeySetzen(String id, boolean an) {
        JSONObject g = finde(id);
        if (g == null || !ART_HANDY.equals(g.optString("art"))) return false;
        try {
            if (an) g.put("nurPasskey", true);
            else g.remove("nurPasskey");
        } catch (Exception e) {
            return false;
        }
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

    static void besitzerTelegram(String name, String wie) {
        String text = at.greenchili.surfmate.server.Messages.text("besitzer.telegram_" + wie,
                "Dein Handy {0} ist jetzt Besitzer-Handy.", NeueKopplungMeldung.anzeigeName(name));
        at.greenchili.surfmate.telegram.TelegramNotifier.sendMessage("📱 " + text, "CRITICAL");
    }

    static void besitzerFrageTelegram(boolean bestaetigen) {
        String text = at.greenchili.surfmate.server.Messages.text(
                bestaetigen ? "besitzer.frage_telegram_bestaetigen" : "besitzer.frage_telegram",
                "Welches Gerät ist dein Handy? Bitte am Auto unter System → Gekoppelte Geräte auswählen.");
        at.greenchili.surfmate.telegram.TelegramNotifier.sendMessage("📱 " + text, "CRITICAL");
    }

    public static Kopplungen get() {
        Kopplungen k = instanz;
        if (k == null) {
            synchronized (Kopplungen.class) {
                if (instanz == null) {
                    // 0.1.38.8: Hinweis an den Telegram-Besitzer, wenn ein Handy Besitzer wird (Name, nie Rechte-Werte)
                    if (besitzerMelder == null) besitzerMelder = Kopplungen::besitzerTelegram;
                    // 0.1.38.9: einmal „Welches Gerät ist dein Handy?“ (am Display/Login beantworten)
                    if (besitzerFrageMelder == null) besitzerFrageMelder = Kopplungen::besitzerFrageTelegram;
                    instanz = new Kopplungen(new UcmSpeicher());
                }
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
