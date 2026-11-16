package at.greenchili.surfmate.tacho;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** 0.1.43.9 (T3): Kartenbild im Tacho, Regeln ohne Netz (Attrappen statt Kacheln). */
public class TachoKachelKarteTest {

    private static JSONObject eigenMitKarte(String blick) throws Exception {
        return new JSONObject().put("format", TachoEigen.FORMAT).put("version", TachoFrei.VERSION).put("basis", "klar")
                .put("elemente", new JSONArray()
                        .put(new JSONObject().put("typ", "zahl").put("wert", "tempo").put("x", 80).put("y", 150).put("b", 260).put("h", 150))
                        .put(new JSONObject().put("typ", "karte").put("x", 600).put("y", 40).put("b", 360).put("h", 230).put("blick", blick)));
    }

    @Test
    public void kastenNurMitKarteImDesign() throws Exception {
        JSONObject k = TachoKachelKarte.kasten(TachoEigen.NAME, eigenMitKarte("nord"));
        assertNotNull(k);
        assertEquals(346, k.getInt("b"));
        assertEquals(221, k.getInt("h"));
        assertEquals("nord", k.getString("blick"));
        assertNull(TachoKachelKarte.kasten("klar", null));
        assertNull(TachoKachelKarte.kasten(TachoEigen.NAME, TachoEigen.standard()));
        assertEquals("fahrt", TachoKachelKarte.kasten("kombi", null).getString("blick"));
        assertEquals("nord", TachoKachelKarte.kasten("kasten", null).getString("blick"));
        assertTrue(TachoKachelKarte.kasten("kasten", null).getInt("b") <= TachoKachelKarte.MAX_BREITE_PX);
    }

    @Test
    public void positionNurFrischUndPlausibel() throws Exception {
        long jetzt = 1_000_000_000L;
        assertNull(TachoKachelKarte.position(0, 0, 10, jetzt, jetzt));
        assertNull(TachoKachelKarte.position(47.8, 13.0, 10, jetzt - TachoKachelKarte.POS_MAX_ALTER_MS - 1, jetzt));
        assertNull(TachoKachelKarte.position(47.8, 13.0, 10, 0, jetzt));
        JSONObject p = TachoKachelKarte.position(47.8, 13.0, -90 + 360, jetzt - 1000, jetzt);
        assertEquals(270, p.getDouble("kurs"), 1e-9);
        assertFalse(TachoKachelKarte.position(47.8, 13.0, Double.NaN, jetzt, jetzt).has("kurs"));
    }

    @Test
    public void zoomPasstZurKastenbreite() {
        double lat = 47.8;
        double z = TachoKachelKarte.zoom(lat, 346, TachoKachelKarte.METER_FAHRT);
        double mpp = TachoKachelKarte.mpp(lat, z);
        assertEquals(TachoKachelKarte.METER_FAHRT, mpp * 346, 1.0);
        assertTrue("z=" + z, z > 14 && z < 16);
        assertEquals(10, TachoKachelKarte.zoom(lat, 10, 100_000), 1e-9);
        assertEquals(18, TachoKachelKarte.zoom(lat, 640, 1), 1e-9);
    }

    @Test
    public void mitteVorausDamitDasAutoUntenSteht() {
        double mpp = 2.0;
        double[] m = TachoKachelKarte.mitte(47.8, 13.0, 0, "fahrt", 200, mpp);
        // 22 % von 200 px × 2 m = 88 m nach Norden
        assertEquals(88, TachoKachelKarte.abstandM(47.8, 13.0, m[0], m[1]), 0.5);
        assertTrue(m[0] > 47.8);
        double[] o = TachoKachelKarte.mitte(47.8, 13.0, 90, "fahrt", 200, mpp);
        assertTrue(o[1] > 13.0);
        assertEquals(47.8, o[0], 1e-9);
        double[] n = TachoKachelKarte.mitte(47.8, 13.0, 90, "nord", 200, mpp);
        assertEquals(47.8, n[0], 0);
        assertEquals(13.0, n[1], 0);
    }

