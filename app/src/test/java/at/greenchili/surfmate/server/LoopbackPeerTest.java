package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LoopbackPeerTest {

    // Shape of /proc/net/tcp on the head unit: the client end of a loopback
    // connection to 8180 (0x1FF4) from port 0xADBA, plus the server end.
    private static final String TCP =
            "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n"
            + "   0: 0100007F:21B8 00000000:0000 0A 00000000:00000000 00:00000000 00000000  2000        0 2009535 1 0000000000000000 100 0 0 10 0\n"
            + "   8: 0100007F:ADBA 0100007F:1FF4 01 00000000:00000000 00:00000000 00000000  2000        0 4431740 2 0000000000000000 21 0 0 10 1400\n"
            + "   9: 0100007F:1FF4 0100007F:ADBA 01 00000000:00000000 00:00000000 00000000  2000        0 4431741 2 0000000000000000 21 0 0 10 1400\n";

    private static final String TCP6 =
            "  sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n"
            + "  12: 00000000000000000000000000000000:1FF4 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000  2000        0 4414633 1 0000000000000000 100 0 0 10 0\n"
            + "  13: 0000000000000000FFFF00000100007F:C001 0000000000000000FFFF00000100007F:1FF4 01 00000000:00000000 00:00000000 00000000 10099        0 4400001 1 0000000000000000 100 0 0 10 0\n"
            + "  14: 00000000000000000000000001000000:C002 00000000000000000000000001000000:1FF4 01 00000000:00000000 00:00000000 00000000 10099        0 4400002 1 0000000000000000 100 0 0 10 0\n";

    @Test
    public void findsClientEndOfLoopbackConnection() {
        assertEquals(4431740L, LoopbackPeer.findClientInode(TCP, 0xADBA, 8180));
        assertEquals(4400001L, LoopbackPeer.findClientInode(TCP6, 0xC001, 8180));
        assertEquals(4400002L, LoopbackPeer.findClientInode(TCP6, 0xC002, 8180));
    }

    @Test
    public void ignoresServerEndListenersAndOtherPorts() {
        // The server end has local=8180, remote=client — must not match.
        assertEquals(-1L, LoopbackPeer.findClientInode(TCP, 8180, 0xADBA + 1));
        assertEquals(-1L, LoopbackPeer.findClientInode(TCP, 0xADBB, 8180));
        assertEquals(-1L, LoopbackPeer.findClientInode(TCP6, 0, 8180));
        assertEquals(-1L, LoopbackPeer.findClientInode("", 0xADBA, 8180));
    }

    @Test
    public void recognisesLoopbackEndpointsOnly() {
        assertTrue(LoopbackPeer.isLoopbackEndpoint("0100007F:1FF4", 8180));
        assertTrue(LoopbackPeer.isLoopbackEndpoint(
                "0000000000000000FFFF00000100007F:1FF4", 8180));
        assertTrue(LoopbackPeer.isLoopbackEndpoint(
                "00000000000000000000000001000000:1FF4", 8180));
        assertFalse(LoopbackPeer.isLoopbackEndpoint("0100007F:1FF4", 8181));
        // 100.64.0.10 and 192.168.1.30 are not loopback.
        assertFalse(LoopbackPeer.isLoopbackEndpoint("396E4764:1FF4", 8180));
        assertFalse(LoopbackPeer.isLoopbackEndpoint("1E00A8C0:1FF4", 8180));
        assertFalse(LoopbackPeer.isLoopbackEndpoint("garbage", 8180));
    }
}
