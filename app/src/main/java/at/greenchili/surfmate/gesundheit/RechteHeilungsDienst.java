package at.greenchili.surfmate.gesundheit;

import at.greenchili.surfmate.logging.DaemonLogger;
import at.greenchili.surfmate.server.Messages;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Rechte-Selbstheilung im Kamera-Daemon (läuft als {@code shell}): {@link RechteHeilung#durchgang} kurz nach dem
 * Start und danach täglich. Fehlt nach der Reparatur noch etwas, gibt es eine Meldung am Display und über Telegram
 * mit dem, was zu tun ist. Dieselbe Meldung höchstens einmal am Tag. Keine Fahrzeugfunktion, nur Rechte der App.
 *
 * <p>0.1.43.16 (E2E 0.1.43.13): der Dialog am Display kommt nur im sicheren Stand (dieselbe fail-closed-Prüfung wie die
 * Wächter-Frage). Sonst bleibt die Meldung offen und wird alle {@value #MELDE_WIEDER_S} s erneut versucht, bis sie am
 * Display stand (Quittung). Telegram geht sofort, je Meldung einmal. Der Dialog geht nach {@value #DIALOG_SEK} s von selbst.
 * Steht das Auto, aber der Overlay-Dienst quittiert {@value #DISPLAY_VERSUCHE}-mal nicht (z. B. fehlt gerade das
 * Overlay-Recht), gilt sie als gemeldet (Telegram war schon da), damit sie nicht endlos versucht wird.
 *
 * <p>0.1.43.17 (E2E 0.1.43.16): der Meldestand ({@code gemeldet}, {@code gemeldetMs}, offene Meldung, Telegram gesendet,
 * Display-Versuche) liegt in {@value #DATEI} neben den anderen Markern und wird atomar geschrieben. Nach einem
 * Kern-Neustart kommt dieselbe Meldung also nicht noch einmal per Telegram und behält ihre Display-Pflicht.
 */
public final class RechteHeilungsDienst {

    private static final DaemonLogger logger = DaemonLogger.getInstance("RechteHeilung");
    /** Erste Prüfung nach dem Start: nach dem {@code PermissionGranter}, der beim Start ohnehin alles setzt. */
    static final long ERSTE_PRUEFUNG_S = 5 * 60;
    static final long TAKT_S = 24L * 3600;
    /** 0.1.43.16: Wiederholung, solange eine Display-Meldung offen ist (nur Anzeige, ohne neue Heilung). */
    static final long MELDE_WIEDER_S = 5 * 60;
    static final int DIALOG_SEK = 180;
    static final int DISPLAY_VERSUCHE = 3;

    /** Wege nach außen; im Test ersetzt. */
    interface Wege {
        /** Sicherer Stand für einen Dialog (fail-closed). */
        boolean stand();
        /**
         * Dialog zeigen und warten, bis er zu ist (0.1.43.17). {@code ohneVersuch()} (losgefahren, anderer Frage-Dialog
         * offen, verdrängt) kostet keinen Versuch; {@code NICHT_GEZEIGT} ist ein Fehlversuch; alles andere stand da.
         */
        at.greenchili.surfmate.byd.MessageOverlayController.DialogAusgang display(String titel, String text);
        void telegram(String titel, String text);
    }

    static final Wege ANDROID = new Wege() {
        @Override public boolean stand() {
            return at.greenchili.surfmate.appupdate.AppUpdateDienst.sichererStandJetzt();
        }

        @Override public at.greenchili.surfmate.byd.MessageOverlayController.DialogAusgang display(String titel, String text) {
            // 0.1.43.17: mit Ausgang, damit der Kern bei offenem Dialog jede Sekunde den Stand prüft und beim Losfahren schließt
            return at.greenchili.surfmate.byd.MessageOverlayController.showDialogMitAusgang(titel, text, "OK", "warning",
                    DIALOG_SEK, at.greenchili.surfmate.appupdate.AppUpdateDienst::sichererStandJetzt);
        }

        @Override public void telegram(String titel, String text) {
            at.greenchili.surfmate.telegram.TelegramNotifier.sendMessage("⚠️ *" + titel + "*\n" + text, "CRITICAL");
        }
    };
    private static final long BEFEHL_MAX_MS = 15_000L;

    private static volatile RechteHeilungsDienst instance;

    private final String pkg;
    private volatile JSONObject letzterStand = new JSONObject();
    private List<String> gemeldet = new ArrayList<>();
    private long gemeldetMs;
    private final Wege wege;
    private final RechteHeilung.Shell sh;
    /** 0.1.43.16: Meldung, die noch ans Display muss (null = keine); Telegram dafür schon gesendet? */
    private List<String> offeneMeldung;
    private boolean offeneTelegram;
    private int offeneVersuche;
    private long naechsteHeilungMs = -1;
    /** 0.1.43.17: Ordner für {@value #DATEI}; null = nichts speichern. */
    private final java.io.File basis;

    /** 0.1.43.17: Meldestand, überlebt einen Kern-Neustart. */
    static final String DATEI = ".rechte-meldung.json";
    /** Derselbe Ordner wie der Marker der Wächter-Frage. */
    private static final String BASIS = "/data/user_de/0/com.android.shell/surfmate";

    private RechteHeilungsDienst(String pkg) {
        this(pkg, ANDROID, RechteHeilungsDienst::shell,
                new java.io.File(at.greenchili.surfmate.util.DaemonStorage.rebase(BASIS)));
    }

    RechteHeilungsDienst(String pkg, Wege wege, RechteHeilung.Shell sh) {
        this(pkg, wege, sh, null);
    }

    RechteHeilungsDienst(String pkg, Wege wege, RechteHeilung.Shell sh, java.io.File basis) {
        this.pkg = pkg;
        this.wege = wege;
        this.sh = sh;
        this.basis = basis;
        laden();
    }

    // ------------------------------------------------------------------ 0.1.43.17 Meldestand auf Platte

    private void laden() {
        if (basis == null) return;
        try {
            java.io.File f = new java.io.File(basis, DATEI);
            if (!f.isFile()) return;
            JSONObject o = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            gemeldet = liste(o.optJSONArray("gemeldet"));
            gemeldetMs = o.optLong("gemeldetMs", 0);
            JSONArray offen = o.optJSONArray("offen");
            offeneMeldung = offen == null || offen.length() == 0 ? null : liste(offen);
            offeneTelegram = o.optBoolean("telegram", false);
            offeneVersuche = o.optInt("versuche", 0);
        } catch (Exception e) {
            logger.warn("Meldestand nicht lesbar, beginne leer: " + e.getMessage());
        }
    }

    private static List<String> liste(JSONArray a) {
        List<String> r = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) r.add(a.optString(i));
        return r;
    }

    private void speichern() {
        if (basis == null) return;
        try {
            JSONObject o = new JSONObject().put("gemeldet", new JSONArray(gemeldet)).put("gemeldetMs", gemeldetMs)
                    .put("telegram", offeneTelegram).put("versuche", offeneVersuche);
            if (offeneMeldung != null) o.put("offen", new JSONArray(offeneMeldung));
            byte[] b = o.toString().getBytes(StandardCharsets.UTF_8);
            java.io.File tmp = new java.io.File(basis, DATEI + ".neu");
            java.nio.file.Files.write(tmp.toPath(), b);
            java.nio.file.Path ziel = new java.io.File(basis, DATEI).toPath();
            try {
                java.nio.file.Files.move(tmp.toPath(), ziel,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException nicht) {
                java.nio.file.Files.move(tmp.toPath(), ziel, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            logger.warn("Meldestand nicht gespeichert: " + e.getMessage());
        }
    }

    public static synchronized void start(String pkg) {
        if (instance != null) return;
        RechteHeilungsDienst d = new RechteHeilungsDienst(pkg);
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RechteHeilung");
            t.setDaemon(true);
            return t;
        });
        // 0.1.43.16: Takt alle 5 min; die Heilung selbst nur alle 24 h, dazwischen nur eine offene Display-Meldung
        exec.scheduleWithFixedDelay(() -> {
            try {
                d.tick(System.currentTimeMillis());
            } catch (Throwable t) {
                logger.warn("takt: " + t.getMessage());
            }
        }, ERSTE_PRUEFUNG_S, MELDE_WIEDER_S, TimeUnit.SECONDS);
        instance = d;
    }

    /** Letzter Befund ({@code zustand}, {@code offen}, {@code repariert}, {@code ts}); leer vor der ersten Prüfung. */
    public static JSONObject status() {
        RechteHeilungsDienst d = instance;
        return d == null ? new JSONObject() : d.letzterStand;
    }

    /** 0.1.43.16: alle {@value #MELDE_WIEDER_S} s; Heilung fällig → {@link #takt}, sonst nur die offene Meldung. */
    void tick(long now) {
        if (naechsteHeilungMs < 0 || now >= naechsteHeilungMs || now < naechsteHeilungMs - TAKT_S * 1000) {
            naechsteHeilungMs = now + TAKT_S * 1000;
            takt(now);
        } else {
            meldungTakt(now);
        }
    }

    void takt(long now) {
        RechteHeilung.Ergebnis e = RechteHeilung.durchgang(pkg, sh);
        JSONObject s = new JSONObject();
        try {
            s.put("zustand", e.zustand).put("offen", new JSONArray(e.offen)).put("repariert", new JSONArray(e.repariert))
                    .put("ts", now / 1000);
        } catch (Exception ignored) {
        }
        letzterStand = s;
        if ("geheilt".equals(e.zustand)) logger.warn("Rechte wiederhergestellt: " + e.repariert);
        else if ("offen".equals(e.zustand)) logger.warn("Rechte fehlen weiter: " + e.offen + " (repariert: " + e.repariert + ")");
        else logger.info("Rechte: " + e.zustand);
        if (e.offen.isEmpty()) {
            boolean war = offeneMeldung != null || !gemeldet.isEmpty() || gemeldetMs != 0;
            gemeldet = new ArrayList<>();
            gemeldetMs = 0;
            offeneMeldung = null;   // nichts mehr zu melden
            if (war) speichern();
        } else if (RechteHeilung.meldenNoetig(e.offen, gemeldet, gemeldetMs, now)) {
            if (offeneMeldung == null || !new java.util.HashSet<>(offeneMeldung).equals(new java.util.HashSet<>(e.offen))) {
                offeneMeldung = new ArrayList<>(e.offen);
                offeneTelegram = false;
                offeneVersuche = 0;
                speichern();
            }
            meldungTakt(now);
        }
    }

    /**
     * 0.1.43.16: offene Meldung weitergeben. Telegram sofort (einmal); Display nur im sicheren Stand, „gemeldet“ erst nach
     * der Quittung (oder nach {@value #DISPLAY_VERSUCHE} Versuchen im Stand ohne Quittung).
     */
    void meldungTakt(long now) {
        List<String> offen = offeneMeldung;
        if (offen == null) return;
        String[] m = meldung(offen);
        if (!offeneTelegram) {
            offeneTelegram = true;
            speichern();   // vor dem Senden: lieber einmal zu wenig als nach einem Absturz doppelt
            try {
                wege.telegram(m[0], m[1]);
            } catch (Throwable t) {
                logger.warn("Telegram-Meldung: " + t.getMessage());
            }
        }
        boolean stand;
        try {
            stand = wege.stand();
        } catch (Throwable t) {
            stand = false;
        }
        if (!stand) {
            logger.info("Rechte-Meldung aufgeschoben (Auto steht nicht sicher)");
            return;
        }
        at.greenchili.surfmate.byd.MessageOverlayController.DialogAusgang a =
                at.greenchili.surfmate.byd.MessageOverlayController.DialogAusgang.NICHT_GEZEIGT;
        try {
            a = wege.display(m[0], m[1]);
            if (a == null) a = at.greenchili.surfmate.byd.MessageOverlayController.DialogAusgang.NICHT_GEZEIGT;
        } catch (Throwable t) {
            logger.warn("Display-Meldung: " + t.getMessage());
        }
        if (a.ohneVersuch()) {
            logger.info("Rechte-Meldung: " + a + ", zählt nicht, im Stand erneut");
            return;
        }
        boolean gezeigt = a.gezeigt();
        if (!gezeigt && ++offeneVersuche < DISPLAY_VERSUCHE) {
            speichern();
            logger.info("Rechte-Meldung: Display ohne Quittung (" + offeneVersuche + "), später erneut");
            return;
        }
        if (!gezeigt) logger.warn("Rechte-Meldung: Display " + offeneVersuche + "-mal ohne Quittung, nur Telegram");
        gemeldet = new ArrayList<>(offen);
        gemeldetMs = now;
        offeneMeldung = null;
        offeneTelegram = false;
        offeneVersuche = 0;
        speichern();
    }

    /** Für Tests: ist eine Display-Meldung offen? */
    boolean meldungOffen() {
        return offeneMeldung != null;
    }

    /** Text der Meldung: Titel und was zu tun ist (Server-Kataloge, 5 Sprachen). */
    static String[] meldung(List<String> offen) {
        StringBuilder liste = new StringBuilder();
        for (String w : offen) {
            if (liste.length() > 0) liste.append(", ");
            liste.append(Messages.text("rechte_heilung.wort_" + w, w));
        }
        String titel = Messages.text("rechte_heilung.titel", "Panoptes has lost permissions");
        String text = Messages.text("rechte_heilung.text",
                "The car took these permissions away and Panoptes could not restore them: {0}. Please open Panoptes "
                        + "once on the car screen. If that does not help: Android settings, Apps, Panoptes, Permissions, "
                        + "and allow the ones listed.", liste.toString());
        return new String[] {titel, text};
    }

    /** {@code sh -c} als {@code shell}, Ausgabe oder null; höchstens {@link #BEFEHL_MAX_MS}. */
    static String shell(String befehl) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[] {"sh", "-c", befehl});
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String z;
                while ((z = r.readLine()) != null && sb.length() < 512 * 1024) sb.append(z).append('\n');
            }
            if (!p.waitFor(BEFEHL_MAX_MS, TimeUnit.MILLISECONDS)) {
                p.destroy();
                return null;
            }
            return sb.toString();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) {
                try { p.getErrorStream().close(); } catch (Exception ignored) { }
            }
        }
    }
}
