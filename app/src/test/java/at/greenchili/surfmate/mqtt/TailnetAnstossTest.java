package at.greenchili.surfmate.mqtt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Deploy-Messung 21.10.: tailscaled nach Netzwechsel anstoßen, nie beenden. */
public class TailnetAnstossTest {

    private final List<String> befehle = new ArrayList<>();

    @Before
    public void vorher() {
        TailnetAnstoss.zuruecksetzen();
        TailnetAnstoss.cliPruefen = false;
        TailnetAnstoss.shell = (cmd, t) -> { befehle.add(cmd); return "pong"; };
    }

    @After
    public void nachher() {
        TailnetAnstoss.cliPruefen = true;
        TailnetAnstoss.zuruecksetzen();
    }

    @Test
    public void erkenntTailnetZiele() {
        assertTrue(TailnetAnstoss.istTailnetZiel("100.64.0.10"));
        assertTrue(TailnetAnstoss.istTailnetZiel("100.64.0.1"));
        assertTrue(TailnetAnstoss.istTailnetZiel("100.127.255.254"));
        assertFalse(TailnetAnstoss.istTailnetZiel("100.63.0.1"));
        assertFalse(TailnetAnstoss.istTailnetZiel("100.128.0.1"));
        assertFalse(TailnetAnstoss.istTailnetZiel("192.168.0.10"));
        assertFalse(TailnetAnstoss.istTailnetZiel("broker.example.org"));
        assertTrue(TailnetAnstoss.istTailnetZiel("ha.tail1234.ts.net"));
        assertTrue(TailnetAnstoss.istTailnetZiel("fd7a:115c:a1e0::1"));
        assertEquals("100.71.1.2", TailnetAnstoss.hostAus("tcp://100.71.1.2:1883"));
    }

    @Test
    public void taktung() {
        assertTrue(TailnetAnstoss.sollAnstossen(true, 0));
        assertFalse(TailnetAnstoss.sollAnstossen(false, 1));
        assertTrue(TailnetAnstoss.sollAnstossen(false, 2));
        assertFalse(TailnetAnstoss.sollAnstossen(false, 3));
        assertTrue(TailnetAnstoss.sollAnstossen(false, 4));
        assertTrue(TailnetAnstoss.sollRebind(true, 0, 100_000L, 0L));
        assertFalse("gedrosselt", TailnetAnstoss.sollRebind(true, 0, 100_000L, 90_000L));
        assertFalse(TailnetAnstoss.sollRebind(false, 2, 100_000L, 0L));
        assertTrue(TailnetAnstoss.sollRebind(false, 4, 100_000L, 0L));
    }

    @Test
    public void netzwechselRebindUndPingZumBroker() {
        assertTrue(TailnetAnstoss.anstossen("tcp://100.64.0.10:1883", true, 0));
        assertTrue(befehle.stream().anyMatch(b -> b.contains(" debug rebind")));
        assertTrue(befehle.stream().anyMatch(b -> b.contains(" ping -c 1 --timeout 3s 100.64.0.10")));
        // zweiter Netzwechsel sofort danach: kein zweiter Rebind (gedrosselt), aber Ping
        befehle.clear();
        assertTrue(TailnetAnstoss.anstossen("tcp://100.64.0.10:1883", true, 0));
        assertFalse(befehle.stream().anyMatch(b -> b.contains("debug rebind")));
        assertTrue(befehle.stream().anyMatch(b -> b.contains(" ping ")));
        nieGefaehrlich();
    }

    @Test
    public void brokerAusserhalbDesTailnetsUnberuehrt() {
        assertFalse(TailnetAnstoss.anstossen("tcp://192.168.0.10:1883", true, 4));
        assertFalse(TailnetAnstoss.anstossen("ssl://broker.example.org:8883", true, 4));
        assertTrue(befehle.isEmpty());
    }

    private void nieGefaehrlich() {
        for (String b : befehle) {
            assertFalse(b, b.contains("kill"));
            assertFalse(b, b.contains("tailscaled"));
            assertFalse(b, b.contains(" down"));
            assertFalse(b, b.contains(" up "));
            assertFalse(b, b.contains("logout"));
            assertFalse(b, b.contains("serve"));
        }
    }
}
