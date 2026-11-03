package at.greenchili.surfmate.storage;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Dateien zwischen Handy und Auto (Konkurrenzvergleich Kim Launcher, 03.11.2026): ein eigener Ordner, sonst nichts.
 *
 * <ul>
 *   <li>Höchstens {@link #MAX_BYTES} je Datei. Hochladen in Teilen zu {@link #TEIL_BYTES} (passt unter die
 *       Rumpf-Grenze des {@code HttpServer}), Reihenfolge fest, Größe vorher angesagt und nie überschritten.</li>
 *   <li>Keine Pfad-Ausbrüche: Namen ohne {@code /}, {@code \}, {@code ..}, Steuerzeichen und führenden Punkt;
 *       der Ordner und jede Datei dürfen kein Symlink sein, gelesen und geschrieben wird mit
 *       {@code NOFOLLOW_LINKS}, die fertige Datei entsteht per {@code move} ohne Überschreiben.</li>
 *   <li>Nichts wird ausgeführt oder installiert. APK-Pakete werden gar nicht erst angenommen
 *       ({@link #gesperrteEndung}).</li>
 *   <li>Löschen nur einzeln, nach Namen.</li>
 * </ul>
 */
public final class Dateiablage {

    public static final long MAX_BYTES = 200L * 1024 * 1024;
    public static final int TEIL_BYTES = 2 * 1024 * 1024;
    static final int MAX_DATEIEN = 500;
    static final int MAX_UPLOADS = 3;
    static final long UPLOAD_LEBEN_MS = 30L * 60 * 1000;
    /** So viel Platz bleibt immer frei (Aufnahmen gehen vor). */
    static final long RESERVE_BYTES = 512L * 1024 * 1024;
    static final String TEIL_PRAEFIX = ".teil-";

    /** Paket-Endungen, die nie angenommen werden (Android-Apps, auch gebündelt). */
    static final List<String> GESPERRT = Collections.unmodifiableList(Arrays.asList(
            ".apk", ".apks", ".xapk", ".apkm", ".aab"));

    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N} ._()+,&!'\\[\\]-]{1,120}");
    private static final Pattern ID = Pattern.compile("[0-9a-f]{16}");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Ergebnis mit Grund (Kennwort) bei Fehler. */
    public static final class Antwort {
        public final String grund;   // null = ok
        public final String wert;    // id beim Start, Name bei fertig

        Antwort(String grund, String wert) {
            this.grund = grund;
            this.wert = wert;
        }

        public boolean ok() {
            return grund == null;
        }
    }

    /** Eintrag der Liste. */
    public static final class Info {
        public final String name;
        public final long groesse, zeit;

        Info(String name, long groesse, long zeit) {
            this.name = name;
            this.groesse = groesse;
            this.zeit = zeit;
        }
    }

    private static final class Upload {
        final String name;
        final long groesse;
        long geschrieben;
        int naechster;
        long aktiv;
        final Path teil;

        Upload(String name, long groesse, Path teil, long now) {
            this.name = name;
            this.groesse = groesse;
            this.teil = teil;
            this.aktiv = now;
        }
    }

    private final File ordner;
    private final Map<String, Upload> uploads = new HashMap<>();

    public Dateiablage(File ordner) {
        this.ordner = ordner;
    }

    public File ordner() {
        return ordner;
    }

    // ------------------------------------------------------------------ Namen und Pfade

    /** Gültiger Name oder null. Nie ein Pfad, nie versteckt, nie „.“/„..“. */
    public static String name(String roh) {
        if (roh == null) return null;
        String n = roh.trim();
        if (n.isEmpty() || n.length() > 120) return null;
        if (n.startsWith(".") || n.contains("..") || n.contains("/") || n.contains("\\")) return null;
        for (int i = 0; i < n.length(); i++) if (Character.isISOControl(n.charAt(i))) return null;
        return NAME.matcher(n).matches() ? n : null;
    }

    /** APK und verwandte Pakete: werden nicht angenommen (nichts wird installiert). */
    public static boolean gesperrteEndung(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT).trim();
        for (String e : GESPERRT) if (n.endsWith(e)) return true;
        return false;
    }

    /** Ordner anlegen; er darf kein Symlink sein. */
    boolean ordnerBereit() {
        try {
            Path p = ordner.toPath();
            if (Files.isSymbolicLink(p)) return false;
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(p);
            return Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(p);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Pfad einer vorhandenen, normalen Datei in genau diesem Ordner, sonst null (kein Symlink, kein Unterordner,
     * nichts außerhalb).
     */
    Path datei(String roh) {
        String n = name(roh);
        if (n == null || !ordnerBereit()) return null;
        try {
            Path basis = ordner.toPath().toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path p = basis.resolve(n).normalize();
            if (!basis.equals(p.getParent())) return null;
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (a.isSymbolicLink() || !a.isRegularFile()) return null;
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ Liste, Holen, Löschen

    public synchronized List<Info> liste() {
        List<Info> out = new ArrayList<>();
        if (!ordnerBereit()) return out;
        File[] alle = ordner.listFiles();
        if (alle == null) return out;
        for (File f : alle) {
            if (name(f.getName()) == null) continue;   // auch .teil-… und Fremdes
            try {
                BasicFileAttributes a = Files.readAttributes(f.toPath(), BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (a.isSymbolicLink() || !a.isRegularFile()) continue;
                out.add(new Info(f.getName(), a.size(), a.lastModifiedTime().toMillis()));
            } catch (Exception ignored) {
            }
        }
        Collections.sort(out, (x, y) -> Long.compare(y.zeit, x.zeit));
        return out;
    }

    /** Lesestrom einer Datei (ohne Symlinks zu folgen) oder null. */
    public synchronized InputStream oeffnen(String name) {
        Path p = datei(name);
        if (p == null) return null;
        try {
            return Files.newInputStream(p, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized long groesse(String name) {
        Path p = datei(name);
        try {
            return p == null ? -1 : Files.size(p);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Genau eine Datei löschen. */
    public synchronized boolean loeschen(String name) {
        Path p = datei(name);
        if (p == null) return false;
        try {
            Files.delete(p);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ Hochladen

    /**
     * Hochladen beginnen. Gründe: name_ungueltig, apk_gesperrt, zu_gross, leer, voll, zu_viele, kein_platz, ordner.
     *
     * @param frei freier Platz in Bytes (vom Aufrufer, Tests geben ihn vor)
     */
    public synchronized Antwort start(String rohName, long groesse, long frei, long now) {
        aufraeumen(now);
        String n = name(rohName);
        if (n == null) return new Antwort("name_ungueltig", null);
        if (gesperrteEndung(n)) return new Antwort("apk_gesperrt", null);
        if (groesse <= 0) return new Antwort("leer", null);
        if (groesse > MAX_BYTES) return new Antwort("zu_gross", null);
        if (uploads.size() >= MAX_UPLOADS) return new Antwort("zu_viele", null);
        if (!ordnerBereit()) return new Antwort("ordner", null);
        if (liste().size() >= MAX_DATEIEN) return new Antwort("voll", null);
        long offen = 0;
        for (Upload u : uploads.values()) offen += u.groesse - u.geschrieben;
        if (frei - offen - groesse < RESERVE_BYTES) return new Antwort("kein_platz", null);
        String id = neueId();
        Path teil = ordner.toPath().resolve(TEIL_PRAEFIX + id);
        try {
            // CREATE_NEW: nie in eine schon vorhandene Datei oder einen untergeschobenen Symlink schreiben
            Files.newOutputStream(teil, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS).close();
        } catch (Exception e) {
            return new Antwort("ordner", null);
        }
        uploads.put(id, new Upload(n, groesse, teil, now));
        return new Antwort(null, id);
    }

    /** Ein Teil in fester Reihenfolge. Gründe: unbekannt, reihenfolge, teil_zu_gross, zu_gross, schreiben. */
    public synchronized Antwort teil(String id, int nr, byte[] daten, long now) {
        Upload u = id == null || !ID.matcher(id).matches() ? null : uploads.get(id);
        if (u == null) return new Antwort("unbekannt", null);
        if (nr != u.naechster) return new Antwort("reihenfolge", null);
        if (daten == null || daten.length == 0 || daten.length > TEIL_BYTES) return new Antwort("teil_zu_gross", null);
        if (u.geschrieben + daten.length > u.groesse) {
            verwerfen(id);
            return new Antwort("zu_gross", null);
        }
        try (OutputStream o = Files.newOutputStream(u.teil, StandardOpenOption.WRITE, StandardOpenOption.APPEND,
                LinkOption.NOFOLLOW_LINKS)) {
            o.write(daten);
        } catch (Exception e) {
            verwerfen(id);
            return new Antwort("schreiben", null);
        }
        u.geschrieben += daten.length;
        u.naechster++;
        u.aktiv = now;
        return new Antwort(null, String.valueOf(u.geschrieben));
    }

    /** Abschließen: Größe muss genau stimmen. Liefert den endgültigen Namen (bei Gleichstand „Name (2).ext“). */
    public synchronized Antwort fertig(String id, long now) {
        Upload u = id == null || !ID.matcher(id).matches() ? null : uploads.get(id);
        if (u == null) return new Antwort("unbekannt", null);
        if (u.geschrieben != u.groesse) return new Antwort("unvollstaendig", null);
        try {
            if (Files.size(u.teil) != u.groesse || Files.isSymbolicLink(u.teil)) {
                verwerfen(id);
                return new Antwort("unvollstaendig", null);
            }
        } catch (IOException e) {
            verwerfen(id);
            return new Antwort("schreiben", null);
        }
        for (int i = 1; i <= 50; i++) {
            String ziel = i == 1 ? u.name : mitNummer(u.name, i);
            if (name(ziel) == null) break;
            try {
                Files.move(u.teil, ordner.toPath().resolve(ziel));
                uploads.remove(id);
                return new Antwort(null, ziel);
            } catch (FileAlreadyExistsException e) {
                // nächsten Namen versuchen
            } catch (Exception e) {
                verwerfen(id);
                return new Antwort("schreiben", null);
            }
        }
        verwerfen(id);
        return new Antwort("name_belegt", null);
    }

    public synchronized boolean abbrechen(String id) {
        if (id == null || !uploads.containsKey(id)) return false;
        verwerfen(id);
        return true;
    }

    /** Liegengebliebene Teile weg (auch aus einem früheren Daemon-Lauf). */
    public synchronized void aufraeumen(long now) {
        for (Iterator<Map.Entry<String, Upload>> it = uploads.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Upload> e = it.next();
            if (now - e.getValue().aktiv > UPLOAD_LEBEN_MS || now < e.getValue().aktiv) {
                loescheStill(e.getValue().teil);
                it.remove();
            }
        }
        File[] alle = ordner.listFiles();
        if (alle == null) return;
        for (File f : alle) {
            String n = f.getName();
            if (!n.startsWith(TEIL_PRAEFIX)) continue;
            String id = n.substring(TEIL_PRAEFIX.length());
            if (!uploads.containsKey(id)) loescheStill(f.toPath());
        }
    }

    static String mitNummer(String name, int i) {
        int punkt = name.lastIndexOf('.');
        if (punkt <= 0) return name + " (" + i + ")";
        return name.substring(0, punkt) + " (" + i + ")" + name.substring(punkt);
    }

    private void verwerfen(String id) {
        Upload u = uploads.remove(id);
        if (u != null) loescheStill(u.teil);
    }

    private static void loescheStill(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (Exception ignored) {
        }
    }

    private static String neueId() {
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }
}
