package at.greenchili.surfmate.planer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.einstellungen.Feld;
import at.greenchili.surfmate.einstellungen.Katalog;

import org.json.JSONObject;
import org.junit.Test;

/** LADEN_VERTRAG A7 / Planer §14: Vorlieben nur mit eigener Einwilligung, nur bei Änderung, Widerruf mit vorlieben:null. */
public class AutoZustandVorliebenTest {

    static class Env implements AutoZustand.Umgebung {
        boolean ein = true, gesendet, positionGesendet, vorliebenGesendet;
        AutoZustand.Messung m;
        JSONObject vorlieben;
        public boolean einwilligung() { return ein; }
        public boolean positionEinwilligung() { return false; }
        public String autoId() { return "a-0123456789abcdef"; }
        public String[] geraeteToken() { return AutoZustandTest.TOKEN; }
        public AutoZustand.Messung messung() { return m; }
        public boolean hatGesendet() { return gesendet; }
        public void merkeGesendet(boolean v) { gesendet = v; }
        public boolean hatPositionGesendet() { return positionGesendet; }
        public void merkePositionGesendet(boolean v) { positionGesendet = v; }
        @Override public JSONObject vorlieben() { return vorlieben; }
        @Override public boolean hatVorliebenGesendet() { return vorliebenGesendet; }
        @Override public void merkeVorliebenGesendet(boolean v) { vorliebenGesendet = v; }
    }

    private static JSONObject stueck(int umweg) throws Exception {
        return new JSONObject().put("karten", new org.json.JSONArray().put(4)).put("nur_meine_karten", 0)
                .put("filter", new JSONObject().put("bevorzugt", new org.json.JSONArray()).put("umweg_max_min", umweg))
                .put("vorlieben_quelle", "auto");
    }

    private static AutoZustand.Messung m(double soc, long t) {
        return new AutoZustand.Messung(soc, 230, false, Double.NaN, t);
    }

    @Test
    public void ohneEinwilligung_keineVorliebenImBody() throws Exception {
        AutoZustandTest.Netz n = new AutoZustandTest.Netz();
        Env e = new Env();
        e.m = m(60, 1_000L);
        new AutoZustand(n, e).takt(1_000L);
        assertEquals(1, n.bodies.size());
        assertFalse(n.bodies.get(0).has("vorlieben"));
    }

    @Test
    public void mitEinwilligung_mitgesendet_undBeiAenderungErneut() throws Exception {
        AutoZustandTest.Netz n = new AutoZustandTest.Netz();
        Env e = new Env();
        e.vorlieben = stueck(10);
        e.m = m(60, 1_000L);
        AutoZustand z = new AutoZustand(n, e);
        z.takt(1_000L);
        assertEquals(10, n.bodies.get(0).getJSONObject("vorlieben").getJSONObject("filter").getInt("umweg_max_min"));
        assertTrue(e.vorliebenGesendet);
        assertFalse("nie Position nebenbei", n.bodies.get(0).has("position"));
        // gleiche Vorlieben, gleicher Ladestand → nichts
        e.m = m(60, 100_000L);
        z.takt(100_000L);
        assertEquals(1, n.bodies.size());
        // Vorlieben geändert → nach der Server-Bremse (60 s) erneut, auch ohne neuen Ladestand
        e.vorlieben = stueck(20);
        e.m = m(60, 120_000L);
        z.takt(30_000L + 1_000L);
        assertEquals(1, n.bodies.size());
        z.takt(120_000L);
        assertEquals(2, n.bodies.size());
        assertEquals(20, n.bodies.get(1).getJSONObject("vorlieben").getJSONObject("filter").getInt("umweg_max_min"));
    }

    @Test
    public void widerruf_sendetVorliebenNull_einmal() throws Exception {
        AutoZustandTest.Netz n = new AutoZustandTest.Netz();
        Env e = new Env();
        e.vorliebenGesendet = true;
        e.vorlieben = null;   // Einwilligung aus
        e.m = m(60, 1_000L);
        AutoZustand z = new AutoZustand(n, e);
        z.takt(1_000L);
        assertEquals("auto-zustand", n.ops.get(0));
        assertTrue(n.bodies.get(0).isNull("vorlieben"));
        assertEquals(2, n.bodies.get(0).length());   // nur auto + vorlieben:null
        assertFalse(e.vorliebenGesendet);
        int vorher = n.ops.size();
        e.m = m(60, 2_000L);
        z.takt(2_000L);
        for (int i = vorher; i < n.bodies.size(); i++) assertFalse(n.bodies.get(i).has("vorlieben"));
    }

    @Test
    public void zustandAus_loeschtAuchVorlieben() throws Exception {
        AutoZustandTest.Netz n = new AutoZustandTest.Netz();
        Env e = new Env();
        e.ein = false;
        e.gesendet = true;
        e.vorliebenGesendet = true;
        new AutoZustand(n, e).takt(1_000L);
        assertEquals("auto-zustand/loeschen", n.ops.get(0));
        assertEquals("auto-zustand", n.ops.get(1));
        assertTrue(n.bodies.get(1).isNull("vorlieben"));
        assertFalse(e.vorliebenGesendet);
    }

    @Test
    public void widerruf_netzfehler_spaeterWiederholt() throws Exception {
        AutoZustandTest.Netz n = new AutoZustandTest.Netz();
        n.standardCode = -1;   // kein Netz
        Env e = new Env();
        e.vorliebenGesendet = true;
        e.m = m(60, 1_000L);
        AutoZustand z = new AutoZustand(n, e);
        z.takt(1_000L);
        assertTrue(e.vorliebenGesendet);
        n.standardCode = 200;
        z.takt(1_000L + AutoZustand.BACKOFF_START_MS);
        assertFalse(e.vorliebenGesendet);
    }

    @Test
    public void einwilligungImSchema_standardAus_mitBestaetigung() {
        Feld f = Katalog.finde(Katalog.felder(new Katalog.Kontext()), "planner.vorliebenSenden");
        assertEquals(Boolean.FALSE, f.standard);
        assertEquals("datenschutz", f.gruppe);
        assertEquals("planer.vorliebenEinwilligung", f.bestaetigung);
        assertTrue(f.nurLesendExtern);
    }
}
