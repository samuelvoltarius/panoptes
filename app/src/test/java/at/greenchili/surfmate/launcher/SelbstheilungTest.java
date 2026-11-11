package at.greenchili.surfmate.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.logging.LogArchivShellTest;

import org.junit.Assume;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 0.1.38.1 Fix „start_cam_daemon.sh fehlt nach dem Update“ (Auto 04.11., 01:04) und PFLICHT von Projektinhaber:
 * „so was muss bei jedem automatisch gehen, sobald das Update durch ist“.
 *
 * <p>Ursache: Der Post-Update-Reset löschte die Skripte, und der Schreiber starb mitten in den printf-Zeilen
 * ({@code .neu.1218} mit 166/227 Zeilen, Rechte 666). Seine argv ({@code sh -c "…start_cam_daemon.sh.neu.$$…"}) passte
 * auf die Kill-Muster ({@code pkill -9 -f start_cam_daemon} in CameraDaemon u. a.).
 */
public class SelbstheilungTest {

    private static final String BASIS = "/data/user_de/0/com.android.shell/surfmate";

    /** Alle Muster, mit denen Panoptes per Teilstring Prozesse beendet (Quellen siehe Übergabe A5). */
    private static final List<String> KILL_MUSTER = Arrays.asList(
            // UpdateLifecycle.hardResetDaemons
            "start_cam_daemon", "start_acc_sentry", "start_telegram", "byd_cam_daemon", "cam_daemon", "sentry_daemon",
            "acc_sentry_daemon", "telegram_bot_daemon", "sentry_proxy", "cloudflared", "zrok", "sing-box", "wgproxy",
            // DaemonLauncher.psAwkKillLine
            "acc_sentry", "start_telegram.sh",
            // AccSentryDaemon Park-Reaper
            "start_zrok", "start_singbox", "start_cloudflared", "start_tailscale", "tailscaled",
            // update-on-car.sh stop_daemons
            BASIS + "/start_");

