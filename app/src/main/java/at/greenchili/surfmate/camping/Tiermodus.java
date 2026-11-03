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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tiermodus zur Laufzeit (03.11.2026, Vorbild Teslas Dog Mode). Läuft im Kamera-Daemon neben Camping.
 *
 * <ul>
 *   <li><b>Start</b> nur am Autobildschirm ({@code /api/tiermodus}, direkt am Auto) oder aus der Handy-App mit Recht
 *       {@code klima} – nie automatisch, nie aus einer Automation, nie aus Telegram. Nur in P, bei 0 km/h, Auto an,
 *       nicht neben Camping ({@link TiermodusRegel#startFehler}).</li>
 *   <li><b>Klima</b> über den vorhandenen Camping-Heizweg ({@link RouterHeizweg}: lokal bei Auto an, Cloud nur bei Auto
 *       aus hinter dem CloudPowerGate). Kein eigener Regler: die Klimaanlage regelt selbst auf den Sollwert.</li>
 *   <li><b>Überhitzungsschutz</b>: Klima aus, Auto aus, Zustand unklar, Innentemperatur über der Grenze (nur mit
 *       gemessenem Fühler), Akku an der Untergrenze, 12 V LOW → sofort Meldung an Handy (Push) und Telegram, wiederholt
 *       bis jemand „Gesehen“ tippt. Akku und 12 V schalten das Klima aus – nie still: „Klima AUS – Tier prüfen!“.</li>
 *   <li>Nie Hupe, Blinker, Licht oder Alarm (REGEL A+). Wächter und Kamera laufen weiter; Erschütterung und
 *       Abschreckung ruhen, solange der Tiermodus läuft ({@link #aktiv()} in ShockMonitor, ScreenDeterrent,
 *       BydCloudDeterrent).</li>
 *   <li>Ende per Tipp, Handy oder Losfahren. Ein Ende schaltet das Klima nicht aus (jemand ist beim Tier).</li>
 * </ul>
 */
public final class Tiermodus {

    private static final DaemonLogger logger = DaemonLogger.getInstance("Tiermodus");
    static final long TAKT_MS = 30_000L;
    /** Klima neu einschalten frühestens so lange nach dem letzten Versuch. */
    static final long NEU_EIN_ABSTAND_MS = 2 * 60_000L;
    static final int GEBLAESE = 3;
    static final String ZUSTAND_DATEI = "tiermodus_zustand.json";
    /**
     * Haftungshinweis (03.11.2026): vor dem ersten Start bestätigen, nach jeder Textänderung erneut. Bei einer
     * Textänderung in den Katalogen ({@code notifications.tiermodus_hinweis_text}, Web {@code tiermodus.hinweis_text})
     * diese Version hochzählen.
     */
    public static final String HINWEIS_VERSION = "2026-11-03";
    /** Nur Textversion und Zeit, keine Personendaten. */
    static final String HINWEIS_DATEI = "tiermodus_hinweis.json";
    public static final String KATEGORIE_ALARM = "tiermodus.alarm";
    public static final String KATEGORIE_HINWEIS = "tiermodus.hinweis";

    private static volatile boolean aktiv;
    private static volatile Tiermodus instance;

    private final String ordner;
    /** Stellglied: derselbe Weg wie beim Camping-Heizen (in Tests ersetzbar). */
    Heizweg heizweg;
    java.util.function.LongSupplier uhr = System::currentTimeMillis;
    java.util.function.Supplier<BydVehicleData> daten = () -> {
        try {
            return BydDataCollector.getInstance().getData();
        } catch (Throwable t) {
            return null;
        }
    };
    java.util.function.Supplier<Integer> klimaLive = () -> {
        try {
            return BydDataCollector.getInstance().readAcPowerNow();
        } catch (Throwable t) {
            return -1;
        }
    };
    java.util.function.Supplier<Double> kmh = () -> Double.NaN;
    java.util.function.BooleanSupplier gangP = () -> false;
    java.util.function.BooleanSupplier v12Low = () -> {
        try {
            return at.greenchili.surfmate.surveillance.GuardStopMonitor.v12Low();
        } catch (Throwable t) {
            return false;
        }
    };
    java.util.function.BooleanSupplier campingAktiv = Camping::aktiv;
    /**
     * Misst dieses Modell die Innentemperatur nachweislich ({@code KabinenTemperatur.MESSUNG_BELEGT})? Ohne Beleg gibt
     * es keinen Innenwert und keine Warnung „veraltet“ – sonst wäre sie beim Surf ein Dauer-Alarm.
     */
    java.util.function.BooleanSupplier innenMessung = Tiermodus::innenMessungBelegt;
    java.util.function.Supplier<TiermodusRegel.Einstellung> einstellung = Tiermodus::einstellungAusKonfig;
    /** Meldung abschicken (in Tests ersetzbar). */
    java.util.function.Consumer<NotificationEvent> melder = e -> NotificationBus.get().publish(e);

    // ---- Zustand (überlebt Neustarts über ZUSTAND_DATEI)
    volatile long seitMs;
    volatile String quelle;
    /** Probleme im letzten Takt. */
    volatile Set<String> probleme = new LinkedHashSet<>();
    /** Mit „Gesehen“ bestätigte Probleme: keine Wiederholung, bis ein neues dazukommt. */
    final Set<String> quittiert = new java.util.concurrent.CopyOnWriteArraySet<>();
    volatile long letzterAlarmMs;
    volatile long letzterEinVersuchMs;
    volatile int unbekanntTakte;
    volatile boolean schutzAusGesendet;
    volatile boolean anzeigeOk;
    volatile String letztesKlima;
    volatile double innenC = Double.NaN;
    final Set<String> einmalGemeldet = new LinkedHashSet<>();
    private ScheduledExecutorService exec;

    Tiermodus(String ordner) {
        this.ordner = ordner;
    }

    // ---------------------------------------------------------------- schnelle Abfrage

    /** Tiermodus an? (volatile, ohne I/O – auch aus dem Wächter-Thread) */
    public static boolean aktiv() { return aktiv; }

    // ---------------------------------------------------------------- Start im Daemon

    public static synchronized void start() {
        if (instance != null) return;
        Tiermodus t = new Tiermodus(at.greenchili.surfmate.util.DaemonStorage.rebase(Camping.ORDNER));
        t.heizweg = new RouterHeizweg(t.einstellung.get().wunsch, null, () -> t.daten.get());
        t.kmh = () -> {
            try {
                return at.greenchili.surfmate.byd.Stillstand.tempoJetzt(t.daten.get());
            } catch (Throwable x) {
                return Double.NaN;
            }
        };
        t.gangP = () -> {
            try {
                GearMonitor gm = GearMonitor.getInstance();
                if (gm.isActive()) return gm.getCurrentGear() == GearMonitor.GEAR_P;
            } catch (Throwable ignored) { }
            BydVehicleData v = t.daten.get();
            return v != null && v.gearMode == GearMonitor.GEAR_P;
        };
        t.laden();
        t.exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread th = new Thread(r, "Tiermodus");
            th.setDaemon(true);
            return th;
        });
        t.exec.scheduleWithFixedDelay(() -> {
            try {
                t.takt();
            } catch (Throwable x) {
                logger.warn("Takt fehlgeschlagen: " + x.getMessage());
            }
        }, 15_000L, TAKT_MS, TimeUnit.MILLISECONDS);
        instance = t;
        logger.info("gestartet (Tiermodus " + (aktiv ? "an – läuft nach Neustart weiter" : "aus") + ")");
    }

    // ---------------------------------------------------------------- Bedienung

    /** Einschalten. {@code quelle} = „display“ oder „handy:…“; andere Wege gibt es nicht. */
    public static JSONObject ein(String quelle) {
        Tiermodus t = instance;
        if (t == null) return fehler("nicht_gestartet");
        return t.einschalten(quelle);
    }

    public static JSONObject aus(String quelle) {
        Tiermodus t = instance;
        if (t == null) return fehler("nicht_gestartet");
        return t.ausschalten(quelle == null || quelle.isEmpty() ? "tipp" : quelle);
    }

    /** „Gesehen“: laufende Wiederholungen stoppen, bis ein neues Problem kommt. */
    /** Haftungshinweis bestätigen. Gilt nur für die aktuelle {@link #HINWEIS_VERSION}. */
    public static JSONObject hinweisBestaetigen(String version) {
        Tiermodus t = instance;
        if (t == null) return fehler("nicht_gestartet");
        return t.hinweisBestaetigenIntern(version);
    }

    synchronized JSONObject hinweisBestaetigenIntern(String version) {
        if (!HINWEIS_VERSION.equals(version)) return fehler("hinweis_version");
        try {
            JSONObject h = new JSONObject().put("version", HINWEIS_VERSION).put("zeit", uhr.getAsLong());
            Files.write(new File(ordner, HINWEIS_DATEI).toPath(), h.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable x) {
            logger.warn("Hinweis nicht gespeichert: " + x.getMessage());
            return fehler("speichern");
        }
        logger.info("Haftungshinweis bestätigt (Version " + HINWEIS_VERSION + ")");
        return ok();
    }

    /** Ist der Hinweis in der aktuellen Version bestätigt? */
    boolean hinweisBestaetigt() {
        try {
            File f = new File(ordner, HINWEIS_DATEI);
            if (!f.isFile()) return false;
            JSONObject h = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            return HINWEIS_VERSION.equals(h.optString("version")) && h.optLong("zeit", 0) > 0;
        } catch (Throwable x) {
            return false;
        }
    }

    public static JSONObject quittieren(String quelle) {
        Tiermodus t = instance;
        if (t == null) return fehler("nicht_gestartet");
        return t.quittierenIntern(quelle);
    }

    public static JSONObject status() {
        Tiermodus t = instance;
        if (t == null) {
            JSONObject o = new JSONObject();
            try { o.put("aktiv", false).put("gestartet", false); } catch (Exception ignored) { }
            return o;
        }
        return t.statusJson();
    }

    synchronized JSONObject einschalten(String q) {
        if (aktiv) return ok();
        // Ohne bestätigten Haftungshinweis kein Start – auch nicht vom Handy
        if (!hinweisBestaetigt()) {
            logger.info("Start abgelehnt (" + q + "): hinweis_fehlt");
            return fehler("hinweis_fehlt");
        }
        TiermodusRegel.Einstellung e = einstellung.get();
        TiermodusRegel.Lage l = lage();
        String f = TiermodusRegel.startFehler(e, l, campingAktiv.getAsBoolean());
        if (f != null) {
            logger.info("Start abgelehnt (" + q + "): " + f);
            return fehler(f);
        }
        if (heizweg != null) heizweg.zuruecksetzen();
        Heizweg.Ergebnis r = heizweg == null ? Heizweg.Ergebnis.fehler("kein_heizweg")
                : heizweg.ein(e.wunsch, GEBLAESE, 30, l.alsCampingLage(), false);
        letzterEinVersuchMs = uhr.getAsLong();
        letztesKlima = r.toString();
        if (r.art != Heizweg.Ergebnis.Art.LOKAL) {
            logger.warn("Start: Klima ließ sich nicht einschalten (" + r + ")");
            return fehler("klima_fehlgeschlagen");
        }
        aktiv = true;
        seitMs = uhr.getAsLong();
        quelle = q;
        probleme = new LinkedHashSet<>();
        quittiert.clear();
        einmalGemeldet.clear();
        letzterAlarmMs = 0;
        unbekanntTakte = 0;
        schutzAusGesendet = false;
        anzeigeOk = false;
        speichern();
        logger.info("Tiermodus an (" + q + "), Sollwert " + e.wunsch);
        melden(KATEGORIE_HINWEIS, NotificationEvent.Severity.WARN, "an",
                Messages.text("notifications.tiermodus_an", "Pet mode on – climate is running"), sollText(e), null);
        if (!innenMessung.getAsBoolean()) {
            // Ruhiger Hinweis, einmal beim Start: Schutz hängt am Klima-Zustand, Akku und 12 V
            einmalMelden("ohne_fuehler", Messages.text("notifications.tiermodus_ohne_fuehler",
                    "This car does not report a cabin temperature. Panoptes monitors whether the climate is running."));
        }
        // Erste Prüfung gleich, nicht erst im nächsten Takt
        pruefen(l, e);
        return ok();
    }

    synchronized JSONObject ausschalten(String grund) {
        if (!aktiv) return ok();
        aktiv = false;
        probleme = new LinkedHashSet<>();
        quittiert.clear();
        anzeigeOk = false;
        speichern();
        logger.info("Tiermodus aus (" + grund + ") – Klima bleibt, wie es ist");
        String key = "losgefahren".equals(grund) ? "notifications.tiermodus_aus_losgefahren" : "notifications.tiermodus_aus";
        melden(KATEGORIE_HINWEIS, NotificationEvent.Severity.WARN, "aus",
                Messages.text(key, "Pet mode off"), null, null);
        return ok();
    }

    synchronized JSONObject quittierenIntern(String q) {
        if (!aktiv) return fehler("tiermodus_aus");
        quittiert.addAll(probleme);
        speichern();
        logger.info("Gesehen (" + q + "): " + probleme);
        JSONObject o = ok();
        try { o.put("quittiert", new JSONArray(quittiert)); } catch (Exception ignored) { }
        return o;
    }

    // ---------------------------------------------------------------- Takt

    synchronized void takt() {
        if (!aktiv) return;
        TiermodusRegel.Lage l = lage();
        if (l.fahrt == CampingRegler.Fahrt.FAEHRT) {
            ausschalten("losgefahren");
            return;
        }
        pruefen(l, einstellung.get());
        speichern();
    }

    /** Eine Prüfung: Klima halten bzw. Schutz, dann Meldungen. */
    private void pruefen(TiermodusRegel.Lage l, TiermodusRegel.Einstellung e) {
        long jetzt = l.jetztMs;
        innenC = l.innenC;
        boolean an = TiermodusRegel.autoAn(l);
        unbekanntTakte = an && l.klimaLaeuft == null ? unbekanntTakte + 1 : 0;
        Set<String> p = TiermodusRegel.probleme(e, l, unbekanntTakte, false);
        boolean schutz = TiermodusRegel.schutz(p);
        if (schutz) {
            // Schutzgrenzen bleiben, wie sie sind: Klima aus – aber laut (Meldung unten), nie still
            if (!schutzAusGesendet && heizweg != null) {
                Heizweg.Ergebnis r = heizweg.aus(l.alsCampingLage());
                letztesKlima = "aus " + r;
                logger.warn("Schutz " + p + ": Klima aus → " + r);
            }
            schutzAusGesendet = true;
        } else {
            schutzAusGesendet = false;
            boolean neuVersuchen = jetzt - letzterEinVersuchMs >= NEU_EIN_ABSTAND_MS;
            if (heizweg != null && neuVersuchen
                    && ((an && Boolean.FALSE.equals(l.klimaLaeuft)) || (TiermodusRegel.autoAus(l) && e.cloud))) {
                // Auto an + Klima aus: lokal neu einschalten. Auto aus: Cloud nur auf Wahl, RouterHeizweg prüft
                // CloudPowerGate, Höchstzahl, Abstand, Weg 2 – das Problem bleibt trotzdem gemeldet.
                letzterEinVersuchMs = jetzt;
                Heizweg.Ergebnis r = heizweg.ein(e.wunsch, GEBLAESE, 30, l.alsCampingLage(), e.cloud);
                letztesKlima = r.toString();
                logger.info("Klima neu einschalten → " + r);
            }
        }
        anzeigeOk = TiermodusRegel.anzeigeOk(p, l);
        Set<String> vorher = probleme;
        probleme = p;
        // Probleme, die vorbei sind, verlieren ihr „Gesehen“ – kommen sie wieder, wird neu gemeldet
        quittiert.retainAll(p);
        Set<String> neu = new LinkedHashSet<>(p);
        neu.removeAll(vorher);
        Set<String> offen = new LinkedHashSet<>(p);
        offen.removeAll(quittiert);
        boolean faellig = !offen.isEmpty() && (!neu.isEmpty()
                || jetzt - letzterAlarmMs >= e.wiederholenMinuten * 60_000L);
        if (faellig) {
            letzterAlarmMs = jetzt;
            alarm(p, l, e);
        } else if (p.isEmpty() && !vorher.isEmpty()) {
            melden(KATEGORIE_HINWEIS, NotificationEvent.Severity.WARN, "wieder_ok",
                    Messages.text("notifications.tiermodus_wieder_ok", "Pet mode: climate is running again"), null, null);
        }
        if (!Double.isNaN(l.soc) && l.soc > e.socGrenze && l.soc <= e.socGrenze + 5) {
            einmalMelden("akku_bald", Messages.text("notifications.tiermodus_akku_bald",
                    "Pet mode: battery close to the lower limit ({0} %)", e.socGrenze));
        }
    }

    private void alarm(Set<String> p, TiermodusRegel.Lage l, TiermodusRegel.Einstellung e) {
        String titelKey = TiermodusRegel.alarmTitel(p);
        String titel = Messages.text("notifications.tiermodus_" + titelKey,
                "alarm_zu_warm".equals(titelKey) ? "Too warm in the car – check your pet!"
                        : "alarm_innen_veraltet".equals(titelKey) ? "Cabin temperature not measurable – check your pet!"
                        : "alarm_unklar".equals(titelKey) ? "Climate unclear – check your pet!"
                        : "Climate OFF – check your pet!");
        StringBuilder body = new StringBuilder();
        for (String code : p) {
            String g;
            switch (code) {
                case TiermodusRegel.AKKU:
                    g = Messages.text("notifications.tiermodus_grund_akku_grenze", "Battery at the lower limit ({0} %)", e.socGrenze);
                    break;
                case TiermodusRegel.ZU_WARM:
                    g = Messages.text("notifications.tiermodus_grund_zu_warm", "Inside {0} °C", Math.round(l.innenC));
                    break;
                default:
                    g = Messages.text("notifications.tiermodus_grund_" + code, code);
            }
            if (body.length() > 0) body.append(" · ");
            body.append(g);
        }
        body.append("\n").append(Messages.text("notifications.tiermodus_gesehen_hinweis",
                "Seen? Tap \"Seen\" in the app or send /tier ok"));
        logger.warn("ALARM " + p + (quittiert.isEmpty() ? "" : " (gesehen: " + quittiert + ")"));
        melden(KATEGORIE_ALARM, NotificationEvent.Severity.CRITICAL, "alarm", titel, body.toString(), p);
    }

    private void einmalMelden(String code, String titel) {
        if (!einmalGemeldet.add(code)) return;
        // „ohne_fuehler“ ist ein ruhiger Hinweis (kein Problem), alles andere eine Warnung
        melden(KATEGORIE_HINWEIS, "ohne_fuehler".equals(code) ? NotificationEvent.Severity.INFO
                : NotificationEvent.Severity.WARN, code, titel, null, null);
    }

    private void melden(String kategorie, NotificationEvent.Severity s, String code, String titel, String body,
                        Set<String> p) {
        try {
            JSONObject data = new JSONObject();
            data.put("code", code);
            if (p != null) data.put("probleme", new JSONArray(p));
            melder.accept(new NotificationEvent(kategorie, s, titel, body == null ? "" : body, "tiermodus-" + code,
                    "/local/tiermodus.html", data));
        } catch (Throwable t) {
            // Eine Meldung darf den Tiermodus nie blockieren
            logger.debug("Meldung " + code + ": " + t.getMessage());
        }
    }

    private static String sollText(TiermodusRegel.Einstellung e) {
        try {
            return at.greenchili.surfmate.byd.KlimaStufe.anzeige(e.wunsch);
        } catch (Throwable t) {
            return e.wunsch + " °C";
        }
    }

    // ---------------------------------------------------------------- Lage

    TiermodusRegel.Lage lage() {
        TiermodusRegel.Lage l = new TiermodusRegel.Lage();
        l.jetztMs = uhr.getAsLong();
        BydVehicleData v = daten.get();
        double tempo = Double.NaN;
        try { tempo = kmh.get(); } catch (Throwable ignored) { }
        l.kmh = tempo;
        l.gangP = gangP.getAsBoolean();
        l.fahrt = v == null ? CampingRegler.Fahrt.UNBEKANNT : Camping.fahrt(v, tempo);
        if (v != null && v.powerLevel != BydVehicleData.UNAVAILABLE && v.powerLevelAtMs > 0) {
            l.powerLevel = v.powerLevel;
            l.powerLevelAlterMs = l.jetztMs - v.powerLevelAtMs;
        }
        if (l.powerLevel == 2) {
            Integer k = null;
            try { k = klimaLive.get(); } catch (Throwable ignored) { }
            if (k != null && (k == 0 || k == 1)) l.klimaLaeuft = k == 1;
        }
        if (v != null) {
            l.soc = v.socPercent > 0 && v.socPercent <= 100 ? v.socPercent : Double.NaN;
            l.aussenC = v.outsideTempC;
            // insideTempC aus dem AC-Gerät (insideTempReadAt = letzte echte Antwort), Frische wie BydEvent
            // (hasFreshCabinTemperature, CABIN_TEMP_MAX_AGE_MS). Nie der Klima-Sollwert (der steht in klimaSollwertC).
            if (innenMessung.getAsBoolean() && !Double.isNaN(v.insideTempC) && v.insideTempReadAt > 0) {
                if (l.jetztMs - v.insideTempReadAt <= TiermodusRegel.INNEN_MAX_ALTER_MS) l.innenC = v.insideTempC;
                else l.innenVeraltet = true;
            }
        }
        l.v12Low = v12Low.getAsBoolean();
        return l;
    }

    static boolean innenMessungBelegt() {
        try {
            String m = at.greenchili.surfmate.byd.KabinenTemperatur.aktuellesModell();
            return at.greenchili.surfmate.byd.KabinenTemperatur.acTempInsideIstMessung(m)
                    || at.greenchili.surfmate.byd.KabinenTemperatur.cloudTempInCarIstMessung(m);
        } catch (Throwable t) {
            return false;
        }
    }

    static TiermodusRegel.Einstellung einstellungAusKonfig() {
        try {
            JSONObject root = UnifiedConfigManager.loadConfig();
            return TiermodusRegel.Einstellung.aus(root == null ? null : root.optJSONObject("tiermodus"));
        } catch (Throwable t) {
            return new TiermodusRegel.Einstellung();
        }
    }

    // ---------------------------------------------------------------- Status

    JSONObject statusJson() {
        JSONObject o = new JSONObject();
        try {
            TiermodusRegel.Einstellung e = einstellung.get();
            o.put("aktiv", aktiv).put("gestartet", true);
            o.put("seit", aktiv ? seitMs : 0);
            o.put("wunsch", e.wunsch).put("alarmC", e.alarmC).put("socGrenze", e.socGrenze)
                    .put("wiederholenMinuten", e.wiederholenMinuten).put("cloud", e.cloud);
            o.put("stufenModell", stufenModell());
            o.put("hinweisVersion", HINWEIS_VERSION).put("hinweisBestaetigt", hinweisBestaetigt());
            o.put("hinweisText", Messages.text("notifications.tiermodus_hinweis_text", HINWEIS_TEXT_EN));
            o.put("hinweisKnopf", Messages.text("notifications.tiermodus_hinweis_knopf", "Understood, I am responsible myself"));
            o.put("anzeige", !aktiv ? "aus" : anzeigeOk ? "ok" : "pruefen");
            o.put("probleme", new JSONArray(aktiv ? probleme : new LinkedHashSet<String>()));
            o.put("quittiert", new JSONArray(aktiv ? quittiert : new LinkedHashSet<String>()));
            o.put("innenMessung", innenMessung.getAsBoolean());
            o.put("innenC", Double.isNaN(innenC) ? JSONObject.NULL : (Object) (Math.round(innenC * 10) / 10.0));
            o.put("klima", letztesKlima == null ? JSONObject.NULL : letztesKlima);
            if (!aktiv) {
                // Für den Start-Knopf: warum es gerade nicht geht (null = möglich)
                String f = TiermodusRegel.startFehler(e, lage(), campingAktiv.getAsBoolean());
                o.put("startFehler", f == null ? JSONObject.NULL : f);
            }
        } catch (Exception ex) {
            logger.debug("Status: " + ex.getMessage());
        }
        return o;
    }

    private static boolean stufenModell() {
        try {
            return at.greenchili.surfmate.byd.KlimaStufe.stufenModell();
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- Datei

    synchronized void laden() {
        try {
            File f = new File(ordner, ZUSTAND_DATEI);
            if (!f.isFile()) return;
            JSONObject z = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            aktiv = z.optBoolean("aktiv", false);
            seitMs = z.optLong("seitMs", 0);
            quelle = z.optString("quelle", "");
            JSONArray q = z.optJSONArray("quittiert");
            quittiert.clear();
            if (q != null) for (int i = 0; i < q.length(); i++) quittiert.add(q.optString(i));
            // Nach einem Neustart sind alle Probleme „neu“ – lieber einmal zu viel melden
            probleme = new LinkedHashSet<>();
        } catch (Throwable t) {
            logger.warn("Zustand nicht lesbar: " + t.getMessage());
        }
    }

    synchronized void speichern() {
        try {
            JSONObject z = new JSONObject();
            z.put("aktiv", aktiv).put("seitMs", seitMs).put("quelle", quelle == null ? "" : quelle)
                    .put("quittiert", new JSONArray(quittiert));
            File f = new File(ordner, ZUSTAND_DATEI);
            File tmp = new File(ordner, ZUSTAND_DATEI + ".tmp");
            Files.write(tmp.toPath(), z.toString().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(f)) {
                Files.write(f.toPath(), z.toString().getBytes(StandardCharsets.UTF_8));
                tmp.delete();
            }
        } catch (Throwable t) {
            logger.debug("Zustand nicht gespeichert: " + t.getMessage());
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
    static Tiermodus fuerTest(String ordner) {
        Tiermodus t = new Tiermodus(ordner);
        t.laden();
        instance = t;
        return t;
    }

    static final String HINWEIS_TEXT_EN = "Pet mode is an aid, not a guarantee. The climate, battery or software can fail, "
            + "and messages can arrive late or not at all. Never leave your pet unattended in the car if you cannot be back "
            + "within a few minutes. You remain responsible yourself; Panoptes accepts no liability.";

    static void testZuruecksetzen() {
        instance = null;
        aktiv = false;
    }
}
