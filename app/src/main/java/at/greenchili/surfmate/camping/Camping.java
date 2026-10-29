package at.greenchili.surfmate.camping;

import at.greenchili.surfmate.byd.BydDataCollector;
import at.greenchili.surfmate.byd.BydVehicleData;
import at.greenchili.surfmate.config.UnifiedConfigManager;
import at.greenchili.surfmate.logging.DaemonLogger;
import at.greenchili.surfmate.monitor.GearMonitor;
import at.greenchili.surfmate.notifications.NotificationBus;
import at.greenchili.surfmate.notifications.NotificationEvent;
import at.greenchili.surfmate.server.Messages;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Calendar;
import java.util.Deque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Camping-Modus zur Laufzeit (CAMPING_PLAN.md, 0.1.16 Stufe 2a). Läuft im Kamera-Daemon neben dem
 * Wächter-Stopp: Takt 60 s, Zustand in kleinen Dateien (überlebt Neustarts), Meldungen über den
 * NotificationBus, Status für {@code /api/camping}.
 *
 * <p><b>Heizweg</b> ({@code camping.heizweg}, Standard „probelauf“): Im Probelauf entscheidet der Heizregler,
 * aber niemand führt es aus – es wird nur protokolliert („würde einschalten: Stufe 29, Gebläse 2“). Ab 0.1.18
 * (27.10.2026) schalten „auto_an“ (lokal) und „auto_an_und_cloud“ (zusätzlich OPENAIR bei Auto aus, hinter
 * dem CloudPowerGate, mit Cloud-Bestätigung, Pause nach zwei Fehlschlägen, Protokoll {@link #CLOUD_PROTOKOLL})
 * echt – nur auf ausdrückliche Wahl des Nutzers. Ohne Heizen ist Camping eine reine Panoptes-Funktion: Wächter „bewohnt“, Ruhe, Akku- und
 * 12-V-Wache, Verbrauch. Panoptes schaltet dabei nie das Auto ab.
 *
 * <p>Schnelle Abfragen ({@link #aktiv()}, {@link #blocksArming()}, {@link #ohneAbschreckung()}) lesen nur
 * volatile Felder – sie werden aus dem GL-Thread des Wächters gerufen (keine I/O).
 */
public final class Camping {

    private static final DaemonLogger logger = DaemonLogger.getInstance("Camping");
    private static final long TAKT_MS = 60_000L;
    static final String ORDNER = "/data/user_de/0/com.android.shell/surfmate/";
    static final String ZUSTAND_DATEI = "camping_zustand.json";
    static final String LERNEN_DATEI = "camping_lernen.json";
    /** Lokale Kopie des Kennfelds; ersetzt das Asset, wenn vorhanden (Expertenbereich). */
    static final String TABELLE_DATEI = "camping_heiztabelle.json";
    static final String ASSET_TABELLE = "camping/heiztabelle.json";
    static final String KATEGORIE_HINWEIS = "camping.hinweis";
    static final String KATEGORIE_WARNUNG = "camping.warnung";
    /** Wächter im Modus „bewohnt“: Außenbewegung nichts (Wächter schläft) oder Notiz (läuft ohne Abschreckung). */
    public static final String AUSSEN_NICHTS = "nichts", AUSSEN_NOTIZ = "notiz";
    /** Einstellung camping.heizweg (Stufe 2b): Probelauf, nur bei Auto an (H1), oder zusätzlich Cloud (H2). */
    public static final String WEG_PROBE = "probelauf", WEG_AUTO_AN = "auto_an", WEG_MIT_CLOUD = "auto_an_und_cloud";

    private static volatile boolean aktiv;
    private static volatile boolean bewohnt = true;
    private static volatile String aussenBewegung = AUSSEN_NICHTS;
    private static volatile Camping instance;

    /** Eingriff in den Wächter des Kamera-Daemons (wie beim Wächter-Stopp). */
    public interface Waechter {
        boolean isRunning();
        void stop();
        void start();
    }

    private static volatile Waechter waechter;
    private static volatile boolean waechterVonUnsGestoppt;

    private final String ordner;
    private HeizTabelle tabelle;
    private String tabellenFehler;
    private CampingRegler regler;
    private HeizLernen lernen = new HeizLernen();
    private final VerbrauchSchaetzer verbrauch = new VerbrauchSchaetzer();
    private CampingRegler.Entscheidung letzte;
    private final Deque<JSONObject> protokoll = new ArrayDeque<>();
    private int[] letzteFenster;
    private ScheduledExecutorService exec;
    // ---- 0.1.17 Plan (CampingPlan) ----
    /** Geplanter Start/Ende (0 = keiner); läuft Camping, gilt sitzungEndeMs. */
    long planStartMs, planEndeMs;
    /** Beginn und ausdrückliches Ende (aus dem Plan) der laufenden Sitzung; 0 = keins. */
    long sitzungSeitMs, sitzungEndeMs;
    String planFehler;
    long planFehlerMs;
    /** Uhr und Zeitzone (in Tests ersetzbar). */
    java.util.function.LongSupplier uhr = System::currentTimeMillis;
    java.util.TimeZone zone = java.util.TimeZone.getDefault();

    /** Stellglied (in Tests ersetzbar); null ohne Kennfeld. */
    Heizweg heizweg;
    /**
     * Wo geschaltet wird: Ein Befehl wartet bis ~30 s auf die Wirkung (Echo). Im Daemon deshalb ein eigener
     * Thread, damit Status/MQTT nicht hängen; in Tests direkt.
     */
    java.util.concurrent.Executor schalter = Runnable::run;
    /** Live: läuft die lokale AC? 1/0, sonst unbekannt (in Tests ersetzbar). */
    java.util.function.Supplier<Integer> klimaLive = () -> {
        try {
            return BydDataCollector.getInstance().readAcPowerNow();
        } catch (Throwable t) {
            return -1;
        }
    };
    /** Fahrzeugdaten (in Tests ersetzbar). */
    java.util.function.Supplier<BydVehicleData> daten = () -> {
        try {
            return BydDataCollector.getInstance().getData();
        } catch (Throwable t) {
            return null;
        }
    };

    Camping(String ordner) {
        this.ordner = ordner;
    }

    // ---------------------------------------------------------------- schnelle Abfragen

    /** Camping an? (volatile, ohne I/O) */
    public static boolean aktiv() { return aktiv; }

    /** Wächter soll nicht scharf werden: Camping an, „bewohnt“ und Außenbewegung „nichts“. */
    public static boolean blocksArming() {
        return aktiv && bewohnt && AUSSEN_NICHTS.equals(aussenBewegung);
    }

    /** Keine Abschreckung (Bildschirm, Licht, Hupe) und kein Erschütterungsalarm: Camping an und „bewohnt“. */
    public static boolean ohneAbschreckung() { return aktiv && bewohnt; }

    // ---------------------------------------------------------------- Start

    /** Im Kamera-Daemon starten (einmal). Lädt Kennfeld, Lernwerte und Zustand. */
    public static synchronized void start(Waechter w) {
        if (instance != null) return;
        waechter = w;
        Camping c = new Camping(at.greenchili.surfmate.util.DaemonStorage.rebase(ORDNER));
        c.laden();
        c.schalter = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "CampingSchalter");
            t.setDaemon(true);
            return t;
        });
        c.exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Camping");
            t.setDaemon(true);
            return t;
        });
        c.exec.scheduleWithFixedDelay(() -> {
            try {
                c.takt();
            } catch (Throwable t) {
                logger.warn("Takt fehlgeschlagen: " + t.getMessage());
            }
        }, 20_000L, TAKT_MS, TimeUnit.MILLISECONDS);
        instance = c;
        logger.info("gestartet (Trockenlauf, Camping " + (aktiv ? "an" : "aus") + ")");
    }

    synchronized void laden() {
        tabelleLaden();
        try {
            String l = lies(LERNEN_DATEI);
            lernen = HeizLernen.aus(l == null ? null : new JSONObject(l));
        } catch (Exception e) {
            lernen = new HeizLernen();
        }
        regler = tabelle == null ? null : new CampingRegler(tabelle, istProbe(heizwegEinstellung()));
        if (tabelle != null && heizweg == null) heizweg = new RouterHeizweg(tabelle.cloudTemperatur, this::cloudProtokoll, () -> daten.get());
        try {
            String z = lies(ZUSTAND_DATEI);
            if (z != null) {
                JSONObject zj = new JSONObject(z);
                JSONObject plan = zj.optJSONObject("plan");
                if (plan != null) {
                    planStartMs = plan.optLong("startMs", 0);
                    planEndeMs = plan.optLong("endeMs", 0);
                    planFehler = plan.optString("fehler", "").isEmpty() ? null : plan.optString("fehler");
                    planFehlerMs = plan.optLong("fehlerMs", 0);
                }
                sitzungSeitMs = zj.optLong("sitzungSeitMs", 0);
                sitzungEndeMs = zj.optLong("sitzungEndeMs", 0);
            }
            if (z != null && regler != null) regler.laden(new JSONObject(z).optJSONObject("regler"));
            if (z != null && regler == null) {
                // ohne Kennfeld bleibt nur „an/aus“ erhalten
                aktiv = new JSONObject(z).optBoolean("aktiv", false);
            }
        } catch (Exception e) {
            logger.warn("Zustand nicht lesbar: " + e.getMessage());
        }
        if (regler != null) aktiv = regler.aktiv();
        einstellungUebernehmen(einstellung());
    }

    /** Kennfeld lesen (lokale Kopie vor Asset). Ohne Kennfeld kein Heizen; erneuter Versuch im Takt. */
    private void tabelleLaden() {
        String t = lies(TABELLE_DATEI);
        if (t == null) t = asset();
        try {
            tabelle = HeizTabelle.aus(t == null ? "" : t);
            tabellenFehler = null;
        } catch (Exception e) {
            tabelle = null;
            tabellenFehler = "kennfeld_fehlt";
            logger.warn("Kennfeld nicht lesbar – Heizen gesperrt: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- Bedienung

    /** Camping einschalten (Tipp, Handy, HA, Telegram). Nur im Stand mit bekanntem Fahrzustand. */
    public static JSONObject ein(String quelle) {
        Camping c = instance;
        if (c == null) return fehler("nicht_gestartet");
        JSONObject r = c.einschalten(quelle);
        if (r != null && r.optBoolean("ok", false)) at.greenchili.surfmate.gewohnheiten.BefehlsHaken.camping(true, quelle);
        return r;
    }

    public static JSONObject aus(String grund) {
        Camping c = instance;
        if (c == null) return fehler("nicht_gestartet");
        String g = grund == null || grund.isEmpty() ? "tipp" : grund;
        JSONObject r = c.ausschalten(g);
        if (r != null && r.optBoolean("ok", false)) at.greenchili.surfmate.gewohnheiten.BefehlsHaken.camping(false, g);
        return r;
    }

    /** „zu kalt / passt / zu warm“. */
    public static JSONObject rueckmeldung(String art) {
        Camping c = instance;
        if (c == null) return fehler("nicht_gestartet");
        return c.rueckmelden(art);
    }

    /** Nach „Auto hat abgeschaltet“: weiter regeln (Tipp). */
    public static JSONObject weiter() {
        Camping c = instance;
        if (c == null) return fehler("nicht_gestartet");
        synchronized (c) {
            if (c.regler != null) c.regler.bestaetigen();
            c.speichern();
        }
        return ok();
    }

    public static JSONObject status() {
        Camping c = instance;
        if (c == null) {
            JSONObject o = new JSONObject();
            try { o.put("aktiv", false).put("gestartet", false); } catch (Exception ignored) { }
            return o;
        }
        return c.statusJson();
    }

    synchronized JSONObject einschalten(String quelle) {
        if (aktiv) return ok();
        CampingRegler.Lage l = lage();
        if (l.fahrt != CampingRegler.Fahrt.STEHT) {
            return fehler(l.fahrt == CampingRegler.Fahrt.FAEHRT ? "nicht_geparkt" : "fahrzustand_unbekannt");
        }
        CampingRegler.Einstellung e = einstellung();
        einstellungUebernehmen(e);
        verbrauch.leeren();
        sitzungSeitMs = uhr.getAsLong();
        sitzungEndeMs = 0;
        letzteFenster = null;
        if (heizweg != null) heizweg.zuruecksetzen();
        einmalGemeldet.clear();
        if (regler != null) regler.setTrockenlauf(istProbe(heizwegEinstellung()));
        if (regler == null) {
            aktiv = true;                 // ohne Kennfeld: Camping ohne Heizen
            melden("camping_an", false, null);
        } else {
            CampingRegler.Entscheidung d = regler.einschalten(e, l);
            aktiv = regler.aktiv();
            verarbeiten(d, l);
        }
        logger.info("Camping an (" + quelle + ")");
        waechterAnpassen();
        speichern();
        return ok();
    }

    synchronized JSONObject ausschalten(String grund) {
        if (!aktiv) return ok();
        if (regler != null) {
            // 0.1.17: mit echter Lage, sonst würde eine laufende lokale Heizphase nicht abgeschaltet
            CampingRegler.Lage l = lage();
            CampingRegler.Entscheidung d = regler.ausschalten(grund, l.jetztMs);
            verarbeiten(d, l);
        } else {
            melden("camping_aus_" + grund, false, null);
        }
        aktiv = false;
        sitzungEndeMs = 0;
        logger.info("Camping aus (" + grund + ")");
        waechterAnpassen();
        speichern();
        return ok();
    }

    // ---------------------------------------------------------------- 0.1.17 Plan

    /** POST /api/camping/plan: {"start","ende"} bzw. {"loeschen": true}. */
    public static JSONObject plan(JSONObject req) {
        Camping c = instance;
        if (c == null) return fehler("nicht_gestartet");
        return c.planSetzen(req);
    }

    synchronized JSONObject planSetzen(JSONObject req) {
        if (req == null) return fehler("json");
        if (req.optBoolean("loeschen", false)) {
            planStartMs = 0;
            planEndeMs = 0;
            planFehler = null;
            planFehlerMs = 0;
            speichern();
            logger.info("Plan gelöscht");
            return ok();
        }
        if (aktiv) return fehler("camping_laeuft");
        String ende = req.isNull("ende") ? null : req.optString("ende", null);
        CampingPlan.Ergebnis r = CampingPlan.pruefen(req.optString("start", null), ende, uhr.getAsLong(), zone);
        if (!r.ok()) return fehler(r.fehler);
        planStartMs = r.startMs;
        planEndeMs = r.endeMs;
        planFehler = null;
        planFehlerMs = 0;
        speichern();
        logger.info("Plan gesetzt: Start " + planStartMs + (planEndeMs > 0 ? ", Ende " + planEndeMs : ""));
        JSONObject o = ok();
        try { o.put("geplant", CampingPlan.alsJson(planStartMs, planEndeMs)); } catch (Exception ignored) { }
        return o;
    }

    /** Takt: fälligen Plan starten – mit allen Prüfungen von {@link #einschalten}; geht das nicht, Grund merken. */
    private void planAusfuehren() {
        if (aktiv || planStartMs <= 0) return;
        long jetzt = uhr.getAsLong();
        if (jetzt < planStartMs) return;
        long ende = planEndeMs;
        String fehler = null;
        if (jetzt - planStartMs > CampingPlan.VERPASST_MS) {
            fehler = "verpasst";
        } else {
            JSONObject r = einschalten("plan");
            if (!r.optBoolean("ok", false)) fehler = r.optString("fehler", "unbekannt");
        }
        planStartMs = 0;
        planEndeMs = 0;
        if (fehler != null) {
            planFehler = fehler;
            planFehlerMs = jetzt;
            logger.warn("Geplanter Start nicht möglich: " + fehler);
            melden("camping_plan_fehler", true, null);
        } else {
            sitzungEndeMs = ende;
            planFehler = null;
        }
        speichern();
    }

    /** Ende-Regel (CampingPlan.ende): Plan-Ende bzw. Weckzeit + Nachlauf, spätestens Höchstdauer. true = beendet. */
    private boolean endeRegel() {
        CampingPlan.Ende en = aktuellesEnde();
        if (en == null || uhr.getAsLong() < en.ms) return false;
        ausschalten(en.grund);
        return true;
    }

    private CampingPlan.Ende aktuellesEnde() {
        long seit = sitzungSeitMs > 0 ? sitzungSeitMs : (regler != null ? regler.aktivSeitMs() : 0);
        if (seit <= 0) return null;
        CampingRegler.Einstellung e = einstellung();
        return CampingPlan.ende(seit, sitzungEndeMs, e.weckMinuteDesTages, e.nachlaufMinuten,
                e.hoechstdauerStunden, zone);
    }

    synchronized JSONObject rueckmelden(String art) {
        if (!aktiv) return fehler("camping_aus");
        if (!HeizLernen.PASST.equals(art) && !HeizLernen.ZU_KALT.equals(art) && !HeizLernen.ZU_WARM.equals(art)) {
            return fehler("art");
        }
        double aussen = lage().aussenC;
        boolean geaendert = lernen.rueckmeldung(art, aussen, System.currentTimeMillis());
        schreibe(LERNEN_DATEI, lernen.alsJson().toString());
        JSONObject r = ok();
        try {
            r.put("uebernommen", geaendert);
            if (!geaendert) r.put("grund", "sperre_30_min");
        } catch (Exception ignored) { }
        return r;
    }

    // ---------------------------------------------------------------- Takt

    synchronized void takt() {
        einstellungUebernehmen(einstellung());
        if (tabelle == null && !aktiv) {
            // z. B. Daemon-Kontext beim Start noch nicht bereit: nur außerhalb von Camping neu versuchen
            tabelleLaden();
            if (tabelle != null) {
                regler = new CampingRegler(tabelle, istProbe(heizwegEinstellung()));
                if (heizweg == null) heizweg = new RouterHeizweg(tabelle.cloudTemperatur, this::cloudProtokoll, () -> daten.get());
            }
        }
        planAusfuehren();
        if (!aktiv) return;
        if (endeRegel()) return;
        CampingRegler.Lage l = lage();
        if (regler == null) {
            if (l.fahrt == CampingRegler.Fahrt.FAEHRT) ausschalten("losgefahren");
            return;
        }
        regler.setTrockenlauf(istProbe(heizwegEinstellung()));
        verbrauch.probe(l.jetztMs, l.soc, regler.heizStundenKumuliert(l.jetztMs));
        CampingRegler.Entscheidung d = regler.takt(einstellung(), l);
        verarbeiten(d, l);
        if (heizweg != null) {
            String m = heizweg.nachTakt(l.jetztMs);
            if (m != null) melden(m, true, l);
        }
        fensterPruefen();
        if (!regler.aktiv()) aktiv = false;
        waechterAnpassen();
        speichern();
    }

    private void verarbeiten(CampingRegler.Entscheidung d, CampingRegler.Lage l) {
        letzte = d;
        if (d.aktion != CampingRegler.Aktion.KEINE) {
            boolean probe = regler == null || regler.trockenlauf() || heizweg == null || l == null;
            String ergebnis = "probelauf";
            Runnable schalten = null;
            if (!probe) {
                // Stufe 2b: wirklich schalten – H1 lokal bei Auto an, H2 Cloud bei Auto aus (nur wenn erlaubt).
                // Asynchron (Wirkungsprüfung dauert); das Ergebnis wird danach unter der Sperre verbucht.
                ergebnis = "laeuft";
                final CampingRegler.Aktion aktion = d.aktion;
                final int stufe = d.stufe, geblaese = d.geblaese;
                final int dauer = d.phaseEndeMs > 0 ? (int) Math.max(1, (d.phaseEndeMs - l.jetztMs) / 60_000L) : 15;
                final boolean cloud = WEG_MIT_CLOUD.equals(heizwegEinstellung());
                final Heizweg hw = heizweg;
                schalten = () -> {
                    Heizweg.Ergebnis e;
                    try {
                        e = aktion == CampingRegler.Aktion.EIN ? hw.ein(stufe, geblaese, dauer, l, cloud) : hw.aus(l);
                    } catch (Throwable t) {
                        e = Heizweg.Ergebnis.fehler("ausnahme");
                        logger.warn("Heizweg: " + t);
                    }
                    ergebnisVerbuchen(aktion, e, l);
                };
            }
            JSONObject p = new JSONObject();
            try {
                p.put("zeit", System.currentTimeMillis()).put("aktion", d.aktion.name().toLowerCase())
                        .put("phase", d.phase == null ? JSONObject.NULL : d.phase.name().toLowerCase())
                        .put("stufe", d.stufe).put("geblaese", d.geblaese).put("trockenlauf", probe)
                        .put("ergebnis", ergebnis);
            } catch (Exception ignored) { }
            protokoll.addLast(p);
            while (protokoll.size() > 50) protokoll.removeFirst();
            if (schalten != null) schalter.execute(schalten);
            logger.info((probe ? "Probelauf: würde " : "Heizweg: ") + (d.aktion == CampingRegler.Aktion.EIN
                    ? "einschalten (" + at.greenchili.surfmate.byd.KlimaStufe.anzeige(d.stufe) + ", Gebläse " + d.geblaese + ", " + d.phase + ")"
                    : "ausschalten (" + d.phase + ")") + (probe ? "" : " → " + ergebnis));
        }
        for (String m : d.meldungen) {
            boolean warn = m.startsWith("akku_") || m.startsWith("v12_") || m.startsWith("hoechstdauer")
                    || m.startsWith("auto_hat");
            melden(m, warn, l);
        }
        if (d.campingBeenden) aktiv = false;
    }

    /** Ergebnis eines Schaltversuchs verbuchen (unter der Sperre, ggf. aus dem Schalter-Thread). */
    private synchronized void ergebnisVerbuchen(CampingRegler.Aktion aktion, Heizweg.Ergebnis e, CampingRegler.Lage l) {
        JSONObject letzter = protokoll.peekLast();
        try {
            if (letzter != null && "laeuft".equals(letzter.optString("ergebnis"))) letzter.put("ergebnis", e.toString());
        } catch (Exception ignored) { }
        logger.info("Heizweg " + aktion + " → " + e);
        if (regler == null || !aktiv) return;
        if (aktion == CampingRegler.Aktion.EIN) {
            if (e.art == Heizweg.Ergebnis.Art.FEHLER) {
                regler.fehlgeschlagen(l.jetztMs);
                melden("heizen_fehlgeschlagen", true, l);
                // zweimal keine Cloud-Bestätigung: Cloud-Heizen ruht bis zum nächsten Camping, einmal melden
                if (heizweg instanceof RouterHeizweg && ((RouterHeizweg) heizweg).cloudPausiert()) {
                    einmalMelden("cloud_pausiert", l);
                }
            } else if (e.art == Heizweg.Ergebnis.Art.UEBERSPRUNGEN) {
                regler.uebersprungen(l.jetztMs);
                if ("cloud_gesperrt_weg2".equals(e.grund) || "weck_grenze".equals(e.grund)) einmalMelden(e.grund, l);
            }
        } else if (e.art == Heizweg.Ergebnis.Art.FEHLER) {
            melden("heizen_aus_fehlgeschlagen", true, l);
        }
        speichern();
    }

    private final java.util.Set<String> einmalGemeldet = new java.util.HashSet<>();

    /** Protokoll jeder Cloud-Entscheidung (JSON je Zeile), für die Auswertung des ersten echten Einsatzes. */
    static final String CLOUD_PROTOKOLL = "camping_cloud.jsonl";
    static final long CLOUD_PROTOKOLL_MAX = 256 * 1024;

    /** Zeile anhängen; über der Grenze bleibt die jüngere Hälfte. Wirft nie (nur Protokoll). */
    void cloudProtokoll(JSONObject eintrag) {
        try {
            File f = new File(ordner, CLOUD_PROTOKOLL);
            if (f.length() > CLOUD_PROTOKOLL_MAX) {
                byte[] alt = java.nio.file.Files.readAllBytes(f.toPath());
                int ab = alt.length / 2;
                while (ab < alt.length && alt[ab] != '\n') ab++;
                java.nio.file.Files.write(f.toPath(), java.util.Arrays.copyOfRange(alt, Math.min(alt.length, ab + 1), alt.length));
            }
            java.nio.file.Files.write(f.toPath(), (eintrag.toString() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Throwable t) {
            logger.warn("Cloud-Protokoll nicht geschrieben: " + t.getMessage());
        }
    }

    private void einmalMelden(String code, CampingRegler.Lage l) {
        if (einmalGemeldet.add(code)) melden(code, true, l);
    }

    static boolean istProbe(String weg) {
        return !WEG_AUTO_AN.equals(weg) && !WEG_MIT_CLOUD.equals(weg);
    }

    /** Quelle der Einstellung camping.heizweg (in Tests ersetzbar). */
    static java.util.function.Supplier<String> heizwegQuelle = Camping::heizwegAusKonfig;

    /** Einstellung camping.heizweg; Standard „probelauf“ – echtes Heizen nur auf ausdrückliche Wahl. */
    static String heizwegEinstellung() {
        String w = heizwegQuelle.get();
        return WEG_PROBE.equals(w) || WEG_MIT_CLOUD.equals(w) || WEG_AUTO_AN.equals(w) ? w : WEG_PROBE;
    }

    private static String heizwegAusKonfig() {
        try {
            JSONObject root = UnifiedConfigManager.loadConfig();
            JSONObject c = root == null ? null : root.optJSONObject("camping");
            String w = c == null ? WEG_PROBE : c.optString("heizweg", WEG_PROBE);
            return WEG_AUTO_AN.equals(w) || WEG_MIT_CLOUD.equals(w) ? w : WEG_PROBE;
        } catch (Throwable t) {
            return WEG_PROBE;   // Konfiguration unlesbar: sicher, nichts schalten
        }
    }

    /** „bewohnt“ mit Außenbewegung „nichts“: Wächter aus, solange Camping läuft; danach wie vorher. */
    private static void waechterAnpassen() {
        Waechter w = waechter;
        if (w == null) return;
        try {
            if (blocksArming()) {
                if (w.isRunning()) {
                    w.stop();
                    waechterVonUnsGestoppt = true;
                    logger.info("Wächter pausiert (Camping, bewohnt)");
                }
            } else if (waechterVonUnsGestoppt) {
                waechterVonUnsGestoppt = false;
                w.start();
                logger.info("Wächter wieder wie vorher (Camping aus bzw. Außenbewegung Notiz)");
            }
        } catch (Throwable t) {
            logger.warn("Wächter anpassen: " + t.getMessage());
        }
    }

    private void fensterPruefen() {
        try {
            BydVehicleData v = daten.get();
            int[] f = v == null ? null : v.windowOpenPercent;
            if (f == null) return;
            if (letzteFenster != null && letzteFenster.length == f.length) {
                for (int i = 0; i < f.length; i++) {
                    if (letzteFenster[i] <= 0 && f[i] > 0) {
                        melden("fenster_offen", false, null);
                        break;
                    }
                }
            }
            letzteFenster = f.clone();
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- Lage und Einstellung

    CampingRegler.Lage lage() {
        CampingRegler.Lage l = new CampingRegler.Lage();
        l.jetztMs = System.currentTimeMillis();
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(l.jetztMs);
        l.minuteDesTages = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        BydVehicleData v = daten.get();
        l.fahrt = fahrt(v);
        if (v != null && v.powerLevel != BydVehicleData.UNAVAILABLE && v.powerLevelAtMs > 0) {
            l.powerLevel = v.powerLevel;
            l.powerLevelAlterMs = l.jetztMs - v.powerLevelAtMs;
        }
        // Rückmeldung „läuft die AC“ nur, wenn das Auto an ist (H1); bei Auto aus ist eine Cloud-Sitzung unsichtbar
        if (l.powerLevel == 2) {
            Integer k = klimaLive.get();
            if (k != null && (k == 0 || k == 1)) l.klimaLaeuft = k == 1;
        }
        if (v != null) {
            l.soc = v.socPercent > 0 && v.socPercent <= 100 ? v.socPercent : Double.NaN;
            l.aussenC = v.outsideTempC;
            double ruhe = Double.NaN;
            try {
                ruhe = at.greenchili.surfmate.surveillance.GuardStopMonitor.ruheMedian12v();
            } catch (Throwable ignored) {
            }
            l.v12Median = Double.isNaN(ruhe) ? v.voltage12v : ruhe;
        }
        l.gelerntK = lernen.verschiebungK(l.aussenC);
        // Raten: gemessen, sonst Kennfeld × Kapazität (aus Fahrzeugwerten, nie fest)
        VerbrauchSchaetzer.Ergebnis e = verbrauch.schaetzen();
        double kap = kapazitaetKwh(v);
        if (!Double.isNaN(e.grundProzentProStunde)) {
            l.grundProzentProStunde = e.grundProzentProStunde;
        } else if (!Double.isNaN(kap) && tabelle != null && v != null && v.powerLevel >= 2) {
            l.grundProzentProStunde = tabelle.grundlastKwAutoAn / kap * 100;
        } else if (e.gemessen()) {
            l.grundProzentProStunde = e.gesamtProzentProStunde;
        }
        if (!Double.isNaN(e.heizZusatzProzentProStunde)) {
            l.heizProzentProStunde = e.heizZusatzProzentProStunde;
        } else if (!Double.isNaN(kap) && tabelle != null) {
            HeizTabelle.Zeile z = tabelle.fuer(l.aussenC, einstellung().waerme, l.gelerntK);
            l.heizProzentProStunde = tabelle.leistungKw(z.stufe) / kap * 100;
        }
        return l;
    }

    /** Stand / Fahrt / unbekannt aus Gang und Tempo (fail-closed). */
    static CampingRegler.Fahrt fahrt(BydVehicleData v) {
        if (v == null) return CampingRegler.Fahrt.UNBEKANNT;
        return fahrt(v, at.greenchili.surfmate.byd.Stillstand.tempoJetzt(v));
    }

    /** Wie oben, mit dem Tempo nach byd.Stillstand (0.1.28.1: alter Restwert bei ACC aus + P zählt nicht). */
    static CampingRegler.Fahrt fahrt(BydVehicleData v, double kmh) {
        if (v == null) return CampingRegler.Fahrt.UNBEKANNT;
        boolean tempoBekannt = !Double.isNaN(kmh) && kmh >= 0;
        if (tempoBekannt && kmh > 2.0) return CampingRegler.Fahrt.FAEHRT;
        if (v.gearMode == BydVehicleData.UNAVAILABLE || v.gearMode <= 0) return CampingRegler.Fahrt.UNBEKANNT;
        if (v.gearMode != GearMonitor.GEAR_P) return CampingRegler.Fahrt.FAEHRT;
        return tempoBekannt ? CampingRegler.Fahrt.STEHT : CampingRegler.Fahrt.UNBEKANNT;
    }

    /** Nutzbare Kapazität aus Restenergie und SoC (10–200 kWh plausibel), sonst NaN. */
    static double kapazitaetKwh(BydVehicleData v) {
        if (v == null || Double.isNaN(v.remainKwh) || Double.isNaN(v.socPercent) || v.socPercent < 5) return Double.NaN;
        double k = v.remainKwh / (v.socPercent / 100.0);
        return k >= 10 && k <= 200 ? k : Double.NaN;
    }

    static CampingRegler.Einstellung einstellung() {
        CampingRegler.Einstellung e = new CampingRegler.Einstellung();
        JSONObject c = null;
        JSONObject root = null;
        try {
            root = UnifiedConfigManager.loadConfig();
            c = root == null ? null : root.optJSONObject("camping");
        } catch (Throwable ignored) {
        }
        if (c == null) c = new JSONObject();
        e.waerme = c.optString("waerme", HeizTabelle.WARM);
        e.vorheizenMin = clamp(c.optInt("vorheizenMinuten", 30), 0, 60);
        e.morgens = c.optBoolean("morgensHeizen", false);
        e.weckMinuteDesTages = minuten(c.optString("weckzeit", ""));
        e.socGrenze = clamp(c.optInt("socGrenze", 20), 5, 80);
        double v12 = c.optDouble("v12Warnung", Double.NaN);
        if (Double.isNaN(v12)) {
            try {
                v12 = at.greenchili.surfmate.einstellungen.AkkuChemie.v12Standard(
                        at.greenchili.surfmate.einstellungen.AkkuChemie.aktuell(root))[0];
            } catch (Throwable ignored) {
            }
        }
        e.v12Grenze = v12;
        e.hoechstdauerStunden = clamp(c.optInt("hoechstdauerStunden", 12), 1, 24);
        e.nachlaufMinuten = clamp(c.optInt("nachlaufMinuten", 60), 0, 240);
        return e;
    }

    private static void einstellungUebernehmen(CampingRegler.Einstellung ignoriert) {
        try {
            JSONObject root = UnifiedConfigManager.loadConfig();
            JSONObject c = root == null ? null : root.optJSONObject("camping");
            bewohnt = c == null || c.optBoolean("waechterBewohnt", true);
            String a = c == null ? AUSSEN_NICHTS : c.optString("aussenBewegung", AUSSEN_NICHTS);
            aussenBewegung = AUSSEN_NOTIZ.equals(a) ? AUSSEN_NOTIZ : AUSSEN_NICHTS;
        } catch (Throwable ignored) {
        }
    }

    static int minuten(String hhmm) {
        if (hhmm == null || !hhmm.matches("\\d{1,2}:\\d{2}")) return -1;
        String[] t = hhmm.split(":");
        int h = Integer.parseInt(t[0]), m = Integer.parseInt(t[1]);
        return h > 23 || m > 59 ? -1 : h * 60 + m;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    // ---------------------------------------------------------------- Status

    synchronized JSONObject statusJson() {
        JSONObject o = new JSONObject();
        try {
            String weg = heizwegEinstellung();
            o.put("aktiv", aktiv).put("gestartet", true).put("trockenlauf", regler == null || istProbe(weg));
            o.put("heizweg", weg);
            if (heizweg != null) o.put("heizwegStand", heizweg.stand());
            if (tabellenFehler != null) o.put("fehler", tabellenFehler);
            o.put("waechterBewohnt", bewohnt).put("aussenBewegung", aussenBewegung);
            CampingRegler.Lage l = lage();
            o.put("fahrt", l.fahrt.name().toLowerCase());
            o.put("soc", Double.isNaN(l.soc) ? JSONObject.NULL : l.soc);
            o.put("v12", Double.isNaN(l.v12Median) ? JSONObject.NULL : Math.round(l.v12Median * 10) / 10.0);
            // Nur Außentemperatur – eine Innentemperatur gibt es ohne echten Fühler nicht (KabinenTemperatur).
            o.put("aussenC", Double.isNaN(l.aussenC) ? JSONObject.NULL : l.aussenC);
            o.put("innenC", JSONObject.NULL);
            CampingRegler.Einstellung e = einstellung();
            o.put("waerme", e.waerme).put("socGrenze", e.socGrenze);
            // 0.1.17 Plan (Cockpit): geplant {start, ende} in ms bzw. null, planFehler {grund, zeit} bzw. null
            o.put("geplant", planStartMs > 0 ? CampingPlan.alsJson(planStartMs, planEndeMs) : JSONObject.NULL);
            o.put("planFehler", planFehler != null
                    ? new JSONObject().put("grund", planFehler).put("zeit", planFehlerMs) : JSONObject.NULL);
            if (aktiv) {
                CampingPlan.Ende en = aktuellesEnde();
                if (en != null) o.put("endeUm", en.ms).put("endeGrund", en.grund);
            }
            if (regler != null) {
                o.put("seit", aktiv ? regler.aktivSeitMs() : 0);
                o.put("phase", regler.phase().name().toLowerCase());
            }
            if (letzte != null && aktiv) {
                if (letzte.zeile != null) o.put("tabelle", letzte.zeile.alsJson());
                if (letzte.naechsterZyklusMs > 0) o.put("naechsterZyklusMs", letzte.naechsterZyklusMs);
                if (letzte.phaseEndeMs > 0) o.put("phaseEndeMs", letzte.phaseEndeMs);
                if (letzte.sperrGrund != null) o.put("sperrGrund", letzte.sperrGrund);
                o.put("sitzheizung", letzte.sitzheizung);
                EnergieBudget.Plan p = letzte.plan;
                if (p != null && p.bekannt) {
                    JSONObject b = new JSONObject();
                    b.put("intervallMin", p.intervallMin).put("dauerMin", p.dauerMin).put("gestreckt", p.gestreckt);
                    if (!Double.isInfinite(p.reichtMinuten)) {
                        b.put("reichtBisMs", l.jetztMs + Math.round(p.reichtMinuten * 60_000));
                    }
                    if (p.reichtBisWeck != null) b.put("reichtBisWeck", p.reichtBisWeck);
                    o.put("budget", b);
                }
            }
            VerbrauchSchaetzer.Ergebnis v = verbrauch.schaetzen();
            JSONObject vb = new JSONObject();
            vb.put("gemessen", v.gemessen());
            if (v.gemessen()) vb.put("prozentProStunde", runde(v.gesamtProzentProStunde));
            if (!Double.isNaN(v.grundProzentProStunde)) vb.put("grundProzentProStunde", runde(v.grundProzentProStunde));
            if (!Double.isNaN(v.heizZusatzProzentProStunde)) vb.put("heizProzentProStunde", runde(v.heizZusatzProzentProStunde));
            o.put("verbrauch", vb);
            o.put("lernen", lernen.alsJson());
            o.put("protokoll", new JSONArray(protokoll));
        } catch (Exception e) {
            logger.debug("Status: " + e.getMessage());
        }
        return o;
    }

    private static double runde(double d) { return Math.round(d * 100) / 100.0; }

    // ---------------------------------------------------------------- Meldungen

    private void melden(String code, boolean warn, CampingRegler.Lage l) {
        try {
            String titel = Messages.get("notifications." + (code.startsWith("camping_") ? code : "camping_" + code));
            JSONObject data = new JSONObject();
            data.put("code", code);
            NotificationBus.get().publish(new NotificationEvent(
                    warn ? KATEGORIE_WARNUNG : KATEGORIE_HINWEIS,
                    warn ? NotificationEvent.Severity.WARN : NotificationEvent.Severity.INFO,
                    titel, "", "camping-" + code, "/local/camping.html", data));
        } catch (Throwable t) {
            // Eine Meldung darf Camping nie blockieren.
            logger.debug("Meldung " + code + ": " + t.getMessage());
        }
    }

    /** Türmeldung im Camping (auch bei eingeschaltetem Auto); von {@code DoorEventNotifier}. */
    public static void tuerGeoeffnet(String bereich) {
        Camping c = instance;
        if (c == null || !aktiv || !bewohnt) return;
        try {
            NotificationBus.get().publish(new NotificationEvent(KATEGORIE_HINWEIS, NotificationEvent.Severity.INFO,
                    Messages.get("notifications.camping_tuer_offen", bereich), "", "camping-tuer",
                    "/local/camping.html", null));
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- Dateien

    private synchronized void speichern() {
        JSONObject z = new JSONObject();
        try {
            z.put("aktiv", aktiv);
            if (regler != null) z.put("regler", regler.zustand());
            z.put("plan", new JSONObject().put("startMs", planStartMs).put("endeMs", planEndeMs)
                    .put("fehler", planFehler == null ? "" : planFehler).put("fehlerMs", planFehlerMs));
            z.put("sitzungSeitMs", sitzungSeitMs).put("sitzungEndeMs", sitzungEndeMs);
        } catch (Exception ignored) { }
        schreibe(ZUSTAND_DATEI, z.toString());
    }

    private String lies(String name) {
        try {
            File f = new File(ordner, name);
            if (!f.isFile()) return null;
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    private void schreibe(String name, String inhalt) {
        try {
            File f = new File(ordner, name);
            File tmp = new File(ordner, name + ".tmp");
            Files.write(tmp.toPath(), inhalt.getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(f)) {
                Files.write(f.toPath(), inhalt.getBytes(StandardCharsets.UTF_8));
                tmp.delete();
            }
        } catch (Throwable t) {
            logger.debug(name + " nicht gespeichert: " + t.getMessage());
        }
    }

    private static volatile boolean assetFehlerGemeldet;

    private static String asset() {
        try {
            // Im Kamera-Daemon (uid 2000) erreicht nur der APK-AssetManager unsere Assets; er steht nach dem
            // Wächter-Init bereit – bis dahin versucht der Takt es erneut.
            android.content.res.AssetManager am = at.greenchili.surfmate.daemon.CameraDaemon.getApkAssets();
            if (am == null) am = at.greenchili.surfmate.daemon.DaemonBootstrap.apkAssets();
            if (am == null) return null;
            try (InputStream in = am.open(ASSET_TABELLE)) {
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                return new String(b.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            if (!assetFehlerGemeldet) {
                assetFehlerGemeldet = true;
                logger.warn("Kennfeld-Asset nicht lesbar: " + t);
            }
            return null;
        }
    }

    private static JSONObject ok() {
        JSONObject o = new JSONObject();
        try { o.put("ok", true); } catch (Exception ignored) { }
        return o;
    }

    private static JSONObject fehler(String grund) {
        JSONObject o = new JSONObject();
        try { o.put("ok", false).put("fehler", grund); } catch (Exception ignored) { }
        return o;
    }

    // ---------------------------------------------------------------- Tests

    /** Nur für Tests: Instanz mit eigenem Ordner, ohne Takt-Thread. */
    static Camping fuerTest(String ordner, java.util.function.Supplier<BydVehicleData> daten, Heizweg heizweg,
                            java.util.function.Supplier<Integer> klimaLive) {
        Camping c = new Camping(ordner);
        c.daten = daten;
        c.heizweg = heizweg;
        c.klimaLive = klimaLive;
        c.laden();
        instance = c;
        return c;
    }

    static void testZuruecksetzen() {
        instance = null;
        heizwegQuelle = () -> WEG_PROBE;
        aktiv = false;
        bewohnt = true;
        aussenBewegung = AUSSEN_NICHTS;
    }
}
