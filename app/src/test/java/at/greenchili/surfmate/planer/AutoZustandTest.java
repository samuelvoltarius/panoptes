package at.greenchili.surfmate.planer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/** Vertrag 9: Auto-Zustand an den Planer – nur mit Einwilligung, nie Position, Takt, Backoff, Widerruf. */
public class AutoZustandTest {

    static final String[] TOKEN = {"X-Geraete-Token", "aa"};

    static final class Netz implements AutoZustand.Netz {
        final List<String> ops = new ArrayList<>();
        final List<JSONObject> bodies = new ArrayList<>();
        final ArrayDeque<PlanerVerbindung.Antwort> antworten = new ArrayDeque<>();
        int standardCode = 200;

        public PlanerVerbindung.Antwort post(String op, String[] t, JSONObject b) {
            ops.add(op);
            bodies.add(b);
            if (!antworten.isEmpty()) return antworten.poll();
            return standardCode < 0 ? null : new PlanerVerbindung.Antwort(standardCode, new JSONObject());
        }
    }

    static final class Env implements AutoZustand.Umgebung {
        boolean ein = true, gesendet = false, positionEin = false;
        String[] token = TOKEN;
        AutoZustand.Messung m;

        public boolean einwilligung() { return ein; }
        public boolean positionEinwilligung() { return positionEin; }
        public String autoId() { return "a-0123456789abcdef"; }
        public String[] geraeteToken() { return token; }
        public AutoZustand.Messung messung() { return m; }
        public boolean hatGesendet() { return gesendet; }
        public void merkeGesendet(boolean v) { gesendet = v; }
        boolean positionGesendet = false;
        public boolean hatPositionGesendet() { return positionGesendet; }
        public void merkePositionGesendet(boolean v) { positionGesendet = v; }
    }

    private static AutoZustand.Messung m(double soc, boolean laedt, double kw, long t) {
        return new AutoZustand.Messung(soc, 230, laedt, kw, t);
    }

    @Test
    public void ohneEinwilligungNieSendenUndNieLoeschen() {
        Netz n = new Netz();
        Env e = new Env();
        e.ein = false;
        e.m = m(64, false, Double.NaN, 1_000L);
        AutoZustand z = new AutoZustand(n, e);
        z.takt(1_000L);
        z.takt(1_000_000L);
        assertTrue("kein Versand und kein abmelden ohne je erteilte Einwilligung", n.ops.isEmpty());
    }

    @Test
    public void sendetNurErlaubteFelderBeiAenderungHoechstensAlle5Min() throws Exception {
        Netz n = new Netz();
        Env e = new Env();
        AutoZustand z = new AutoZustand(n, e);
        e.m = new AutoZustand.Messung(64.4, 231, true, 6.63, 10_000L);
        z.takt(10_000L);
        assertEquals(1, n.ops.size());
        assertEquals("auto-zustand", n.ops.get(0));
        JSONObject b = n.bodies.get(0);
        assertEquals("a-0123456789abcdef", b.getString("auto"));
        assertEquals(64.4, b.getDouble("soc"), 1e-9);
        assertEquals(231, b.getInt("reichweite_km"));
        assertTrue(b.getBoolean("laedt"));
        assertEquals(6.6, b.getDouble("ladeleistung_kw"), 1e-9);
        assertTrue(b.getString("zeit").endsWith("Z"));
        Iterator<String> it = b.keys();
        while (it.hasNext()) {
            String k = it.next();
            assertTrue(k, Arrays.asList("auto", "soc", "reichweite_km", "laedt", "ladeleistung_kw", "zeit").contains(k));
        }
        assertTrue(e.gesendet);

        e.m = m(70, true, 6.6, 60_000L);              // Änderung, aber innerhalb 5 min
        z.takt(60_000L);
        assertEquals(1, n.ops.size());
        e.m = m(70, true, 6.6, 320_000L);             // nach 5 min
        z.takt(320_000L);
        assertEquals(2, n.ops.size());
        e.m = m(70.4, true, 6.9, 700_000L);           // keine nennenswerte Änderung
        z.takt(700_000L);
        assertEquals(2, n.ops.size());
    }

    @Test
    public void autoSchlaeftNichtSenden() {
        Netz n = new Netz();
        Env e = new Env();
        e.m = m(64, false, Double.NaN, 0L);
        AutoZustand z = new AutoZustand(n, e);
        z.takt(AutoZustand.FRISCH_MS + 1);
        assertTrue(n.ops.isEmpty());
        assertEquals("auto_schlaeft", z.zustand().optString("status"));
    }

