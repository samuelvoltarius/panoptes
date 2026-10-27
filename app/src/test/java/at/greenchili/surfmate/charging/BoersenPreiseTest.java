package at.greenchili.surfmate.charging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** 0.1.21 Börse (aWATTar AT/DE) nur hinter dem Schalter „dynamischer Tarif“ (LADEPLAN_KONZEPT §3). */
public class BoersenPreiseTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    static final ZoneId WIEN = ZoneId.of("Europe/Vienna");
    final AtomicLong jetzt = new AtomicLong(ms(28, 9, 0));
    final AtomicBoolean schalter = new AtomicBoolean(false);
    final List<String> abrufe = new ArrayList<>();

    static long ms(int t, int h, int m) {
        return ZonedDateTime.of(2026, 10, t, h, m, 0, 0, WIEN).toInstant().toEpochMilli();
    }

    /** aWATTar-Attrappe: Stundenpreise; Nacht 50 €/MWh, Tag 200 €/MWh, ab Tag 29 nur bis „veroeffentlichtBis“. */
    long veroeffentlichtBis = ms(29, 0, 0);
    BoersenPreise.Http awattar = url -> {
        abrufe.add(url);
        long start = Long.parseLong(url.replaceAll(".*start=(\\d+).*", "$1"));
        long ende = Math.min(Long.parseLong(url.replaceAll(".*end=(\\d+).*", "$1")), veroeffentlichtBis);
        JSONArray d = new JSONArray();
        for (long s = start; s < ende; s += 3_600_000L) {
            int h = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(s), WIEN).getHour();
            d.put(new JSONObject().put("start_timestamp", s).put("end_timestamp", s + 3_600_000L)
                    .put("marketprice", h < 6 ? 50.0 : h == 13 ? -20.0 : 200.0).put("unit", "Eur/MWh"));
        }
        return new Object[]{200, new JSONObject().put("object", "list").put("data", d).toString()};
    };

    BoersenPreise b() {
        return new BoersenPreise(tmp.getRoot(), awattar, jetzt::get, WIEN, schalter::get);
    }

    @After
    public void ab() {
        BoersenPreise.setzeAktiv(null);
    }

    @Test
    public void ohneSchalterKeinAbrufUndKeinPreis() throws Exception {
        BoersenPreise b = b();
        assertFalse(b.aktualisiere("AT"));
        assertTrue(abrufe.isEmpty());
        // auch ein alter Cache wird ohne Schalter nicht benutzt
        schalter.set(true);
        assertTrue(b.aktualisiere("AT"));
        schalter.set(false);
        assertTrue(Double.isNaN(b.euroProKwh("AT", ms(28, 3, 0))));
        assertFalse(b.status("AT").getBoolean("an"));
        assertFalse(b.status("AT").has("bis"));
    }

    @Test
    public void holtHeuteDannAb14UhrMorgenHoechstensStuendlich() throws Exception {
        schalter.set(true);
        BoersenPreise b = b();
        assertTrue(b.aktualisiere("AT"));
        assertTrue(abrufe.get(0).startsWith("https://api.awattar.at/v1/marketdata?start=" + ms(28, 0, 0) + "&end=" + ms(30, 0, 0)));
        assertEquals(0.05, b.euroProKwh("AT", ms(28, 3, 30)), 1e-9);
        assertEquals(0.20, b.euroProKwh("AT", ms(28, 10, 0)), 1e-9);
        assertEquals(-0.02, b.euroProKwh("AT", ms(28, 13, 15)), 1e-9);   // negative Preise bleiben
        assertTrue(Double.isNaN(b.euroProKwh("AT", ms(29, 3, 0))));        // morgen noch nicht veröffentlicht
        assertFalse("heute vollständig, vor 14 Uhr", b.aktualisiere("AT"));
        jetzt.set(ms(28, 14, 5));
        assertTrue("ab 14 Uhr: morgen holen (noch nicht veröffentlicht)", b.aktualisiere("AT"));
        veroeffentlichtBis = ms(30, 0, 0);
        jetzt.set(ms(28, 14, 50));
        assertFalse("höchstens einmal je Stunde", b.aktualisiere("AT"));
        jetzt.set(ms(28, 15, 10));
        assertTrue(b.aktualisiere("AT"));
        assertEquals(0.05, b.euroProKwh("AT", ms(29, 3, 0)), 1e-9);
        assertEquals(ms(30, 0, 0), b.bis("AT"));
        assertEquals(3, abrufe.size());
        // Cache überlebt einen Neustart
        BoersenPreise neu = b();
        assertEquals(0.05, neu.euroProKwh("AT", ms(29, 3, 0)), 1e-9);
        assertFalse(neu.aktualisiere("AT"));
        // DE eigener Host, unbekanntes Land nie
        assertTrue(b.aktualisiere("DE"));
        assertTrue(abrufe.get(3).startsWith("https://api.awattar.de/"));
        assertFalse(b.aktualisiere("CH"));
    }

    @Test
    public void fehlerWirdGemeldetUndNichtSofortWiederholt() throws Exception {
        schalter.set(true);
        BoersenPreise b = new BoersenPreise(tmp.getRoot(), url -> {
            abrufe.add(url);
            return new Object[]{503, ""};
        }, jetzt::get, WIEN, schalter::get);
        assertFalse(b.aktualisiere("AT"));
        assertEquals("http_503", b.status("AT").getString("fehler"));
        assertFalse(b.aktualisiere("AT"));
        assertEquals(1, abrufe.size());
    }

    @Test
    public void boersentarifBoerseAufschlagBandErsatzpreis() throws Exception {
        schalter.set(true);
        BoersenPreise b = b();
        b.aktualisiere("AT");
        BoersenPreise.setzeAktiv(b);
        Zeittarif z = Zeittarif.aus(new JSONObject().put("art", "boerse").put("land", "AT").put("aufschlag", 0.12)
                .put("grundpreis", 0.30).put("baender", new JSONArray().put(new JSONObject().put("tage", "alle")
                        .put("von", "06:00").put("bis", "22:00").put("preis", 0.05))));
        assertTrue(z.istBoerse());
        assertEquals(0.05 + 0.12, z.preis(ms(28, 3, 0), WIEN), 1e-9);
        assertEquals(0.20 + 0.12 + 0.05, z.preis(ms(28, 10, 0), WIEN), 1e-9);
        assertTrue(z.boerseBekannt(ms(28, 10, 0)));
        assertEquals("Ersatzpreis, solange morgen unbekannt", 0.30, z.preis(ms(29, 3, 0), WIEN), 1e-9);
        assertFalse(z.boerseBekannt(ms(29, 3, 0)));
        schalter.set(false);
        assertEquals("Schalter aus → nur Ersatzpreis", 0.30, z.preis(ms(28, 3, 0), WIEN), 1e-9);
        // Börsentarif braucht Land und gültigen Aufschlag
        for (JSONObject j : new JSONObject[]{
                new JSONObject().put("art", "boerse").put("land", "").put("aufschlag", 0.1).put("grundpreis", 0.3),
                new JSONObject().put("art", "boerse").put("land", "AT").put("grundpreis", 0.3),
                new JSONObject().put("art", "boerse").put("land", "AT").put("aufschlag", 5).put("grundpreis", 0.3)}) {
            assertTrue(j.toString(), Zeittarif.oderNull(j) == null);
        }
    }
}
