package at.greenchili.surfmate.fahrtenbuch;

import at.greenchili.surfmate.logging.DaemonLogger;
import at.greenchili.surfmate.planer.PlanerSchluessel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Antwortet auf Fahrtenbuch-Anfragen von /ev/ (Planer-Vertrag §8): holt offene Anfragen
 * ({@code GET fahrtenbuch/offen?auto=}), verschlüsselt die gewünschte Seite Ende-zu-Ende ({@link FahrtenbuchKrypto})
 * und schickt nur Chiffretext ({@code POST fahrtenbuch/antwort}). Das Fahrtenbuch verlässt das Auto nie im Klartext.
 *
 * <p>Nur mit {@code fahrtenbuch.planer = true} (Standard aus) und Planer-Schlüssel. Takt: jede Minute; nach einer
 * Anfrage 5 min lang alle 20 s (weitere Seiten). 401 vom Planer → 10 min Pause. Jede Anfrage wird höchstens einmal
 * beantwortet.
 */
public final class FahrtenbuchPostfach {

    private static final DaemonLogger logger = DaemonLogger.getInstance("Fahrtenbuch");

    static final long TAKT_MS = 60_000L;
    static final long SCHNELL_MS = 20_000L;
    static final long SCHNELL_DAUER_MS = 5 * 60_000L;
    static final long PAUSE_401_MS = 10 * 60_000L;
    static final int MAX_CHIFFRE = 256 * 1024;

    /** Netz + Daten (im Test nachgebaut). */
    public interface Quelle {
        boolean eingeschaltet();
        String autoId();
        String[] schluessel();
        /** {status, json} oder null bei Netzfehler. */
        Object[] http(String methode, String op, String[] schluessel, String body);
        List<FahrtenbuchSeite.Fahrt> fahrten(long vonMs, long bisMs);
        TimeZone zone();
        long jetzt();
    }

    private static volatile FahrtenbuchPostfach instance;

    private final Quelle q;
    private long schnellBis;
    private long pauseBis;
    private long naechster;
    /** Beantwortete anfrageIds (Ablauf beim Planer 2 min; wir merken 10 min). */
    private final Map<String, Long> erledigt = new LinkedHashMap<>();

    // Diagnose (0.1.9): sichtbar machen, ob das Postfach läuft
    private volatile long letzteAbfrage;
    /** HTTP-Status der letzten Abfrage, 0 = Netzfehler, −1 = noch keine. */
    private volatile int letzterStatus = -1;
    private volatile String grund = "startet";

    FahrtenbuchPostfach(Quelle q) {
        this.q = q;
    }

    /** Status für GET /api/planer/verbindung → fahrtenbuchPostfach. */
    public static JSONObject status() {
        FahrtenbuchPostfach p = instance;
        JSONObject o = new JSONObject();
        try {
            if (p == null) {
                o.put("laeuft", false);
                return o;
            }
            o.put("laeuft", true);
            o.put("an", p.q.eingeschaltet());
            o.put("grund", p.grund);
            o.put("letzteAbfrage", p.letzteAbfrage > 0 ? p.letzteAbfrage : JSONObject.NULL);
            o.put("httpStatus", p.letzterStatus > 0 ? p.letzterStatus : JSONObject.NULL);
            o.put("netzFehler", p.letzterStatus == 0);
        } catch (Exception ignored) {
        }
        return o;
    }

    JSONObject statusIntern() {
        FahrtenbuchPostfach alt = instance;
        instance = this;
        try {
            return status();
        } finally {
            instance = alt;
        }
    }

