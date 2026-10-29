package at.greenchili.surfmate.charging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** 0.1.20 Ladeplan: Zeitbänder am Heimort-Tarif (LADEPLAN_KONZEPT §3). */
public class ZeittarifTest {

    static final ZoneId WIEN = ZoneId.of("Europe/Vienna");

    static long ms(int j, int m, int t, int h, int min) {
        return ZonedDateTime.of(j, m, t, h, min, 0, 0, WIEN).toInstant().toEpochMilli();
    }

    static JSONObject tarif(String land, Object... baender) throws Exception {
        JSONArray b = new JSONArray();
        for (Object o : baender) b.put(o);
        return new JSONObject().put("art", "zeittarif").put("grundpreis", 0.30).put("land", land).put("baender", b);
    }

    static JSONObject band(String tage, String von, String bis, double preis) throws Exception {
        return new JSONObject().put("tage", tage).put("von", von).put("bis", bis).put("preis", preis);
    }

    @Test
    public void mittagsstromNurWerktags() throws Exception {
        Zeittarif z = Zeittarif.aus(tarif("AT", band("werktag", "11:00", "15:00", 0.12),
                band("wochenende", "00:00", "24:00", 0.20)));
        // Mi 28.10.2026
        assertEquals(0.30, z.preis(ms(2026, 10, 28, 10, 59), WIEN), 1e-9);
        assertEquals(0.12, z.preis(ms(2026, 10, 28, 11, 0), WIEN), 1e-9);
        assertEquals(0.12, z.preis(ms(2026, 10, 28, 14, 59), WIEN), 1e-9);
        assertEquals(0.30, z.preis(ms(2026, 10, 28, 15, 0), WIEN), 1e-9);
        // Sa 31.10.2026: Wochenendband den ganzen Tag
        assertEquals(0.20, z.preis(ms(2026, 10, 31, 12, 0), WIEN), 1e-9);
        assertEquals(0.20, z.preis(ms(2026, 10, 31, 23, 59), WIEN), 1e-9);
    }

    @Test
    public void feiertagIstWochenende() throws Exception {
        Zeittarif at = Zeittarif.aus(tarif("AT", band("werktag", "11:00", "15:00", 0.12)));
        // Mo 26.10.2026 = Nationalfeiertag AT → kein Werktagsband
        assertEquals(0.30, at.preis(ms(2026, 10, 26, 12, 0), WIEN), 1e-9);
        Zeittarif de = Zeittarif.aus(tarif("DE", band("werktag", "11:00", "15:00", 0.12)));
        assertEquals(0.12, de.preis(ms(2026, 10, 26, 12, 0), WIEN), 1e-9);
        assertTrue(Zeittarif.feiertag(LocalDate.of(2026, 10, 3), "DE"));
        assertFalse(Zeittarif.feiertag(LocalDate.of(2026, 10, 3), "AT"));
        // Ostern 2026 = 5.4. → Ostermontag 6.4., Karfreitag (DE) 3.4., Fronleichnam (AT) 4.6.
        assertEquals(LocalDate.of(2026, 4, 5), Zeittarif.ostersonntag(2026));
        assertEquals(LocalDate.of(2027, 3, 28), Zeittarif.ostersonntag(2027));
        assertTrue(Zeittarif.feiertag(LocalDate.of(2026, 4, 6), "AT"));
        assertTrue(Zeittarif.feiertag(LocalDate.of(2026, 4, 3), "DE"));
        assertTrue(Zeittarif.feiertag(LocalDate.of(2026, 6, 4), "AT"));
        assertFalse(Zeittarif.feiertag(LocalDate.of(2026, 6, 4), "DE"));
        // ohne Land keine Feiertage
        assertFalse(Zeittarif.feiertag(LocalDate.of(2026, 12, 25), ""));
    }

    @Test
    public void nachtbandUeberMitternacht() throws Exception {
        Zeittarif z = Zeittarif.aus(Zeittarif.vorlage(Zeittarif.VORLAGE_TAG_NACHT, 0.30, "AT"));
        assertEquals(0.20, z.preis(ms(2026, 10, 28, 22, 0), WIEN), 1e-9);
        assertEquals(0.20, z.preis(ms(2026, 10, 29, 3, 0), WIEN), 1e-9);
        assertEquals(0.20, z.preis(ms(2026, 10, 29, 5, 59), WIEN), 1e-9);
        assertEquals(0.30, z.preis(ms(2026, 10, 29, 6, 0), WIEN), 1e-9);
        assertEquals(0.30, z.preis(ms(2026, 10, 29, 21, 59), WIEN), 1e-9);
    }

