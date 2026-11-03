package at.greenchili.surfmate.logging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 0.1.36 Log-Archiv, Shell-Seite wirklich ausgeführt (POSIX-sh; unter Windows Git-sh, sonst übersprungen):
 * Umbenennen statt Überschreiben bei jedem Start, Kopieren+Kürzen im laufenden Betrieb, Tage, MB, Dateizahl.
 */
public class LogArchivShellTest {

    static final long TAG = 86_400_000L;

    File dir;
    File log;
    File archiv;

    /** /bin/sh, sonst Git-sh unter Windows, sonst null. */
    public static String shell() {
        if (new File("/bin/sh").canExecute() && !System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return "/bin/sh";
        }
        for (String p : new String[] {"C:/Program Files/Git/bin/sh.exe", "C:/Program Files/Git/usr/bin/sh.exe"}) {
            if (new File(p).canExecute()) return p;
        }
        return null;
    }

    public static String pfad(File f) {
        return f.getAbsolutePath().replace('\\', '/');
    }

    /** Führt das Skript aus; gibt die Ausgabe zurück, Exit-Code muss 0 sein. */
    public static String fuehreAus(String skript) throws Exception {
        String sh = shell();
        Assume.assumeTrue("keine POSIX-Shell", sh != null);
        // Als Datei, nicht per -c: Windows verliert sonst Anführungszeichen in Argumenten.
        File f = Files.createTempFile("logarchiv", ".sh").toFile();
        try {
            Files.write(f.toPath(), (skript + "\n").getBytes(StandardCharsets.UTF_8));
            Process p = new ProcessBuilder(sh, pfad(f)).redirectErrorStream(true).start();
            byte[] out = p.getInputStream().readAllBytes();
            int code = p.waitFor();
            String text = new String(out, StandardCharsets.UTF_8);
            assertEquals(text, 0, code);
            return text;
        } finally {
            f.delete();
        }
    }

    String funktionen() {
        StringBuilder sb = new StringBuilder("LOGARCHIV=\"" + pfad(archiv) + "\"\n");
        for (String z : LogArchiv.shellFunktionen()) sb.append(z).append('\n');
        return sb.toString();
    }

    static void schreibe(File f, String s) throws Exception {
        Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
    }

    static String lies(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    List<File> archivDateien() {
        File[] l = archiv.listFiles((d, n) -> n.endsWith(".log"));
        List<File> r = l == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(l));
        r.sort((a, b) -> a.getName().compareTo(b.getName()));
        return r;
    }

    @Before
    public void auf() throws Exception {
        Assume.assumeTrue("keine POSIX-Shell", shell() != null);
        Assume.assumeTrue("zu wenig freier Platz für die Grenz-Tests",
                Files.createTempDirectory("platz").toFile().getUsableSpace() > LogArchiv.WENIG_FREI_BYTES * 2);
        dir = Files.createTempDirectory("logarchiv-shell").toFile();
        log = new File(dir, "cam_daemon.log");
        archiv = new File(dir, LogArchiv.ORDNER);
    }

    @After
    public void ab() {
        if (dir == null) return;
        File[] l = archiv.listFiles();
        if (l != null) for (File f : l) f.delete();
        archiv.delete();
        l = dir.listFiles();
        if (l != null) for (File f : l) f.delete();
        dir.delete();
    }

    @Test
    public void neustartBenenntUmStattZuUeberschreiben() throws Exception {
        schreibe(log, "lauf 1: Ereignis erkannt\n");
        fuehreAus(funktionen() + "log_archivieren \"" + pfad(log) + "\"\n");
        assertFalse("Log nach dem Archivieren weg", log.exists());
        schreibe(log, "lauf 2\n");
        fuehreAus(funktionen() + "log_archivieren \"" + pfad(log) + "\"\n");
        // leeres Log wird nicht archiviert
        schreibe(log, "");
        fuehreAus(funktionen() + "log_archivieren \"" + pfad(log) + "\"\n");
        List<File> a = archivDateien();
        assertEquals(2, a.size());
        StringBuilder alles = new StringBuilder();
        for (File f : a) {
            assertEquals("cam_daemon", LogArchiv.art(f.getName()));
            alles.append(lies(f));
        }
        assertTrue(alles.toString().contains("lauf 1: Ereignis erkannt"));
        assertTrue(alles.toString().contains("lauf 2"));
    }

