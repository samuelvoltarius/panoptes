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
        assertEquals("https://www.openstreetmap.org/?mlat=48.300000&mlon=14.275000#map=18/48.300000/14.275000",
                ParkingNotifier.osmUrl(48.3, 14.275));
    }

    @Test
    public void keinGoogleLinkInTelegram() throws Exception {
        for (String p : new String[] {"src/main/java/at/greenchili/surfmate/parking/ParkingNotifier.java",
                "src/main/java/at/greenchili/surfmate/daemon/telegram/WhereCommandHandler.java"}) {
            assertFalse(p, Quelltext.lies(Paths.get(p)).contains("maps.google.com"));
        }
    }
}