    private static String quelle(String rel) throws Exception {
        Path p = Paths.get(rel);
        if (!Files.exists(p)) p = Paths.get("..").resolve(rel);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static List<String> kamera() {
        return DaemonLauncher.Companion.buildCamDaemonWatchdogScript("/a/base.apk", "/a/lib", "/a/out", "");
    }

    // ---------------------------------------------------------------- A3: kein Muster in der argv des Schreibers

    @Test
    public void tempNameUndSkriptAufrufEnthaltenKeinKillMuster() {
        for (String art : new String[] {DaemonLauncher.ART_KAMERA, DaemonLauncher.ART_WAECHTER, DaemonLauncher.ART_TELEGRAM}) {
            String ziel = DaemonLauncher.watchdogSkriptPfad(art);
            String tmp = DaemonLauncher.skriptTempPfad(ziel, "12345");
            assertTrue(tmp, tmp.startsWith(BASIS + "/" + DaemonLauncher.TEMP_PRAEFIX));
            // So sieht die argv der lang laufenden Shell des Schreibers aus (AdbShellExecutor.executeScript).
            String argv = "sh -c trap 'rm -f " + BASIS + "/.adb_script_1730000000_7.sh' EXIT; sh "
                    + BASIS + "/.adb_script_1730000000_7.sh";
            for (String m : KILL_MUSTER) {
                assertFalse(tmp + " trifft " + m, tmp.contains(m));
                assertFalse(argv + " trifft " + m, argv.contains(m));
            }
            assertNotEquals(DaemonLauncher.skriptTempPfad(ziel, "1"), DaemonLauncher.skriptTempPfad(ziel, "2"));
        }
    }

    @Test
    public void schreibwegeLaufenUeberSkriptdatei() throws Exception {
        String src = quelle("app/src/main/java/at/greenchili/surfmate/launcher/DaemonLauncher.kt");
        for (String fn : new String[] {"private fun writeCamDaemonScript(", "private fun writeWatchdogScript(",
                "private fun writeTelegramWatchdogScript("}) {
            String teil = src.substring(src.indexOf(fn));
            teil = teil.substring(0, teil.indexOf("\n    }\n") > 0 ? teil.indexOf("\n    }\n") : teil.length());
            // 0.1.38.3 (fix-waechter-skript): über skriptSchreibenMitWiederholung, weiter als Skriptdatei
            assertTrue(fn, teil.contains("skriptSchreibenMitWiederholung("));
            assertTrue(fn, teil.contains("adbShellExecutor.executeScript(scriptBody = writeCmd, callback = cb)"));
            assertFalse(fn, teil.contains("command = writeCmd"));
        }
    }

    // ---------------------------------------------------------------- A5: Schreiber stirbt → altes Ziel bleibt

    /** Der Schreibbefehl wird nach k Teilbefehlen abgeschnitten (= der Schreiber stirbt dort). */
    @Test
    public void schreiberStirbtMittendrinAltesZielBleibt() throws Exception {
        Assume.assumeTrue(LogArchivShellTest.shell() != null);
        File dir = Files.createTempDirectory("skript-tod").toFile();
        try {
            File ziel = new File(dir, "start_cam_daemon.sh");
            String alt = "#!/system/bin/sh\necho altes skript\n";
            Files.write(ziel.toPath(), alt.getBytes(StandardCharsets.UTF_8));
            String z = LogArchivShellTest.pfad(ziel);
            List<String> zeilen = kamera();
            String befehl = DaemonLauncher.Companion.buildWatchdogScriptWriteCommand(z, zeilen);
            // Grenzen nach der k-ten Skriptzeile, genau wie buildWatchdogScriptWriteCommand sie zusammensetzt
            String tmp = DaemonLauncher.skriptTempPfad(z, "$$");
            int pos = befehl.indexOf("rm -f " + tmp + " 2>/dev/null; ") + ("rm -f " + tmp + " 2>/dev/null; ").length();
            List<Integer> grenzen = new ArrayList<>();
            for (int i = 0; i < zeilen.size(); i++) {
                String teil = DaemonLauncher.buildScriptLineWriteCommand(zeilen.get(i), i == 0, tmp) + "; ";
                assertEquals(pos, befehl.indexOf(teil, pos));
                pos += teil.length();
                grenzen.add(pos);
            }
            assertTrue(befehl.substring(pos).startsWith("SK_N="));
            // 166 = Abbruchstelle am Auto (166/227 Zeilen in .neu.1218)
            for (int k : new int[] {1, 50, 166, zeilen.size() - 1, zeilen.size()}) {
                LogArchivShellTest.fuehreAus(befehl.substring(0, grenzen.get(k - 1)) + "\ntrue");
                assertEquals("Schreiber stirbt nach Zeile " + k, alt, lies(ziel));
            }
            // Ohne Abbruch wird ersetzt
            assertTrue(LogArchivShellTest.fuehreAus(befehl).contains("SKRIPT_OK"));
            assertEquals(String.join("\n", zeilen) + "\n", lies(ziel));
        } finally {
            weg(dir);
        }
    }

    @Test
    public void tempDateiIstNieFuerAlleBeschreibbar() throws Exception {
        Assume.assumeTrue(LogArchivShellTest.shell() != null);
        File dir = Files.createTempDirectory("skript-rechte").toFile();
        try {
            // Kann diese Shell Rechte überhaupt abbilden? (Windows: oft nicht.)
            String probe = LogArchivShellTest.fuehreAus("cd '" + LogArchivShellTest.pfad(dir) + "'; umask 000; : > p; "
                    + "chmod 600 p; stat -c %a p");
            Assume.assumeTrue("Shell bildet Dateirechte nicht ab", probe.trim().equals("600"));
            String tmp = LogArchivShellTest.pfad(new File(dir, ".pn_neu_x.1"));
            String out = LogArchivShellTest.fuehreAus("umask 000; "
                    + DaemonLauncher.buildScriptLineWriteCommand("echo a", true, tmp) + "; stat -c %a " + tmp
                    + "; : > frei; stat -c %a frei");
            String[] z = out.trim().split("\\s+");
            assertEquals("Temp-Datei mit umask 077", "600", z[0]);
            assertEquals("umask bleibt in der Unter-Shell", "666", z[1]);
        } finally {
            weg(dir);
        }
    }

    @Test
    public void liegengebliebeneTempDateienNurAeltereWerdenGeloescht() throws Exception {
        Assume.assumeTrue(LogArchivShellTest.shell() != null);
        File dir = Files.createTempDirectory("skript-reste").toFile();
        try {
            File ziel = new File(dir, "start_cam_daemon.sh");
            File altNeu = new File(dir, ".pn_neu_abc.111");
            File altAlt = new File(dir, "start_cam_daemon.sh.neu.1218");
            File frisch = new File(dir, ".pn_neu_abc.222");
            for (File f : new File[] {altNeu, altAlt, frisch}) Files.write(f.toPath(), "x\n".getBytes(StandardCharsets.UTF_8));
            long vorZehn = System.currentTimeMillis() - 10 * 60_000L;
            assertTrue(altNeu.setLastModified(vorZehn));
            assertTrue(altAlt.setLastModified(vorZehn));
            String out = LogArchivShellTest.fuehreAus(DaemonLauncher.Companion.buildWatchdogScriptWriteCommand(
                    LogArchivShellTest.pfad(ziel), kamera()));
            assertTrue(out, out.contains("SKRIPT_OK"));
            assertFalse(altNeu.exists());
            assertFalse(altAlt.exists());
            assertTrue("eine frische Temp (gleichzeitiger Schreiber) bleibt", frisch.exists());
        } finally {
            weg(dir);
        }
    }

    // ---------------------------------------------------------------- A1/A2: nichts löscht die Skripte mehr

    @Test
    public void postUpdateResetLaesstSkripteStehen() throws Exception {
        String src = quelle("app/src/main/java/at/greenchili/surfmate/updater/UpdateLifecycle.java");
        String skript = src.substring(src.indexOf("String script ="), src.indexOf("launcher.executeShellScript(script"));
        for (String s : new String[] {"start_cam_daemon.sh", "start_acc_sentry.sh", "start_telegram.sh"}) {
            for (String zeile : skript.split("\n")) {
                if (zeile.trim().startsWith("//")) continue;
                assertFalse("hardReset löscht " + s + ": " + zeile, zeile.contains("rm ") && zeile.contains(s));
            }
        }
    }

    @Test
    public void cameraDaemonBeendetWatchdogOhnePkillUndLoeschtNichts() throws Exception {
        String src = quelle("app/src/main/java/at/greenchili/surfmate/daemon/CameraDaemon.java");
        String fn = src.substring(src.indexOf("private static void killWatchdogWrapper()"));
        fn = fn.substring(0, fn.indexOf("\n    }\n"));
        assertFalse(fn.contains("\"pkill\""));
        assertFalse(fn.contains("start_cam_daemon.sh\")).delete()"));
        assertTrue(fn.contains("WatchdogSelbstpruefung.watchdogPids(skript)"));
    }

    /** Emulator 04.11.: main brach vor der Selbstprüfung ab (Native-Bibliothek) → sie muss gleich nach der Sperre starten. */
    @Test
    public void selbstpruefungStartetGanzFruehInDenDaemons() throws Exception {
        String cam = quelle("app/src/main/java/at/greenchili/surfmate/daemon/CameraDaemon.java");
        String main = cam.substring(cam.indexOf("public static void main(String[] args)"));
        int sperre = main.indexOf("acquireSingletonLock()");
        int start = main.indexOf("WatchdogSelbstpruefung.start(");
        assertTrue(sperre > 0 && start > sperre);
        assertTrue("vor dem restlichen Start", start < main.indexOf("UnifiedConfigManager.init();"));
        String acc = quelle("app/src/main/java/at/greenchili/surfmate/daemon/AccSentryDaemon.java");
        String accMain = acc.substring(acc.indexOf("public static void main(String[] args)"));
        assertTrue(accMain.indexOf("WatchdogSelbstpruefung.start(") < accMain.indexOf("device_config")
                || accMain.indexOf("device_config") < 0);
        assertTrue(accMain.indexOf("WatchdogSelbstpruefung.start(") > 0);
    }

    // ---------------------------------------------------------------- A4: Anhänge-Modus, nie ein zweiter Daemon

    @Test
    public void alleWatchdogSkripteHabenDenAnhaengeModus() {
        List<List<String>> alle = Arrays.asList(kamera(),
                DaemonLauncher.Companion.buildAccSentryWatchdogScript("/a/base.apk", ""),
                DaemonLauncher.Companion.buildTelegramWatchdogScript("/a/base.apk", ""));
        String[] namen = {"byd_cam_daemon", "acc_sentry_daemon", "telegram_bot_daemon"};
        for (int i = 0; i < 3; i++) {
            List<String> z = alle.get(i);
            int marke = z.indexOf(DaemonLauncher.ANHAENGEN_MARKE);
            int sperre = z.indexOf("if ! acquire_watchdog_lock; then");
            int archiv = z.indexOf("log_archivieren \"$LOG_FILE\"");
            assertTrue(namen[i], marke > sperre && sperre > 0);
            assertTrue(namen[i] + ": erst anhängen, dann das alte Log archivieren", archiv > marke);
            assertTrue(String.join("\n", z).contains("= \"" + namen[i] + "\""));
        }
        // 0.1.40 W1: +61 Zeilen Lock-Übernahme mit Alter, Übernahme-Sperre, exklusiver Eigentümer-Datei und Log-Zeilen.
        assertEquals("Kamera-Skript (am Auto 227 Zeilen) + Anhänge-Modus + W1-Sperre", 312, kamera().size());
    }

    /**
     * Eindeutiger Prozessname je Testlauf. Die Prozesse werden über /proc nach argv[0] gezählt, und /proc zeigt alle
     * Prozesse der Maschine: Laufen mehrere Suiten oder Sitzungen gleichzeitig (jede mit eigenem „byd_cam_daemon“-Ersatz),
     * zählte die Zählung fremde Ersatz-Daemons mit. Darum bekommt jeder Test einen eigenen Namen und das Skript wird für
     * den Test genau darauf umgeschrieben (Produktverhalten unverändert: es ist nur der Name).
     */
    private static String eindeutig(String name) {
        return name + "_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** Ersetzt den Daemon-Namen im Skripttext (nur für den Test). */
    private static String fuer(String text, String name) {
        return text.replace("byd_cam_daemon", name);
    }

    /** Ersatz-Daemon: eine Shell mit argv[0] = name, die schläft. Gibt {pid (Shell-Sicht), Prozess} zurück. */
    private static Object[] ersatzDaemon(File dir, String name) throws Exception {
        File f = new File(dir, "ersatz_" + name + ".sh");
        Files.write(f.toPath(), ("echo $$\nexec -a " + name + " sleep 120\n").getBytes(StandardCharsets.UTF_8));
        Process p = new ProcessBuilder(bash(), LogArchivShellTest.pfad(f)).start();
        String pid = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)).readLine();
        Thread.sleep(300);
        return new Object[] {pid == null ? "" : pid.trim(), p};
    }

