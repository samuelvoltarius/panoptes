package at.greenchili.surfmate.planer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 0.1.25 „Route ins Auto schicken“ vom Handy ({@code /api/handy/v1/aktion route}, Recht „route“, Koordination Weg A):
 * Panoptes plant ab der Auto-Position ({@code POST plan}, Fahrzeug und Vorlieben wie im Auto) und legt das Ergebnis mit
 * dem eigenen Geräte-Schlüssel ins Planer-Postfach ({@code POST an-auto}, nur die eigene Auto-Kennung). Am Auto erscheint
 * die bestehende Karte „Route übernehmen?“ – sie bleibt der einzige Weg ins Navi, nichts startet von selbst.
 *
 * <p>Der Planer rechnet lange Strecken > 1 min; deshalb antwortet {@link #starten} sofort mit {@code status:"plant"} und
 * {@link #stand} liefert danach {@code bereit} oder {@code fehler} (mit Grund).
 */
public final class HandyRoute {

    static final int MAX_ZWISCHEN = 5;

    /** Netz, Auto und Ablage (im Test nachgebaut). */
    public interface Umgebung {
        /** {lat, lon} der letzten bekannten Lage oder null (das GPS wird dafür nie eingeschaltet). */
        double[] position();

        /** Ladestand in % oder NaN. */
        double soc();

        String autoId();

        String[] token();

        /** {HTTP-Status, JSON oder null}; null = Netzfehler. */
        Object[] http(String methode, String op, String[] token, String body, int leseMs);

        /** Fahrzeug und Lade-Vorlieben einsetzen wie beim Planen im Auto. */
        String planBody(String body);

        /** Die Karte „Route übernehmen?“ sofort neu lesen lassen. */
        void routeNeuLaden();
    }

    private final Umgebung env;
    private final Executor ausfuehrer;
    /** je Kopplung der letzte Stand: {status:"plant"|"bereit"|"fehler", grund?, titel?, zeit} */
    private final Map<String, JSONObject> staende = new HashMap<>();
    private boolean laeuft;

    HandyRoute(Umgebung env, Executor ausfuehrer) {
        this.env = env;
        this.ausfuehrer = ausfuehrer;
    }

    static boolean ort(JSONObject o) {
        if (o == null) return false;
        double lat = o.optDouble("lat", Double.NaN), lon = o.optDouble("lon", o.optDouble("lng", Double.NaN));
        return !Double.isNaN(lat) && !Double.isNaN(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180
                && !(lat == 0 && lon == 0);
    }

    static JSONObject ortKurz(JSONObject o) throws Exception {
        JSONObject r = new JSONObject().put("lat", o.optDouble("lat"))
                .put("lon", o.has("lon") ? o.optDouble("lon") : o.optDouble("lng"));
        String n = o.optString("name", "").replaceAll("[\\p{Cntrl}]", " ").trim();
        if (!n.isEmpty()) r.put("name", n.length() > 80 ? n.substring(0, 80) : n);
        return r;
    }

    /** Prüft die Anfrage und startet das Planen im Hintergrund. Antwort sofort. */
    public JSONObject starten(String kid, JSONObject req, long jetzt) throws Exception {
        JSONObject ziel = req.optJSONObject("ziel");
        if (!ort(ziel)) return fehler("werte");
        JSONArray zz = req.optJSONArray("zwischenziele");
        if (zz != null && zz.length() > MAX_ZWISCHEN) return fehler("zu_viele_zwischenziele");
        JSONArray via = new JSONArray();
        for (int i = 0; zz != null && i < zz.length(); i++) {
            if (!ort(zz.optJSONObject(i))) return fehler("werte");
            via.put(ortKurz(zz.getJSONObject(i)));
        }
        String[] token = env.token();
        String auto = env.autoId();
        if (token == null || auto == null || auto.isEmpty()) return fehler("kein_planer");
        double[] pos = env.position();
        if (pos == null) return fehler("keine_position");
        JSONObject zielKurz = ortKurz(ziel);
        synchronized (this) {
            if (laeuft) return fehler("laeuft_schon");
            laeuft = true;
            staende.put(kid, new JSONObject().put("status", "plant").put("zeit", jetzt));
        }
        ausfuehrer.execute(() -> {
            JSONObject e;
            try {
                e = ausfuehren(token, auto, pos, zielKurz, via);
            } catch (Throwable t) {
                e = stand("fehler", "intern");
            }
            synchronized (HandyRoute.this) {
                try { e.put("zeit", jetzt); } catch (Exception ignored) { }
                staende.put(kid, e);
                laeuft = false;
            }
        });
        return new JSONObject().put("ok", true).put("status", "plant");
    }

    /** Planen und ins Postfach legen (blockiert; im Hintergrund). */
    JSONObject ausfuehren(String[] token, String auto, double[] pos, JSONObject ziel, JSONArray via) throws Exception {
        JSONObject b = new JSONObject()
                .put("start", new JSONArray().put(pos[0]).put(pos[1]))
                .put("ziel", new JSONArray().put(ziel.getDouble("lat")).put(ziel.getDouble("lon")))
                .put("auto", auto);
        if (via.length() > 0) b.put("via", via);
        double soc = env.soc();
        if (!Double.isNaN(soc)) b.put("soc_start", Math.round(soc));
        Object[] p = env.http("POST", "plan", token, env.planBody(b.toString()), 150_000);
        if (p == null) return stand("fehler", "netz");
        int code = (Integer) p[0];
        JSONObject plan = (JSONObject) p[1];
        if (code == 401) return stand("fehler", "kein_planer");
        if (code != 200 || plan == null || !plan.optBoolean("ok", true)) return stand("fehler", "planer");
        plan = fuerPostfach(plan, pos, ziel, soc);
        if (plan == null) return stand("fehler", "planer");
        // Bindung der Auto-Kennung an den Schlüssel (Planer: einmal fahrtenbuch/offen mit derselben Kennung)
        env.http("GET", "fahrtenbuch/offen?auto=" + auto, token, null, 8_000);
        Object[] a = env.http("POST", "an-auto", token, new JSONObject().put("auto", auto).put("plan", plan).toString(), 15_000);
        if (a == null) return stand("fehler", "netz");
        int ac = (Integer) a[0];
        if (ac == 403) return stand("fehler", "planer_recht_fehlt");
        if (ac == 401) return stand("fehler", "kein_planer");
        if (ac != 200) return stand("fehler", "planer");
        env.routeNeuLaden();
        JSONObject r = stand("bereit", null);
        if (plan.has("titel")) r.put("titel", plan.optString("titel"));
        if (plan.has("km")) r.put("km", plan.optDouble("km"));
        return r;
    }

    static final int MAX_PUNKTE = 600;

    /**
     * Plan-Ergebnis → Postfach-Form wie der Web-Planer ({@code anAuto()} in erweitert.js, API_FUER_SURFMATE §3; Hinweis
     * Routenplaner 28.10.): {@code km/minuten/geometrie} liegen im Ergebnis unter {@code route}, {@code fahrzeug} ist ein
     * Objekt, Stopps tragen die Säule unter {@code saeule}. Geometrie auf ≤ {@value #MAX_PUNKTE} Punkte ausgedünnt (der
     * Planer liest höchstens 400 KB). null ohne Geometrie.
     */
    static JSONObject fuerPostfach(JSONObject erg, double[] pos, JSONObject ziel, double soc) throws Exception {
        JSONObject route = erg.optJSONObject("route");
        JSONArray geo = route != null ? route.optJSONArray("geometrie") : erg.optJSONArray("geometrie");
        if (geo == null || geo.length() < 2) return null;
        JSONObject p = new JSONObject();
        String name = ziel.optString("name", "");
        p.put("titel", name.isEmpty() ? "Ziel" : name);
        p.put("start", new JSONObject().put("lat", pos[0]).put("lon", pos[1]).put("name", ""));
        p.put("ziel", new JSONObject().put("lat", ziel.getDouble("lat")).put("lon", ziel.getDouble("lon")).put("name", name));
        Object km = route != null ? route.opt("km") : erg.opt("km");
        Object min = route != null ? route.opt("minuten") : erg.opt("minuten");
        if (km != null) p.put("km", km);
        if (min != null) p.put("minuten", min);
        if (!Double.isNaN(soc)) p.put("soc_start", Math.round(soc));
        if (erg.has("soc_ankunft")) p.put("soc_ankunft", erg.opt("soc_ankunft"));
        Object fz = erg.opt("fahrzeug");
        if (fz instanceof JSONObject) fz = ((JSONObject) fz).optString("name", "");
        if (fz != null && !"".equals(fz)) p.put("fahrzeug", fz);
        JSONArray stopps = new JSONArray();
        JSONArray st = erg.optJSONArray("stopps");
        for (int i = 0; st != null && i < st.length() && stopps.length() < 20; i++) {
            JSONObject s = st.optJSONObject(i);
            JSONObject sa = s == null ? null : s.optJSONObject("saeule");
            if (sa == null) continue;
            JSONObject o = new JSONObject().put("name", sa.optString("name", "")).put("lat", sa.opt("lat")).put("lon", sa.opt("lon"));
            if (sa.has("km_auf_route")) o.put("km", sa.opt("km_auf_route"));
            if (sa.has("kw")) o.put("kw", sa.opt("kw"));
            if (sa.has("cpo")) o.put("cpo", sa.opt("cpo"));
            if (s.has("soc_vorher")) o.put("soc_an", s.opt("soc_vorher"));
            if (s.has("soc_nachher")) o.put("soc_ab", s.opt("soc_nachher"));
            if (s.has("ladedauer_min")) o.put("dauer_min", Math.round(s.optDouble("ladedauer_min")));
            if (s.has("kwh")) o.put("kwh", s.opt("kwh"));
            stopps.put(o);
        }
        p.put("stopps", stopps);
        JSONArray zz = erg.optJSONArray("zwischenziele");
        if (zz != null && zz.length() > 0) {
            JSONArray z = new JSONArray();
            for (int i = 0; i < zz.length() && z.length() < MAX_ZWISCHEN; i++) {
                JSONObject x = zz.optJSONObject(i);
                if (x == null) continue;
                JSONObject o = new JSONObject().put("name", x.optString("name", "")).put("lat", x.opt("lat")).put("lon", x.opt("lon"));
                if (x.has("km_auf_route")) o.put("km", x.opt("km_auf_route"));
                if (x.has("aufenthalt_min")) o.put("aufenthalt_min", x.opt("aufenthalt_min"));
                z.put(o);
            }
            p.put("zwischenziele", z);
        }
        p.put("geometrie", ausduennen(geo, MAX_PUNKTE));
        return p;
    }

    /** Gleichmäßig ausdünnen, erster und letzter Punkt bleiben. */
    static JSONArray ausduennen(JSONArray geo, int max) {
        if (geo.length() <= max) return geo;
        JSONArray r = new JSONArray();
        double schritt = (geo.length() - 1) / (double) (max - 1);
        for (int i = 0; i < max; i++) r.put(geo.opt((int) Math.round(i * schritt)));
        return r;
    }

    /** Letzter Stand dieser Kopplung: {ok, status, grund?, titel?, km?} oder status "keiner". */
    public synchronized JSONObject stand(String kid) throws Exception {
        JSONObject s = staende.get(kid);
        JSONObject r = new JSONObject().put("ok", true);
        if (s == null) return r.put("status", "keiner");
        for (java.util.Iterator<String> it = s.keys(); it.hasNext(); ) {
            String k = it.next();
            r.put(k, s.get(k));
        }
        return r;
    }

    private static JSONObject stand(String status, String grund) {
        JSONObject o = new JSONObject();
        try {
            o.put("status", status);
            if (grund != null) o.put("grund", grund);
        } catch (Exception ignored) { }
        return o;
    }

    private static JSONObject fehler(String grund) throws Exception {
        return new JSONObject().put("ok", false).put("grund", grund);
    }

    // ------------------------------------------------------------------ Laufzeit

    private static volatile HandyRoute instanz;

    public static HandyRoute get() {
        HandyRoute r = instanz;
        if (r == null) {
            synchronized (HandyRoute.class) {
                if (instanz == null) {
                    instanz = new HandyRoute(new Echt(), cmd -> {
                        Thread t = new Thread(cmd, "HandyRoute");
                        t.setDaemon(true);
                        t.start();
                    });
                }
                r = instanz;
            }
        }
        return r;
    }

    static final class Echt implements Umgebung {
        @Override public double[] position() {
            try {
                at.greenchili.surfmate.monitor.GpsMonitor g = at.greenchili.surfmate.monitor.GpsMonitor.getInstance();
                return g.hasLocation() ? new double[]{g.getLatitude(), g.getLongitude()} : null;
            } catch (Throwable t) {
                return null;
            }
        }

        @Override public double soc() {
            try {
                at.greenchili.surfmate.byd.BydVehicleData v = at.greenchili.surfmate.byd.BydDataCollector.getInstance().getData();
                return v != null && v.socPercent > 0 && v.socPercent <= 100 ? v.socPercent : Double.NaN;
            } catch (Throwable t) {
                return Double.NaN;
            }
        }

        @Override public String autoId() {
            try {
                return at.greenchili.surfmate.server.EinstellungenApiHandler.autoId();
            } catch (Throwable t) {
                return null;
            }
        }

        @Override public String[] token() { return PlanerSchluessel.kopf(); }

        @Override public String planBody(String body) {
            return FahrzeugWahl.einsetzen(at.greenchili.surfmate.server.NaviApiHandler.planBodyFuerAuto(body));
        }

        @Override public void routeNeuLaden() {
            PlanerRoute.get().neuLaden();
        }

        @Override public Object[] http(String methode, String op, String[] token, String body, int leseMs) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(PlanerSchluessel.BASIS + op).openConnection();
                c.setRequestMethod(methode);
                c.setConnectTimeout(10_000);
                c.setReadTimeout(leseMs);
                c.setRequestProperty(token[0], token[1]);   // nur im Header, nie in URL/Log
                c.setRequestProperty("Accept", "application/json");
                c.setRequestProperty("User-Agent", "Panoptes");
                if (body != null) {
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    byte[] b = body.getBytes(StandardCharsets.UTF_8);
                    c.setFixedLengthStreamingMode(b.length);
                    try (OutputStream o = c.getOutputStream()) { o.write(b); }
                }
                int code = c.getResponseCode();
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                JSONObject j = null;
                if (in != null) {
                    try (InputStream s = in) {
                        ByteArrayOutputStream buf = new ByteArrayOutputStream();
                        byte[] x = new byte[16 * 1024];
                        int n;
                        while ((n = s.read(x)) > 0 && buf.size() < 4 * 1024 * 1024) buf.write(x, 0, n);
                        String t = new String(buf.toByteArray(), StandardCharsets.UTF_8).trim();
                        if (t.startsWith("{")) j = new JSONObject(t);
                    }
                }
                return new Object[]{code, j};
            } catch (Exception e) {
                return null;
            } finally {
                if (c != null) c.disconnect();
            }
        }
    }
}
