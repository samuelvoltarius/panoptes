package at.greenchili.surfmate.trips;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 0.1.28 P7: jüngere Fahrten zählen mehr (Halbwertszeit 21 Tage). Abnahme laut Plan: nur, was die Rückprüfung
 * messbar besser macht (kleinerer Fehler und schmalere P25–P75-Spanne).
 */
public class GewichteteToepfeTest {

    static final long TAG = 86_400_000L, T0 = 1_780_000_000_000L;

    @Test
    public void halbwertszeit() {
        assertEquals(0.5, GewichteteToepfe.faktor(T0, T0 + 21 * TAG), 1e-12);
        assertEquals(1.0, GewichteteToepfe.faktor(T0, T0 - TAG), 1e-12);
        GewichteteToepfe g = new GewichteteToepfe();
        for (int i = 0; i < 20; i++) g.dazu("city_mild_mid", 0.20, T0);
        for (int i = 0; i < 20; i++) g.dazu("city_mild_mid", 0.10, T0 + 42 * TAG);
        // alte 20 zählen ¼, neue 20 voll: (5·0,20 + 20·0,10) / 25 = 0,12
        RangeEstimator.BucketResult r = g.aufloesen(30, 15, 60, T0 + 42 * TAG);
        assertTrue(r.exakt);
        assertEquals(0.12, r.mean, 1e-9);
        assertEquals(25, r.sampleCount);
    }

    @Test
    public void alternAendertMittelNichtNurDieMenge() {
        GewichteteToepfe g = new GewichteteToepfe();
        for (int i = 0; i < 12; i++) g.dazu("highway_cold_low", 0.22, T0 + i * 1000);
        RangeEstimator.BucketResult jetzt = g.aufloesen(100, 5, 30, T0);
        assertTrue(jetzt.exakt);
        // 30 Tage später: Gewicht < 10 → nicht mehr „genau“, aber derselbe Mittelwert (Auto stand lange)
        RangeEstimator.BucketResult spaeter = g.aufloesen(100, 5, 30, T0 + 30 * TAG);
        assertNotNull(spaeter);
        assertFalse(spaeter.exakt);
        assertEquals(0.22, spaeter.mean, 1e-9);
    }

    @Test
    public void rueckfallketteMitGewichten() {
        GewichteteToepfe g = new GewichteteToepfe();
        for (int i = 0; i < 9; i++) g.dazu("city_cold_high", 0.30, T0);
        assertNull("unter 10 Fahrten: nichts", g.aufloesen(30, 5, 80, T0));
        for (int i = 0; i < 40; i++) g.dazu("city_mild_high", 0.15, T0);
        RangeEstimator.BucketResult kalt = g.aufloesen(30, 5, 80, T0);
        assertEquals("city_cold_high(blend)", kalt.bucketKey);
        // (9·0,30 + 0,3·40·0,15) / (9 + 12) = 0,2143
        assertEquals((9 * 0.30 + 0.3 * 40 * 0.15) / 21.0, kalt.mean, 1e-9);
        RangeEstimator.BucketResult autobahn = g.aufloesen(120, 15, 80, T0);
        assertEquals("overall", autobahn.bucketKey);
    }

    @Test
    public void ausFahrtenWieBeimLernen() {
        List<TripRecord> l = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            TripRecord t = new TripRecord();
            t.startTime = T0 + i * TAG;
            t.distanceKm = i == 0 ? 0.4 : 20;   // ≤ 0,5 km zählt nicht
            t.durationSeconds = 1800;
            t.avgSpeedKmh = 40;
            t.extTempC = 12;
            t.kwhStart = 30;
            t.kwhEnd = 27;
            t.anticipationScore = t.smoothnessScore = t.speedDisciplineScore = t.efficiencyScore = t.consistencyScore = 60;
            l.add(t);
        }
        GewichteteToepfe g = GewichteteToepfe.aus(l, 43);
        assertEquals(T0 + 14 * TAG, g.stand());
        assertEquals(0.15, g.aufloesen(40, 12, 60, T0 + 14 * TAG).mean, 1e-9);
    }

    /**
     * Abnahme an echten Fahrten – nur am Entwicklungsrechner, die Datei (ohne Orte) liegt bewusst NICHT im Repo.
     * Fehlt sie, wird der Test übersprungen.
     */
    @Test
    public void abnahmeAnAlfredsFahrten() throws Exception {
        File f = new File("build/abgabe/reichweite/trips.json");
        Assume.assumeTrue(f.isFile());
        JSONArray a = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))
                .getJSONArray("trips");
        List<TripRecord> l = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject j = a.getJSONObject(i);
            TripRecord t = new TripRecord();
            t.startTime = j.getLong("startTime");
            t.distanceKm = j.getDouble("distanceKm");
            t.durationSeconds = j.getInt("durationSeconds");
            t.avgSpeedKmh = j.getDouble("avgSpeedKmh");
            t.extTempC = j.getInt("extTempC");
            t.socStart = j.getDouble("socStart");
            t.socEnd = j.getDouble("socEnd");
            t.kwhStart = j.optDouble("kwhStart", 0);
            t.kwhEnd = j.optDouble("kwhEnd", 0);
            t.elecConStart = j.optDouble("elecConStart", -1);
            t.elecConEnd = j.optDouble("elecConEnd", -1);
            t.anticipationScore = j.getInt("anticipationScore");
            t.smoothnessScore = j.getInt("smoothnessScore");
            t.speedDisciplineScore = j.getInt("speedDisciplineScore");
            t.efficiencyScore = j.getInt("efficiencyScore");
            t.consistencyScore = j.getInt("consistencyScore");
            l.add(t);
        }
        ReichweitenRueckpruefung.Ergebnis alt = ReichweitenRueckpruefung.rechne(l, 43.2, false);
        ReichweitenRueckpruefung.Ergebnis neu = ReichweitenRueckpruefung.rechne(l, 43.2, true);
        String msg = String.format(java.util.Locale.ROOT, "alt %.4f %.3f–%.3f n=%d | neu %.4f %.3f–%.3f n=%d",
                alt.fehler, alt.p25, alt.p75, alt.fenster, neu.fehler, neu.p25, neu.p75, neu.fenster);
        System.out.println("P7-Abnahme: " + msg);
        assertEquals(msg, alt.fenster, neu.fenster);
        assertTrue("Fehler kleiner: " + msg, neu.fehler < alt.fehler);
        assertTrue("Spanne schmaler: " + msg, neu.p75 - neu.p25 < alt.p75 - alt.p25);
    }
}