    @Test
    public void netzfehlerBackoffUnd429() throws Exception {
        Netz n = new Netz();
        n.standardCode = -1;
        Env e = new Env();
        AutoZustand z = new AutoZustand(n, e);
        e.m = m(50, false, Double.NaN, 1_000L);
        z.takt(1_000L);
        assertEquals("planer_nicht_erreichbar", z.zustand().optString("status"));
        e.m = m(50, false, Double.NaN, 30_000L);
        z.takt(30_000L);                                    // innerhalb Backoff 60 s
        assertEquals(1, n.ops.size());
        n.antworten.add(new PlanerVerbindung.Antwort(429, new JSONObject().put("warte_s", 120)));
        e.m = m(50, false, Double.NaN, 62_000L);
        z.takt(62_000L);
        assertEquals("gebremst", z.zustand().optString("status"));
        e.m = m(50, false, Double.NaN, 150_000L);
        z.takt(150_000L);                                   // warte_s 120 noch nicht vorbei
        assertEquals(2, n.ops.size());
    }

    @Test
    public void widerrufLoeschtAmServerNurNachEchtemVersandUndNurEinmal() {
        Netz n = new Netz();
        Env e = new Env();
        AutoZustand z = new AutoZustand(n, e);
        e.m = m(50, false, Double.NaN, 1_000L);
        z.takt(1_000L);
        assertTrue(e.gesendet);
        e.ein = false;
        z.takt(2_000L);
        assertEquals(AutoZustand.WIDERRUF_OP, n.ops.get(n.ops.size() - 1));
        assertFalse("nie geraet/abmelden – das entfernt das Gerät", n.ops.contains("geraet/abmelden"));
        assertFalse(e.gesendet);
        int vorher = n.ops.size();
        z.takt(3_000L);
        assertEquals(vorher, n.ops.size());
    }

    @Test
    public void nurMitGeraeteSchluessel() {
        Netz n = new Netz();
        Env e = new Env();
        e.token = null;
        e.m = m(50, false, Double.NaN, 1_000L);
        AutoZustand z = new AutoZustand(n, e);
        z.takt(1_000L);
        assertTrue(n.ops.isEmpty());
        assertEquals("kein_schluessel", z.zustand().optString("status"));
    }

    @Test
    public void positionNurMitEigenerEinwilligungUndNurLatLon() throws Exception {
        Netz n = new Netz();
        Env e = new Env();
        AutoZustand z = new AutoZustand(n, e);
        e.m = m(50, false, Double.NaN, 1_000L).mitPosition(47.80123, 13.04456, 1_000L);
        z.takt(1_000L);
        assertFalse("ohne Positions-Einwilligung keine Position", n.bodies.get(0).has("position"));
        assertFalse(n.bodies.get(0).has("lat"));

        e.positionEin = true;
        e.m = m(60, false, Double.NaN, 400_000L).mitPosition(47.80123, 13.04456, 400_000L);
        z.takt(400_000L);
        JSONObject b = n.bodies.get(1);
        JSONObject pos = b.getJSONObject("position");
        assertEquals(2, pos.length());
        assertEquals(47.8012, pos.getDouble("lat"), 1e-9);
        assertEquals(13.0446, pos.getDouble("lon"), 1e-9);
        assertFalse(b.has("lat") || b.has("lon"));

        // veralteter GPS-Fix: keine Position
        e.m = m(70, false, Double.NaN, 800_000L).mitPosition(47.8, 13.0, 1_000L);
        z.takt(800_000L);
        assertFalse(n.bodies.get(2).has("position"));
    }

    @Test
    public void beimAbstellenEinmalSenden() {
        Netz n = new Netz();
        Env e = new Env();
        AutoZustand z = new AutoZustand(n, e);
        e.m = m(50, false, Double.NaN, 1_000L).mitAcc(true);
        z.takt(1_000L);
        assertEquals(1, n.ops.size());
        e.m = m(50, false, Double.NaN, 120_000L).mitAcc(false);   // abgestellt, ohne Änderung, < 5 min
        z.takt(120_000L);
        assertEquals("Abstellen sendet einmal", 2, n.ops.size());
        e.m = m(50, false, Double.NaN, 200_000L).mitAcc(false);
        z.takt(200_000L);
        assertEquals("nur einmal", 2, n.ops.size());
    }

    @Test
    public void positionsWiderrufLoeschtNurDiePositionUndNurWennJeGesendet() throws Exception {
        Netz n = new Netz();
        Env e = new Env();
        AutoZustand z = new AutoZustand(n, e);
        e.positionEin = false;
        e.m = m(50, false, Double.NaN, 1_000L).mitPosition(47.8, 13.0, 1_000L);
        z.takt(1_000L);
        assertFalse("nie Position gesendet -> kein Widerruf", n.ops.contains(AutoZustand.WIDERRUF_OP));

        e.positionEin = true;
        e.m = m(60, false, Double.NaN, 400_000L).mitPosition(47.8, 13.0, 400_000L);
        z.takt(400_000L);
        assertTrue(e.positionGesendet);

        e.positionEin = false;
        e.m = m(60, false, Double.NaN, 401_000L);
        z.takt(401_000L);
        int i = n.ops.lastIndexOf(AutoZustand.WIDERRUF_OP);
        assertTrue(i >= 0);
        assertEquals("position", n.bodies.get(i).getString("nur"));
        assertFalse(e.positionGesendet);
        assertTrue("Zustand bleibt", e.gesendet);
        assertFalse(n.ops.contains("geraet/abmelden"));
    }
}
