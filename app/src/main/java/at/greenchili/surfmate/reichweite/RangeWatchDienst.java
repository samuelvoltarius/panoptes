package at.greenchili.surfmate.reichweite;

import at.greenchili.surfmate.config.UnifiedConfigManager;
import at.greenchili.surfmate.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * F1 im Daemon: holt die Planer-Route, misst alle 10 s und hält die {@link RangeWatch.Lage} für
 * {@code GET /api/navi/unterwegs} bereit. Keine Fahrzeugbefehle, nichts am Auto ausgelöst.
 * Zum Planer geht der Abruf der eigenen Route (Mitglieds-Schlüssel), auto-status
 * „navigation“ beim Start bzw. Stoppwechsel und „abgelehnt“ beim Verwerfen eines Vorschlags
 * (Vertrag §3a). Die Position geht nur bei der Neuplanung ({@link Neuplanung}, Stufe
 * „Reicht nicht“, höchstens 1× je 5 min) mit – sonst nie.
 *
 * <p>Band am Mittelschirm ({@link BandRegel}, Einstellung {@code planner.band}, Standard an) und
 * Ansage ({@code planner.ansage}, Standard aus). Nichts geht automatisch ins Navi.
 */
public final class RangeWatchDienst {

    private static final DaemonLogger logger = DaemonLogger.getInstance("RangeWatch");
    static final long MESS_MS = 10_000L;
    static final long PLAN_MS = 60_000L;
    private static final String BASIS = "https://byd.serveone.at/ev/api/";

    private static volatile RangeWatchDienst instance;

    private RangeWatch watch;
    private String planKennung;
    private long planAbgeholt;
    private volatile JSONObject letzteAntwort = inaktiv("startet");
    private int gemeldeterStopp = -1;
    /** Live-Belegung des nächsten Stopps (Name, Zeitpunkt der Abfrage, Ergebnis). */
    static final long BELEGUNG_MS = 120_000L;
    static final double BELEGUNG_AB_KM = 60.0;
    private String belegungFuer;
    private long belegungZeit;      // letzter Abfrageversuch
    private long belegungStand;     // letzte erfolgreiche Antwort
    private StoppBelegung belegung;
    /** Plan-Rohdaten für die Neuplanung (Ziel, Fahrzeugprofil). */
    private JSONObject planRoh;
    private final Neuplanung neuplanung = new Neuplanung();
    // 0.1.16 Planer §17 (Neuplanung unterwegs, nur mit Einwilligung planner.abweichung)
    private final PlanAbweichung abweichung = new PlanAbweichung();
    private volatile PlanAbweichung.Befund letzterBefund;
    private final BandRegel band;
    private ScheduledExecutorService exec;

    private RangeWatchDienst() {
        this(at.greenchili.surfmate.server.Messages::get);
    }

    RangeWatchDienst(BandRegel.Texte texte) {
        band = new BandRegel(texte);
    }

    /**
     * Eigener Schalter „Unterwegs-Hilfe“ ({@code planner.unterwegs}, Koordination 0.1.9): ohne ihn ruft der
     * Dienst den Planer nie auf. Neue Installation aus; Bestand mit gesetztem Planer-Schlüssel an.
     */
    static final String SCHALTER = "unterwegs";

    /** Schalter an? Nur ein ausdrückliches true (festgeschrieben beim ersten Start, s. {@link #bestandsWert}). */
    static boolean an(JSONObject planner) {
        return planner != null && planner.optBoolean(SCHALTER, false);
    }

    /**
     * Wert, der beim ersten Start mit 0.1.9 festgeschrieben wird, oder null (schon gesetzt). Bestand mit
     * Geräte- oder Mitglieds-Schlüssel nutzte die Hilfe schon → an; sonst aus. Festschreiben ist nötig, damit
     * ein neuer Nutzer, der sich später mit dem Planer verbindet, sie nicht stillschweigend eingeschaltet bekommt.
     */
    static Boolean bestandsWert(JSONObject planner) {
        if (planner != null && planner.has(SCHALTER)) return null;
        if (planner == null) return Boolean.FALSE;
        boolean schluessel = !planner.optString("geraeteSchluessel", "").trim().isEmpty()
                || !planner.optString("memberToken", "").trim().isEmpty();
        return schluessel;
    }

