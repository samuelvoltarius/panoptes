package at.greenchili.surfmate.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.BuildConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Regression coverage for version-aware Tailscale native deployment. */
public class TailscaleDeploymentContractTest {

    @Test
    public void deploymentProbeRequiresBothExecutablesAndCurrentAppVersion() {
        String command = TailscaleLauncher.deploymentStatusCommand();

        assertTrue(command.contains("test -x /data/user_de/0/com.android.shell/surfmate/.tailscale/tailscale"));
        assertTrue(command.contains("test -x /data/user_de/0/com.android.shell/surfmate/.tailscale/tailscaled"));
        assertTrue(command.contains("/data/user_de/0/com.android.shell/surfmate/.tailscale/installed_version"));
        assertTrue(command.contains(String.valueOf(BuildConfig.VERSION_CODE)));
        assertTrue(command.contains("printf 'current"));
        assertTrue(command.contains("printf 'stale"));
        assertFalse(command.contains("= \"-1\""));
    }

    @Test
    public void runningFastPathCannotSkipDeploymentAndStampIsWrittenLast()
            throws Exception {
        String source = read(
                "app/src/main/java/at/greenchili/surfmate/launcher/TailscaleLauncher.kt");

        int launch = source.indexOf("fun launchTailscale(");
        int deployment = source.indexOf("isDeploymentCurrent {", launch);
        int fingerprint = source.indexOf("getTailscaledFingerprint {", launch);
        int redeploy = source.indexOf(
                "redeployRunningTailscale(fingerprint, callback)", launch);
        assertTrue(launch >= 0);
        assertTrue(deployment > launch && deployment < fingerprint);
        assertTrue(redeploy > fingerprint);

        int install = source.indexOf("private fun installTailscale(");
        int nextMethod = source.indexOf("fun generateLoginUrl(", install);
        String installBody = source.substring(install, nextMethod);
        int copy = installBody.indexOf("cp -f");
        int link = installBody.indexOf("ln -sf");
        int chmod = installBody.indexOf("chmod +x");
        int stamp = installBody.indexOf("printf '$expectedVersion");
        assertTrue(copy >= 0 && copy < link);
        assertTrue(link < chmod);
        assertTrue(chmod < stamp);
    }

    @Test
    public void telegramDirectLaunchFailsClosedOnStalePayload() throws Exception {
        String source = read(
                "app/src/main/java/at/greenchili/surfmate/daemon/telegram/DaemonCommandHandler.java");
        int tailscaleCase = source.indexOf("case \"tailscale\":");
        int deployment = source.indexOf("deploymentStatusCommand()", tailscaleCase);
        int staleReturn = source.indexOf(
                "Tailscale binary is stale; waiting for app-side redeployment",
                deployment);
        int directLaunch = source.indexOf(
                "/data/user_de/0/com.android.shell/surfmate/.tailscale/tailscaled --tun", tailscaleCase);

        assertTrue(tailscaleCase >= 0);
        assertTrue(deployment > tailscaleCase);
        assertTrue(staleReturn > deployment && staleReturn < directLaunch);
        assertTrue(source.substring(staleReturn, directLaunch).contains("return false;"));
    }

    private static String read(String relativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
