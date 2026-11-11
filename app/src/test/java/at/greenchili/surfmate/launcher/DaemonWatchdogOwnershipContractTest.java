package at.greenchili.surfmate.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import org.junit.Assume;
import org.junit.Test;

/** Regression coverage for process-safe camera and ACC watchdog ownership. */
public class DaemonWatchdogOwnershipContractTest {

    @Test
    public void cameraWatchdogAcquiresAtomicallyBeforeEnteringLoop()
            throws Exception {
        String script = join(DaemonLauncher.Companion
                .buildCamDaemonWatchdogScript(
                        "/data/app/base.apk",
                        "/data/app/lib",
                        "/sdcard/Android/data/at.greenchili.surfmate/files",
                        ""));

        assertOwnershipProtocol(script, "/data/user_de/0/com.android.shell/surfmate/start_cam_daemon.sh",
                "/data/user_de/0/com.android.shell/surfmate/cam_watchdog.pid",
                "/data/user_de/0/com.android.shell/surfmate/cam_watchdog.lock");
        assertTrue(script.indexOf("if ! acquire_watchdog_lock; then")
                < script.indexOf("while true; do"));
        assertShellSyntax(script);
    }

    /**
     * Three launchers start together and meet at the atomic mkdir. The
     * contract is "never two owners AT THE SAME TIME": each owner stamps
     * when it holds the lock ("belegt") and when it is about to release it
     * ("frei"); those intervals must not overlap. A launcher that starts
     * after the previous owner released may own legitimately afterwards
     * (CI 43043606191: slow process start). Only the first mkdir gets the
     * extra delay, so the test measures the fast path it was written for.
     */
    @Test
    public void concurrentLaunchCannotStealLockBeforeOwnerPublishesPid()
            throws Exception {
        Path shell = Paths.get("/bin/sh");
        Assume.assumeTrue(Files.isExecutable(shell));

        Path root = Files.createTempDirectory("surfmate-watchdog-race-");
        try {
            Round round = runConcurrentRound(shell, root, 3, false);
            assertTrue(round.report(), round.owners() >= 1);
            assertTrue("two owners at the same time\n" + round.report(),
                    round.overlap() == null);
            assertFalse(round.report(), Files.exists(root.resolve("cam_watchdog.pid")));
            assertFalse(round.report(), Files.exists(root.resolve("cam_watchdog.lock")));
            assertFalse(round.report(), Files.exists(root.resolve("cam_watchdog.lock.steal")));
        } finally {
            deleteTree(root);
        }
    }

    /** Result of one concurrent round: stamps, watchdog log and outputs. */
    static final class Round {
        final List<String> stamps;
        final String log;
        final String outputs;

        Round(List<String> stamps, String log, String outputs) {
            this.stamps = stamps;
            this.log = log;
            this.outputs = outputs;
        }

        int owners() {
            int n = 0;
            for (String s : stamps) if (s.contains(" belegt ")) n++;
            return n;
        }

        /** First pair of owners whose [belegt, frei] intervals overlap, or null. */
        String overlap() {
            java.util.Map<String, long[]> iv = new java.util.LinkedHashMap<>();
            for (String s : stamps) {
                String[] p = s.trim().split(" ");
                if (p.length != 3) continue;
                long[] v = iv.computeIfAbsent(p[0], k -> new long[] {-1, Long.MAX_VALUE});
                if ("belegt".equals(p[1])) v[0] = Long.parseLong(p[2]);
                if ("frei".equals(p[1])) v[1] = Long.parseLong(p[2]);
            }
            List<String> pids = new java.util.ArrayList<>(iv.keySet());
            for (int i = 0; i < pids.size(); i++) {
                for (int j = i + 1; j < pids.size(); j++) {
                    long[] a = iv.get(pids.get(i));
                    long[] b = iv.get(pids.get(j));
                    if (a[0] < b[1] && b[0] < a[1]) {
                        return pids.get(i) + " / " + pids.get(j);
                    }
                }
            }
            return null;
        }

        String report() {
            return "owners.txt:\n" + String.join("\n", stamps)
                    + "\nwatchdog log:\n" + log + "\noutputs:\n" + outputs;
        }
    }

