package at.greenchili.surfmate.surveillance;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import at.greenchili.surfmate.ai.Detection;
import at.greenchili.surfmate.ai.YoloDetector;
import at.greenchili.surfmate.config.UnifiedConfigManager;
import at.greenchili.surfmate.logging.DaemonLogger;

/**
 * 0.1.44 (L1): Live-Umgebung im Launcher. Die vorhandene YOLO-Erkennung auf dem 2×2-Mosaik der gemeinsamen
 * Kamera-Pipeline (kein zweites Kamera-Oeffnen, Dashcam/Waechter bleiben Eigentuemer), Ergebnis nur als grobe Skizze:
 * Seite (vorn/rechts/hinten/links), Lage quer (Drittel), Zone (nah/mittel/fern), Art (Auto/Lkw/Person/Rad).
 *
 * <p>Regeln:
 * <ul>
 *   <li>Ab Werk aus ({@code launcher.liveUmgebung}). Laeuft nur, solange der Launcher die Kachel zeigt (Abfrage in den
 *       letzten {@link #SICHTBAR_MS}) und der Waechter nicht laeuft (der hat Vorrang).</li>
 *   <li>Nichts speichern: keine Bilder, keine Kaesten, keine Zeile je Lauf im Log ({@link YoloDetector#setLeise}).
 *       Nur das letzte Ergebnis im Speicher.</li>
 *   <li>Leistung wird gemessen. Reicht sie nicht (unter {@link #MIN_HZ} Laeufe/s), sperrt sich die Funktion ehrlich
 *       ({@code status=leistung}). Zu warm: Pause ({@code status=warm}).</li>
 *   <li>Keine Warnung, kein Ton: nur Anzeige. Kein frisches Ergebnis = {@code keine_erkennung}, nie „frei“.</li>
 * </ul>
 */
public final class LiveUmgebung {
    private static final DaemonLogger logger = DaemonLogger.getInstance("LiveUmgebung");

    public static final String ABSCHNITT = "launcher";
    public static final String SCHALTER = "liveUmgebung";

    static final long SICHTBAR_MS = 3_000L;
    static final long MIN_ABSTAND_MS = 200L;          // hoechstens 5 Laeufe/s
    static final long FRISCH_MS = 1_500L;
    /** E2E 0.1.43b M3: so lange darf „startet“ ohne erstes Ergebnis stehen, danach ehrlich „keine_erkennung“. */
    static final long START_FRIST_MS = 8_000L;
    static final long MESSFENSTER_MS = 20_000L;
    static final double MIN_HZ = 2.0;
    static final double LIVE_HZ = 4.0;
    static final long LEISTUNG_SPERRE_MS = 10 * 60_000L;
    static final int HEISS_C = 75;
    static final int KUEHL_C = 68;
    static final long TEMPERATUR_ALLE_MS = 5_000L;
    static final int THREADS = 2;
    static final float KONFIDENZ = 0.35f;
    static final float MIN_REL = 0.06f;               // relativ zur Mosaik-Hoehe (≈ 12 % des Kamerabilds)
    static final float NAH = 0.40f;                   // Kastenhoehe relativ zum Kamerabild
    static final float MITTEL = 0.18f;
    static final int MAX_OBJEKTE = 24;

    /** Mosaik-Anordnung wie GpuDownscaler: Q0 oben links … (MotionPipelineV2.QUADRANT_NAMES). */
    static final String[] SEITEN = MotionPipelineV2.QUADRANT_NAMES;

    private static final ExecutorService rechner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LiveUmgebung");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final AtomicBoolean busy = new AtomicBoolean(false);

    private static volatile long letzteAbfrage;
    private static volatile long letzterStart;
    private static volatile long schalterAt;
    private static volatile boolean schalter;
    private static volatile long sperreBis;
    private static volatile boolean warm;
    private static volatile long temperaturAt;
    private static volatile Integer temperatur;
    private static volatile boolean modellFehler;
    private static volatile YoloDetector detector;
    private static volatile byte[] puffer;

    // Messung (nur Zahlen, keine Inhalte)
    private static final Object messLock = new Object();
    private static final long[] laufEnde = new long[64];
    private static final long[] laufMs = new long[64];
    private static int laufN;
    private static volatile long sichtbarSeit;
    private static volatile long anSeit;

