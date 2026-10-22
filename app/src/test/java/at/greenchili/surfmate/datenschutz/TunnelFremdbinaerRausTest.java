package at.greenchili.surfmate.datenschutz;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/**
 * Koordination 21.10.: cloudflared und zrok waren veränderte, UPX-gepackte Fremd-Binärdateien aus der
 * Overdrive-Zeit ohne Quellcode. Sie werden nicht ausgeliefert, auch nicht ausgeschaltet.
 * wireguard (eigener Build, tools/wireguard) und tailscale (Build-Skript + Patches) bleiben.
 */
public class TunnelFremdbinaerRausTest {

    private static final Path JNI = Paths.get("src/main/jniLibs");

    @Test
    public void keineCloudflaredOderZrokBinaerdatei() throws Exception {
        try (Stream<Path> s = Files.walk(JNI)) {
            assertFalse(s.map(p -> p.getFileName().toString().toLowerCase())
                    .anyMatch(n -> n.contains("cloudflared") || n.contains("zrok")));
        }
        assertTrue(Files.exists(JNI.resolve("arm64-v8a/libwireguard.so")));
        assertTrue(Files.exists(JNI.resolve("arm64-v8a/libtailscale.so")));
    }

    @Test
    public void keinDienstTypUndKeinStarter() throws Exception {
        String typ = new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/ui/model/DaemonType.kt")), StandardCharsets.UTF_8);
        assertFalse(typ.contains("CLOUDFLARED_TUNNEL"));
        assertFalse(typ.contains("ZROK_TUNNEL"));
        assertTrue(typ.contains("TAILSCALE_TUNNEL"));
        assertTrue(typ.contains("WIREGUARD_TUNNEL"));
        String j = "src/main/java/at/greenchili/surfmate/";
        assertFalse(Files.exists(Paths.get(j + "launcher/TunnelLauncher.kt")));
        assertFalse(Files.exists(Paths.get(j + "launcher/ZrokLauncher.kt")));
        assertFalse(Files.exists(Paths.get(j + "ui/daemon/CloudflaredController.kt")));
        assertFalse(Files.exists(Paths.get(j + "ui/daemon/ZrokController.kt")));
    }
}
