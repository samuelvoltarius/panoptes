package at.greenchili.surfmate.ladeplan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.charging.Zeittarif;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/** 0.1.20 Ladeplan-Planer (LADEPLAN_KONZEPT §2/§4): reine Logik. */
public class PlanerTest {

    static final ZoneId WIEN = ZoneId.of("Europe/Vienna");

    static long ms(int t, int h, int min) {
        return ZonedDateTime.of(2026, 10, t, h, min, 0, 0, WIEN).toInstant().toEpochMilli();
    }

    /** Mi 28.10. 18:00, SoC 40 %, 40 kWh, 11 kW, Abfahrt Do 07:00 80 %. */
    static Planer.Eingabe basis() {
        Planer.Eingabe e = new Planer.Eingabe();
        e.jetzt = ms(28, 18, 0);
        e.zone = WIEN;
        e.socProzent = 40;
        e.kapazitaetKwh = 40;
        e.wirkungsgrad = 1.0;
        e.leistungKw = 11;
        e.modus = "guenstig";
        e.mindestladung = 30;
        e.abfahrt = ms(29, 7, 0);
        e.abfahrtZiel = 80;
        return e;
    }

    static Zeittarif tagNacht() throws Exception {
        return Zeittarif.aus(Zeittarif.vorlage(Zeittarif.VORLAGE_TAG_NACHT, 0.30, "AT"));
    }

    @Test
    public void guenstigBisAbfahrtNimmtNachtband() throws Exception {
        Planer.Eingabe e = basis();
        Zeittarif z = tagNacht();
        e.preis = t -> z.preis(t, WIEN);
        e.festerPreis = false;
        Planer.Plan p = Planer.plane(e);
        assertEquals("guenstig", p.grund);
        assertEquals(16.0, p.bedarfKwh, 1e-9);
        assertEquals(16.0, p.kwh, 1e-9);
        assertFalse(p.ladeJetzt);
        assertEquals(1, p.bloecke.size());
        // gleich teuer → früher zuerst: ab 22:00, 16 kWh / 11 kW ≈ 1:27 → 6 Viertelstunden (bis 23:30)
        assertEquals(ms(28, 22, 0), p.bloecke.get(0)[0]);
        assertEquals(ms(28, 23, 30), p.bloecke.get(0)[1]);
        assertEquals(16 * 0.20, p.kosten, 1e-9);
        assertEquals(16 * 0.30, p.kostenSofort, 1e-9);
        assertTrue(p.fertig > ms(28, 23, 15) && p.fertig <= ms(28, 23, 30));
        JSONObject j = p.json();
        assertEquals(1.6, j.getDouble("ersparnis"), 1e-9);
        assertEquals(2, j.getInt("schaltungen"));
        assertTrue(j.getJSONArray("stunden").length() >= 13);
    }

    @Test
    public void festerPreisSoSpaetWieMoeglich() {
        Planer.Eingabe e = basis();
        e.preis = t -> 0.30;
        e.festerPreis = true;
        Planer.Plan p = Planer.plane(e);
        assertEquals(1, p.bloecke.size());
        assertEquals(ms(29, 7, 0), p.bloecke.get(0)[1]);
        assertEquals(ms(29, 5, 30), p.bloecke.get(0)[0]);
        assertEquals(0, p.kostenSofort - p.kosten, 1e-9);
    }

    @Test
    public void zuWenigZeitLaedtSofortMitMeldung() {
        Planer.Eingabe e = basis();
        e.jetzt = ms(29, 6, 0);
        e.preis = t -> 0.30;
        Planer.Plan p = Planer.plane(e);
        assertEquals("ziel_nicht_erreichbar", p.meldung);
        assertTrue(p.ladeJetzt);
        assertEquals(11.0, p.kwh, 1e-9);
    }

    @Test
    public void mindestladungSofortDannGuenstig() throws Exception {
        Planer.Eingabe e = basis();
        e.socProzent = 20;
        Zeittarif z = tagNacht();
        e.preis = t -> z.preis(t, WIEN);
        e.festerPreis = false;
        Planer.Plan p = Planer.plane(e);
        assertTrue(p.ladeJetzt);
        assertEquals("guenstig", p.grund);
        assertEquals(2, p.bloecke.size());
        assertEquals(ms(28, 18, 0), p.bloecke.get(0)[0]);
        // 10 % = 4 kWh → 2 Viertelstunden (5,5 kWh) sofort
        assertEquals(ms(28, 18, 30), p.bloecke.get(0)[1]);
        assertEquals(ms(28, 22, 0), p.bloecke.get(1)[0]);
        // ohne Abfahrt nur Mindestladung
        e.abfahrt = 0;
        p = Planer.plane(e);
        assertEquals("mindestladung", p.grund);
        assertEquals(4.0, p.kwh, 1e-9);
    }