    // Letztes Ergebnis
    private static volatile JSONArray objekte = new JSONArray();
    private static volatile long ergebnisAt;

    private LiveUmgebung() {}

    // ---------------------------------------------------------------- Kamera-Seite (GL-/Worker-Thread, billig)

    /** Pro Kamerabild auf dem GL-Thread: braucht die KI-Spur Bilder fuer die Live-Umgebung? */
    public static boolean benoetigt() {
        long now = jetzt();
        if (now - letzteAbfrage > SICHTBAR_MS) return false;
        if (!schalterAn(now)) return false;
        if (modellFehler || warm) return false;
        return now >= sperreBis;
    }

    /** Soll genau dieses Bild gelesen werden? (Taktgrenze, nicht beschaeftigt) */
    public static boolean willBild() {
        return benoetigt() && !busy.get() && jetzt() - letzterStart >= MIN_ABSTAND_MS;
    }

    /**
     * Mosaik uebernehmen (wird kopiert; der Aufrufer gibt seinen Puffer sofort zurueck). Rechnet auf eigenem
     * Thread mit niedriger Prioritaet. false = nicht angenommen.
     */
    public static boolean nimm(byte[] mosaik, int breite, int hoehe) {
        if (mosaik == null || breite <= 0 || hoehe <= 0 || mosaik.length < breite * hoehe * 3) return false;
        if (!willBild()) return false;
        if (!busy.compareAndSet(false, true)) return false;
        letzterStart = jetzt();
        byte[] b = puffer;
        int n = breite * hoehe * 3;
        if (b == null || b.length != n) { b = new byte[n]; puffer = b; }
        System.arraycopy(mosaik, 0, b, 0, n);
        final byte[] bild = b;
        try {
            rechner.execute(() -> {
                try { rechne(bild, breite, hoehe); }
                catch (Throwable t) { logger.warn("Live-Umgebung: Lauf fehlgeschlagen: " + t.getClass().getSimpleName()); }
                finally { busy.set(false); }
            });
            return true;
        } catch (Throwable t) {
            busy.set(false);
            return false;
        }
    }

    private static void rechne(byte[] bild, int breite, int hoehe) {
        temperaturPruefen();
        if (warm) return;
        YoloDetector d = detector();
        if (d == null) return;
        long t0 = jetzt();
        List<Detection> dets = d.detect(bild, breite, hoehe, KONFIDENZ, true, true, false, true, MIN_REL);
        long ms = jetzt() - t0;
        JSONArray o = objekte(dets, breite, hoehe);
        objekte = o;
        ergebnisAt = jetzt();
        messen(ergebnisAt, ms);
    }

    private static YoloDetector detector() {
        YoloDetector d = detector;
        if (d != null || modellFehler) return d;
        try {
            android.content.res.AssetManager am = at.greenchili.surfmate.daemon.DaemonBootstrap.apkAssets();
            if (am == null) { modellFehler = true; return null; }
            YoloDetector neu = new YoloDetector(new at.greenchili.surfmate.ai.AssetContext(am), THREADS);
            neu.setLeise(true);
            if (!neu.init()) { modellFehler = true; return null; }
            detector = neu;
            logger.info("Live-Umgebung: Erkennung bereit (" + THREADS + " Threads)");
            return neu;
        } catch (Throwable t) {
            modellFehler = true;
            logger.warn("Live-Umgebung: Modell nicht ladbar: " + t.getClass().getSimpleName());
            return null;
        }
    }

    // ---------------------------------------------------------------- reine Funktionen (Tests)

