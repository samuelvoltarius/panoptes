package at.greenchili.surfmate.weckwort;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Weckwort-Messung Stufe 1: sammelt NUR Zahlen (CPU, RAM, Latenz, Treffer, Mikrofon-Zustand).
 * Kein Audio, kein Text aus der Erkennung außer dem festen Messwort, keine Orte.
 *
 * <p>Rein und ohne Android, damit die Auswertung im Unit-Test geprüft werden kann. Nicht thread-sicher:
 * Schreiben nur aus dem Mess-Thread, Lesen über {@link #alsJson} unter dem Lock des Aufrufers.
 */
public final class WeckwortMessStatistik {

    /** Auflösung der Latenz-Verteilung: 0,1 ms je Fach, bis 500 ms; darüber zählt nur das Maximum. */
    static final int FAECHER = 5000;
    static final int MAX_TREFFER_ZEITEN = 200;

    final String baustein;
    final String modell;
    final String messwort;
    final String quelle;
    final int abtastrate;
    final int stueckMs;

    long startMs;
    long endeMs;
    String status = "bereit";
    String grund;

    // Mikrofon
    boolean mikrofonOffen;
    String mikrofonFehler;
    long stuecke;
    long audioMs;
    long nullStuecke;
    long stummMeldungen;
    int fremdeAufnahmenMax;
    int fremdeAufnahmenLetzt;
    final Map<Integer, Long> leseFehler = new TreeMap<>();
    double pegelSumme;
    long pegelAnzahl;

    // Erkenner
    long ladeMs = -1;
    String erkennerFehler;
    final int[] latenzFaecher = new int[FAECHER];
    long latenzAnzahl;
    double latenzSummeMs;
    double latenzMaxMs;
    double erkennerSummeMs;

    // CPU
    long threadCpuMs;
    long prozessCpuStartMs = -1;
    long prozessCpuLetztMs = -1;
    long prozessWandStartMs = -1;
    long prozessWandLetztMs = -1;
    int kerne = 1;

    // RAM (PSS in kB)
    long pssVorherKb = -1;
    long pssMaxKb = -1;
    long pssLetztKb = -1;
    long nativeHeapKb = -1;

    // Treffer
    long treffer;
    final long[] trefferSekunden = new long[MAX_TREFFER_ZEITEN];
    int trefferGemerkt;

    public WeckwortMessStatistik(String baustein, String modell, String messwort, String quelle,
                                 int abtastrate, int stueckMs) {
        this.baustein = baustein;
        this.modell = modell;
        this.messwort = messwort;
        this.quelle = quelle;
        this.abtastrate = abtastrate;
        this.stueckMs = stueckMs;
    }

    /** Ein gelesenes Stück Audio (nur Kennzahlen): Anzahl Samples, ob alles 0 war, Pegel in dBFS. */
    public void stueck(int samples, boolean alleNull, double pegelDbfs) {
        stuecke++;
        audioMs += samples * 1000L / Math.max(1, abtastrate);
        if (alleNull) nullStuecke++;
        if (!Double.isNaN(pegelDbfs) && !Double.isInfinite(pegelDbfs)) {
            pegelSumme += pegelDbfs;
            pegelAnzahl++;
        }
    }

    /** AudioRecord.read lieferte einen Fehlercode (negativ) oder 0 Samples. */
    public void leseFehler(int code) {
        Long n = leseFehler.get(code);
        leseFehler.put(code, n == null ? 1L : n + 1L);
    }

    /** Verarbeitungszeit des Erkenners für ein Stück (Wandzeit). */
    public void verarbeitung(double ms) {
        if (ms < 0 || Double.isNaN(ms)) return;
        latenzAnzahl++;
        latenzSummeMs += ms;
        erkennerSummeMs += ms;
        if (ms > latenzMaxMs) latenzMaxMs = ms;
        int fach = (int) Math.floor(ms * 10.0);
        if (fach >= 0 && fach < FAECHER) latenzFaecher[fach]++;
    }

    /** Ein Treffer des Messworts nach {@code sekunde} Sekunden Laufzeit. */
    public void treffer(long sekunde) {
        treffer++;
        if (trefferGemerkt < MAX_TREFFER_ZEITEN) trefferSekunden[trefferGemerkt++] = sekunde;
    }

    public void prozessCpu(long cpuMs, long wandMs) {
        if (prozessCpuStartMs < 0) {
            prozessCpuStartMs = cpuMs;
            prozessWandStartMs = wandMs;
        }
        prozessCpuLetztMs = cpuMs;
        prozessWandLetztMs = wandMs;
    }

    public void pss(long kb) {
        if (kb <= 0) return;
        if (pssVorherKb < 0) pssVorherKb = kb;
        pssLetztKb = kb;
        if (kb > pssMaxKb) pssMaxKb = kb;
    }

    /** Perzentil der Verarbeitungszeit in ms (0..1), -1 ohne Werte. Über 500 ms zählt das Maximum. */
    double perzentil(double p) {
        if (latenzAnzahl == 0) return -1;
        long ziel = (long) Math.ceil(p * latenzAnzahl);
        if (ziel < 1) ziel = 1;
        long summe = 0;
        for (int i = 0; i < FAECHER; i++) {
            summe += latenzFaecher[i];
            if (summe >= ziel) return (i + 1) / 10.0;
        }
        return latenzMaxMs;
    }

    long laufzeitMs(long jetztMs) {
        if (startMs <= 0) return 0;
        long ende = endeMs > 0 ? endeMs : jetztMs;
        return Math.max(0, ende - startMs);
    }

    static double runde(double v, int stellen) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return -1;
        double f = Math.pow(10, stellen);
        return Math.round(v * f) / f;
    }

    /** Fehlauslösungen je Stunde: Treffer, wenn niemand das Messwort sagt (siehe Messplan). */
    double trefferJeStunde(long jetztMs) {
        long ms = laufzeitMs(jetztMs);
        if (ms < 60_000L) return -1;
        return treffer * 3_600_000.0 / ms;
    }

    public JSONObject alsJson(long jetztMs) throws Exception {
        JSONObject o = new JSONObject();
        o.put("version", 1);
        o.put("art", "weckwort-messung");
        o.put("status", status);
        if (grund != null) o.put("grund", grund);
        o.put("baustein", baustein);
        o.put("modell", modell);
        o.put("messwort", messwort);
        o.put("startMs", startMs);
        if (endeMs > 0) o.put("endeMs", endeMs);
        long lauf = laufzeitMs(jetztMs);
        o.put("laufzeitS", lauf / 1000L);
        o.put("datenschutz", "nur Zahlen, kein Audio gespeichert oder gesendet");

        JSONObject mik = new JSONObject();
        mik.put("quelle", quelle);
        mik.put("abtastrate", abtastrate);
        mik.put("offen", mikrofonOffen);
        if (mikrofonFehler != null) mik.put("fehler", mikrofonFehler);
        mik.put("stuecke", stuecke);
        mik.put("audioS", audioMs / 1000L);
        mik.put("nullStuecke", nullStuecke);
        mik.put("nullAnteil", stuecke == 0 ? 0 : runde(nullStuecke / (double) stuecke, 4));
        JSONObject lf = new JSONObject();
        long lfSumme = 0;
        for (Map.Entry<Integer, Long> e : leseFehler.entrySet()) {
            lf.put(String.valueOf(e.getKey()), e.getValue());
            lfSumme += e.getValue();
        }
        mik.put("leseFehler", lf);
        mik.put("leseFehlerSumme", lfSumme);
        mik.put("stummMeldungen", stummMeldungen);
        mik.put("fremdeAufnahmenMax", fremdeAufnahmenMax);
        mik.put("fremdeAufnahmenLetzt", fremdeAufnahmenLetzt);
        mik.put("pegelDbfsMittel", pegelAnzahl == 0 ? JSONObject.NULL : runde(pegelSumme / pegelAnzahl, 1));
        o.put("mikrofon", mik);

        JSONObject cpu = new JSONObject();
        cpu.put("kerne", kerne);
        double threadEinKern = lauf <= 0 ? -1 : threadCpuMs * 100.0 / lauf;
        cpu.put("messThreadProzentEinKern", runde(threadEinKern, 2));
        cpu.put("messThreadProzentGesamt", threadEinKern < 0 ? -1 : runde(threadEinKern / Math.max(1, kerne), 2));
        long pw = prozessWandLetztMs - prozessWandStartMs;
        if (prozessCpuStartMs >= 0 && pw > 0) {
            double proz = (prozessCpuLetztMs - prozessCpuStartMs) * 100.0 / pw;
            cpu.put("appProzessProzentEinKern", runde(proz, 2));
            cpu.put("appProzessProzentGesamt", runde(proz / Math.max(1, kerne), 2));
        } else {
            cpu.put("appProzessProzentEinKern", -1);
            cpu.put("appProzessProzentGesamt", -1);
        }
        o.put("cpu", cpu);

        JSONObject ram = new JSONObject();
        ram.put("pssVorherKb", pssVorherKb);
        ram.put("pssMaxKb", pssMaxKb);
        ram.put("pssLetztKb", pssLetztKb);
        ram.put("zuwachsMaxKb", pssVorherKb < 0 || pssMaxKb < 0 ? -1 : pssMaxKb - pssVorherKb);
        ram.put("nativeHeapKb", nativeHeapKb);
        o.put("ram", ram);

        JSONObject lat = new JSONObject();
        lat.put("modellLadenMs", ladeMs);
        lat.put("stueckMs", stueckMs);
        lat.put("anzahl", latenzAnzahl);
        lat.put("mittelMs", latenzAnzahl == 0 ? -1 : runde(latenzSummeMs / latenzAnzahl, 2));
        lat.put("p50Ms", runde(perzentil(0.50), 1));
        lat.put("p95Ms", runde(perzentil(0.95), 1));
        lat.put("p99Ms", runde(perzentil(0.99), 1));
        lat.put("maxMs", runde(latenzMaxMs, 1));
        lat.put("echtzeitFaktor", audioMs <= 0 || latenzAnzahl == 0 ? -1 : runde(erkennerSummeMs / audioMs, 4));
        if (erkennerFehler != null) lat.put("erkennerFehler", erkennerFehler);
        o.put("latenz", lat);

        JSONObject tr = new JSONObject();
        tr.put("anzahl", treffer);
        tr.put("jeStunde", runde(trefferJeStunde(jetztMs), 2));
        JSONArray zeiten = new JSONArray();
        for (int i = 0; i < trefferGemerkt; i++) zeiten.put(trefferSekunden[i]);
        tr.put("sekundenSeitStart", zeiten);
        o.put("treffer", tr);
        return o;
    }

    /** Pegel eines 16-bit-Stücks in dBFS (RMS); -120 bei Stille. Nur die Zahl bleibt, das Audio nicht. */
    public static double pegelDbfs(short[] puffer, int n) {
        if (n <= 0) return -120;
        double summe = 0;
        for (int i = 0; i < n; i++) {
            double s = puffer[i] / 32768.0;
            summe += s * s;
        }
        double rms = Math.sqrt(summe / n);
        if (rms <= 1e-6) return -120;
        return 20.0 * Math.log10(rms);
    }

    public static boolean alleNull(short[] puffer, int n) {
        for (int i = 0; i < n; i++) if (puffer[i] != 0) return false;
        return true;
    }

    static String prozent(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
