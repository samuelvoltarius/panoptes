package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

/** Koordination 22.10.: rohe Planer-Fehlertexte nie anzeigen; fahrzeug_fehlt mit eigenem Text und Auswahl-Hinweis. */
public class PlanerFehlerTest {

    static final PlanerFehler.Texte T = (k, r, a) -> k + (a.length > 0 ? "|" + a[0] : "");

    @Test
    public void fahrzeugFehlt_eigenerText() throws Exception {
        JSONObject o = new JSONObject(PlanerFehler.fuerNutzer(400,
                "{\"code\":\"fahrzeug_fehlt\",\"fehler\":\"Fahrzeug angeben: Feld 'fahrzeug' …\"}", T));
        assertEquals("planer.navi.fahrzeug_fehlt", o.getString("fehler"));
        assertTrue(o.getBoolean("fahrzeugWaehlen"));
        assertFalse(o.toString().contains("Feld 'fahrzeug'"));
    }

    @Test
    public void andereFehler_allgemeinMitCode_nieRoh() throws Exception {
        JSONObject a = new JSONObject(PlanerFehler.fuerNutzer(400, "{\"code\":\"route_fehlt\",\"fehler\":\"Valhalla: no path\"}", T));
        assertEquals("planer.navi.planer_fehler|route_fehlt", a.getString("fehler"));
        JSONObject b = new JSONObject(PlanerFehler.fuerNutzer(500, "{\"fehler\":\"Planung fehlgeschlagen: KeyError 'x'\"}", T));
        assertEquals("planer.navi.planer_fehler_allgemein", b.getString("fehler"));
        assertFalse(b.toString().contains("KeyError"));
        JSONObject c = new JSONObject(PlanerFehler.fuerNutzer(429, "{\"fehler\":\"zu oft\",\"warte_s\":60}", T));
        assertEquals("planer.navi.zu_viele", c.getString("fehler"));
        assertEquals(60, c.getInt("warte_s"));
    }

    @Test
    public void erfolgUnveraendert() {
        String ok = "{\"route\":{},\"stopps\":[]}";
        assertEquals(ok, PlanerFehler.fuerNutzer(200, ok, T));
        assertEquals("{\"x\":1}", PlanerFehler.fuerNutzer(404, "{\"x\":1}", T));   // kein Fehlerobjekt
    }

    @Test
    public void verdrahtet() throws Exception {
        String p = Files.exists(Paths.get("src/main/java")) ? "src/main/java/" : "app/src/main/java/";
        String navi = new String(Files.readAllBytes(Paths.get(p + "at/greenchili/surfmate/server/NaviApiHandler.java")), "UTF-8");
        assertTrue(navi.contains("PlanerFehler.fuerNutzer(code, text, PlanerFehler.LAUFZEIT)"));
        assertTrue(navi.contains("FahrzeugWahl.einsetzen(planBody(body))"));
    }
}