    /** Erkennungen auf dem 2×2-Mosaik → Skizzen-Objekte. Kaesten ueber einer Bildgrenze fallen weg. */
    public static JSONArray objekte(List<Detection> dets, int breite, int hoehe) {
        JSONArray out = new JSONArray();
        if (dets == null || breite <= 1 || hoehe <= 1) return out;
        int qW = breite / 2, qH = hoehe / 2;
        List<JSONObject> l = new ArrayList<>();
        for (Detection det : dets) {
            if (det == null) continue;
            String typ = typ(det.getClassId());
            if (typ == null) continue;
            int x0 = det.getX(), y0 = det.getY(), x1 = x0 + det.getW(), y1 = y0 + det.getH();
            int spalte = (x0 + x1) / 2 >= qW ? 1 : 0;
            int zeile = (y0 + y1) / 2 >= qH ? 1 : 0;
            int qx = spalte * qW, qy = zeile * qH;
            if (x0 < qx - 2 || x1 > qx + qW + 2 || y0 < qy - 2 || y1 > qy + qH + 2) continue;  // Grenze
            int q = zeile * 2 + spalte;
            float rel = det.getH() / (float) qH;
            float mitte = ((x0 + x1) / 2f - qx) / qW;
            try {
                l.add(new JSONObject().put("seite", SEITEN[q]).put("quer", drittel(mitte))
                        .put("zone", zone(rel)).put("typ", typ));
            } catch (Exception ignored) { }
        }
        l.sort((a, b) -> Integer.compare(rang(a.optString("zone")), rang(b.optString("zone"))));
        for (int i = 0; i < l.size() && i < MAX_OBJEKTE; i++) out.put(l.get(i));
        return out;
    }

    static String typ(int classId) {
        switch (classId) {
            case 0: return "person";
            case 1: case 3: return "rad";
            case 2: return "auto";
            case 5: case 7: return "lkw";
            default: return null;
        }
    }

    /** Kastenhoehe relativ zum Kamerabild → Zone. Grob, ohne Kalibrierung, keine Meter. */
    static String zone(float relHoehe) {
        if (relHoehe >= NAH) return "nah";
        if (relHoehe >= MITTEL) return "mittel";
        return "fern";
    }

    /** Lage quer im Kamerabild: -1 linkes, 0 mittleres, 1 rechtes Drittel (Bildsicht, nicht Fahrzeugsicht). */
    static int drittel(float mitte01) {
        if (mitte01 < 1f / 3f) return -1;
        if (mitte01 > 2f / 3f) return 1;
        return 0;
    }

    private static int rang(String zone) {
        return "nah".equals(zone) ? 0 : "mittel".equals(zone) ? 1 : 2;
    }

    /** Leistungsurteil aus dem Mittel der Laufzeiten: moegliche Laeufe/s. */
    static String leistung(double moeglicheHz) {
        if (moeglicheHz <= 0) return "startet";
        if (moeglicheHz < MIN_HZ) return "leistung";
        if (moeglicheHz < LIVE_HZ) return "verzoegert";
        return "live";
    }

    /** Temperatur-Schalter mit Abstand (aus bei ≥ HEISS, wieder an bei ≤ KUEHL); null = unbekannt, Zustand bleibt. */
    static boolean warmNeu(boolean warmAlt, Integer grad) {
        if (grad == null) return warmAlt;
        if (grad >= HEISS_C) return true;
        if (grad <= KUEHL_C) return false;
        return warmAlt;
    }

    /** Status fuer den Launcher. */
    static String status(boolean an, boolean modellFehler, boolean gesperrt, boolean warm, long alterMs,
                         double moeglicheHz, long wartetMs) {
        if (!an) return "aus";
        if (modellFehler) return "modell";
        if (gesperrt) return "leistung";
        if (warm) return "warm";
        // M3: noch nie ein Ergebnis (z. B. keine Kamera) – nach der Frist nicht endlos „startet“.
        if (alterMs < 0) return wartetMs >= START_FRIST_MS ? "keine_erkennung" : "startet";
        if (alterMs > FRISCH_MS) return "keine_erkennung";
        String l = leistung(moeglicheHz);
        return "startet".equals(l) ? "live" : l;
    }

    // ---------------------------------------------------------------- Launcher-Seite

    /** GET: zaehlt als „Kachel sichtbar“ und liefert das letzte Ergebnis (nie aelter als FRISCH_MS als Objekte). */
    public static JSONObject abfrage() throws Exception {
        long now = jetzt();
        if (now - letzteAbfrage > SICHTBAR_MS) sichtbarSeit = now;
        letzteAbfrage = now;
        boolean an = schalterAn(now);
        double hz = moeglicheHz();
        boolean gesperrt = now < sperreBis;
        long alter = ergebnisAt == 0L ? -1L : now - ergebnisAt;
        long wartet = now - Math.max(sichtbarSeit, anSeit);
        String st = status(an, modellFehler, gesperrt, warm, alter, hz, wartet);
        JSONObject o = new JSONObject().put("an", an).put("status", st)
                .put("hz", Math.round(hz * 10) / 10.0);
        if ("live".equals(st) || "verzoegert".equals(st)) o.put("objekte", objekte).put("alterMs", alter);
        else o.put("objekte", new JSONArray());
        return o;
    }

