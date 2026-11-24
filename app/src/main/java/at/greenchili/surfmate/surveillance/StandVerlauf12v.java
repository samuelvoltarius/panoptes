package at.greenchili.surfmate.surveillance;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 0.1.43.21: 12-V- und SOC-Verlauf im Stand plus Warnung bei Dauer-Unterspannung (PRUEF_ADB_12V_20261124).
 *
 * <ul>
 *   <li><b>Verlauf:</b> geparkt alle {@link #TAKT_MS} ein Punkt {zeit, v12, soc}, lokal in einer Datei, Ringpuffer
 *       {@link #BEHALTEN_MS} (14 Tage). Nur frische 12-V-Werte ({@link #FRISCH_MS}); ohne frischen Wert bleibt v12 leer.</li>
 *   <li><b>Warnung:</b> liegt die 12-V-Spannung {@link #DAUER_MS} (30 min) lang durchgehend unter {@link #GRENZE_V}
 *       (12,2 V), geht einmal je Standphase eine Telegram-Meldung raus. Ein frischer Wert ≥ Grenze setzt die Dauer
 *       zurück; fehlende Werte unterbrechen sie nicht. Die Standphase ist der Park-Beginn des Wächter-Stopps; die
 *       Marke „schon gemeldet“ liegt auf Platte, damit ein Daemon-Neustart nicht erneut meldet.</li>
 * </ul>
 * Ändert nichts am Wächter oder am Wächter-Stopp: nur lesen, merken, melden.
 */
public final class StandVerlauf12v {

    public static final double GRENZE_V = 12.2;
    public static final long DAUER_MS = 30 * 60_000L;
    public static final long TAKT_MS = 10 * 60_000L;
    public static final long BEHALTEN_MS = 14L * 24 * 60 * 60_000L;
    /** Wie die Frischegrenze der Statusanzeige ({@code BatteryMonitor}): im Stand kommt alle ~90 s ein Wert. */
    public static final long FRISCH_MS = 150_000L;

    static final String VERLAUF_DATEI = at.greenchili.surfmate.util.DaemonStorage.rebase(
            "/data/user_de/0/com.android.shell/surfmate/stand_v12_verlauf.csv");
    static final String MARKE_DATEI = at.greenchili.surfmate.util.DaemonStorage.rebase(
            "/data/user_de/0/com.android.shell/surfmate/stand_v12_warnung.txt");

    /** Zustellung (in Tests ersetzbar). */
    interface Melder {
        void melden(double volt);
    }

    static volatile Melder melder = volt ->
            at.greenchili.surfmate.telegram.TelegramNotifier.notifyBattery12v(volt, "dauer");

    private final String verlaufDatei;
    private final String markeDatei;
    private long letzterPunktMs;
    private long unterSeitMs;
    private double tiefsterV = Double.NaN;
    private long phase;
    private Long gemeldetePhase;

    StandVerlauf12v(String verlaufDatei, String markeDatei) {
        this.verlaufDatei = verlaufDatei;
        this.markeDatei = markeDatei;
    }

    private static volatile StandVerlauf12v instanz;

    static StandVerlauf12v instanz() {
        StandVerlauf12v s = instanz;
        if (s == null) {
            synchronized (StandVerlauf12v.class) {
                if (instanz == null) instanz = new StandVerlauf12v(VERLAUF_DATEI, MARKE_DATEI);
                s = instanz;
            }
        }
        return s;
    }

    static boolean frisch(double v12, long v12AtMs, long jetzt) {
        return Double.isFinite(v12) && v12 > 5 && v12 < 20 && v12AtMs > 0
                && jetzt >= v12AtMs && jetzt - v12AtMs <= FRISCH_MS;
    }

    /**
     * Ein Takt im Stand (vom Wächter-Stopp-Takt, ACC aus belegt).
     *
     * @param parkedSinceMs Beginn der Standphase
     * @return true, wenn in diesem Takt gewarnt wurde
     */
    synchronized boolean takt(long jetzt, long parkedSinceMs, double v12, long v12AtMs, double soc) {
        if (parkedSinceMs <= 0L) return false;
        if (parkedSinceMs != phase) {
            phase = parkedSinceMs;
            unterSeitMs = 0L;
            tiefsterV = Double.NaN;
        }
        boolean frisch = frisch(v12, v12AtMs, jetzt);
        if (letzterPunktMs <= 0L || jetzt - letzterPunktMs >= TAKT_MS || jetzt < letzterPunktMs) {
            letzterPunktMs = jetzt;
            punktSchreiben(jetzt, frisch ? v12 : Double.NaN, soc);
        }
        if (!frisch) return false;
        if (v12 >= GRENZE_V) {
            unterSeitMs = 0L;
            tiefsterV = Double.NaN;
            return false;
        }
        if (unterSeitMs <= 0L) unterSeitMs = Math.min(v12AtMs, jetzt);
        tiefsterV = Double.isNaN(tiefsterV) ? v12 : Math.min(tiefsterV, v12);
        if (jetzt - unterSeitMs < DAUER_MS) return false;
        if (gemeldetePhase == null) gemeldetePhase = leseMarke();
        if (gemeldetePhase == phase) return false;
        gemeldetePhase = phase;
        schreibeMarke(phase);
        melder.melden(tiefsterV);
        return true;
    }

    // ------------------------------------------------------------------ Ringpuffer

    static String zeile(long ms, double v12, double soc) {
        return ms + ";" + (Double.isNaN(v12) ? "" : String.format(Locale.ROOT, "%.2f", v12)) + ";"
                + (Double.isNaN(soc) || soc <= 0 || soc > 100 ? "" : String.format(Locale.ROOT, "%.1f", soc));
    }

    /** Behalten: nur Zeilen jünger als {@link #BEHALTEN_MS}, älteste zuerst. */
    static List<String> beschneiden(List<String> zeilen, long jetzt) {
        List<String> out = new ArrayList<>();
        for (String z : zeilen) {
            int i = z.indexOf(';');
            if (i <= 0) continue;
            try {
                long ms = Long.parseLong(z.substring(0, i));
                if (ms <= jetzt && jetzt - ms <= BEHALTEN_MS) out.add(z);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private void punktSchreiben(long jetzt, double v12, double soc) {
        try {
            File f = new File(verlaufDatei);
            List<String> alt = f.isFile()
                    ? Files.readAllLines(f.toPath(), StandardCharsets.UTF_8) : new ArrayList<String>();
            List<String> neu = beschneiden(alt, jetzt);
            neu.add(zeile(jetzt, v12, soc));
            File tmp = new File(verlaufDatei + ".tmp");
            Files.write(tmp.toPath(), neu, StandardCharsets.UTF_8);
            if (!tmp.renameTo(f)) {
                Files.write(f.toPath(), neu, StandardCharsets.UTF_8);
                tmp.delete();
            }
        } catch (Throwable ignored) {
            // Verlauf ist Beiwerk: nie den Takt stören
        }
    }

    /** Gespeicherter Verlauf (Zeilen „ms;v12;soc“, älteste zuerst); leer, wenn keiner. */
    public static List<String> verlauf() {
        try {
            File f = new File(instanz().verlaufDatei);
            if (!f.isFile()) return new ArrayList<>();
            return beschneiden(Files.readAllLines(f.toPath(), StandardCharsets.UTF_8), System.currentTimeMillis());
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    private long leseMarke() {
        try {
            File f = new File(markeDatei);
            if (!f.isFile()) return 0L;
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? 0L : Long.parseLong(s);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private void schreibeMarke(long p) {
        try {
            Files.write(new File(markeDatei).toPath(), Long.toString(p).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }
}
