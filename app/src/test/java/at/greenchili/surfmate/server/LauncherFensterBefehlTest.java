package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.byd.routing.VehicleCommandRouter;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 0.1.44 (D1 „Fahrzeug-Startseite“): enger Kern-Fensterweg des Launchers und die Karosserie-Daten im Summary.
 * Nur vl/vr/hl/hr/dach auf/zu, nur im Stand, Ratenlimit, nur von der Launcher-APK; neue Kacheln und Szenen im Abgleich.
 */
public class LauncherFensterBefehlTest {

    private final List<VehicleCommandRouter.VehicleCommand> gesendet = new ArrayList<>();
    private String gang = "P";
    private boolean zuendung = true;
    private Double tempo = 0.0;
    private LauncherFensterBefehl.Auto vorher;

    @Before
    public void auf() {
        vorher = LauncherFensterBefehl.auto;
        LauncherFensterBefehl.ratenlimitLeeren();
        LauncherFensterBefehl.auto = new LauncherFensterBefehl.Auto() {
            @Override public String gang() { return gang; }
            @Override public boolean zuendungAn() { return zuendung; }
            @Override public Double tempo() { return tempo; }
            @Override public LauncherKlimaBefehl.Ergebnis ausfuehren(VehicleCommandRouter.VehicleCommand c) {
                gesendet.add(c);
                return new LauncherKlimaBefehl.Ergebnis("success", "sdk");
            }
        };
    }

    @After
    public void ab() {
        LauncherFensterBefehl.auto = vorher;
        LauncherFensterBefehl.ratenlimitLeeren();
    }

    private static VehicleCommandRouter.WindowMoveCommand w(String f, String z) {
        return (VehicleCommandRouter.WindowMoveCommand) LauncherFensterBefehl.befehl(f, z);
    }

    @Test
    public void befehleWieVehicleControl() {
        VehicleCommandRouter.WindowMoveCommand c = w("vl", "auf");
        assertEquals(1, c.area); assertEquals(1, c.action); assertNull(c.targetPercent);
        c = w("hr", "zu");
        assertEquals(4, c.area); assertEquals(2, c.action);
        c = w("vr", "auf"); assertEquals(2, c.area);
        c = w("hl", "auf"); assertEquals(3, c.area);
        c = w("dach", "auf");
        assertEquals(5, c.area); assertEquals(Integer.valueOf(100), c.targetPercent);
        assertEquals(Integer.valueOf(0), w("dach", "zu").targetPercent);
        for (String[] p : new String[][]{{"alle", "auf"}, {"hupe", "an"}, {"vl", "50"}, {"vl", null}, {null, "auf"}, {"sunshade", "auf"}})
            assertNull(p[0] + "/" + p[1], LauncherFensterBefehl.befehl(p[0], p[1]));
        assertEquals(5, LauncherFensterBefehl.FENSTER.size());
    }

    @Test
    public void nurImStand() {
        assertTrue(LauncherFensterBefehl.imStand("P", true, 0.0));
        assertTrue(LauncherFensterBefehl.imStand("P", true, null));
        assertTrue(LauncherFensterBefehl.imStand(null, false, null));
        assertFalse(LauncherFensterBefehl.imStand("D", true, 0.0));
        assertFalse(LauncherFensterBefehl.imStand("N", true, 0.0));
        assertFalse(LauncherFensterBefehl.imStand("R", true, 0.0));
        assertFalse("Tempo sperrt immer", LauncherFensterBefehl.imStand("P", true, 3.0));
        assertFalse("Gang unbekannt bei Zündung an", LauncherFensterBefehl.imStand(null, true, 0.0));
    }

    private JSONObject los(String body, long t, int status) {
        Object[] r = LauncherFensterBefehl.ausfuehren(body, t);
        assertEquals(body, status, ((Integer) r[0]).intValue());
        return (JSONObject) r[1];
    }

    @Test
    public void ausfuehrenMitAllenSperren() {
        assertEquals("aktion", los("{\"fenster\":\"hupe\",\"ziel\":\"auf\"}", 1_000, 400).optString("grund"));
        assertEquals("aktion", los("kaputt", 1_000, 400).optString("grund"));
        gang = "D";
        assertEquals("fahrt", los("{\"fenster\":\"vl\",\"ziel\":\"auf\"}", 1_000, 409).optString("grund"));
        gang = "P"; tempo = 8.0;
        assertEquals("fahrt", los("{\"fenster\":\"vl\",\"ziel\":\"auf\"}", 1_000, 409).optString("grund"));
        assertTrue("nichts gesendet", gesendet.isEmpty());
        tempo = 0.0;
        JSONObject o = los("{\"fenster\":\"vl\",\"ziel\":\"auf\"}", 10_000, 200);
        assertTrue(o.optBoolean("ok")); assertEquals("success", o.optString("ausgang")); assertEquals("sdk", o.optString("weg"));
        assertEquals(1, gesendet.size());
        assertEquals("ratenlimit", los("{\"fenster\":\"vr\",\"ziel\":\"zu\"}", 10_200, 429).optString("grund"));
        los("{\"fenster\":\"vr\",\"ziel\":\"zu\"}", 11_500, 200);
        assertEquals(2, gesendet.size());
        // Router meldet Fahrsperre/Fehler → ok:false mit Grund
        LauncherFensterBefehl.auto = new LauncherFensterBefehl.Auto() {
            @Override public String gang() { return "P"; }
            @Override public boolean zuendungAn() { return true; }
            @Override public Double tempo() { return 0.0; }
            @Override public LauncherKlimaBefehl.Ergebnis ausfuehren(VehicleCommandRouter.VehicleCommand c) {
                return new LauncherKlimaBefehl.Ergebnis("blocked_driving", "none");
            }
        };
        JSONObject f = los("{\"fenster\":\"dach\",\"ziel\":\"zu\"}", 20_000, 200);
        assertFalse(f.optBoolean("ok")); assertEquals("blocked_driving", f.optString("grund"));
    }