    private static void festschreiben() {
        try {
            Boolean w = bestandsWert(plannerConfig());
            if (w == null) return;
            UnifiedConfigManager.updateSection("planner", new JSONObject().put(SCHALTER, w.booleanValue()));
            logger.info("Unterwegs-Hilfe festgeschrieben: " + (w ? "an (Bestand mit Planer-Schlüssel)" : "aus"));
        } catch (Throwable t) {
            logger.warn("Unterwegs-Hilfe festschreiben: " + t.getMessage());
        }
    }

    public static synchronized void start() {
        if (instance != null) return;
        festschreiben();
        RangeWatchDienst d = new RangeWatchDienst();
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RangeWatch");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(() -> {
            try {
                d.takt(System.currentTimeMillis());
            } catch (Throwable t) {
                logger.warn("takt: " + t.getMessage());
            }
        }, MESS_MS, MESS_MS, TimeUnit.MILLISECONDS);
        d.exec = exec;
        instance = d;
    }

    /**
     * Fahrer verwirft den Neuplanungs-Vorschlag ({@code POST /api/navi/unterwegs/ablehnen}):
     * Vorschlag weg, 30 min kein neuer, Planer bekommt auto-status „abgelehnt“.
     */
    public static JSONObject ablehnen() {
        RangeWatchDienst d = instance;
        JSONObject o = new JSONObject();
        try {
            if (d == null || d.exec == null) {
                o.put("ok", false);
                o.put("grund", "aus");
                return o;
            }
            d.exec.execute(() -> {
                try {
                    d.ablehnenIntern(System.currentTimeMillis());
                } catch (Throwable t) {
                    logger.warn("ablehnen: " + t.getMessage());
                }
            });
            o.put("ok", true);
        } catch (Exception ignored) {
        }
        return o;
    }

    void ablehnenIntern(long now) {
        boolean war = neuplanung.ablehnen(now);
        JSONObject a = letzteAntwort;
        if (a != null && a.has("vorschlag")) {
            try {
                JSONObject kopie = new JSONObject(a.toString());   // HTTP-Threads lesen die alte weiter
                kopie.remove("vorschlag");
                letzteAntwort = kopie;
            } catch (Exception ignored) {
            }
        }
        if (!war) return;
        JSONObject planner = plannerConfig();
        String[] token = at.greenchili.surfmate.planer.PlanerSchluessel.kopf();
        if (token == null || planner == null) return;
        JSONObject b = new JSONObject();
        try {
            String autoId = planner.optString("autoId", "");
            if (autoId.matches("^a-[0-9a-f]{16}$")) b.put("auto", autoId);
            b.put("status", "abgelehnt");
        } catch (Exception ignored) {
        }
        http("POST", "auto-status", token, b.toString());
        logger.info("Neuplanungs-Vorschlag verworfen");
    }

    private static JSONObject plannerConfig() {
        try {
            JSONObject root = UnifiedConfigManager.loadConfig();
            return root == null ? null : root.optJSONObject("planner");
        } catch (Throwable t) {
            return null;
        }
    }

    /** Nach „Route übernehmen“: beim nächsten Takt sofort neu laden statt bis zu 60 s zu warten. */
    public static void routeNeuLaden() {
        RangeWatchDienst d = instance;
        if (d != null && d.exec != null) d.exec.execute(() -> d.planAbgeholt = 0);
    }

    /** Antwort für GET /api/navi/unterwegs. */
    public static JSONObject status() {
        RangeWatchDienst d = instance;
        return d == null ? inaktiv("aus") : d.letzteAntwort;
    }

    void takt(long now) {
        boolean faehrt = false;
        try {
            faehrt = at.greenchili.surfmate.monitor.AccMonitor.isAccStateAuthoritative()
                    && at.greenchili.surfmate.monitor.AccMonitor.isAccOn();
        } catch (Throwable ignored) {
        }
        if (!faehrt) {
            letzteAntwort = inaktiv("steht");
            band.zuruecksetzen();
            return;
        }
        JSONObject planner = plannerConfig();
        if (!an(planner)) {
            letzteAntwort = inaktiv("aus");
            band.zuruecksetzen();
            watch = null;
            return;
        }
        // Geräte-Schlüssel aus „Mit Routenplaner verbinden“ bevorzugt, sonst Mitglieds-Schlüssel.
        String[] token = planner == null ? null : at.greenchili.surfmate.planer.PlanerSchluessel.kopf();
        if (token == null) {
            letzteAntwort = inaktiv("kein_schluessel");
            watch = null;
            return;
        }
        if (now - planAbgeholt >= PLAN_MS || watch == null && planAbgeholt == 0) {
            planAbgeholt = now;
            planHolen(token, planner.optString("autoId", ""), at.greenchili.surfmate.laden.LadenVorlieben.ankunftReserve());
        }
        if (watch == null) {
            letzteAntwort = inaktiv("keine_route");
            return;
        }
        at.greenchili.surfmate.monitor.GpsMonitor g = at.greenchili.surfmate.monitor.GpsMonitor.getInstance();
        at.greenchili.surfmate.byd.BydVehicleData v = at.greenchili.surfmate.byd.BydDataCollector.getInstance().getData();
        long gpsAlter = now - g.getLastUpdate();
        if (v == null || g.getLastUpdate() <= 0 || gpsAlter > 60_000L) {
            letzteAntwort = inaktiv("keine_position");
            return;
        }
        double soc = v.socPercent > 0 && v.socPercent <= 100 ? v.socPercent : Double.NaN;
        boolean frisch = belegung != null && belegung.voll && now - belegungStand < 2 * BELEGUNG_MS;
        RangeWatch.Lage l = watch.messung(now, g.getLatitude(), g.getLongitude(), soc, v.outsideTempC,
                frisch ? belegungFuer : null);
        JSONObject a = antwort(l);
        if (l.aktiv && !l.naechsterIstZiel && belegung != null && l.naechsterName.equals(belegungFuer)) {
            try {
                a.getJSONObject("naechsterStopp").put("belegung", belegung.alsJson());
            } catch (Exception ignored) {
            }
        }
        if (l.aktiv) {
            abweichungPruefen(now, token, planner, l, g.getLatitude(), g.getLongitude(), soc);
            Neuplanung.Vorschlag vs = vorschlag(now, token, planner, l, g.getLatitude(), g.getLongitude(), soc);
            if (letzterBefund != null) {
                try {
                    a.put("abweichung", letzterBefund.alsJson());
                } catch (Exception ignored) {
                }
            }
            if (vs != null) {
                try {
                    a.put("vorschlag", vs.alsJson());
                } catch (Exception ignored) {
                }
            }
            letzteAntwort = a;
            stoppMelden(token, planner.optString("autoId", ""), l);
            belegungHolen(now, l);
            bandZeigen(now, planner, l, vs);
        } else {
            letzteAntwort = a;
        }
    }

    /**
     * 0.1.16, Planer §17: nur mit Einwilligung {@code planner.abweichung}. Höchstens alle 10 min bzw. je 5 pp Akku
     * Route + gerundete Position + Akku an {@code plan-abweichung} (öffentlich, zustandslos, ohne Schlüssel). Sagt der
     * Planer {@code neu_planen}, wird mit seiner {@code neu_plan_anfrage} neu geplant und der Vorschlag wie die lokale
     * Neuplanung angezeigt (nichts automatisch ins Navi; Ablehnen sperrt auch diesen Weg 30 min).
     */
    private void abweichungPruefen(long now, String[] token, JSONObject planner, RangeWatch.Lage l,
                                   double lat, double lon, double soc) {
        if (!planner.optBoolean("abweichung", false) || planRoh == null) return;
        if (!abweichung.faellig(now, soc)) return;
        abweichung.gefragt(now, soc);
        JSONObject body = PlanAbweichung.anfrage(planRoh, lat, lon, soc, l.reserveWirksam);
        if (body == null) return;
        PlanAbweichung.Befund b = PlanAbweichung.auswerten(http("POST", "plan-abweichung", null, body.toString()));
        letzterBefund = b;
        if (!b.ok) return;
        logger.info("Plan-Abweichung: neu_planen=" + b.neuPlanen + " " + b.gruende);
        if (!b.neuPlanen) {
            neuplanung.abweichungEntwarnt();
            return;
        }
        if (b.neuPlanAnfrage == null || !neuplanung.abweichungErlaubt(now)) return;
        JSONObject planBody = PlanAbweichung.planAnfrage(b.neuPlanAnfrage, planRoh.optString("fahrzeug", ""),
                planner.optString("autoId", ""));
        // plan ist öffentlich; ein Geräte-Schlüssel (Bereich „auto“) darf plan nicht – dann ohne.
        String[] k = at.greenchili.surfmate.planer.PlanerSchluessel.KOPF_MITGLIED.equals(token[0]) ? token : null;
        JSONObject res = http("POST", "plan", k, at.greenchili.surfmate.planer.FahrzeugWahl.einsetzen(
                at.greenchili.surfmate.laden.LadenVorlieben.inPlanAnfrage(planBody.toString())));
        if (res == null) return;
        Neuplanung.Vorschlag v = Neuplanung.auswerten(res, now);
        v.quelle = Neuplanung.QUELLE_ABWEICHUNG;
        if (!"keiner".equals(v.art)) neuplanung.ergebnis(v);
        logger.info("Neuplanung (Abweichung): " + v.art);
    }

    /** Neuplanung bei „Reicht nicht“ (≤ 1× je 5 min); Position zum Planer nur hier und in abweichungPruefen. */
    private Neuplanung.Vorschlag vorschlag(long now, String[] token, JSONObject planner, RangeWatch.Lage l,
                                           double lat, double lon, double soc) {
        if (neuplanung.faellig(now, l.stufe) && planRoh != null) {
            JSONObject ziel = planRoh.optJSONObject("ziel");
            double zLat = ziel == null ? Double.NaN : ziel.optDouble("lat", Double.NaN);
            double zLon = ziel == null ? Double.NaN : ziel.optDouble("lon", Double.NaN);
            if (!Double.isNaN(zLat) && !Double.isNaN(zLon)) {
                neuplanung.versucht(now);
                JSONObject body = Neuplanung.anfrage(lat, lon, soc, zLat, zLon, l.reserveWirksam,
                        planRoh.optString("fahrzeug", ""), planner.optString("autoId", ""));
                // plan ist öffentlich; ein Geräte-Schlüssel (Bereich „auto“) darf plan nicht – dann ohne.
                String[] k = at.greenchili.surfmate.planer.PlanerSchluessel.KOPF_MITGLIED.equals(token[0]) ? token : null;
                JSONObject res = http("POST", "plan", k, at.greenchili.surfmate.planer.FahrzeugWahl.einsetzen(at.greenchili.surfmate.laden.LadenVorlieben.inPlanAnfrage(body.toString())));
                if (res != null) {
                    Neuplanung.Vorschlag v = Neuplanung.auswerten(res, now);
                    neuplanung.ergebnis(v);
                    logger.info("Neuplanung: " + v.art);
                }
            }
        }
        return neuplanung.aktuell(l.stufe);
    }

    /** Band am Mittelschirm (Overlay im App-Prozess) und optionale Ansage. Nur Anzeige. */
    private void bandZeigen(long now, JSONObject planner, RangeWatch.Lage l, Neuplanung.Vorschlag vs) {
        if (!planner.optBoolean("band", true)) return;
        StoppBelegung b = belegung != null && l.naechsterName.equals(belegungFuer)
                && now - belegungStand < 2 * BELEGUNG_MS ? belegung : null;
        BandRegel.Anzeige an = band.pruefe(now, l, b, vs, planner.optBoolean("ansage", false));
        if (an == null) return;
        at.greenchili.surfmate.byd.MessageOverlayController.showBand(an.titel, an.zeile2, an.stufe, an.dauerMs);
        if (an.ansagen) at.greenchili.surfmate.byd.AudioPlaybackController.speak(an.titel, "voice");
    }

    private void planHolen(String[] token, String autoId, double reserve) {
        String q = autoId.matches("^a-[0-9a-f]{16}$") ? "?auto=" + autoId : "";
        JSONObject res = http("GET", "meine-route" + q, token, null);
        if (res == null) return;   // Netz weg: alten Plan behalten
        JSONObject plan = res.optJSONObject("plan");
        // 0.1.9: nur eine am Auto per Tipp angenommene Route wird überwacht (nichts automatisch übernehmen)
        if (plan != null && !at.greenchili.surfmate.planer.PlanerRoute.istAngenommen(plan)) plan = null;
        String k = PlanLeser.kennung(plan);
        if (plan == null) {
            if (watch != null) logger.info("Planer-Route entfernt");
            watch = null;
            planKennung = null;
            planRoh = null;
            neuplanung.zuruecksetzen();
            abweichung.zuruecksetzen();
            letzterBefund = null;
            return;
        }
        if (k.equals(planKennung) && watch != null) return;
        RangeWatch.Plan p = PlanLeser.lies(plan, reserve);
        watch = p == null ? null : new RangeWatch(p);
        planKennung = k;
        planRoh = plan;
        neuplanung.zuruecksetzen();
        abweichung.zuruecksetzen();
        letzterBefund = null;
        band.zuruecksetzen();
        gemeldeterStopp = -1;
        belegung = null;
        belegungFuer = null;
        logger.info("Planer-Route übernommen: " + plan.optString("titel", "?"));
    }

    /** Alle 2 min (bzw. beim Stoppwechsel) die Belegung des nächsten Stopps, wenn er < 60 km entfernt ist. */
    private void belegungHolen(long now, RangeWatch.Lage l) {
        if (l.naechsterIstZiel || l.naechsterKm > BELEGUNG_AB_KM) return;
        RangeWatch.Stopp ziel = null;
        for (RangeWatch.Stopp s : watch.plan().stopps) {
            if (s.name.equals(l.naechsterName)) { ziel = s; break; }
        }
        if (ziel == null || Double.isNaN(ziel.lat) || Double.isNaN(ziel.lon)) return;
        if (now - belegungZeit < BELEGUNG_MS && (ziel.name.equals(belegungFuer) || now - belegungZeit < 20_000L)) return;
        if (at.greenchili.surfmate.planer.PlanerBackoff.UMKREIS.warteMs(now) > 0) return;   // Planer gerade weg
        belegungZeit = now;
        // Abfrage um den Stopp (nicht um das Auto), öffentlich, ohne Mitglieds-Schlüssel.
        JSONObject res = http("POST", "umkreis", null, StoppBelegung.anfrage(ziel.lat, ziel.lon).toString());
        if (res == null) return;   // Netz weg: letzte Belegung bleibt höchstens 4 min gültig
        belegung = StoppBelegung.auswerten(res, ziel.lat, ziel.lon, ziel.kw * 0.5);
        belegungFuer = ziel.name;
        belegungStand = now;
    }

    private void stoppMelden(String[] token, String autoId, RangeWatch.Lage l) {
        int idx = 0;
        if (!l.naechsterIstZiel) {
            for (RangeWatch.Stopp s : watch.plan().stopps) {
                if (s.name.equals(l.naechsterName)) break;
                idx++;
            }
        } else {
            idx = watch.plan().stopps.size();
        }
        if (idx == gemeldeterStopp) return;
        gemeldeterStopp = idx;
        JSONObject b = new JSONObject();
        try {
            if (autoId.matches("^a-[0-9a-f]{16}$")) b.put("auto", autoId);
            b.put("status", "navigation");
            b.put("stopp", Math.min(20, idx));
        } catch (Exception ignored) {
        }
        http("POST", "auto-status", token, b.toString());
    }

    static JSONObject antwort(RangeWatch.Lage l) {
        if (!l.aktiv) return inaktiv("nicht_auf_route");
        JSONObject o = new JSONObject();
        try {
            o.put("aktiv", true);
            o.put("stufe", l.stufe == null ? JSONObject.NULL : l.stufe.name().toLowerCase(java.util.Locale.ROOT));
            o.put("prognoseProzent", runde(l.prognoseProzent));
            o.put("sollProzent", runde(l.sollProzent));
            o.put("istProzent", runde(l.istProzent));
            o.put("delta", runde(l.delta));
            o.put("kmGefahren", runde(l.kmGefahren));
            o.put("faktor", Math.round(l.faktor * 1000) / 1000.0);
            o.put("reserve", runde(l.reserveWirksam));
            JSONObject n = new JSONObject();
            n.put("name", l.naechsterName);
            n.put("km", runde(l.naechsterKm));
            n.put("istZiel", l.naechsterIstZiel);
            o.put("naechsterStopp", n);
            org.json.JSONArray v = new org.json.JSONArray();
            for (double[] p : l.verlauf) {
                JSONObject q = new JSONObject();
                q.put("km", runde(p[0]));
                q.put("soll", runde(p[1]));
                q.put("ist", runde(p[2]));
                v.put(q);
            }
            o.put("verlauf", v);
        } catch (Exception ignored) {
        }
        return o;
    }

    static JSONObject inaktiv(String grund) {
        JSONObject o = new JSONObject();
        try {
            o.put("aktiv", false);
            o.put("grund", grund);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static double runde(double x) { return Math.round(x * 10) / 10.0; }

    /** Planer-Aufruf mit Mitglieds-Schlüssel im Header (nie in der URL). */
    private static JSONObject http(String method, String op, String[] token, String body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(BASIS + op).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(5_000);
            c.setReadTimeout(8_000);
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
            if (code != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n, total = 0;
                while ((n = in.read(buf)) > 0 && total < 2_000_000) {
                    b.write(buf, 0, n);
                    total += n;
                }
                return new JSONObject(new String(b.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