    @Test
    public void sommerzeitWechsel() throws Exception {
        // 25.10.2026 03:00 → 02:00 (Wien). Band 02:00–03:00 gilt nach der Wanduhr (beide Stunden)
        Zeittarif z = Zeittarif.aus(tarif("", band("alle", "02:00", "03:00", 0.05)));
        long vorher = ZonedDateTime.of(2026, 10, 25, 2, 30, 0, 0, WIEN).withEarlierOffsetAtOverlap().toInstant().toEpochMilli();
        long nachher = ZonedDateTime.of(2026, 10, 25, 2, 30, 0, 0, WIEN).withLaterOffsetAtOverlap().toInstant().toEpochMilli();
        assertEquals(3_600_000L, nachher - vorher);
        assertEquals(0.05, z.preis(vorher, WIEN), 1e-9);
        assertEquals(0.05, z.preis(nachher, WIEN), 1e-9);
    }

    @Test
    public void unsinnWirdAbgelehnt() throws Exception {
        for (JSONObject j : new JSONObject[]{
                new JSONObject().put("art", "boerse").put("grundpreis", 0.3),
                tarif("AT").put("grundpreis", -1),
                tarif("XX"),
                tarif("AT", band("montag", "11:00", "15:00", 0.1)),
                tarif("AT", band("alle", "25:00", "15:00", 0.1)),
                tarif("AT", band("alle", "11:00", "15:00", 99)),
                tarif("AT", band("alle", "24:00", "15:00", 0.1))}) {
            try {
                Zeittarif.aus(j);
                fail("angenommen: " + j);
            } catch (IllegalArgumentException erwartet) {
                assertNotNull(erwartet.getMessage());
            }
        }
        assertNull(Zeittarif.oderNull(new JSONObject()));
    }

    @Test
    public void tarifProfilHaeltZeittarifUndVerwirftUnsinn() throws Exception {
        JSONObject j = new TariffProfile("Zuhause", 48.3, 14.3, 50, 0.30, 0, "€").toJson();
        j.put("zeittarif", Zeittarif.vorlage(Zeittarif.VORLAGE_MITTAG, 0.30, "AT"));
        TariffProfile p = new TariffProfile(j);
        assertNotNull(p.getZeittarif());
        assertEquals(0.20, p.getZeittarif().preis(ms(2026, 10, 28, 12, 0), WIEN), 1e-9);
        TariffProfile rund = new TariffProfile(p.toJson());
        assertNotNull(rund.getZeittarif());
        rund.setZeittarif(new JSONObject().put("art", "zeittarif").put("grundpreis", "x"));
        assertNull(rund.getZeittarif());
        assertFalse(rund.toJson().has("zeittarif"));
        // nur Zeittarif ohne AC-Preis ist ein gültiger Tarif
        TariffProfile nur = new TariffProfile("Z", 48.3, 14.3, 50, 0, 0, "");
        assertFalse(nur.hasAnyRate());
        nur.setZeittarif(Zeittarif.vorlage(Zeittarif.VORLAGE_TAG_NACHT, 0.3, "AT"));
        assertTrue(nur.hasAnyRate());
    }

    @Test
    public void mittelpreisEnergiegewichtet() throws Exception {
        Zeittarif z = Zeittarif.aus(tarif("", band("alle", "11:00", "12:00", 0.10)));
        // 10:30–12:00 mit 11 kW, Proben alle 5 min → 1/3 der Energie zu 0,30, 2/3 zu 0,10
        int n = 19;
        long[] t = new long[n];
        double[] kw = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = ms(2026, 10, 28, 10, 30) + i * 5 * 60_000L;
            kw[i] = 11;
        }
        double p = z.mittelpreis(t, kw, t[0], t[n - 1], WIEN);
        assertEquals((0.30 + 2 * 0.10) / 3, p, 1e-3);
        // Pause (Probe 0) dazwischen zählt nicht
        kw[6] = 0;
        assertTrue(z.mittelpreis(t, kw, t[0], t[n - 1], WIEN) < 0.17);
        // ohne Proben: Zeitmittel
        assertEquals(0.10, z.mittelpreis(new long[0], new double[0], ms(2026, 10, 28, 11, 0), ms(2026, 10, 28, 12, 0), WIEN), 1e-9);
    }
}
