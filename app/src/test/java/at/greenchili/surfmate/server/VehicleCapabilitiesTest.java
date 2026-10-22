package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class VehicleCapabilitiesTest {

    private static final String TWO_DISPLAYS =
            "  mDisplayId=0\n  mName=Built-in Screen\n  mDisplayId=1\n  mName=fission cluster\n";

    @Test
    public void singleOsNeverHasClusterOrHud() throws Exception {
        JSONObject c = VehicleCapabilities.parse("1", TWO_DISPLAYS + "  mName=HUD\n");
        assertFalse(c.getBoolean("cluster"));
        assertFalse(c.getBoolean("hud"));
        assertTrue(c.getBoolean("singleOs"));
    }

    @Test
    public void secondDisplayMeansCluster() throws Exception {
        JSONObject c = VehicleCapabilities.parse("", TWO_DISPLAYS);
        assertTrue(c.getBoolean("cluster"));
        assertFalse(c.getBoolean("hud"));
        assertEquals(2, c.getInt("displays"));
    }

    @Test
    public void hudByName() throws Exception {
        assertTrue(VehicleCapabilities.parse("0", "  mDisplayId=0\n  mName=Head-Up Display\n").getBoolean("hud"));
    }

    @Test
    public void emptyOutputMeansNothing() throws Exception {
        JSONObject c = VehicleCapabilities.parse(null, null);
        assertFalse(c.getBoolean("cluster"));
        assertFalse(c.getBoolean("hud"));
    }

    // ------------------------------------------------------------ 0.1.10 Einrichtung Teil E (Schein-Daten)

    private static Map<String, Double> werte(double soc, double v12, double innen, double akku, double reifen) {
        Map<String, Double> m = new HashMap<>();
        m.put("ladestand", soc);
        m.put("spannung12v", v12);
        m.put("innentemperatur", innen);
        m.put("akkutemperatur", akku);
        m.put("reifendruck", reifen);
        return m;
    }

    @Test
    public void dilink3SurfMitCarPlay() throws Exception {
        JSONObject c = VehicleCapabilities.erweitere(VehicleCapabilities.parse("0", TWO_DISPLAYS),
                "Number of camera devices: 4\n", false,
                "package:com.byd.carplay.ui\npackage:com.byd.androidauto\npackage:at.greenchili.surfmate\n", "seagull",
                werte(64, 12.6, 21.5, 24, 250), paketeAusAsset());
        assertFalse("media.camera ist nicht die Kamerazahl des Autos", c.getJSONObject("kameras").has("anzahl"));
        assertEquals(4, c.getJSONObject("kameras").getInt("androidKameras"));
        assertEquals("ja", c.getJSONObject("kameras").getString("status"));
        assertEquals("ja", c.getJSONObject("werte").getString("ladestand"));
        assertEquals("ja", c.getJSONObject("werte").getString("reifendruck"));
        assertEquals("ja", c.getJSONObject("projektion").getString("carplay"));
        assertEquals("Surf: AA ist com.byd.androidauto", "ja", c.getJSONObject("projektion").getString("androidauto"));
        // Ohne Datentabelle wird nichts erkannt (kein Paketname im Code)
        JSONObject ohne = VehicleCapabilities.erweitere(new JSONObject(), null, false,
                "package:com.byd.carplay.ui\n", null, null, null);
        assertEquals("unbekannt", ohne.getJSONObject("projektion").getString("carplay"));
        // Teilstring zählt nicht
        JSONObject teil = VehicleCapabilities.erweitere(new JSONObject(), null, false,
                "package:com.byd.carplay.ui.extra\n", null, null, paketeAusAsset());
        assertEquals("unbekannt", teil.getJSONObject("projektion").getString("carplay"));
        // ohne Tabelle/Nutzerangabe nichts geraten (kein Modell-if im Code, Koordination 22.10.)
        assertEquals("unbekannt", c.getJSONObject("ueberProjektion").getString("carplay"));
    }

    @Test
    public void dilink4OhneKamerasUndOhneWerte() throws Exception {
        JSONObject c = VehicleCapabilities.erweitere(VehicleCapabilities.parse("1", ""),
                "Number of camera devices: 0", false,
                "package:com.google.android.projection.gearhead\n", "atto3",
                werte(Double.NaN, 0, 0, 0, 0), paketeAusAsset());
        assertFalse(c.getJSONObject("kameras").has("anzahl"));
        assertEquals("0 beweist nichts", "unbekannt", c.getJSONObject("kameras").getString("status"));
        for (String k : new String[] {"ladestand", "spannung12v", "innentemperatur", "akkutemperatur", "reifendruck"}) {
            assertEquals(k, "unbekannt", c.getJSONObject("werte").getString(k));   // nie „ja“ ohne Beleg
        }
        assertEquals("ja", c.getJSONObject("projektion").getString("androidauto"));
        assertEquals("unbekannt", c.getJSONObject("ueberProjektion").getString("carplay"));
    }

    @Test
    public void dilink5NullKamerasIstUnbekannt() throws Exception {
        // QCarCam: media.camera meldet 0, obwohl Kameras da sein können → keine Anzahl, Schritt entfällt nicht
        JSONObject c = VehicleCapabilities.erweitere(VehicleCapabilities.parse("", TWO_DISPLAYS),
                "Number of camera devices: 0", true, null, null, null);
        assertFalse(c.getJSONObject("kameras").has("anzahl"));
        assertEquals("unbekannt", c.getJSONObject("kameras").getString("status"));
        assertEquals("unbekannt", c.getJSONObject("projektion").getString("carplay"));
        assertEquals("unbekannt", c.getJSONObject("werte").getString("ladestand"));
        // mit gemeldeten Kameras zählt die Zahl auch bei DiLink 5
        JSONObject d = VehicleCapabilities.erweitere(new JSONObject(), "Number of camera devices: 6", true, "", null, null);
        assertEquals(6, d.getJSONObject("kameras").getInt("androidKameras"));
        assertEquals("ja", d.getJSONObject("kameras").getString("status"));
    }

    @Test
    public void fehlendeAusgabeAllesUnbekannt() throws Exception {
        JSONObject c = VehicleCapabilities.erweitere(new JSONObject(), null, false, null, null, null);
        assertFalse(c.getJSONObject("kameras").has("anzahl"));
        assertEquals("unbekannt", c.getJSONObject("ueberProjektion").getString("carplay"));
    }

    @Test
    public void kameraSchrittEntfaelltNurBeiBelegterNull() throws Exception {
        JSONObject root = new JSONObject();
        // media.camera 0 reicht nicht (Inventur Surf): der Schritt bleibt offen
        JSONObject null0 = VehicleCapabilities.erweitere(new JSONObject(), "Number of camera devices: 0", false, null, null, null);
        assertEquals("offen", status(EinrichtungApiHandler.sicht(root, EinrichtungApiHandler.zustand(root), null0, null), "kameras"));
        // nur eine belegte Anzahl 0 (künftige Quelle) lässt ihn entfallen
        JSONObject belegt0 = new JSONObject().put("kameras", new JSONObject().put("anzahl", 0).put("status", "nein"));
        JSONObject s = EinrichtungApiHandler.sicht(root, EinrichtungApiHandler.zustand(root), belegt0, null);
        assertEquals("entfaellt", status(s, "kameras"));
        assertTrue(s.toString().contains("einr.grund.keine_kameras"));
        JSONObject unbekannt = VehicleCapabilities.erweitere(new JSONObject(), "Number of camera devices: 0", true, null, null, null);
        JSONObject s2 = EinrichtungApiHandler.sicht(root, EinrichtungApiHandler.zustand(root), unbekannt, null);
        assertEquals("offen", status(s2, "kameras"));
        assertEquals("unbekannt", s2.getJSONObject("faehigkeiten").getJSONObject("kameras").getString("status"));
    }

    private static String status(JSONObject sicht, String id) throws Exception {
        org.json.JSONArray a = sicht.getJSONArray("schritte");
        for (int i = 0; i < a.length(); i++) {
            if (id.equals(a.getJSONObject(i).getString("id"))) return a.getJSONObject(i).getString("status");
        }
        throw new AssertionError(id);
    }

    private static JSONObject paketeAusAsset() throws Exception {
        String roh = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/assets/web/shared/faehigkeiten_belegt.json")),
                java.nio.charset.StandardCharsets.UTF_8);
        return new JSONObject(roh).getJSONObject("projektion_pakete");
    }

    private static org.json.JSONArray tabelleAusAsset() throws Exception {
        String roh = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/assets/web/shared/faehigkeiten_belegt.json")),
                java.nio.charset.StandardCharsets.UTF_8);
        return new JSONObject(roh).getJSONArray("eintraege");
    }

    @Test
    public void ueberProjektionAusTabelleNichtAusCode() throws Exception {
        org.json.JSONArray t = tabelleAusAsset();
        JSONObject surf = VehicleCapabilities.ueberProjektion(new JSONObject(), "seagull", null, t, null);
        assertEquals("ja", surf.getJSONObject("ueberProjektion").getString("carplay"));
        assertEquals("tabelle", surf.getJSONObject("ueberProjektionQuelle").getString("carplay"));
        assertEquals("Android Auto nie geraten", "unbekannt", surf.getJSONObject("ueberProjektion").getString("androidauto"));
        JSONObject atto = VehicleCapabilities.ueberProjektion(new JSONObject(), "atto3", 4, t, null);
        assertEquals("unbekannt", atto.getJSONObject("ueberProjektion").getString("carplay"));
        assertEquals("keine", atto.getJSONObject("ueberProjektionQuelle").getString("carplay"));
        assertEquals("ohne Modell kein Treffer", "unbekannt", VehicleCapabilities.ueberProjektion(
                new JSONObject(), null, null, t, null).getJSONObject("ueberProjektion").getString("carplay"));
        // Jeder Tabelleneintrag hat Quelle und Datum
        for (int i = 0; i < t.length(); i++) {
            JSONObject e = t.getJSONObject(i);
            for (String k : new String[] {"modell", "merkmal", "projektion", "wert", "quelle", "datum"}) {
                assertTrue(i + ":" + k, e.has(k) && !e.optString(k).isEmpty());
            }
        }
        // Kein Modell-Name als Sonderfall im Code
        String q = at.greenchili.surfmate.testutil.Quelltext.lies(
                java.nio.file.Paths.get("src/main/java/at/greenchili/surfmate/server/VehicleCapabilities.java"));
        assertFalse(q.contains("\"seagull\""));
        assertFalse("Paketnamen nur in der Tabelle", q.contains("gearhead") || q.contains("com.byd.carplay")
                || q.contains("contains(\"carplay\")"));
    }

    @Test
    public void nutzerAngabeUeberschreibtTabelle() throws Exception {
        org.json.JSONArray t = tabelleAusAsset();
        JSONObject nein = VehicleCapabilities.ueberProjektion(new JSONObject(), "seagull", null, t,
                new JSONObject().put("ueberCarPlay", "nein"));
        assertEquals("nein", nein.getJSONObject("ueberProjektion").getString("carplay"));
        assertEquals("nutzer", nein.getJSONObject("ueberProjektionQuelle").getString("carplay"));
        JSONObject ja = VehicleCapabilities.ueberProjektion(new JSONObject(), "atto3", null, t,
                new JSONObject().put("ueberAndroidAuto", "ja").put("ueberCarPlay", "unbekannt"));
        assertEquals("ja", ja.getJSONObject("ueberProjektion").getString("androidauto"));
        assertEquals("unbekannt = keine eigene Angabe", "unbekannt", ja.getJSONObject("ueberProjektion").getString("carplay"));
    }

    @Test
    public void dilinkPasstNurWennErhoben() {
        org.json.JSONArray t = new org.json.JSONArray().put(new JSONObject() {{
            try {
                put("modell", "seal"); put("dilink", 5); put("merkmal", "ueberProjektion");
                put("projektion", "carplay"); put("wert", "ja"); put("quelle", "x"); put("datum", "2026-10-22");
            } catch (Exception e) { throw new RuntimeException(e); }
        }});
        assertEquals("ja", VehicleCapabilities.ausTabelle(t, "seal", 5, "ueberProjektion", "carplay"));
        assertEquals(null, VehicleCapabilities.ausTabelle(t, "seal", null, "ueberProjektion", "carplay"));
        assertEquals(null, VehicleCapabilities.ausTabelle(t, "seal", 4, "ueberProjektion", "carplay"));
    }
}
