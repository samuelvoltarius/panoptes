package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.byd.BydVehicleData;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

/**
 * 0.1.41 (LK): Kacheln „reifen“, „fahrt“, „tempo“ überstehen den Kern-Abgleich und stehen im Web-Bearbeiter;
 * die Reifenwarnung kommt nur aus den vorhandenen TPMS-Feldern (nur lesen), unbekannt = null.
 */
public class LauncherZusatzKachelnKernTest {

    @Test
    public void kachelnBleibenBeimKern() throws Exception {
        JSONArray seite = new JSONArray();
        String[] k = {"reifen", "fahrt", "tempo"};
        for (int i = 0; i < k.length; i++) {
            assertTrue(LauncherLayoutPruefung.KACHELN.contains(k[i]));
            seite.put(new JSONObject().put("id", "w" + i).put("typ", "widget").put("kachel", k[i])
                    .put("x", i * 2).put("y", 0).put("b", 2).put("h", 2));
        }
        JSONObject roh = new JSONObject().put("format", "panoptes-launcher-layout").put("version", 1)
                .put("seiten", new JSONArray().put(seite)).put("dock", new JSONArray())
                .put("hintergrund", "panoptes").put("zaehler", 9);
        LauncherLayoutPruefung.Ergebnis e = LauncherLayoutPruefung.pruefen(roh, Collections.<String>emptySet());
        assertEquals(0, e.bereinigt);
        JSONArray s = e.layout.getJSONArray("seiten").getJSONArray(0);
        assertEquals(3, s.length());
        for (int i = 0; i < k.length; i++) assertEquals(k[i], s.getJSONObject(i).getString("kachel"));
    }

    @Test
    public void reifenWarnungNurAusTpms() {
        assertEquals("leck_schnell", ReifenWarnung.fuer(BydVehicleData.TYRE_PRESSURE_STATE_UNDERPRESSURE, 2, 1));
        assertEquals("leck_langsam", ReifenWarnung.fuer(0, 1, 0));
        assertEquals("unterdruck", ReifenWarnung.fuer(BydVehicleData.TYRE_PRESSURE_STATE_UNDERPRESSURE, 0, 0));
        assertEquals("ueberdruck", ReifenWarnung.fuer(BydVehicleData.TYRE_PRESSURE_STATE_OVERPRESSURE, 0, 0));
        assertEquals("signal", ReifenWarnung.fuer(0, 0, 1));
        assertNull(ReifenWarnung.fuer(0, 0, 0));
        int u = BydVehicleData.UNAVAILABLE;
        assertNull("unbekannt → keine erfundene Warnung", ReifenWarnung.fuer(u, u, u));
        assertNull(ReifenWarnung.rad(null, null, null, 2));
        assertEquals("unterdruck", ReifenWarnung.rad(new int[] {0, 0, 2, 0}, new int[4], new int[4], 2));
        assertNull("kurzes Array", ReifenWarnung.rad(new int[] {2}, null, null, 3));
    }

    private static String lies(String pfad) throws Exception {
        File b = new File("src/main").isDirectory() ? new File(".") : new File("app");
        return new String(Files.readAllBytes(new File(b, pfad).toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void summaryUndWebBearbeiter() throws Exception {
        String api = lies("src/main/java/at/greenchili/surfmate/server/LauncherApiHandler.java");
        assertTrue(api.contains("ReifenWarnung.rad(vd.tyrePressureState, vd.tyreAirLeakState, vd.tyreSignalState, i)"));
        assertTrue(api.contains("o.put(\"warn\", warn);"));
        String h = lies("src/main/assets/web/local/launcher.html");
        assertTrue(h.contains("'projektion', 'reifen', 'fahrt', 'tempo', 'geraetewidget', 'sprache']"));   // 0.1.44 (P2)
        assertTrue(h.contains("reifen: 'Reifen', fahrt: 'Letzte Fahrt', tempo: 'Tempo'"));
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject j = new JSONObject(lies("src/main/assets/web/i18n/" + l + ".json")).getJSONObject("launcherlayout");
            for (String k : new String[] {"k_reifen", "k_fahrt", "k_tempo"}) assertTrue(l + " " + k, !j.getString(k).isEmpty());
        }
    }
}
