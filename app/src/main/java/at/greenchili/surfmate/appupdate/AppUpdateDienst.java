package at.greenchili.surfmate.appupdate;

import at.greenchili.surfmate.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * In-App-Updater (Koordination 21.10.2026, API NEUBAU2_API_VORSCHLAG.md §1): liest {@code version.json} aus der
 * eingestellten {@link UpdateQuelle} (Standard seit 0.1.15: öffentliches Repo, ohne Token), lädt die APK, prüft sha256
 * und Signaturzertifikat (für jede Quelle gleich) und
 * übergibt die Installation an {@code update-on-car.sh} (dasselbe Skript wie die Deploy-Sitzung: Sicherung,
 * Totmannschalter 30 min, automatischer Rückfall). Selbstbestätigung erst, wenn die neue Version 3 Prüfungen lang
 * gesund läuft (versionCode, Daemons, Web).
 *
 * <p>Regeln: nie während der Fahrt oder bei Zündung an (siehe {@link UpdateRegel}); nie, solange
 * {@code .deploy.lock} besteht (Deploy-Sitzung oder ein laufendes Update); Modus „melden“ (Standard) installiert
 * nie von selbst. Eine zurückgerollte Version wird nicht automatisch erneut versucht. Vorabversionen (Kanal test)
 * werden nie automatisch installiert. Fehlschläge (404/401/kein Netz) landen nur im Protokoll, als Warnung höchstens
 * einmal je Version und Tag ({@link UpdateRegel#berichtSchluessel}); keine Meldung am Display. Eine neue Version
 * meldet ab 0.1.31 zusätzlich die Karte am Display, nur im Stand ({@link UpdateHinweis}).
 */
public final class AppUpdateDienst {

    private static final DaemonLogger logger = DaemonLogger.getInstance("AppUpdate");

    static final String NEU = "/data/user_de/0/com.android.shell/surfmate";
    static final String DD = "/data/local/tmp/surfmate-deploy";
    static final long MAX_APK_BYTES = 250L * 1024 * 1024;
    /**
     * Download in Stücken (HTTP Range): bricht die Mobilfunk-Verbindung ab, geht höchstens ein Stück verloren; die
     * Teildatei bleibt liegen und wird beim nächsten Versuch fortgesetzt (Deploy 21.10.: 118 MB über LTE scheiterten
     * am Stück). sha256 wird erst über die fertige Datei geprüft.
     */
    static final long STUECK_BYTES = 4L * 1024 * 1024;

    /** Netz-Zugriff (im Test nachgebaut). */
    public interface Netz {
        /** Text der Adresse oder Exception. */
        String text(String url) throws Exception;
        /** Lädt nach {@code ziel}; meldet Fortschritt 0–100. */
        void laden(String url, File ziel, long maxBytes, java.util.function.IntConsumer prozent) throws Exception;
        /** Content-Length oder −1. */
        long groesse(String url);

        /**
         * Hängt Bytes ab {@code ab} (höchstens {@code laenge}) per HTTP Range an {@code teil} an und liefert die
         * Gesamtgröße der Datei (−1 = unbekannt). Antwortet der Server ohne Range (200), wird {@code teil} von vorn
         * vollständig geschrieben. Standard (ohne Range-Unterstützung): alles auf einmal über {@link #laden}.
         */
        /**
         * Lese-Token für die folgenden Abrufe (null = ohne). Geht nur an api.github.com, nie an andere Hosts (auch
         * nicht nach einer Umleitung zum Datei-Speicher).
         */
        default void zugang(String token) { }

        default long ladenStueck(String url, File teil, long ab, long laenge, long maxBytes) throws Exception {
            File neu = new File(teil.getPath() + ".neu");
            neu.delete();
            laden(url, neu, maxBytes, p -> { });
            teil.delete();
            if (!neu.renameTo(teil)) throw new java.io.IOException("Umbenennen fehlgeschlagen");
            return teil.length();
        }
    }

    /** Auto, Dateien, Prozesse (im Test nachgebaut). */
    public interface Umgebung {
        int installierterVc();
        String installierteVersion();
        /** {allowed, grund} von CloudPowerGate. */
        Object[] autoAus();
        /** km/h oder NaN, wenn unbekannt. */
        double kmh();
        /** ACC autoritativ aus → TRUE, autoritativ an → FALSE, nicht autoritativ/unbekannt → null. */
        Boolean accAus();
        String config(String key);

        /** 0.1.32: neue Einrichtung, Update-Frage noch offen: kein Abruf im Hintergrund. */
        default boolean updatesUnbeantwortet() { return false; }
        File basis();
        File arbeitsordner();
        /** SHA-256 des Zertifikats der installierten App oder null (nicht lesbar). */
        String installiertesZertifikat();
        /**
         * update-on-car.sh als Text. 0.1.19: aus dem geprüften neuen APK ({@code apk}, sha256 + Zertifikat sind dann
         * schon bestätigt), sonst aus dem installierten – nie über einen Android-Context (im Daemon gibt es keinen).
         */
        String skript(File apk) throws Exception;
        /** Startet das Skript abgekoppelt (überlebt den Tod des Daemons). */
        void starteAbgekoppelt(File skript, File apk, String schluessel, int vc, String tag) throws Exception;
        long jetzt();
        /** "wlan" | "mobil" | "unbekannt" ({@link UpdateRegel#netz}). */
        default String netz() { return UpdateRegel.NETZ_UNBEKANNT; }
        /** Neue Version gefunden → Telegram (Kategorie UPDATE), mit Größe, wenn bekannt. */
        default void melde(Freigabe f, long groesseBytes) { }
        /** Schreibt Werte in den Config-Abschnitt {@code update}; true = gespeichert. */
        default boolean setzeConfig(java.util.Map<String, Object> werte) { return false; }
        /** Fahrsperre: null = steht in P bzw. Auto aus; sonst Grund (DrivingSafetyGuard). */
        default String fahrsperre() { return "state_unknown"; }
        /** Wächter-Ereignis oder Abschreckungs-Anzeige läuft gerade. */
        default boolean waechterEreignis() { return true; }
        /** Es wird gerade aufgenommen (vor einer vorgemerkten Installation). */
        default boolean aufnahmeLaeuft() { return true; }
        /** Gang P: TRUE/FALSE, null = unbekannt. */
        default Boolean gangP() { return null; }
        /** Update-Hinweis zeigen; true = steht wirklich am Display. */
        default boolean zeigeHinweis(String griff, Freigabe f, String notizen, String zusatz) {
            return zeigeHinweis(griff, f, notizen);
        }

        default boolean zeigeHinweis(String griff, Freigabe f, String notizen) { return false; }
        /** Update-Hinweis mit diesem Griff wegnehmen. */
        default void nimmHinweisWeg(String griff) { }
    }

    private static volatile AppUpdateDienst instance;

    final Netz netz;
    final Umgebung env;
    private final Object lock = new Object();

    // Zustand (in appupdate_stand.json gespiegelt)
    private long letztePruefung;
    private String ergebnis = "nie";
    private String fehler;
    private Freigabe verfuegbar;
    private String phase;
    private Integer prozent;
    private long ausSeit = -1;
    /** Seit wann ACC autoritativ aus + 0 km/h (zweiter Nachweis), −1 = nicht. */
    private long zweiterSeit = -1;
    private long letztesSuchen;
    private boolean installiertGerade;
    /** Höchster versionCode, der schon per Telegram gemeldet wurde. */
    private int gemeldetVc;
    /** {@link UpdateQuelle#kennung()} der letzten Prüfung; wechselt sie, gilt {@link #verfuegbar} nicht mehr. */
    private String quelleKennung;
    /** Version@Tag der letzten Warnung im Protokoll (Drossel: eine je Version und Tag). */
    private String berichtSchluessel;
    /** 0.1.31 Update-Hinweis, Stand je Version (gespiegelt). */
    private final UpdateHinweis.Stand hinweis = new UpdateHinweis.Stand();
    /** Griff der gerade gezeigten Karte (null = keine) und Ablauf; nur im Speicher. */
    private String hinweisGriff;
    private long hinweisBis;
    private long hinweisPauseBis;
    private long hinweisRuhigSeit = -1;
    private long hinweisBlick;

    AppUpdateDienst(Netz netz, Umgebung env) {
        this.netz = netz;
        this.env = env;
        boolean bestand = standDatei().exists();
        laden();
        migriereQuelle(bestand);
    }

    public static synchronized void start() {
        if (instance != null) return;
        AppUpdateDienst d = new AppUpdateDienst(new OkNetz(), new AndroidUmgebung());
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AppUpdate");
            t.setDaemon(true);
            return t;
        });
        // jede Minute: Lage (geparkt seit?) und ggf. automatisch; Prüfung im Netz nur alle 6 h
        exec.scheduleWithFixedDelay(() -> {
            try {
                d.takt();
            } catch (Throwable t) {
                logger.warn("takt: " + t.getMessage());
            }
        }, 10 * 60, 60, TimeUnit.SECONDS);
        // eigener Faden: takt() kann beim Laden lange blockieren, die Karte muss beim Gangwechsel sofort weg
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AppUpdate-Hinweis");
            t.setDaemon(true);
            return t;
        }).scheduleWithFixedDelay(() -> {
            try {
                d.hinweisTakt();
            } catch (Throwable t) {
                logger.warn("hinweis: " + t.getMessage());
            }
        }, 60, 1, TimeUnit.SECONDS);
        instance = d;
    }

    public static AppUpdateDienst get() {
        return instance;
    }

    // ------------------------------------------------------------------ API

    /** GET /api/appupdate/status. */
    public JSONObject status(String sprache) {
        synchronized (lock) {
            UpdateRegel.Lage l = lage();
            JSONObject o = new JSONObject();
            try {
                o.put("kanal", kanal());
                UpdateQuelle q = quelle();
                o.put("quelle", q.art);
                o.put("quelleRepo", q.repo);
                o.put("modus", automatisch() ? "automatisch" : "melden");
                o.put("pruefen", pruefenAn());
                o.put("mobilfunk", !l.nurWlan);
                o.put("netz", l.netz);
                o.put("aktuell", new JSONObject().put("version", env.installierteVersion())
                        .put("versionCode", env.installierterVc()));
                o.put("letztePruefung", letztePruefung > 0 ? letztePruefung : JSONObject.NULL);
                o.put("ergebnis", ergebnis);
                o.put("fehler", fehler == null ? JSONObject.NULL : fehler);
                if (verfuegbar != null && verfuegbar.versionCode > env.installierterVc()) {
                    JSONObject v = new JSONObject();
                    v.put("version", verfuegbar.versionName).put("versionCode", verfuegbar.versionCode);
                    long g = groesseCache;
                    v.put("groesseBytes", g > 0 ? g : JSONObject.NULL);
                    v.put("veroeffentlicht", JSONObject.NULL);
                    v.put("notizen", verfuegbar.notizen(sprache));
                    v.put("kanal", verfuegbar.kanal);
                    v.put("zurueckgerollt", verfuegbar.versionCode == l.rueckfallVc);
                    o.put("verfuegbar", v);
                } else {
                    o.put("verfuegbar", JSONObject.NULL);
                }
                String nicht = nichtWeil(l);
                o.put("installierbar", nicht == null);
                // 0.1.32.1: ohne WLAN fragen wie die Karte (nur neue Einrichtungen, Größe, nur diese Version)
                o.put("mobilFrage", UpdateRegel.WARTET_WLAN.equals(nicht) && mobilMerker());
                o.put("nichtWeil", nicht == null ? JSONObject.NULL : nicht);
                o.put("phase", aktuellePhase(l));
                o.put("prozent", prozent == null ? JSONObject.NULL : prozent);
                long geladen = geladenBytes();
                o.put("geladenBytes", geladen > 0 ? geladen : JSONObject.NULL);
                long ab = UpdateRegel.automatischAb(l, automatisch(), pruefenAn(), ausSeit);
                o.put("automatischGeplant", ab > 0 ? ab : JSONObject.NULL);
                o.put("vorgemerkt", vorgemerkt());
            } catch (Exception ignored) { }
            return o;
        }
    }

    /** POST /api/appupdate/suchen → null bei Ratenlimit, sonst frischer Status. */
    public JSONObject suchen(String sprache) {
        long now = env.jetzt();
        synchronized (lock) {
            if (now - letztesSuchen < UpdateRegel.SUCHEN_MIN_ABSTAND_MS) return null;
            letztesSuchen = now;
        }
        pruefen(true);
        return status(sprache);
    }

    /** POST /api/appupdate/installieren {versionCode} → null = gestartet, sonst Grund. */
    public String installieren(int versionCode) {
        return installieren(versionCode, false);
    }

    /** {@code mobil} = Einwilligung für genau diesen einen Download; greift nur nach der Frage ({@code mobilFrage}). */
    public String installieren(int versionCode, boolean mobil) {
        synchronized (lock) {
            if (installiertGerade) return "laeuft_schon";
            boolean einmal = mobil && mobilMerker() && verfuegbar != null && verfuegbar.versionCode == versionCode;
            if (einmal) UpdateHinweis.mobilErlauben(hinweis, versionCode, env.jetzt());
            UpdateRegel.Lage l = lage();
            String nicht = nichtWeil(l);
            if (nicht != null) {
                if (einmal) UpdateHinweis.mobilErlauben(hinweis, 0, 0);
                return nicht;
            }
            if (verfuegbar.versionCode != versionCode) return "anderer_stand";
            if (einmal) speichern();
            installiertGerade = true;
        }
        final Freigabe f = verfuegbar;
        Thread t = new Thread(() -> {
            try {
                ausfuehren(f);
            } finally {
                synchronized (lock) { installiertGerade = false; }
            }
        }, "AppUpdate-Install");
        t.setDaemon(true);
        t.start();
        return null;
    }

    /**
     * {@code POST /api/appupdate/hinweis {aktion:"installieren", version}} vom Knopf der Karte: startet über den
     * normalen Weg ({@link FernUpdate#starten}, gleiche Regeln). Fehlt nur noch „Auto aus“, merkt es sich die Version.
     * Antwort {@code {ok, vorgemerkt, text}}.
     */
    public JSONObject hinweisInstallieren(FernUpdate fern, String version, String sprache) throws Exception {
        synchronized (lock) {
            // 0.1.32: Der Tipp ist die Einwilligung für genau diesen einen Download (die Karte nannte die Größe)
            if (mobilMerker() && verfuegbar != null && verfuegbar.versionName.equals(version)) {
                UpdateHinweis.mobilErlauben(hinweis, verfuegbar.versionCode, env.jetzt());
                speichern();
            }
        }
        JSONObject r = fern.starten(version, true, UpdateHinweis.geraetName(), sprache);
        if (r.optBoolean("ok", false)) return r.put("vorgemerkt", false);
        String grund = r.optString("grund", "");
        if (!UpdateHinweis.vormerkbar(grund)) {
            synchronized (lock) {
                UpdateHinweis.mobilErlauben(hinweis, 0, 0);
                speichern();
            }
            return r.put("vorgemerkt", false);
        }
        synchronized (lock) {
            if (verfuegbar == null || !verfuegbar.versionName.equals(version)
                    || verfuegbar.versionCode == rueckfallVc() || !UpdateHinweis.kanalPasst(verfuegbar, kanal())) {
                return r.put("vorgemerkt", false);
            }
            UpdateHinweis.vormerken(hinweis, verfuegbar.versionCode, env.jetzt());
            speichern();
        }
        logger.info("Update-Hinweis: " + version + " vorgemerkt (" + grund + ")");
        return new JSONObject().put("ok", true).put("vorgemerkt", true)
                .put("text", at.greenchili.surfmate.server.Messages.withLocale(sprache,
                        () -> UpdateHinweis.textVorgemerkt("wartet_wlan".equals(grund))));
    }

    /** {@code POST /api/appupdate/hinweis {aktion:"zuruecknehmen"}}: doch nicht von selbst installieren. */
    public void hinweisZuruecknehmen() {
        synchronized (lock) {
            UpdateHinweis.vergessen(hinweis);
            speichern();
        }
    }

    private boolean vorgemerkt() {
        return verfuegbar != null && verfuegbar.versionCode > env.installierterVc()
                && UpdateHinweis.vorgemerktGilt(hinweis, verfuegbar.versionCode, env.jetzt());
    }

    private boolean vorgemerktFrei() {
        return UpdateHinweis.installFrei(env.fahrsperre(), env.gangP(), env.waechterEreignis(), env.aufnahmeLaeuft());
    }

    /**
     * Sekundentakt der Karte am Display: Solange sie steht, nimmt jede Änderung am Stand sie sofort weg. Sonst wird
     * alle {@value #HINWEIS_BLICK_MS} ms nachgesehen, und das nur, wenn es überhaupt etwas Neues gibt (ohne Netz).
     */
    void hinweisTakt() {
        long now = env.jetzt();
        String griff;
        synchronized (lock) {
            griff = hinweisGriff;
            if (griff == null) {
                if (verfuegbar == null || verfuegbar.versionCode <= env.installierterVc()) return;
                if (now >= hinweisBlick && now - hinweisBlick < HINWEIS_BLICK_MS) return;
                hinweisBlick = now;
            }
        }
        boolean ruhig = UpdateHinweis.ruhig(env.accAus(), env.fahrsperre(), env.kmh(), env.waechterEreignis());
        if (griff != null) {
            if (ruhig && now < hinweisBis) return;
            synchronized (lock) { hinweisGriff = null; hinweisRuhigSeit = -1; }
            env.nimmHinweisWeg(griff);
            return;
        }
        Freigabe f;
        String zusatz;
        synchronized (lock) {
            hinweisRuhigSeit = !ruhig ? -1 : hinweisRuhigSeit < 0 ? now : hinweisRuhigSeit;
            if (now < hinweisPauseBis) return;
            UpdateHinweis.Lage l = new UpdateHinweis.Lage();
            l.schalterAn = UpdateRegel.an(env.config("hinweis"));
            l.pruefenAn = pruefenAn();
            l.automatisch = automatisch();
            l.kanal = kanal();
            l.installiert = env.installierterVc();
            l.verfuegbar = verfuegbar;
            l.rueckfallVc = rueckfallVc();
            l.laeuftSchon = installiertGerade || phase != null || new File(env.basis(), ".deploy.lock").exists();
            l.ruhigSeit = hinweisRuhigSeit;
            l.jetzt = now;
            if (UpdateHinweis.nichtWeil(l, hinweis) != null) return;
            f = verfuegbar;
            zusatz = UpdateHinweis.mobilSatz(UpdateHinweis.mobilFragen(mobilMerker(),
                    !UpdateRegel.an(env.config("mobilfunk")), env.netz()), groesseCache);
        }
        String neuerGriff = String.format("%016x", new SecureRandom().nextLong());
        boolean gezeigt = false;
        try {
            gezeigt = env.zeigeHinweis(neuerGriff, f, UpdateHinweis.notizenText(
                    f.notizen(at.greenchili.surfmate.server.LocaleManager.get())), zusatz);
        } catch (Throwable t) {
            logger.warn("Update-Hinweis: " + t.getMessage());
        }
        synchronized (lock) {
            if (!gezeigt) {
                hinweisPauseBis = now + UpdateHinweis.PAUSE_NACH_FEHLVERSUCH_MS;
                return;
            }
            hinweisGriff = neuerGriff;
            hinweisBis = now + UpdateHinweis.SICHTBAR_SEK * 1000L;
            UpdateHinweis.gezeigt(hinweis, f.versionCode, now);
            speichern();
        }
        logger.info("Update-Hinweis gezeigt: " + f.versionName + " (" + hinweis.anzahl + ". Mal)");
    }

    private static final long HINWEIS_BLICK_MS = 5_000L;

    // ------------------------------------------------------------------ Ablauf

    void takt() {
        long now = env.jetzt();
        // update.pruefen aus (Ersteinrichtung „Nein“): kein Abruf bei GitHub im Hintergrund, nichts von selbst
        if (pruefenAn() && UpdateRegel.pruefenFaellig(now, letztePruefung, fehler != null)) pruefen(false);
        Freigabe f;
        boolean alsVorgemerkt;
        synchronized (lock) {
            UpdateRegel.Lage l = lage();
            boolean frei = nichtWeil(l) == null;
            if (!frei) ausSeit = -1;
            else if (ausSeit < 0) ausSeit = now;
            alsVorgemerkt = vorgemerkt() && !automatisch();
            long ab = alsVorgemerkt
                    ? UpdateHinweis.vorgemerktAb(l, hinweis, kanal(), vorgemerktFrei(), now, ausSeit)
                    : UpdateRegel.automatischAb(l, automatisch(), pruefenAn(), ausSeit);
            if (ab < 0 || now < ab || installiertGerade) return;
            installiertGerade = true;
            laufVorgemerkt = alsVorgemerkt;
            laufGescheitert = false;
            f = verfuegbar;
        }
        logger.info("automatisch: installiere " + f.versionName + " (vC " + f.versionCode + ")");
        try {
            ausfuehren(f);
        } finally {
            synchronized (lock) {
                installiertGerade = false;
                if (alsVorgemerkt) {
                    UpdateHinweis.versuchVorbei(hinweis, laufGescheitert, env.jetzt());
                    speichern();
                }
                laufVorgemerkt = false;
            }
        }
    }

    /** Dieser Lauf kommt aus einer Vormerkung: Vor dem Start gilt zusätzlich {@link #vorgemerktFrei()}. */
    private boolean laufVorgemerkt;
    private boolean laufGescheitert;

    private volatile long groesseCache = -1;

    /** Hintergrund-Prüfung (Fehlschläge bleiben still). */
    void pruefen() {
        pruefen(false);
    }

    /**
     * Holt version.json aus der eingestellten Quelle (Kanal stabil: neueste Nicht-Vorabversion; test: zusätzlich
     * neueste Vorabversion). {@code manuell} = auf Knopfdruck: nur dann wird ein Fehlschlag als Ergebnis „fehler“
     * gezeigt. Im Hintergrund bleibt das bisherige Ergebnis stehen, der Fehler geht nur (gedrosselt) ins Protokoll.
     */
    void pruefen(boolean manuell) {
        String k = kanal();
        UpdateQuelle q = quelle();
        netz.zugang(q.token);
        Freigabe neu = null;
        String err = null;
        String ursache = null;
        try {
            neu = q.mitToken() ? ausRelease(q, netz.text(q.latestRelease()))
                    : mitApk(q, Freigabe.lies(netz.text(q.latestVersionJson())));
        } catch (Freigabe.Fehler e) {
            err = e.grund;
            ursache = e.getMessage();
        } catch (Exception e) {
            err = grundAus(q, e);
            ursache = e.getMessage();
        }
        if ("test".equals(k)) {
            try {
                String tag = neuesterTag(netz.text(q.releasesListe()));
                if (tag != null) {
                    Freigabe t = q.mitToken() ? ausRelease(q, netz.text(q.releaseZuTag(tag)))
                            : mitApk(q, Freigabe.lies(netz.text(q.download(tag, "version.json"))));
                    neu = UpdateRegel.neuere(neu, t);
                    err = null;
                }
            } catch (Freigabe.Fehler e) {
                if (neu == null) {
                    err = e.grund;
                    ursache = e.getMessage();
                }
            } catch (Exception e) {
                if (neu == null && err == null) {
                    err = grundAus(q, e);
                    ursache = e.getMessage();
                }
            }
        } else if (neu != null && !"stabil".equals(neu.kanal)) {
            neu = null;   // „latest“ ist nie eine Vorabversion; doppelt hält besser
        }
        long g = -1;
        if (neu != null && neu.versionCode > env.installierterVc()) g = netz.groesse(neu.apkUrl());
        boolean melden = false;
        synchronized (lock) {
            letztePruefung = env.jetzt();
            if (!q.kennung().equals(quelleKennung)) {
                // andere Quelle als beim letzten Mal: was dort gefunden wurde, gilt nicht mehr
                verfuegbar = null;
                groesseCache = -1;
                quelleKennung = q.kennung();
            }
            if (neu != null) {
                verfuegbar = neu;
                fehler = null;
                ergebnis = neu.versionCode > env.installierterVc() ? "verfuegbar" : "aktuell";
                groesseCache = g;
                if (neu.versionCode > env.installierterVc() && neu.versionCode > gemeldetVc) {
                    gemeldetVc = neu.versionCode;   // je Version nur einmal melden
                    melden = true;
                }
            } else {
                fehler = err == null ? "nicht_erreichbar" : err;
                if (manuell) ergebnis = "fehler";
                // ein früher gefundenes Update bleibt bekannt (nur nicht frisch bestätigt)
            }
            speichern();
        }
        if (melden) {
            try {
                env.melde(neu, g);
            } catch (Throwable t) {
                logger.warn("melden: " + t.getMessage());
            }
        }
        if (neu != null) {
            logger.info("Prüfung (" + k + ", " + q.art + "): " + ergebnis + " → " + neu.versionName
                    + " vC " + neu.versionCode);
        } else {
            berichte("Prüfung (" + k + ", " + q.art + " " + q.repo + ") fehlgeschlagen: " + fehler
                    + (ursache != null ? " (" + ursache + ")" : ""));
        }
    }

    /** Ohne Token: die APK liegt unter der öffentlichen Download-Adresse desselben Releases. */
    private static Freigabe mitApk(UpdateQuelle q, Freigabe f) {
        return f.mitApkUrl(q.download(f.tag, f.apk));
    }

    /** Mit Token: version.json und APK als API-Assets der Release-Beschreibung (private Repos). */
    private Freigabe ausRelease(UpdateQuelle q, String releaseJson) throws Exception {
        String vj = UpdateQuelle.asset(releaseJson, "version.json");
        if (vj == null) throw new Freigabe.Fehler("nicht_erreichbar", "Release ohne version.json");
        Freigabe f = Freigabe.lies(netz.text(vj));
        String apk = UpdateQuelle.asset(releaseJson, f.apk);
        if (apk == null) throw new Freigabe.Fehler("nicht_erreichbar", "Release ohne " + f.apk);
        return f.mitApkUrl(apk);
    }

    /** 401/403 (oder 404 mit Token, so antwortet GitHub bei fehlendem Recht) → kein_zugang, sonst nicht_erreichbar. */
    static String grundAus(UpdateQuelle q, Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.contains("HTTP 401") || m.contains("HTTP 403")) return "kein_zugang";
        if (m.contains("HTTP 404") && q.mitToken()) return "kein_zugang";
        return "nicht_erreichbar";
    }

    /** Fehlschlag ins Protokoll: höchstens eine Warnung je installierter Version und Tag, sonst nur debug. */
    private void berichte(String text) {
        String neu;
        synchronized (lock) {
            neu = UpdateRegel.berichtSchluessel(berichtSchluessel, env.installierteVersion(), env.jetzt());
            if (neu != null) {
                berichtSchluessel = neu;
                speichern();
            }
        }
        if (neu != null) logger.warn(text);
        else logger.debug(text);
    }

    /** Schlüssel der letzten Warnung (Version@Tag), für Tests. */
    String berichtSchluessel() {
        synchronized (lock) { return berichtSchluessel; }
    }

    UpdateQuelle quelle() {
        return UpdateQuelle.aus(env.config("quelle"), env.config("repo"), env.config("token"));
    }

    /**
     * Einmalig (Marke {@code .appupdate-quelle}): Installationen, deren Updater schon vor 0.1.15 lief, behalten die
     * bisher fest eingebaute Quelle bzw. einen vorhandenen Token; neue Installationen nutzen die öffentliche Quelle.
     * Scheitert das Speichern, versucht es der nächste Start erneut (die Marke bleibt dann weg).
     */
    private void migriereQuelle(boolean bestand) {
        File marke = new File(env.basis(), ".appupdate-quelle");
        if (marke.exists()) return;
        java.util.Map<String, Object> m = UpdateQuelle.migration(env.config("quelle"), env.config("repo"),
                env.config("token"), bestand);
        if (!m.isEmpty()) {
            boolean ok;
            try {
                ok = env.setzeConfig(m);
            } catch (Throwable t) {
                ok = false;
            }
            if (!ok) {
                logger.warn("Update-Quelle: Übernahme nicht gespeichert, der nächste Start versucht es erneut");
                return;
            }
            logger.info("Update-Quelle: bestehende Installation behält ihre Quelle " + m);
        }
        try {
            //noinspection ResultOfMethodCallIgnored
            env.basis().mkdirs();
            //noinspection ResultOfMethodCallIgnored
            marke.createNewFile();
        } catch (Exception e) {
            logger.debug("Update-Quelle: Marke: " + e.getMessage());
        }
    }

    /** Tag der neuesten nicht-Entwurfs-Version aus der GitHub-API-Liste (neueste zuerst). */
    static String neuesterTag(String apiJson) {
        try {
            org.json.JSONArray a = new org.json.JSONArray(apiJson);
            for (int i = 0; i < a.length(); i++) {
                JSONObject r = a.getJSONObject(i);
                if (r.optBoolean("draft", false)) continue;
                String tag = r.optString("tag_name", "");
                if (tag.matches("v[0-9.]+(-test\\.[0-9]+)?")) return tag;
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** Laden → prüfen → Sperre → Skript starten. Fehler landen in {@code fehler}, der Daemon läuft weiter. */
    void ausfuehren(Freigabe f) {
        // 0.1.19: eine hier gesetzte Deploy-Sperre wird bei JEDEM Abbruch vor dem Skriptstart wieder freigegeben
        // (Befund 27.10.: „kein Context“ fiel ins äußere catch, .deploy.lock blieb liegen → dauerhaft laeuft_schon)
        File[] gesetzteSperre = {null};
        String[] sperrSchluessel = {null};
        boolean[] gestartet = {false};
        try {
            ausfuehrenIntern(f, gesetzteSperre, sperrSchluessel, gestartet);
        } finally {
            if (gesetzteSperre[0] != null && !gestartet[0]) sperreFreigeben(gesetzteSperre[0], sperrSchluessel[0]);
        }
    }

    /** Nur unsere Sperre (Schlüssel in info) entfernen – nie die einer Deploy-Sitzung. */
    static void sperreFreigeben(File sperre, String schluessel) {
        File info = new File(sperre, "info");
        try {
            if (info.isFile()) {
                String t = new String(Files.readAllBytes(info.toPath()), StandardCharsets.UTF_8);
                if (schluessel == null || !t.contains("schluessel=" + schluessel)) return;
            }
            //noinspection ResultOfMethodCallIgnored
            info.delete();
            //noinspection ResultOfMethodCallIgnored
            sperre.delete();
            logger.info("Deploy-Sperre freigegeben (Update nicht gestartet)");
        } catch (Exception e) {
            logger.warn("Deploy-Sperre freigeben: " + e.getMessage());
        }
    }

    private void ausfuehrenIntern(Freigabe f, File[] gesetzteSperre, String[] sperrSchluessel, boolean[] gestartet) {
        File dd = env.arbeitsordner();
        //noinspection ResultOfMethodCallIgnored
        dd.mkdirs();
        File apk = new File(dd, "neu-appupdate-" + f.versionCode + ".apk");
        File teil = teilDatei(f);
        if (!UpdateQuelle.erlaubteAdresse(f.apkUrl())) {
            scheitern("nicht_erreichbar", "keine Download-Adresse für " + f.tag, null);
            return;
        }
        netz.zugang(quelle().token);
        try {
            alteTeileWeg(teil);
            boolean fertig;
            try {
                fertig = ladeInStuecken(f, teil);
            } catch (Exception e) {
                // Verbindung weg o. Ä.: Teildatei bleibt, der nächste Versuch setzt dort fort
                scheitern("nicht_erreichbar", "Download unterbrochen bei " + teil.length() + " B: " + e.getMessage(),
                        null);
                return;
            }
            if (!fertig) return;   // pausiert (Zündung, kein WLAN …): Teildatei bleibt
            setzePhase("prueft", null);
            String sha = sha256(teil);
            if (!f.sha256.equals(sha)) {
                scheitern("pruefsumme", "sha256 " + sha + " ≠ " + f.sha256, teil);
                return;
            }
            // 0.1.15: altes und neues Panoptes-Zertifikat (Rotation mit Lineage), nie zurück von neu auf alt.
            // Passt es nicht, würde pm install scheitern – Daemons gar nicht erst stoppen.
            String grund = Freigabe.signaturPasst(ApkZertifikat.sha256(teil), env.installiertesZertifikat());
            if (grund != null) {
                scheitern("signatur", grund, teil);
                return;
            }
            synchronized (lock) {
                if (laufVorgemerkt && !vorgemerktFrei()) {
                    phase = null; prozent = null;
                    logger.info("Vorgemerkte Installation verschoben: Wächter oder Aufnahme");
                    return;   // Teildatei bleibt geprüft liegen, kein zweiter Download
                }
            }
            apk.delete();
            if (!teil.renameTo(apk)) {
                scheitern("nicht_erreichbar", "Umbenennen fehlgeschlagen", teil);
                return;
            }
            // Letzte Prüfung direkt vor dem Start: noch immer aus, keine Sperre
            synchronized (lock) {
                String nicht = nichtWeil(lage());
                if (nicht != null) {
                    phase = null; prozent = null;
                    fehler = null;
                    logger.info("Installation verschoben: " + nicht);
                    apk.delete();
                    return;
                }
            }
            File sperre = new File(env.basis(), ".deploy.lock");
            if (!sperre.mkdir()) {
                synchronized (lock) { phase = null; prozent = null; }
                logger.info("Installation verschoben: Deploy-Sperre besteht");
                apk.delete();
                return;
            }
            String schluessel = "appupdate-" + env.jetzt() + "-" + Long.toHexString(new SecureRandom().nextLong());
            gesetzteSperre[0] = sperre;
            sperrSchluessel[0] = schluessel;
            Files.write(new File(sperre, "info").toPath(), ("sitzung=appupdate\ncommit=" + f.tag + "\napk="
                    + apk.getAbsolutePath() + "\nstart=" + env.jetzt() + "\nschluessel=" + schluessel + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            File skript = new File(dd, "update-on-car.sh");
            Files.write(skript.toPath(), env.skript(apk).getBytes(StandardCharsets.UTF_8));
            new File(env.basis(), ".appupdate-rueckfall").delete();
            setzePhase("installiert", null);
            logger.warn("Installation " + f.versionName + " (vC " + f.versionCode + ", " + f.tag
                    + ") startet – Totmannschalter 30 min, Selbstbestätigung nach 3 gesunden Prüfungen");
            try {
                env.starteAbgekoppelt(skript, apk, schluessel, f.versionCode, f.tag);
                gestartet[0] = true;   // ab hier gehört die Sperre dem Skript (Totmann, Selbstbestätigung)
            } catch (Exception e) {
                // Skript lief nicht an: Sperre gibt ausfuehren() frei (nur unsere)
                scheitern("nicht_erreichbar", "Start fehlgeschlagen: " + e.getMessage(), apk);
            }
        } catch (Exception e) {
            scheitern("nicht_erreichbar", e.getMessage(), teil);
        }
    }

    /** Teildatei je Version und erwarteter Prüfsumme – nie zwei Stände in einer Datei. */
    File teilDatei(Freigabe f) {
        return new File(env.arbeitsordner(), "neu-appupdate-" + f.versionCode + "-" + f.sha256.substring(0, 12)
                + ".apk.part");
    }

    /** Teildateien anderer Versionen weg (Platz); die passende bleibt zum Fortsetzen. */
    private void alteTeileWeg(File behalten) {
        File[] l = behalten.getParentFile() == null ? null : behalten.getParentFile().listFiles();
        if (l == null) return;
        for (File x : l) {
            String n = x.getName();
            if (n.startsWith("neu-appupdate-") && (n.endsWith(".part") || n.endsWith(".part.neu"))
                    && !n.equals(behalten.getName())) {
                //noinspection ResultOfMethodCallIgnored
                x.delete();
            }
        }
    }

    /** Bytes der passenden Teildatei (für Status „fortsetzbar“), sonst 0. */
    private long geladenBytes() {
        Freigabe f = verfuegbar;
        if (f == null) return 0;
        try {
            File t = teilDatei(f);
            return t.exists() ? t.length() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Lädt Stück für Stück. Vor jedem Stück: Auto noch geparkt, ggf. WLAN – sonst pausieren (kein Fehler, keine
     * Eskalation; die Teildatei bleibt und wird beim nächsten Versuch fortgesetzt). true = vollständig.
     */
    boolean ladeInStuecken(Freigabe f, File teil) throws Exception {
        long gesamt = groesseCache > 0 ? groesseCache : -1;
        setzePhase("laedt", prozentVon(teil.exists() ? teil.length() : 0, gesamt));
        while (true) {
            long ab = teil.exists() ? teil.length() : 0;
            if (gesamt > 0 && ab == gesamt) return true;
            if (gesamt > 0 && ab > gesamt) {
                // Teildatei größer als die Datei am Server: verwerfen, von vorn
                //noinspection ResultOfMethodCallIgnored
                teil.delete();
                ab = 0;
            }
            String nicht;
            synchronized (lock) { nicht = nichtWeil(lage()); }
            if (nicht != null) {
                synchronized (lock) { phase = null; prozent = null; }
                logger.info("Download pausiert bei " + ab + " B: " + nicht);
                return false;
            }
            long g = netz.ladenStueck(f.apkUrl(), teil, ab, STUECK_BYTES, MAX_APK_BYTES);
            if (g > MAX_APK_BYTES) throw new java.io.IOException("zu groß");
            if (g > 0) {
                gesamt = g;
                if (groesseCache != g) {
                    groesseCache = g;
                    synchronized (lock) { speichern(); }
                }
            }
            long nun = teil.exists() ? teil.length() : 0;
            synchronized (lock) { prozent = prozentVon(nun, gesamt); }
            if (gesamt <= 0) return true;              // Server ohne Größenangabe: was kam, ist alles
            if (nun == ab) throw new java.io.IOException("keine Daten ab " + ab);
        }
    }

    private static Integer prozentVon(long n, long gesamt) {
        return gesamt > 0 ? (int) Math.min(100, n * 100 / gesamt) : 0;
    }

    private void scheitern(String grund, String text, File weg) {
        if (weg != null) weg.delete();
        synchronized (lock) {
            phase = null;
            prozent = null;
            fehler = grund;
            ergebnis = "fehler";
            laufGescheitert = true;
            speichern();
        }
        berichte("Update abgebrochen (" + grund + "): " + text);
    }

    private void setzePhase(String p, Integer pr) {
        synchronized (lock) { phase = p; prozent = pr; }
    }

    // ------------------------------------------------------------------ Lage

    UpdateRegel.Lage lage() {
        UpdateRegel.Lage l = new UpdateRegel.Lage();
        l.installiert = env.installierterVc();
        l.verfuegbar = verfuegbar;
        Object[] gate = env.autoAus();
        l.autoAus = Boolean.TRUE.equals(gate[0]);
        l.gateGrund = (String) gate[1];
        l.kmh = env.kmh();
        long jetzt = env.jetzt();
        zweiterSeit = UpdateRegel.zweiterNachweisSeit(zweiterSeit, env.accAus(), l.kmh, jetzt);
        l.zweiterNachweis = UpdateRegel.zweiterNachweisGilt(zweiterSeit, jetzt);
        l.laeuftSchon = new File(env.basis(), ".deploy.lock").exists();   // eigener Ablauf: installiertGerade
        l.rueckfallVc = rueckfallVc();
        l.nurWlan = !UpdateRegel.an(env.config("mobilfunk"));
        l.mobilEinmal = verfuegbar != null && mobilMerker()
                && UpdateHinweis.mobilGilt(hinweis, verfuegbar.versionCode, jetzt);
        l.netz = env.netz();
        return l;
    }

    /** {@code update.mobilfunkEinmal}: nur neue Einrichtungen, siehe {@link UpdateHinweis}. */
    private boolean mobilMerker() {
        return "true".equals(env.config("mobilfunkEinmal"));
    }

    private static String nichtWeil(UpdateRegel.Lage l) {
        return UpdateRegel.nichtWeil(l);
    }

    /** Phase für die Anzeige; nach dem Neustart aus Sperre/Rückfall-Marke abgeleitet. */
    private Object aktuellePhase(UpdateRegel.Lage l) {
        if (phase != null) return phase;
        File info = new File(new File(env.basis(), ".deploy.lock"), "info");
        try {
            if (info.exists() && new String(Files.readAllBytes(info.toPath()), StandardCharsets.UTF_8)
                    .contains("sitzung=appupdate")) {
                return "beobachtet";
            }
        } catch (Exception ignored) { }
        if (l.rueckfallVc > 0 && l.rueckfallVc > l.installiert && rueckfallFrisch()) return "rueckfall";
        if (!installiertGerade && l.verfuegbar != null && l.verfuegbar.versionCode > l.installiert
                && geladenBytes() > 0) return "pausiert";
        return JSONObject.NULL;
    }

    private boolean rueckfallFrisch() {
        File m = new File(env.basis(), ".appupdate-rueckfall");
        return m.exists() && env.jetzt() - m.lastModified() < 24 * 3_600_000L;
    }

    int rueckfallVc() {
        File m = new File(env.basis(), ".appupdate-rueckfall");
        if (!m.exists()) return -1;
        try {
            String s = new String(Files.readAllBytes(m.toPath()), StandardCharsets.UTF_8).trim();
            int sp = s.indexOf(' ');
            return Integer.parseInt(sp > 0 ? s.substring(0, sp) : s);
        } catch (Exception e) {
            return -1;
        }
    }

    private String kanal() {
        return UpdateRegel.kanal(env.config("kanal"));
    }

    private boolean automatisch() {
        return UpdateRegel.automatisch(env.config("modus"));
    }

    /** {@code update.pruefen}: nicht gesetzt (Bestand) = an; die Ersteinrichtung fragt ohne Vorauswahl. */
    private boolean pruefenAn() {
        return UpdateRegel.an(env.config("pruefen")) && !env.updatesUnbeantwortet();
    }

    // ------------------------------------------------------------------ Speicher

    private File standDatei() {
        return new File(env.basis(), "appupdate_stand.json");
    }

    private void laden() {
        try {
            File f = standDatei();
            if (!f.exists()) return;
            JSONObject j = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            letztePruefung = j.optLong("letztePruefung", 0);
            ergebnis = j.optString("ergebnis", "nie");
            fehler = j.isNull("fehler") ? null : j.optString("fehler", null);
            quelleKennung = j.isNull("quelle") ? null : j.optString("quelle", null);
            berichtSchluessel = j.isNull("bericht") ? null : j.optString("bericht", null);
            JSONObject v = j.optJSONObject("verfuegbar");
            String apkUrl = j.isNull("apkUrl") ? null : j.optString("apkUrl", null);
            // ohne gültige Adresse (Stand vor 0.1.15) gilt der Fund nicht; die nächste Prüfung holt ihn neu
            if (v != null && UpdateQuelle.erlaubteAdresse(apkUrl)) {
                verfuegbar = Freigabe.lies(v.toString()).mitApkUrl(apkUrl);
            }
            groesseCache = j.optLong("groesse", -1);
            gemeldetVc = j.optInt("gemeldetVc", 0);
            hinweis.vc = j.optInt("hinweisVc", 0);
            hinweis.anzahl = j.optInt("hinweisAnzahl", 0);
            hinweis.zuletzt = j.optLong("hinweisZuletzt", 0);
            hinweis.vorgemerktVc = j.optInt("vorgemerktVc", 0);
            hinweis.vorgemerktAm = j.optLong("vorgemerktAm", 0);
            hinweis.versuchAb = j.optLong("vorgemerktVersuchAb", 0);
            hinweis.fehlversuche = j.optInt("vorgemerktFehlversuche", 0);
            hinweis.mobilVc = j.optInt("mobilVc", 0);
            hinweis.mobilAm = j.optLong("mobilAm", 0);
        } catch (Exception e) {
            verfuegbar = null;
        }
    }

    private void speichern() {
        try {
            JSONObject j = new JSONObject();
            j.put("letztePruefung", letztePruefung).put("ergebnis", ergebnis)
                    .put("fehler", fehler == null ? JSONObject.NULL : fehler)
                    .put("verfuegbar", verfuegbar == null ? JSONObject.NULL : verfuegbar.alsJson())
                    .put("groesse", groesseCache).put("gemeldetVc", gemeldetVc)
                    .put("apkUrl", verfuegbar == null || verfuegbar.apkUrl() == null ? JSONObject.NULL
                            : verfuegbar.apkUrl())
                    .put("quelle", quelleKennung == null ? JSONObject.NULL : quelleKennung)
                    .put("bericht", berichtSchluessel == null ? JSONObject.NULL : berichtSchluessel)
                    .put("hinweisVc", hinweis.vc).put("hinweisAnzahl", hinweis.anzahl)
                    .put("hinweisZuletzt", hinweis.zuletzt).put("vorgemerktVc", hinweis.vorgemerktVc)
                    .put("vorgemerktAm", hinweis.vorgemerktAm).put("vorgemerktVersuchAb", hinweis.versuchAb)
                    .put("vorgemerktFehlversuche", hinweis.fehlversuche)
                    .put("mobilVc", hinweis.mobilVc).put("mobilAm", hinweis.mobilAm);
            File f = standDatei();
            File tmp = new File(f.getPath() + ".tmp");
            Files.write(tmp.toPath(), j.toString().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(f)) {
                f.delete();
                tmp.renameTo(f);
            }
        } catch (Exception e) {
            logger.warn("Stand speichern: " + e.getMessage());
        }
    }

    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(f.toPath())) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
        }
        return ApkZertifikat.hex(md.digest());
    }

    // ------------------------------------------------------------------ echte Umgebung

    /** OkHttp mit derselben Proxy-Kette wie der Rest (Proxy zuerst, sonst direkt). */
    static final class OkNetz implements Netz {
        private volatile String token;

        @Override
        public void zugang(String token) {
            this.token = token;
        }

        private okhttp3.OkHttpClient client(long readSec) {
            return new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(readSec, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .proxySelector(new java.net.ProxySelector() {
                        @Override
                        public java.util.List<java.net.Proxy> select(java.net.URI uri) {
                            return at.greenchili.surfmate.mqtt.ProxyHelper.proxyRouteChain(
                                    at.greenchili.surfmate.mqtt.ProxyHelper.getHttpProxy());
                        }

                        @Override
                        public void connectFailed(java.net.URI uri, java.net.SocketAddress a, java.io.IOException e) {
                            at.greenchili.surfmate.mqtt.ProxyHelper.invalidateCache();
                        }
                    })
                    .build();
        }

        private okhttp3.Request anfrage(String url) {
            return anfrageBau(url).build();
        }

        /**
         * Token nur an https://api.github.com (OkHttp lässt Authorization bei einer Umleitung auf einen anderen Host
         * weg, der Datei-Speicher bekommt ihn also nie). Asset-Adressen der API liefern mit octet-stream die Datei.
         */
        private okhttp3.Request.Builder anfrageBau(String url) {
            okhttp3.HttpUrl u = okhttp3.HttpUrl.parse(url);
            boolean api = u != null && "https".equals(u.scheme()) && "api.github.com".equals(u.host());
            okhttp3.Request.Builder b = new okhttp3.Request.Builder().url(url)
                    .header("User-Agent", "Panoptes-Updater")
                    .header("Accept", api && url.contains("/releases/assets/") ? "application/octet-stream"
                            : api ? "application/vnd.github+json" : "*/*");
            String t = token;
            if (api && t != null) b.header("Authorization", "Bearer " + t);
            return b;
        }

        @Override
        public String text(String url) throws Exception {
            try (okhttp3.Response r = client(30).newCall(anfrage(url)).execute()) {
                if (!r.isSuccessful() || r.body() == null) throw new java.io.IOException("HTTP " + r.code());
                byte[] b = r.body().bytes();
                if (b.length > 256 * 1024) throw new java.io.IOException("zu groß");
                return new String(b, StandardCharsets.UTF_8);
            }
        }

        @Override
        public void laden(String url, File ziel, long maxBytes, java.util.function.IntConsumer prozent) throws Exception {
            try (okhttp3.Response r = client(120).newCall(anfrage(url)).execute()) {
                if (!r.isSuccessful() || r.body() == null) throw new java.io.IOException("HTTP " + r.code());
                long gesamt = r.body().contentLength();
                if (gesamt > maxBytes) throw new java.io.IOException("zu groß");
                try (InputStream in = r.body().byteStream(); OutputStream o = new FileOutputStream(ziel)) {
                    byte[] b = new byte[65536];
                    long n = 0;
                    int letzt = -1, k;
                    while ((k = in.read(b)) > 0) {
                        n += k;
                        if (n > maxBytes) throw new java.io.IOException("zu groß");
                        o.write(b, 0, k);
                        if (gesamt > 0) {
                            int p = (int) (n * 100 / gesamt);
                            if (p != letzt) { letzt = p; prozent.accept(p); }
                        }
                    }
                }
            }
        }

        @Override
        public long ladenStueck(String url, File teil, long ab, long laenge, long maxBytes) throws Exception {
            okhttp3.Request.Builder b = anfrageBau(url)
                    .header("Range", "bytes=" + ab + "-" + (ab + laenge - 1));
            try (okhttp3.Response r = client(120).newCall(b.build()).execute()) {
                if (r.code() == 416) {
                    // Range hinter dem Ende: Teildatei ist vollständig oder ungültig – Gesamtgröße aus Content-Range
                    long g = gesamtAus(r.header("Content-Range"));
                    if (g < 0) throw new java.io.IOException("HTTP 416");
                    return g;
                }
                if (!r.isSuccessful() || r.body() == null) throw new java.io.IOException("HTTP " + r.code());
                boolean teilweise = r.code() == 206;
                long gesamt = teilweise ? gesamtAus(r.header("Content-Range")) : r.body().contentLength();
                if (gesamt > maxBytes) throw new java.io.IOException("zu groß");
                if (teilweise && startAus(r.header("Content-Range")) != ab) {
                    throw new java.io.IOException("Content-Range passt nicht");
                }
                try (InputStream in = r.body().byteStream();
                     OutputStream o = new FileOutputStream(teil, teilweise)) {   // 200 = von vorn
                    byte[] buf = new byte[65536];
                    long n = teilweise ? ab : 0;
                    int k;
                    while ((k = in.read(buf)) > 0) {
                        n += k;
                        if (n > maxBytes) throw new java.io.IOException("zu groß");
                        o.write(buf, 0, k);
                    }
                }
                return gesamt;
            }
        }

        /** "bytes 0-99/1234" → 1234; −1 wenn unbekannt. */
        static long gesamtAus(String contentRange) {
            if (contentRange == null) return -1;
            int s = contentRange.lastIndexOf('/');
            try {
                return s < 0 ? -1 : Long.parseLong(contentRange.substring(s + 1).trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        static long startAus(String contentRange) {
            if (contentRange == null) return -1;
            try {
                String t = contentRange.trim();
                int sp = t.indexOf(' ');
                int st = t.indexOf('-');
                return Long.parseLong(t.substring(sp + 1, st).trim());
            } catch (Exception e) {
                return -1;
            }
        }

        @Override
        public long groesse(String url) {
            if (url == null) return -1;
            try (okhttp3.Response r = client(30).newCall(anfrageBau(url).head().build()).execute()) {
                return r.isSuccessful() ? r.body() == null ? -1 : Long.parseLong(
                        r.header("Content-Length", "-1")) : -1;
            } catch (Exception e) {
                return -1;
            }
        }
    }

    static final class AndroidUmgebung implements Umgebung {
        @Override public int installierterVc() { return at.greenchili.surfmate.BuildConfig.VERSION_CODE; }
        @Override public String installierteVersion() { return at.greenchili.surfmate.BuildConfig.VERSION_NAME; }

        @Override
        public Object[] autoAus() {
            at.greenchili.surfmate.byd.routing.CloudPowerGate.Decision d =
                    at.greenchili.surfmate.byd.routing.CloudPowerGate.evaluateLive("appupdate");
            return new Object[]{d.allowed, d.reasonKey};
        }

        @Override
        public double kmh() {
            try {
                at.greenchili.surfmate.byd.BydVehicleData d =
                        at.greenchili.surfmate.byd.BydDataCollector.getInstance().getData();
                // 0.1.28.1: alter Restwert der letzten Fahrt zählt bei ACC aus + P nicht als „fährt“ (byd.Stillstand)
                double v = at.greenchili.surfmate.byd.Stillstand.tempoJetzt(d);
                return Double.isNaN(v) ? Double.NaN : Math.abs(v);
            } catch (Throwable t) {
                return Double.NaN;
            }
        }

        @Override
        public Boolean accAus() {
            try {
                if (!at.greenchili.surfmate.monitor.AccMonitor.isAccStateAuthoritative()) return null;
                return !at.greenchili.surfmate.monitor.AccMonitor.isAccOn();
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public String config(String key) {
            try {
                JSONObject u = at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig().optJSONObject("update");
                return u == null ? null : u.optString(key, null);
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public boolean updatesUnbeantwortet() {
            try {
                return at.greenchili.surfmate.server.EinrichtungApiHandler.updatesUnbeantwortet(
                        at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig());
            } catch (Throwable t) {
                return false;
            }
        }

        @Override public File basis() { return new File(at.greenchili.surfmate.util.DaemonStorage.rebase(NEU)); }
        @Override public File arbeitsordner() { return new File(at.greenchili.surfmate.util.DaemonStorage.rebase(DD)); }

        @Override
        public String installiertesZertifikat() {
            try {
                // 0.1.19: im Daemon gibt es keinen Context – das installierte APK steht im CLASSPATH
                File apk = installiertesApk();
                return apk == null ? null : ApkZertifikat.sha256(apk);
            } catch (Throwable t) {
                return null;
            }
        }

        /** Installiertes APK: CLASSPATH des Daemons, sonst (App-Prozess) ApplicationInfo. */
        static File installiertesApk() {
            File f = ApkQuelle.installiertesApk(System.getenv("CLASSPATH"));
            if (f != null) return f;
            try {
                android.content.Context c = at.greenchili.surfmate.daemon.DaemonBootstrap.getContext();
                String pfad = c == null ? null : c.getApplicationInfo().sourceDir;
                return pfad == null ? null : new File(pfad);
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public String skript(File apk) throws Exception {
            // 0.1.19 (Befund 27.10.: „kein Context“): direkt aus dem geprüften neuen APK, sonst aus dem installierten
            try {
                return ApkQuelle.text(apk, ApkQuelle.SKRIPT_EINTRAG);
            } catch (java.io.IOException e) {
                logger.warn("Skript aus neuem APK: " + e.getMessage() + " – nehme das installierte");
            }
            File inst = installiertesApk();
            if (inst == null) throw new java.io.IOException("kein installiertes APK im CLASSPATH");
            return ApkQuelle.text(inst, ApkQuelle.SKRIPT_EINTRAG);
        }

        @Override
        public void starteAbgekoppelt(File skript, File apk, String schluessel, int vc, String tag) throws Exception {
            // CLASSPATH muss weg: update-on-car.sh beendet jeden Prozess mit unserem APK im CLASSPATH – sonst sich selbst.
            // „( … &)“ hängt das Skript an init (setsid fehlt auf manchen BYD-toybox-Ständen).
            String dd = skript.getParentFile().getAbsolutePath();
            String cmd = "(unset CLASSPATH; LOCK_KEY='" + schluessel + "' AUTO_OK=1 EXPECT_VC=" + vc
                    + " DD='" + dd + "' nohup sh " + dd + "/update-on-car.sh '" + apk.getAbsolutePath()
                    + "' appupdate '" + tag + "' </dev/null >/dev/null 2>&1 &)";
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            pb.environment().remove("CLASSPATH");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getOutputStream().close();
            if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroy();
        }

        @Override public long jetzt() { return System.currentTimeMillis(); }

        @Override
        public boolean setzeConfig(java.util.Map<String, Object> werte) {
            return at.greenchili.surfmate.config.UnifiedConfigManager.updateValues("update", werte);
        }

        @Override
        public String netz() {
            try {
                at.greenchili.surfmate.monitor.NetworkMonitor.refresh();
                return UpdateRegel.netz(at.greenchili.surfmate.monitor.NetworkMonitor.getNetworkType());
            } catch (Throwable t) {
                return UpdateRegel.NETZ_UNBEKANNT;
            }
        }

        @Override
        public void melde(Freigabe f, long groesseBytes) {
            String g = UpdateRegel.groesseText(groesseBytes);
            String text = g.isEmpty()
                    ? at.greenchili.surfmate.telegram.TelegramMessages.get("update.available", f.versionName)
                    : at.greenchili.surfmate.telegram.TelegramMessages.get("update.available_size", f.versionName, g);
            at.greenchili.surfmate.telegram.TelegramNotifier.sendMessage(text, "UPDATE");
        }

        @Override
        public String fahrsperre() {
            return at.greenchili.surfmate.byd.routing.DrivingSafetyGuard.getMovementBlockReason();
        }

        @Override
        public boolean waechterEreignis() {
            try {
                return at.greenchili.surfmate.surveillance.ScreenDeterrent.getInstance().laeuft()
                        || at.greenchili.surfmate.recording.EreignisMonitor.sichertGerade();
            } catch (Throwable t) {
                return true;
            }
        }

        @Override
        public boolean aufnahmeLaeuft() {
            return at.greenchili.surfmate.surveillance.ScreenDeterrent.aufnahmeLaeuft();
        }

        @Override
        public Boolean gangP() {
            try {
                String g = at.greenchili.surfmate.monitor.GearMonitor.gearToString(
                        at.greenchili.surfmate.byd.BydDataCollector.getInstance().getData().gearMode);
                if ("P".equals(g)) return Boolean.TRUE;
                return g.startsWith("UNKNOWN") ? null : Boolean.FALSE;
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public boolean zeigeHinweis(String griff, Freigabe f, String notizen, String zusatz) {
            return at.greenchili.surfmate.byd.MessageOverlayController.showUpdateHinweis(griff, f.versionName,
                    UpdateHinweis.titel(f.versionName),
                    zusatz.isEmpty() ? UpdateHinweis.frage() : UpdateHinweis.frage() + "\n\n" + zusatz, notizen,
                    UpdateHinweis.knopfInstallieren(), UpdateHinweis.knopfSpaeter(), UpdateHinweis.knopfWasNeu(),
                    UpdateHinweis.knopfSchliessen(), UpdateHinweis.knopfZuruecknehmen(), UpdateHinweis.textFehler(),
                    UpdateHinweis.SICHTBAR_SEK);
        }

        @Override
        public void nimmHinweisWeg(String griff) {
            at.greenchili.surfmate.byd.MessageOverlayController.dismissKopplung(griff);
        }
    }
}
