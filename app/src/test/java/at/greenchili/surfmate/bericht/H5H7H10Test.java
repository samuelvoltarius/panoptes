package at.greenchili.surfmate.bericht;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.fahrtenbuch.FahrtenbuchSeite;
import at.greenchili.surfmate.ha.Anwesenheit;
import at.greenchili.surfmate.neuigkeiten.Neuigkeiten;
import at.greenchili.surfmate.server.ExternGuard;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

/** H5 Ankunft/Abfahrt (ohne Koordinaten), H7 Monatsbericht (kein Raten), H10 Neuigkeiten (einmal pro Auto). */
public class H5H7H10Test {

    static final TimeZone WIEN = TimeZone.getTimeZone("Europe/Vienna");

    // ------------------------------------------------------------- H5

    @Test
    public void ankunftNurBeimAbstellenAbfahrtNachAnkunft() {
        Anwesenheit a = new Anwesenheit();
        assertNull(a.takt(null, false));
        assertNull(a.takt("Zuhause", false));                 // durchfahren / noch an
        assertArrayEquals(new String[]{"ankunft", "Zuhause"}, a.takt("Zuhause", true));
        assertNull(a.takt("Zuhause", true));                  // einmal je Aufenthalt
        assertNull(a.takt(null, true));                       // GPS weg im Stand → keine Abfahrt
        assertNull(a.takt("Zuhause", false));                 // Zündung an, noch in der Zone
        assertArrayEquals(new String[]{"abfahrt", "Zuhause"}, a.takt(null, false));
        assertNull(a.takt(null, false));
        assertNull(a.takt("Arbeit", false));
        assertArrayEquals(new String[]{"ankunft", "Arbeit"}, a.takt("Arbeit", true));
        assertArrayEquals(new String[]{"abfahrt", "Arbeit"}, a.takt("Zuhause", false));   // Zonenwechsel
        // Neustart mit gemerkter Zone: keine doppelte Ankunft
        Anwesenheit b = new Anwesenheit("Zuhause");
        assertNull(b.takt("Zuhause", true));
        assertNull(new Anwesenheit("").gemeldet());
    }

    // ------------------------------------------------------------- H7

    static FahrtenbuchSeite.Fahrt f(double km, double kwh, long dauerMin) {
        FahrtenbuchSeite.Fahrt x = new FahrtenbuchSeite.Fahrt();
        x.start = 1_000_000;
        x.ende = x.start + dauerMin * 60_000;
        x.km = km;
        x.kwh = kwh;
        return x;
    }

    @Test
    public void monatsgrenzen() {
        long[] m = Monatsbericht.monat("2026-10", WIEN);
        assertEquals(31 * 86_400_000L + 3_600_000L, m[1] - m[0]);   // Oktober mit Zeitumstellung (25 h am 25.10.)
        assertNull(Monatsbericht.monat("2026-13", WIEN));
        assertNull(Monatsbericht.monat("10-2026", WIEN));
        assertEquals("2025-12", Monatsbericht.vormonat("2026-01"));
        assertEquals("2026-09", Monatsbericht.vormonat("2026-10"));
    }

    @Test
    public void fahrenOhneRaten() throws Exception {
        List<FahrtenbuchSeite.Fahrt> l = new ArrayList<>();
        l.add(f(100, 15, 60));
        l.add(f(50, Double.NaN, 30));
        JSONObject o = Monatsbericht.fahren(l);
        assertEquals(150.0, o.getDouble("km"), 0.01);
        assertEquals(2, o.getInt("fahrten"));
        assertEquals(15.0, o.getDouble("kwh"), 0.01);
        assertEquals(15.0, o.getDouble("kwhPro100km"), 0.01);   // nur Fahrten mit bekanntem Verbrauch
        assertEquals(1.5, o.getDouble("stunden"), 0.01);
        JSONObject leer = Monatsbericht.fahren(Collections.emptyList());
        assertTrue(leer.isNull("kwh"));
        assertTrue(leer.isNull("kwhPro100km"));
    }

