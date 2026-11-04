package at.greenchili.surfmate.tacho;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** 0.1.38.5: Der Launcher startet die Navi-App direkt; der Kern nennt nur bekannte Navi-Pakete, nie Panoptes selbst. */
public class TachoNaviAppLauncherTest {

    @Test
    public void bekannteNaviPaketeOhnePanoptes() {
        List<String> l = TachoNaviApp.naviPakete();
        assertFalse(l.contains("at.greenchili.surfmate"));
        assertTrue(l.contains("com.waze"));
        assertTrue(l.contains("com.google.android.apps.maps"));
        assertEquals(TachoNaviApp.NAVI_PAKETE.size() - 1, l.size());
    }
}
