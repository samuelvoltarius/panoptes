package at.greenchili.surfmate.ladeplan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.charging.TariffProfile;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZoneId;

/** 0.1.28 (P6): Einstellung {@code vorklima} im Ladeplan – Standard aus, Teil-Änderung, Einschalten nur mit eigenem Login. */
public class LadeplanVorklimaTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    LadeplanDienst d;
    File einst;

    @Before
    public void setUp() throws Exception {
        einst = new File(tmp.getRoot(), "ladeplan.json");
        LadeplanDienst.Umgebung u = new LadeplanDienst.Umgebung() {
            @Override public long jetzt() { return 1_800_000_000_000L; }
            @Override public ZoneId zone() { return ZoneId.of("Europe/Vienna"); }
            @Override public JSONObject telemetrie() { return null; }
            @Override public JSONArray orte() { return null; }
            @Override public TariffProfile tarif(String id) { return null; }
            @Override public double festerPreis() { return 0.3; }
            @Override public double kapazitaetKwh() { return 43; }
        };
        d = new LadeplanDienst(einst, new File(tmp.getRoot(), "z.json"), u, null);
    }

    @Test
    public void standardAusUndAlteDateiOhneVorklima() throws Exception {
        assertFalse(d.einstellungen().getJSONObject("vorklima").getBoolean("an"));
        // Ladeplan aus 0.1.27 ohne „vorklima“: Standard ergänzt
        Files.write(einst.toPath(), "{\"v\":1,\"modus\":\"guenstig\"}".getBytes(StandardCharsets.UTF_8));
        JSONObject s = d.einstellungen();
        assertEquals("guenstig", s.getString("modus"));
        assertFalse(s.getJSONObject("vorklima").getBoolean("an"));
        assertEquals(15, s.getJSONObject("vorklima").getInt("vorlaufMin"));
    }

    @Test
    public void teilAenderungWirdZusammengefuehrt() throws Exception {
        assertNull(d.aendere(new JSONObject().put("vorklima", new JSONObject().put("an", true)), false));
        assertNull(d.aendere(new JSONObject().put("vorklima", new JSONObject().put("vorlaufMin", 20)), false));
        JSONObject v = d.einstellungen().getJSONObject("vorklima");
        assertTrue(v.getBoolean("an"));
        assertEquals(20, v.getInt("vorlaufMin"));
        assertEquals("vorklima_ungueltig",
                d.aendere(new JSONObject().put("vorklima", new JSONObject().put("vorlaufMin", 45)), false));
    }

    @Test
    public void gekoppeltesGeraetDarfNichtEinschaltenAberAus() throws Exception {
        assertEquals("nur_am_auto", d.aendere(new JSONObject().put("vorklima", new JSONObject().put("an", true)), true));
        assertFalse(d.einstellungen().getJSONObject("vorklima").getBoolean("an"));
        assertNull(d.aendere(new JSONObject().put("vorklima", new JSONObject().put("an", true)), false));
        assertNull("Vorlauf ändern und Ausschalten geht", d.aendere(new JSONObject()
                .put("vorklima", new JSONObject().put("vorlaufMin", 10)), true));
        assertNull(d.aendere(new JSONObject().put("vorklima", new JSONObject().put("an", false)), true));
        assertFalse(d.einstellungen().getJSONObject("vorklima").getBoolean("an"));
    }
}
