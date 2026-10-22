package at.greenchili.surfmate.planer;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Vertrag 9 „Auto-Zustand an den Planer“ (PLANER_AUTO_ZUSTAND.md, 0.1.9). Nur mit der Einwilligung
 * {@code planner.zustandSenden} (Regel 2, Standard AUS).
 *
 * <ul>
 *   <li>Gesendet werden SoC, Reichweite laut Auto, ob/mit wie viel kW geladen wird und die Messzeit –
 *       NIE Position, VIN oder Kennzeichen (der Body wird nur aus diesen Feldern gebaut).</li>
 *   <li>Bei Änderung (SoC ±1 %, Wechsel laedt, Ladeleistung ±1 kW), höchstens alle 5 min; nicht, solange
 *       das Auto schläft (keine frische Messung).</li>
 *   <li>Netzfehler (DNS/Socket): Backoff 1 → 30 min, ehrlicher Status; 429 → warte_s abwarten.</li>
 *   <li>Einwilligung zurückgenommen → einmal {@link #WIDERRUF_OP} ({@code auto-zustand/loeschen}, Planer d40ec1a)
 *       mit {@code auto}: löscht Zustand und Position am Server, das Gerät bleibt. Nur Position zurückgenommen →
 *       {@code "nur":"position"}. Bei Netzfehler später wiederholen.
 *       NIE {@code geraet/abmelden} – das entfernt laut Planer (21.10.) auch das Gerät selbst.</li>
 *   <li>Nachtrag Position: nur mit eigener Einwilligung {@code planner.positionSenden} (Standard AUS) zusätzlich
 *       {@code "position":{"lat","lon"}}; beim Abstellen einmal senden. Server hält sie nur 15 min im RAM.</li>
 * </ul>
 * Rein bis auf {@link #laufzeit()}; Netz, Messung, Einwilligung und Uhr kommen von außen.
 */
public final class AutoZustand {

    static final long MIN_ABSTAND_MS = 5 * 60_000L;
    static final long FRISCH_MS = 3 * 60_000L;
    static final long BACKOFF_START_MS = 60_000L;
    static final long BACKOFF_MAX_MS = 30 * 60_000L;
    /** Server-Bremse: mindestens so viel Abstand, auch beim Abstell-Versand. */
    static final long SERVER_BREMSE_MS = 60_000L;
    static final long GPS_FRISCH_MS = 2 * 60_000L;
    /** Löschen des Zustands bei Widerruf – eigener Aufruf, der das Gerät NICHT entfernt (mit dem Planer abzustimmen). */
    static final String WIDERRUF_OP = "auto-zustand/loeschen";

    /** Eine Messung am Auto. NaN = unbekannt. */
    public static final class Messung {
        public final double soc, reichweiteKm, ladeKw;
        public final boolean laedt;
        public final long zeitMs;
        /** Position (NaN = keine) und ihr Alter; accAn null = unbekannt. */
        public double lat = Double.NaN, lon = Double.NaN;
        public long gpsZeitMs;
        public Boolean accAn;
        public Messung(double soc, double reichweiteKm, boolean laedt, double ladeKw, long zeitMs) {
            this.soc = soc; this.reichweiteKm = reichweiteKm; this.laedt = laedt; this.ladeKw = ladeKw; this.zeitMs = zeitMs;
        }
        public Messung mitPosition(double lat, double lon, long gpsZeitMs) {
            this.lat = lat; this.lon = lon; this.gpsZeitMs = gpsZeitMs;
            return this;
        }
        public Messung mitAcc(Boolean an) {
            this.accAn = an;
            return this;
        }
    }

    /** Planer-Aufruf mit Geräte-Schlüssel; null bei Netzfehler. */
    public interface Netz {
        PlanerVerbindung.Antwort post(String op, String[] token, JSONObject body);
    }

    /** Umgebung (Laufzeit bzw. Test). */
    public interface Umgebung {
        boolean einwilligung();
        /** Eigene Einwilligung „Position für den Routenstart senden“ (Standard AUS). */
        boolean positionEinwilligung();
        String autoId();
        String[] geraeteToken();
        Messung messung();
        /** Dauerhaft gemerkt: Hat dieses Auto schon einmal erfolgreich gesendet? (nur dann gibt es etwas zu widerrufen) */
        boolean hatGesendet();
        void merkeGesendet(boolean v);
        /** Dauerhaft gemerkt: Wurde je eine Position gesendet? (nur dann gibt es sie zu widerrufen) */
        boolean hatPositionGesendet();
        void merkePositionGesendet(boolean v);
        /**
         * Kern 5, LADEN_VERTRAG A7: Lade-Vorlieben (planer-Stück) für /ev/ im Browser, nur mit eigener Einwilligung
         * {@code planner.vorliebenSenden} (Standard AUS); null = keine Einwilligung oder nichts gespeichert.
         * Der Planer hält sie nur im RAM (bis 24 h).
         */
        default JSONObject vorlieben() { return null; }
        /** Dauerhaft gemerkt: Wurden je Vorlieben gesendet? (nur dann gibt es sie zu widerrufen) */
        default boolean hatVorliebenGesendet() { return false; }
        default void merkeVorliebenGesendet(boolean v) { }
        /** Planer-Profilname (Stammdaten „fahrzeug“, Vertrag 9; Teil der Einwilligung „Zustand senden“) oder null. */
        default String fahrzeug() { return null; }
    }

    private final Netz netz;
    private final Umgebung env;
    private Messung zuletzt;
    private long letzterVersandMs, naechsterVersuchMs;
    private int netzFehler;
    private String status = "aus";
    private Boolean letzteAcc;
    private String vorliebenGesendet;   // Inhalt der zuletzt gesendeten Vorlieben (Vergleich „geändert“)

    public AutoZustand(Netz netz, Umgebung env) {
        this.netz = netz;
        this.env = env;
    }

    /** Lohnt ein Versand? (reine Entscheidung) */
    static boolean geaendert(Messung neu, Messung alt) {
        if (alt == null) return true;
        if (neu.laedt != alt.laedt) return true;
        if (!Double.isNaN(neu.soc) && (Double.isNaN(alt.soc) || Math.abs(neu.soc - alt.soc) >= 1.0)) return true;
        return neu.laedt && !Double.isNaN(neu.ladeKw)
                && (Double.isNaN(alt.ladeKw) || Math.abs(neu.ladeKw - alt.ladeKw) >= 1.0);
    }

    /** Body nach Vertrag 9 – ausschließlich erlaubte Felder. */
    static JSONObject body(String autoId, Messung m) throws Exception {
        return body(autoId, m, false, 0L);
    }

    /** Wie oben; Position nur mit Einwilligung, gültigen Koordinaten und frischem Fix (genau lat/lon). */
    static JSONObject body(String autoId, Messung m, boolean mitPosition, long now) throws Exception {
        JSONObject b = new JSONObject();
        b.put("auto", autoId);
        if (!Double.isNaN(m.soc)) b.put("soc", Math.round(m.soc * 10) / 10.0);
        if (!Double.isNaN(m.reichweiteKm) && m.reichweiteKm > 0) b.put("reichweite_km", Math.round(m.reichweiteKm));
        b.put("laedt", m.laedt);
        if (m.laedt && !Double.isNaN(m.ladeKw) && m.ladeKw > 0) b.put("ladeleistung_kw", Math.round(m.ladeKw * 10) / 10.0);
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        b.put("zeit", f.format(new Date(m.zeitMs)));
        if (mitPosition && gueltig(m.lat, m.lon) && now - m.gpsZeitMs <= GPS_FRISCH_MS) {
            b.put("position", new JSONObject()
                    .put("lat", Math.round(m.lat * 1e4) / 1e4)
                    .put("lon", Math.round(m.lon * 1e4) / 1e4));
        }
        return b;
    }

    static boolean gueltig(double lat, double lon) {
        return !Double.isNaN(lat) && !Double.isNaN(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180
                && !(lat == 0 && lon == 0);
    }

    /** Ein Takt (z. B. jede Minute). */
    public synchronized void takt(long now) {
        String autoId = env.autoId();
        String[] token = env.geraeteToken();
        if (!env.einwilligung()) {
            // Widerruf: einmal am Server löschen (auch Ladestand/Faktor/Route dieses Autos).
            // NUR wenn je gesendet wurde – sonst würden Planer-Daten von Nutzern ohne Einwilligung gelöscht.
            if (env.hatGesendet() && token != null && autoId != null && now >= naechsterVersuchMs) {
                try {
                    PlanerVerbindung.Antwort a = netz.post(WIDERRUF_OP, token, new JSONObject().put("auto", autoId));
                    if (a != null && a.code == 200) { env.merkeGesendet(false); env.merkePositionGesendet(false); }
                    else naechsterVersuchMs = now + BACKOFF_START_MS;
                } catch (Exception ignored) {
                }
            }
            if (env.hatVorliebenGesendet() && token != null && autoId != null && now >= naechsterVersuchMs) {
                vorliebenWiderrufen(token, autoId, now);
            }
            zuletzt = null;
            vorliebenGesendet = null;
            status = "aus";
            return;
        }
        if (token == null || autoId == null || !autoId.matches("^[A-Za-z0-9_-]{1,32}$")) { status = "kein_schluessel"; return; }
        if (!env.positionEinwilligung() && env.hatPositionGesendet() && now >= naechsterVersuchMs) {
            // Planer d40ec1a: nur die Position löschen, Gerät und Zustand bleiben.
            try {
                PlanerVerbindung.Antwort w = netz.post(WIDERRUF_OP, token,
                        new JSONObject().put("auto", autoId).put("nur", "position"));
                if (w != null && w.code == 200) env.merkePositionGesendet(false);
                else { naechsterVersuchMs = now + BACKOFF_START_MS; return; }
            } catch (Exception ignored) {
                return;
            }
        }
        JSONObject vorlieben = env.vorlieben();
        if (vorlieben == null && env.hatVorliebenGesendet() && now >= naechsterVersuchMs) {
            // Widerruf „Vorlieben senden“: vorlieben:null löscht die RAM-Kopie beim Planer sofort.
            if (!vorliebenWiderrufen(token, autoId, now)) return;
        }
        if (now < naechsterVersuchMs) return;
        Messung m = env.messung();
        if (m == null || Double.isNaN(m.soc) || now - m.zeitMs > FRISCH_MS) { status = "auto_schlaeft"; return; }
        boolean abgestellt = Boolean.TRUE.equals(letzteAcc) && Boolean.FALSE.equals(m.accAn);
        if (m.accAn != null) letzteAcc = m.accAn;
        if (abgestellt) {
            // Beim Abstellen einmal senden (Server-Bremse 60 s beachten).
            if (zuletzt != null && now - letzterVersandMs < SERVER_BREMSE_MS) return;
        } else {
            boolean vorliebenNeu = vorlieben != null && !vorlieben.toString().equals(vorliebenGesendet);
            if (vorliebenNeu) {
                // geänderte Vorlieben: bald senden, aber die Server-Bremse (60 s) beachten
                if (zuletzt != null && now - letzterVersandMs < SERVER_BREMSE_MS) return;
            } else {
                if (zuletzt != null && now - letzterVersandMs < MIN_ABSTAND_MS) return;
                if (!geaendert(m, zuletzt)) return;
            }
        }
        PlanerVerbindung.Antwort a;
        boolean gesendetMitPosition = false;
        try {
            JSONObject b = body(autoId, m, env.positionEinwilligung(), now);
            gesendetMitPosition = b.has("position");
            if (vorlieben != null) b.put("vorlieben", vorlieben);
            String fz = env.fahrzeug();
            if (fz != null && !fz.isEmpty()) b.put("fahrzeug", fz);   // Planer 3: Modell je auto-Kennung
            a = netz.post("auto-zustand", token, b);
        } catch (Exception e) {
            a = null;
        }
        if (a == null || a.code == 0 || a.code >= 500) {
            netzFehler++;
            naechsterVersuchMs = now + Math.min(BACKOFF_MAX_MS, BACKOFF_START_MS << Math.min(netzFehler - 1, 5));
            status = "planer_nicht_erreichbar";
            return;
        }
        netzFehler = 0;
        if (a.code == 429) {
            long warte = Math.max(60, a.body.optLong("warte_s", 300)) * 1000L;
            naechsterVersuchMs = now + warte;
            status = "gebremst";
            return;
        }
        if (a.code == 401) { status = "schluessel_ungueltig"; naechsterVersuchMs = now + BACKOFF_MAX_MS; return; }
        if (a.code != 200) { status = "abgelehnt_" + a.code; naechsterVersuchMs = now + BACKOFF_MAX_MS; return; }
        zuletzt = m;
        letzterVersandMs = now;
        if (!env.hatGesendet()) env.merkeGesendet(true);
        if (gesendetMitPosition && !env.hatPositionGesendet()) env.merkePositionGesendet(true);
        if (vorlieben != null) {
            vorliebenGesendet = vorlieben.toString();
            if (!env.hatVorliebenGesendet()) env.merkeVorliebenGesendet(true);
        }
        status = "gesendet";
    }

    /** {@code auto-zustand} mit {@code "vorlieben": null} (Planer §14: Widerruf). true bei Erfolg. */
    private boolean vorliebenWiderrufen(String[] token, String autoId, long now) {
        try {
            PlanerVerbindung.Antwort w = netz.post("auto-zustand", token,
                    new JSONObject().put("auto", autoId).put("vorlieben", JSONObject.NULL));
            if (w != null && w.code == 200) {
                env.merkeVorliebenGesendet(false);
                vorliebenGesendet = null;
                return true;
            }
            naechsterVersuchMs = now + BACKOFF_START_MS;
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Für Status-API/Diagnose. */
    public synchronized JSONObject zustand() {
        JSONObject o = new JSONObject();
        try {
            o.put("status", status);
            if (letzterVersandMs > 0) o.put("letzterVersand", letzterVersandMs);
            if (netzFehler > 0) o.put("netzFehler", netzFehler);
        } catch (Exception ignored) {
        }
        return o;
    }

    // ---------------------------------------------------------------- Laufzeit (Daemon)

    private static volatile AutoZustand instanz;

    public static AutoZustand laufzeit() {
        AutoZustand z = instanz;
        if (z != null) return z;
        synchronized (AutoZustand.class) {
            if (instanz != null) return instanz;
            AutoZustand neu = new AutoZustand(AutoZustand::post, new LaufzeitUmgebung());
            java.util.concurrent.ScheduledExecutorService ex =
                    java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "PlanerAutoZustand");
                        t.setDaemon(true);
                        return t;
                    });
            ex.scheduleWithFixedDelay(() -> {
                try { neu.takt(System.currentTimeMillis()); } catch (Throwable ignored) { }
            }, 30, 60, java.util.concurrent.TimeUnit.SECONDS);
            instanz = neu;
            return neu;
        }
    }

    static final class LaufzeitUmgebung implements Umgebung {
        private static JSONObject planner() {
            try {
                JSONObject root = at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig();
                return root == null ? null : root.optJSONObject("planner");
            } catch (Throwable t) {
                return null;
            }
        }
        public boolean einwilligung() {
            JSONObject p = planner();
            return p != null && p.optBoolean("zustandSenden", false);
        }
        public String autoId() {
            JSONObject p = planner();
            String id = p == null ? "" : p.optString("autoId", "");
            if (!id.isEmpty()) return id;
            try {
                return at.greenchili.surfmate.server.EinstellungenApiHandler.autoId();   // Bugfix 0.1.9: fehlende Kennung erzeugen
            } catch (Throwable t) {
                return null;
            }
        }
        public String[] geraeteToken() {
            String[] k = PlanerSchluessel.kopf();
            // Vertrag 9: melden darf nur ein verbundenes Gerät (kein Mitglieds-Schlüssel).
            return k != null && PlanerSchluessel.KOPF_GERAET.equals(k[0]) ? k : null;
        }
        public boolean positionEinwilligung() {
            JSONObject p = planner();
            return p != null && p.optBoolean("positionSenden", false);
        }
        public boolean hatGesendet() {
            JSONObject p = planner();
            return p != null && p.optBoolean("zustandGesendet", false);
        }
        public void merkeGesendet(boolean v) {
            try {
                java.util.Map<String, Object> m = new java.util.HashMap<>();
                m.put("zustandGesendet", v);
                at.greenchili.surfmate.config.UnifiedConfigManager.updateValues("planner", m);
            } catch (Throwable ignored) {
            }
        }
        public boolean hatPositionGesendet() {
            JSONObject p = planner();
            return p != null && p.optBoolean("positionGesendet", false);
        }
        public void merkePositionGesendet(boolean v) {
            try {
                java.util.Map<String, Object> m = new java.util.HashMap<>();
                m.put("positionGesendet", v);
                at.greenchili.surfmate.config.UnifiedConfigManager.updateValues("planner", m);
            } catch (Throwable ignored) {
            }
        }
        public String fahrzeug() {
            return FahrzeugWahl.wirksamJetzt();
        }
        public JSONObject vorlieben() {
            JSONObject p = planner();
            if (p == null || !p.optBoolean("vorliebenSenden", false)) return null;
            try {
                return at.greenchili.surfmate.laden.LadenVorlieben.planerStueck(
                        at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig());
            } catch (Throwable t) {
                return null;
            }
        }
        public boolean hatVorliebenGesendet() {
            JSONObject p = planner();
            return p != null && p.optBoolean("vorliebenGesendet", false);
        }
        public void merkeVorliebenGesendet(boolean v) {
            try {
                java.util.Map<String, Object> m = new java.util.HashMap<>();
                m.put("vorliebenGesendet", v);
                at.greenchili.surfmate.config.UnifiedConfigManager.updateValues("planner", m);
            } catch (Throwable ignored) {
            }
        }
        public Messung messung() {
            try {
                at.greenchili.surfmate.byd.BydVehicleData v =
                        at.greenchili.surfmate.byd.BydDataCollector.getInstance().getData();
                if (v == null) return null;
                double soc = v.socPercent > 0 && v.socPercent <= 100 ? v.socPercent : Double.NaN;
                double rw = v.elecRangeKm > 0 ? v.elecRangeKm : Double.NaN;
                boolean laedt = false;
                try { laedt = at.greenchili.surfmate.monitor.ChargingDetector.getInstance().isCharging(); }
                catch (Throwable ignored) { }
                double kw = v.chargingPowerKw > 0 ? v.chargingPowerKw : (v.chargePowerKw > 0 ? v.chargePowerKw : Double.NaN);
                Messung m = new Messung(soc, rw, laedt, kw, v.timestamp);
                try {
                    at.greenchili.surfmate.monitor.GpsMonitor g = at.greenchili.surfmate.monitor.GpsMonitor.getInstance();
                    if (g.getLastUpdate() > 0) m.mitPosition(g.getLatitude(), g.getLongitude(), g.getLastUpdate());
                } catch (Throwable ignored) {
                }
                try {
                    if (at.greenchili.surfmate.monitor.AccMonitor.isAccStateAuthoritative()) {
                        m.mitAcc(at.greenchili.surfmate.monitor.AccMonitor.isAccOn());
                    }
                } catch (Throwable ignored) {
                }
                return m;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    static PlanerVerbindung.Antwort post(String op, String[] token, JSONObject body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(PlanerSchluessel.BASIS + op).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(5_000);
            c.setReadTimeout(8_000);
            c.setRequestProperty(token[0], token[1]);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            c.setDoOutput(true);
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            JSONObject j = new JSONObject();
            if (in != null) {
                try (InputStream s = in) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n, total = 0;
                    while ((n = s.read(buf)) > 0 && total < 65_536) { b.write(buf, 0, n); total += n; }
                    String t = new String(b.toByteArray(), StandardCharsets.UTF_8).trim();
                    if (t.startsWith("{")) j = new JSONObject(t);
                }
            }
            return new PlanerVerbindung.Antwort(code, j);
        } catch (Exception e) {
            return null;   // DNS/Socket: Backoff im Takt
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