    @Test
    public void ladenZuhauseUnterwegsKostenNurMitTarif() throws Exception {
        List<Monatsbericht.Zone> z = Collections.singletonList(new Monatsbericht.Zone(47.80, 13.04, 100));
        JSONArray s = new JSONArray()
                .put(new JSONObject().put("energyAdded", 20.0).put("lat", 47.8001).put("lng", 13.0401)
                        .put("tariffId", "t1").put("cost", 5.0).put("currency", "EUR"))
                .put(new JSONObject().put("energyAdded", 30.0).put("lat", 48.2).put("lng", 16.37))
                .put(new JSONObject().put("energyAdded", 5.0).put("inProgress", true))
                .put(new JSONObject().put("energyAdded", 0.0));
        JSONObject l = Monatsbericht.laden(s, z);
        assertEquals(2, l.getInt("anzahl"));
        assertEquals(50.0, l.getDouble("kwh"), 0.01);
        assertEquals(20.0, l.getJSONObject("zuhause").getDouble("kwh"), 0.01);
        assertEquals(5.0, l.getJSONObject("zuhause").getDouble("kosten"), 0.01);
        assertTrue(l.getJSONObject("unterwegs").isNull("kosten"));   // kein Tarif → keine Kosten
        assertEquals("EUR", l.getString("waehrung"));
        JSONObject b = Monatsbericht.bericht("2026-10", new JSONArray().put("2026-10"), true,
                Monatsbericht.fahren(Collections.emptyList()), l, null);
        assertTrue(b.isNull("vormonat"));
        assertTrue(b.getJSONObject("standverlust").isNull("prozent"));
        assertTrue(b.getJSONObject("akku").isNull("sohProzent"));
        assertFalse(b.toString().contains("47.8"));   // keine Koordinaten im Bericht
    }

    // ------------------------------------------------------------- H10

    @Test
    public void firmwareErsterStartNurMerken() throws Exception {
        JSONObject n = Neuigkeiten.firmwareAbgleich(new JSONObject(), "2026.08.01", 5);
        assertEquals("2026.08.01", n.getString("firmwareJetzt"));
        assertFalse(n.has("firmwareVorher"));
        JSONObject stand = new JSONObject().put("firmwareJetzt", "2026.08.01").put("firmwareGesehen", true);
        assertNull(Neuigkeiten.firmwareAbgleich(stand, "2026.08.01", 6));
        assertNull(Neuigkeiten.firmwareAbgleich(stand, null, 6));
        JSONObject w = Neuigkeiten.firmwareAbgleich(stand, "2026.10.10", 7);
        assertEquals("2026.08.01", w.getString("firmwareVorher"));
        assertFalse(w.getBoolean("firmwareGesehen"));
        assertEquals(7, w.getLong("firmwareGeaendertAm"));
    }

    @Test
    public void antwortUndGesehen() throws Exception {
        JSONObject a = Neuigkeiten.antwort(new JSONObject(), 9, "0.1.8");
        assertEquals(9, a.getInt("versionCode"));
        assertTrue(a.isNull("gesehenVersionCode"));
        assertTrue(a.getJSONObject("firmware").isNull("vorher"));
        assertEquals(9, Neuigkeiten.gesehen(new JSONObject(), 9).getInt("gesehenVersionCode"));
        assertEquals(9, Neuigkeiten.gesehen(new JSONObject().put("versionCode", 99), 9).getInt("gesehenVersionCode"));
        JSONObject fw = Neuigkeiten.gesehen(new JSONObject().put("firmware", true), 9);
        assertTrue(fw.getBoolean("firmwareGesehen"));
        assertFalse(fw.has("gesehenVersionCode"));
    }

    @Test
    public void kopplungDarfNurLesen() {
        assertTrue(ExternGuard.erlaubt("GET", "/api/neuigkeiten"));
        assertFalse(ExternGuard.erlaubt("POST", "/api/neuigkeiten/gesehen"));
        assertTrue(ExternGuard.erlaubt("GET", "/api/bericht/monat"));
        assertFalse(ExternGuard.erlaubt("POST", "/api/bericht/monat"));
    }
}
