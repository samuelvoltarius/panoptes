package at.greenchili.surfmate.mqtt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.wireguard.WireGuardPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Guards the WireGuard SOCKS listener's place in the proxy selection. */
public class ProxyHelperWireGuardContractTest {

    @Test
    public void wireGuardFlagIsParsedLikeTheTailscaleFlag() {
        // The launcher writes the flag with `echo true > file`
        assertTrue(ProxyHelper.isProxyEnabledValue("true\n"));
        assertEquals(
                "/data/user_de/0/com.android.shell/surfmate/.wireguard/proxy_enabled",
                WireGuardPaths.PROXY_FLAG);
    }

    @Test
    public void probeOrderIsTailscaleThenWireGuardNoSingBox() throws Exception {
        String source = read("app/src/main/java/at/greenchili/surfmate/mqtt/ProxyHelper.java");

        int tailscale = source.indexOf("if (probePort(TAILSCALE_PROXY_PORT))");
        int wireGuard = source.indexOf("probePort(WIREGUARD_PROXY_PORT)");

        assertTrue(tailscale >= 0);
        assertTrue(wireGuard > tailscale);
        // sing-box (8219) ist seit 0.1.8 entfernt (Koordination 21.10.): kein Rückfall mehr dorthin.
        assertFalse(source.contains("probePort(PROXY_PORT)"));
        assertFalse(source.contains("8219"));
    }

    @Test
    public void wireGuardPortSpeaksSocksOnly() throws Exception {
        String source = read("app/src/main/java/at/greenchili/surfmate/mqtt/ProxyHelper.java");

        assertTrue(source.contains("private static final int WIREGUARD_PROXY_PORT = 8541;"));
        assertTrue(source.contains(
                "proxyPort == TAILSCALE_PROXY_PORT || proxyPort == WIREGUARD_PROXY_PORT"));
    }

    @Test
    public void failClosedRouteNamesTheExpectedPort() throws Exception {
        String source = read("app/src/main/java/at/greenchili/surfmate/mqtt/ProxyHelper.java");

        int failClosed = source.indexOf("public static Proxy getFailClosedHttpProxy()");
        int expected = source.indexOf("getExpectedProxyPort()", failClosed);
        assertTrue(failClosed >= 0 && expected > failClosed);
        assertTrue(source.contains(
                "return isFlagEnabled(PROXY_ENABLED_FILE) || isFlagEnabled(WIREGUARD_PROXY_ENABLED_FILE);"));
    }

    private static String read(String relativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return at.greenchili.surfmate.testutil.Quelltext.lies(candidate);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return at.greenchili.surfmate.testutil.Quelltext.lies(fromModule);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