    private static String sh(String skript) throws Exception {
        return LogArchivShellTest.fuehreAus(skript);
    }

    private static int anzahl(String name) throws Exception {
        String out = sh("N=0; for d in /proc/[0-9]*; do [ \"$(tr '\\000' '\\n' 2>/dev/null < $d/cmdline | head -n 1)\" = \""
                + name + "\" ] && N=$((N+1)); done; echo $N");
        return Integer.parseInt(out.trim());
    }

    /**
     * Shell für die Ersatz-Daemons: sie brauchen {@code exec -a} (argv[0] setzen), das {@code dash} (Ubuntu-{@code /bin/sh}, CI)
     * nicht kennt. Darum bash, wo es sie gibt; unter Windows die Git-Shell (ist bash). Das Skript unter Test läuft weiter
     * mit {@link LogArchivShellTest#shell()}.
     */
    private static String bash() {
        for (String p : new String[] {"/bin/bash", "/usr/bin/bash"}) {
            if (new File(p).canExecute() && !System.getProperty("os.name", "").toLowerCase().contains("win")) return p;
        }
        return LogArchivShellTest.shell();
    }

    private static void prozessCheck() throws Exception {
        Assume.assumeTrue(LogArchivShellTest.shell() != null && bash() != null);
        // Die Probe darf nie selbst rot werden: ohne exec -a (oder /proc) wird nur übersprungen, der Exit-Code zählt nicht
        String probe;
        try {
            File f = File.createTempFile("probe", ".sh");
            try {
                Files.write(f.toPath(), ("(exec -a pn_probe_argv0 sleep 3) & P=$!\nsleep 1\n"
                        + "tr '\\000' '\\n' < /proc/$P/cmdline | head -n 1\nkill $P 2>/dev/null\nexit 0\n").getBytes(StandardCharsets.UTF_8));
                Process p = new ProcessBuilder(bash(), LogArchivShellTest.pfad(f)).redirectErrorStream(true).start();
                probe = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                p.waitFor();
            } finally {
                f.delete();
            }
        } catch (Exception e) {
            probe = "";
        }
        Assume.assumeTrue("Shell ohne exec -a bzw. /proc", probe.trim().equals("pn_probe_argv0"));
    }