    /**
     * Runs {@code contenders} launchers of the camera acquisition protocol at
     * once. {@code delayEveryMkdir} = the pre-W1 test setup (delay before
     * every {@code mkdir} of the lock dir, which since W1 also hits the
     * re-acquire inside the steal guard); otherwise only the first one.
     */
    static Round runConcurrentRound(Path shell, Path root, int contenders,
            boolean delayEveryMkdir) throws Exception {
        Path scriptPath = root.resolve("start_cam_daemon.sh");
        Path pidFile = root.resolve("cam_watchdog.pid");
        Path lockDir = root.resolve("cam_watchdog.lock");
        Path owners = root.resolve("owners.txt");
        Path logFile = root.resolve("watchdog.log");

        String generated = join(DaemonLauncher.Companion
                .buildCamDaemonWatchdogScript(
                        "/data/app/base.apk",
                        "/data/app/lib",
                        "/sdcard/Android/data/at.greenchili.surfmate/files",
                        ""));
        String acquisitionEnd = "if ! acquire_watchdog_lock; then\n"
                + "  exit 0\n"
                + "fi";
        int end = generated.indexOf(acquisitionEnd);
        assertTrue(end >= 0);
        String protocol = generated.substring(0, end + acquisitionEnd.length())
                .replace("/data/user_de/0/com.android.shell/surfmate/start_cam_daemon.sh",
                        scriptPath.toString())
                .replace("/data/user_de/0/com.android.shell/surfmate/cam_watchdog.pid",
                        pidFile.toString())
                .replace("/data/user_de/0/com.android.shell/surfmate/cam_watchdog.lock",
                        lockDir.toString())
                .replaceFirst("(?m)^LOG_FILE=.*$", java.util.regex.Matcher
                        .quoteReplacement("LOG_FILE=\"" + logFile + "\""));
        // Make the contenders reach the atomic directory acquisition together.
        String mkdir = "if mkdir \"$WATCHDOG_LOCK_DIR\" 2>/dev/null; then";
        String delayed = "sleep 1\n  " + mkdir;
        if (delayEveryMkdir) {
            protocol = protocol.replace(mkdir, delayed);
        } else {
            int first = protocol.indexOf(mkdir);
            assertTrue(first >= 0);
            protocol = protocol.substring(0, first) + delayed
                    + protocol.substring(first + mkdir.length());
        }
        protocol = protocol
                + "\necho \"$$ belegt $(date +%s%N)\" >> \"" + owners + "\"\n"
                + "sleep 1\n"
                + "echo \"$$ frei $(date +%s%N)\" >> \"" + owners + "\"\n";
        Files.write(scriptPath, protocol.getBytes(StandardCharsets.UTF_8));

        List<Process> started = new java.util.ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            started.add(new ProcessBuilder(shell.toString(), scriptPath.toString())
                    .redirectErrorStream(true)
                    .start());
        }
        StringBuilder outputs = new StringBuilder();
        for (Process p : started) {
            String out = new String(p.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            int code = p.waitFor();
            outputs.append("exit=").append(code).append(' ').append(out).append('\n');
            assertEquals(out, 0, code);
        }
        List<String> stamps = Files.exists(owners)
                ? Files.readAllLines(owners) : new java.util.ArrayList<>();
        String log = Files.exists(logFile)
                ? new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8) : "";
        return new Round(stamps, log, outputs.toString());
    }

    // ---- 0.1.40 W1: deterministic reproduction of the steal race ----------
    // CI 42976248463 / 42979087331: a second launcher saw the fresh lock dir
    // of the first one BEFORE its owner PID was written, judged it stale and
    // stole it; both ended up owning the camera watchdog. The tests below hold
    // that window open on purpose instead of hoping for a lucky interleaving.

    @Test
    public void freshOwnerlessLockOfCompetitorIsNeverStolen() throws Exception {
        Path shell = Paths.get("/bin/sh");
        Assume.assumeTrue(Files.isExecutable(shell));
        Path root = Files.createTempDirectory("surfmate-watchdog-gap-");
        Process first = null;
        Process second = null;
        try {
            Racefield f = new Racefield(root);
            // First contender stops for 5 s between mkdir and writing its PID.
            first = f.start(shell, "5", "0");
            assertTrue("first contender never created the lock dir",
                    waitFor(f.lockDir, 10_000));
            assertFalse(Files.exists(f.lockDir.resolve("pid")));
            // Second contender would hold the lock for 8 s if it (wrongly) won,
            // so with the old steal branch both would own it at the same time.
            second = f.start(shell, "", "8");
            assertEquals(f.output(second), 0, second.waitFor());
            assertTrue("fresh lock of the first contender was removed",
                    Files.isDirectory(f.lockDir));
            assertFalse("second contender became owner",
                    Files.exists(f.owners));
            assertEquals(f.output(first), 0, first.waitFor());

            List<String> owners = Files.readAllLines(f.owners);
            assertEquals(owners.toString(), 1, owners.size());
            String winner = owners.get(0).trim();
            String log = f.log();
            assertTrue(log, log.contains("Watchdog-Sperre: nicht uebernommen: frisch"));
            // Exactly one contender ever logged "belegt"; it is the owner.
            assertEquals(log, 1, count(log, "Watchdog-Sperre: belegt"));
            assertTrue(log, log.contains("Watchdog-Sperre: belegt (pid " + winner + ")"));
            assertTrue(log, log.contains("Watchdog-Sperre: freigegeben (pid " + winner + ")"));
            assertFalse(Files.exists(f.lockDir));
            assertFalse(Files.exists(f.pidFile));
            assertFalse(Files.exists(f.stealDir));
        } finally {
            if (first != null) first.destroyForcibly();
            if (second != null) second.destroyForcibly();
            deleteTree(root);
        }
    }

    /**
     * CI 43052239331 (Ubuntu 26.04): two racers BOTH got success from
     * {@code mkdir} on the same path, no one removed the lock in between.
     * Simulated deterministically with {@code mkdir -p} (always succeeds):
     * the second launcher must still stand down, because only one can
     * create the owner file exclusively, and must leave the lock alone.
     */
    @Test
    public void nonAtomicMkdirStillYieldsOneOwner() throws Exception {
        Path shell = Paths.get("/bin/sh");
        Assume.assumeTrue(Files.isExecutable(shell));
        Path root = Files.createTempDirectory("surfmate-watchdog-mkdirp-");
        Process first = null;
        Process second = null;
        try {
            Racefield f = new Racefield(root);
            String script = new String(Files.readAllBytes(f.script), StandardCharsets.UTF_8);
            String mkdir = "if mkdir \"$WATCHDOG_LOCK_DIR\" 2>/dev/null; then";
            assertTrue(script.contains(mkdir));
            Files.write(f.script, script.replace(mkdir,
                    "if mkdir -p \"$WATCHDOG_LOCK_DIR\" 2>/dev/null; then")
                    .getBytes(StandardCharsets.UTF_8));
            // First launcher owns and holds the lock for 6 s.
            first = f.start(shell, "", "6");
            assertTrue(waitFor(f.lockDir.resolve("pid"), 10_000));
            String owner = new String(Files.readAllBytes(f.lockDir.resolve("pid")),
                    StandardCharsets.UTF_8).trim();
            // Second launcher's "mkdir" succeeds too, like on the CI runner.
            second = f.start(shell, "", "6");
            assertEquals(f.output(second), 0, second.waitFor());
            assertEquals("second launcher replaced the owner", owner, new String(
                    Files.readAllBytes(f.lockDir.resolve("pid")),
                    StandardCharsets.UTF_8).trim());
            assertTrue(Files.isDirectory(f.lockDir));
            assertEquals(f.output(first), 0, first.waitFor());
            List<String> owners = Files.readAllLines(f.owners);
            assertEquals(f.log() + owners, 1, owners.size());
            assertEquals(owner, owners.get(0).trim());
            assertEquals(f.log(), 1, count(f.log(), "Watchdog-Sperre: belegt"));
            assertFalse(Files.exists(f.lockDir));
        } finally {
            if (first != null) first.destroyForcibly();
            if (second != null) second.destroyForcibly();
            deleteTree(root);
        }
    }

    @Test
    public void ownerlessLockOlderThanThresholdIsStolen() throws Exception {
        Path shell = Paths.get("/bin/sh");
        Assume.assumeTrue(Files.isExecutable(shell));
        Path root = Files.createTempDirectory("surfmate-watchdog-old-");
        try {
            Racefield f = new Racefield(root);
            Files.createDirectory(f.lockDir);
            age(f.lockDir, DaemonLauncher.WATCHDOG_LOCK_VERWAIST_SEKUNDEN + 60);
            Process p = f.start(shell, "", "0");
            assertEquals(f.output(p), 0, p.waitFor());
            assertEquals(1, Files.readAllLines(f.owners).size());
            String log = f.log();
            assertTrue(log, log.contains("Watchdog-Sperre: uebernommen (vorher: leer, "));
            assertFalse(Files.exists(f.lockDir));
            assertFalse(Files.exists(f.stealDir));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    public void foreignOwnerPidIsStolenImmediately() throws Exception {
        Path shell = Paths.get("/bin/sh");
        Assume.assumeTrue(Files.isExecutable(shell));
        Path root = Files.createTempDirectory("surfmate-watchdog-foreign-");
        try {
            Racefield f = new Racefield(root);
            // Lock from a previous boot: numeric PID that is not this watchdog
            // (PID 1 = init, alive but never the script). Fresh mtime on
            // purpose - a written PID never needs the age rule.
            String foreign = "1";
            Files.createDirectory(f.lockDir);
            Files.write(f.lockDir.resolve("pid"),
                    (foreign + "\n").getBytes(StandardCharsets.UTF_8));
            Process p = f.start(shell, "", "0");
            assertEquals(f.output(p), 0, p.waitFor());
            assertEquals(1, Files.readAllLines(f.owners).size());
            String log = f.log();
            assertTrue(log, log.contains(
                    "Watchdog-Sperre: uebernommen (vorher: " + foreign + ")"));
            assertFalse(Files.exists(f.lockDir));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    public void liveWatchdogOwnerIsNeverStolen() throws Exception {
        Path shell = Paths.get("/bin/sh");
        Assume.assumeTrue(Files.isExecutable(shell));
        Path root = Files.createTempDirectory("surfmate-watchdog-live-");
        Process owner = null;
        try {
            Racefield f = new Racefield(root);
            // A live process whose cmdline names the watchdog script.
            owner = new ProcessBuilder(shell.toString(), "-c", "echo $$; sleep 30; :",
                    f.script.toString()).start();
            String ownerPid = new java.io.BufferedReader(new java.io.InputStreamReader(
                    owner.getInputStream(), StandardCharsets.UTF_8)).readLine().trim();
            Files.createDirectory(f.lockDir);
            Files.write(f.lockDir.resolve("pid"),
                    (ownerPid + "\n").getBytes(StandardCharsets.UTF_8));
            age(f.lockDir, DaemonLauncher.WATCHDOG_LOCK_VERWAIST_SEKUNDEN + 60);
            Process p = f.start(shell, "", "0");
            assertEquals(f.output(p), 0, p.waitFor());
            assertFalse(Files.exists(f.owners));
            assertEquals(ownerPid, new String(
                    Files.readAllBytes(f.lockDir.resolve("pid")),
                    StandardCharsets.UTF_8).trim());
        } finally {
            if (owner != null) owner.destroyForcibly();
            deleteTree(root);
        }
    }

    @Test
    public void stealIsSerializedByStealGuard() throws Exception {
        Path shell = Paths.get("/bin/sh");
        Assume.assumeTrue(Files.isExecutable(shell));
        Path root = Files.createTempDirectory("surfmate-watchdog-guard-");
        try {
            Racefield f = new Racefield(root);
            String foreign = "1";
            Files.createDirectory(f.lockDir);
            Files.write(f.lockDir.resolve("pid"),
                    (foreign + "\n").getBytes(StandardCharsets.UTF_8));
            // Another stealer is mid-steal: this one must stand down and
            // leave the lock alone (no second clear of the same lock).
            Files.createDirectory(f.stealDir);
            Process blocked = f.start(shell, "", "0");
            assertEquals(f.output(blocked), 0, blocked.waitFor());
            assertFalse(Files.exists(f.owners));
            assertTrue(Files.isDirectory(f.lockDir));
            assertTrue(Files.isDirectory(f.stealDir));

            // A guard left behind by a killed stealer expires, then the next
            // launch heals the stale lock.
            age(f.stealDir, DaemonLauncher.WATCHDOG_STEAL_VERWAIST_SEKUNDEN + 60);
            Process expire = f.start(shell, "", "0");
            assertEquals(f.output(expire), 0, expire.waitFor());
            assertFalse(Files.exists(f.stealDir));
            assertFalse(Files.exists(f.owners));
            Process heal = f.start(shell, "", "0");
            assertEquals(f.output(heal), 0, heal.waitFor());
            assertEquals(1, Files.readAllLines(f.owners).size());
            assertTrue(f.log().contains(
                    "Watchdog-Sperre: alte Uebernahme-Sperre entfernt"));
        } finally {
            deleteTree(root);
        }
    }

    /** Camera watchdog acquisition protocol, rebased into a temp dir. */
    private static final class Racefield {
        final Path script;
        final Path pidFile;
        final Path lockDir;
        final Path stealDir;
        final Path owners;
        final Path logFile;

        Racefield(Path root) throws Exception {
            script = root.resolve("start_cam_daemon.sh");
            pidFile = root.resolve("cam_watchdog.pid");
            lockDir = root.resolve("cam_watchdog.lock");
            stealDir = root.resolve("cam_watchdog.lock.steal");
            owners = root.resolve("owners.txt");
            logFile = root.resolve("watchdog.log");
            String generated = join(DaemonLauncher.Companion
                    .buildCamDaemonWatchdogScript(
                            "/data/app/base.apk",
                            "/data/app/lib",
                            "/sdcard/Android/data/at.greenchili.surfmate/files",
                            ""));
            String acquisitionEnd = "if ! acquire_watchdog_lock; then\n"
                    + "  exit 0\n"
                    + "fi";
            int end = generated.indexOf(acquisitionEnd);
            assertTrue(end >= 0);
            String protocol = generated.substring(0, end + acquisitionEnd.length())
                    .replace("/data/user_de/0/com.android.shell/surfmate/start_cam_daemon.sh",
                            script.toString())
                    .replace("/data/user_de/0/com.android.shell/surfmate/cam_watchdog.pid",
                            pidFile.toString())
                    .replace("/data/user_de/0/com.android.shell/surfmate/cam_watchdog.lock",
                            lockDir.toString())
                    .replaceFirst("(?m)^LOG_FILE=.*$", java.util.regex.Matcher
                            .quoteReplacement("LOG_FILE=\"" + logFile + "\""));
            // Test hook: W1_LUECKE holds the window between the winning mkdir
            // and the owner PID write open (first, fast-path write only).
            String ownerWrite =
                    "    if ! (set -C; echo $$ > \"$WATCHDOG_LOCK_OWNER\") 2>/dev/null; then";
            int gap = protocol.indexOf(ownerWrite);
            assertTrue(gap >= 0);
            protocol = protocol.substring(0, gap)
                    + "    [ -z \"$W1_LUECKE\" ] || sleep \"$W1_LUECKE\"\n"
                    + protocol.substring(gap)
                    + "\necho $$ >> \"" + owners + "\"\n"
                    + "sleep \"${W1_HALTEN:-0}\"\n";
            Files.write(script, protocol.getBytes(StandardCharsets.UTF_8));
        }

        Process start(Path shell, String luecke, String halten) throws Exception {
            ProcessBuilder pb = new ProcessBuilder(shell.toString(), script.toString())
                    .redirectErrorStream(true);
            pb.environment().put("W1_LUECKE", luecke);
            pb.environment().put("W1_HALTEN", halten);
            return pb.start();
        }

        String output(Process p) throws Exception {
            return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }

        String log() throws Exception {
            return Files.exists(logFile)
                    ? new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8)
                    : "";
        }
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0;
                i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    private static boolean waitFor(Path path, long timeoutMs) throws Exception {
        long until = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < until) {
            if (Files.exists(path)) return true;
            Thread.sleep(20);
        }
        return Files.exists(path);
    }

    private static void age(Path path, long seconds) throws Exception {
        Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - seconds * 1000L));
    }

    @Test
    public void uiDeploymentPreservesWatchdogScriptsByteForByte()
            throws Exception {
        Path shell = Paths.get("/bin/sh");
        Assume.assumeTrue(Files.isExecutable(shell));

        Path root = Files.createTempDirectory("surfmate-watchdog-write-");
        try {
            List<String> camera = DaemonLauncher.Companion
                    .buildCamDaemonWatchdogScript(
                            "/data/app/base.apk",
                            "/data/app/lib",
                            "/sdcard/Android/data/at.greenchili.surfmate/files",
                            "");
            List<String> acc = DaemonLauncher.Companion
                    .buildAccSentryWatchdogScript(
                            "/data/app/base.apk", "");

            assertWriteRoundTrip(
                    shell, root.resolve("start_cam_daemon.sh"), camera);
            assertWriteRoundTrip(
                    shell, root.resolve("start_acc_sentry.sh"), acc);
        } finally {
            deleteTree(root);
        }
    }

    @Test
    public void deploymentPathsClearStaleOwnershipAfterStoppingOldWrapper()
            throws Exception {
        String launcher = readRepositoryFile(
                "app/src/main/java/at/greenchili/surfmate/launcher/DaemonLauncher.kt");
        String cameraLaunch = section(
                launcher,
                "private fun launchCameraDaemonInternal(",
                "private fun writeCamDaemonScript(");
        assertOrdered(cameraLaunch,
                "psAwkKillLine(\"cam_daemon\")",
                "sleep 1",
                "$CAMERA_WATCHDOG_PID_FILE",
                "rm -rf $CAMERA_WATCHDOG_LOCK_PATH");

        String accLaunch = section(
                launcher,
                "private fun launchAccSentryDaemonInternal(",
                "private fun writeWatchdogScript(");
        assertOrdered(accLaunch,
                "psAwkKillLine(\"acc_sentry\")",
                "sleep 1",
                "$ACC_SENTRY_WATCHDOG_PID_FILE ");
        assertTrue(accLaunch.contains(
                "rm -rf $ACC_SENTRY_WATCHDOG_LOCK_PATH"));

        String telegram = readRepositoryFile(
                "app/src/main/java/at/greenchili/surfmate/daemon/telegram/"
                        + "DaemonCommandHandler.java");
        String cameraTelegram = section(
                telegram,
                "private boolean startCameraDaemonWithWatchdog(",
                "private boolean startAccSentryDaemonWithWatchdog(");
        assertOrdered(cameraTelegram,
                "EigeneProzesse.beenden(\"cam_daemon\")",
                "sleep 1",
                "/data/user_de/0/com.android.shell/surfmate/cam_watchdog.lock");
        assertTrue(cameraTelegram.contains(
                "rm -rf /data/user_de/0/com.android.shell/surfmate/cam_watchdog.lock"));

        String accTelegram = section(
                telegram,
                "private boolean startAccSentryDaemonWithWatchdog(",
                "private boolean startTailscaleDashboardGuard(");
        assertOrdered(accTelegram,
                "EigeneProzesse.beenden(\"acc_sentry\")",
                "sleep 1",
                "/data/user_de/0/com.android.shell/surfmate/acc_sentry_watchdog.lock");
        assertTrue(accTelegram.contains(
                "rm -rf /data/user_de/0/com.android.shell/surfmate/acc_sentry_watchdog.lock"));

        String cameraController = readRepositoryFile(
                "app/src/main/java/at/greenchili/surfmate/ui/daemon/"
                        + "CameraDaemonController.kt");
        assertTrue(cameraController.contains(
                "rm -rf /data/user_de/0/com.android.shell/surfmate/cam_watchdog.lock"));

        String accController = readRepositoryFile(
                "app/src/main/java/at/greenchili/surfmate/ui/daemon/"
                        + "AccSentryDaemonController.kt");
        assertTrue(accController.contains(
                "rm -rf /data/user_de/0/com.android.shell/surfmate/acc_sentry_watchdog.lock"));
    }

    @Test
    public void accWatchdogOwnsLockBeforeMutatingLogOrDeviceConfig()
            throws Exception {
        String script = join(DaemonLauncher.Companion
                .buildAccSentryWatchdogScript(
                        "/data/app/base.apk", ""));

        assertOwnershipProtocol(script, "/data/user_de/0/com.android.shell/surfmate/start_acc_sentry.sh",
                "/data/user_de/0/com.android.shell/surfmate/acc_sentry_watchdog.pid",
                "/data/user_de/0/com.android.shell/surfmate/acc_sentry_watchdog.lock");
        int acquired = script.indexOf("if ! acquire_watchdog_lock; then");
        assertTrue(acquired < script.indexOf("/system/bin/device_config put"));
        assertTrue(acquired < script.indexOf("=== WATCHDOG STARTED ==="));
        assertShellSyntax(script);
    }

    private static void assertOwnershipProtocol(
            String script, String scriptPath, String pidFile, String lockFile) {
        assertTrue(script.contains("WATCHDOG_SCRIPT=\"" + scriptPath + "\""));
        assertTrue(script.contains("WATCHDOG_PID_FILE=\"" + pidFile + "\""));
        assertTrue(script.contains("WATCHDOG_LOCK_DIR=\"" + lockFile + "\""));
        assertTrue(script.contains(
                "WATCHDOG_LOCK_OWNER=\"$WATCHDOG_LOCK_DIR/pid\""));

        int atomicMkdir = script.indexOf(
                "if mkdir \"$WATCHDOG_LOCK_DIR\" 2>/dev/null; then");
        int ownerPidWrite =
                script.indexOf("echo $$ > \"$WATCHDOG_LOCK_OWNER\"");
        int publicPidWrite =
                script.indexOf("echo $$ > \"$WATCHDOG_PID_FILE\"");
        assertTrue(atomicMkdir >= 0);
        assertTrue(ownerPidWrite > atomicMkdir);
        assertTrue(publicPidWrite > ownerPidWrite);
        assertFalse(script.contains("if ln "));

        assertTrue(script.contains(
                "[ -r \"/proc/$CANDIDATE_PID/cmdline\" ] || return 1"));
        assertTrue(script.contains(
                "grep -F \"$WATCHDOG_SCRIPT\" >/dev/null 2>&1"));
        int identityCheck =
                script.indexOf("if watchdog_pid_matches \"$OLD_WPID\"; then");
        assertTrue(identityCheck >= 0);
        String acquisition = script.substring(
                script.indexOf("acquire_watchdog_lock() {"),
                script.indexOf("cleanup_watchdog_lock() {"));
        assertFalse(acquisition.contains(
                "rm -rf \"$WATCHDOG_LOCK_DIR\""));
        assertTrue(script.contains("trap 'cleanup_watchdog_lock' EXIT"));
        assertTrue(script.contains(
                "if [ \"$CURRENT_WPID\" = \"$$\" ]; then"));
        assertTrue(script.contains(
                "if [ \"$LEGACY_WPID\" = \"$$\" ]; then"));
        assertTrue(script.contains(
                "rmdir \"$WATCHDOG_LOCK_DIR\" 2>/dev/null"));

        // 0.1.40 W1: steal only under the steal guard, and an owner-less lock
        // only when it is old; every lock change is logged.
        int stealGuard = acquisition.indexOf(
                "if ! mkdir \"$WATCHDOG_STEAL_DIR\" 2>/dev/null; then");
        int ageRule = acquisition.indexOf(
                "[ \"$WATCHDOG_LOCK_AGE\" -lt \"$WATCHDOG_LOCK_STALE_S\" ]");
        int dropStale = acquisition.indexOf(
                "rm -f \"$WATCHDOG_PID_FILE\" 2>/dev/null");
        assertTrue(stealGuard >= 0);
        assertTrue(ageRule > stealGuard);
        assertTrue(dropStale > ageRule);
        assertTrue(script.contains("WATCHDOG_LOCK_STALE_S="
                + DaemonLauncher.WATCHDOG_LOCK_VERWAIST_SEKUNDEN));
        assertTrue(script.contains("watchdog_lock_log \"belegt\""));
        // The owner file is created exclusively in both acquisition paths.
        assertEquals(2, count(script,
                "if ! (set -C; echo $$ > \"$WATCHDOG_LOCK_OWNER\") 2>/dev/null; then"));
        assertTrue(script.contains("watchdog_lock_log \"freigegeben\""));
        assertTrue(script.contains("watchdog_lock_log \"uebernommen (vorher: $STALE_WPID)\""));

        // Ownership must come exclusively from atomic mkdir, not a
        // check-then-write PID file or Android-blocked hard link.
        assertFalse(script.contains(
                "if [ -f \"$WATCHDOG_PID_FILE\" ]; then"));
    }

    private static void assertWriteRoundTrip(
            Path shell, Path scriptPath, List<String> lines) throws Exception {
        String expected = String.join("\n", lines) + "\n";
        assertTrue(expected.contains("\\000"));

        String writeCommand = DaemonLauncher.Companion
                .buildWatchdogScriptWriteCommand(
                        scriptPath.toString(), lines);
        Process writer = new ProcessBuilder(
                shell.toString(), "-c", writeCommand)
                .redirectErrorStream(true)
                .start();
        byte[] writerOutput = writer.getInputStream().readAllBytes();
        assertEquals(
                new String(writerOutput, StandardCharsets.UTF_8),
                0,
                writer.waitFor());

        byte[] actualBytes = Files.readAllBytes(scriptPath);
        for (byte value : actualBytes) {
            assertTrue("deployed watchdog contains a binary NUL", value != 0);
        }
        assertEquals(expected,
                new String(actualBytes, StandardCharsets.UTF_8));

        Process syntax = new ProcessBuilder(
                shell.toString(), "-n", scriptPath.toString())
                .redirectErrorStream(true)
                .start();
        byte[] syntaxOutput = syntax.getInputStream().readAllBytes();
        assertEquals(
                new String(syntaxOutput, StandardCharsets.UTF_8),
                0,
                syntax.waitFor());
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                            // Best-effort test cleanup.
                        }
                    });
        }
    }

    private static String join(List<String> lines) {
        return String.join("\n", lines);
    }

    private static String section(
            String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue("missing section start: " + startMarker, start >= 0);
        assertTrue("missing section end: " + endMarker, end > start);
        return source.substring(start, end);
    }

    private static void assertOrdered(String source, String... fragments) {
        int previous = -1;
        for (String fragment : fragments) {
            int current = source.indexOf(fragment, previous + 1);
            assertTrue("missing/out-of-order fragment: " + fragment,
                    current > previous);
            previous = current;
        }
    }

    private static String readRepositoryFile(String relativePath)
            throws Exception {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(
                        Files.readAllBytes(candidate),
                        StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(
                    relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(
                        Files.readAllBytes(fromModule),
                        StandardCharsets.UTF_8);
            }
            current = at.greenchili.surfmate.testutil.RepoWurzel.hoch(current);
        }
        throw new AssertionError(
                "Could not locate repository file: " + relativePath);
    }

    private static void assertShellSyntax(String script) throws Exception {
        Path shell = Paths.get("/bin/sh");
        Assume.assumeTrue(Files.isExecutable(shell));
        Path file = Files.createTempFile("surfmate-watchdog-", ".sh");
        try {
            Files.write(file, script.getBytes(StandardCharsets.UTF_8));
            Process process = new ProcessBuilder(
                    shell.toString(), "-n", file.toString())
                    .redirectErrorStream(true)
                    .start();
            byte[] output = process.getInputStream().readAllBytes();
            int exit = process.waitFor();
            assertTrue("shell syntax error: "
                            + new String(output, StandardCharsets.UTF_8),
                    exit == 0);
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
