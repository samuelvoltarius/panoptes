package at.greenchili.surfmate.charging;

import at.greenchili.surfmate.logging.DaemonLogger;
import at.greenchili.surfmate.monitor.SocHistoryDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Laufzeit der Ladesäulen-Erkennung (Vorschlag v2, 03.11.2026). Die Entscheidungen stehen rein in
 * {@link #bearbeite} und {@link #nachtragen}; Netz, Config und Datenbank kommen über kleine Schnittstellen.
 *
 * <ul>
 *   <li>Genau eine Anfrage je Ladeende ({@link #nachLadeende}), asynchron. Aktiv nur im Daemon
 *       ({@link #aktivieren}), in Tests nie.</li>
 *   <li>Schalter {@code laden.saeuleNachschlagen} (ab Werk an): aus = keine Anfrage.</li>
 *   <li>Keine Anfrage ohne Position, an privaten Orten, „zuhause“ und in Ruhezonen.</li>
 *   <li>Gemerkte Säule (Ort mit Tarif über „Säule merken“) gewinnt ohne Anfrage.</li>
 *   <li>Alte Ladungen nur per Knopf ({@link #nachtragen}): je Planer-Zelle (0,01°) eine Anfrage.</li>
 * </ul>
 * Logs nennen nur die Ladungs-ID und das Ergebnis, nie Koordinaten, Namen oder Preise.
 */
public final class LadesaeuleDienst {

    private LadesaeuleDienst() {}

    private static final DaemonLogger logger = DaemonLogger.getInstance("Ladesaeule");

    public static final String SCHALTER = "saeuleNachschlagen";
    public static final String ABSCHNITT_GEMERKT = "ladesaeule";
    /** Höchstens so viele Planer-Anfragen je Tipp auf „nachtragen“. */
    public static final int MAX_NACHTRAG_ANFRAGEN = 10;

    /** Planer {@code POST umkreis}; null = nicht erreichbar. */
    public interface Planer {
        JSONObject umkreis(JSONObject body);
    }

    /** Config, Orte, Ruhezonen. */
    public interface Umgebung {
        JSONObject config();
        JSONArray orte();
        JSONArray ruhe();
    }

    /** Ergebnis für eine Ladung; {@code angefragt} = der Planer wurde gefragt. */
    public static final class Ergebnis {
        public final String zuordnung;
        public final String stationId, name, betreiber, art;
        public final String kandidatenJson;
        public final boolean angefragt;

        Ergebnis(String zuordnung, String stationId, String name, String betreiber, String art, String kandidatenJson,
                 boolean angefragt) {
            this.zuordnung = zuordnung;
            this.stationId = stationId;
            this.name = name;
            this.betreiber = betreiber;
            this.art = art;
            this.kandidatenJson = kandidatenJson;
            this.angefragt = angefragt;
        }

        static Ergebnis ohne(String zuordnung, boolean angefragt) {
            return new Ergebnis(zuordnung, null, null, null, null, null, angefragt);
        }

        /** null = nichts speichern (z. B. Schalter aus). */
        boolean speichern() {
            return zuordnung != null;
        }
    }

    private static volatile boolean aktiv;
    static volatile Planer planer = LadesaeuleDienst::echtUmkreis;

    /** Im Daemon beim Serverstart. */
    public static void aktivieren() {
        aktiv = true;
    }

    public static boolean schalterAn(JSONObject cfg) {
        JSONObject l = cfg == null ? null : cfg.optJSONObject("laden");
        return l == null || !l.has(SCHALTER) || l.isNull(SCHALTER) || l.optBoolean(SCHALTER, true);
    }

    // ------------------------------------------------------------------ rein

    /**
     * Eine Ladung bearbeiten. Ergebnis mit {@code zuordnung == null}: nichts tun (Schalter aus oder schon erledigt).
     * Der Planer wird höchstens einmal gefragt.
     */
    public static Ergebnis bearbeite(SocHistoryDatabase.StationZeile z, JSONObject cfg, JSONArray orte, JSONArray ruhe,
                                     Planer p) {
        if (z == null) return Ergebnis.ohne(null, false);
        if (!z.zuordnung.isEmpty() && !LadesaeuleZuordnung.FEHLER.equals(z.zuordnung)) return Ergebnis.ohne(null, false);
        JSONObject g = LadesaeuleZuordnung.gemerkt(gemerktListe(cfg), z.tariffId, z.isDc);
        if (g != null) {
            return new Ergebnis(LadesaeuleZuordnung.GEMERKT, g.optString("id", ""), g.optString("name", ""),
                    g.optString("betreiber", ""), g.optString("art", ""), null, false);
        }
        if (!schalterAn(cfg)) return Ergebnis.ohne(null, false);
        if (z.lat == 0 && z.lng == 0) return Ergebnis.ohne(null, false);
        if (LadesaeuleZuordnung.privat(orte, ruhe, z.lat, z.lng)) return Ergebnis.ohne(LadesaeuleZuordnung.PRIVAT, false);
        JSONObject antwort;
        try {
            antwort = p == null ? null : p.umkreis(LadesaeuleZuordnung.anfrage(z.lat, z.lng, karten(cfg)));
        } catch (Throwable t) {
            antwort = null;
        }
        if (antwort == null) return Ergebnis.ohne(LadesaeuleZuordnung.FEHLER, true);
        return auswerten(z, LadesaeuleZuordnung.ausPlaner(antwort));
    }

    static Ergebnis auswerten(SocHistoryDatabase.StationZeile z, List<LadesaeuleZuordnung.Station> stationen) {
        LadesaeuleZuordnung.Ergebnis e = LadesaeuleZuordnung.zuordnen(stationen, z.lat, z.lng, z.isDc, z.peakKw);
        String kand;
        try {
            kand = e.kandidatenJson().toString();
        } catch (Exception ex) {
            kand = "[]";
        }
        if (e.station == null) return new Ergebnis(LadesaeuleZuordnung.KEINE, null, null, null, null, kand, true);
        LadesaeuleZuordnung.Station s = e.station.station;
        return new Ergebnis(e.zuordnung, s.id, s.name, s.betreiber, s.art, kand, true);
    }

    /**
     * Knopf „Ladestationen nachtragen“: offene Ladungen nach Planer-Zelle gruppieren, je Zelle eine Anfrage, höchstens
     * {@code maxAnfragen}. Schalter aus = nichts. Private Orte werden als „privat“ markiert, ohne Anfrage.
     */
    public static Map<Long, Ergebnis> nachtragen(List<SocHistoryDatabase.StationZeile> offen, JSONObject cfg,
                                                 JSONArray orte, JSONArray ruhe, Planer p, int maxAnfragen) {
        Map<Long, Ergebnis> aus = new LinkedHashMap<>();
        if (offen == null || !schalterAn(cfg)) return aus;
        Map<String, List<SocHistoryDatabase.StationZeile>> zellen = new LinkedHashMap<>();
        for (SocHistoryDatabase.StationZeile z : offen) {
            JSONObject g = LadesaeuleZuordnung.gemerkt(gemerktListe(cfg), z.tariffId, z.isDc);
            if (g != null) {
                aus.put(z.id, new Ergebnis(LadesaeuleZuordnung.GEMERKT, g.optString("id", ""), g.optString("name", ""),
                        g.optString("betreiber", ""), g.optString("art", ""), null, false));
                continue;
            }
            if (z.lat == 0 && z.lng == 0) continue;
            if (LadesaeuleZuordnung.privat(orte, ruhe, z.lat, z.lng)) {
                aus.put(z.id, Ergebnis.ohne(LadesaeuleZuordnung.PRIVAT, false));
                continue;
            }
            String k = LadesaeuleZuordnung.zelle(z.lat, z.lng);
            List<SocHistoryDatabase.StationZeile> l = zellen.get(k);
            if (l == null) {
                l = new ArrayList<>();
                zellen.put(k, l);
            }
            l.add(z);
        }
        int n = 0;
        for (List<SocHistoryDatabase.StationZeile> l : zellen.values()) {
            if (n >= maxAnfragen) break;
            n++;
            SocHistoryDatabase.StationZeile erste = l.get(0);
            JSONObject antwort;
            try {
                antwort = p == null ? null : p.umkreis(LadesaeuleZuordnung.anfrage(erste.lat, erste.lng, karten(cfg)));
            } catch (Throwable t) {
                antwort = null;
            }
            List<LadesaeuleZuordnung.Station> st = antwort == null ? null : LadesaeuleZuordnung.ausPlaner(antwort);
            for (SocHistoryDatabase.StationZeile z : l) {
                aus.put(z.id, st == null ? Ergebnis.ohne(LadesaeuleZuordnung.FEHLER, true) : auswerten(z, st));
            }
        }
        return aus;
    }

    static JSONArray gemerktListe(JSONObject cfg) {
        JSONObject s = cfg == null ? null : cfg.optJSONObject(ABSCHNITT_GEMERKT);
        JSONArray a = s == null ? null : s.optJSONArray("gemerkt");
        return a == null ? new JSONArray() : a;
    }

    static JSONArray karten(JSONObject cfg) {
        JSONObject l = cfg == null ? null : cfg.optJSONObject("laden");
        return l == null ? null : l.optJSONArray("karten");
    }

    /** Gemerkte Säule eintragen (rein): ersetzt einen Eintrag mit gleichem Tarif. */
    public static JSONArray merke(JSONArray alt, String tarifId, String stationId, String name, String betreiber,
                                  String art) throws Exception {
        JSONArray neu = new JSONArray();
        for (int i = 0; alt != null && i < alt.length(); i++) {
            JSONObject g = alt.optJSONObject(i);
            if (g != null && !tarifId.equals(g.optString("tarif", ""))) neu.put(g);
        }
        JSONObject g = new JSONObject();
        g.put("tarif", tarifId);
        g.put("id", stationId == null ? "" : stationId);
        g.put("name", LadesaeuleZuordnung.einzeilig(name, 120));
        g.put("betreiber", LadesaeuleZuordnung.einzeilig(betreiber, 90));
        g.put("art", "DC".equals(art) ? "DC" : "AC".equals(art) ? "AC" : "");
        neu.put(g);
        while (neu.length() > 50) neu.remove(0);
        return neu;
    }

    // ------------------------------------------------------------------ Laufzeit

    /** Aus {@code SocHistoryDatabase} nach dem Schließen einer Ladung. Ohne {@link #aktivieren} nichts. */
    public static void nachLadeende(final SocHistoryDatabase db, final long sessionStart) {
        if (!aktiv || db == null || sessionStart <= 0) return;
        Thread t = new Thread(() -> {
            try {
                SocHistoryDatabase.StationZeile z = db.getStationZeileByStart(sessionStart);
                if (z == null) return;
                Ergebnis e = bearbeite(z, config(), orteEcht(), ruheEcht(), planer);
                speichere(db, z.id, e);
            } catch (Throwable t1) {
                logger.debug("Ladesaeule nach Ladeende: " + t1.getClass().getSimpleName());
            }
        }, "Ladesaeule");
        t.setDaemon(true);
        t.start();
    }

    /** Knopf „nachtragen“ zur Laufzeit. {@code {ok, gefragt, zugeordnet, unsicher, keine, privat, fehler, offen}}. */
    public static JSONObject nachtragenJetzt(SocHistoryDatabase db) throws Exception {
        JSONObject o = new JSONObject();
        JSONObject cfg = config();
        if (!schalterAn(cfg)) {
            o.put("ok", false);
            o.put("grund", "aus");
            return o;
        }
        List<SocHistoryDatabase.StationZeile> offen = db.getStationOffen(500);
        Map<Long, Ergebnis> r = nachtragen(offen, cfg, orteEcht(), ruheEcht(), planer, MAX_NACHTRAG_ANFRAGEN);
        int gefragtZellen = 0, auto = 0, unsicher = 0, keine = 0, privat = 0, fehler = 0;
        java.util.Set<String> zellen = new java.util.HashSet<>();
        for (SocHistoryDatabase.StationZeile z : offen) {
            Ergebnis e = r.get(z.id);
            if (e == null) continue;
            if (e.angefragt) zellen.add(LadesaeuleZuordnung.zelle(z.lat, z.lng));
            speichere(db, z.id, e);
            switch (e.zuordnung) {
                case LadesaeuleZuordnung.AUTO: case LadesaeuleZuordnung.GEMERKT: auto++; break;
                case LadesaeuleZuordnung.UNSICHER: unsicher++; break;
                case LadesaeuleZuordnung.KEINE: keine++; break;
                case LadesaeuleZuordnung.PRIVAT: privat++; break;
                default: fehler++;
            }
        }
        gefragtZellen = zellen.size();
        o.put("ok", true);
        o.put("gefragt", gefragtZellen);
        o.put("zugeordnet", auto);
        o.put("unsicher", unsicher);
        o.put("keine", keine);
        o.put("privat", privat);
        o.put("fehler", fehler);
        o.put("offen", Math.max(0, offen.size() - r.size()));
        logger.info("Ladesaeulen nachgetragen: " + gefragtZellen + " Anfragen, " + auto + " sicher, " + unsicher
                + " unsicher, " + keine + " ohne Treffer, " + privat + " privat, " + fehler + " Fehler");
        return o;
    }

    static void speichere(SocHistoryDatabase db, long id, Ergebnis e) {
        if (e == null || !e.speichern()) return;
        boolean ok = db.setChargingStation(id, e.zuordnung, e.stationId, e.name, e.betreiber, e.art, e.kandidatenJson);
        logger.info("Ladesaeule Ladung " + id + ": " + e.zuordnung + (ok ? "" : " (nicht gespeichert)"));
    }

    static JSONObject config() {
        try {
            return at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig();
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    static JSONArray orteEcht() {
        try {
            JSONArray a = at.greenchili.surfmate.orte.OrteDaemon.listeOhneAbgleich();
            return a == null ? new JSONArray() : a;
        } catch (Throwable t) {
            return new JSONArray();
        }
    }

    static JSONArray ruheEcht() {
        JSONArray a = new JSONArray();
        try {
            for (at.greenchili.surfmate.surveillance.SafeLocation z
                    : at.greenchili.surfmate.surveillance.SafeLocationManager.getInstance().getZones()) {
                a.put(new JSONObject().put("lat", z.getLatitude()).put("lon", z.getLongitude()).put("r", z.getRadiusMeters()));
            }
        } catch (Throwable ignored) {
        }
        return a;
    }

    /** Echte Anfrage an den eigenen Planer, ohne Schlüssel. Body nur im POST, nie in URL oder Log. */
    static JSONObject echtUmkreis(JSONObject body) {
        HttpURLConnection c = null;
        try {
            long warte = at.greenchili.surfmate.planer.PlanerBackoff.UMKREIS.warteMs(System.currentTimeMillis());
            if (warte > 0) return null;
            c = (HttpURLConnection) new URL(at.greenchili.surfmate.server.NaviApiHandler.planerBasis() + "umkreis")
                    .openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(10_000);
            c.setReadTimeout(25_000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "Panoptes");
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(payload.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(payload);
            }
            int code = c.getResponseCode();
            if (code != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    b.write(buf, 0, n);
                    if (b.size() > 4 * 1024 * 1024) return null;
                }
                return new JSONObject(new String(b.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
