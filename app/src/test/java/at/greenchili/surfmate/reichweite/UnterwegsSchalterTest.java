package at.greenchili.surfmate.reichweite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Paths;

/** Koordination 0.1.9: Unterwegs-Hilfe hat einen eigenen Schalter (neu aus, Bestand mit Planer-Schlüssel an). */
public class UnterwegsSchalterTest {

    @Test
    public void nurAusdruecklichAn() throws Exception {
        assertFalse(RangeWatchDienst.an(null));
        assertFalse(RangeWatchDienst.an(new JSONObject()));
        assertFalse(RangeWatchDienst.an(new JSONObject().put("geraeteSchluessel", "x")));   // Schlüssel allein reicht nicht
        assertTrue(RangeWatchDienst.an(new JSONObject().put("unterwegs", true)));
        assertFalse(RangeWatchDienst.an(new JSONObject().put("unterwegs", false).put("memberToken", "m")));
    }

    @Test
    public void festschreibenBeimErstenStart() throws Exception {
        assertEquals(Boolean.FALSE, RangeWatchDienst.bestandsWert(null));                      // neue Installation
        assertEquals(Boolean.FALSE, RangeWatchDienst.bestandsWert(new JSONObject()));
        assertEquals(Boolean.TRUE, RangeWatchDienst.bestandsWert(new JSONObject().put("geraeteSchluessel", "enc:abc")));
        assertEquals(Boolean.TRUE, RangeWatchDienst.bestandsWert(new JSONObject().put("memberToken", "tok")));
        assertEquals(Boolean.FALSE, RangeWatchDienst.bestandsWert(new JSONObject().put("memberToken", "  ")));
        // schon gesetzt: nie überschreiben (auch nicht, wenn später ein Schlüssel dazukommt)
        assertNull(RangeWatchDienst.bestandsWert(new JSONObject().put("unterwegs", false).put("geraeteSchluessel", "x")));
    }

    @Test
    public void taktFragtOhneSchalterNieDenPlaner() throws Exception {
        String s = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/reichweite/RangeWatchDienst.java"));
        int takt = s.indexOf("void takt(long now)");
        int schalter = s.indexOf("if (!an(planner))", takt);
        int abruf = s.indexOf("planHolen(token", takt);
        assertTrue(schalter > takt && schalter < abruf);
        assertTrue(s.indexOf("festschreiben();", s.indexOf("public static synchronized void start()")) > 0);
    }
}