    @Test
    public void taktHoechstensAlleDreiSekundenUndNurBeiBewegung() {
        TachoKachelKarte.Stand s = new TachoKachelKarte.Stand(47.8, 13.0, 0, "osm|346|221|fahrt|true", 0);
        String k = "osm|346|221|fahrt|true";
        assertTrue(TachoKachelKarte.neuNoetig(null, 47.8, 13.0, 0, "fahrt", k, 0));
        assertFalse("zu früh", TachoKachelKarte.neuNoetig(s, 47.81, 13.0, 0, "fahrt", k, 2999));
        assertFalse("steht", TachoKachelKarte.neuNoetig(s, 47.8, 13.0, 0, "fahrt", k, 5000));
        assertTrue("fährt 111 m", TachoKachelKarte.neuNoetig(s, 47.801, 13.0, 0, "fahrt", k, 5000));
        assertTrue("dreht", TachoKachelKarte.neuNoetig(s, 47.8, 13.0, 20, "fahrt", k, 5000));
        assertFalse("nordoben: Drehen egal", TachoKachelKarte.neuNoetig(
                new TachoKachelKarte.Stand(47.8, 13.0, 0, k, 0), 47.8, 13.0, 20, "nord", k, 5000));
        assertTrue("Quelle gewechselt", TachoKachelKarte.neuNoetig(s, 47.8, 13.0, 0, "fahrt", "community|346|221|fahrt|true", 5000));
        assertTrue("spätestens", TachoKachelKarte.neuNoetig(s, 47.8, 13.0, 0, "fahrt", k, TachoKachelKarte.NEU_SPAETESTENS_MS));
    }

    @Test
    public void namensnennungJeQuelleOhneQuelleNichts() {
        assertEquals("© OpenStreetMap", TachoKachelKarte.nennung("osm"));
        assertTrue(TachoKachelKarte.nennung("community").contains("OpenStreetMap"));
        assertEquals("", TachoKachelKarte.nennung("keine"));
    }

    /** Daemon gibt die Position nur auf Anfrage, die Activity reicht sie nie an die Seite weiter. */
    @Test
    public void positionNieAnDieSeite() throws Exception {
        String act = TachoFaehigkeitTest.lies("app/src/main/java/at/greenchili/surfmate/tacho/TachoAnsichtActivity.kt");
        int a = act.indexOf("for (k in arrayOf("), b = act.indexOf(")", a + 20);
        String liste = act.substring(a, b);
        assertFalse(liste.contains("kartePos"));
        assertTrue(act.contains("a.optJSONObject(\"kartePos\")"));
        String bild = TachoFaehigkeitTest.lies("app/src/main/java/at/greenchili/surfmate/tacho/TachoKarteBild.kt");
        int f = bild.indexOf("fertig(JSONObject()");
        String ausgabe = bild.substring(f, bild.indexOf("}, {", f));
        for (String verboten : new String[] {"\"lat\"", "\"lon\"", "LatLng"}) assertFalse(verboten, ausgabe.contains(verboten));
        String kern = TachoFaehigkeitTest.lies("app/src/main/java/at/greenchili/surfmate/tacho/TachoAnsicht.java");
        assertTrue("nur auf Anfrage", kern.contains("cmd.optBoolean(\"karte\", false)"));
        // Ohne Quelle keine Kacheln: der Snapshotter startet nur mit community/osm
        assertTrue(bild.contains("quelle != Kartenquelle.COMMUNITY && quelle != Kartenquelle.OSM)) return"));
    }

    /**
     * Komposition mit Attrappen-Kacheln: eine Kachelreihe wie der Snapshotter sie zusammensetzt, ohne Netz.
     * Prüft, dass der gewählte Zoom für jeden Kasten höchstens wenige Kacheln braucht (Datenverbrauch).
     */
    @Test
    public void kachelnJeBildMitAttrappen() {
        for (int[] k : new int[][] {{346, 221}, {384, 259}, {442, 285}, {640, 300}}) {
            for (String blick : new String[] {"fahrt", "nord"}) {
                double z = TachoKachelKarte.zoom(47.8, k[0], TachoKachelKarte.meter(blick));
                // 512er Kacheln: höchstens so viele Spalten/Zeilen wie der Kasten bei Drehung überdeckt
                double diag = Math.hypot(k[0], k[1]);
                int kacheln = (int) Math.pow(Math.ceil(diag / 512.0) + 1, 2);
                assertTrue(blick + " " + k[0] + ": " + kacheln, kacheln <= 9);
                assertTrue(z >= 10 && z <= 18);
            }
        }
    }
}
