package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;

/** 0.1.42 (L48): Der Kern-Abgleich behält die eigenen Launcher-Motive „motiv-1“ … „motiv-10“, alles andere wird „panoptes“. */
public class LauncherMotiveKernTest {

    private static JSONObject roh(String hg) throws Exception {
        return new JSONObject().put("format", "panoptes-launcher-layout").put("version", 1)
                .put("seiten", new JSONArray().put(new JSONArray())).put("dock", new JSONArray())
                .put("hintergrund", hg).put("zaehler", 1);
    }

    @Test
    public void motiveBleiben() throws Exception {
        for (int i = 1; i <= 20; i++) {   // 0.1.44 (D1): 11–14 Szenen; 0.1.43.4: 15–20 gerenderte Szenen
            LauncherLayoutPruefung.Ergebnis e = LauncherLayoutPruefung.pruefen(roh("motiv-" + i), Collections.<String>emptySet());
            assertEquals("motiv-" + i, e.layout.getString("hintergrund"));
            assertEquals(0, e.bereinigt);
        }
    }

    @Test
    public void unbekanntWirdPanoptes() throws Exception {
        for (String s : new String[] {"motiv-0", "motiv-21", "motiv-01", "motiv-", "motiv-x", "foo"}) {
            assertFalse(s, LauncherLayoutPruefung.hintergrundGueltig(s));
            assertEquals(s, "panoptes", LauncherLayoutPruefung.pruefen(roh(s), Collections.<String>emptySet()).layout.getString("hintergrund"));
        }
        assertTrue(LauncherLayoutPruefung.hintergrundGueltig("eigen"));
    }
}
