package at.greenchili.surfmate.storage;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.1.11 (Kern 7): Rückfall-Aufnahmen vom internen Speicher zurück auf den gewählten Datenträger (USB/SD) bringen,
 * nachdem dieser wieder da ist („Noch offen: rueckfall_aufnahmen“). Nur auf Wunsch des Nutzers, nie automatisch.
 *
 * <p>Ablauf je Aufnahme (Anker {@code .mp4} + gleichnamige Begleitdateien {@code <stem>.*}/{@code <stem>_*} +
 * {@code thumb_<stem>_*}): alle Dateien als {@code .verschieben.tmp} kopieren → Größe UND SHA-256 gegen das Original
 * prüfen → umbenennen → Index umtragen → erst dann die Originale löschen. Schlägt irgendein Schritt fehl, bleiben die
 * Originale unangetastet und die halben Kopien werden entfernt. Eine schon vorhandene Zieldatei wird nie
 * überschrieben: gleicher Inhalt = gilt als kopiert, anderer Inhalt = Aufnahme bleibt intern (Konflikt).
 * Dateien, die jünger als {@link #MIN_ALTER_MS} sind (könnten noch geschrieben werden), bleiben liegen.
 */
public final class RueckfallVerschieber {

    /** Hook für den Aufnahmen-Index (Daemon: RecordingsIndex; Test: Attrappe). */
    public interface Index {
        void neu(File mp4);
        void weg(String alterPfad);
    }

    public static final long MIN_ALTER_MS = 120_000L;
    /** Mindestens so viel muss auf dem Ziel nach dem Kopieren frei bleiben. */
    public static final long RESERVE_BYTES = 512L * 1024 * 1024;
    static final String TMP = ".verschieben.tmp";

    public static final class Ergebnis {
        public int gruppen, verschoben, uebersprungen, konflikte, fehler;
        public long bytes;
        public String abbruch;   // null | "kein_platz" | "ziel_fehlt" | "abgebrochen"

        @Override public String toString() {
            return "gruppen=" + gruppen + " verschoben=" + verschoben + " uebersprungen=" + uebersprungen
                    + " konflikte=" + konflikte + " fehler=" + fehler + " bytes=" + bytes
                    + (abbruch != null ? " abbruch=" + abbruch : "");
        }
    }

    /** Für Tests/Abbruch: freier Platz am Ziel und „weitermachen?“. */
    public interface Umgebung {
        long freiBytes(File ziel);
        boolean weiter();
        long jetzt();
    }

    private RueckfallVerschieber() {}

    /** Rein: Dateien eines Ordners nach Anker gruppieren. Nicht zuordenbare Dateien landen unter eigenem Schlüssel. */
    static Map<String, List<File>> gruppieren(File[] dateien, String anker) {
        Map<String, List<File>> g = new LinkedHashMap<>();
        List<String> stems = new ArrayList<>();
        if (dateien == null) return g;
        for (File f : dateien) {
            if (f.isFile() && f.getName().endsWith(anker)) {
                String stem = f.getName().substring(0, f.getName().length() - anker.length());
                stems.add(stem);
                g.put(stem, new ArrayList<>());
            }
        }
        // längste Stems zuerst, damit cam_1 nicht cam_12 schluckt
        stems.sort((a, b) -> b.length() - a.length());
        for (File f : dateien) {
            if (!f.isFile() || f.getName().endsWith(TMP) || f.getName().endsWith(".tmp")) continue;
            String n = f.getName();
            String zu = null;
            for (String s : stems) {
                if (n.equals(s + anker) || n.startsWith(s + ".") || n.startsWith(s + "_")
                        || n.startsWith("thumb_" + s + "_") || n.equals("thumb_" + s + ".jpg")) {
                    zu = s;
                    break;
                }
            }
            if (zu != null) g.get(zu).add(f);
        }
        return g;
    }

    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[1 << 16];
        try (InputStream in = new FileInputStream(f)) {
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /**
     * Verschiebt alle fertigen Aufnahmen aus {@code quelle} nach {@code ziel}. {@code quelle} und {@code ziel} müssen
     * verschiedene Ordner sein.
     */
    public static Ergebnis verschieben(File quelle, File ziel, String anker, Index index, Umgebung env) {
        Ergebnis e = new Ergebnis();
        if (quelle == null || ziel == null || quelle.getAbsolutePath().equals(ziel.getAbsolutePath())) {
            e.abbruch = "ziel_fehlt";
            return e;
        }
        if (!ziel.isDirectory() && !ziel.mkdirs()) {
            e.abbruch = "ziel_fehlt";
            return e;
        }
        Map<String, List<File>> gruppen = gruppieren(quelle.listFiles(), anker);
        e.gruppen = gruppen.size();
        // älteste zuerst (Dateiname enthält die Zeit; lastModified als Rückfall)
        List<Map.Entry<String, List<File>>> liste = new ArrayList<>(gruppen.entrySet());
        liste.sort((a, b) -> Long.compare(ankerZeit(a.getValue(), anker), ankerZeit(b.getValue(), anker)));
        for (Map.Entry<String, List<File>> g : liste) {
            if (!env.weiter()) { e.abbruch = "abgebrochen"; break; }
            List<File> teile = g.getValue();
            boolean frisch = false;
            long groesse = 0;
            for (File f : teile) {
                if (env.jetzt() - f.lastModified() < MIN_ALTER_MS) frisch = true;
                groesse += f.length();
            }
            if (frisch) { e.uebersprungen++; continue; }
            if (env.freiBytes(ziel) - groesse < RESERVE_BYTES) { e.abbruch = "kein_platz"; break; }
            int r = gruppeVerschieben(teile, ziel, anker, index);
            if (r == 0) { e.verschoben++; e.bytes += groesse; }
            else if (r == 1) e.konflikte++;
            else e.fehler++;
        }
        return e;
    }

    private static long ankerZeit(List<File> teile, String anker) {
        for (File f : teile) if (f.getName().endsWith(anker)) return f.lastModified();
        return Long.MAX_VALUE;
    }

    /** @return 0 = verschoben, 1 = Konflikt (Zieldatei anders), 2 = Fehler (Originale unverändert) */
    static int gruppeVerschieben(List<File> teile, File ziel, String anker, Index index) {
        List<File> kopien = new ArrayList<>();      // neu angelegte .tmp
        List<File[]> umbenennen = new ArrayList<>(); // {tmp, final}
        try {
            for (File f : teile) {
                File fin = new File(ziel, f.getName());
                String h = sha256(f);
                if (fin.exists()) {
                    if (fin.length() == f.length() && h.equals(sha256(fin))) continue;   // schon dort, gleich
                    aufraeumen(kopien);
                    return 1;
                }
                File tmp = new File(ziel, f.getName() + TMP);
                java.nio.file.Files.copy(f.toPath(), tmp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                kopien.add(tmp);
                if (tmp.length() != f.length() || !h.equals(sha256(tmp))) {
                    aufraeumen(kopien);
                    return 2;
                }
                umbenennen.add(new File[] {tmp, fin});
            }
            for (int i = 0; i < umbenennen.size(); i++) {
                if (!umbenennen.get(i)[0].renameTo(umbenennen.get(i)[1])) {
                    // zurück: schon umbenannte Kopien und restliche .tmp entfernen – die Originale bleiben
                    for (int j = 0; j < i; j++) umbenennen.get(j)[1].delete();
                    for (int j = i; j < umbenennen.size(); j++) umbenennen.get(j)[0].delete();
                    return 2;
                }
            }
        } catch (Exception ex) {
            aufraeumen(kopien);
            return 2;
        }
        // Index umtragen, dann Originale löschen (Kopien sind geprüft)
        for (File f : teile) {
            if (!f.getName().endsWith(anker)) continue;
            try { index.neu(new File(ziel, f.getName())); } catch (Throwable ignored) { }
            try { index.weg(f.getAbsolutePath()); } catch (Throwable ignored) { }
        }
        for (File f : teile) f.delete();
        return 0;
    }

    private static void aufraeumen(List<File> kopien) {
        for (File k : kopien) k.delete();
    }
}
