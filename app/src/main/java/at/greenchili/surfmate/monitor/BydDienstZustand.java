package at.greenchili.surfmate.monitor;

import at.greenchili.surfmate.logging.DaemonLogger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.1.43.20: BYDs eigene Dienste als zusätzliches Park-/ACC-Signal für die shell-Daemons. Nur lesen ({@code dumpsys}),
 * keine Fahrzeugbefehle, keine Binder-Aufrufe.
 *
 * <p>Was belegt ist (Auto-Fundgrube 24.11.2026, Inventur 20.11., Dolphin Surf DiLink 3.0):
 * <ul>
 *   <li>{@code dumpsys byd_car_service} enthält „Current Driving State: N“ (geparkt und verriegelt: 3, beim Boot
 *   „changed from 255 to 3“). Was die anderen Werte heißen, ist <b>nicht</b> belegt. Deshalb entscheidet Panoptes
 *   damit nichts; der Wert wird nur mitgeschrieben (Protokollzeile {@code BYD_FAHRZUSTAND} mit dem Gang), damit
 *   die Bedeutung später an echten Autos belegt werden kann.</li>
 *   <li>{@code dumpsys accmodemanager} enthält {@code mSuspending=0|1} und {@code Acc Locks: size=N}. {@code
 *   mSuspending=1} heißt: BYDs Stromverwaltung legt das System gerade schlafen. Das nutzt Panoptes nur als
 *   <b>zusätzliche Bremse</b> (z. B. keine vorgemerkte Installation starten), nie als Freigabe.</li>
 * </ul>
 * Nicht lesbar (anderes Auto, keine shell-Rechte, Dienst fehlt) → {@code null}; dann gilt allein das bisherige Signal.
 */
public final class BydDienstZustand {

    private static final DaemonLogger logger = DaemonLogger.getInstance("BydDienstZustand");
    private static final Pattern FAHRZUSTAND = Pattern.compile("Current Driving State:\\s*(-?\\d+)");
    private static final Pattern SUSPENDING = Pattern.compile("mSuspending\\s*=\\s*(\\d+)");
    private static final Pattern LOCKS = Pattern.compile("Acc Locks:\\s*size\\s*=\\s*(\\d+)");
    static final long FRISCH_MS = 30_000L;

    private BydDienstZustand() {}

    /** Eine Momentaufnahme; jedes Feld null = nicht lesbar. */
    public static final class Stand {
        public final Integer fahrzustand;
        public final Boolean schlaeft;
        public final Integer accSperren;
        public final long um;

        Stand(Integer fahrzustand, Boolean schlaeft, Integer accSperren, long um) {
            this.fahrzustand = fahrzustand; this.schlaeft = schlaeft; this.accSperren = accSperren; this.um = um;
        }
    }

    // ==================================================================== rein

    /** „Current Driving State: N“ → N; fehlt, 255 (= ungültig, Boot) oder negativ → null. */
    public static Integer fahrzustand(String dump) {
        if (dump == null) return null;
        Matcher m = FAHRZUSTAND.matcher(dump);
        Integer w = null;
        while (m.find()) {
            try { w = Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { w = null; }
        }
        return w == null || w < 0 || w == 255 ? null : w;
    }

    /** {@code mSuspending=1} → TRUE, {@code =0} → FALSE, fehlt → null. */
    public static Boolean schlaeft(String dump) {
        if (dump == null) return null;
        Matcher m = SUSPENDING.matcher(dump);
        if (!m.find()) return null;
        String v = m.group(1);
        return "1".equals(v) ? Boolean.TRUE : "0".equals(v) ? Boolean.FALSE : null;
    }

    /** „Acc Locks: size=N“ → N, fehlt → null. */
    public static Integer accSperren(String dump) {
        if (dump == null) return null;
        Matcher m = LOCKS.matcher(dump);
        if (!m.find()) return null;
        try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { return null; }
    }

    /** Zusätzliche Bremse: nur ein belegtes „schläft gerade ein“ bremst; unbekannt bremst nie. */
    public static boolean bremst(Stand s) {
        return s != null && Boolean.TRUE.equals(s.schlaeft);
    }

    // ==================================================================== live (nur shell/system)

    private static volatile Stand letzter;
    private static volatile Integer gemeldeterFahrzustand;

    /** Momentaufnahme, höchstens alle {@value #FRISCH_MS} ms neu gelesen. Nie null (Felder ggf. null). */
    public static Stand jetzt() {
        Stand s = letzter;
        long t = System.currentTimeMillis();
        if (s != null && t - s.um >= 0 && t - s.um < FRISCH_MS) return s;
        synchronized (BydDienstZustand.class) {
            s = letzter;
            if (s != null && t - s.um >= 0 && t - s.um < FRISCH_MS) return s;
            if (!darfLesen()) {
                s = new Stand(null, null, null, t);
            } else {
                String acc = dumpsys("accmodemanager");
                s = new Stand(fahrzustand(dumpsys("byd_car_service")), schlaeft(acc), accSperren(acc), t);
            }
            letzter = s;
        }
        mitschreiben(s);
        return s;
    }

    /** Für den Updater: true nur, wenn BYD gerade belegt einschläft. */
    public static boolean schlaeftGleich() {
        try {
            return bremst(jetzt());
        } catch (Throwable t) {
            return false;
        }
    }

    /** dumpsys der BYD-Dienste geht nur als shell (2000) oder system (1000), nie aus der App. */
    private static boolean darfLesen() {
        try {
            int uid = android.os.Process.myUid();
            return uid == 2000 || uid == 1000;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void mitschreiben(Stand s) {
        if (s.fahrzustand == null || s.fahrzustand.equals(gemeldeterFahrzustand)) return;
        gemeldeterFahrzustand = s.fahrzustand;
        String gang = "unbekannt";
        try {
            gang = GearMonitor.gearToString(at.greenchili.surfmate.byd.BydDataCollector.getInstance().getData().gearMode);
        } catch (Throwable ignored) {
        }
        logger.info("BYD_FAHRZUSTAND wert=" + s.fahrzustand + " gang=" + gang + " schlaeft=" + s.schlaeft
                + " accSperren=" + s.accSperren);
    }

    private static String dumpsys(String dienst) {
        Process p = null;
        try {
            p = new ProcessBuilder("dumpsys", "-t", "2", dienst).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String z;
                while ((z = r.readLine()) != null && sb.length() < 64 * 1024) sb.append(z).append('\n');
            }
            if (!p.waitFor(3, TimeUnit.SECONDS)) return null;
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }

    /** Nur für Tests. */
    static void vergessen() { letzter = null; gemeldeterFahrzustand = null; }
}
