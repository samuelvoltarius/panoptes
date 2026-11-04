package at.greenchili.surfmate.byd.routing;

import at.greenchili.surfmate.logging.DaemonLogger;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.1.38.8 Fahrzeug-Aktionen als Nutzerwahl (04.11.2026, REGEL A+ getrennt): Panoptes darf Hupe, Licht,
 * Warnblinker, „Auto finden“ und Aufsperren anbieten – je Funktion ein eigener Schalter, Standard AUS, ausgelöst nur
 * durch eine bewusste Aktion des Nutzers (Tipp + Rückfrage), Fernauslösung nur von einem gekoppelten Gerät mit
 * passendem Recht, jede Auslösung protokolliert und gemeldet, mit Ratenlimit.
 *
 * <p>Ein Wächter im Router ({@link #sperrGrund}, in beiden Eingängen direkt hinter der Fahrsperre): ein außen sichtbarer
 * oder hörbarer Befehl läuft NUR innerhalb von {@link #ausloesen}, und nur, wenn sein Schalter an ist. MQTT, Home
 * Assistant, Automationen, Tastenbelegung, KI, Telegram und Debug öffnen keine Auslösung und sind damit zu – auch
 * jeder neue Weg, der später dazukommt. Ausschalten geht immer und von überall ({@link #allesAus}).
 *
 * <p>Eine reine Hupe ohne Licht gibt es bei dieser Generation nicht: „Auto finden“ ({@code FINDCAR}) hupt und blinkt.
 */
public final class FahrzeugAktionen {

    private static final DaemonLogger logger = DaemonLogger.getInstance("FahrzeugAktionen");

    /** Abschnitt in der Konfiguration. */
    public static final String ABSCHNITT = "fahrzeugAktionen";

    /** Funktionen (= Schalter-Schlüssel im Abschnitt). */
    public static final String AUTO_FINDEN = "autoFinden", LICHT = "licht", WARNBLINKER = "warnblinker",
            AUFSPERREN = "aufsperren";
    /** Automatik „Aufsperren beim Annähern“ (Teil 2); nur mit {@link #AUFSPERREN}. */
    public static final String ANNAEHERUNG = "annaeherung";
    public static final List<String> FUNKTIONEN = Collections.unmodifiableList(
            Arrays.asList(AUTO_FINDEN, LICHT, WARNBLINKER, AUFSPERREN));

    /** Wer auslöst (Protokoll, Meldung). */
    public static final String QUELLE_WEB = "web", QUELLE_HANDY = "handy", QUELLE_HANDYWEB = "handyweb",
            QUELLE_ABSCHRECKUNG = "abschreckung", QUELLE_ANNAEHERUNG = "annaeherung";

    /** Gründe ({@code CommandResult.grund}, Handy-Antwort). */
    public static final String GRUND_AUS = "aktion_aus", GRUND_NUR_NUTZER = "aktion_nur_nutzer",
            GRUND_BESTAETIGUNG = "aktion_bestaetigen", GRUND_RATENLIMIT = "aktion_ratenlimit";

    /** Hupe/Licht/Warnblinker: höchstens 3 je 10 Minuten und 10 je Stunde (zusammen, gleitend, nur im Speicher). */
    public static final int SIGNAL_JE_10_MIN = 3, SIGNAL_JE_STUNDE = 10;
    /** Aufsperren zentral (zusätzlich zum Handy-Ratenlimit je Kopplung): 3 je 10 Minuten, 6 je Stunde. */
    public static final int AUFSPERREN_JE_10_MIN = 3, AUFSPERREN_JE_STUNDE = 6;

    /** Konfiguration, Uhr und Meldung – im Test ersetzt. */
    interface Umgebung {
        JSONObject config();
        boolean speichern(Map<String, Object> werte);
        long jetzt();
        /** Telegram (CRITICAL) und Push an gekoppelte Handys; Fehler still. */
        void melden(String telegramKey, String pushKey, Object... args);
    }

    static volatile Umgebung umgebung = new Echt();

    private static final ThreadLocal<String> AKTIV = new ThreadLocal<>();
    private static final Map<String, Deque<Long>> ZEITEN = new HashMap<>();

    private FahrzeugAktionen() {}

    // ------------------------------------------------------------------ Zuordnung

    /** Welche Funktion ein Befehl außen auslöst, sonst null (Warnblinker AUS ist nie gesperrt). */
    public static String funktion(VehicleCommandRouter.VehicleCommand cmd) {
        if (cmd == null) return null;
        if (cmd instanceof VehicleCommandRouter.FindCarCommand) return AUTO_FINDEN;
        if (cmd instanceof VehicleCommandRouter.FlashLightsCommand) return LICHT;
        if (cmd instanceof VehicleCommandRouter.HazardCommand) {
            return ((VehicleCommandRouter.HazardCommand) cmd).on ? WARNBLINKER : null;
        }
        if (cmd instanceof VehicleCommandRouter.HandyUnlockCommand) return AUFSPERREN;
        return null;
    }

    /** Funktion zum Handy-Aktionsnamen ({@code auto_finden}, {@code licht}, {@code warnblinker}), sonst null. */
    public static String ausHandyAktion(String aktion) {
        if ("auto_finden".equals(aktion)) return AUTO_FINDEN;
        if ("licht".equals(aktion)) return LICHT;
        if ("warnblinker".equals(aktion)) return WARNBLINKER;
        return null;
    }

    // ------------------------------------------------------------------ Schalter

    public static boolean an(String funktion) {
        return an(umgebung.config(), funktion);
    }

    static boolean an(JSONObject root, String funktion) {
        if (funktion == null) return false;
        JSONObject a = root == null ? null : root.optJSONObject(ABSCHNITT);
        if (a == null || !a.optBoolean(funktion, false)) return false;
        // Automatik nur zusammen mit dem Aufsperren-Schalter
        return !ANNAEHERUNG.equals(funktion) || a.optBoolean(AUFSPERREN, false);
    }

    /** Stand aller Schalter für Oberfläche/Handy (nur Wahrheitswerte). */
    public static JSONObject stand() {
        JSONObject root = umgebung.config(), o = new JSONObject();
        try {
            for (String f : FUNKTIONEN) o.put(f, an(root, f));
            o.put(ANNAEHERUNG, an(root, ANNAEHERUNG));
        } catch (Exception ignored) {
        }
        return o;
    }

    /**
     * Sofort alles aus (Telegram {@code /aktionen aus}, Handy {@code aktionen_aus}, Oberfläche). Geht immer, ohne
     * Ratenlimit, ohne Recht. {@code nurAutomatik} = nur „Aufsperren beim Annähern“.
     */
    public static boolean allesAus(boolean nurAutomatik, String wer) {
        Map<String, Object> w = new HashMap<>();
        w.put(ANNAEHERUNG, false);
        if (!nurAutomatik) for (String f : FUNKTIONEN) w.put(f, false);
        boolean ok = umgebung.speichern(w);
        logger.info("Fahrzeug-Aktionen " + (nurAutomatik ? "Automatik" : "alle") + " aus durch " + kennwort(wer)
                + (ok ? "" : " – NICHT gespeichert"));
        try {
            at.greenchili.surfmate.server.BefehlsLog.ereignis("aktionen.aus", ok ? "gesetzt" : "fehler", null, null);
        } catch (Throwable ignored) {
        }
        if (ok) umgebung.melden(nurAutomatik ? "aktionen.automatik_aus" : "aktionen.alle_aus",
                nurAutomatik ? "aktionen.push_automatik_aus" : "aktionen.push_alle_aus", kennwort(wer));
        return ok;
    }

    /**
     * Überführung Bestand (0.1.38.8): Hat schon eine Kopplung das Recht „aufsperren“, bleibt das Handy-Aufsperren an
     * (Schalter einmal an, Merker {@code migriert}). Sonst bleibt alles AUS. Läuft je Gerät genau einmal.
     */
    public static void ueberfuehren(boolean aufsperrRechtVorhanden) {
        ueberfuehren(aufsperrRechtVorhanden, java.util.Collections.<String>emptyList());
    }

    /**
     * Wie {@link #ueberfuehren(boolean)}; zusätzlich einmal der Hinweis, welche Regeln Hupe, Licht oder Warnblinker
     * enthalten ({@link #betroffeneRegeln}). Diese lösen ab 0.1.38.8 nie mehr aus – auch nicht bei eingeschaltetem
     * Schalter, denn Regeln sind keine bewusste Nutzer-Aktion.
     */
    public static void ueberfuehren(boolean aufsperrRechtVorhanden, List<String> regeln) {
        JSONObject a = umgebung.config().optJSONObject(ABSCHNITT);
        if (a != null && a.optBoolean("migriert", false)) return;
        Map<String, Object> w = new HashMap<>();
        w.put("migriert", true);
        if (aufsperrRechtVorhanden && (a == null || !a.has(AUFSPERREN))) w.put(AUFSPERREN, true);
        boolean ok = umgebung.speichern(w);
        if (ok && w.containsKey(AUFSPERREN)) {
            logger.info("Überführung: Aufsperren bleibt an (Kopplung mit Recht aufsperren vorhanden)");
            umgebung.melden("aktionen.ueberfuehrt", "aktionen.push_ueberfuehrt");
        }
        if (ok && regeln != null && !regeln.isEmpty()) {
            logger.info("Überführung: " + regeln.size() + " Regel(n) mit Hupe/Licht/Warnblinker gesperrt");
            umgebung.melden("aktionen.regeln_gesperrt", "aktionen.push_regeln_gesperrt", regelListe(regeln));
        }
    }

    /** Aktions-Typen der Regeln, die außen Sicht-/Hörbares auslösen (Katalog-ids, klein). */
    static final java.util.Set<String> REGEL_TYPEN = java.util.Collections.unmodifiableSet(new java.util.HashSet<>(
            Arrays.asList("flash", "findcar", "find_car", "hazard")));

    /**
     * Namen der Regeln, deren Aktionen (auch Sonst-Zweig, Wenn/Schleife und Aktionsgruppen) Hupe, Licht oder
     * Warnblinker EIN enthalten. Rein: {@code alle} = Regeln (id → Regel), {@code gruppen} = Aktionsgruppen.
     */
    public static List<String> betroffeneRegeln(JSONObject alle, JSONObject gruppen) {
        List<String> r = new java.util.ArrayList<>();
        if (alle == null) return r;
        for (java.util.Iterator<String> it = alle.keys(); it.hasNext(); ) {
            String id = it.next();
            JSONObject regel = alle.optJSONObject(id);
            if (regel == null) continue;
            java.util.Set<String> gesehen = new java.util.HashSet<>();
            if (enthaelt(regel.optJSONArray("actions"), gruppen, gesehen, 0)
                    || enthaelt(regel.optJSONArray("elseActions"), gruppen, gesehen, 0)) {
                String n = regel.optString("name", "").trim();
                r.add(n.isEmpty() ? id : n);
            }
        }
        java.util.Collections.sort(r);
        return r;
    }

    private static boolean enthaelt(org.json.JSONArray aktionen, JSONObject gruppen, java.util.Set<String> gesehen,
                                    int tiefe) {
        if (aktionen == null || tiefe > 8) return false;
        for (int i = 0; i < aktionen.length(); i++) {
            JSONObject a = aktionen.optJSONObject(i);
            if (a == null) continue;
            String typ = a.optString("type", "").trim().toLowerCase(java.util.Locale.ROOT);
            JSONObject v = a.optJSONObject("variables");
            if (REGEL_TYPEN.contains(typ)) {
                // Warnblinker AUS ist harmlos
                if (!"hazard".equals(typ) || v == null || !"off".equals(v.optString("payload", ""))) return true;
            }
            if (enthaelt(a.optJSONArray("childActions"), gruppen, gesehen, tiefe + 1)
                    || enthaelt(a.optJSONArray("elseActions"), gruppen, gesehen, tiefe + 1)) {
                return true;
            }
            if ("actiongroup".equals(typ) && v != null && gruppen != null) {
                String gid = v.optString("groupId", "").trim();
                JSONObject g = gruppen.optJSONObject(gid);
                if (g != null && gesehen.add(gid) && enthaelt(g.optJSONArray("actions"), gruppen, gesehen, tiefe + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Höchstens fünf Namen, Rest als Anzahl („…“), ohne Markdown-Zeichen. */
    static String regelListe(List<String> regeln) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < regeln.size() && i < 5; i++) sb.append(i == 0 ? "" : ", ").append("„").append(name(regeln.get(i))).append("“");
        if (regeln.size() > 5) sb.append(" … (+").append(regeln.size() - 5).append(")");
        return sb.toString();
    }

    /** Je Funktion höchstens ein Hinweis am Tag, wenn eine Regel auf die Sperre läuft. */
    private static final Map<String, Long> REGEL_HINWEIS = new HashMap<>();
    static final long REGEL_HINWEIS_MS = 24 * 3_600_000L;

    /** true = jetzt melden (und gemerkt). */
    static boolean regelHinweisFaellig(String funktion, long jetzt) {
        synchronized (REGEL_HINWEIS) {
            Long z = REGEL_HINWEIS.get(funktion);
            if (z != null && jetzt - z < REGEL_HINWEIS_MS) return false;
            REGEL_HINWEIS.put(funktion, jetzt);
            return true;
        }
    }

    // ------------------------------------------------------------------ Router-Wächter

    /** Router: null = darf laufen, sonst Grund ({@link #GRUND_AUS}, {@link #GRUND_NUR_NUTZER}). */
    static String sperrGrund(VehicleCommandRouter.VehicleCommand cmd) {
        String f = funktion(cmd);
        if (f == null) return null;
        String aktiv = AKTIV.get();
        // Wächter-Abschreckung: eigener Schalter (deterrentAction, Standard silent) ist die Zustimmung
        if ((f + "@" + QUELLE_ABSCHRECKUNG).equals(aktiv)) return null;
        if (!an(f)) return GRUND_AUS;
        if (aktiv == null || !aktiv.equals(f)) return GRUND_NUR_NUTZER;
        return null;
    }

    /** Router-Antwort für einen gesperrten Befehl. */
    static VehicleCommandRouter.CommandResult gesperrt(VehicleCommandRouter.VehicleCommand cmd, String grund) {
        String herkunft = AssistenzHinweis.herkunft();
        logger.warn("Gesperrt '" + cmd.name() + "' – " + grund + " (Herkunft " + herkunft + ")");
        // Eine Regel (auch neu angelegt) lief auf die Sperre: einmal am Tag je Funktion erklären, warum nichts passiert
        String f = funktion(cmd);
        if ("automation".equals(herkunft) && f != null && regelHinweisFaellig(f, umgebung.jetzt())) {
            umgebung.melden("aktionen.regel_lief", "aktionen.push_regel_lief", text(f));
        }
        return VehicleCommandRouter.CommandResult.aktionGesperrt(grund);
    }

    // ------------------------------------------------------------------ Auslösen

    /** Führt einen Befehl in der Auslösung aus (im Test ersetzt). */
    public interface Ausfuehrer {
        VehicleCommandRouter.CommandResult ausfuehren(VehicleCommandRouter.VehicleCommand cmd);
    }

    /**
     * Einziger Weg für einen außen sichtbaren Befehl. Prüft Schalter, Bestätigung (Tipp + Rückfrage) und das zentrale
     * Ratenlimit, öffnet die Auslösung nur für diesen einen Befehl, schreibt das Protokoll und meldet jede Auslösung
     * (auch abgelehnte nach bestandener Bestätigung) per Telegram und Push.
     *
     * @param wer Anzeigename des Geräts bzw. {@code Display}/{@code Browser} – nur für die Meldung, nie ins Protokoll
     */
    public static VehicleCommandRouter.CommandResult ausloesen(VehicleCommandRouter.VehicleCommand cmd, String quelle,
                                                                String wer, boolean bestaetigt, Ausfuehrer router) {
        String f = funktion(cmd);
        if (f == null) return router.ausfuehren(cmd);
        String q = kennwort(quelle);
        boolean abschreckung = QUELLE_ABSCHRECKUNG.equals(q);
        if (abschreckung && !AUTO_FINDEN.equals(f) && !LICHT.equals(f)) return protokoll(f, q, GRUND_NUR_NUTZER, null);
        if (QUELLE_ANNAEHERUNG.equals(q) && (!AUFSPERREN.equals(f) || !an(ANNAEHERUNG))) {
            return protokoll(f, q, GRUND_AUS, null);
        }
        if (!abschreckung && !an(f)) return protokoll(f, q, GRUND_AUS, null);
        // Abschreckung und Automatik bestätigen nicht im Moment: ihr eigener Schalter ist die Zustimmung
        boolean automatisch = abschreckung || QUELLE_ANNAEHERUNG.equals(q);
        if (!bestaetigt && !automatisch) return protokoll(f, q, GRUND_BESTAETIGUNG, null);
        if (!abschreckung && !zaehlen(f, umgebung.jetzt())) {
            umgebung.melden("aktionen.abgelehnt", "aktionen.push_abgelehnt", text(f), name(wer), GRUND_RATENLIMIT);
            return protokoll(f, q, GRUND_RATENLIMIT, null);
        }
        String vorher = AKTIV.get();
        AKTIV.set(abschreckung ? f + "@" + QUELLE_ABSCHRECKUNG : f);
        VehicleCommandRouter.CommandResult r;
        try {
            r = router.ausfuehren(cmd);
        } finally {
            if (vorher == null) AKTIV.remove();
            else AKTIV.set(vorher);
        }
        protokoll(f, q, null, r);
        // Abschreckung meldet der Wächter selbst (Ereignis), sonst käme bei jeder Bewegung Telegram; das Handy-Aufsperren
        // meldet HandyApiHandler je Versuch selbst (handy.aufsperren_*), die Annäherung meldet Annaeherung samt 60-s-Hinweis
        if (!abschreckung && !QUELLE_ANNAEHERUNG.equals(q) && !(AUFSPERREN.equals(f) && QUELLE_HANDY.equals(q))) {
            boolean ok = r != null && r.outcome == VehicleCommandRouter.Outcome.SUCCESS;
            umgebung.melden(ok ? "aktionen.ausgeloest" : "aktionen.fehlgeschlagen",
                    ok ? "aktionen.push_ausgeloest" : "aktionen.push_fehlgeschlagen", text(f), name(wer),
                    r == null || r.outcome == null ? "null" : r.outcome.name().toLowerCase(java.util.Locale.ROOT));
        }
        return r;
    }

    /** Gleitendes Fenster je Gruppe (Signal bzw. Aufsperren); true = erlaubt und gezählt. */
    static boolean zaehlen(String funktion, long jetzt) {
        boolean auf = AUFSPERREN.equals(funktion);
        String gruppe = auf ? AUFSPERREN : "signal";
        int je10 = auf ? AUFSPERREN_JE_10_MIN : SIGNAL_JE_10_MIN, jeStunde = auf ? AUFSPERREN_JE_STUNDE : SIGNAL_JE_STUNDE;
        synchronized (ZEITEN) {
            Deque<Long> d = ZEITEN.computeIfAbsent(gruppe, k -> new ArrayDeque<>());
            while (!d.isEmpty() && d.peekFirst() <= jetzt - 3_600_000L) d.pollFirst();
            if (d.size() >= jeStunde) return false;
            int in10 = 0;
            for (long t : d) if (t > jetzt - 600_000L) in10++;
            if (in10 >= je10) return false;
            d.addLast(jetzt);
            return true;
        }
    }

    /**
     * Nur für Tests (JVM, nie am Auto): Konfiguration im Speicher statt der Datei, keine Meldungen, Ratenlimit leer.
     * {@code null} stellt die echte Umgebung wieder her. Rückgabe: die Telegram-Schlüssel der Meldungen, in Reihenfolge.
     */
    public static List<String> fuerTest(JSONObject config) {
        return fuerTest(config, System::currentTimeMillis);
    }

    /** Wie {@link #fuerTest(JSONObject)}, mit eigener Uhr (Ratenlimit). */
    public static List<String> fuerTest(JSONObject config, java.util.function.LongSupplier uhr) {
        zuruecksetzen();
        List<String> gemeldet = Collections.synchronizedList(new java.util.ArrayList<>());
        if (config == null) {
            umgebung = new Echt();
            return gemeldet;
        }
        umgebung = new Umgebung() {
            public JSONObject config() { return config; }
            public boolean speichern(Map<String, Object> werte) {
                try {
                    JSONObject a = config.optJSONObject(ABSCHNITT);
                    if (a == null) config.put(ABSCHNITT, a = new JSONObject());
                    for (Map.Entry<String, Object> e : werte.entrySet()) a.put(e.getKey(), e.getValue());
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
            public long jetzt() { return uhr.getAsLong(); }
            public void melden(String telegramKey, String pushKey, Object... args) { gemeldet.add(telegramKey); }
        };
        return gemeldet;
    }

    /** Nur für Tests: Ratenlimit leeren. */
    static void zuruecksetzen() {
        synchronized (ZEITEN) {
            ZEITEN.clear();
        }
        synchronized (REGEL_HINWEIS) {
            REGEL_HINWEIS.clear();
        }
    }

    /** Protokoll (nur Kennwörter) und die Antwort bei Ablehnung. */
    private static VehicleCommandRouter.CommandResult protokoll(String f, String quelle, String grund,
                                                                VehicleCommandRouter.CommandResult r) {
        String ergebnis = grund != null ? grund
                : r == null || r.outcome == null ? "null" : r.outcome.name().toLowerCase(java.util.Locale.ROOT);
        try {
            at.greenchili.surfmate.server.BefehlsLog.ereignis("aktion." + f + "." + quelle, ergebnis, null, null);
        } catch (Throwable ignored) {
        }
        logger.info("Aktion " + f + " über " + quelle + ": " + ergebnis);
        return grund == null ? r : VehicleCommandRouter.CommandResult.aktionGesperrt(grund);
    }

    private static String kennwort(String s) {
        return s == null || !s.matches("[a-z0-9_]{1,24}") ? "unbekannt" : s;
    }

    private static String name(String wer) {
        if (wer == null || wer.trim().isEmpty()) return "?";
        String w = wer.replaceAll("[\\r\\n*_`\\[\\]]", " ").trim();
        return w.length() > 40 ? w.substring(0, 40) : w;
    }

    /** Sichtbarer Name der Funktion (Katalog {@code aktionen.name.*}). */
    private static String text(String f) {
        try {
            return at.greenchili.surfmate.server.Messages.get("aktionen.name." + f);
        } catch (Throwable t) {
            return f;
        }
    }

    // ------------------------------------------------------------------ echte Umgebung

    static final class Echt implements Umgebung {
        public JSONObject config() {
            try {
                return at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig();
            } catch (Throwable t) {
                return new JSONObject();
            }
        }

        public boolean speichern(Map<String, Object> werte) {
            try {
                return at.greenchili.surfmate.config.UnifiedConfigManager.updateValues(ABSCHNITT, werte);
            } catch (Throwable t) {
                return false;
            }
        }

        public long jetzt() {
            return System.currentTimeMillis();
        }

        public void melden(String telegramKey, String pushKey, Object... args) {
            try {
                at.greenchili.surfmate.telegram.TelegramNotifier.sendMessage(
                        at.greenchili.surfmate.telegram.TelegramMessages.get(telegramKey, args), "CRITICAL");
            } catch (Throwable ignored) {
            }
            try {
                at.greenchili.surfmate.notifications.NotificationBus.get().publish(
                        new at.greenchili.surfmate.notifications.NotificationEvent(PUSH_KATEGORIE,
                                at.greenchili.surfmate.notifications.NotificationEvent.Severity.CRITICAL,
                                at.greenchili.surfmate.server.Messages.get(pushKey, args), "",
                                "aktion-" + System.currentTimeMillis(), "/local/vehicle-control.html", new JSONObject()));
            } catch (Throwable ignored) {
            }
        }
    }

    /** Meldung über dieselbe Umgebung (Telegram CRITICAL + Push), z. B. für {@code Annaeherung}. */
    public static void melden(String telegramKey, String pushKey, Object... args) {
        umgebung.melden(telegramKey, pushKey, args);
    }

    /** Push-Kategorie; der TelegramSink lässt sie aus (Telegram geht direkt). */
    public static final String PUSH_KATEGORIE = "vehicle.security.aktion";
}