    @Test
    public void laufendesLogWirdKopiertUndGekuerzt() throws Exception {
        StringBuilder gross = new StringBuilder();
        for (int i = 0; i < 2000; i++) gross.append("zeile ").append(i).append('\n');
        schreibe(log, gross.toString());
        fuehreAus(funktionen() + "log_kopieren_kuerzen \"" + pfad(log) + "\"\n");
        List<File> a = archivDateien();
        assertEquals(1, a.size());
        assertEquals(gross.toString(), lies(a.get(0)));
        String rest = lies(log);
        assertTrue(rest, rest.contains("Log archiviert nach " + a.get(0).getName()));
        assertTrue(rest.length() < 200);
    }

    @Test
    public void tageUndMbAusGrenzenDateiWirken() throws Exception {
        assertTrue(archiv.mkdirs());
        schreibe(new File(archiv, "grenzen"), "3 1\n");
        long jetzt = System.currentTimeMillis();
        byte[] mb06 = new byte[600 * 1024];
        File alt = new File(archiv, "cam_daemon.20261020-100000-1-1.log");
        File mittel = new File(archiv, "cam_daemon.20261102-100000-1-1.log");
        File neu = new File(archiv, "cam_daemon.20261103-100000-1-1.log");
        File andereArt = new File(archiv, "acc_sentry_daemon.20261103-100000-1-1.log");
        Files.write(alt.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        Files.write(mittel.toPath(), mb06);
        Files.write(neu.toPath(), mb06);
        Files.write(andereArt.toPath(), mb06);
        assertTrue(alt.setLastModified(jetzt - 4 * TAG));
        assertTrue(mittel.setLastModified(jetzt - 2 * 3_600_000L));
        assertTrue(neu.setLastModified(jetzt - 3_600_000L));
        assertTrue(andereArt.setLastModified(jetzt - 3_600_000L));
        fuehreAus(funktionen() + "log_archiv_aufraeumen\n");
        assertFalse("älter als 3 Tage", alt.exists());
        assertFalse("über 1 MB je Art: die ältere fällt", mittel.exists());
        assertTrue(neu.exists());
        assertTrue("andere Art zählt getrennt", andereArt.exists());
    }

    @Test
    public void ohneGrenzenDateiGeltenSiebenTage() throws Exception {
        assertTrue(archiv.mkdirs());
        long jetzt = System.currentTimeMillis();
        File sechs = new File(archiv, "sentry_daemon.20261028-100000-1-1.log");
        File acht = new File(archiv, "sentry_daemon.20261026-100000-1-1.log");
        schreibe(sechs, "a");
        schreibe(acht, "b");
        assertTrue(sechs.setLastModified(jetzt - 6 * TAG));
        assertTrue(acht.setLastModified(jetzt - 8 * TAG));
        fuehreAus(funktionen() + "log_archiv_aufraeumen\n");
        assertTrue(sechs.exists());
        assertFalse(acht.exists());
    }

    @Test
    public void einzeilerFuerStartwegeOhneWaechter() throws Exception {
        schreibe(log, "vorheriger Lauf\n");
        String befehl = LogArchiv.archivBefehl(pfad(log)) + "echo neuer Lauf >> \"" + pfad(log) + "\"";
        fuehreAus(befehl);
        assertEquals("neuer Lauf\n", lies(log).replace("\r", ""));
        List<File> a = archivDateien();
        assertEquals(1, a.size());
        assertEquals("vorheriger Lauf\n", lies(a.get(0)));
    }
}
