package at.greenchili.surfmate.overlay;

import java.util.Locale;

/**
 * Hintergrundbild am Zentraldisplay (0.1.37, DashArc-Abgleich Punkt 22), reine Regeln.
 *
 * <p>Weg: das Bild landet über die Weboberfläche (mit Login) beim Daemon, wird hier geprüft und dann im App-Prozess
 * mit dem Android-{@code WallpaperManager} als System-Hintergrund gesetzt (Recht {@code SET_WALLPAPER}). „Zurück auf
 * Standard“ ist {@code WallpaperManager.clear()}. Ob der BYD-Launcher das System-Hintergrundbild zeigt, ist in der
 * Firmware nur als Hinweis belegt (Launcher3 mit Kategorie {@code WALLPAPER_HOME}, BYD-eigener
 * {@code BydWallpaperService} als Live-Hintergrund), am Auto aber noch nicht gesehen.
 *
 * <p>Video-Hintergrund (mp4) bleibt aus: die Firmware hat zwar einen {@code BydVideoWallpaperService}, aber der
 * Aufruf dafür ist nicht belegt ({@link #VIDEO_BELEGT}). Später.
 */
public final class HintergrundRegel {

    public static final long MAX_BYTES = 20L * 1024 * 1024;
    /** Video-Hintergrund: kein belegter Weg (siehe Klassenkommentar). */
    public static final boolean VIDEO_BELEGT = false;
    /** Fester Dateiname im App-Ordner (eine Datei, wird ersetzt). */
    public static final String DATEI = "hintergrund.bild";

    private HintergrundRegel() {}

    /** Endung des Namens: jpg, png, webp oder null. */
    public static String endung(String name) {
        if (name == null) return null;
        String n = name.trim().toLowerCase(Locale.ROOT);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "jpg";
        if (n.endsWith(".png")) return "png";
        if (n.endsWith(".webp")) return "webp";
        return null;
    }

    /** Art nach den ersten Bytes: jpg, png, webp oder null. */
    public static String art(byte[] k) {
        if (k == null) return null;
        if (k.length >= 3 && (k[0] & 0xff) == 0xFF && (k[1] & 0xff) == 0xD8 && (k[2] & 0xff) == 0xFF) return "jpg";
        if (k.length >= 8 && (k[0] & 0xff) == 0x89 && k[1] == 'P' && k[2] == 'N' && k[3] == 'G'
                && k[4] == 0x0D && k[5] == 0x0A && k[6] == 0x1A && k[7] == 0x0A) {
            return "png";
        }
        if (k.length >= 12 && k[0] == 'R' && k[1] == 'I' && k[2] == 'F' && k[3] == 'F'
                && k[8] == 'W' && k[9] == 'E' && k[10] == 'B' && k[11] == 'P') {
            return "webp";
        }
        return null;
    }

    /** Vor dem Hochladen. Gründe: art (Endung), zu_gross, leer. */
    public static String grundVorher(String name, long groesse) {
        if (endung(name) == null) return "art";
        if (groesse <= 0) return "leer";
        if (groesse > MAX_BYTES) return "zu_gross";
        return null;
    }

    /** Nach dem Hochladen: Inhalt muss zur Endung passen. Gründe: art, zu_gross, leer. */
    public static String grundNachher(String name, long groesse, byte[] kopf) {
        String g = grundVorher(name, groesse);
        if (g != null) return g;
        String a = art(kopf);
        return a != null && a.equals(endung(name)) ? null : "art";
    }
}
