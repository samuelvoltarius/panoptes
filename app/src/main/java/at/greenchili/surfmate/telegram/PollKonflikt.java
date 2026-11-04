package at.greenchili.surfmate.telegram;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 0.1.38.7: Telegram antwortet auf {@code getUpdates} mit HTTP 409, wenn ein zweites Programm mit demselben
 * Bot-Schlüssel abruft (anderes Gerät, alte Overdrive-/Panoptes-Installation, Skript). Dann kommt am Auto kein
 * Befehl mehr an, auch kein {@code /koppeln}. Der Telegram-Daemon merkt sich das hier, damit {@code telegram.html}
 * es verständlich sagen kann.
 *
 * <p>Ablage: kleine Datei im Daemon-Verzeichnis (Telegram- und Kamera-Daemon laufen beide als uid 2000), geschrieben
 * nur beim Wechsel (409 an → aus). Inhalt: Zeitpunkt des ersten 409 in Folge, 0 = kein Konflikt.
 */
public final class PollKonflikt {

    public static final String DATEI = "telegram_poll_konflikt";

    private PollKonflikt() {}

    /** Aus der Poll-Schleife: Ergebnis eines Abrufs (409 = true, erfolgreicher Abruf = false). */
    public static void abruf(boolean konflikt, long jetztMs) {
        abruf(konflikt, jetztMs, datei());
    }

    static synchronized void abruf(boolean konflikt, long jetztMs, File f) {
        // Kein Gedächtnis im Prozess: auch der Kamera-Daemon löscht die Datei (neuer Schlüssel), also immer die Datei
        // selbst ansehen (wenige Bytes, höchstens einmal je Abruf).
        boolean alt = f.exists();
        if (alt == konflikt) return;
        try {
            if (konflikt) {
                Files.write(f.toPath(), Long.toString(jetztMs).getBytes(StandardCharsets.UTF_8));
            } else {
                //noinspection ResultOfMethodCallIgnored
                f.delete();   // klappt es nicht, versucht es der nächste erfolgreiche Abruf
            }
        } catch (Exception ignored) {
            // nächster Abruf versucht es wieder
        }
    }

    /** Für die Seite: seit wann (ms) der Konflikt besteht, 0 = keiner. */
    public static long seit() {
        return seit(datei());
    }

    static long seit(File f) {
        try {
            if (!f.isFile() || f.length() > 32) return 0;
            return Long.parseLong(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** Neuer Bot-Schlüssel oder Bot entfernt: alter Konflikt gilt nicht mehr. */
    public static void zuruecksetzen() {
        abruf(false, 0L);
    }

    private static File datei() {
        return new File(at.greenchili.surfmate.util.DaemonStorage.path(DATEI));
    }
}