    /** POST: Schalter. Ausschalten raeumt sofort auf. */
    public static boolean schalten(boolean an) {
        try {
            boolean ok = UnifiedConfigManager.updateSection(ABSCHNITT, new JSONObject().put(SCHALTER, an));
            if (ok) { if (an && !schalter) anSeit = jetzt(); schalter = an; schalterAt = jetzt(); }
            if (!an) aufraeumen();
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void aufraeumen() {
        objekte = new JSONArray();
        ergebnisAt = 0L;
        synchronized (messLock) { laufN = 0; }
    }

    private static boolean schalterAn(long now) {
        if (now - schalterAt > 2_000L) {
            boolean an = false;
            try {
                JSONObject l = UnifiedConfigManager.loadConfig().optJSONObject(ABSCHNITT);
                an = l != null && l.optBoolean(SCHALTER, false);
            } catch (Throwable ignored) { }
            if (schalter && !an) aufraeumen();
            if (an && !schalter) anSeit = now;
            schalter = an;
            schalterAt = now;
        }
        return schalter;
    }

    private static void messen(long ende, long ms) {
        synchronized (messLock) {
            int i = laufN % laufEnde.length;
            laufEnde[i] = ende;
            laufMs[i] = ms;
            laufN++;
            // Urteil erst nach einem vollen Messfenster sichtbarer Kachel
            if (sichtbarSeit != 0L && ende - sichtbarSeit >= MESSFENSTER_MS && laufN >= 10) {
                double hz = moeglicheHzLocked(ende);
                if (hz > 0 && hz < MIN_HZ) {
                    sperreBis = ende + LEISTUNG_SPERRE_MS;
                    logger.info(String.format(Locale.ROOT,
                            "Live-Umgebung: Leistung reicht nicht (%.1f Laeufe/s), Pause %d min", hz,
                            LEISTUNG_SPERRE_MS / 60_000L));
                    objekte = new JSONArray();
                }
            }
        }
    }

    private static double moeglicheHz() {
        synchronized (messLock) { return moeglicheHzLocked(jetzt()); }
    }

    /** Moegliche Laeufe/s = 1000 / mittlere Laufzeit (inkl. Taktgrenze) der letzten MESSFENSTER_MS. */
    private static double moeglicheHzLocked(long now) {
        int n = Math.min(laufN, laufEnde.length);
        long summe = 0; int k = 0;
        for (int i = 0; i < n; i++) {
            if (now - laufEnde[i] <= MESSFENSTER_MS) { summe += Math.max(laufMs[i], MIN_ABSTAND_MS); k++; }
        }
        return k == 0 ? 0 : 1000.0 * k / summe;
    }

    private static void temperaturPruefen() {
        long now = jetzt();
        if (now - temperaturAt < TEMPERATUR_ALLE_MS) return;
        temperaturAt = now;
        Integer g = hoechsteTemperatur();
        temperatur = g;
        boolean neu = warmNeu(warm, g);
        if (neu != warm) logger.info("Live-Umgebung: " + (neu ? "zu warm, Pause" : "abgekuehlt, weiter") + " (" + g + " °C)");
        warm = neu;
    }

    /** Hoechste plausible Temperatur aus /sys/class/thermal (Milligrad), null = unbekannt. */
    static Integer hoechsteTemperatur() {
        File[] zonen = new File("/sys/class/thermal").listFiles((dir, name) -> name.startsWith("thermal_zone"));
        if (zonen == null) return null;
        Integer max = null;
        for (File z : zonen) {
            Integer g = grad(lies(new File(z, "temp")));
            if (g != null && (max == null || g > max)) max = g;
        }
        return max;
    }

    /** „48000“ → 48; Werte ausserhalb 0..150 °C gelten als unplausibel. */
    static Integer grad(String roh) {
        if (roh == null) return null;
        try {
            long v = Long.parseLong(roh.trim());
            long c = v > 1000 ? v / 1000 : v;
            return c >= 0 && c <= 150 ? (int) c : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String lies(File f) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            return r.readLine();
        } catch (Throwable t) {
            return null;
        }
    }

    private static long jetzt() {
        return android.os.SystemClock.elapsedRealtime();
    }
}
