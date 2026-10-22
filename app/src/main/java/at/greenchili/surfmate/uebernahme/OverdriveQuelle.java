package at.greenchili.surfmate.uebernahme;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Findet die Daten einer früheren Overdrive-Installation am Auto und liest sie <b>nur lesend</b>
 * (LADEN_VERTRAG.md Teil B). Es werden ausschließlich die Dateinamen geöffnet, die die Abbildungstabelle
 * kennt; nichts wird dort geschrieben, gelöscht oder umbenannt, nichts verlässt das Auto.
 */
public final class OverdriveQuelle {

    /** Ablageort der Overdrive-Daten (als shell lesbar). */
    public static final String STANDARD_ORDNER = "/data/local/tmp";
    static final long MAX_BYTES = 512 * 1024;

    public static final String NICHT_INSTALLIERT = "nicht_installiert";
    public static final String KEINE_DATEN = "keine_daten";
    public static final String NICHT_LESBAR = "nicht_lesbar";

    /** Ergebnis der Erkennung: gelesene Dateien (Name → Text) oder Grund, warum nichts da ist. */
    public static final class Befund {
        public final Map<String, String> dateien;
        public final String grund;
        Befund(Map<String, String> dateien, String grund) { this.dateien = dateien; this.grund = grund; }
        public boolean vorhanden() { return grund == null && !dateien.isEmpty(); }
        public Collection<String> namen() { return new TreeSet<>(dateien.keySet()); }
    }

    private OverdriveQuelle() {}

    /**
     * @param ordner         Ablageort (Test: temporärer Ordner)
     * @param namen          bekannte Dateinamen aus der Abbildungstabelle
     * @param paketBekannt   Overdrive-Paket am Gerät gefunden (installiert oder deaktiviert)
     */
    public static Befund erkenne(File ordner, Collection<String> namen, boolean paketBekannt) {
        Map<String, String> gelesen = new LinkedHashMap<>();
        boolean irgendeineDa = false;
        for (String n : new TreeSet<>(namen)) {
            if (n.contains("/") || n.contains("..")) continue;   // nur flache, bekannte Namen
            File f = new File(ordner, n);
            if (!f.isFile()) continue;
            irgendeineDa = true;
            String text = lies(f);
            if (text != null) gelesen.put(n, text);
        }
        if (!gelesen.isEmpty()) return new Befund(gelesen, null);
        if (irgendeineDa) return new Befund(gelesen, NICHT_LESBAR);
        return new Befund(gelesen, paketBekannt ? KEINE_DATEN : NICHT_INSTALLIERT);
    }

    /** Liest höchstens {@link #MAX_BYTES}; zu groß oder nicht lesbar → null. Öffnet nur zum Lesen. */
    static String lies(File f) {
        if (!f.canRead() || f.length() > MAX_BYTES) return null;
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                b.write(buf, 0, n);
                if (b.size() > MAX_BYTES) return null;
            }
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** Overdrive-Paket am Gerät bekannt? (Paketliste des Systems, nur lesen; Fehler → false.) */
    public static boolean paketBekannt() {
        try {
            Process p = new ProcessBuilder("pm", "path", "com.overdrive.app").redirectErrorStream(true).start();
            boolean fertig = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!fertig) {
                p.destroy();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Throwable t) {
            return false;
        }
    }
}
