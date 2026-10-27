package at.greenchili.surfmate.server;

import at.greenchili.surfmate.config.UnifiedConfigManager;
import at.greenchili.surfmate.einstellungen.Einstellungen;
import at.greenchili.surfmate.einstellungen.Feld;
import at.greenchili.surfmate.einstellungen.Katalog;
import at.greenchili.surfmate.einstellungen.LadenKatalog;
import at.greenchili.surfmate.laden.LadenVorlieben;
import at.greenchili.surfmate.planer.PlanerSchluessel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Lade-Vorlieben im Auto (LADEN_VERTRAG.md Teil A):
 * <ul>
 *   <li>{@code GET /api/laden}, {@code PUT /api/laden} – Login, Display oder Kopplung (Cockpit)</li>
 *   <li>{@code POST /api/laden/zuruecksetzen} – nur Login/Display</li>
 *   <li>{@code GET /api/laden/tarife}, {@code GET /api/laden/betreiber?q=} – öffentliche Planer-Kataloge durchgereicht</li>
 *   <li>{@code GET/POST /api/laden/aus-konto} – einmalige Übernahme aus dem Planer-Konto mit Vorschau</li>
 * </ul>
 * Regel A/A+: nur gespeicherte Werte, keine Befehle ans Auto. Nichts davon wird protokolliert.
 */
public final class LadenApiHandler {

    private LadenApiHandler() {}

