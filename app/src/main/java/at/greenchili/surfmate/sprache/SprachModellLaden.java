package at.greenchili.surfmate.sprache;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 0.1.44 (P2b) Sprachmodell laden – nur auf Tipp, nie automatisch. 0.1.43.2 (Fix E2E M2): läuft in der App
 * ({@link SprachModellDienst}, eigener Ordner der App, dort liest auch die Erkennung), ausgelöst nur vom Kern nach
 * {@code POST /api/sprache/v1/modell {aktion:"laden"}} ({@code SprachModellKern}).
 *
 * <ul>
 *   <li>Nur von der offiziellen Vosk-Quelle: feste URL je Modell ({@link #MODELLE}), nichts anderes.</li>
 *   <li>Fest eingetragene SHA-256 je Modell; weicht die Datei ab, wird sie verworfen (gelöscht) und nichts entpackt.</li>
 *   <li>Vorher Speicherplatz-Prüfung (Datei + entpackt + Reserve); zu wenig = es wird gar nichts geladen.</li>
 *   <li>Fortschritt und Abbruch; Entpacken nur in den eigenen Modellordner (keine Pfade außerhalb, keine „..“).</li>
 *   <li>Die Ablage von Hand (z. B. {@code adb push}) bleibt der andere Weg; sie wird nie überschrieben.</li>
 * </ul>
 */
public final class SprachModellLaden {

    /** Ein ladbares Modell (Werte am 13.11.2026 von der Quelle geladen und geprüft). */
    public static final class Modell {
        public final String sprache, name, url, sha256, lizenz;
        public final long zipBytes, entpacktBytes;

        Modell(String sprache, String name, String sha256, long zipBytes, long entpacktBytes, String lizenz) {
            this.sprache = sprache;
            this.name = name;
            this.url = QUELLE + name + ".zip";
            this.sha256 = sha256;
            this.zipBytes = zipBytes;
            this.entpacktBytes = entpacktBytes;
            this.lizenz = lizenz;
        }

        public long mb() { return Math.round(zipBytes / 1_000_000.0); }
    }

    /** Offizielle Quelle der Vosk-Modelle (Alpha Cephei). */
    public static final String QUELLE = "https://alphacephei.com/vosk/models/";
    /** Puffer über Datei + entpackt hinaus, damit das Gerät nie voll läuft. */
    public static final long RESERVE = 50_000_000L;

    public static final Map<String, Modell> MODELLE;
    static {
        Map<String, Modell> m = new LinkedHashMap<>();
        // DE: COPYING im Archiv = Apache-2.0 (gleiches Modell wie Weckwort-Messung Stufe 1, gleiche Prüfsumme).
        m.put("de", new Modell("de", "vosk-model-small-de-0.15",
                "b7e53c90b1f0a38456f4cd62b366ecd58803cd97cd42b06438e2c131713d5e43", 46_499_967L, 95_621_761L, "Apache-2.0"));
        // EN: Lizenz Apache 2.0 laut offizieller Modellliste (alphacephei.com/vosk/models); im Archiv liegt KEIN COPYING,
        // nur README „Copyright 2020 Alpha Cephei Inc“ (THIRD_PARTY_NOTICES.md).
        m.put("en", new Modell("en", "vosk-model-small-en-us-0.15",
                "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498", 41_205_931L, 70_898_967L, "Apache-2.0"));
        MODELLE = Collections.unmodifiableMap(m);
    }

    /** Netz (im Test nachgebaut). Wird NUR aus {@link #laden} gerufen. */
    interface Netz {
        InputStream oeffnen(String url) throws IOException;
    }

    /** Freier Platz (im Test nachgebaut). */
    interface Platz {
        long frei(File ordner);
    }

    /** Nur die feste Vosk-Quelle (HTTPS). */
    static final Netz NETZ_QUELLE = new Echt();
    static volatile Netz netz = NETZ_QUELLE;
    static volatile Platz platz = File::getUsableSpace;
    /** Modellordner der App; {@link SprachModellDienst} setzt {@code getExternalFilesDir("sprache")} (dort liest die Erkennung). */
    static volatile File basis = new File("/storage/emulated/0/Android/data/at.greenchili.surfmate/files/" + SprachErkenner.ORDNER);

    private static final Object LOCK = new Object();
    private static String ladeSprache = "", phase = "", grund = "";
    private static long bytes, gesamt;

    private SprachModellLaden() {}

    /** 0.1.43.3: für den Launcher-Katalog (nur lesen). */
    public static boolean istDa(Modell m) { return da(m); }

    /** Ist das Modell schon da (geladen oder von Hand abgelegt)? */
    static boolean da(Modell m) {
        File o = new File(basis, m.name);
        return new File(o, "am/final.mdl").isFile() && new File(o, "conf/model.conf").isFile();
    }

    /** null = darf laden; sonst Grund (schon da, kein Platz). Ohne Netz. */
    static String vorpruefen(Modell m) {
        if (da(m)) return "schon_da";
        if (!basis.isDirectory() && !basis.mkdirs()) return "ordner";
        long noetig = m.zipBytes + m.entpacktBytes + RESERVE;
        if (platz.frei(basis) < noetig) return "kein_platz";
        return null;
    }

    /** Stand für die Oberfläche: je Modell vorhanden/Größe, dazu der laufende oder letzte Ladevorgang. */
    public static JSONObject stand() throws Exception {
        JSONObject mo = new JSONObject();
        for (Modell m : MODELLE.values()) {
            mo.put(m.sprache, new JSONObject().put("name", m.name).put("da", da(m)).put("mb", m.mb())
                    .put("noetigMb", Math.round((m.zipBytes + m.entpacktBytes + RESERVE) / 1_000_000.0)).put("lizenz", m.lizenz));
        }
        JSONObject l = new JSONObject();
        synchronized (LOCK) {
            l.put("sprache", ladeSprache).put("phase", phase).put("grund", grund).put("bytes", bytes).put("gesamt", gesamt);
        }
        return new JSONObject().put("ok", true).put("modelle", mo).put("laden", l);
    }

    /** Fehler vor dem Laden (z. B. Ordner, Platz) für die Meldung an den Kern. */
    static void fehler(String sprache, String grund) {
        setze(sprache == null ? "" : sprache, "fehler", grund, 0, 0);
    }

    private static void setze(String s, String p, String g, long b, long ges) {
        synchronized (LOCK) {
            ladeSprache = s; phase = p; grund = g; bytes = b; gesamt = ges;
        }
    }

    /**
     * Laden, prüfen, entpacken (synchron; {@link SprachModellDienst} ruft es in der App). Ergebnis-Phase: {@code fertig},
     * {@code abgebrochen} oder {@code fehler} mit Grund {@code pruefsumme}, {@code groesse}, {@code netz}, {@code entpacken}.
     */
    static String laden(Modell m, AtomicBoolean abbruch) {
        File zip = new File(basis, "." + m.name + ".zip.laden");
        File staging = new File(basis, "." + m.name + ".entpacken");
        setze(m.sprache, "laedt", "", 0, m.zipBytes);
        try {
            String g = herunterladen(m, zip, abbruch);
            if (g == null) {
                setze(m.sprache, "prueft", "", m.zipBytes, m.zipBytes);
                g = pruefsumme(zip).equals(m.sha256) ? null : "pruefsumme";
            }
            if (g == null) {
                setze(m.sprache, "entpackt", "", m.zipBytes, m.zipBytes);
                loeschen(staging);
                g = entpacken(m, zip, staging, abbruch);
            }
            if (g == null) {
                File ziel = new File(basis, m.name);
                if (da(m)) g = "schon_da";   // inzwischen von Hand abgelegt: nie überschreiben
                else {
                    loeschen(ziel);   // nur ein unvollständiger Rest (kein am/final.mdl oder conf/model.conf)
                    if (!new File(staging, m.name).renameTo(ziel)) g = "entpacken";
                }
            }
            String ende = g == null ? "fertig" : "abgebrochen".equals(g) ? "abgebrochen" : "fehler";
            setze(m.sprache, ende, g == null || "abgebrochen".equals(g) ? "" : g, m.zipBytes, m.zipBytes);
            return ende;
        } catch (Throwable t) {
            setze(m.sprache, "fehler", "netz", 0, m.zipBytes);
            return "fehler";
        } finally {
            // Verworfen bzw. fertig: Download-Datei und Zwischenordner immer weg.
            zip.delete();
            loeschen(staging);
        }
    }

    private static String herunterladen(Modell m, File zip, AtomicBoolean abbruch) throws IOException {
        long n = 0;
        try (InputStream in = new BufferedInputStream(netz.oeffnen(m.url)); OutputStream out = new FileOutputStream(zip)) {
            byte[] puffer = new byte[64 * 1024];
            for (int k; (k = in.read(puffer)) > 0; ) {
                if (abbruch.get()) return "abgebrochen";
                n += k;
                if (n > m.zipBytes) return "groesse";   // mehr als erwartet: nicht weiter laden
                out.write(puffer, 0, k);
                setze(m.sprache, "laedt", "", n, m.zipBytes);
            }
        }
        return n == m.zipBytes ? null : "groesse";
    }

    static String pruefsumme(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] puffer = new byte[64 * 1024];
            for (int k; (k = in.read(puffer)) > 0; ) md.update(puffer, 0, k);
        }
        StringBuilder s = new StringBuilder();
        for (byte b : md.digest()) s.append(String.format(Locale.ROOT, "%02x", b & 255));
        return s.toString();
    }

    /** Entpacken nur unter {@code <name>/…}; keine absoluten Pfade, kein „..“, Größe begrenzt. */
    static String entpacken(Modell m, File zip, File staging, AtomicBoolean abbruch) throws IOException {
        long summe = 0;
        String wurzel = staging.getCanonicalPath() + File.separator;
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(new java.io.FileInputStream(zip)))) {
            byte[] puffer = new byte[64 * 1024];
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if (abbruch.get()) return "abgebrochen";
                String n = e.getName().replace('\\', '/');
                if (!n.startsWith(m.name + "/") || n.contains("..") || n.startsWith("/")) return "entpacken";
                File f = new File(staging, n);
                if (!f.getCanonicalPath().startsWith(wurzel)) return "entpacken";
                if (e.isDirectory()) { if (!f.isDirectory() && !f.mkdirs()) return "entpacken"; continue; }
                File eltern = f.getParentFile();
                if (eltern != null && !eltern.isDirectory() && !eltern.mkdirs()) return "entpacken";
                try (OutputStream out = new FileOutputStream(f)) {
                    for (int k; (k = z.read(puffer)) > 0; ) {
                        summe += k;
                        if (summe > m.entpacktBytes) return "entpacken";
                        out.write(puffer, 0, k);
                    }
                }
            }
        }
        return null;
    }

    static void loeschen(File f) {
        if (f == null || !f.exists()) return;
        File[] k = f.listFiles();
        if (k != null) for (File x : k) loeschen(x);
        f.delete();
    }

    /** Nur HTTPS zur festen Quelle; Weiterleitungen nur innerhalb von HTTPS (die Prüfsumme sichert den Inhalt). */
    private static final class Echt implements Netz {
        @Override
        public InputStream oeffnen(String url) throws IOException {
            if (!url.startsWith(QUELLE)) throw new IOException("quelle");
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(15_000);
            c.setReadTimeout(30_000);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) { c.disconnect(); throw new IOException("http"); }
            if (!"https".equals(c.getURL().getProtocol())) { c.disconnect(); throw new IOException("quelle"); }
            return c.getInputStream();
        }
    }
}