    public static synchronized void start() {
        if (instance != null) return;
        FahrtenbuchPostfach p = new FahrtenbuchPostfach(new Echt());
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Fahrtenbuch");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleWithFixedDelay(() -> {
            try {
                p.takt();
            } catch (Throwable t) {
                logger.warn("takt: " + t.getMessage());
            }
        }, 90, SCHNELL_MS / 1000, TimeUnit.SECONDS);
        instance = p;
    }

    /** Läuft alle 20 s; fragt nur, wenn der Takt (60 s bzw. 20 s im Schnellmodus) fällig ist. */
    void takt() {
        long now = q.jetzt();
        if (!q.eingeschaltet()) { grund = "aus"; return; }
        if (now < pauseBis) { grund = "pause_401"; return; }
        if (now < naechster) return;
        naechster = now + (now < schnellBis ? SCHNELL_MS : TAKT_MS) - 1_000;
        String auto = q.autoId();
        String[] k = q.schluessel();
        if (k == null) { grund = "kein_schluessel"; return; }
        if (auto == null) { grund = "keine_auto_id"; return; }
        Object[] r = q.http("GET", "fahrtenbuch/offen?auto=" + auto, k, null);
        letzteAbfrage = now;
        if (r == null) {
            if (letzterStatus != 0) logger.info("Fahrtenbuch-Postfach: Planer nicht erreichbar");
            letzterStatus = 0;
            grund = "netz";
            return;
        }
        int st = (Integer) r[0];
        if (st != letzterStatus) logger.info("Fahrtenbuch-Postfach: HTTP " + st);
        letzterStatus = st;
        grund = st == 200 ? "ok" : "http";
        if (st == 401 || st == 403) {
            pauseBis = now + PAUSE_401_MS;
            logger.warn("Planer lehnt den Schlüssel ab (" + st + ") – 10 min Pause");
            return;
        }
        if (st != 200 || r[1] == null) return;
        JSONArray a = ((JSONObject) r[1]).optJSONArray("anfragen");
        aufraeumen(now);
        if (a == null || a.length() == 0) return;
        schnellBis = now + SCHNELL_DAUER_MS;
        naechster = now + SCHNELL_MS - 1_000;
        for (int i = 0; i < a.length() && i < 6; i++) {
            JSONObject an = a.optJSONObject(i);
            if (an == null) continue;
            String id = an.optString("anfrageId", "");
            if (!id.matches("^[A-Za-z0-9_-]{16,64}$") || erledigt.containsKey(id)) continue;
            erledigt.put(id, now);
            antworten(an, id, auto, k);
        }
    }

    void antworten(JSONObject an, String id, String auto, String[] k) {
        JSONObject zr = an.optJSONObject("zeitraum");
        String von = zr == null ? null : zr.optString("von", null);
        String bis = zr == null ? null : zr.optString("bis", null);
        long[] z = FahrtenbuchSeite.zeitraum(von, bis, q.zone());
        if (z == null) {
            logger.info("Anfrage mit ungültigem Zeitraum verworfen");
            return;
        }
        List<FahrtenbuchSeite.Fahrt> f = new ArrayList<>(q.fahrten(z[0], z[1]));
        Collections.sort(f, (x, y) -> Long.compare(x.start, y.start));
        JSONObject klar = FahrtenbuchSeite.seite(auto, von, bis, an.optInt("seite", 1), f, q.zone());
        try {
            FahrtenbuchKrypto.Paket p = FahrtenbuchKrypto.verschluesseln(an.optString("pubkey", ""), id,
                    klar.toString().getBytes(StandardCharsets.UTF_8));
            if (p.chiffretext.length() > MAX_CHIFFRE) {
                logger.warn("Seite zu groß für den Planer – nicht gesendet");
                return;
            }
            JSONObject body = new JSONObject().put("anfrageId", id).put("pubkey", p.pubkey)
                    .put("iv", p.iv).put("chiffretext", p.chiffretext);
            Object[] r = q.http("POST", "fahrtenbuch/antwort", k, body.toString());
            logger.info("Fahrtenbuch-Seite " + klar.optInt("seite") + "/" + klar.optInt("seiten") + " ("
                    + klar.optJSONArray("fahrten").length() + " Fahrten) verschlüsselt gesendet: "
                    + (r == null ? "Netzfehler" : r[0]));
        } catch (IllegalArgumentException e) {
            logger.warn("Anfrage mit ungültigem Schlüssel verworfen");
        } catch (Exception e) {
            logger.warn("Verschlüsseln/Senden: " + e.getMessage());
        }
    }

    private void aufraeumen(long now) {
        erledigt.entrySet().removeIf(e -> now - e.getValue() > 10 * 60_000L);
    }

    // ------------------------------------------------------------------ echt

    static final class Echt implements Quelle {
        private JSONObject cfg() {
            try {
                return at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig();
            } catch (Throwable t) {
                return new JSONObject();
            }
        }

        @Override
        public boolean eingeschaltet() {
            JSONObject f = cfg().optJSONObject("fahrtenbuch");
            return f != null && f.optBoolean("planer", false);
        }

        @Override
        public String autoId() {
            JSONObject p = cfg().optJSONObject("planner");
            String id = p == null ? "" : p.optString("autoId", "");
            if (id.matches("^a-[0-9a-f]{16}$")) return id;
            // Bugfix 0.1.9: fehlt sie (nie /api/planer/identitaet aufgerufen), jetzt erzeugen statt nie abzufragen
            try {
                return at.greenchili.surfmate.server.EinstellungenApiHandler.autoId();
            } catch (Throwable t) {
                return null;
            }
        }

        @Override public String[] schluessel() { return PlanerSchluessel.kopf(); }

        @Override
        public Object[] http(String methode, String op, String[] token, String body) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(PlanerSchluessel.BASIS + op).openConnection();
                c.setRequestMethod(methode);
                c.setConnectTimeout(5_000);
                c.setReadTimeout(10_000);
                if (token != null) c.setRequestProperty(token[0], token[1]);
                c.setRequestProperty("Accept", "application/json");
                if (body != null) {
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    try (OutputStream o = c.getOutputStream()) {
                        o.write(body.getBytes(StandardCharsets.UTF_8));
                    }
                }
                int code = c.getResponseCode();
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                JSONObject j = null;
                if (in != null) {
                    try (InputStream i = in) {
                        ByteArrayOutputStream b = new ByteArrayOutputStream();
                        byte[] buf = new byte[4096];
                        int n, total = 0;
                        while ((n = i.read(buf)) > 0 && total < 256_000) { b.write(buf, 0, n); total += n; }
                        try { j = new JSONObject(new String(b.toByteArray(), StandardCharsets.UTF_8)); } catch (Exception ignored) { }
                    }
                }
                return new Object[]{code, j};
            } catch (Exception e) {
                return null;
            } finally {
                if (c != null) c.disconnect();
            }
        }

        @Override
        public List<FahrtenbuchSeite.Fahrt> fahrten(long von, long bis) {
            List<FahrtenbuchSeite.Fahrt> l = new ArrayList<>();
            try {
                at.greenchili.surfmate.trips.TripAnalyticsManager m =
                        at.greenchili.surfmate.daemon.CameraDaemon.getTripAnalyticsManager();
                at.greenchili.surfmate.trips.TripDatabase db = m == null ? null : m.getDatabase();
                if (db == null) return l;
                for (at.greenchili.surfmate.trips.TripRecord t : db.getTripsBetween(von, bis, 5000, 0)) {
                    l.add(alsFahrt(t));
                }
            } catch (Throwable t) {
                logger.warn("Fahrten lesen: " + t.getMessage());
            }
            return l;
        }

        @Override public TimeZone zone() { return TimeZone.getDefault(); }
        @Override public long jetzt() { return System.currentTimeMillis(); }
    }

    /** Nur Zeiten, km, kWh, SoC – keine Koordinaten. */
    public static FahrtenbuchSeite.Fahrt alsFahrt(at.greenchili.surfmate.trips.TripRecord t) {
        FahrtenbuchSeite.Fahrt f = new FahrtenbuchSeite.Fahrt();
        f.start = t.startTime;
        f.ende = t.endTime;
        f.km = Math.max(0, t.distanceKm);
        if (t.kwhStart > 0 && t.kwhEnd > 0 && t.kwhStart >= t.kwhEnd) f.kwh = t.kwhStart - t.kwhEnd;
        else if (t.energyPerKm > 0) f.kwh = t.energyPerKm * f.km;
        if (t.socStart > 0) f.socStart = t.socStart;
        if (t.socEnd > 0) f.socEnde = t.socEnd;
        return f;
    }
}
