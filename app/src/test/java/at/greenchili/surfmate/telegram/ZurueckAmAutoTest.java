package at.greenchili.surfmate.telegram;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** 0.1.21 Telegram „zurück am Auto“: Standard aus, erst ab 10 min Parken, ohne Ort. */
public class ZurueckAmAutoTest {

    private static final long MIN = 60_000L;

    @Test
    public void meldungNurEingeschaltetUndNachParkzeit() {
        assertNull("Standard aus", ZurueckAmAuto.meldung(false, 1L, 1L + 60 * MIN, 0));
        assertNull("kurzes Aus/An", ZurueckAmAuto.meldung(true, 1L, 1L + 9 * MIN, 0));
        assertNull("unbekannt", ZurueckAmAuto.meldung(true, 0L, 60 * MIN, 0));
        assertArrayEquals(new Object[]{"zurueck.text", "2 h 14 min"}, ZurueckAmAuto.meldung(true, 1L, 1L + 134 * MIN, 0));
        assertArrayEquals(new Object[]{"zurueck.mit_ereignissen", "10 min", 3}, ZurueckAmAuto.meldung(true, 1L, 1L + 10 * MIN, 3));
        assertArrayEquals(new Object[]{"zurueck.text", "3 d 1 h"}, ZurueckAmAuto.meldung(true, 1L, 1L + 73 * 60 * MIN, 0));
    }

    @Test
    public void schalterStandardAus() throws Exception {
        assertFalse(ZurueckAmAuto.schalter(null));
        assertFalse(ZurueckAmAuto.schalter(new JSONObject()));
        assertFalse(ZurueckAmAuto.schalter(new JSONObject().put("telegram", new JSONObject())));
        assertTrue(ZurueckAmAuto.schalter(new JSONObject().put("telegram", new JSONObject().put("zurueckAmAuto", true))));    }
}
