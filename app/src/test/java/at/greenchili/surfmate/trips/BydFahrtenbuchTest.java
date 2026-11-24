package at.greenchili.surfmate.trips;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 0.1.43.20 „Verbrauch laut BYD“. Die Datenbanken unter test/resources/byd sind SYNTHETISCH
 * ({@code tools/byd_beispiel_db.py}): BYDs Schema, erfundene Fahrten, keine Orte.
 */
public class BydFahrtenbuchTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Before
    public void leer() { BydFahrtenbuch.vergessen(); }

    private static byte[] res(String name) throws Exception {
        try (InputStream in = BydFahrtenbuchTest.class.getResourceAsStream("/byd/" + name)) {
            assertNotNull("Testdatei fehlt: " + name, in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toByteArray();
        }
    }

    @Test
    public void liestAlleFahrtenUeberMehrereSeiten() throws Exception {
        List<BydFahrtenbuch.Fahrt> f = BydFahrtenbuch.lese(res("ec_beispiel.db"));
        assertNotNull(f);
        assertEquals("700 Zeilen, 8 gelöscht", 692, f.size());
        double km = 0, kwh = 0;
        for (BydFahrtenbuch.Fahrt x : f) { km += x.km; kwh += x.kwh; }
        assertEquals(12854.0, km, 0.01);
        assertEquals(1764.0, kwh, 0.01);
        // älteste zuerst, Zeiten in ms, _id aus der rowid (INTEGER PRIMARY KEY steht im Satz als NULL)
        assertEquals(1, f.get(0).id);
        assertEquals((1772409600L + 8 * 3600) * 1000L, f.get(0).startMs);
        BydFahrtenbuch.Fahrt letzte = f.get(f.size() - 1);
        assertEquals(700, letzte.id);
        assertEquals(1802624400_000L, letzte.startMs);
        assertEquals(2400, letzte.dauerSek);
        assertEquals(26.0, letzte.km, 1e-9);
        assertEquals(4.2, letzte.kwh, 1e-9);
    }

    @Test
    public void negativerStromUndNullKmBleiben() throws Exception {
        List<BydFahrtenbuch.Fahrt> f = BydFahrtenbuch.lese(res("ec_beispiel.db"));
        boolean neg = false, null0 = false;
        for (BydFahrtenbuch.Fahrt x : f) {
            if (x.kwh < 0) neg = true;
            if (x.km == 0) { null0 = true; assertTrue("unter 1 km kein Verbrauchswert", Double.isNaN(x.kwhPro100km())); }
        }
        assertTrue(neg);
        assertTrue(null0);
    }

    @Test
    public void ueberlaufseitenWerdenGelesen() throws Exception {
        List<BydFahrtenbuch.Fahrt> f = BydFahrtenbuch.lese(res("ec_ueberlauf.db"));
        assertNotNull("Zusatzspalte mit 9000 Zeichen (Überlaufseiten) darf nicht stören", f);
        assertEquals("30 Zeilen, Zeile 4 gelöscht", 29, f.size());
    }

    @Test
    public void fremdesSchemaIstStillAus() throws Exception {
        assertNull(BydFahrtenbuch.lese(res("ec_fremd.db")));
        JSONObject o = BydFahrtenbuch.antwort(null, null, 50, UTC);
        assertTrue(o.getBoolean("success"));
        assertFalse(o.getBoolean("verfuegbar"));
        assertFalse("still aus: keine Daten, keine Fehlermeldung", o.has("fahrten") || o.has("error"));
    }

    @Test
    public void kaputteDateiIstStillAus() throws Exception {
        byte[] b = res("ec_beispiel.db");
        assertNull(BydFahrtenbuch.lese(Arrays.copyOf(b, 300)));
        byte[] kaputt = b.clone();
        int wurzel = new SqliteNurLesen(b).tabelle("EnergyConsumption").wurzel;
        for (int i = (wurzel - 1) * 4096; i < wurzel * 4096; i++) kaputt[i] = (byte) 0xFF;   // Wurzelseite zerstört
        assertNull(BydFahrtenbuch.lese(kaputt));
        assertNull(BydFahrtenbuch.lese("kein sqlite".getBytes("UTF-8")));
        assertNull(BydFahrtenbuch.lese(null));
    }

    @Test
    public void dateiFehltIstStillAus() throws Exception {
        File weg = new File(tmp.getRoot(), "energydata/EC_database.db");
        assertNull(BydFahrtenbuch.ausDatei(weg, 1000L));
        JSONObject o = BydFahrtenbuch.antwort(BydFahrtenbuch.ausDatei(weg, 1000L), new ArrayList<>(), 50, UTC);
        assertFalse(o.getBoolean("verfuegbar"));
    }

    @Test
    public void dateiWirdNurGelesenUndNieVeraendert() throws Exception {
        File ordner = tmp.newFolder("energydata");
        File db = new File(ordner, "EC_database.db");
        byte[] b = res("ec_beispiel.db");
        try (FileOutputStream o = new FileOutputStream(db)) { o.write(b); }
        long vorher = db.lastModified();
        List<BydFahrtenbuch.Fahrt> f = BydFahrtenbuch.ausDatei(db, 1000L);
        assertEquals(692, f.size());
        assertEquals(vorher, db.lastModified());
        assertTrue(Arrays.equals(b, java.nio.file.Files.readAllBytes(db.toPath())));
        String[] danach = ordner.list();
        assertEquals("kein Journal, keine Kopie neben BYDs Datei", 1, danach.length);
    }

    @Test
    public void journalDaneben_alterStandBleibt() throws Exception {
        File ordner = tmp.newFolder("energydata");
        File db = new File(ordner, "EC_database.db");
        try (FileOutputStream o = new FileOutputStream(db)) { o.write(res("ec_beispiel.db")); }
        assertEquals(692, BydFahrtenbuch.ausDatei(db, 1000L).size());
        File journal = new File(ordner, "EC_database.db-journal");
        try (FileOutputStream o = new FileOutputStream(journal)) { o.write(new byte[]{1, 2, 3}); }
        assertNull("BYD schreibt gerade: kein halbes Abbild", BydFahrtenbuch.abbild(db));
        assertTrue(db.setLastModified(db.lastModified() + 5000));
        assertEquals("letzter guter Stand", 692, BydFahrtenbuch.ausDatei(db, 200_000L).size());
    }

    @Test
    public void gruppenNachTagWocheMonat() throws Exception {
        List<BydFahrtenbuch.Fahrt> f = BydFahrtenbuch.lese(res("ec_beispiel.db"));
        JSONObject o = BydFahrtenbuch.antwort(f, new ArrayList<>(), 10, UTC);
        assertTrue(o.getBoolean("verfuegbar"));
        assertEquals(692, o.getJSONObject("gesamt").getInt("fahrten"));
        assertEquals(13.7, o.getJSONObject("gesamt").getDouble("kwhPro100km"), 0.05);   // 1764 / 12854 * 100
        assertEquals(10, o.getJSONArray("fahrten").length());
        assertEquals("neueste zuerst", 700, o.getJSONArray("fahrten").getJSONObject(0).getLong("id"));

        JSONArray monate = o.getJSONArray("monate");
        JSONObject april = null;
        for (int i = 0; i < monate.length(); i++) if ("2026-04".equals(monate.getJSONObject(i).getString("zeitraum"))) april = monate.getJSONObject(i);
        assertNotNull(april);
        assertEquals(59, april.getInt("fahrten"));
        assertEquals(1098.5, april.getDouble("km"), 0.05);
        assertEquals(148.8, april.getDouble("kwh"), 0.05);
        assertTrue(monate.getJSONObject(0).getString("zeitraum").compareTo(monate.getJSONObject(1).getString("zeitraum")) > 0);

        JSONArray tage = o.getJSONArray("tage");
        assertEquals(31, tage.length());
        JSONArray wochen = o.getJSONArray("wochen");
        assertTrue(wochen.getJSONObject(0).getString("zeitraum").matches("\\d{4}-W\\d{2}"));
        // erste Woche: Mo 02.03.2026 = KW 10
        List<BydFahrtenbuch.Fahrt> ersteWoche = f.subList(0, 13);
        JSONArray w = BydFahrtenbuch.antwort(new ArrayList<>(ersteWoche), null, 5, UTC).getJSONArray("wochen");
        assertEquals("2026-W10", w.getJSONObject(w.length() - 1).getString("zeitraum"));
    }

    @Test
    public void vergleichMitEigenenFahrten() throws Exception {
        List<BydFahrtenbuch.Fahrt> f = BydFahrtenbuch.lese(res("ec_beispiel.db"));
        BydFahrtenbuch.Fahrt a = f.get(f.size() - 1), b = f.get(f.size() - 2);
        List<BydFahrtenbuch.EigeneFahrt> eigene = new ArrayList<>();
        // gleiche Fahrt, Panoptes startet 30 s später und misst 10 % mehr
        eigene.add(new BydFahrtenbuch.EigeneFahrt(11, a.startMs + 30_000, a.endeMs + 10_000, a.km, a.kwh * 1.1));
        eigene.add(new BydFahrtenbuch.EigeneFahrt(12, b.startMs, b.endeMs, b.km, b.kwh * 1.1));
        // überlappt nur kurz → keine Paarung
        eigene.add(new BydFahrtenbuch.EigeneFahrt(13, a.endeMs - 60_000, a.endeMs + 3_600_000, 40, 6));
        JSONObject v = BydFahrtenbuch.antwort(f, eigene, 5, UTC).getJSONObject("vergleich");
        assertEquals(2, v.getInt("paare"));
        assertEquals(10, v.getInt("abweichungProzent"));
        assertEquals(2, v.getJSONArray("fahrten").length());
        assertEquals(700, v.getJSONArray("fahrten").getJSONObject(0).getLong("bydId"));
        assertEquals(11, v.getJSONArray("fahrten").getJSONObject(0).getLong("panoptesId"));
    }

    @Test
    public void ohneEigeneFahrtenKeinVergleich() throws Exception {
        List<BydFahrtenbuch.Fahrt> f = BydFahrtenbuch.lese(res("ec_beispiel.db"));
        JSONObject v = BydFahrtenbuch.antwort(f, new ArrayList<>(), 5, UTC).getJSONObject("vergleich");
        assertEquals(0, v.getInt("paare"));
        assertFalse(v.has("abweichungProzent"));
    }

    @Test
    public void spaltenAusCreateText() {
        assertEquals(Arrays.asList("_id", "month", "date", "start_timestamp", "end_timestamp", "is_deleted", "duration",
                        "trip", "electricity", "fuel"),
                SqliteNurLesen.spalten("CREATE TABLE EnergyConsumption (_id INTEGER PRIMARY KEY AUTOINCREMENT, month TEXT, "
                        + "date TEXT, start_timestamp INTEGER, end_timestamp INTEGER, is_deleted INTEGER DEFAULT 0, "
                        + "duration INTEGER, trip REAL, electricity REAL, fuel REAL )"));
        assertEquals(Arrays.asList("a", "b"), SqliteNurLesen.spalten("CREATE TABLE t (\"a\" NUMERIC(10,2), [b] TEXT, PRIMARY KEY(a))"));
        assertTrue(SqliteNurLesen.spalten("kaputt").isEmpty());
    }

    @Test
    public void sekundenOderMillisekunden() {
        assertEquals(1_783_578_921_000L, BydFahrtenbuch.sekZuMs(1_783_578_921L));
        assertEquals(1_783_578_921_000L, BydFahrtenbuch.sekZuMs(1_783_578_921_000L));
    }
}
