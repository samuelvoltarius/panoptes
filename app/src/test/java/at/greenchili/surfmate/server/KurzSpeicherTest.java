package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public class KurzSpeicherTest {

    private int bauten;

    private String bau() {
        return "w" + ++bauten;
    }

    @Test
    public void teiltInnerhalbDerFrist() throws Exception {
        KurzSpeicher<String> k = new KurzSpeicher<>(2_000L);
        assertEquals("w1", k.hol(10_000L, this::bau));
        assertEquals("w1", k.hol(11_999L, this::bau));
        assertEquals("w2", k.hol(12_000L, this::bau));
    }

    @Test
    public void leerenErzwingtFrischeAntwort() throws Exception {
        KurzSpeicher<String> k = new KurzSpeicher<>(2_000L);
        k.hol(10_000L, this::bau);
        k.leeren();
        assertEquals("w2", k.hol(10_001L, this::bau));
    }

    @Test
    public void uhrRueckwaertsBautNeu() throws Exception {
        KurzSpeicher<String> k = new KurzSpeicher<>(2_000L);
        k.hol(10_000L, this::bau);
        assertEquals("w2", k.hol(9_000L, this::bau));
    }

    @Test
    public void fehlerWirdNichtGemerkt() throws Exception {
        KurzSpeicher<String> k = new KurzSpeicher<>(2_000L);
        try {
            k.hol(10_000L, () -> { throw new IllegalStateException("x"); });
            fail();
        } catch (IllegalStateException erwartet) {
            assertEquals("w1", k.hol(10_001L, this::bau));
        }
    }
}
