package at.greenchili.surfmate.ha;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Datei der HA-Geräte ({@link HaGeraete}): nur der Kamera-Daemon (uid 2000, HttpServer) liest und schreibt sie, daher
 * 0600 wie {@code bruecke.json}. Gerätenamen und Räume sind privat und gehören nicht in die 0666-Gesamt-Config.
 */
public final class HaGeraeteSpeicher {

    private final File datei;
    private JSONObject bestand;

    public HaGeraeteSpeicher(File datei) {
        this.datei = datei;
    }

    private static volatile HaGeraeteSpeicher instanz;

    public static HaGeraeteSpeicher get() {
        if (instanz == null) {
            synchronized (HaGeraeteSpeicher.class) {
                if (instanz == null) {
                    instanz = new HaGeraeteSpeicher(new File(at.greenchili.surfmate.util.DaemonStorage.path("ha_geraete.json")));
                }
            }
        }
        return instanz;
    }

    public synchronized JSONObject lies() {
        if (bestand != null) return bestand;
        try {
            if (datei.exists()) {
                bestand = new JSONObject(new String(Files.readAllBytes(datei.toPath()), StandardCharsets.UTF_8));
                return bestand;
            }
        } catch (Exception ignored) {
            // kaputt: wie leer (nichts freigegeben)
        }
        return null;
    }

    public synchronized boolean schreibe(JSONObject neu) {
        try {
            File tmp = new File(datei.getPath() + ".tmp");
            Files.write(tmp.toPath(), neu.toString().getBytes(StandardCharsets.UTF_8));
            //noinspection ResultOfMethodCallIgnored
            tmp.setReadable(false, false);
            //noinspection ResultOfMethodCallIgnored
            tmp.setReadable(true, true);
            //noinspection ResultOfMethodCallIgnored
            tmp.setWritable(false, false);
            //noinspection ResultOfMethodCallIgnored
            tmp.setWritable(true, true);
            if (!tmp.renameTo(datei)) {
                //noinspection ResultOfMethodCallIgnored
                datei.delete();
                if (!tmp.renameTo(datei)) return false;
            }
            bestand = neu;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Beim Widerruf der HA-Kopplung bzw. Abschalten des Rechts: Liste und Freigaben weg. */
    public synchronized void loeschen() {
        bestand = null;
        //noinspection ResultOfMethodCallIgnored
        datei.delete();
    }
}
