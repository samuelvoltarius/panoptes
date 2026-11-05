package at.greenchili.surfmate.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.logging.LogArchivShellTest;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 0.1.36.1: Skripte am Auto werden nur noch über eine eigene Temp-Datei geschrieben, dann
 * {@code sh -n} + Zeilenzahl, dann {@code mv}. Befund am Auto (0.1.36, vC 61): start_telegram.sh
 * hatte 334 statt 220 Zeilen, {@code sh -n} meldete „[221]: unexpected 'fi'“. Ursache: zwei Schreiber
 * zugleich (UI-Weg in einem Zug, AccSentry zeilenweise mit {@code >>}) – die restlichen Zeilen 110..223
 * des langsameren Schreibers landeten hinter dem fertigen Skript.
 */
public class SkriptAtomarSchreibenTest {

    private static List<String> telegram() {
        return DaemonLauncher.Companion.buildTelegramWatchdogScript("/a/base.apk", "");
    }

    /** Führt ein Skript aus und gibt {Exit, Ausgabe} zurück (ohne Exit-0-Pflicht). */
    private static Object[] lauf(String skript) throws Exception {
        String sh = LogArchivShellTest.shell();
        Assume.assumeTrue("keine POSIX-Shell", sh != null);
        File f = Files.createTempFile("skript-atomar", ".sh").toFile();
        try {
            Files.write(f.toPath(), (skript + "\n").getBytes(StandardCharsets.UTF_8));
            Process p = new ProcessBuilder(sh, LogArchivShellTest.pfad(f)).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new Object[] {p.waitFor(), out};
        } finally {
            f.delete();
        }
    }

    private static int shN(File datei) throws Exception {
        String sh = LogArchivShellTest.shell();
        Process p = new ProcessBuilder(sh, "-n", LogArchivShellTest.pfad(datei)).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        return p.waitFor();
    }

