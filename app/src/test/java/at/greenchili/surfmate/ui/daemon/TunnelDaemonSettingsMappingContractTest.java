package at.greenchili.surfmate.ui.daemon;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Guards tunnel daemon rows against opening another tunnel's settings. */
public class TunnelDaemonSettingsMappingContractTest {

    // 0.1.39 A1: eachTunnelTypeOpensItsOwnSettingsUi entfällt – prüfte nur die gelöschte alte Oberfläche

    @Test
    public void daemonCardPassesTheBoundTypeToConfigureActions() throws IOException {
        String adapter = read(
                "app/src/main/java/at/greenchili/surfmate/ui/adapter/DaemonAdapter.kt");

        for (String type : new String[] {
                "TAILSCALE_TUNNEL", "WIREGUARD_TUNNEL"
        }) {
            assertTrue(adapter.contains("state.type == DaemonType." + type));
        }
        assertTrue(adapter.contains("onConfigureClick?.invoke(state.type)"));
        assertTrue(adapter.contains("onConfigureClick.invoke(state.type)"));
    }

    @Test
    public void eachTunnelTypeUsesItsMatchingController() throws IOException {
        String viewModel = read(
                "app/src/main/java/at/greenchili/surfmate/ui/viewmodel/DaemonsViewModel.kt");

        assertTrue(viewModel.contains(
                "DaemonType.TAILSCALE_TUNNEL to tailscaleController"));
        assertTrue(viewModel.contains(
                "DaemonType.WIREGUARD_TUNNEL to wireguardController"));

        assertControllerType("TailscaleController.kt", "TAILSCALE_TUNNEL");
        assertControllerType("WireGuardController.kt", "WIREGUARD_TUNNEL");
    }

    // 0.1.39 A1: settingsPaneHostsTheCanonicalDaemonScreen entfällt – prüfte nur die gelöschte alte Oberfläche

    private static void assertControllerType(String file, String type)
            throws IOException {
        String controller = read(
                "app/src/main/java/at/greenchili/surfmate/ui/daemon/" + file);
        assertTrue(controller.contains(
                "override val type = DaemonType." + type));
    }

    private static String read(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize();
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
}