    /** Alles, was außerhalb der reinen Logik liegt (Config, Planer-Netz, Anfrage-Art). */
    interface Umgebung {
        JSONObject config();
        boolean schreibe(String abschnitt, Map<String, Object> werte);
        boolean abschnittLeeren(String abschnitt);
        boolean extern();
        /** {code, text} oder null bei Netzfehler. */
        Object[] planerGet(String opMitQuery, String[] kopf);
        String[] planerKopf();
        /** Fahrzeugprofil-Stand (gewählt/erkannt/wirksam/Kandidaten), null = unbekannt. */
        default JSONObject fahrzeug(JSONObject root) { return null; }
        /** Preis-Weg (Nachbepreisung/Ladenachfrage); null = nicht verfügbar (Test). */
        default at.greenchili.surfmate.charging.LadePreis.Db preisDb() { return null; }
        default at.greenchili.surfmate.charging.LadePreis.Tarife tarife() { return null; }
        default at.greenchili.surfmate.charging.LadePreis.Protokoll protokoll() { return null; }
        default java.io.File sicherungsOrdner() { return null; }
        default String standardWaehrung() { return ""; }
        default long jetzt() { return System.currentTimeMillis(); }
        /** Ladenachfrage: Ladungen ab {@code seit}, Ortskreise, Stopps der angenommenen Route, Auto steht. */
        default JSONArray ladungenSeit(long seit) { return new JSONArray(); }
        default JSONArray ortskreise() { return new JSONArray(); }
        default JSONArray planerStopps() { return null; }
        default boolean steht() { return false; }
        default boolean nachfrageSetzen(long id, String stand) { return false; }
        /** Neuer Ortskreis („Ort merken“); null = nicht gespeichert. */
        default String ortMerken(String name, double lat, double lng, int radiusM, double rate, boolean dc,
                                 String waehrung, boolean gratis) { return null; }
        /** Kosten je 100 km / Ladungsliste: Sitzungen (chargingRowToJson) und gefahrene km im Zeitraum. */
        default JSONArray sitzungen(long von, long bis) { return new JSONArray(); }
        default double km(long von, long bis) { return 0; }
        default java.util.TimeZone zeitzone() { return java.util.TimeZone.getDefault(); }
    }

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String pathOnly = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if (!pathOnly.equals("/api/laden") && !pathOnly.startsWith("/api/laden/")) return false;
        String query = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
        Object[] r = bearbeite(method, pathOnly, query, body, ECHT);
        if (r == null) return false;
        HttpResponse.sendJson(out, (Integer) r[0], r[1].toString());
        return true;
    }

    /** {Status, Antwort} oder null, wenn der Pfad unbekannt ist. */
    static Object[] bearbeite(String method, String pathOnly, String query, String body, Umgebung u) throws Exception {
        switch (pathOnly) {
            case "/api/laden":
                if ("GET".equals(method)) return ok(mitFahrzeug(LadenVorlieben.antwort(u.config()), u));
                if ("PUT".equals(method) || "POST".equals(method)) return aendern(body, u);
                return fehler(405, "methode");
            case "/api/laden/zuruecksetzen":
                if (!"POST".equals(method)) return fehler(405, "methode");
                if (u.extern()) return fehler(403, "nur_mit_login");
                if (!u.abschnittLeeren(LadenKatalog.ABSCHNITT)) return fehler(500, "speichernFehlgeschlagen");
                return ok(LadenVorlieben.antwort(u.config()));
            case "/api/laden/kurve":   // 0.1.19 eigene Ladekurve (lokal, nur lesen)
                if (!"GET".equals(method)) return fehler(405, "methode");
                if (u.extern()) return fehler(403, "nur_mit_login");
                return ok(at.greenchili.surfmate.ladekurve.LadekurveDienst.status());
            case "/api/laden/tarife":
                if (!"GET".equals(method)) return fehler(405, "methode");
                return durchreichen(u, "tarife");
            case "/api/laden/betreiber": {
                if (!"GET".equals(method)) return fehler(405, "methode");
                String q = param(query, "q").trim();
                if (q.length() > 60) q = q.substring(0, 60);
                if (q.isEmpty()) return fehler(400, "q");
                return durchreichen(u, "betreiber?q=" + URLEncoder.encode(q, "UTF-8"));
            }
            case "/api/laden/preis":
            case "/api/laden/preis/rueckgaengig":
            case "/api/laden/sicherung": {
                if (!"POST".equals(method)) return fehler(405, "methode");
                if (u.extern()) return fehler(403, "nur_mit_login");
                if (u.preisDb() == null) return fehler(503, "ladedaten_nicht_bereit");
                JSONObject req;
                try {
                    req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
                } catch (Exception e) {
                    return fehler(400, "json");
                }
                if (pathOnly.endsWith("/sicherung")) {
                    return at.greenchili.surfmate.charging.LadePreis.sicherung(u.preisDb(), u.sicherungsOrdner(), u.jetzt());
                }
                if (pathOnly.endsWith("/rueckgaengig")) {
                    return at.greenchili.surfmate.charging.LadePreis.rueckgaengig(req, u.preisDb(), u.protokoll());
                }
                return at.greenchili.surfmate.charging.LadePreis.preis(req, u.preisDb(), u.tarife(), u.protokoll(),
                        u.standardWaehrung(), u.jetzt());
            }
            case "/api/laden/kosten": {
                if (!"GET".equals(method)) return fehler(405, "methode");
                String z = param(query, "zeitraum");
                if (z.isEmpty()) z = at.greenchili.surfmate.charging.LadeKosten.MONAT;
                if (!at.greenchili.surfmate.charging.LadeKosten.gueltig(z)) return fehler(400, "zeitraum");
                long[] vb = at.greenchili.surfmate.charging.LadeKosten.zeitraum(z, u.jetzt(), u.zeitzone());
                return ok(at.greenchili.surfmate.charging.LadeKosten.kosten(u.sitzungen(vb[0], vb[1]), u.km(vb[0], vb[1]),
                        z, vb[0], vb[1]));
            }
            case "/api/laden/ladungen": {
                if (!"GET".equals(method)) return fehler(405, "methode");
                long bis = zahl(param(query, "bis"), u.jetzt());
                long von = zahl(param(query, "von"), 0);
                int seite = (int) zahl(param(query, "seite"), 0);
                int anzahl = (int) zahl(param(query, "anzahl"), 50);
                return ok(at.greenchili.surfmate.charging.LadeKosten.ladungen(u.sitzungen(von, bis), seite, anzahl, u.zeitzone()));
            }
            case "/api/laden/nachfrage":
                if ("GET".equals(method)) {
                    return ok(at.greenchili.surfmate.charging.LadeNachfrage.liste(
                            u.ladungenSeit(at.greenchili.surfmate.charging.LadeNachfrage.SEIT), u.config(),
                            u.ortskreise(), u.planerStopps(), u.steht()));
                }
                if ("POST".equals(method)) return nachfrageBeantworten(body, u);
                return fehler(405, "methode");
            case "/api/laden/nachfrage/spaeter":
            case "/api/laden/nachfrage/kein-preis": {
                if (!"POST".equals(method)) return fehler(405, "methode");
                JSONObject req;
                try {
                    req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
                } catch (Exception e) {
                    return fehler(400, "json");
                }
                long id = req.optLong("id", -1);
                if (id <= 0) return fehler(400, "id");
                if (pathOnly.endsWith("/spaeter")) {
                    return u.nachfrageSetzen(id, "spaeter") ? ok(new JSONObject().put("ok", true)) : fehler(404, "unbekannt");
                }
                if (u.preisDb() == null) return fehler(503, "ladedaten_nicht_bereit");
                return at.greenchili.surfmate.charging.LadePreis.preis(new JSONObject().put("id", id).put("fehlt", true),
                        u.preisDb(), u.tarife(), u.protokoll(), u.standardWaehrung(), u.jetzt());
            }
            case "/api/laden/aus-konto":
                if (u.extern()) return fehler(403, "nur_mit_login");
                if ("GET".equals(method)) return ausKonto(u, false, body);
                if ("POST".equals(method)) return ausKonto(u, true, body);
                return fehler(405, "methode");
            default:
                return null;
        }
    }

    // ---------------------------------------------------------------- PUT

    static Object[] aendern(String body, Umgebung u) throws Exception {
        JSONObject req;
        try {
            req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
        } catch (Exception e) {
            return fehler(400, "json");
        }
        JSONObject laden = req.optJSONObject("laden");
        if (laden == null) return fehler(400, "laden");
        return schreiben(laden, req.optString("etag", ""), u);
    }

    /** Prüft alle Felder vorab (nichts speichern, wenn eines falsch ist), schreibt dann und liest zurück. */
    static Object[] schreiben(JSONObject laden, String etag, Umgebung u) throws Exception {
        JSONObject root = u.config();
        String aktuell = LadenVorlieben.etag(root);
        if (!etag.isEmpty() && !etag.equals(aktuell)) {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("grund", "veraltet");
            o.put("etag", aktuell);
            return new Object[] {409, o};
        }
        List<Feld> felder = LadenKatalog.felder();
        JSONObject werte = new JSONObject();
        JSONObject fehler = new JSONObject();
        Iterator<String> it = laden.keys();
        while (it.hasNext()) {
            String kurz = it.next();
            String key = LadenKatalog.ABSCHNITT + "." + kurz;
            Feld f = Katalog.finde(felder, key);
            if (f == null) {
                fehler.put(key, LadenKatalog.STAND.equals(kurz) ? "nurLesend" : "unbekannt");
                continue;
            }
            Object roh = laden.get(kurz);
            try {
                Einstellungen.pruefe(f, roh == JSONObject.NULL ? null : roh);
            } catch (IllegalArgumentException ex) {
                fehler.put(key, ex.getMessage());
                continue;
            }
            werte.put(key, roh);
        }
        if (fehler.length() > 0) {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("fehler", fehler);
            return new Object[] {400, o};
        }
        if (werte.length() > 0) {
            JSONObject b = new JSONObject();
            b.put("werte", werte);
            Einstellungen.Ergebnis e = Einstellungen.aendern(felder, root, new Katalog.Kontext(), b, u.extern(),
                    u::schreibe, System.currentTimeMillis());
            if (!e.ok()) {
                JSONObject o = new JSONObject();
                o.put("ok", false);
                o.put("fehler", e.fehler);
                return new Object[] {500, o};
            }
        }
        return ok(mitFahrzeug(LadenVorlieben.antwort(u.config()), u));
    }

    private static JSONObject mitFahrzeug(JSONObject antwort, Umgebung u) throws Exception {
        JSONObject f = u.fahrzeug(u.config());
        if (f != null) antwort.put("fahrzeug", f);
        return antwort;
    }

    /** {@code POST /api/laden/nachfrage}: Preis setzen (LadePreis), optional „Ort merken“ als neuer Ortskreis. */
    static Object[] nachfrageBeantworten(String body, Umgebung u) throws Exception {
        JSONObject req;
        try {
            req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
        } catch (Exception e) {
            return fehler(400, "json");
        }
        if (u.preisDb() == null) return fehler(503, "ladedaten_nicht_bereit");
        JSONObject cfg = u.config();
        String[] grund = new String[1];
        JSONObject anfrage = at.greenchili.surfmate.charging.LadeNachfrage.alsPreisAnfrage(req, cfg, grund);
        if (anfrage == null) return fehler(400, grund[0]);
        JSONObject vorher = u.preisDb().snapshot(anfrage.getLong("id"));
        Object[] r = at.greenchili.surfmate.charging.LadePreis.preis(anfrage, u.preisDb(), u.tarife(), u.protokoll(),
                u.standardWaehrung(), u.jetzt());
        JSONObject o = (JSONObject) r[1];
        JSONObject merken = req.optJSONObject("ortMerken");
        if ((Integer) r[0] == 200 && o.optBoolean("ok") && merken != null && vorher != null) {
            double lat = vorher.optDouble("lat", 0), lng = vorher.optDouble("lng", 0);
            if (lat == 0 && lng == 0) {
                o.put("ortMerken", "keine_position");
            } else {
                JSONObject neu = o.getJSONArray("ergebnisse").getJSONObject(0).getJSONObject("neu");
                String name = merken.optString("name", "").trim();
                if (name.isEmpty()) name = neu.optString("tariffLabel", "Ladeort");
                int radius = Math.max(25, Math.min(2000, merken.optInt("radiusM", 100)));
                boolean dc = !vorher.isNull("dc") && vorher.optBoolean("dc");
                double rate = neu.optDouble("rate", -1);
                String tid = rate < 0 ? null : u.ortMerken(name, lat, lng, radius, rate, dc,
                        neu.optString("currency", ""), rate == 0);
                o.put("ortMerken", tid == null ? "fehlgeschlagen" : tid);
            }
        }
        return r;
    }

    // ---------------------------------------------------------------- Kataloge

    private static Object[] durchreichen(Umgebung u, String op) throws Exception {
        Object[] a = u.planerGet(op, null);
        if (a == null) return fehler(503, "netz");
        int code = (Integer) a[0];
        String text = String.valueOf(a[1]);
        JSONObject j;
        try {
            j = new JSONObject(text);
        } catch (Exception e) {
            return fehler(502, "planer");
        }
        return new Object[] {code == 200 ? 200 : 502, j};
    }

    // ---------------------------------------------------------------- Übernahme aus dem Konto

    static Object[] ausKonto(Umgebung u, boolean uebernehmen, String body) throws Exception {
        if (uebernehmen) {
            JSONObject req;
            try {
                req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
            } catch (Exception e) {
                return fehler(400, "json");
            }
            if (!req.optBoolean("bestaetigt", false)) return fehler(400, "bestaetigungFehlt");
        }
        String[] kopf = u.planerKopf();
        if (kopf == null) return fehler(409, "nicht_verbunden");
        Object[] k = u.planerGet("meine-karten", kopf);
        Object[] n = u.planerGet("meine-ladenetze", kopf);
        if (k == null || n == null) return fehler(503, "netz");
        int kc = (Integer) k[0], nc = (Integer) n[0];
        if (kc == 401 || kc == 403 || nc == 401 || nc == 403) return fehler(403, "kein_leserecht");
        if (kc != 200 || nc != 200) return fehler(502, "planer");
        JSONObject konto = kontoAlsLaden(json(k[1]), json(n[1]));
        if (konto == null) return fehler(502, "planer");
        if (uebernehmen) return schreiben(konto, "", u);
        JSONObject jetzt = LadenVorlieben.lies(u.config());
        JSONArray aend = new JSONArray();
        Iterator<String> it = konto.keys();
        while (it.hasNext()) {
            String f = it.next();
            Object neu = konto.get(f), alt = jetzt.opt(f);
            if (!String.valueOf(neu).equals(String.valueOf(alt))) {
                JSONObject a = new JSONObject();
                a.put("feld", f);
                a.put("alt", alt == null ? JSONObject.NULL : alt);
                a.put("neu", neu);
                aend.put(a);
            }
        }
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("konto", konto);
        o.put("jetzt", jetzt);
        o.put("aenderungen", aend);
        return new Object[] {200, o};
    }

    /** Planer-Antworten (§3d meine-karten, §10 meine-ladenetze) in laden-Form; null bei Unsinn. */
    static JSONObject kontoAlsLaden(JSONObject karten, JSONObject netze) {
        if (karten == null || netze == null) return null;
        try {
            JSONObject l = new JSONObject();
            JSONArray ka = new JSONArray();
            JSONArray kl = karten.optJSONArray("karten");
            for (int i = 0; kl != null && i < kl.length() && ka.length() < LadenKatalog.MAX_KARTEN; i++) {
                JSONObject k = kl.optJSONObject(i);
                if (k == null || k.optInt("tarif_id", 0) <= 0) continue;
                JSONObject e = new JSONObject();
                e.put("id", k.getInt("tarif_id"));
                String anz = (k.optString("msp", "") + " · " + k.optString("tarif", "")).trim();
                if (anz.startsWith("·")) anz = anz.substring(1).trim();
                if (anz.endsWith("·")) anz = anz.substring(0, anz.length() - 1).trim();
                if (!anz.isEmpty()) e.put("anzeige", anz);
                ka.put(e);
            }
            l.put("karten", ka);
            if (netze.optBoolean("gespeichert", false)) {
                JSONArray na = new JSONArray();
                JSONArray nl = netze.optJSONArray("netze");
                for (int i = 0; nl != null && i < nl.length() && na.length() < LadenKatalog.MAX_NETZE; i++) {
                    String c = nl.optString(i, "").trim();
                    if (c.isEmpty()) continue;
                    na.put(new JSONObject().put("cpo", c).put("anzeige", c));
                }
                l.put("netze", na);
                l.put("netzeModus", "nur".equals(netze.optString("modus")) ? "nur" : "bevorzugen");
                l.put("umwegMaxMin", Math.max(0, Math.min(60, netze.optInt("umweg_max_min", 10))));
                l.put("nurOffen", netze.optBoolean("nur_offen", false));
            }
            return l;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- Hilfen

    private static JSONObject json(Object text) {
        try {
            return new JSONObject(String.valueOf(text));
        } catch (Exception e) {
            return null;
        }
    }

    static String param(String query, String name) {
        if (query == null) return "";
        for (String p : query.split("&")) {
            int i = p.indexOf('=');
            String k = i < 0 ? p : p.substring(0, i);
            if (!name.equals(k)) continue;
            try {
                return java.net.URLDecoder.decode(i < 0 ? "" : p.substring(i + 1), "UTF-8");
            } catch (Exception e) {
                return "";
            }
        }
        return "";
    }

    private static long zahl(String s, long rueckfall) {
        try {
            return s == null || s.isEmpty() ? rueckfall : Long.parseLong(s.trim());
        } catch (Exception e) {
            return rueckfall;
        }
    }

    private static Object[] ok(JSONObject o) {
        return new Object[] {200, o};
    }

    private static Object[] fehler(int status, String grund) throws Exception {
        JSONObject o = new JSONObject();
        o.put("ok", false);
        o.put("grund", grund);
        return new Object[] {status, o};
    }

    // ---------------------------------------------------------------- Laufzeit

    static final Umgebung ECHT = new Umgebung() {
        public JSONObject config() { return UnifiedConfigManager.forceReload(); }

        public boolean schreibe(String abschnitt, Map<String, Object> werte) {
            try {
                return UnifiedConfigManager.updateValues(abschnitt, werte);
            } catch (Exception e) {
                return false;
            }
        }

        public boolean abschnittLeeren(String abschnitt) {
            try {
                return UnifiedConfigManager.replaceSection(abschnitt, new JSONObject());
            } catch (Exception e) {
                return false;
            }
        }

        public boolean extern() { return AuthMiddleware.isExternRequest(); }

        public String[] planerKopf() { return PlanerSchluessel.kopf(); }

        public JSONObject fahrzeug(JSONObject root) { return at.greenchili.surfmate.planer.FahrzeugWahl.status(root); }

        public at.greenchili.surfmate.charging.LadePreis.Db preisDb() {
            final at.greenchili.surfmate.monitor.SocHistoryDatabase d = at.greenchili.surfmate.monitor.SocHistoryDatabase.getInstance();
            if (d == null) return null;
            return new at.greenchili.surfmate.charging.LadePreis.Db() {
                public JSONObject snapshot(long id) { return d.chargingPriceSnapshot(id); }
                public boolean setzen(long id, double rate, String w, String tid, String tn, String q, String n) {
                    return d.setChargingSessionPrice(id, rate, w, tid, tn, q, n);
                }
                public JSONObject export() throws Exception { return d.exportChargingJson(); }
            };
        }

        public at.greenchili.surfmate.charging.LadePreis.Tarife tarife() {
            return id -> at.greenchili.surfmate.charging.TariffManager.getInstance().findById(id);
        }

        public at.greenchili.surfmate.charging.LadePreis.Protokoll protokoll() { return PreisProtokollDatei.INSTANZ; }

        public java.io.File sicherungsOrdner() {
            return new java.io.File(at.greenchili.surfmate.util.DaemonStorage.rebase(
                    "/data/user_de/0/com.android.shell/surfmate/sicherung"));
        }

        public JSONArray ladungenSeit(long seit) {
            at.greenchili.surfmate.monitor.SocHistoryDatabase d = at.greenchili.surfmate.monitor.SocHistoryDatabase.getInstance();
            return d == null ? new JSONArray() : d.chargingSessionsSeit(seit, 300);
        }

        public JSONArray ortskreise() {
            JSONArray a = new JSONArray();
            try {
                for (at.greenchili.surfmate.charging.TariffProfile p
                        : at.greenchili.surfmate.charging.TariffManager.getInstance().getProfiles()) {
                    if (p.isEnabled()) a.put(p.toJson());
                }
            } catch (Throwable ignored) {
            }
            return a;
        }

        public JSONArray planerStopps() {
            try {
                Object[] r = at.greenchili.surfmate.planer.PlanerRoute.get().status(System.currentTimeMillis());
                JSONObject o = (JSONObject) r[1];
                return "angenommen".equals(o.optString("status")) ? o.optJSONArray("stopps") : null;
            } catch (Throwable t) {
                return null;
            }
        }

        public boolean steht() {
            try {
                at.greenchili.surfmate.byd.BydVehicleData v = at.greenchili.surfmate.byd.BydDataCollector.getInstance().getData();
                return v != null && v.gearMode == 1 && v.speedKmh < 1;   // Gang P, keine Fahrt
            } catch (Throwable t) {
                return false;
            }
        }

        public boolean nachfrageSetzen(long id, String stand) {
            at.greenchili.surfmate.monitor.SocHistoryDatabase d = at.greenchili.surfmate.monitor.SocHistoryDatabase.getInstance();
            return d != null && d.setChargingNachfrage(id, stand);
        }

        public String ortMerken(String name, double lat, double lng, int radiusM, double rate, boolean dc,
                                String waehrung, boolean gratis) {
            try {
                at.greenchili.surfmate.charging.TariffProfile p = at.greenchili.surfmate.charging.TariffManager.getInstance().add(
                        name, lat, lng, radiusM, dc ? 0 : rate, dc ? rate : 0, waehrung, gratis && !dc, gratis && dc);
                return p == null ? null : p.getId();
            } catch (Throwable t) {
                return null;
            }
        }

        public JSONArray sitzungen(long von, long bis) {
            try {
                return at.greenchili.surfmate.monitor.SocHistoryDatabase.getInstance()
                        .getChargingSessionsV2Range(von, Math.max(von, bis - 1), 5000, 0);
            } catch (Throwable t) {
                return new JSONArray();
            }
        }

        public double km(long von, long bis) {
            try {
                at.greenchili.surfmate.trips.TripAnalyticsManager m =
                        at.greenchili.surfmate.daemon.CameraDaemon.getTripAnalyticsManager();
                at.greenchili.surfmate.trips.TripDatabase db = m == null ? null : m.getDatabase();
                if (db == null) return 0;
                double s = 0;
                for (at.greenchili.surfmate.trips.TripRecord t : db.getTripsBetween(von, Math.max(von, bis - 1), 100_000, 0)) {
                    if (t.distanceKm > 0 && t.distanceKm < 5000) s += t.distanceKm;
                }
                return s;
            } catch (Throwable t) {
                return 0;
            }
        }

        public String standardWaehrung() {
            JSONObject t = config().optJSONObject("tripAnalytics");
            return t == null ? "" : t.optString("currency", "");
        }

        public Object[] planerGet(String op, String[] kopf) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(PlanerSchluessel.BASIS + op).openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(8_000);
                c.setReadTimeout(15_000);
                c.setRequestProperty("Accept", "application/json");
                c.setRequestProperty("User-Agent", "Panoptes");
                if (kopf != null) c.setRequestProperty(kopf[0], kopf[1]);   // nur im Header, nie in URL/Log
                int code = c.getResponseCode();
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                String text = "";
                if (in != null) {
                    try (InputStream i = in) {
                        ByteArrayOutputStream b = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = i.read(buf)) > 0) {
                            b.write(buf, 0, n);
                            if (b.size() > 2 * 1024 * 1024) throw new IllegalStateException("Antwort zu groß");
                        }
                        text = new String(b.toByteArray(), StandardCharsets.UTF_8);
                    }
                }
                return new Object[] {code, text};
            } catch (Exception e) {
                return null;
            } finally {
                if (c != null) c.disconnect();
            }
        }
    };
}
