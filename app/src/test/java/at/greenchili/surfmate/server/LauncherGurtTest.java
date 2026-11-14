package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** 0.1.43.4 (Rang 4, Warnplaketten): Gurt im Launcher-Summary nur lesen, nur bekannte Werte, Beifahrer nur besetzt. */
public class LauncherGurtTest {

    @Test
    public void nurBekannteWerte() throws Exception {
        JSONObject g = LauncherApiHandler.gurt(new int[] {1, 0}, new int[] {1});
        assertTrue(g.getBoolean("fahrer"));
        assertFalse(g.getBoolean("beifahrer"));
        assertFalse(LauncherApiHandler.gurt(new int[] {0, 1}, new int[] {1}).getBoolean("fahrer"));
        // unbekannt (Sentinel, 2 = INVALID, fehlend) → Feld fehlt, nie geraten
        assertEquals(0, LauncherApiHandler.gurt(new int[] {Integer.MIN_VALUE, Integer.MIN_VALUE}, new int[] {1}).length());
        assertEquals(0, LauncherApiHandler.gurt(new int[] {2}, null).length());
        assertEquals(0, LauncherApiHandler.gurt(null, null).length());
    }

    @Test
    public void beifahrerNurBeiBelegtemSitz() throws Exception {
        assertFalse(LauncherApiHandler.gurt(new int[] {1, 0}, new int[] {0}).has("beifahrer"));
        assertFalse(LauncherApiHandler.gurt(new int[] {1, 0}, null).has("beifahrer"));
        assertFalse(LauncherApiHandler.gurt(new int[] {1}, new int[] {1}).has("beifahrer"));
        assertTrue(LauncherApiHandler.gurt(new int[] {1, 1}, new int[] {1}).getBoolean("beifahrer"));
    }
}
