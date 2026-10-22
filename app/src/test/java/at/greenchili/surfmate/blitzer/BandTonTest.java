package at.greenchili.surfmate.blitzer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.byd.MessageOverlayController;
import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Paths;

/** 0.1.9.1 nach Alfreds Fahrt: Band groß/oben Mitte (einstellbar), Blitzer-Ton aus|hinweiston|ansage. */
public class BandTonTest {

    @Test
    public void bandStandardGrossObenMitte() throws Exception {
        assertArrayEquals(new String[] {"gross", "oben_mitte"}, MessageOverlayController.bandStil(null));
        assertArrayEquals(new String[] {"normal", "oben_rechts"}, MessageOverlayController.bandStil(new JSONObject()
                .put("anzeige", new JSONObject().put("bandGroesse", "normal").put("bandPosition", "oben_rechts"))));
        String cmd = MessageOverlayController.bandCommand("T", "Z", "knapp", 5000, "gross", "oben_mitte");
        assertTrue(cmd.contains("--es groesse") && cmd.contains("--es position"));
        String o = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/overlay/MessageOverlayService.java"));
        assertTrue(o.contains("Gravity.CENTER_HORIZONTAL"));
    }

    @Test
    public void tonMitMigration() throws Exception {
        assertEquals("aus", BlitzerDienst.ton(new JSONObject()));
        assertEquals("ansage", BlitzerDienst.ton(new JSONObject().put("ansage", true)));        // Bestand
        assertEquals("hinweiston", BlitzerDienst.ton(new JSONObject().put("ansage", true).put("ton", "hinweiston")));
        assertEquals("aus", BlitzerDienst.ton(new JSONObject().put("ton", "quatsch")));
    }
}
