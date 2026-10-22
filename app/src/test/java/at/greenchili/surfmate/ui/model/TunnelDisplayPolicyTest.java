package at.greenchili.surfmate.ui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TunnelDisplayPolicyTest {

    @Test
    public void noConfiguredTunnelIsHiddenRatherThanConnecting() {
        TunnelDisplayPolicy.Result result =
                TunnelDisplayPolicy.resolve(null, DaemonStatus.STOPPED);

        assertEquals(TunnelDisplayPolicy.Kind.HIDDEN, result.getKind());
        assertFalse(result.isVisible());
        assertNull(result.getUrl());
    }

    @Test
    public void persistedUrlDoesNotMasqueradeAsOnline() {
        TunnelDisplayPolicy.Result result = TunnelDisplayPolicy.resolve(
                "https://stale.example", DaemonStatus.STOPPED);

        assertEquals(TunnelDisplayPolicy.Kind.HIDDEN, result.getKind());
        assertFalse(result.isOnline());
    }

    @Test
    public void startingAndWaitingAreExplicitStates() {
        assertEquals(TunnelDisplayPolicy.Kind.STARTING_TAILSCALE,
                TunnelDisplayPolicy.resolve(null, DaemonStatus.STARTING).getKind());
        assertEquals(TunnelDisplayPolicy.Kind.WAITING_FOR_URL,
                TunnelDisplayPolicy.resolve(null, DaemonStatus.RUNNING).getKind());
    }

    @Test
    public void stoppingTunnelStaysVisible() {
        TunnelDisplayPolicy.Result result =
                TunnelDisplayPolicy.resolve(null, DaemonStatus.STOPPING);

        assertEquals(TunnelDisplayPolicy.Kind.STOPPING, result.getKind());
        assertTrue(result.isVisible());
        assertFalse(result.isOnline());
    }

    @Test
    public void erroredTunnelStaysVisibleInsteadOfDisappearing() {
        TunnelDisplayPolicy.Result result =
                TunnelDisplayPolicy.resolve(null, DaemonStatus.ERROR);

        assertEquals(TunnelDisplayPolicy.Kind.FAILED, result.getKind());
        assertTrue(result.isVisible());
        assertFalse(result.isOnline());
    }

    @Test
    public void onlineRequiresRunningProcess() {
        TunnelDisplayPolicy.Result result = TunnelDisplayPolicy.resolve(
                "https://tailscale.example", DaemonStatus.RUNNING);

        assertEquals(TunnelDisplayPolicy.Kind.ONLINE, result.getKind());
        assertEquals("https://tailscale.example", result.getUrl());
        assertTrue(result.isOnline());
    }
}