    /** Nur der Anhänge-Block des Kamera-Skripts, danach „NORMALER_START“ statt der Schleife. */
    private static File anhaengeSkript(File dir, String name) throws Exception {
        List<String> z = kamera();
        int von = z.indexOf(DaemonLauncher.ANHAENGEN_MARKE);
        // das erste „fi“ ohne Einrückung schließt den Block
        int ende = von;
        while (!z.get(ende).equals("fi")) ende++;
        StringBuilder sb = new StringBuilder("LOG_FILE=\"" + LogArchivShellTest.pfad(new File(dir, "log")) + "\"\n");
        for (String s : z.subList(von, ende + 1)) sb.append(fuer(s, name)).append('\n');
        sb.append("echo NORMALER_START\n");
        File f = new File(dir, "anhaengen.sh");
        Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        return f;
    }

    @Test
    public void anhaengenWartetAufDenLaufendenDaemonUndStartetErstDanach() throws Exception {
        prozessCheck();
        File dir = Files.createTempDirectory("anhaengen").toFile();
        String name = eindeutig("byd_cam_daemon");
        Object[] d = ersatzDaemon(dir, name);
        try {
            File f = anhaengeSkript(dir, name);
            Process w = new ProcessBuilder(LogArchivShellTest.shell(), LogArchivShellTest.pfad(f), "anhaengen", (String) d[0])
                    .redirectErrorStream(true).start();
            assertFalse("Watchdog wartet, solange der Daemon lebt", w.waitFor(3, TimeUnit.SECONDS));
            assertEquals("kein zweiter Daemon", 1, anzahl(name));
            sh("kill " + d[0]);
            assertTrue(w.waitFor(15, TimeUnit.SECONDS));
            String out = new String(w.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(out, out.contains("NORMALER_START"));
            assertTrue(lies(new File(dir, "log")).contains("angehaengt an laufenden Daemon (pid " + d[0] + ")"));
        } finally {
            ((Process) d[1]).destroyForcibly();
            sh("kill " + d[0] + " 2>/dev/null; true");
            weg(dir);
        }
    }

    @Test
    public void anhaengenMitFremderPidStartetKeinenZweitenDaemon() throws Exception {
        prozessCheck();
        File dir = Files.createTempDirectory("anhaengen-fremd").toFile();
        String name = eindeutig("byd_cam_daemon");
        Object[] d = ersatzDaemon(dir, name);
        Object[] fremd = ersatzDaemon(dir, eindeutig("irgendwas_anderes"));
        try {
            File f = anhaengeSkript(dir, name);
            Process w = new ProcessBuilder(LogArchivShellTest.shell(), LogArchivShellTest.pfad(f), "anhaengen", (String) fremd[0])
                    .redirectErrorStream(true).start();
            assertTrue(w.waitFor(30, TimeUnit.SECONDS));
            String out = new String(w.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(out, out.contains("NORMALER_START"));
            assertEquals(0, w.exitValue());
            assertEquals(1, anzahl(name));
        } finally {
            for (Object[] o : new Object[][] {d, fremd}) {
                ((Process) o[1]).destroyForcibly();
                sh("kill " + o[0] + " 2>/dev/null; true");
            }
            weg(dir);
        }
    }

    /**
     * Ganzer Weg wie am Auto, nur in einem Temp-Ordner: Daemon läuft, Skript und Watchdog fehlen. Die Selbstheilung
     * schreibt das Skript, hängt einen Watchdog an und startet keinen zweiten Daemon. Danach ist sie grün.
     */
    @Test
    public void selbstheilungSchreibtSkriptUndHaengtAnOhneZweitenDaemon() throws Exception {
        prozessCheck();
        File dir = Files.createTempDirectory("selbstheilung").toFile();
        String b = LogArchivShellTest.pfad(dir);
        String name = eindeutig("byd_cam_daemon");
        Object[] d = ersatzDaemon(dir, name);
        String wpid = null;
        try {
            // Der Name wird nur EINMAL ersetzt (am fertigen Heil-Skript); die erwarteten Zeilen bekommen dieselbe Ersetzung.
            List<String> roh = new ArrayList<>();
            for (String z : kamera()) roh.add(z.replace(BASIS, b));
            String heil = fuer(DaemonLauncher.buildSelbstheilSkript(DaemonLauncher.ART_KAMERA, roh).replace(BASIS, b), name);
            List<String> zeilen = new ArrayList<>();
            for (String z : roh) zeilen.add(fuer(z, name));
            // Sentinel gesetzt: nichts tun
            Files.write(new File(dir, "camera_daemon.disabled").toPath(), "x\n".getBytes(StandardCharsets.UTF_8));
            assertEquals("gesperrt", DaemonLauncher.selbstheilBefund(sh(heil)));
            new File(dir, "camera_daemon.disabled").delete();

            String out = sh(heil);
            assertEquals(out, "angehaengt", DaemonLauncher.selbstheilBefund(out));
            File skript = new File(dir, "start_cam_daemon.sh");
            assertTrue(skript.isFile());
            assertEquals(String.join("\n", zeilen) + "\n", lies(skript));
            // Watchdog läuft (Sperre + PID-Datei), hängt am Ersatz-Daemon, kein zweiter Daemon
            for (int i = 0; i < 50 && !new File(dir, "cam_watchdog.pid").isFile(); i++) Thread.sleep(100);
            wpid = lies(new File(dir, "cam_watchdog.pid")).trim();
            String argv = sh("tr '\\000' ' ' < /proc/" + wpid + "/cmdline");
            assertTrue(argv, argv.contains(b + "/start_cam_daemon.sh anhaengen " + d[0]));
            Thread.sleep(1500);
            assertEquals(1, anzahl(name));
            assertEquals("gruen", DaemonLauncher.selbstheilBefund(sh(heil)));

            // Skript weg, Watchdog lebt: nur neu schreiben
            assertTrue(skript.delete());
            assertEquals("skript_neu", DaemonLauncher.selbstheilBefund(sh(heil)));
            assertTrue(skript.isFile());
            assertEquals(1, anzahl(name));
            File[] reste = dir.listFiles((x, n) -> n.startsWith(DaemonLauncher.TEMP_PRAEFIX) || n.contains(".neu."));
            assertEquals(0, reste == null ? 0 : reste.length);
        } finally {
            if (wpid != null) sh("kill -9 " + wpid + " 2>/dev/null; true");
            ((Process) d[1]).destroyForcibly();
            sh("kill " + d[0] + " 2>/dev/null; true");
            weg(dir);
        }
    }

    @Test
    public void selbstheilBefundLesen() {
        assertEquals("angehaengt", DaemonLauncher.selbstheilBefund("SKRIPT_OK /x\nPNH angehaengt 4711\n"));
        assertEquals("skript_fehler", DaemonLauncher.selbstheilBefund("SKRIPT_FEHLER /x rc=2 zeilen=166/248"));
        assertEquals(null, DaemonLauncher.selbstheilBefund("irgendwas"));
        assertTrue(DaemonLauncher.selbstheilungOk("gruen"));
        assertTrue(DaemonLauncher.selbstheilungOk("angehaengt"));
        assertFalse(DaemonLauncher.selbstheilungOk("mehrere"));
        assertFalse(DaemonLauncher.selbstheilungOk("skript_fehler"));
    }

    // ---------------------------------------------------------------- PFLICHT 5: Update erst fertig, wenn grün

    @Test
    public void updaterBestaetigtSelbstErstMitGruenenSkripten() throws Exception {
        String a = quelle("app/src/main/assets/deploy/update-on-car.sh");
        assertEquals("beide Kopien gleich", a, quelle("deploy/update-on-car.sh"));
        assertTrue(a.contains("SK=$(skripte_befund)"));
        assertTrue(a.contains("&& [ \"$SK\" = \"ok\" ]; then"));
        assertTrue(a.contains("GESUND=$((GESUND + 1))"));
    }

    @Test
    public void skripteBefundImUpdateSkriptMeldetFehlendesSkript() throws Exception {
        prozessCheck();
        String a = quelle("app/src/main/assets/deploy/update-on-car.sh");
        String fn = a.substring(a.indexOf("skripte_befund() {"));
        fn = fn.substring(0, fn.indexOf("\n}\n") + 3);
        File dir = Files.createTempDirectory("update-befund").toFile();
        String name = eindeutig("byd_cam_daemon");
        fn = fuer(fn, name);
        Object[] d = ersatzDaemon(dir, name);
        try {
            String b = LogArchivShellTest.pfad(dir);
            // Test-Shell ohne pidof: dieselbe Zählung über argv[0]
            String pidof = "pidof() { for x in /proc/[0-9]*; do [ \"$(tr '\\000' '\\n' 2>/dev/null < $x/cmdline | head -n 1)\" = \"$1\" ] && printf '%s ' \"${x#/proc/}\"; done; echo; }\n";
            String kopf = "NEW=\"" + b + "\"\n" + pidof + fn;
            assertEquals("kamera:skript", sh(kopf + "skripte_befund").trim());
            Files.write(new File(dir, "start_cam_daemon.sh").toPath(), "echo ok\n".getBytes(StandardCharsets.UTF_8));
            assertEquals("kamera:watchdog", sh(kopf + "skripte_befund").trim());
            // läuft kein Daemon, gibt es nichts zu prüfen
            assertEquals("ok", sh("NEW=\"" + b + "\"\npidof() { echo; }\n" + fn + "skripte_befund").trim());
        } finally {
            ((Process) d[1]).destroyForcibly();
            sh("kill " + d[0] + " 2>/dev/null; true");
            weg(dir);
        }
    }

    // ---------------------------------------------------------------- Hilfen

    private static String lies(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static void weg(File dir) {
        File[] l = dir.listFiles();
        if (l != null) for (File f : l) {
            if (f.isDirectory()) weg(f);
            else f.delete();
        }
        dir.delete();
    }
}
