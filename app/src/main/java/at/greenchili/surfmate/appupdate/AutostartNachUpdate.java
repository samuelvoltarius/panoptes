package at.greenchili.surfmate.appupdate;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.1.21 Pflichtpunkt (Koordination 27.10.): Autostart nach einem In-App-Update.
 *
 * <p>BYD löscht die Autostart-Freigabe bei JEDER Installation (Deploy, 0.1.20.1: autostartMissing=true). Bisher hob
 * Panoptes sie nur über den Knopf im Einrichtungshinweis oder per adb-Broadcast wieder auf. Jetzt: Sobald
 * {@code update-on-car.sh} das Update bestätigt hat ({@code .update-ok}, auch „SELBST BESTÄTIGT“), schickt der Daemon
 * (Shell-Rechte, DUMP) EINMAL je Installation {@code AUTOSTART_ENABLE} an die App; die legt den Schalter im BYD-Dialog
 * über den bestehenden AutoStartEnabler um und meldet das Ergebnis zurück. Ergebnis + Zeit stehen im Status
 * ({@code autostart.frei}); schlägt es fehl, zeigt die Startseite am Display einen Knopf.
 *
 * <p>Nicht über {@code ssc_whitelist} ({@code autostart.weg=settings}): Deploy-Befund 22.10. (AUTOSTART_BEFUND.md,
 * Zustand a) – die Liste bleibt nach der Installation unverändert, der Weg wirkt nicht. Nichts außen Sicht-/Hörbares
 * (der BYD-Dialog erscheint kurz am Innendisplay). Nicht bei jedem Reconnect: gemerkt je {@code .update-ok}-Zeitpunkt.
 *
 * <p>0.1.38.8 (04.11.: „kann man das automatisieren?“): Ein Weg ohne den BYD-Dialog gibt es ohne root nicht
 * (AUTOSTART_BEFUND Zustand a: der Schalter liegt bei com.byd.appstartmanagement, für die Shell weder lesbar noch
 * setzbar). Bleibt die Freigabe nach allen Versuchen aus, geht genau einmal je Installation eine Meldung hinaus
 * („bitte einmal am Display erlauben“, Telegram + Handy), statt nur still den Knopf auf der Startseite zu zeigen.
 */
public final class AutostartNachUpdate {

    static final String PKG = "at.greenchili.surfmate";
    static final String ENABLE = "am broadcast -a " + PKG + ".action.AUTOSTART_ENABLE -n " + PKG
            + "/.receiver.AutoStartCheckReceiver";
    static final String STATUS = "am broadcast -a " + PKG + ".action.AUTOSTART_STATUS -n " + PKG
            + "/.receiver.AutoStartCheckReceiver";
    /** 0.1.38.8: Installationszeit und Panoptes-eigene Marke wie {@code deploy/autostart-check.sh}. */
    static final String INSTALLZEIT = "dumpsys package " + PKG + " | grep -m1 lastUpdateTime=";
    static final String MARKE_LESEN = "settings get global panoptes_autostart_ok";
    private static final Pattern INST_ZEIT = Pattern.compile("lastUpdateTime=(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})");
    /** Höchstens so viele automatische Versuche je Installation (nur, wenn die App noch nicht bereit war). */
    static final int MAX_VERSUCHE = 3;
    static final long LIVE_CACHE_MS = 60_000L;

    /** Shell im Daemon (Tests ersetzen sie). */
    public interface Shell {
        /** Ausgabe des Befehls oder null bei Fehler/Zeitüberschreitung. */
        String run(String cmd, int timeoutS);
    }

    /** 0.1.38.8: Meldung „Autostart bitte einmal am Display erlauben“ (Tests ersetzen sie). */
    public interface Melder {
        void autostartFehlt();

        /** 0.1.43.21: neutraler Hinweis „nach dem Umstellen einmal aus/ein, nachsehen“ (nicht belegte Plattform). */
        default void autostartPruefen() { }
    }

    /** 0.1.43.21: Ist die Bedeutung des BYD-Schalters auf diesem Auto belegt? Mit Grund fürs Log. */
    public static final class Entscheidung {
        final boolean belegt;
        final String grund;