    private static String lies(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static void weg(File dir) {
        File[] l = dir.listFiles();
        if (l != null) for (File f : l) f.delete();
        dir.delete();
    }

    private static void keineTempReste(File dir) {
        File[] l = dir.listFiles();
        for (File f : l) assertFalse("Temp-Datei liegt noch: " + f.getName(),
                f.getName().contains(".neu.") || f.getName().startsWith(DaemonLauncher.TEMP_PRAEFIX));
    }

    @Test
    public void altesLaengeresSkriptWirdByteGleichUndGueltigErsetzt() throws Exception {
        Assume.assumeTrue(LogArchivShellTest.shell() != null);
        File dir = Files.createTempDirectory("skript-atomar").toFile();
        try {
            File ziel = new File(dir, "start_telegram.sh");
            List<String> zeilen = telegram();
            // Altes, längeres und kaputtes Skript wie am Auto: volles Skript + Zeilen 110..223 noch einmal.
            List<String> alt = new ArrayList<>(zeilen);
            alt.addAll(zeilen.subList(109, zeilen.size()));
            Files.write(ziel.toPath(), (String.join("\n", alt) + "\n").getBytes(StandardCharsets.UTF_8));
            assertEquals(zeilen.size() + zeilen.size() - 109, alt.size());   // am Auto: 334
            assertNotEquals("Vorbedingung: altes Skript ist syntaktisch kaputt", 0, shN(ziel));

            LogArchivShellTest.fuehreAus(DaemonLauncher.Companion.buildWatchdogScriptWriteCommand(
                    LogArchivShellTest.pfad(ziel), zeilen));
            assertEquals(String.join("\n", zeilen) + "\n", lies(ziel));
            assertEquals(0, shN(ziel));
            keineTempReste(dir);
        } finally {
            weg(dir);
        }
    }

    /** So entstand der Befund: alter Weg (direkt ins Ziel) mit zwei Schreibern gibt genau 334 Zeilen. */
    @Test
    public void ursacheZweiSchreiberDirektInsZielGibt334Zeilen() throws Exception {
        Assume.assumeTrue(LogArchivShellTest.shell() != null);
        File dir = Files.createTempDirectory("skript-ursache").toFile();
        try {
            File ziel = new File(dir, "start_telegram.sh");
            String z = LogArchivShellTest.pfad(ziel);
            List<String> zeilen = telegram();
            // Am Auto 220 Zeilen; seit 0.1.38.1 21 Zeilen mehr (Anhänge-Modus), Befund-Logik unverändert.
            int n = zeilen.size();
            assertEquals(244, n);
            StringBuilder sk = new StringBuilder();
            // AccSentry (alt): rm, Zeilen 1..109 einzeln ins Ziel
            sk.append("rm -f ").append(z).append('\n');
            for (int i = 0; i < 109; i++) {
                sk.append(DaemonLauncher.buildScriptLineWriteCommand(zeilen.get(i), i == 0, z)).append('\n');
            }
            // UI-Weg (alt) dazwischen: rm + ganzes Skript in einem Zug
            sk.append("rm -f ").append(z).append('\n');
            for (int i = 0; i < zeilen.size(); i++) {
                sk.append(DaemonLauncher.buildScriptLineWriteCommand(zeilen.get(i), i == 0, z)).append('\n');
            }
            // AccSentry macht mit >> weiter
            for (int i = 109; i < zeilen.size(); i++) {
                sk.append(DaemonLauncher.buildScriptLineWriteCommand(zeilen.get(i), false, z)).append('\n');
            }
            LogArchivShellTest.fuehreAus(sk.toString());
            List<String> ergebnis = Arrays.asList(lies(ziel).split("\n", -1));
            assertEquals("am Auto 334 Zeilen + leeres Ende", n + (n - 109) + 1, ergebnis.size());
            assertEquals("Zeile n+1 (am Auto 221) = '  fi' aus acquire_watchdog_lock", "  fi", ergebnis.get(n));
            assertTrue(ergebnis.get(n + 2).contains("watchdog_pid_matches"));
            assertNotEquals(0, shN(ziel));
        } finally {
            weg(dir);
        }
    }

    /** Neuer Weg: dieselbe Verzahnung, aber jeder in seine Temp-Datei – das Ergebnis ist immer ganz. */
    @Test
    public void zweiSchreiberMitTempDateiGebenEinGanzesSkript() throws Exception {
        Assume.assumeTrue(LogArchivShellTest.shell() != null);
        File dir = Files.createTempDirectory("skript-zwei").toFile();
        try {
            File ziel = new File(dir, "start_telegram.sh");
            String z = LogArchivShellTest.pfad(ziel);
            List<String> zeilen = telegram();
            String accTmp = DaemonLauncher.skriptTempPfad(z, "acc1");
            StringBuilder sk = new StringBuilder();
            for (int i = 0; i < 109; i++) {
                sk.append(DaemonLauncher.buildScriptLineWriteCommand(zeilen.get(i), i == 0, accTmp)).append('\n');
            }
            sk.append(DaemonLauncher.Companion.buildWatchdogScriptWriteCommand(z, zeilen)).append('\n');
            for (int i = 109; i < zeilen.size(); i++) {
                sk.append(DaemonLauncher.buildScriptLineWriteCommand(zeilen.get(i), false, accTmp)).append('\n');
            }
            sk.append(DaemonLauncher.buildSkriptEinsetzenCommand(accTmp, z, zeilen.size(), "755"));
            String out = LogArchivShellTest.fuehreAus(sk.toString());
            assertTrue(out, out.contains("SKRIPT_OK"));
            assertEquals(String.join("\n", zeilen) + "\n", lies(ziel));
            assertEquals(0, shN(ziel));
            keineTempReste(dir);
        } finally {
            weg(dir);
        }
    }

    @Test
    public void syntaxFehlerBehaeltAltesSkriptUndMeldet() throws Exception {
        Assume.assumeTrue(LogArchivShellTest.shell() != null);
        File dir = Files.createTempDirectory("skript-fehler").toFile();
        try {
            File ziel = new File(dir, "start_x.sh");
            String altText = "#!/system/bin/sh\necho alt\n";
            Files.write(ziel.toPath(), altText.getBytes(StandardCharsets.UTF_8));
            List<String> kaputt = Arrays.asList("#!/system/bin/sh", "if true; then", "  echo x", "fi", "fi");
            Object[] r = lauf(DaemonLauncher.Companion.buildWatchdogScriptWriteCommand(
                    LogArchivShellTest.pfad(ziel), kaputt));
            assertEquals(r[1].toString(), 3, r[0]);
            assertTrue(r[1].toString(), r[1].toString().contains("SKRIPT_FEHLER"));
            assertEquals("altes Skript bleibt", altText, lies(ziel));
            keineTempReste(dir);
        } finally {
            weg(dir);
        }
    }

    @Test
    public void falscheZeilenzahlBehaeltAltesSkript() throws Exception {
        Assume.assumeTrue(LogArchivShellTest.shell() != null);
        File dir = Files.createTempDirectory("skript-zeilen").toFile();
        try {
            File ziel = new File(dir, "start_x.sh");
            File tmp = new File(dir, "start_x.sh.neu.t1");
            Files.write(ziel.toPath(), "echo alt\n".getBytes(StandardCharsets.UTF_8));
            Files.write(tmp.toPath(), "echo a\necho b\n".getBytes(StandardCharsets.UTF_8));
            Object[] r = lauf(DaemonLauncher.buildSkriptEinsetzenCommand(
                    LogArchivShellTest.pfad(tmp), LogArchivShellTest.pfad(ziel), 3, "755"));
            assertEquals(r[1].toString(), 3, r[0]);
            assertEquals("echo alt\n", lies(ziel));
            assertFalse(tmp.exists());
        } finally {
            weg(dir);
        }
    }

    /** Alle Skripte, die Panoptes am Auto dauerhaft schreibt, bestehen sh -n über den Schreibweg. */
    @Test
    public void alleGeschriebenenSkripteBestehenShN() throws Exception {
        Assume.assumeTrue(LogArchivShellTest.shell() != null);
        File dir = Files.createTempDirectory("skript-alle").toFile();
        try {
            List<List<String>> alle = Arrays.asList(
                    DaemonLauncher.Companion.buildCamDaemonWatchdogScript("/a/base.apk", "/a/lib", "/a/out", ""),
                    DaemonLauncher.Companion.buildAccSentryWatchdogScript("/a/base.apk", ""),
                    telegram(),
                    TailscaleLauncher.buildDashboardServeGuardScript());
            String[] namen = {"start_cam_daemon.sh", "start_acc_sentry.sh", "start_telegram.sh",
                    "secure_dashboard_serve.sh"};
            for (int i = 0; i < namen.length; i++) {
                File ziel = new File(dir, namen[i]);
                // altes längeres Skript liegt schon da
                Files.write(ziel.toPath(), (String.join("\n", alle.get(i)) + "\nfi\nfi\n" + String.join("\n", alle.get(i)) + "\n")
                        .getBytes(StandardCharsets.UTF_8));
                LogArchivShellTest.fuehreAus(DaemonLauncher.Companion.buildWatchdogScriptWriteCommand(
                        LogArchivShellTest.pfad(ziel), alle.get(i)));
                assertEquals(namen[i], String.join("\n", alle.get(i)) + "\n", lies(ziel));
                assertEquals(namen[i], 0, shN(ziel));
            }
            keineTempReste(dir);
        } finally {
            weg(dir);
        }
    }

    @Test
    public void quelleSchreibtNirgendsMehrDirektInsZiel() throws Exception {
        String launcher = quelle("app/src/main/java/at/greenchili/surfmate/launcher/DaemonLauncher.kt");
        String tel = launcher.substring(launcher.indexOf("private fun writeTelegramWatchdogScript("));
        tel = tel.substring(0, tel.indexOf("adbShellExecutor.execute("));
        assertTrue(tel.contains("buildWatchdogScriptWriteCommand(scriptPath, scriptLines)"));
        assertFalse("echo-Schreibweg ist zurueck", tel.contains("echo \\\""));

        String acc = quelle("app/src/main/java/at/greenchili/surfmate/daemon/AccSentryDaemon.java");
        assertTrue(acc.contains(".buildScriptLineWriteCommand(line, first, tmpPath)"));
        assertTrue(acc.contains(".buildSkriptEinsetzenCommand("));
        assertFalse(acc.contains(".buildScriptLineWriteCommand(line, first, scriptPath)"));

        String tg = quelle("app/src/main/java/at/greenchili/surfmate/daemon/telegram/DaemonCommandHandler.java");
        assertFalse(tg.contains("\"cat > \" + scriptPath"));
        assertFalse(tg.contains("\"cat > \" + TAILSCALE_DASHBOARD_GUARD_SCRIPT"));
        assertEquals(3, zaehle(tg, "DaemonLauncher.buildSkriptEinsetzenCommand("));
    }

    private static int zaehle(String s, String teil) {
        int n = 0;
        for (int i = s.indexOf(teil); i >= 0; i = s.indexOf(teil, i + 1)) n++;
        return n;
    }

    private static String quelle(String rel) throws Exception {
        Path p = Paths.get(rel);
        if (!Files.exists(p)) p = Paths.get("..").resolve(rel);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