    @Test
    public void koerperNurBekannteWerte() throws Exception {
        JSONObject t = new JSONObject().put("lf", true).put("rf", false).put("fuelCap", true);
        JSONObject f = new JSONObject().put("lf", 0).put("rf", 40).put("lr", -1).put("rr", 101).put("sunroof", 25).put("sunshade", 80);
        JSONObject k = LauncherApiHandler.koerper(t, f);
        assertEquals("{\"lf\":true,\"rf\":false}", sortiert(k.getJSONObject("tueren")));
        JSONObject fe = k.getJSONObject("fenster");
        assertEquals(0, fe.getInt("vl")); assertEquals(40, fe.getInt("vr")); assertEquals(25, fe.getInt("dach"));
        assertFalse(fe.has("hl")); assertFalse(fe.has("hr")); assertFalse("kein Rollo", fe.has("sunshade"));
        assertTrue(k.getBoolean("dach"));
        JSONObject leer = LauncherApiHandler.koerper(new JSONObject(), new JSONObject().put("lf", 0));
        assertFalse(leer.getBoolean("dach"));
        assertNotNull(LauncherApiHandler.koerper(null, null));
    }

    private static String sortiert(JSONObject o) {
        List<String> k = new ArrayList<>();
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) k.add(it.next());
        Collections.sort(k);
        StringBuilder sb = new StringBuilder("{");
        for (String s : k) { if (sb.length() > 1) sb.append(','); sb.append('"').append(s).append("\":").append(o.opt(s)); }
        return sb.append('}').toString();
    }

    private static String lies(String rel) throws Exception {
        File f = new File(rel);
        if (!f.exists()) f = new File("app/" + rel);
        if (!f.exists()) f = new File(rel.replace("../", ""));
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    public void verdrahtung() throws Exception {
        String h = lies("src/main/java/at/greenchili/surfmate/server/LauncherApiHandler.java");
        String weg = h.substring(h.indexOf("if (cleanPath.equals(FENSTER_PFAD)"), h.indexOf("// 0.1.37 „Tacho wieder normal“"));
        assertTrue(weg.contains("\"POST\".equals(method)"));
        assertTrue("erst der Absender, dann der Befehl",
                weg.indexOf("launcherDarf(out)") >= 0 && weg.indexOf("launcherDarf(out)") < weg.indexOf("LauncherFensterBefehl.ausfuehren"));
        assertTrue(h.contains("static final String FENSTER_PFAD = \"/api/launcher/v1/fenster\";"));
        assertTrue(h.contains("root.put(\"koerper\",   buildKoerper());"));
        assertTrue(lies("src/main/java/at/greenchili/surfmate/server/BefehlsLog.java").contains("\"/api/launcher/v1/fenster\""));
        assertTrue(lies("src/main/java/at/greenchili/surfmate/server/DemoProfil.java").contains("\"/api/launcher/v1/fenster\""));
        String fb = lies("src/main/java/at/greenchili/surfmate/server/LauncherFensterBefehl.java");
        // nur der Fensterbefehl des Routers
        for (String verboten : new String[]{"HornCommand", "FlashCommand", "FindCar", "LockCommand", "TrunkCommand", "OpenAllWindowsCommand", "VentAllWindowsCommand"})
            assertFalse(verboten, fb.contains(verboten));
    }

    @Test
    public void neueKachelnSzenenUndVorlageImAbgleich() throws Exception {
        assertTrue(LauncherLayoutPruefung.KACHELN.contains("fahrstatus"));
        assertTrue(LauncherLayoutPruefung.KACHELN.contains("autoansicht"));
        assertTrue(LauncherLayoutPruefung.hintergrundGueltig("motiv-11"));
        assertTrue(LauncherLayoutPruefung.hintergrundGueltig("motiv-14"));
        assertTrue("0.1.43.4: gerenderte Szenen", LauncherLayoutPruefung.hintergrundGueltig("motiv-15"));
        assertTrue(LauncherLayoutPruefung.hintergrundGueltig("motiv-20"));
        assertFalse(LauncherLayoutPruefung.hintergrundGueltig("motiv-21"));
        String vorlage = lies("../panoptes-launcher/src/main/assets/vorlagen/fahrzeug.json");
        LauncherLayoutPruefung.Ergebnis e = LauncherLayoutPruefung.pruefen(new JSONObject(vorlage), Collections.<String>emptySet());
        assertNotNull(e.layout);
        assertEquals("nichts fällt weg", 0, e.bereinigt);
        assertEquals("0.1.43.4: Vorlage Fahrzeug mit gerenderter Stadt", "motiv-16", e.layout.getString("hintergrund"));
        String web = lies("src/main/assets/web/local/launcher.html");
        assertTrue(web.contains("KACHELN.push('fahrstatus', 'autoansicht');"));
        for (String l : new String[]{"de", "en", "fr", "it", "nl"}) {
            String j = lies("src/main/assets/web/i18n/" + l + ".json");
            assertTrue(l, j.contains("\"k_fahrstatus\"") && j.contains("\"k_autoansicht\""));
        }
    }
}
