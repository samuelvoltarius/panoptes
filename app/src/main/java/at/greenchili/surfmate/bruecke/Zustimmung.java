package at.greenchili.surfmate.bruecke;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 0.1.24 Zustimmung zur Brücke (28.10.: „mach das so“): ein Satz mit Häkchen, gesetzt bevor die Brücke angeht –
 * am Display (Einrichtung „Handy verbinden“) und in der Web-Einstellung. Gespeichert werden Zeit, Textversion und Quelle
 * ({@code bruecke.zustimmung}); „Brücke aus“ nimmt sie zurück. Ändert sich der Satz, steigt {@link #TEXT_VERSION} und
 * es wird neu gefragt.
 *
 * <p>Bestand (Brücke schon an, ohne Zustimmung): einmal nachfragen, die Brücke bleibt bis dahin an.
 * Eigene Datei neben {@code bruecke.json}, damit der Brücken-Zustand (Schlüssel, Postfach) unberührt bleibt.
 */
public final class Zustimmung {

    /** Version des Zustimmungssatzes (web i18n {@code system.bridge_consent}, Display {@code einr_handy_zustimmung}). */
    public static final int TEXT_VERSION = 1;

    public interface Platte {
        JSONObject lade();
        boolean speichere(JSONObject z);
        boolean loesche();
    }

    private final Platte platte;

    Zustimmung(Platte platte) {
        this.platte = platte;
    }

    /** Quelle: kurz, nur harmlose Zeichen (z. B. „einrichtung“, „web“, „koordination 28.10.“). */
    static boolean quelleOk(String q) {
        return q != null && q.matches("[a-z0-9 .\\-]{1,40}");
    }

    /** Gespeicherte Zustimmung {@code {zeit, version, quelle}} oder null. */
    public synchronized JSONObject lese() {
        JSONObject z = platte.lade();
        return z == null || !z.has("zeit") ? null : z;
    }

    /** Gilt nur für den aktuellen Satz. */
    public synchronized boolean erteilt() {
        JSONObject z = lese();
        return z != null && z.optInt("version", 0) == TEXT_VERSION;
    }

    public synchronized boolean setzen(long zeit, String quelle) {
        if (!quelleOk(quelle)) return false;
        try {
            return platte.speichere(new JSONObject().put("zeit", zeit).put("version", TEXT_VERSION).put("quelle", quelle));
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized void zuruecknehmen() {
        platte.loesche();
    }

    /**
     * Für {@code GET /api/bruecke}: {@code zustimmung} ({zeit, version, quelle} oder null) und {@code zustimmungFehlt}
     * (Brücke an, aber keine gültige Zustimmung → einmal nachfragen, nicht abschalten).
     */
    public synchronized void inStatus(JSONObject status, boolean ein) throws Exception {
        JSONObject z = lese();
        status.put("zustimmung", z == null ? JSONObject.NULL : z);
        status.put("zustimmungFehlt", ein && !erteilt());
        status.put("zustimmungVersion", TEXT_VERSION);
    }

    // ------------------------------------------------------------------ Laufzeit

    private static volatile Zustimmung instanz;

    public static Zustimmung get() {
        Zustimmung z = instanz;
        if (z == null) {
            synchronized (Zustimmung.class) {
                if (instanz == null) instanz = new Zustimmung(new DateiPlatte());
                z = instanz;
            }
        }
        return z;
    }

    static final class DateiPlatte implements Platte {
        private final File datei = new File(at.greenchili.surfmate.util.DaemonStorage.path("bruecke_zustimmung.json"));

        public JSONObject lade() {
            try {
                if (!datei.isFile()) return null;
                return new JSONObject(new String(Files.readAllBytes(datei.toPath()), StandardCharsets.UTF_8));
            } catch (Exception e) {
                return null;
            }
        }

        public boolean speichere(JSONObject z) {
            try {
                File tmp = new File(datei.getPath() + ".tmp");
                Files.write(tmp.toPath(), z.toString().getBytes(StandardCharsets.UTF_8));
                return tmp.renameTo(datei);
            } catch (Exception e) {
                return false;
            }
        }

        public boolean loesche() {
            return !datei.exists() || datei.delete();
        }
    }
}