    @Test
    public void sofortHatVorrangUndZielErreicht() {
        Planer.Eingabe e = basis();
        e.modus = "aus";
        e.sofort = true;
        e.sofortZiel = 100;
        Planer.Plan p = Planer.plane(e);
        assertEquals("sofort", p.grund);
        assertTrue(p.ladeJetzt);
        assertEquals(24.0, p.kwh, 1e-9);
        e.socProzent = 100;
        p = Planer.plane(e);
        assertEquals("ziel_erreicht", p.grund);
        assertFalse(p.ladeJetzt);
        e.sofort = false;
        assertEquals("aus", Planer.plane(e).grund);
    }

    @Test
    public void zeitfensterLaedtNurImFenster() throws Exception {
        Planer.Eingabe e = basis();
        e.jetzt = ms(28, 9, 0);
        e.modus = "zeitfenster";
        e.ziel = 80;
        e.fenster = Planer.fenster(new JSONArray().put(new JSONObject().put("tage", "alle").put("von", "11:00").put("bis", "12:00")),
                WIEN, "AT");
        e.preis = t -> 0.30;
        Planer.Plan p = Planer.plane(e);
        assertEquals("zeitfenster", p.grund);
        assertFalse(p.ladeJetzt);
        assertEquals(ms(28, 11, 0), p.bloecke.get(0)[0]);
        assertEquals(ms(28, 12, 0), p.bloecke.get(0)[1]);
        // Horizont 24 h: im Fenster nur 11 kWh möglich, mehr wird nicht geplant
        assertEquals(1, p.bloecke.size());
        assertEquals(11.0, p.kwh, 1e-9);
    }

    @Test
    public void hoechstensZwoelfSchaltungen() {
        Planer.Eingabe e = basis();
        e.socProzent = 0;
        e.mindestladung = 0;
        e.abfahrtZiel = 100;
        e.leistungKw = 3.7;
        // abwechselnd billig/teuer je Viertelstunde → ohne Glätten dutzende Blöcke
        e.preis = t -> (t / Planer.SLOT_MS) % 2 == 0 ? 0.10 : 0.40;
        e.festerPreis = false;
        Planer.Plan p = Planer.plane(e);
        assertTrue("Schaltungen " + p.schaltungen(), p.schaltungen() <= Planer.MAX_SCHALTUNGEN);
        assertTrue(p.kwh >= p.bedarfKwh - 1e-9);
    }

    @Test
    public void glaettenSchliesstEinzelneLuecke() {
        boolean[] w = {true, true, false, true, true, false, false, true};
        double[] kwh = new double[8];
        double[] preis = new double[8];
        java.util.Arrays.fill(kwh, 1);
        java.util.Arrays.fill(preis, 0.2);
        Planer.glaette(w, kwh, preis, 5, false, 6, 0);
        // Lücke geschlossen, danach der überzählige letzte Slot abgegeben
        assertTrue(w[2]);
        assertFalse(w[7]);
        assertEquals(1, Planer.bloecke(w));
    }

    @Test
    public void naechsteAbfahrtWerktagWochenendeFeiertag() throws Exception {
        JSONObject ab = new JSONObject()
                .put("werktag", new JSONObject().put("an", true).put("zeit", "07:00").put("ziel", 80))
                .put("wochenende", new JSONObject().put("an", false).put("zeit", "09:00").put("ziel", 60));
        // Fr 30.10. 20:00 → Mo 2.11. 07:00 (Sa/So aus)
        long[] a = Planer.naechsteAbfahrt(ab, ms(30, 20, 0), WIEN, "AT");
        assertEquals(ZonedDateTime.of(2026, 11, 2, 7, 0, 0, 0, WIEN).toInstant().toEpochMilli(), a[0]);
        assertEquals(80, a[1]);
        // So 25.10. 20:00 → Mo 26.10. ist Feiertag (AT) → Di 27.10.
        a = Planer.naechsteAbfahrt(ab, ms(25, 20, 0), WIEN, "AT");
        assertEquals(ms(27, 7, 0), a[0]);
        assertNull(Planer.naechsteAbfahrt(new JSONObject(), ms(25, 20, 0), WIEN, "AT"));
    }

    @Test
    public void datenFehlen() throws Exception {
        Planer.Eingabe e = basis();
        e.socProzent = Double.NaN;
        assertEquals("daten_fehlen", Planer.plane(e).grund);
        assertNotNull(Planer.plane(basis()).json());
        assertEquals(7.59, Planer.leistungKw(11, 3, 0), 1e-9);
        assertEquals(7.36, Planer.leistungKw(32, 1, 7.4), 1e-9);
        assertEquals(7.4, Planer.leistungKw(32, 3, 7.4), 1e-9);
    }
}