        public Entscheidung(boolean belegt, String grund) {
            this.belegt = belegt;
            this.grund = grund == null ? "" : grund;
        }
    }

    /** 0.1.43.21: Plattform-Prüfung (echt: {@code BydSeiten.autostartPlattform}; Tests ersetzen sie). */
    public interface Plattform {
        Entscheidung pruefe();
    }

    /** 0.1.43.21: Auto steht (Gang P, kein Tempo) – erst dann der neutrale Hinweis. */
    public interface Stand {
        boolean steht();
    }

    /** Ergebnis auf nicht belegter Plattform: nichts geklickt, nichts gesetzt. */
    static final String NICHT_BELEGT = "NICHT_BELEGT";

    private final File updateOk;
    private final File merkDatei;
    private final Shell sh;
    private final Melder melder;
    private final Plattform plattform;
    private final Stand stand;
    /** 0.1.43.21: Entscheidungs-Log (echt: DaemonLogger; Tests lesen mit). */
    java.util.function.Consumer<String> log = t -> { };
    private long liveStand;

    AutostartNachUpdate(File updateOk, File merkDatei, Shell sh) {
        this(updateOk, merkDatei, sh, () -> { });
    }

    /** Wie bisher (belegte Plattform, Surf). */
    AutostartNachUpdate(File updateOk, File merkDatei, Shell sh, Melder melder) {
        this(updateOk, merkDatei, sh, melder, () -> new Entscheidung(true, "belegt"), () -> true);
    }

    AutostartNachUpdate(File updateOk, File merkDatei, Shell sh, Melder melder, Plattform plattform, Stand stand) {
        this.updateOk = updateOk;
        this.merkDatei = merkDatei;
        this.sh = sh;
        this.melder = melder;
        this.plattform = plattform;
        this.stand = stand;
    }

    private Entscheidung entscheide() {
        try {
            Entscheidung e = plattform.pruefe();
            return e == null ? new Entscheidung(false, "nicht belegt: keine Angabe") : e;
        } catch (Throwable t) {
            return new Entscheidung(false, "nicht belegt: Plattform nicht lesbar (" + t.getClass().getSimpleName() + ")");
        }
    }

    private void protokoll(String text) {
        try {
            log.accept(text);
        } catch (Throwable ignored) { }
    }

    /**
     * Ausgabe von {@code am broadcast} auswerten. Der Receiver setzt das Ergebnis per {@code setResult}:
     * {@code result=1} = frei, {@code result=0} = nicht frei; {@code data="ergebnis=… ok=…"}.
     * Kein Ergebnis (App/Receiver nicht da) → {@code {frei:false, ergebnis:"keine_antwort"}}.
     */
    static JSONObject auswerten(String ausgabe) throws Exception {
        JSONObject o = new JSONObject();
        Matcher m = Pattern.compile("Broadcast completed: result=(-?\\d+)(?:, data=\"([^\"]*)\")?").matcher(
                ausgabe == null ? "" : ausgabe);
        if (!m.find()) return o.put("frei", false).put("ergebnis", "keine_antwort");
        String daten = m.group(2) == null ? "" : m.group(2);
        Matcher e = Pattern.compile("ergebnis=([A-Za-z_]+)").matcher(daten);
        String ergebnis = e.find() ? e.group(1) : "unbekannt";
        Matcher f = Pattern.compile("autostartMissing=(true|false)").matcher(daten);
        boolean frei = "1".equals(m.group(1));
        if (f.find()) frei = "false".equals(f.group(1));
        return o.put("frei", frei).put("ergebnis", ergebnis);
    }

