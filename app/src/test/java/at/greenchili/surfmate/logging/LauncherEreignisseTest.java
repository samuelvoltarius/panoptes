package at.greenchili.surfmate.logging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/** 0.1.38.1 (A6): Ereignis-Ringdatei daemon_launcher.log. */
public class LauncherEreignisseTest {

    @Test
    public void keineGeheimnisseUndKeineKillMusterInDerZeile() {
        String z = LauncherEreignisse.zeile(0, "app", "SKRIPT_FEHLER",
                "token=abc123 jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig passwort=geheim "
                        + "/data/start_cam_daemon.sh zeilen=166/248 0123456789abcdef0123456789abcdef 'x'");
        for (String verboten : new String[] {"abc123", "eyJ", "geheim", "0123456789abcdef0123", "cam_daemon",
                "start_", "'"}) {
            assertFalse(z + " enthält " + verboten, z.contains(verboten));
        }
        assertTrue(z, z.contains("SKRIPT_FEHLER"));
        assertTrue(z, z.contains("zeilen=166/248"));
        assertTrue(z.length() <= LauncherEreignisse.MAX_ZEILE);
        assertFalse(z.contains("\n"));
    }

    @Test
    public void jedesEreignisGibtGenauEineZeile() throws Exception {
        File d = Files.createTempDirectory("ereignisse").toFile();
        File f = new File(d, "daemon_launcher.log");
        try {
            String[] ereignisse = {"SKRIPT_SCHREIBEN", "SKRIPT_OK", "SKRIPT_FEHLER", "WRAPPER_BEENDET",
                    "HARDRESET_BEGINN", "HARDRESET_ENDE", "HARDRESET_FEHLER", "HARDRESET_ZEITABLAUF", "ADB_ABBRUCH",
                    "SELBSTHEILUNG", "SELBSTHEILUNG_GRUEN", "SELBSTHEILUNG_GEMELDET"};
            for (String e : ereignisse) {
                LauncherEreignisse.direktAnhaengen(f, LauncherEreignisse.zeile(1, "kamera", e, "a=1\nb=2"));
            }
            List<String> zeilen = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            assertEquals(ereignisse.length, zeilen.size());
            for (int i = 0; i < ereignisse.length; i++) assertTrue(zeilen.get(i), zeilen.get(i).contains(" " + ereignisse[i]));
        } finally {
            for (File x : d.listFiles()) x.delete();
            d.delete();
        }
    }

    @Test
    public void ringGrenzeDreht() throws Exception {
        File d = Files.createTempDirectory("ring").toFile();
        File f = new File(d, "daemon_launcher.log");
        try {
            String z = LauncherEreignisse.zeile(0, "kamera", "SELBSTHEILUNG", "befund=gruen");
            int n = (int) (LauncherEreignisse.MAX_BYTES * 3 / (z.length() + 1));
            for (int i = 0; i < n; i++) LauncherEreignisse.direktAnhaengen(f, z);
            assertTrue(f.length() <= LauncherEreignisse.MAX_BYTES + z.length() + 1);
            File alt = new File(d, "daemon_launcher.log.1");
            assertTrue(alt.isFile());
            assertTrue(alt.length() <= LauncherEreignisse.MAX_BYTES + z.length() + 1);
            assertEquals(2, d.listFiles().length);
        } finally {
            for (File x : d.listFiles()) x.delete();
            d.delete();
        }
    }

    @Test
    public void appPufferGehtBeiFehlerZurueck() {
        LauncherEreignisse.entnehmen();
        LauncherEreignisse.merken("SKRIPT_SCHREIBEN", "art=kamera");
        LauncherEreignisse.merken("SKRIPT_OK", "art=kamera");
        List<String> l = LauncherEreignisse.entnehmen();
        assertEquals(2, l.size());
        assertEquals(0, LauncherEreignisse.ausstehend());
        LauncherEreignisse.zuruecklegen(l);
        assertEquals(2, LauncherEreignisse.ausstehend());
        List<String> wieder = LauncherEreignisse.entnehmen();
        assertTrue(wieder.get(0).contains("SKRIPT_SCHREIBEN"));
        for (int i = 0; i < LauncherEreignisse.MAX_PUFFER + 5; i++) LauncherEreignisse.merken("ADB_ABBRUCH", "n=" + i);
        List<String> voll = LauncherEreignisse.entnehmen();
        assertEquals(LauncherEreignisse.MAX_PUFFER + 1, voll.size());
        assertTrue(voll.get(0).contains("PUFFER_VOLL"));
    }

    @Test
    public void shellTeilHaengtDieZeilenAn() throws Exception {
        Assume.assumeTrue(LogArchivShellTest.shell() != null);
        File d = Files.createTempDirectory("ereignis-shell").toFile();
        File f = new File(d, "daemon_launcher.log");
        try {
            List<String> z = Arrays.asList(LauncherEreignisse.zeile(0, "app", "SKRIPT_OK", "art=kamera"),
                    LauncherEreignisse.zeile(0, "app", "ADB_ABBRUCH", "gen=3 frist adb#12"));
            String teil = LauncherEreignisse.shellAnhaengen(z).replace(LauncherEreignisse.DATEI, LogArchivShellTest.pfad(f));
            LogArchivShellTest.fuehreAus(teil + teil);
            assertEquals(4, Files.readAllLines(f.toPath(), StandardCharsets.UTF_8).size());
            assertEquals("", LauncherEreignisse.shellAnhaengen(java.util.Collections.emptyList()));
        } finally {
            for (File x : d.listFiles()) x.delete();
            d.delete();
        }
    }
}
