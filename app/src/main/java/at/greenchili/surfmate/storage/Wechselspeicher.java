package at.greenchili.surfmate.storage;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Dateimanager für SD-Karte und USB-Stick (0.1.37, DashArc-Abgleich Punkt 23), Teil der {@link Dateiablage}.
 *
 * <ul>
 *   <li>Nur unter den Wurzeln, die Android als Wechselspeicher meldet ({@link StorageManager}: SD und USB), nie
 *       intern, nie ein Systempfad ({@link #istWechselWurzel}).</li>
 *   <li>Keine Pfad-Ausbrüche: Teile ohne {@code ..}, {@code .}, {@code \}, Steuerzeichen und führenden Punkt; jeder
 *       Teil des Wegs darf kein Symlink sein, gelesen und geschrieben wird mit {@code NOFOLLOW_LINKS}.</li>
 *   <li>Dashcam-Aufnahmen bleiben draußen: die Ordner von Panoptes und der BYD-Dashcam ({@link #AUSGESCHLOSSEN})
 *       erscheinen nicht und sind nicht erreichbar. Für sie gelten die Regeln des Wächters.</li>
 *   <li>Schreiben ist nur „Datei aus der Ablage hierher kopieren“, nie überschreiben, keine APKs. Löschen nur eine
 *       einzelne Datei, keine Ordner, keine Sammel-Löschung. Beides braucht vorher eine Rückfrage (Server).</li>
 * </ul>
 */
public final class Wechselspeicher {

    public static final String SD = "sd", USB = "usb";

    /** Erste Ebene, die nie gezeigt wird (Groß/klein egal): Panoptes, BYD-Dashcam, Android-Systemordner. */
    static final Set<String> AUSGESCHLOSSEN;
    /** Tiefere Dashcam-Wege (aus {@link ExternalStorageCleaner}): erste Ebene/zweite Ebene. */
    static final Set<String> AUSGESCHLOSSEN_TIEF;

    static {
        Set<String> a = new TreeSet<>();
        for (String s : new String[]{AppOrdner.NAME, AppOrdner.ALT, "Recorder", "DVR", "CDR", "行车记录仪",
                "CarRecord", "DashCam", "Android", "LOST.DIR", "System Volume Information"}) {
            a.add(s.toLowerCase(Locale.ROOT));
        }
        AUSGESCHLOSSEN = Collections.unmodifiableSet(a);
        Set<String> t = new TreeSet<>();
        for (String s : new String[]{"DCIM/100MEDIA", "Video/DVR", "Movies/DVR"}) t.add(s.toLowerCase(Locale.ROOT));
        AUSGESCHLOSSEN_TIEF = Collections.unmodifiableSet(t);
    }

    static final int MAX_EINTRAEGE = 1000;
    static final int MAX_TIEFE = 24;

    /** Eintrag einer Ordnerliste. */
    public static final class Eintrag {
        public final String name;
        public final boolean ordner;
        public final long groesse, zeit;

        Eintrag(String name, boolean ordner, long groesse, long zeit) {
            this.name = name;
            this.ordner = ordner;
            this.groesse = groesse;
            this.zeit = zeit;
        }
    }

    private final Map<String, File> wurzeln;

    /** @param wurzeln Kennung ({@link #SD}/{@link #USB}) → Wurzel; nur echte, eingehängte Wechselspeicher übergeben. */
    public Wechselspeicher(Map<String, File> wurzeln) {
        this.wurzeln = new LinkedHashMap<>(wurzeln == null ? Collections.emptyMap() : wurzeln);
    }

    /** Live: SD und USB aus dem {@link StorageManager}, nur wenn eingehängt und ein echter Wechselspeicher-Pfad. */
    public static Wechselspeicher live() {
        Map<String, File> m = new LinkedHashMap<>();
        try {
            StorageManager s = StorageManager.getInstance();
            if (s.isSdCardAvailable() && istWechselWurzel(s.getSdCardPath())) m.put(SD, new File(s.getSdCardPath()));
            if (s.isUsbAvailable() && istWechselWurzel(s.getUsbPath())) m.put(USB, new File(s.getUsbPath()));
        } catch (Throwable ignored) {
        }
        return new Wechselspeicher(m);
    }

    /**
     * Nur {@code /storage/<id>} oder {@code /mnt/media_rw/<id>} (genau eine Ebene), nie der interne Speicher, nie
     * {@code self}, nie ein Symlink-Ziel woanders hin.
     */
    public static boolean istWechselWurzel(String pfad) {
        if (pfad == null) return false;
        String p = pfad.endsWith("/") ? pfad.substring(0, pfad.length() - 1) : pfad;
        String rest;
        if (p.startsWith("/storage/")) rest = p.substring("/storage/".length());
        else if (p.startsWith("/mnt/media_rw/")) rest = p.substring("/mnt/media_rw/".length());
        else return false;
        if (rest.isEmpty() || rest.contains("/") || rest.startsWith(".")) return false;
        String r = rest.toLowerCase(Locale.ROOT);
        if (r.equals("emulated") || r.equals("self") || r.equals("sdcard0") || r.equals("sdcard")) return false;
        return rest.matches("[A-Za-z0-9_-]{1,64}");
    }

    /** Welche Speicher gerade da sind. */
    public List<String> speicher() {
        List<String> r = new ArrayList<>();
        for (String k : wurzeln.keySet()) if (wurzel(k) != null) r.add(k);
        return r;
    }

    /** Echte Wurzel oder null (fehlt, kein Ordner, Symlink). */
    Path wurzel(String speicher) {
        File f = speicher == null ? null : wurzeln.get(speicher);
        if (f == null) return null;
        try {
            Path p = f.toPath();
            if (Files.isSymbolicLink(p) || !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) return null;
            return p.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (Exception e) {
            return null;
        }
    }

    /** Teile eines relativen Wegs oder null bei jedem verdächtigen Teil. Leer/„/“ = Wurzel. */
    static List<String> teile(String rel) {
        List<String> r = new ArrayList<>();
        if (rel == null) return r;
        String s = rel.trim();
        if (s.contains("\\") || s.contains("//")) return null;
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) return r;
        for (String t : s.split("/")) {
            if (t.isEmpty() || t.equals(".") || t.equals("..") || t.startsWith(".") || t.length() > 255) return null;
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if (Character.isISOControl(c) || c == ':' || c == '*' || c == '?' || c == '"' || c == '<'
                        || c == '>' || c == '|') {
                    return null;
                }
            }
            r.add(t);
        }
        if (r.size() > MAX_TIEFE) return null;
        return r;
    }

    /** Liegt dieser Weg in einem Dashcam- oder Systemordner? */
    static boolean ausgeschlossen(List<String> teile) {
        if (teile == null) return true;
        if (teile.isEmpty()) return false;
        if (AUSGESCHLOSSEN.contains(teile.get(0).toLowerCase(Locale.ROOT))) return true;
        if (teile.size() >= 2) {
            String zwei = (teile.get(0) + "/" + teile.get(1)).toLowerCase(Locale.ROOT);
            if (AUSGESCHLOSSEN_TIEF.contains(zwei)) return true;
        }
        return false;
    }

    /**
     * Weg auflösen, Schritt für Schritt ohne Symlinks. null = nicht erlaubt. Existiert der Weg nicht, wird er nur
     * geliefert, wenn {@code mussDa} false ist (und auch dann ist der Elternordner geprüft).
     */
    Path aufloesen(String speicher, String rel, boolean mussDa) {
        Path basis = wurzel(speicher);
        List<String> t = teile(rel);
        if (basis == null || t == null || ausgeschlossen(t)) return null;
        Path cur = basis;
        for (int i = 0; i < t.size(); i++) {
            Path next = cur.resolve(t.get(i)).normalize();
            if (!cur.equals(next.getParent()) || !next.startsWith(basis)) return null;
            boolean da = Files.exists(next, LinkOption.NOFOLLOW_LINKS);
            if (da && Files.isSymbolicLink(next)) return null;
            if (!da) {
                if (mussDa || i < t.size() - 1) return null;
                return next;
            }
            cur = next;
        }
        return cur.startsWith(basis) ? cur : null;
    }

    // ------------------------------------------------------------------ Lesen

    /** Ordnerinhalt, Ordner zuerst, dann nach Name; null = Weg nicht erlaubt oder kein Ordner. */
    public List<Eintrag> liste(String speicher, String rel) {
        Path d = aufloesen(speicher, rel, true);
        if (d == null || !Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS)) return null;
        List<String> t = teile(rel);
        List<Eintrag> out = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(d)) {
            for (Path p : s) {
                if (out.size() >= MAX_EINTRAEGE) break;
                String n = p.getFileName().toString();
                List<String> mit = new ArrayList<>(t);
                mit.add(n);
                if (n.startsWith(".") || ausgeschlossen(mit) || teile(String.join("/", mit)) == null) continue;
                try {
                    BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (a.isSymbolicLink() || a.isOther()) continue;
                    out.add(new Eintrag(n, a.isDirectory(), a.isDirectory() ? 0 : a.size(),
                            a.lastModifiedTime().toMillis()));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            return null;
        }
        Collections.sort(out, (x, y) -> x.ordner != y.ordner ? (x.ordner ? -1 : 1)
                : x.name.compareToIgnoreCase(y.name));
        return out;
    }

    /** Größe einer normalen Datei oder -1. */
    public long groesse(String speicher, String rel) {
        Path p = datei(speicher, rel);
        try {
            return p == null ? -1 : Files.size(p);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Lesestrom einer normalen Datei oder null. */
    public InputStream oeffnen(String speicher, String rel) {
        Path p = datei(speicher, rel);
        if (p == null) return null;
        try {
            return Files.newInputStream(p, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        } catch (Exception e) {
            return null;
        }
    }

    private Path datei(String speicher, String rel) {
        List<String> t = teile(rel);
        if (t == null || t.isEmpty()) return null;
        Path p = aufloesen(speicher, rel, true);
        if (p == null) return null;
        try {
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return a.isRegularFile() && !a.isSymbolicLink() ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ Schreiben (nur nach Rückfrage, Server)

    /** Genau eine normale Datei löschen. Gründe: nicht_erlaubt, fehler. */
    public String loeschen(String speicher, String rel) {
        Path p = datei(speicher, rel);
        if (p == null) return "nicht_erlaubt";
        try {
            Files.delete(p);
            return null;
        } catch (Exception e) {
            return "fehler";
        }
    }

    /**
     * Datei in einen vorhandenen Ordner kopieren, nie überschreiben, keine APKs. Gründe: name_ungueltig,
     * apk_gesperrt, nicht_erlaubt, schon_da, kein_platz, fehler.
     */
    public String kopiereHinein(String speicher, String relOrdner, String name, InputStream quelle, long groesse) {
        String n = Dateiablage.name(name);
        if (n == null) return "name_ungueltig";
        if (Dateiablage.gesperrteEndung(n)) return "apk_gesperrt";
        Path ordner = aufloesen(speicher, relOrdner, true);
        if (ordner == null || !Files.isDirectory(ordner, LinkOption.NOFOLLOW_LINKS)) return "nicht_erlaubt";
        List<String> t = teile(relOrdner);
        List<String> mit = new ArrayList<>(t == null ? Collections.<String>emptyList() : t);
        mit.add(n);
        if (ausgeschlossen(mit)) return "nicht_erlaubt";
        Path ziel = ordner.resolve(n);
        if (!ordner.equals(ziel.getParent())) return "nicht_erlaubt";
        if (Files.exists(ziel, LinkOption.NOFOLLOW_LINKS)) return "schon_da";
        if (ordner.toFile().getUsableSpace() > 0 && ordner.toFile().getUsableSpace() < groesse + 16L * 1024 * 1024) {
            return "kein_platz";
        }
        Path teil = ordner.resolve(".panoptes-teil-" + Long.toHexString(System.nanoTime()));
        try {
            try (OutputStream o = Files.newOutputStream(teil, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                byte[] b = new byte[64 * 1024];
                int r;
                long summe = 0;
                while ((r = quelle.read(b)) > 0) {
                    summe += r;
                    if (summe > groesse) throw new java.io.IOException("zu_gross");
                    o.write(b, 0, r);
                }
            }
            Files.move(teil, ziel);   // ohne REPLACE_EXISTING: nie überschreiben
            if (Files.isSymbolicLink(ziel)) {
                Files.deleteIfExists(ziel);
                return "fehler";
            }
            return null;
        } catch (java.nio.file.FileAlreadyExistsException e) {
            loescheStill(teil);
            return "schon_da";
        } catch (Exception e) {
            loescheStill(teil);
            return "fehler";
        }
    }

    private static void loescheStill(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (Exception ignored) {
        }
    }

    /** Für Anzeige und Tests: alle ausgeschlossenen Namen der ersten Ebene. */
    public static List<String> ausgeschlosseneOrdner() {
        return new ArrayList<>(Arrays.asList(AUSGESCHLOSSEN.toArray(new String[0])));
    }
}
