package at.greenchili.surfmate.datenschutz;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.einstellungen.Einstellungen;
import at.greenchili.surfmate.einstellungen.Feld;
import at.greenchili.surfmate.einstellungen.Kartenquelle;
import at.greenchili.surfmate.einstellungen.Katalog;
import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Regel Nr. 2 (Koordination 21.10.): Kartenquelle karte.quelle (community | osm | keine), Standard keine,
 * auch für Bestand. Die native Karte lädt nur von der gewählten Quelle, OpenFreeMap ist raus.
 */
public class KartenquelleTest {

    private static final class Merker implements Einstellungen.Schreiber {
        final Map<String, Map<String, Object>> geschrieben = new HashMap<>();
        public boolean schreibe(String a, Map<String, Object> w) {
            geschrieben.computeIfAbsent(a, x -> new HashMap<>()).putAll(w);
            return true;
        }
    }

    @Test
    public void feldStandardKeineInGruppeDatenschutz() {
        Feld f = StandardsAusTest.feld("karte.quelle");
        assertEquals(Feld.Typ.AUSWAHL, f.typ);
        assertEquals("datenschutz", f.gruppe);
        assertEquals("keine", f.standard);
        assertTrue(f.nurLesendExtern);
        assertEquals(3, f.optionen.size());
        boolean gruppe = false;
        for (Katalog.Gruppe g : Katalog.GRUPPEN) gruppe |= "datenschutz".equals(g.id);
        assertTrue(gruppe);
    }

    @Test
    public void bestandUndUnbekanntGeltenAlsKeine() throws Exception {
        assertEquals("keine", Kartenquelle.aus(new JSONObject()));
        assertEquals("keine", Kartenquelle.aus(null));
        assertEquals("keine", Kartenquelle.aus(new JSONObject("{\"karte\":{\"quelle\":\"openfreemap\"}}")));
        assertEquals("community", Kartenquelle.aus(new JSONObject("{\"karte\":{\"quelle\":\"community\"}}")));
        assertEquals("osm", Kartenquelle.aus(new JSONObject("{\"karte\":{\"quelle\":\"osm\"}}")));
    }

    @Test
    public void lokalSchreibbarExternNicht() throws Exception {
        Katalog.Kontext k = new Katalog.Kontext();
        JSONObject body = new JSONObject().put("werte", new JSONObject().put("karte.quelle", "community"));
        Merker m = new Merker();
        Einstellungen.Ergebnis e = Einstellungen.aendern(Katalog.felder(k), new JSONObject(), k, body, false, m, 0L);
        assertTrue(e.fehler.toString(), e.ok());
        assertEquals("community", m.geschrieben.get("karte").get("quelle"));

        Merker x = new Merker();
        Einstellungen.Ergebnis ex = Einstellungen.aendern(Katalog.felder(k), new JSONObject(), k, body, true, x, 0L);
        assertEquals("nurLesendExtern", ex.fehler.getString("karte.quelle"));
        assertFalse(x.geschrieben.containsKey("karte"));

        Einstellungen.Ergebnis falsch = Einstellungen.aendern(Katalog.felder(k), new JSONObject(), k,
                new JSONObject().put("werte", new JSONObject().put("karte.quelle", "openfreemap")), false, new Merker(), 0L);
        assertTrue(falsch.fehler.has("karte.quelle"));
    }

    @Test
    public void leererStilLaedtNichts() throws Exception {
        JSONObject s = new JSONObject(Kartenquelle.leererStil(true));
        assertEquals(0, s.getJSONObject("sources").length());
        JSONObject o = new JSONObject(Kartenquelle.osmStil(false));
        assertEquals(Kartenquelle.OSM_KACHELN, o.getJSONObject("sources").getJSONObject("osm").getJSONArray("tiles").getString(0));
    }

    @Test
    public void nativeKarteOhneOpenFreeMap() throws Exception {
        String a = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/navmap/RoadSenseMapActivity.kt"));
        assertFalse(a.contains("tiles.openfreemap.org"));
        assertTrue(a.contains("Kartenquelle.aktuell()"));
        assertFalse(Files.exists(Paths.get("src/main/assets/maps/liberty_style.json")));
        assertFalse(Files.exists(Paths.get("src/main/assets/maps/dark_style.json")));
    }
}
