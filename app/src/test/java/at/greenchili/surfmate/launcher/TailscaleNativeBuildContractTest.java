package at.greenchili.surfmate.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class TailscaleNativeBuildContractTest {

    @Test
    public void nativeBuildIsPinnedAndVerifiesHttpsSupportBeforeInstall() throws Exception {
        String script = read("tools/build-libtailscale.sh");
        String build = read("app/build.gradle.kts");

        assertTrue(script.contains("TS_VERSION=\"${1:-v1.96.4}\""));
        assertTrue(script.contains("GO_TOOLCHAIN=\"${GO_TOOLCHAIN:-go1.26.2}\""));
        assertTrue(script.contains("NDK_VERSION=\"${NDK_VERSION:-26.1.10909125}\""));
        assertFalse(script.contains("TAGS+=,ts_omit_acme"));
        assertTrue(build.contains("keepDebugSymbols += \"**/libtailscale.so\""));

        assertOrdered(
                script,
                "\"$LLVM_STRIP\" --strip-all",
                "upx --best",
                "upx -t",
                "grep -qa \"acme-v02.api.letsencrypt.org\"",
                "grep -qa \"tls-terminated-tcp\"",
                "grep -qa \"proxy-protocol\"",
                "grep -qa \"TS_NETSTACK_LOCAL_FORWARD_PORTS\"",
                "install -m 0644");
        assertOrdered(
                script,
                "apply \"$PATCH\"",
                "apply --check \"$NO_LOCAL_FORWARD_PATCH\"",
                "apply \"$NO_LOCAL_FORWARD_PATCH\"",
                "./wgengine/netstack",
                "go build");
    }

    @Test
    public void netstackPatchRefusesLoopbackForwardOfOwnTailnetAddresses() throws Exception {
        String patch = read("tools/patches/tailscale-no-local-forward.patch");

        // Userspace networking must not relay unserved tailnet ports to
        // 127.0.0.1: that is what made the loopback local API (8632) and the
        // loopback-only daemons (1997x) reachable from every peer.
        assertTrue(patch.contains("+++ b/wgengine/netstack/netstack.go"));
        assertTrue(patch.contains("+func localForwardAllowed(port uint16) bool {"));
        assertOrdered(
                patch,
                "+\t\tif !localForwardAllowed(reqDetails.LocalPort) {",
                "+\t\t\tr.Complete(true) // sends a RST",
                " \t\tdialIP = ipv4Loopback");
        assertTrue(patch.contains(
                "+\tif ns.isLocalIP(dstAddr.Addr()) && !localForwardAllowed(dstAddr.Port()) {"));
        assertTrue(patch.contains("+func TestLocalAddrNotForwardedToLoopback(t *testing.T) {"));
    }

    @Test
    public void localPatchKeepsAndroidControlSocketAndAcmeFallback() throws Exception {
        String patch = read("tools/patches/tailscale-tcp-socket.patch");

        assertTrue(patch.contains("func (ci *ConnIdentity) IsLoopbackTCP() bool"));
        assertTrue(patch.contains(
                "var acmeFallbackResolvers = []string{\"1.1.1.1:53\", \"8.8.8.8:53\"}"));
        assertTrue(patch.contains("func tcpAddr(path string) (string, bool)"));
    }

    private static void assertOrdered(String text, String... needles) {
        int previous = -1;
        for (String needle : needles) {
            int current = text.indexOf(needle);
            assertTrue("missing build step: " + needle, current >= 0);
            assertTrue("build step out of order: " + needle, current > previous);
            previous = current;
        }
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
