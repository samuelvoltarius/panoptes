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
 */
public final class AutostartNachUpdate {

    static final String PKG = "at.greenchili.surfmate";
    static final String ENABLE = "am broadcast -a " + PKG + ".action.AUTOSTART_ENABLE -n " + PKG
            + "/.receiver.AutoStartCheckReceiver";
    static final String STATUS = "am broadcast -a " + PKG + ".action.AUTOSTART_STATUS -n " + PKG
            + "/.receiver.AutoStartCheckReceiver";
    /** Höchstens so viele automatische Versuche je Installation (nur, wenn die App noch nicht bereit war). */
    static final int MAX_VERSUCHE = 3;
    static final long LIVE_CACHE_MS = 60_000L;

    /** Shell im Daemon (Tests ersetzen sie). */
    public interface Shell {
        /** Ausgabe des Befehls oder null bei Fehler/Zeitüberschreitung. */
        String run(String cmd, int timeoutS);
    }

    private final File updateOk;
    private final File merkDatei;
    private final Shell sh;
    private long liveStand;

    AutostartNachUpdate(File updateOk, File merkDatei, Shell sh) {
        this.updateOk = updateOk;
        this.merkDatei = merkDatei;
        this.sh = sh;
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
        if (!faellig(marke, alt)) return false;
        int versuche = alt.optLong("installMarke", 0L) == marke ? alt.optInt("versuche", 0) : 0;
        JSONObject r = auswerten(sh.run(ENABLE, 90));
        speichere(r.put("installMarke", marke).put("zeit", jetzt).put("quelle", "update")
                .put("versuche", versuche + 1).put("weg", "sichtbar"));
        return true;
    }

    /** Knopf „Autostart wieder einschalten“ (am Display): sofort versuchen, Ergebnis merken. */
    public JSONObject freigeben(long jetzt) throws Exception {
        JSONObject r = auswerten(sh.run(ENABLE, 90));
        JSONObject alt = merk();
        speichere(r.put("installMarke", installMarke()).put("zeit", jetzt).put("quelle", "knopf")
                .put("versuche", alt.optInt("versuche", 0) + 1).put("weg", "sichtbar"));
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
                            new File(AppUpdateDienst.NEU, "autostart_nach_update.json"), AutostartNachUpdate::shell);
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
