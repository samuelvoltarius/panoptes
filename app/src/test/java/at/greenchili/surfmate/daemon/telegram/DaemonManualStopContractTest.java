package at.greenchili.surfmate.daemon.telegram;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class DaemonManualStopContractTest {

    @Test
    public void telegramStopIsIdempotentAndWritesTheSharedSentinel() {
        FakeContext context = new FakeContext();

        new DaemonCommandHandler().handle(
                1L, new String[]{"/daemon", "sentry", "stop"}, context);

        assertTrue(context.commands.stream().anyMatch(command ->
                command.contains("disabled by telegram")
                        && command.contains("sentry_daemon.disabled")));
        assertTrue(context.commands.stream()
                .filter(command -> command.contains("sentry_daemon"))
                .filter(command -> command.contains("ps -A"))
                .allMatch(command -> command.contains("grep -v acc_sentry_daemon")));
        assertTrue(context.commands.stream().anyMatch(command ->
                command.contains("sentry_daemon.pid")
                        && command.contains("127.0.0.1 19879")));
        assertTrue(context.messages.contains("tr:daemon.stopped"));
    }

    @Test
    public void telegramKenntSingboxNichtMehr() {
        // sing-box/GlobalProxy ist seit 0.1.8 entfernt (Koordination 21.10.): Telegram startet,
        // stoppt und meldet ihn nicht mehr und fasst die globalen Proxy-Einstellungen nicht an.
        FakeContext context = new FakeContext();

        new DaemonCommandHandler().handle(
                1L, new String[]{"/daemon", "singbox", "stop"}, context);

        assertFalse(context.commands.stream().anyMatch(command ->
                command.contains("global_http_proxy") || command.contains("singbox.disabled")
                        || command.contains("sing-box")));
        assertFalse(context.messages.contains("tr:daemon.stopped"));
    }

    @Test
    public void automaticStartupNeverClearsManualStopIntent() throws IOException {
        String startup = read(
                "app/src/main/java/at/greenchili/surfmate/ui/daemon/DaemonStartupManager.kt");

        assertTrue(startup.contains("'disabled by ui'*|'disabled by telegram'*"));
        assertTrue(startup.contains(
                "vm.startDaemon(DaemonType.CAMERA_DAEMON, userInitiated = false)"));
        assertTrue(startup.contains(
                "vm.startDaemon(DaemonType.SENTRY_DAEMON, userInitiated = false)"));
        assertTrue(startup.contains(
                "vm.startDaemon(DaemonType.ACC_SENTRY_DAEMON, userInitiated = false)"));
        assertTrue(count(startup,
                "ifNotUserStopped(DaemonType.CAMERA_DAEMON)") >= 2);
        assertTrue(count(startup,
                "ifNotUserStopped(DaemonType.SENTRY_DAEMON)") >= 2);
        assertTrue(count(startup,
                "ifNotUserStopped(DaemonType.ACC_SENTRY_DAEMON)") >= 2);
        assertTrue(startup.contains("leaving it stopped this tick"));

        int clear = startup.indexOf("fun clearStaleSentinels()");
        int next = startup.indexOf("fun stopHealthCheckThread()", clear);
        assertTrue(clear >= 0 && next > clear);
        assertFalse(startup.substring(clear, next).contains("rm -f"));
    }

    @Test
    public void tunnelSwitchAndMachineTransitionsPreserveManualIntent() throws IOException {
        String handler = read(
                "app/src/main/java/at/greenchili/surfmate/daemon/telegram/DaemonCommandHandler.java");
        String system = read(
                "app/src/main/java/at/greenchili/surfmate/daemon/telegram/SystemCommandHandler.java");
        String surveillance = read(
                "app/src/main/java/at/greenchili/surfmate/daemon/telegram/SurveillanceCommandHandler.java");
        String lifecycle = read(
                "app/src/main/java/at/greenchili/surfmate/updater/UpdateLifecycle.java");
        String updater = read(
                "app/src/main/java/at/greenchili/surfmate/updater/AppUpdater.java");
        String launcher = read(
                "app/src/main/java/at/greenchili/surfmate/launcher/DaemonLauncher.kt");
        String acc = read(
                "app/src/main/java/at/greenchili/surfmate/daemon/AccSentryDaemon.java");

        assertFalse(handler.contains("writeSentinel"));
        assertTrue(handler.contains("startDaemon(daemon[2], processName, ctx)"));
        assertFalse(handler.contains("className.toLowerCase()"));
        assertTrue(handler.contains("if (\"tailscaled\".equals(processName)) {"));
        assertTrue(system.contains(
                "DaemonCommandHandler.processMatcher(processName)"));
        // Panoptes: /status listet keine Dienste mehr (das macht /dienste),
        // daher kein Prozess-Abgleich im SurveillanceCommandHandler.
        assertTrue(lifecycle.contains(
                "[ -f /data/user_de/0/com.android.shell/surfmate/camera_daemon.disabled ] || "));
        // 0.1.9.1 (F16): Der Stopp-Sweep des alten Updaters ist mit seinem Installationsablauf entfernt.
        assertTrue(!updater.contains("private boolean stopAllDaemons()"));
        assertTrue(launcher.contains(
                "[ -f /data/user_de/0/com.android.shell/surfmate/acc_sentry_daemon.disabled ] || "));
        int privilegedKill = launcher.indexOf(
                "private fun killDaemonViaPrivilegedShell");
        int adbKill = launcher.indexOf(
                "private fun killDaemonViaAdb", privilegedKill);
        int bothKill = launcher.indexOf(
                "private fun killDaemonViaBothShells", adbKill);
        assertTrue(privilegedKill >= 0 && adbKill > privilegedKill
                && bothKill > adbKill);
        assertFalse(launcher.substring(privilegedKill, adbKill)
                .contains("disabled by ui"));
        assertFalse(launcher.substring(bothKill)
                .contains("disabled by ui"));
        assertTrue(acc.contains(
                "'disabled by ui'*|'disabled by telegram'*|'disabled by user'*"));
    }

    private static String read(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return at.greenchili.surfmate.testutil.Quelltext.lies(candidate);
            }
            Path fromModule = current.resolve(
                    relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return at.greenchili.surfmate.testutil.Quelltext.lies(fromModule);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }

    private static int count(String source, String needle) {
        int count = 0;
        int from = 0;
        while ((from = source.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private static final class FakeContext implements CommandContext {
        final List<String> commands = new ArrayList<>();
        final List<String> messages = new ArrayList<>();

        @Override
        public String tr(String key, Object... args) {
            return "tr:" + key;
        }

        @Override
        public boolean sendMessage(long chatId, String text) {
            messages.add(text);
            return true;
        }

        @Override
        public boolean sendMessageWithButtons(
                long chatId, String text, String[][][] buttons) {
            return true;
        }

        @Override
        public boolean sendVideo(long chatId, String videoPath, String caption) {
            return true;
        }

        @Override
        public JSONObject sendIpcCommand(int port, JSONObject command) {
            return null;
        }

        @Override
        public String execShell(String command) {
            commands.add(command);
            return "";
        }

        @Override
        public void log(String message) {}
    }
}
