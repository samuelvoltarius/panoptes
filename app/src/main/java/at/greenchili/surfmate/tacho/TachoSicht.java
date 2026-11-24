package at.greenchili.surfmate.tacho;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Pflichtfrage beim ersten Einschalten (0.1.43.20, P4): „Was siehst du am Tacho? – Warnleuchten und Tempo sichtbar?“
 *
 * <p>Herkunft: Ablauf nach DashCast 1.9.80/1.9.81 (MIT-Lizenz, „guided recovery“: Timeout = unbekannt, nie
 * „wiederhergestellt“). Code eigen.
 *
 * <ul>
 *   <li>Startet, sobald das Gate ({@link TachoKohorte#darfSenden}) zum ersten Mal einen öffnenden Befehl für einen
 *       Hardware-Schlüssel ohne Antwort durchlässt (nur im Stand).</li>
 *   <li>„ja“ speichert die Bestätigung für genau diesen Schlüssel.</li>
 *   <li>„nein“, „weiß nicht“ oder keine Antwort in {@link #FRIST_MS}: sofort zurück zur BYD-Anzeige
 *       ({@link TachoNaviApp#tachoNormal}: jede Projektion zu, 18 → 0), die Funktion bleibt für diesen Schlüssel aus.
 *       Keine Antwort zählt als „unbekannt“, nie als „in Ordnung“.</li>
 * </ul>
 * Gespeichert lokal unter {@code tachoSicht.antworten.<schluessel>}; nichts geht an einen Server.
 */
public final class TachoSicht {

    public static final String JA = "ja", NEIN = "nein", UNBEKANNT = "unbekannt";
    static final String SEKTION = "tachoSicht";
    static final long FRIST_MS = 60_000L;

    private TachoSicht() {}

    interface Speicher {
        JSONObject lies();
        boolean schreib(Map<String, Object> werte);
    }

    interface Rueckweg { void zurueck() throws Exception; }

    static volatile Speicher speicher = new Speicher() {
        @Override public JSONObject lies() {
            try {
                JSONObject s = at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig().optJSONObject(SEKTION);
                return s == null ? new JSONObject() : s;
            } catch (Throwable t) { return new JSONObject(); }
        }

        @Override public boolean schreib(Map<String, Object> werte) {
            try {
                return at.greenchili.surfmate.config.UnifiedConfigManager.updateValues(SEKTION, werte);
            } catch (Throwable t) { return false; }
        }
    };

    static volatile Rueckweg rueckweg = () -> {
        try { TachoNaviApp.tachoNormal(); }
        finally { TachoSicht.probe.ende(); }
    };

    /**
     * Kurze Probe-Projektion nach der Tester-Freigabe (DI3/DI4): eine leere Vollprojektion zeigt sofort, ob
     * Warnleuchten und Tempo frei bleiben. Auf DiLink 5 gibt es sie nicht; dort kommt die Frage beim ersten Start.
     */
    interface Probe {
        boolean start();
        void ende();
    }

    static final String PROBE_TOKEN = "sichtprobe";

    static volatile Probe probe = new Probe() {
        @Override public boolean start() {
            try {
                return at.greenchili.surfmate.surveillance.ClusterProjectionController.getInstance()
                        .acquireSustained(PROBE_TOKEN);
            } catch (Throwable t) { return false; }
        }

        @Override public void ende() {
            try {
                at.greenchili.surfmate.surveillance.ClusterProjectionController.getInstance()
                        .releaseSustained(PROBE_TOKEN);
            } catch (Throwable ignored) { }
        }
    };
    /** Zeitgeber, in Tests ersetzbar. */
    static volatile Uhr uhr = System::currentTimeMillis;

    interface Uhr { long jetzt(); }

    private static String offenId;
    private static long offenBis;
    private static Thread waechter;

    /** Läuft gerade eine Frage? (billig, für das Abfragen der Seiten) */
    public static synchronized boolean offen() {
        return offenId != null && uhr.jetzt() < offenBis;
    }

    /** Gespeicherte Antwort für diesen Schlüssel oder "". */
    public static String antwort(String id) {
        if (id == null || id.isEmpty()) return "";
        JSONObject a = speicher.lies().optJSONObject("antworten");
        return a == null ? "" : a.optString(id, "");
    }

    /**
     * Tester-Freigabe gilt nur für genau den Hardware-Schlüssel, für den sie gesetzt wurde. Der Schlüssel enthält
     * Tacho-Firmware, Build und Datei-Hashes: nach einem BYD-Firmware-Update passt er nicht mehr → Freigabe AUS.
     */
    public static boolean testerFreigabe(String id) {
        return id != null && !id.isEmpty() && id.equals(speicher.lies().optString("testerFuer", ""));
    }

    /** Freigabe setzen (an = für diesen Schlüssel) oder zurücknehmen. */
    public static boolean setzeTesterFreigabe(boolean an, String id) {
        if (an && (id == null || id.isEmpty())) return false;
        Map<String, Object> w = new HashMap<>();
        w.put("testerFuer", an ? id : "");
        TachoKohorte.urteilNeu();
        return speicher.schreib(w);
    }

    /**
     * Nach dem Freischalten sofort prüfen: Probe-Projektion starten; das Gate öffnet die Pflichtfrage. Liefert
     * "probe" (Frage kommt gleich), "beim_ersten_start" (DiLink 5 / keine Probe möglich) oder "schon_beantwortet".
     */
    public static String starteSichtprobe(String id) {
        if (!antwort(id).isEmpty()) return "schon_beantwortet";
        return probe.start() ? "probe" : "beim_ersten_start";
    }

    /**
     * Einmaliger Hinweis nach dem Update (0.1.43.20): wer die Tacho-Anzeige schon genutzt hat und dessen Auto nicht
     * geprüft ist, erfährt, warum sie pausiert und wie er sie wieder einschaltet.
     */
    public static boolean pausiertHinweisFaellig(boolean bisherGenutzt, TachoKohorte.Urteil u) {
        return bisherGenutzt && u != null && "hardware_unbekannt".equals(u.code)
                && !speicher.lies().optBoolean("pausiertGesehen", false);
    }

    public static boolean pausiertGesehen() {
        Map<String, Object> w = new HashMap<>();
        w.put("pausiertGesehen", true);
        return speicher.schreib(w);
    }

    /** Hat dieses Auto die Tacho-Projektion schon benutzt? Nur aus eigenen Einstellungen gelesen. */
    static boolean bisherGenutzt(JSONObject config) {
        if (config == null) return false;
        JSONObject navi = config.optJSONObject("tachoNavi");
        if (navi != null) {
            if (navi.optBoolean("an", false) || navi.optLong("hinweisVerstanden", 0L) > 0L) return true;
            JSONObject f = navi.optJSONObject("freigaben");
            if (f != null && f.length() > 0) return true;
        }
        JSONObject bs = config.optJSONObject("blindspot");
        if (bs != null && ("cluster".equals(bs.optString("target", "")) || bs.optInt("clusterSizeProfile", 0) != 0)) {
            return true;
        }
        JSONObject sv = config.optJSONObject("surveillance");
        return sv != null && sv.optInt("clusterSizeProfile", 0) != 0;
    }

    public static boolean bisherGenutzt() {
        try {
            return bisherGenutzt(at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig());
        } catch (Throwable t) { return false; }
    }

    /** Vom Gate: ein öffnender Befehl ging für einen Schlüssel ohne Antwort hinaus → Frage offen, Frist läuft. */
    static synchronized void oeffnungGesendet(String id) {
        if (id == null || id.isEmpty() || !antwort(id).isEmpty()) return;
        if (id.equals(offenId) && uhr.jetzt() < offenBis) return;
        offenId = id;
        offenBis = uhr.jetzt() + FRIST_MS;
        starteWaechter(id, offenBis);
    }

    private static void starteWaechter(final String id, final long bis) {
        Thread t = new Thread(() -> {
            try {
                while (true) {
                    long rest;
                    synchronized (TachoSicht.class) {
                        if (!id.equals(offenId) || offenBis != bis) return;
                        rest = bis - uhr.jetzt();
                    }
                    if (rest <= 0) break;
                    Thread.sleep(Math.min(rest, 1_000L));
                }
                fristAbgelaufen(id, bis);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "TachoSichtFrist");
        t.setDaemon(true);
        waechter = t;
        t.start();
    }

    /** Package-sichtbar für Tests. */
    static void fristAbgelaufen(String id, long bis) {
        synchronized (TachoSicht.class) {
            if (!id.equals(offenId) || offenBis != bis) return;
            offenId = null;
        }
        speichere(id, UNBEKANNT);
        zurueck("frist");
    }

    /**
     * Antwort aus der Oberfläche: ja | nein | unbekannt („weiß nicht“). Ohne offene Frage nur möglich, wenn ein
     * Schlüssel mitkommt (z. B. „neu prüfen“ setzt per {@link #zuruecksetzen}).
     */
    public static JSONObject beantworte(String antwort) throws Exception {
        String a = antwort == null ? "" : antwort.trim().toLowerCase(java.util.Locale.ROOT);
        if (!JA.equals(a) && !NEIN.equals(a) && !UNBEKANNT.equals(a)) {
            return new JSONObject().put("ok", false).put("grund", "ungueltige_antwort");
        }
        String id;
        synchronized (TachoSicht.class) {
            id = offenId;
            if (id == null || uhr.jetzt() >= offenBis) {
                return new JSONObject().put("ok", false).put("grund", "keine_frage_offen");
            }
            offenId = null;
        }
        boolean gespeichert = speichere(id, a);
        // Rückweg außerhalb der Sperre: tachoNormal schließt Projektionen, die wieder ins Gate laufen.
        if (!JA.equals(a)) zurueck(a);
        else probe.ende();   // eine Probe-Projektion endet auch nach „ja“
        return new JSONObject().put("ok", true).put("antwort", a).put("gespeichert", gespeichert)
                .put("zurueck", !JA.equals(a));
    }

    /** „Neu prüfen“: Antwort für den aktuellen Schlüssel löschen. Beim nächsten Einschalten (im Stand) wird gefragt. */
    public static synchronized JSONObject zuruecksetzen(String id) throws Exception {
        if (id == null || id.isEmpty()) return new JSONObject().put("ok", false).put("grund", "kein_schluessel");
        if (id.equals(offenId)) offenId = null;
        JSONObject alt = speicher.lies().optJSONObject("antworten");
        JSONObject neu = alt == null ? new JSONObject() : new JSONObject(alt.toString());
        neu.remove(id);
        Map<String, Object> w = new HashMap<>();
        w.put("antworten", neu);
        TachoKohorte.urteilNeu();
        return new JSONObject().put("ok", speicher.schreib(w));
    }

    public static synchronized JSONObject stand(String id) throws Exception {
        long jetzt = uhr.jetzt();
        boolean offen = offenId != null && jetzt < offenBis;
        return new JSONObject()
                .put("offen", offen)
                .put("restSekunden", offen ? Math.max(0, (offenBis - jetzt + 999) / 1000) : 0)
                .put("antwort", antwort(id))
                .put("frage", "Was siehst du am Tacho?")
                .put("pflicht", "Warnleuchten und Tempo sichtbar?")
                .put("hinweis", "Keine Antwort zählt als „unbekannt“: Panoptes holt dann die BYD-Anzeige zurück "
                        + "und lässt die Funktion aus.");
    }

    private static boolean speichere(String id, String a) {
        try {
            JSONObject alt = speicher.lies().optJSONObject("antworten");
            JSONObject neu = alt == null ? new JSONObject() : new JSONObject(alt.toString());
            neu.put(id, a);
            Map<String, Object> w = new HashMap<>();
            w.put("antworten", neu);
            TachoKohorte.urteilNeu();
            return speicher.schreib(w);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void zurueck(String grund) {
        try {
            rueckweg.zurueck();
        } catch (Throwable t) {
            try {
                at.greenchili.surfmate.logging.DaemonLogger.getInstance("TachoSicht")
                        .warn("Rückweg nach „" + grund + "“ fehlgeschlagen: " + t.getMessage());
            } catch (Throwable ignored) { }
        }
    }

    /** Nur Tests. */
    static synchronized void zuruecksetzenFuerTest() {
        offenId = null;
        offenBis = 0;
        if (waechter != null) waechter.interrupt();
        waechter = null;
    }
}