    /**
     * 0.1.38.8 (Koordination 04.11.): nach erfolgreicher Freigabe wie {@code autostart-check.sh --freigeben} die Marke
     * {@code settings global panoptes_autostart_ok} auf die lastUpdateTime dieser Installation setzen (Selbsttest und
     * Einrichtungsdialog lesen sie) und zurücklesen. Nur ein streng geprüfter Zeitstempel geht in den Befehl.
     * true = gesetzt und zurückgelesen.
     */
    boolean markeSetzen() {
        Matcher m = INST_ZEIT.matcher(String.valueOf(sh.run(INSTALLZEIT, 10)));
        if (!m.find()) return false;
        String inst = m.group(1);
        sh.run("settings put global panoptes_autostart_ok '" + inst + "'", 10);
        String zurueck = sh.run(MARKE_LESEN, 10);
        return zurueck != null && inst.equals(zurueck.trim());
    }

    /** Merkzeichen der laufenden Installation = Zeitpunkt der Bestätigung; 0 = (noch) nicht bestätigt. */
    long installMarke() {
        return updateOk.exists() ? updateOk.lastModified() : 0L;
    }

    synchronized JSONObject merk() {
        try {
            if (merkDatei.exists()) {
                return new JSONObject(new String(Files.readAllBytes(merkDatei.toPath()), StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { }
        return new JSONObject();
    }

    private synchronized void speichere(JSONObject m) {
        try {
            File tmp = new File(merkDatei.getPath() + ".tmp");
            Files.write(tmp.toPath(), m.toString().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(merkDatei)) Files.write(merkDatei.toPath(), m.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    /** Fällig: bestätigte Installation, für die noch kein endgültiges Ergebnis gemerkt ist. */
    static boolean faellig(long marke, JSONObject merk) {
        if (marke <= 0L) return false;
        if (merk.optLong("installMarke", 0L) != marke) return true;
        if (merk.optBoolean("frei", false)) return false;
        String e = merk.optString("ergebnis", "");
        boolean nichtBereit = "keine_antwort".equals(e) || "NO_SERVICE".equals(e);
        return nichtBereit && merk.optInt("versuche", 0) < MAX_VERSUCHE;
    }

    /** Ein Wächter-Durchlauf (automatisch, einmal je Installation). true = es wurde freigegeben/versucht. */
    public boolean pruefen(long jetzt) throws Exception {
        long marke = installMarke();
        JSONObject alt = merk();
        // 0.1.43.21: schon als „nicht belegt“ entschieden → nur noch einmal der neutrale Hinweis, sobald das Auto steht
        if (marke > 0L && alt.optLong("installMarke", 0L) == marke && NICHT_BELEGT.equals(alt.optString("ergebnis"))) {
            return hinweisImStand(alt, jetzt);
        }
        if (!faellig(marke, alt)) return false;
        Entscheidung e = entscheide();
        protokoll("Autostart nach Update: " + (e.belegt ? "Klick erlaubt" : "kein Klick") + " – " + e.grund);
        if (!e.belegt) {
            // Nichts klicken, kein ENABLE, keine Marke: der Zustand am Auto bleibt, wie er ist. Ohne „frei“ zeigt die
            // Startseite keinen Knopf (zustand → frei:null).
            JSONObject r = new JSONObject().put("installMarke", marke).put("zeit", jetzt).put("quelle", "update")
                    .put("ergebnis", NICHT_BELEGT).put("entscheidung", e.grund).put("hinweis", false);
            speichere(r);
            hinweisImStand(r, jetzt);
            return true;
        }
        int versuche = alt.optLong("installMarke", 0L) == marke ? alt.optInt("versuche", 0) : 0;
        JSONObject r = auswerten(sh.run(ENABLE, 90));
        r.put("installMarke", marke).put("zeit", jetzt).put("quelle", "update")
                .put("versuche", versuche + 1).put("weg", "sichtbar").put("entscheidung", e.grund);
        // 0.1.38.8: endgültig nicht frei (kein weiterer Versuch fällig) → genau eine Meldung je Installation
        boolean melden = !r.optBoolean("frei", false) && !faellig(marke, r)
                && !(alt.optLong("installMarke", 0L) == marke && alt.optBoolean("gemeldet", false));
        if (melden) r.put("gemeldet", true);
        if (r.optBoolean("frei", false)) r.put("marke", markeSetzen());
        speichere(r);
        if (melden) {
            try {
                melder.autostartFehlt();
            } catch (Throwable ignored) { }
        }
        return true;
    }

    /** 0.1.43.21: neutraler Hinweis genau einmal je Installation, nur im Stand. true = jetzt gemeldet. */
    private boolean hinweisImStand(JSONObject r, long jetzt) throws Exception {
        if (r.optBoolean("hinweis", false)) return false;
        boolean steht;
        try {
            steht = stand.steht();
        } catch (Throwable t) {
            steht = false;
        }
        if (!steht) return false;
        speichere(r.put("hinweis", true).put("hinweisZeit", jetzt));
        try {
            melder.autostartPruefen();
        } catch (Throwable ignored) { }
        return true;
    }

    /** Knopf „Autostart wieder einschalten“ (am Display): sofort versuchen, Ergebnis merken. */
    public JSONObject freigeben(long jetzt) throws Exception {
        Entscheidung e = entscheide();
        protokoll("Autostart-Knopf: " + (e.belegt ? "Klick erlaubt" : "kein Klick") + " – " + e.grund);
        if (!e.belegt) {
            // 0.1.43.21: auch auf Knopfdruck kein automatischer Klick auf nicht belegter Plattform; nichts gemerkt
            return new JSONObject().put("frei", false).put("ergebnis", NICHT_BELEGT).put("entscheidung", e.grund);
        }
        JSONObject r = auswerten(sh.run(ENABLE, 90));
        JSONObject alt = merk();
        r.put("installMarke", installMarke()).put("zeit", jetzt).put("quelle", "knopf")
                .put("versuche", alt.optInt("versuche", 0) + 1).put("weg", "sichtbar");
        // 0.1.38.8: die Meldung zu dieser Installation ist schon draußen, auch nach einem Tipp
        if (alt.optLong("installMarke", 0L) == installMarke() && alt.optBoolean("gemeldet", false)) r.put("gemeldet", true);
        if (r.optBoolean("frei", false)) r.put("marke", markeSetzen());
        speichere(r);
        liveStand = jetzt;
        return r;
    }

    /**
     * Status für {@code /api/appupdate/status} und die Startseite: {@code {frei, zeit, ergebnis, quelle}} oder
     * {@code {frei:null}} ohne Ergebnis. Steht „nicht frei“, einmal je Minute live nachsehen (Nutzer kann am BYD-Dialog
     * selbst freigegeben haben).
     */
    public JSONObject zustand(long jetzt) throws Exception {
        JSONObject m = merk();
        if (!m.has("frei")) return new JSONObject().put("frei", JSONObject.NULL);
        if (!m.optBoolean("frei", false) && jetzt - liveStand >= LIVE_CACHE_MS) {
            liveStand = jetzt;
            JSONObject live = auswerten(sh.run(STATUS, 10));
            if (live.optBoolean("frei", false)) {
                speichere(m.put("frei", true).put("zeit", jetzt).put("quelle", "live").put("ergebnis", "ALREADY_OFF"));
            }
        }
        JSONObject o = new JSONObject().put("frei", m.optBoolean("frei", false)).put("zeit", m.optLong("zeit", 0L))
                .put("ergebnis", m.optString("ergebnis", "")).put("quelle", m.optString("quelle", ""));
        return o.put("aktuell", m.optLong("installMarke", -1L) == installMarke());
    }

    // ------------------------------------------------------------------ Laufzeit

    private static volatile AutostartNachUpdate instanz;

    public static AutostartNachUpdate get() {
        AutostartNachUpdate a = instanz;
        if (a == null) {
            synchronized (AutostartNachUpdate.class) {
                if (instanz == null) {
                    instanz = new AutostartNachUpdate(new File(AppUpdateDienst.NEU, ".update-ok"),
                            new File(AppUpdateDienst.NEU, "autostart_nach_update.json"), AutostartNachUpdate::shell,
                            new Melder() {
                                public void autostartFehlt() { meldeFehlt(); }
                                public void autostartPruefen() { meldePruefen(); }
                            },
                            () -> {
                                kotlin.Pair<Boolean, String> p = at.greenchili.surfmate.einrichtung.BydSeiten.autostartPlattform();
                                return new Entscheidung(p.getFirst(), p.getSecond());
                            },
                            AutostartNachUpdate::stehtEcht);
                    instanz.log = t -> at.greenchili.surfmate.logging.DaemonLogger.getInstance("AutostartNachUpdate").info(t);
                }
                a = instanz;
            }
        }
        return a;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean GESTARTET = new java.util.concurrent.atomic.AtomicBoolean();

    /** Im Daemon: jede Minute prüfen (billig: nur Dateizeit), handelt höchstens einmal je Installation. */
    public static void starteWaechter() {
        if (!GESTARTET.compareAndSet(false, true)) return;
        java.util.concurrent.ScheduledExecutorService ex = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AutostartNachUpdate");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleWithFixedDelay(() -> {
            try {
                if (get().pruefen(System.currentTimeMillis())) {
                    at.greenchili.surfmate.logging.DaemonLogger.getInstance("AutostartNachUpdate")
                            .info("Autostart nach Update: " + get().merk());
                }
            } catch (Throwable ignored) { }
        }, 60, 60, TimeUnit.SECONDS);
    }

    /** Gang P und kein Tempo, wie {@code LadenApiHandler} (byd.Stillstand). */
    static boolean stehtEcht() {
        try {
            at.greenchili.surfmate.byd.BydVehicleData v = at.greenchili.surfmate.byd.BydDataCollector.getInstance().getData();
            return v != null && v.gearMode == 1 && at.greenchili.surfmate.byd.Stillstand.tempoJetzt(v) < 1;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 0.1.43.21: neutraler Hinweis (nicht belegte Plattform), Telegram UPDATE + Handy-Push, einmal je Installation. */
    static void meldePruefen() {
        String titel = at.greenchili.surfmate.server.Messages.text("update_klein.autostart_titel", "Autostart");
        String text = at.greenchili.surfmate.server.Messages.text("update_klein.autostart_pruefen",
                "After the update: how the BYD autostart switch works differs between models. Panoptes changed nothing "
                        + "there. Switch the car off and on once and check whether Panoptes starts by itself.");
        at.greenchili.surfmate.telegram.TelegramNotifier.sendMessage(text, "UPDATE");
        try {
            at.greenchili.surfmate.notifications.NotificationBus.get().publish(
                    new at.greenchili.surfmate.notifications.NotificationEvent("system.updates",
                            at.greenchili.surfmate.notifications.NotificationEvent.Severity.INFO, titel, text,
                            "update-autostart-pruefen", "/local/system.html",
                            new org.json.JSONObject().put("art", "autostart_pruefen")));
        } catch (Throwable ignored) { }
    }

    /** Telegram (Kategorie UPDATE) und Handy-Push {@code system.updates}, Text in der Nutzersprache. */
    static void meldeFehlt() {
        String titel = at.greenchili.surfmate.server.Messages.text("update_klein.autostart_titel", "Autostart");
        String text = at.greenchili.surfmate.server.Messages.text("update_klein.autostart_fehlt",
                "After the update Panoptes needs the autostart permission once more. Please allow it once on the car "
                        + "display (Panoptes start page).");
        at.greenchili.surfmate.telegram.TelegramNotifier.sendMessage(text, "UPDATE");
        try {
            at.greenchili.surfmate.notifications.NotificationBus.get().publish(
                    new at.greenchili.surfmate.notifications.NotificationEvent("system.updates",
                            at.greenchili.surfmate.notifications.NotificationEvent.Severity.WARN, titel, text,
                            "update-autostart-fehlt", "/local/system.html",
                            new org.json.JSONObject().put("art", "autostart_fehlt")));
        } catch (Throwable ignored) { }
    }

    private static String shell(String cmd, int timeoutS) {
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            final Process pp = p;
            Thread leser = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(pp.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) synchronized (sb) { sb.append(line).append('\n'); }
                } catch (Exception ignored) { }
            });
            leser.setDaemon(true);
            leser.start();
            if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) return null;
            leser.join(1000);
            synchronized (sb) { return sb.toString(); }
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }
}
