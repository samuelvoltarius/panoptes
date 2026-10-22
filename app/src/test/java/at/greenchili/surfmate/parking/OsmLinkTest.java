package at.greenchili.surfmate.parking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import at.greenchili.surfmate.testutil.Quelltext;

import org.junit.Test;

import java.nio.file.Paths;

/** Koordination 0.1.9: Telegram-Parknachricht und /wo verlinken OpenStreetMap, keinen festen Google-Link. */
public class OsmLinkTest {

    @Test
    public void osmLinkMitMarkierung() {
        assertEquals("https://www.openstreetmap.org/?mlat=47.800000&mlon=13.040000#map=18/47.800000/13.040000",
                ParkingNotifier.osmUrl(47.8, 13.04));
    }

    @Test
    public void keinGoogleLinkInTelegram() throws Exception {
        for (String p : new String[] {"src/main/java/at/greenchili/surfmate/parking/ParkingNotifier.java",
                "src/main/java/at/greenchili/surfmate/daemon/telegram/WhereCommandHandler.java"}) {
            assertFalse(p, Quelltext.lies(Paths.get(p)).contains("maps.google.com"));
        }
    }
}
