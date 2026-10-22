package at.greenchili.surfmate.gesundheit;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Dauerbetrieb (21.10.2026: „der Wächter muss tagelang ohne Eingriff laufen“). Jede Minute echte
 * Prüfungen statt „Prozess lebt“, gestufte Selbstheilung mit Backoff. Rein: Fakten rein, Entscheidung raus.
 *
 * <p>Befunde: Wächter soll scharf, ist es aber nicht (nach {@link #SCHARF_GNADE_MS}); keine Kamerabilder seit
 * {@link #BILD_MAX_MS} bei scharfem Wächter; KI verlangt, aber nicht geladen; Telemetrie seit
 * {@link #TELEMETRIE_MAX_MS} nicht veröffentlicht (nur wenn MQTT eingerichtet); MQTT getrennt.
 *
 * <p>Stufen: {@link Stufe#SCHARF} neu scharf schalten → {@link Stufe#DAEMON} Kamera-Daemon trip-sicher neu →
 * {@link Stufe#APP} App-Neustart beim sm-watchdog anfordern (letzte Stufe; Stufe „Shell-Ausführer neu“ liegt im
 * App-Prozess). Eine Stufe wird erst genommen, wenn die vorige ihren Abstand hatte und nicht geholfen hat.
 * Höchstens {@link #MAX_JE_STUNDE} Eingriffe je Stunde; nie während Deploy-Sperre/Update.
 */
public final class Gesundheit {

    static final long SCHARF_GNADE_MS = 2 * 60_000L;
    static final long BILD_MAX_MS = 60_000L;
    static final long TELEMETRIE_MAX_MS = 2 * 60_000L;
    static final int MAX_JE_STUNDE = 6;
    /** Mindestabstand, bevor nach einer Stufe die nächste versucht wird. */
    static final long[] STUFEN_ABSTAND_MS = {0, 2 * 60_000L, 10 * 60_000L, 30 * 60_000L};

    public enum Stufe { KEINE, SCHARF, DAEMON, APP }

    /** Momentaufnahme, vom Daemon gesammelt. */
    public static final class Fakten {
        public long now;
        public boolean sperre;              // Deploy-Sperre oder Update läuft
        public boolean sollScharf;          // gewollt und nicht unterdrückt (Stopp, sicherer Ort, Zeitplan)
        public boolean istScharf;
        public long sollScharfSeit = -1;    // seit wann soll und nicht ist
        public long letztesBild;            // ms, 0 = nie
        public boolean kiVerlangt, kiGeladen;
        public boolean mqttEingerichtet, mqttVerbunden;
        public long letzteTelemetrie;       // ms, 0 = nie
    }

    public static final class Entscheidung {
        public final List<String> befunde;
        public final Stufe stufe;
        public final String grund;
        Entscheidung(List<String> befunde, Stufe stufe, String grund) {
            this.befunde = befunde; this.stufe = stufe; this.grund = grund;
        }
        public boolean gesund() { return befunde.isEmpty(); }
    }

    private Stufe letzteStufe = Stufe.KEINE;
    private long letzterEingriff = -1;
    private final ArrayDeque<Long> eingriffe = new ArrayDeque<>();
    private long ausfallSeit = -1;

    /** Nur die Befunde (für Status/MQTT). */
    public static List<String> befunde(Fakten f) {
        List<String> b = new ArrayList<>();
        if (f.sollScharf && !f.istScharf && f.sollScharfSeit >= 0 && f.now - f.sollScharfSeit >= SCHARF_GNADE_MS) {
            b.add("nicht_scharf");
        }
        if (f.istScharf && (f.letztesBild <= 0 || f.now - f.letztesBild > BILD_MAX_MS)) b.add("keine_bilder");
        if (f.istScharf && f.kiVerlangt && !f.kiGeladen) b.add("ki_fehlt");
        if (f.mqttEingerichtet && !f.mqttVerbunden) b.add("mqtt_getrennt");
        if (f.mqttEingerichtet && f.mqttVerbunden
                && (f.letzteTelemetrie <= 0 || f.now - f.letzteTelemetrie > TELEMETRIE_MAX_MS)) {
            b.add("telemetrie_alt");
        }
        return b;
    }

    /** Ein Takt: Befunde + ggf. Eingriffsstufe. Der Aufrufer führt die Stufe aus und meldet sie. */
    public synchronized Entscheidung takt(Fakten f) {
        while (!eingriffe.isEmpty() && f.now - eingriffe.peekFirst() >= 3600_000L) eingriffe.pollFirst();
        List<String> b = befunde(f);
        boolean waechterAus = b.contains("nicht_scharf") || b.contains("keine_bilder");
        if (b.isEmpty()) {
            letzteStufe = Stufe.KEINE;
            return new Entscheidung(b, Stufe.KEINE, null);
        }
        if (waechterAus && ausfallSeit < 0) ausfallSeit = f.now;
        if (f.sperre) return new Entscheidung(b, Stufe.KEINE, "sperre");
        if (eingriffe.size() >= MAX_JE_STUNDE) return new Entscheidung(b, Stufe.KEINE, "obergrenze");

        Stufe ziel = zielStufe(b);
        if (ziel == Stufe.KEINE) return new Entscheidung(b, Stufe.KEINE, null);
        // Leiter: nach einem Eingriff erst den Abstand abwarten, dann eine Stufe höher (höchstens APP).
        Stufe naechste;
        if (letzteStufe == Stufe.KEINE) {
            naechste = ziel;
        } else {
            long abstand = STUFEN_ABSTAND_MS[letzteStufe.ordinal()];
            if (f.now - letzterEingriff < abstand) return new Entscheidung(b, Stufe.KEINE, "abwarten");
            naechste = Stufe.values()[Math.min(Stufe.APP.ordinal(), Math.max(ziel.ordinal(), letzteStufe.ordinal() + 1))];
        }
        letzteStufe = naechste;
        letzterEingriff = f.now;
        eingriffe.addLast(f.now);
        return new Entscheidung(b, naechste, String.join(",", b));
    }

    /** Erste sinnvolle Stufe je Befund. */
    static Stufe zielStufe(List<String> b) {
        if (b.contains("nicht_scharf")) return Stufe.SCHARF;
        if (b.contains("keine_bilder") || b.contains("ki_fehlt")) return Stufe.DAEMON;
        if (b.contains("mqtt_getrennt") || b.contains("telemetrie_alt")) return Stufe.KEINE;   // MQTT heilt sich selbst; nur melden
        return Stufe.KEINE;
    }

    /** Wächter war seit … aus (für „X min aus, repariert“), -1 wenn nicht. Nach Gesundung zurücksetzen. */
    public synchronized long ausfallSeit() { return ausfallSeit; }

    public synchronized long gesundGeworden() {
        long s = ausfallSeit;
        ausfallSeit = -1;
        return s;
    }

    public synchronized int eingriffeLetzteStunde() { return eingriffe.size(); }

    /** Leiterstand sichern (überlebt den Daemon-Neustart der Stufe DAEMON). */
    public synchronized JSONObject sichern() {
        JSONObject o = new JSONObject();
        try {
            o.put("stufe", letzteStufe.name());
            o.put("eingriff", letzterEingriff);
            o.put("ausfallSeit", ausfallSeit);
            o.put("eingriffe", new JSONArray(new ArrayList<>(eingriffe)));
        } catch (Exception ignored) {
        }
        return o;
    }

    /** Gesicherten Stand laden; Unsinn wird ignoriert. */
    public synchronized void laden(JSONObject o, long now) {
        if (o == null) return;
        try {
            Stufe s = Stufe.valueOf(o.optString("stufe", "KEINE"));
            long e = o.optLong("eingriff", -1);
            if (e > now || now - e > 3600_000L) return;   // alt oder Uhr verstellt: frisch anfangen
            letzteStufe = s;
            letzterEingriff = e;
            ausfallSeit = o.optLong("ausfallSeit", -1);
            JSONArray a = o.optJSONArray("eingriffe");
            for (int i = 0; a != null && i < a.length(); i++) {
                long t = a.optLong(i, -1);
                if (t > 0 && t <= now && now - t < 3600_000L) eingriffe.addLast(t);
            }
        } catch (Exception ignored) {
        }
    }

    public synchronized Stufe letzteStufe() { return letzteStufe; }

    /** JSON für GET /api/gesundheit und MQTT health/state. */
    public static JSONObject alsJson(Entscheidung e, Fakten f, Gesundheit g) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", e.gesund());
            o.put("befunde", new JSONArray(e.befunde));
            o.put("scharf", f.istScharf);
            o.put("sollScharf", f.sollScharf);
            o.put("bildAlterS", f.letztesBild > 0 ? (f.now - f.letztesBild) / 1000 : JSONObject.NULL);
            o.put("eingriffeStunde", g.eingriffeLetzteStunde());
            o.put("letzteStufe", g.letzteStufe().name().toLowerCase(java.util.Locale.ROOT));
            o.put("ts", f.now / 1000);
        } catch (Exception ignored) {
        }
        return o;
    }
}
