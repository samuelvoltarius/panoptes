package at.greenchili.surfmate.recording;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 0.1.36 Auslöser je Clip (Anlass: Ereignis-Clips 31.10. 16:01 und 01.11. 19:40 ohne nachvollziehbaren Grund).
 * Neben jedem Ereignis-Clip und jedem manuellen Clip liegt eine kleine Datei {@code <clip>.ausloeser.json}:
 * Zeit, Art, Quelle, Regel, auslösende Werte mit Schwellen, Ganghebel und Tempo.
 *
 * <p><b>Nie</b>: Ort, Koordinaten, Kennzeichen, Gesichter, Bilder, Pfade. Geschrieben und gelesen wird nur, was auf
 * der Erlaubnisliste in {@link #bereinigt} steht; Namen, die nach Ort aussehen, fallen auch dann weg, wenn ein
 * Aufrufer sie hineinlegt. Die Datei wird mit dem Clip gelöscht ({@code RecordingsApiHandler.deleteSidecars},
 * {@link EreignisAblage}); was übrig bleibt, räumt {@link #waisenPflegen} im 10-min-Takt weg.
 */
public final class AusloeserInfo {

    public static final String ENDUNG = ".ausloeser.json";
    public static final int VERSION = 1;
    static final int MAX_BYTES = 4096;
    static final int MAX_WERTE = 12;

    public static final String BREMSEN = "bremsen", STOSS = "stoss", BEWEGUNG = "bewegung", PERSON = "person",
            MANUELL = "manuell", SONSTIGES = "sonstiges";
    public static final Set<String> ARTEN = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            BREMSEN, STOSS, BEWEGUNG, PERSON, MANUELL, SONSTIGES)));

    public static final String QUELLE_TEMPO = "tempo", QUELLE_TEMPO_SENSOR = "tempo+sensor", QUELLE_SENSOR = "sensor",
            QUELLE_TASTE = "taste", QUELLE_REGEL = "regel", QUELLE_UNBEKANNT = "unbekannt";

    static final Set<String> GAENGE = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "P", "R", "N", "D", "S", "M")));

    /** Kurze Kennwörter, nie Freitext. */
    private static final Pattern KENNWORT = Pattern.compile("[a-z][a-z0-9_+.-]{0,39}");
    private static final Pattern WERTNAME = Pattern.compile("[a-z][A-Za-z0-9_]{0,39}");
    private static final Pattern CLIPNAME = Pattern.compile("[A-Za-z0-9_-]{1,80}\\.mp4");
    private static final Pattern ZEITTEXT = Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    /** Namen, die nach Ort aussehen, kommen nie in die Datei (wie im Befehls-Log). */
    private static final Pattern ORTSNAME = Pattern.compile(
            "(?i).*(lat|lon|lng|koordinat|standort|adresse|position|ort|place|geo|gps|kennzeichen|plate|gesicht"
                    + "|face|bild|image|foto|photo|pfad|path).*");

    private AusloeserInfo() {}

    // ------------------------------------------------------------------ Aufbau

    /** Neuer Eintrag; Werte und Schwellen per {@link #wert}/{@link #schwelle}, Fahrt per {@link #fahrt}. */
    public static JSONObject neu(long zeitMs, String art, String quelle, String regel) {
        JSONObject o = new JSONObject();
        try {
            o.put("v", VERSION);
            o.put("zeit", zeitMs);
            o.put("zeitText", zeitText(zeitMs));
            o.put("art", ARTEN.contains(art) ? art : SONSTIGES);
            o.put("quelle", quelle != null && KENNWORT.matcher(quelle).matches() ? quelle : QUELLE_UNBEKANNT);
            if (regel != null && KENNWORT.matcher(regel).matches()) o.put("regel", regel);
        } catch (Exception ignored) {
            // JSONObject.put wirft nur bei NaN/Unendlich; hier nicht möglich
        }
        return o;
    }

    public static JSONObject wert(JSONObject o, String name, double wert) {
        return zahl(o, "werte", name, wert);
    }

    public static JSONObject schwelle(JSONObject o, String name, double wert) {
        return zahl(o, "schwellen", name, wert);
    }

    /** Ganghebel (P/R/N/D/S/M, sonst weggelassen) und Tempo in km/h (NaN = unbekannt). */
    public static JSONObject fahrt(JSONObject o, String gang, double kmh) {
        try {
            if (gang != null && GAENGE.contains(gang)) o.put("gang", gang);
            if (!Double.isNaN(kmh) && !Double.isInfinite(kmh) && kmh >= 0 && kmh < 400) o.put("kmh", runde(kmh));
        } catch (Exception ignored) {
        }
        return o;
    }

    private static JSONObject zahl(JSONObject o, String gruppe, String name, double wert) {
        if (o == null || name == null || !WERTNAME.matcher(name).matches() || ORTSNAME.matcher(name).matches()) return o;
        if (Double.isNaN(wert) || Double.isInfinite(wert)) return o;
        try {
            JSONObject g = o.optJSONObject(gruppe);
            if (g == null) o.put(gruppe, g = new JSONObject());
            if (g.length() < MAX_WERTE || g.has(name)) g.put(name, runde(wert));
        } catch (Exception ignored) {
        }
        return o;
    }

    static double runde(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    static String zeitText(long ms) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
        return f.format(new java.util.Date(ms));
    }

    // ------------------------------------------------------------------ Erlaubnisliste

    /**
     * Nur erlaubte Felder, nur erlaubte Werte. Für Schreiben, Lesen und die API. null bei unbrauchbarem Inhalt
     * (keine Version, keine Zeit oder unbekannte Art).
     */
    public static JSONObject bereinigt(JSONObject roh) {
        if (roh == null) return null;
        try {
            if (roh.optInt("v", 0) != VERSION) return null;
            long zeit = roh.optLong("zeit", 0);
            String art = roh.optString("art", "");
            if (zeit <= 0 || !ARTEN.contains(art)) return null;
            JSONObject o = new JSONObject();
            o.put("v", VERSION);
            o.put("zeit", zeit);
            String zt = roh.optString("zeitText", "");
            if (ZEITTEXT.matcher(zt).matches()) o.put("zeitText", zt);
            o.put("art", art);
            String quelle = roh.optString("quelle", "");
            o.put("quelle", KENNWORT.matcher(quelle).matches() ? quelle : QUELLE_UNBEKANNT);
            String regel = roh.optString("regel", "");
            if (KENNWORT.matcher(regel).matches()) o.put("regel", regel);
            for (String gruppe : new String[] {"werte", "schwellen"}) {
                JSONObject g = roh.optJSONObject(gruppe);
                if (g == null) continue;
                Map<String, Double> sauber = new LinkedHashMap<>();
                Iterator<String> it = g.keys();
                while (it.hasNext() && sauber.size() < MAX_WERTE) {
                    String k = it.next();
                    Object v = g.opt(k);
                    if (!(v instanceof Number) || !WERTNAME.matcher(k).matches() || ORTSNAME.matcher(k).matches()) continue;
                    double d = ((Number) v).doubleValue();
                    if (Double.isNaN(d) || Double.isInfinite(d)) continue;
                    sauber.put(k, runde(d));
                }
                if (!sauber.isEmpty()) {
                    JSONObject n = new JSONObject();
                    for (Map.Entry<String, Double> e : sauber.entrySet()) n.put(e.getKey(), e.getValue());
                    o.put(gruppe, n);
                }
            }
            String gang = roh.optString("gang", "");
            if (GAENGE.contains(gang)) o.put("gang", gang);
            Object kmh = roh.opt("kmh");
            if (kmh instanceof Number) {
                double d = ((Number) kmh).doubleValue();
                if (!Double.isNaN(d) && d >= 0 && d < 400) o.put("kmh", runde(d));
            }
            String clip = roh.optString("clip", "");
            if (CLIPNAME.matcher(clip).matches()) o.put("clip", clip);
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ Datei

    /** {@code <stamm>.ausloeser.json} neben dem Clip (nur ein abschließendes {@code .mp4} wird ersetzt). */
    public static File datei(File mp4) {
        if (mp4 == null) return null;
        String name = mp4.getName();
        String stamm = name.endsWith(".mp4") ? name.substring(0, name.length() - 4) : name;
        return new File(mp4.getParentFile(), stamm + ENDUNG);
    }

    /** Schreibt bereinigt und atomar (tmp + umbenennen). */
    public static boolean schreibe(File mp4, JSONObject info) {
        File ziel = datei(mp4);
        if (ziel == null || info == null) return false;
        try {
            info.put("clip", mp4.getName());
        } catch (Exception ignored) {
        }
        JSONObject sauber = bereinigt(info);
        if (sauber == null) return false;
        byte[] bytes = sauber.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) return false;
        File tmp = new File(ziel.getParentFile(), ziel.getName() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp, false)) {
                out.write(bytes);
                out.getFD().sync();
            }
            if (!tmp.renameTo(ziel)) {
                ziel.delete();
                if (!tmp.renameTo(ziel)) {
                    tmp.delete();
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            tmp.delete();
            return false;
        }
    }

    /** Bereinigter Inhalt oder null (keine Datei, zu groß, kaputt). */
    public static JSONObject lies(File mp4) {
        File f = datei(mp4);
        if (f == null || !f.isFile() || f.length() <= 0 || f.length() > MAX_BYTES) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) break;
                n += r;
            }
            return bereinigt(new JSONObject(new String(b, 0, n, StandardCharsets.UTF_8)));
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean loesche(File mp4) {
        File f = datei(mp4);
        return f != null && f.isFile() && f.delete();
    }

    /** Auslöser-Dateien im Ordner, deren Clip fehlt, löschen. */
    public static int waisenEntfernen(File ordner) {
        if (ordner == null || !ordner.isDirectory()) return 0;
        String[] namen = ordner.list((d, n) -> n.endsWith(ENDUNG));
        if (namen == null) return 0;
        int weg = 0;
        for (String n : namen) {
            String stamm = n.substring(0, n.length() - ENDUNG.length());
            if (!new File(ordner, stamm + ".mp4").exists() && new File(ordner, n).delete()) weg++;
        }
        return weg;
    }

    /** 10-min-Takt: Ereignis-Ordner und Aufnahme-Ordner (manuelle Clips). */
    public static int waisenPflegen() {
        int weg = 0;
        try {
            at.greenchili.surfmate.storage.StorageManager sm = at.greenchili.surfmate.storage.StorageManager.getInstance();
            for (File d : sm.getAllEreignisDirs()) weg += waisenEntfernen(d);
            weg += waisenEntfernen(sm.getRecordingsDir());
        } catch (Throwable ignored) {
        }
        return weg;
    }
}
