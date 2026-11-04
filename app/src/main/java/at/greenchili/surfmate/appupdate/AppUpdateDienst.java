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
 *
 * <p>0.1.38.8 (04.11.): kleine Updates (nur die vierte Stelle neu, {@link UpdateHinweis#klein}) kommen im Modus
 * „korrekturen“ und „automatisch“ ohne Meldung und ohne Rückfrage, sobald das Auto ≥ 10 min aus ist; danach keine
 * Telegram-Meldung, nur der Eintrag unter „Was ist neu“. Große Updates meldet Panoptes vorher mit der Sammelliste seit
 * der letzten großen Version. Einmalig ({@link #KLEIN_MARKE}) stellt der Updater „melden“ auf „korrekturen“ um und sagt
 * das einmal (Telegram, Handy, Display).
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

        /** 0.1.37 Stufe 4: Launcher-APK am Gerät (Standard: nicht installiert). */
        default LauncherPaket.Stand launcherStand() { return LauncherPaket.ausListe(""); }
        /** {@code pm install -r} der geprüften Launcher-APK; null = ok. */
        default String launcherInstallieren(File apk) { return "nicht_verfuegbar"; }
        /** 0.1.38.8: nach dem Launcher-Update den gewählten Startbildschirm prüfen/herstellen ({@link LauncherHome#nachUpdate}). */
        default String launcherHomeNachUpdate() { return null; }
        /** 0.1.38.8: Startbildschirm ließ sich nach dem Update nicht wiederherstellen – eine Meldung. */
        default void meldeLauncherHome(String grund) { }

        /** 0.1.38.1: Selbstprüfung der Startskripte ({@link UpdateMeldung#SELBST_GRUEN} …). */
        default String selbstpruefung() { return UpdateMeldung.SELBST_GRUEN; }
        /** 0.1.38.1: Update-Meldung senden (Telegram Kategorie UPDATE + Handy-Push {@code system.updates}). */
        default void meldeUpdate(UpdateMeldung.Meldung m) { }

        /** 0.1.38.8: einmalige Umstellung „klein von selbst“ melden (Telegram + Handy-Push). */
        default void meldeUmstellung(String titel, String text) { }
        /** 0.1.38.8: dieselbe Nachricht als Karte am Display (nur im ruhigen Stand); true = steht wirklich da. */
        default boolean zeigeInfo(String titel, String text) { return false; }
        /** 0.1.38.8: Konfiguration (Wurzel) für die einmalige Wächter-Frage; null = nicht lesbar, dann wird nicht gefragt. */
        default JSONObject konfig() { return null; }
        /** 0.1.38.8: die einmalige Frage „Wächter einschalten?“ über Telegram, Handy und Display senden. */
        default void frageWaechterStandard() { }
    }

    /**
     * 0.1.38.8 Wächter Standard an, Phase 3: beim ersten Start auf einer neuen Version EINMAL fragen, ob der Wächter an soll
     * (Bestand, nie still einschalten). Ein Marker je versionCode; Entscheidung in {@code WaechterStandardHinweis}.
     */
    private long waechterHinweisZuletzt = -1;

    void waechterHinweisTakt(long now) {
        if (waechterHinweisZuletzt >= 0 && now - waechterHinweisZuletzt < MELDUNG_TAKT_MS) return;
        waechterHinweisZuletzt = now;
        int vc = env.installierterVc();
        int marker = at.greenchili.surfmate.surveillance.WaechterStandardHinweis.ladeMarker(env.basis());
        if (vc <= 0 || marker >= vc) return;   // schon erledigt: die Konfiguration wird gar nicht erst gelesen
        String was = at.greenchili.surfmate.surveillance.WaechterStandardHinweis.entscheide(env.konfig(), vc, marker);
        if (at.greenchili.surfmate.surveillance.WaechterStandardHinweis.NICHTS.equals(was)) return;
        // Marker zuerst: auch wenn der Versand scheitert oder niemand tippt, wird nicht erneut gefragt
        at.greenchili.surfmate.surveillance.WaechterStandardHinweis.speichereMarker(env.basis(), vc);
        if (at.greenchili.surfmate.surveillance.WaechterStandardHinweis.FRAGEN.equals(was)) {
            logger.info("Wächter-Frage (Standard an) gesendet, versionCode " + vc);
            try {
                env.frageWaechterStandard();
            } catch (Throwable t) {
                logger.warn("Wächter-Frage: " + t.getMessage());
            }
        }
    }

    /** 0.1.38.1: Update-Meldungen höchstens alle 5 min prüfen (der Launcher-Stand kostet einen Shell-Aufruf). */
    static final long MELDUNG_TAKT_MS = 5 * 60_000L;
    private long meldungZuletzt = -1;

    /** Ein Durchgang der Update-Meldungen ({@link UpdateMeldung}); im Takt und im Test aufrufbar. */
    void updateMeldungTakt(long now) {
        if (meldungZuletzt >= 0 && now - meldungZuletzt < MELDUNG_TAKT_MS) return;
        meldungZuletzt = now;
        LauncherPaket.Stand st = env.launcherStand();
        java.util.List<UpdateMeldung.Meldung> raus;
        synchronized (lock) {
            UpdateMeldung m = UpdateMeldung.lade(env.basis());
            boolean beobachtet = "beobachtet".equals(aktuellePhase(lage()));
            int rueck = rueckfallFrisch() ? rueckfallVc() : -1;
            raus = m.pruefe(env.installierterVc(), env.installierteVersion(), gemeldetVc, env.selbstpruefung(),
                    beobachtet, st.versionCode, verfuegbar == null ? null : verfuegbar.launcher, rueck, stillVc);
            m.speichern(env.basis());
            if (stillVc > 0 && m.panoptesVc >= stillVc) {
                // 0.1.38.8: kleines Update kam still – keine Meldung, es steht nur unter „Was ist neu“
                logger.info("Update-Meldung: vC " + stillVc + " kam still, nichts gesendet");
                stillVc = 0;
                speichern();
            }
        }
        for (UpdateMeldung.Meldung me : raus) {
            logger.info("Update-Meldung: " + me);
            try {
                env.meldeUpdate(me);
            } catch (Throwable t) {
                logger.warn("Update-Meldung: " + t.getMessage());
            }
        }
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
    /** 0.1.38.8: dieser versionCode wird still installiert: danach kein „installiert und läuft“ (0 = keiner). */
    private int stillVc;
    /** 0.1.38.8: Nachricht zur Umstellung steht noch aus (Telegram/Handy bzw. Karte am Display). */
    private boolean umstellungMelden;
    private boolean umstellungKarte;
    private long umstellungRuhigSeit = -1;

    AppUpdateDienst(Netz netz, Umgebung env) {
        this.netz = netz;
        this.env = env;
        boolean bestand = standDatei().exists();
        laden();
        migriereQuelle(bestand);
        umstellenAufKlein(bestand);
    }

    /** Marke der einmaligen Umstellung „melden“ → „korrekturen“ (0.1.38.8); einmal gesetzt, nie wieder umgestellt. */
    static final String KLEIN_MARKE = ".appupdate-klein";

    /**
     * 0.1.38.8 (04.11.: „können wir das überrollen? dass es automatisch auch bei allen anderen ist?“): einmal je
     * Gerät „Nur melden“ (auch ohne Wert, der Bestand) auf „korrekturen“, mit einer Nachricht. Wer danach wieder
     * „melden“ wählt, behält es (die Marke bleibt). „automatisch“ und „korrekturen“ bleiben, ebenso Geräte mit
     * {@code update.pruefen} aus und neue Einrichtungen (die fragt die Einrichtung). Scheitert das Speichern, versucht es
     * der nächste Start erneut.
     */
    private void umstellenAufKlein(boolean bestand) {
        File marke = new File(env.basis(), KLEIN_MARKE);
        if (marke.exists()) return;
        String modus = env.config("modus");
        boolean umstellen = UpdateRegel.umstellenAufKlein(bestand, modus, env.config("pruefen"),
                env.updatesUnbeantwortet());
        if (umstellen) {
            java.util.Map<String, Object> m = new java.util.HashMap<>();
            m.put("modus", UpdateRegel.MODUS_KORREKTUREN);
            boolean ok;
            try {
                ok = env.setzeConfig(m);
            } catch (Throwable t) {
                ok = false;
            }
            if (!ok) {
                logger.warn("Update-Modus: Umstellung auf korrekturen nicht gespeichert, der nächste Start versucht es erneut");
                return;
            }
            umstellungMelden = true;
            umstellungKarte = true;
            speichern();
            logger.info("Update-Modus: " + (modus == null ? "(ohne Wert)" : modus) + " → korrekturen (einmalig)");
        }
        try {
            //noinspection ResultOfMethodCallIgnored
            env.basis().mkdirs();
            Files.write(marke.toPath(), ((umstellen ? "umgestellt " : "unveraendert ") + (modus == null ? "-" : modus)
                    + " " + env.jetzt() + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            logger.debug("Update-Modus: Marke: " + e.getMessage());
        }
    }

    /** Telegram + Handy einmal, sobald der Takt läuft (der Bot ist dann sicher da). */
    private void umstellungMeldenTakt() {
        synchronized (lock) {
            if (!umstellungMelden) return;
            umstellungMelden = false;
            speichern();
        }
        try {
            env.meldeUmstellung(UpdateHinweis.umstellungTitel(), UpdateHinweis.umstellungText());
        } catch (Throwable t) {
            logger.warn("Umstellung melden: " + t.getMessage());
        }
    }

    /** Karte am Display einmal, nur im ruhigen Stand wie der Update-Hinweis, nie über einer anderen Update-Karte. */
    private void umstellungKarteTakt(long now) {
        synchronized (lock) {
            if (!umstellungKarte || hinweisGriff != null) return;
        }
        boolean ruhig = UpdateHinweis.ruhig(env.accAus(), env.fahrsperre(), env.kmh(), env.waechterEreignis());
        synchronized (lock) {
            umstellungRuhigSeit = !ruhig ? -1 : umstellungRuhigSeit < 0 ? now : umstellungRuhigSeit;
            if (umstellungRuhigSeit < 0 || now - umstellungRuhigSeit < UpdateHinweis.RUHE_MS || now < hinweisPauseBis) return;
        }
        boolean gezeigt = false;
        try {
            gezeigt = env.zeigeInfo(UpdateHinweis.umstellungTitel(), UpdateHinweis.umstellungText());
        } catch (Throwable t) {
            logger.warn("Umstellung Karte: " + t.getMessage());
        }
        synchronized (lock) {
            if (!gezeigt) {
                hinweisPauseBis = now + UpdateHinweis.PAUSE_NACH_FEHLVERSUCH_MS;
                return;
            }
            umstellungKarte = false;
            speichern();
        }
        logger.info("Umstellung „kleine Updates von selbst“ am Display gezeigt");
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
                o.put("modus", automatisch() ? "automatisch" : korrekturen() ? UpdateRegel.MODUS_KORREKTUREN : "melden");
                o.put("pruefen", pruefenAn());
                o.put("mobilfunk", !l.nurWlan);
                o.put("netz", l.netz);
                o.put("aktuell", new JSONObject().put("version", env.installierteVersion())
                        .put("versionCode", env.installierterVc())
                        .put("label", at.greenchili.surfmate.BuildConfig.RELEASE_LABEL)
                        .put("kanal", at.greenchili.surfmate.BuildConfig.RELEASE_CHANNEL));
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
                    // 0.1.38.5: Art und Kurzfassung direkt in der Meldung (Handy, Cockpit, System-Seite)
                    final Freigabe vf = verfuegbar;
                    final String inst = env.installierteVersion();
                    String art = UpdateHinweis.art(vf, inst);
                    v.put("art", art);
                    v.put("artText", at.greenchili.surfmate.server.Messages.withLocale(sprache,
                            () -> UpdateHinweis.artText(art)));
                    // 0.1.38.8: klein = kommt von selbst; groß = Sammelliste seit der letzten großen Version (≤ 8)
                    v.put("klein", UpdateHinweis.klein(vf, inst));
                    org.json.JSONArray zus = UpdateHinweis.zusammenfassung(vf, inst, sprache);
                    v.put("zusammenfassung", zus);
                    v.put("kurz", UpdateHinweis.kurz(zus));
                    v.put("zurueckgerollt", verfuegbar.versionCode == l.rueckfallVc);
                    o.put("verfuegbar", v);
                } else {
                    o.put("verfuegbar", JSONObject.NULL);
                }
                // 0.1.38.5: wie ein Tipp (Knopf, Handy, Telegram), also auch bei eingeschaltetem Auto in P
                String nicht = nichtWeilGetippt(l);
                o.put("installierbar", nicht == null);
                // 0.1.32.1: ohne WLAN fragen wie die Karte (nur neue Einrichtungen, Größe, nur diese Version)
                o.put("mobilFrage", UpdateRegel.WARTET_WLAN.equals(nicht) && mobilMerker());
                o.put("nichtWeil", nicht == null ? JSONObject.NULL : nicht);
                o.put("phase", aktuellePhase(l));
                o.put("prozent", prozent == null ? JSONObject.NULL : prozent);
                long geladen = geladenBytes();
                o.put("geladenBytes", geladen > 0 ? geladen : JSONObject.NULL);
                long ab = UpdateRegel.automatischAb(l, automatischFuer(verfuegbar), pruefenAn(), ausSeit);
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
            String nicht = nichtWeilGetippt(l);
            if (nicht != null) {
                if (einmal) UpdateHinweis.mobilErlauben(hinweis, 0, 0);
                return nicht;
            }
            if (verfuegbar.versionCode != versionCode) return "anderer_stand";
            if (einmal) speichern();
            installiertGerade = true;
            laufGetippt = true;
        }
        final Freigabe f = verfuegbar;
        Thread t = new Thread(() -> {
            try {
                ausfuehren(f);
            } finally {
                synchronized (lock) {
                    installiertGerade = false;
                    laufGetippt = false;
                }
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
        // 0.1.32: Der Tipp ist die Einwilligung für genau diesen einen Download (die Karte nannte die Größe)
        return installierenOderVormerken(fern, version, true, UpdateHinweis.geraetName(), sprache);
    }

    /**
     * 0.1.38.2 derselbe Weg für Telegram und Geräte mit Recht „update“ ({@code POST /api/appupdate/fern}): sicher →
     * sofort, fehlt nur noch „Auto aus“ (oder WLAN) → vorgemerkt. {@code mobil} = Einwilligung für genau diesen
     * Download (das Gerät hat vorher die Größe genannt); ohne sie und ohne WLAN kommt {@code mobilFrage} zurück.
     */
    public JSONObject installierenOderVormerken(FernUpdate fern, String version, boolean mobil, String geraet,
                                                String sprache) throws Exception {
        synchronized (lock) {
            if (mobil && mobilMerker() && verfuegbar != null && verfuegbar.versionName.equals(version)) {
                UpdateHinweis.mobilErlauben(hinweis, verfuegbar.versionCode, env.jetzt());
                speichern();
            }
        }
        JSONObject r = fern.starten(version, true, mobil, geraet, sprache);
        if (r.optBoolean("ok", false)) return r.put("vorgemerkt", false);
        if (r.optBoolean("mobilFrage", false)) return r.put("vorgemerkt", false);   // erst fragen, nie still vormerken
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
        umstellungKarteTakt(now);
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
            l.automatisch = automatischFuer(verfuegbar);   // 0.1.38.6: Korrektur kommt lautlos, keine Karte
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
            gezeigt = env.zeigeHinweis(neuerGriff, f, UpdateHinweis.notizenText(UpdateHinweis.zusammenfassung(f,
                    env.installierteVersion(), at.greenchili.surfmate.server.LocaleManager.get())), zusatz);
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
        try {
            updateMeldungTakt(now);
        } catch (Throwable t) {
            logger.warn("Update-Meldung: " + t.getMessage());
        }
        umstellungMeldenTakt();
        try {
            waechterHinweisTakt(now);
        } catch (Throwable t) {
            logger.warn("Wächter-Frage: " + t.getMessage());
        }
        // update.pruefen aus (Ersteinrichtung „Nein“): kein Abruf bei GitHub im Hintergrund, nichts von selbst
        if (pruefenAn() && UpdateRegel.pruefenFaellig(now, letztePruefung, fehler != null)) pruefen(false);
        Freigabe f;
        boolean alsVorgemerkt;
        boolean still;
        synchronized (lock) {
            UpdateRegel.Lage l = lage();
            boolean frei = nichtWeil(l) == null;
            if (!frei) ausSeit = -1;
            else if (ausSeit < 0) ausSeit = now;
            alsVorgemerkt = vorgemerkt() && !automatischFuer(verfuegbar);
            long ab = alsVorgemerkt
                    ? UpdateHinweis.vorgemerktAb(l, hinweis, kanal(), vorgemerktFrei(), now, ausSeit)
                    : UpdateRegel.automatischAb(l, automatischFuer(verfuegbar), pruefenAn(), ausSeit);
            if (ab < 0 || now < ab || installiertGerade) return;
            installiertGerade = true;
            laufVorgemerkt = alsVorgemerkt;
            laufGetippt = false;
            laufGescheitert = false;
            f = verfuegbar;
            // 0.1.38.8: kleines Update von selbst: danach keine Meldung (Marke übersteht den Neustart des Daemons)
            still = !alsVorgemerkt && lautlos(f);
            if (still) {
                stillVc = f.versionCode;
                speichern();
            }
        }
        logger.info("automatisch: installiere " + f.versionName + " (vC " + f.versionCode + ")"
                + (still ? " still" : ""));
        boolean gestartet = false;
        try {
            gestartet = ausfuehren(f);
        } finally {
            synchronized (lock) {
                installiertGerade = false;
                if (still && !gestartet) {
                    stillVc = 0;   // nicht angelaufen: ein späterer Tipp auf dieselbe Version meldet wieder
                    speichern();
                }
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
    /** 0.1.38.5: Dieser Lauf kommt aus einem Tipp: Bis zum Start gilt {@link #nichtWeilGetippt} statt „Auto aus“. */
    private boolean laufGetippt;

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
        long gl = -1;
        if (neu != null && neu.launcher != null && neu.launcher.apkUrl() != null) gl = netz.groesse(neu.launcher.apkUrl());
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
                launcherGroesse = gl;
                if (neu.versionCode > env.installierterVc() && neu.versionCode > gemeldetVc) {
                    gemeldetVc = neu.versionCode;   // je Version nur einmal melden
                    melden = true;
                    // 0.1.38.8 (Doppelmeldung 04.11. 19:17): wer gerade sucht oder installiert, braucht kein
                    // „neue Version ist da … ich frage vorher nach“; gemerkt ist die Version trotzdem
                    String schon = schonDabei(manuell, neu.versionCode);
                    if (schon != null) {
                        melden = false;
                        logger.info("Neue Version " + neu.versionName + " nicht gemeldet: " + schon);
                    }
                }
            } else {
                fehler = err == null ? "nicht_erreichbar" : err;
                if (manuell) ergebnis = "fehler";
                // ein früher gefundenes Update bleibt bekannt (nur nicht frisch bestätigt)
            }
            speichern();
        }
        if (melden && lautlos(neu)) {
            logger.info("Korrektur " + neu.versionName + " kommt lautlos, sobald das Auto aus ist");
        } else if (melden) {
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

    /**
     * Grund, warum „neue Version“ jetzt nicht gemeldet wird, oder null: Suche auf Knopfdruck (Telegram, Handy, Web
     * zeigen das Ergebnis selbst), eigener Lauf, Phase, Deploy-Sperre oder ein Start von außen auf diese Version.
     */
    private String schonDabei(boolean manuell, int vc) {
        if (manuell) return "Suche auf Knopfdruck";
        if (installiertGerade || phase != null) return "Installation läuft";
        if (new File(env.basis(), ".deploy.lock").exists()) return "Deploy-Sperre";
        try {
            File m = new File(env.basis(), FernUpdate.MARKE);
            if (m.isFile()) {
                String t = new String(Files.readAllBytes(m.toPath()), StandardCharsets.UTF_8).trim();
                int sp = t.indexOf(' ');
                if (Integer.parseInt(sp > 0 ? t.substring(0, sp) : t) >= vc) return "Start von außen";
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** Ohne Token: die APK liegt unter der öffentlichen Download-Adresse desselben Releases. */
    private static Freigabe mitApk(UpdateQuelle q, Freigabe f) {
        Freigabe m = f.mitApkUrl(q.download(f.tag, f.apk));
        return m.launcher == null ? m : m.mitLauncherUrl(q.download(f.tag, m.launcher.apk));
    }

    /** Mit Token: version.json und APK als API-Assets der Release-Beschreibung (private Repos). */
    private Freigabe ausRelease(UpdateQuelle q, String releaseJson) throws Exception {
        String vj = UpdateQuelle.asset(releaseJson, "version.json");
        if (vj == null) throw new Freigabe.Fehler("nicht_erreichbar", "Release ohne version.json");
        Freigabe f = Freigabe.lies(netz.text(vj));
        String apk = UpdateQuelle.asset(releaseJson, f.apk);
        if (apk == null) throw new Freigabe.Fehler("nicht_erreichbar", "Release ohne " + f.apk);
        Freigabe m = f.mitApkUrl(apk);
        return m.launcher == null ? m : m.mitLauncherUrl(UpdateQuelle.asset(releaseJson, m.launcher.apk));
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

    /**
     * Laden → prüfen → Sperre → Skript starten. Fehler landen in {@code fehler}, der Daemon läuft weiter. true = das
     * Update-Skript ist angelaufen.
     */
    boolean ausfuehren(Freigabe f) {
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
        return gestartet[0];
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
            // Letzte Prüfung direkt vor dem Start: noch immer aus (nach einem Tipp: noch immer sicher in P), keine Sperre
            synchronized (lock) {
                String nicht = laufNichtWeil(lage());
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
            // 0.1.34 Befehls-Log: Installation läuft oft im eigenen Thread, daher eigener Eintrag (nur Versionsnummer)
            at.greenchili.surfmate.server.BefehlsLog.ereignis("update.installieren.vc" + f.versionCode, "gestartet", null, null);
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
            synchronized (lock) { nicht = laufNichtWeil(lage()); }
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

    // ------------------------------------------------------------------ Launcher (0.1.37 Stufe 4)

    private volatile long launcherGroesse = -1;
    private boolean launcherLaeuft;
    private String launcherPhase;
    private Integer launcherProzent;
    private String launcherFehler;

    private LauncherRegel.Lage launcherLage() {
        LauncherRegel.Lage l = new LauncherRegel.Lage();
        l.verfuegbar = verfuegbar == null ? null : verfuegbar.launcher;
        l.fahrsperre = env.fahrsperre();
        l.gangP = env.gangP();
        l.kmh = env.kmh();
        l.netz = env.netz();
        l.laeuft = launcherLaeuft;
        return l;
    }

    /**
     * {@code GET /api/appupdate/launcher} und Update-Karte im Launcher: installiert (versionCode oder null), verfügbar
     * (Version, Größe), ob jetzt installierbar und warum nicht, ob nach Mobilfunk gefragt werden muss.
     */
    public JSONObject launcherStatus() {
        LauncherPaket.Stand st = env.launcherStand();
        synchronized (lock) {
            JSONObject o = new JSONObject();
            try {
                LauncherRegel.Lage l = launcherLage();
                o.put("paket", LauncherPaket.PAKET);
                o.put("installiert", st.installiert() ? (Object) st.versionCode : JSONObject.NULL);
                // 0.1.38.7: Rückfall-Stufe 2 hat das Paket ausgeschaltet: die Karte zeigt dann „Launcher wieder einschalten“
                boolean aus = false;
                try { aus = st.installiert() && LauncherHome.paketDeaktiviert(LauncherPaket.SHELL); } catch (Throwable ignored) { }
                o.put("deaktiviert", aus);
                Freigabe.Launcher f = l.verfuegbar;
                if (f != null && f.apkUrl() != null) {
                    o.put("verfuegbar", new JSONObject().put("version", f.versionName).put("versionCode", f.versionCode)
                            .put("groesseBytes", launcherGroesse > 0 ? launcherGroesse : JSONObject.NULL));
                } else {
                    o.put("verfuegbar", JSONObject.NULL);
                }
                boolean neuer = LauncherRegel.neuer(f, st.versionCode);
                o.put("neuer", neuer);
                String nicht = LauncherRegel.nichtWeil(l, false);
                o.put("installierbar", nicht == null && neuer);
                o.put("mobilFrage", LauncherRegel.WARTET_WLAN.equals(nicht));
                o.put("nichtWeil", nicht == null ? JSONObject.NULL : nicht);
                o.put("phase", launcherPhase == null ? JSONObject.NULL : launcherPhase);
                o.put("prozent", launcherProzent == null ? JSONObject.NULL : launcherProzent);
                o.put("fehler", launcherFehler == null ? JSONObject.NULL : launcherFehler);
            } catch (Exception ignored) { }
            return o;
        }
    }

    /**
     * Launcher installieren oder aktualisieren, nur auf Tipp. {@code versionCode} muss der bekannten Freigabe
     * entsprechen; {@code mobil} = Einwilligung für genau diesen Download. null = gestartet, sonst Grund.
     */
    public String launcherInstallieren(int versionCode, boolean mobil) {
        final Freigabe.Launcher f;
        synchronized (lock) {
            LauncherRegel.Lage l = launcherLage();
            String nicht = LauncherRegel.nichtWeil(l, mobil);
            if (nicht != null) return nicht;
            if (l.verfuegbar.versionCode != versionCode) return LauncherRegel.ANDERER_STAND;
            launcherLaeuft = true;
            launcherFehler = null;
            f = l.verfuegbar;
        }
        Thread t = new Thread(() -> {
            try {
                launcherAusfuehren(f, mobil);
            } finally {
                synchronized (lock) {
                    launcherLaeuft = false;
                    launcherPhase = null;
                    launcherProzent = null;
                }
            }
        }, "AppUpdate-Launcher");
        t.setDaemon(true);
        t.start();
        return null;
    }

    /** Bekannte Launcher-Freigabe; ohne eine erst nachsehen (Knopf in Panoptes vor der allerersten Prüfung). */
    public Freigabe.Launcher launcherFreigabe(boolean nachsehen) {
        synchronized (lock) {
            if (verfuegbar != null && verfuegbar.launcher != null && verfuegbar.launcher.apkUrl() != null) {
                return verfuegbar.launcher;
            }
            if (!nachsehen || env.jetzt() - letztesSuchen < UpdateRegel.SUCHEN_MIN_ABSTAND_MS) return null;
            letztesSuchen = env.jetzt();
        }
        pruefen(true);
        synchronized (lock) {
            return verfuegbar == null ? null : verfuegbar.launcher;
        }
    }

    File launcherTeil(Freigabe.Launcher f) {
        return new File(env.arbeitsordner(), "neu-launcher-" + f.versionCode + "-" + f.sha256.substring(0, 12)
                + ".apk.part");
    }

    /** Laden (fortsetzbar) → sha256 → Zertifikat wie Panoptes → {@code pm install -r}. */
    void launcherAusfuehren(Freigabe.Launcher f, boolean mobil) {
        File dd = env.arbeitsordner();
        //noinspection ResultOfMethodCallIgnored
        dd.mkdirs();
        File teil = launcherTeil(f);
        if (!UpdateQuelle.erlaubteAdresse(f.apkUrl())) {
            launcherScheitern("nicht_erreichbar", "keine Download-Adresse", null);
            return;
        }
        netz.zugang(quelle().token);
        try {
            // fertig geladene, passende Datei von einem früheren Versuch: nicht noch einmal laden
            boolean schonDa = teil.isFile() && f.sha256.equals(sha256(teil));
            long gesamt = launcherGroesse;
            synchronized (lock) { launcherPhase = "laedt"; launcherProzent = 0; }
            while (!schonDa) {
                String nicht;
                synchronized (lock) {
                    LauncherRegel.Lage l = launcherLage();
                    l.laeuft = false;
                    nicht = LauncherRegel.nichtWeil(l, mobil);
                }
                if (nicht != null) {
                    launcherScheitern(nicht, "Download pausiert bei " + teil.length() + " B", null);
                    return;
                }
                long ab = teil.exists() ? teil.length() : 0;
                if (gesamt > 0 && ab >= gesamt) break;
                long g = netz.ladenStueck(f.apkUrl(), teil, ab, STUECK_BYTES, MAX_APK_BYTES);
                if (g > MAX_APK_BYTES) throw new java.io.IOException("zu groß");
                if (g > 0) gesamt = g;
                long nun = teil.exists() ? teil.length() : 0;
                synchronized (lock) { launcherProzent = prozentVon(nun, gesamt); }
                if (gesamt <= 0) break;
                if (nun == ab) throw new java.io.IOException("keine Daten ab " + ab);
            }
            synchronized (lock) { launcherPhase = "prueft"; }
            String sha = sha256(teil);
            if (!f.sha256.equals(sha)) {
                launcherScheitern("pruefsumme", "sha256 " + sha + " ≠ " + f.sha256, teil);
                return;
            }
            // dieselbe Signatur wie das installierte Panoptes (gleicher Schlüssel, gleiche Lineage)
            String cert = ApkZertifikat.sha256(teil);
            String eigen = env.installiertesZertifikat();
            if (!Freigabe.zulaessig(cert) || eigen == null || !eigen.equals(cert)) {
                launcherScheitern("signatur", "Zertifikat " + cert, teil);
                return;
            }
            synchronized (lock) { launcherPhase = "installiert"; }
            File apk = new File(dd, "neu-launcher-" + f.versionCode + ".apk");
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
            if (!teil.renameTo(apk)) {
                launcherScheitern("nicht_erreichbar", "Umbenennen fehlgeschlagen", teil);
                return;
            }
            at.greenchili.surfmate.server.BefehlsLog.ereignis("update.launcher.vc" + f.versionCode, "gestartet", null, null);
            String grund = env.launcherInstallieren(apk);
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
            if (grund != null) {
                launcherScheitern("installation", grund, null);
                return;
            }
            logger.info("Launcher " + f.versionName + " (vC " + f.versionCode + ") installiert");
            String home = null;
            try {
                home = env.launcherHomeNachUpdate();
            } catch (Throwable t) {
                home = "fehler:" + t.getMessage();
            }
            if (home != null) {
                logger.warn("Launcher-Startbildschirm nach Update: " + home);
                try {
                    env.meldeLauncherHome(home);
                } catch (Throwable ignored) { }
            }
        } catch (Exception e) {
            launcherScheitern("nicht_erreichbar", e.getMessage(), null);
        }
    }

    private void launcherScheitern(String grund, String text, File weg) {
        //noinspection ResultOfMethodCallIgnored
        if (weg != null) weg.delete();
        synchronized (lock) { launcherFehler = grund; }
        berichte("Launcher-Update abgebrochen (" + grund + "): " + text);
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

    /** 0.1.38.5: Regel für einen ausdrücklichen Tipp ({@link UpdateRegel#nichtWeilGetippt}). */
    private String nichtWeilGetippt(UpdateRegel.Lage l) {
        return UpdateRegel.nichtWeilGetippt(l, env.fahrsperre(), env.gangP(), env.waechterEreignis(),
                env.aufnahmeLaeuft());
    }

    /** Regel für den laufenden Download und den Start: nach einem Tipp die des Tipps, sonst „Auto aus“. */
    private String laufNichtWeil(UpdateRegel.Lage l) {
        return laufGetippt ? nichtWeilGetippt(l) : nichtWeil(l);
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

    /**
     * 0.1.38.6 (04.11.: „so viel wie möglich automatisch“): von selbst installieren? Modus „automatisch“ für
     * alles, {@code korrekturen} ab 0.1.38.8 jedes kleine Update ({@link UpdateHinweis#klein}, unabhängig von der Art),
     * {@code melden} und kein Wert (Bestand) nie („Nur melden“). Kanal, Rückfall-Sperre, „Auto aus ≥ 10 min“ und
     * {@code update.pruefen} prüft weiter {@link UpdateRegel#automatischAb}.
     */
    private boolean automatischFuer(Freigabe f) {
        if (automatisch()) return true;
        return korrekturen() && f != null && UpdateHinweis.klein(f, env.installierteVersion());
    }

    private boolean korrekturen() {
        return UpdateRegel.nurKorrekturen(env.config("modus"));
    }

    /**
     * 0.1.38.8: kleines Update, das von selbst kommt (Modus „korrekturen“ oder „automatisch“): keine Meldung „neue
     * Version“ vorher und danach kein „installiert und läuft“ ({@link #stillVc}). Große Updates im Modus „automatisch“
     * werden wie bisher vorher gemeldet.
     */
    private boolean lautlos(Freigabe f) {
        return f != null && UpdateHinweis.klein(f, env.installierteVersion()) && automatischFuer(f) && pruefenAn()
                && "stabil".equals(f.kanal) && f.versionCode != rueckfallVc()
                && env.installierterVc() >= f.minVersion;   // sonst käme sie nie
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
                String lUrl = j.isNull("launcherApkUrl") ? null : j.optString("launcherApkUrl", null);
                verfuegbar = verfuegbar.mitLauncherUrl(UpdateQuelle.erlaubteAdresse(lUrl) ? lUrl : null);
            }
            launcherGroesse = j.optLong("launcherGroesse", -1);
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
            stillVc = j.optInt("stillVc", 0);
            umstellungMelden = j.optBoolean("umstellungMelden", false);
            umstellungKarte = j.optBoolean("umstellungKarte", false);
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
                    .put("mobilVc", hinweis.mobilVc).put("mobilAm", hinweis.mobilAm)
                    .put("launcherApkUrl", verfuegbar == null || verfuegbar.launcher == null
                            || verfuegbar.launcher.apkUrl() == null ? JSONObject.NULL : verfuegbar.launcher.apkUrl())
                    .put("launcherGroesse", launcherGroesse).put("stillVc", stillVc)
                    .put("umstellungMelden", umstellungMelden).put("umstellungKarte", umstellungKarte);
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
                String v = u == null ? null : u.optString(key, null);
                // 0.1.38.6: Klartext bleibt maßgeblich (Rückfall-Updater 0.1.38.5); decrypt() reicht ihn durch, liest ENC:
                return "token".equals(key) ? at.greenchili.surfmate.byd.cloud.crypto.CredentialCipher.decrypt(v) : v;
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
            // 0.1.38.2: mit Knopf „Jetzt installieren“ (Rückfrage im Bot, dann derselbe Weg wie die Karte)
            // 0.1.38.5: Art und Kurzfassung gleich mit (Sprache wie der Bot)
            String sprache = at.greenchili.surfmate.server.LocaleManager.get();
            String neu = at.greenchili.surfmate.server.Messages.withLocale(sprache,
                    () -> UpdateHinweis.wasNeu(f, installierteVersion(), sprache));
            at.greenchili.surfmate.telegram.TelegramNotifier.notifyUpdateNeu("panoptes", f.versionName,
                    UpdateRegel.groesseText(groesseBytes), neu);
            // 0.1.38.8: dieselbe Meldung mit Zusammenfassung aufs Handy (Kategorie system.updates; TelegramSink lässt sie aus)
            try {
                JSONObject data = new JSONObject().put("art", "panoptes_verfuegbar").put("versionCode", f.versionCode)
                        .put("version", f.versionName);
                at.greenchili.surfmate.notifications.NotificationBus.get().publish(
                        new at.greenchili.surfmate.notifications.NotificationEvent("system.updates",
                                at.greenchili.surfmate.notifications.NotificationEvent.Severity.INFO,
                                at.greenchili.surfmate.server.Messages.get("notifications.update_verfuegbar", f.versionName),
                                neu, "update-panoptes_verfuegbar-" + f.versionCode, "/local/system.html", data));
            } catch (Throwable t) {
                logger.warn("Update-Push: " + t.getMessage());
            }
        }

        @Override
        public void meldeUmstellung(String titel, String text) {
            at.greenchili.surfmate.telegram.TelegramNotifier.sendMessage(text, "UPDATE");
            try {
                at.greenchili.surfmate.notifications.NotificationBus.get().publish(
                        new at.greenchili.surfmate.notifications.NotificationEvent("system.updates",
                                at.greenchili.surfmate.notifications.NotificationEvent.Severity.INFO, titel, text,
                                "update-klein-umstellung", "/local/system.html",
                                new JSONObject().put("art", "umstellung_klein")));
            } catch (Throwable t) {
                logger.warn("Umstellung-Push: " + t.getMessage());
            }
        }

        @Override
        public boolean zeigeInfo(String titel, String text) {
            return at.greenchili.surfmate.byd.MessageOverlayController.showDialog(titel, text, "OK", "info", 120);
        }

        @Override
        public String selbstpruefung() {
            return at.greenchili.surfmate.gesundheit.WatchdogSelbstpruefung.befund(
                    at.greenchili.surfmate.launcher.DaemonLauncher.ART_KAMERA);
        }

        /**
         * 0.1.38.1: Telegram (Kategorie UPDATE, Schalter wie „neue Version“) und Handy-Push über die Kategorie
         * {@code system.updates}. Der TelegramSink lässt {@code system.updates} aus, sonst käme Telegram doppelt.
         */
        @Override
        public void meldeUpdate(UpdateMeldung.Meldung m) {
            if (UpdateMeldung.LAUNCHER_VERFUEGBAR.equals(m.art)) {
                // 0.1.38.2: neuer Launcher mit Knopf „Jetzt installieren“ (Rückfrage im Bot, Kategorie UPDATE)
                at.greenchili.surfmate.telegram.TelegramNotifier.notifyUpdateNeu("launcher", m.version, "");
            } else {
                at.greenchili.surfmate.telegram.TelegramNotifier.sendMessage(
                        at.greenchili.surfmate.telegram.TelegramMessages.get(UpdateMeldung.telegramSchluessel(m.art), m.version),
                        "UPDATE");
            }
            try {
                JSONObject data = new JSONObject().put("art", m.art).put("versionCode", m.versionCode)
                        .put("version", m.version);
                at.greenchili.surfmate.notifications.NotificationBus.get().publish(
                        new at.greenchili.surfmate.notifications.NotificationEvent("system.updates",
                                at.greenchili.surfmate.notifications.NotificationEvent.Severity.INFO,
                                at.greenchili.surfmate.server.Messages.get(UpdateMeldung.pushSchluessel(m.art), m.version),
                                "", "update-" + m.art + "-" + m.versionCode, "/local/system.html", data));
            } catch (Throwable t) {
                logger.warn("Update-Push: " + t.getMessage());
            }
        }

        @Override
        public JSONObject konfig() {
            return at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig();
        }

        @Override
        public void frageWaechterStandard() {
            at.greenchili.surfmate.surveillance.WaechterStandardKanaele.senden();
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
            // 0.1.38.5: Art und Kurzfassung über der Frage, der volle Text bleibt hinter „Was ist neu?“
            String neu = UpdateHinweis.wasNeuKurz(f, installierteVersion(), at.greenchili.surfmate.server.LocaleManager.get());
            String text = neu + "\n\n" + UpdateHinweis.frage() + (zusatz.isEmpty() ? "" : "\n\n" + zusatz);
            return at.greenchili.surfmate.byd.MessageOverlayController.showUpdateHinweis(griff, f.versionName,
                    UpdateHinweis.titel(f.versionName), text, notizen,
                    UpdateHinweis.knopfInstallieren(), UpdateHinweis.knopfSpaeter(), UpdateHinweis.knopfWasNeu(),
                    UpdateHinweis.knopfSchliessen(), UpdateHinweis.knopfZuruecknehmen(), UpdateHinweis.textFehler(),
                    UpdateHinweis.SICHTBAR_SEK);
        }

        @Override
        public void nimmHinweisWeg(String griff) {
            at.greenchili.surfmate.byd.MessageOverlayController.dismissKopplung(griff);
        }

        @Override public LauncherPaket.Stand launcherStand() { return LauncherPaket.stand(LauncherPaket.SHELL); }
        @Override public String launcherInstallieren(File apk) { return LauncherPaket.installieren(LauncherPaket.SHELL, apk); }

        @Override
        public String launcherHomeNachUpdate() {
            return LauncherHome.nachUpdate(LauncherPaket.SHELL, at.greenchili.surfmate.launcher.HomeWaechter.wunsch(),
                    at.greenchili.surfmate.launcher.HomeWaechter.behalten());
        }

        @Override
        public void meldeLauncherHome(String grund) {
            String text = at.greenchili.surfmate.server.Messages.text("update_klein.launcher_home",
                    "After the update the Panoptes launcher is no longer the home screen. Open the launcher once and "
                            + "choose it again as home screen.");
            at.greenchili.surfmate.telegram.TelegramNotifier.sendMessage(text, "UPDATE");
            try {
                at.greenchili.surfmate.notifications.NotificationBus.get().publish(
                        new at.greenchili.surfmate.notifications.NotificationEvent("system.updates",
                                at.greenchili.surfmate.notifications.NotificationEvent.Severity.WARN,
                                at.greenchili.surfmate.server.Messages.text("update_klein.titel", "Updates"), text,
                                "update-launcher-home", "/local/system.html",
                                new JSONObject().put("art", "launcher_home").put("grund", grund)));
            } catch (Throwable t) {
                logger.warn("Launcher-Home-Push: " + t.getMessage());
            }
        }
    }
}
