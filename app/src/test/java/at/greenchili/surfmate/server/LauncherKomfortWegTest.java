package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.byd.BydVehicleData;
import at.greenchili.surfmate.byd.routing.VehicleCommandRouter;
import at.greenchili.surfmate.byd.routing.VehicleCommandRouter.VehicleCommand;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 0.1.38.6 Launcher-Komfort (04.11.: „Sitzheizung steuern, Klimaanlage und so weiter“): Sitzheizung/-lüftung,
 * Lenkrad, Umluft aus dem GELESENEN Ist-Stand, Auto-Modus, Entfrosten, Nur-Gebläse als an/aus-Paar, alles über denselben
 * engen Weg {@code klima} (nur Launcher-APK, Ratenlimit, Router-Befehle). Die Tabu-Liste bleibt unerreichbar.
 */
public class LauncherKomfortWegTest {

    /** Auto mit frei setzbarem Fahrzeugstand. */
    static final class Auto implements LauncherKlimaBefehl.Auto {
        BydVehicleData daten;
        String art = "grad";
        String ausgang = "success";
        boolean stufen = false;
        @Override public boolean stufenModell() { return stufen; }
        final List<VehicleCommand> befehle = new ArrayList<>();

        @Override public Integer luft() { return 3; }
        @Override public Double sollC() { return 21.0; }
        @Override public BydVehicleData daten() { return daten; }
        @Override public String tempArt() { return art; }
        @Override public Integer sollRoh() { return 26; }
        @Override public LauncherKlimaBefehl.Ergebnis ausfuehren(VehicleCommand c) {
            befehle.add(c);
            return new LauncherKlimaBefehl.Ergebnis(ausgang, "sdk");
        }
    }

    /** Frischer, vollständiger Sitz-Stand (Fahrer/Beifahrer: Heizung, Lüftung). */
    static BydVehicleData stand(int hf, int hb, int lf, int lb, int lenkrad, int umluft) {
        return new BydVehicleData.Builder()
                .seatHeat(new int[]{hf, hb}).seatCool(new int[]{lf, lb})
                .seatClimateAtMs(System.currentTimeMillis())
                .steeringWheelHeat(lenkrad).acCycleMode(umluft).build();
    }

    LauncherKlimaWegTest.Geraet geraet;
    Auto auto;
    LauncherAufrufer.Quelle alteQuelle;
    LauncherKlimaBefehl.Auto altesAuto;

    @Before
    public void vorher() {
        alteQuelle = LauncherAufrufer.quelle;
        altesAuto = LauncherKlimaBefehl.auto;
        geraet = new LauncherKlimaWegTest.Geraet();
        auto = new Auto();
        LauncherAufrufer.quelle = geraet;
        LauncherKlimaBefehl.auto = auto;
        LauncherAufrufer.vergessen();
        LauncherKlimaBefehl.ratenlimitLeeren();
    }

    @After
    public void nachher() {
        LauncherAufrufer.quelle = alteQuelle;
        LauncherKlimaBefehl.auto = altesAuto;
        LauncherAufrufer.vergessen();
        LauncherKlimaBefehl.ratenlimitLeeren();
    }

    private String senden(String aktion) throws Exception {
        LauncherKlimaBefehl.ratenlimitLeeren();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(LauncherApiHandler.handle("POST", LauncherApiHandler.KLIMA_PFAD,
                "{\"aktion\":\"" + aktion + "\"}", out));
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------- Die Liste ist fest, das Tabu fehlt

    @Test
    public void listeIstFestUndTabuFehlt() {
        assertEquals(14, LauncherKlimaBefehl.KOMFORT_AKTIONEN.size());
        assertEquals(6, LauncherKlimaBefehl.KLIMA_AKTIONEN.size());
        assertEquals(20, LauncherKlimaBefehl.AKTIONEN.size());
        for (String tabu : Arrays.asList("hupe", "horn", "licht", "lights", "flash", "blinker", "hazard", "aufsperren", "unlock",
                "zusperren", "lock", "auto_finden", "find", "alarm", "fenster", "window", "dach", "sunroof", "kofferraum",
                "trunk", "spiegel", "mirror", "sitzspeicher", "seat_memory", "sitz_speicher")) {
            assertFalse(tabu, LauncherKlimaBefehl.AKTIONEN.contains(tabu));
        }
    }

    // ------------------------------------------------------------- Sitze: Zyklus aus dem Ist-Stand

    @Test
    public void sitzheizungSchaltetWeiterUndTraegtDieAnderenZonenMit() throws Exception {
        auto.daten = stand(0, 1, 2, 0, 1, 0);
        assertTrue(senden("sitz_fahrer").contains("\"ok\":true"));
        VehicleCommandRouter.SeatHeatCommand c = (VehicleCommandRouter.SeatHeatCommand) auto.befehle.get(0);
        assertEquals(1, c.position);
        assertEquals(1, c.level);                      // aus -> Stufe 1
        assertEquals(1, c.passengerHeat);              // Beifahrer unverändert
        assertEquals(2, c.driverVent);                 // Lüftung unverändert

        auto.daten = stand(2, 1, 0, 0, 1, 0);
        senden("sitz_fahrer");
        assertEquals(0, ((VehicleCommandRouter.SeatHeatCommand) auto.befehle.get(1)).level);   // Stufe 2 -> aus

        auto.daten = stand(0, 1, 0, 0, 1, 0);
        senden("sitz_beifahrer");
        VehicleCommandRouter.SeatHeatCommand b = (VehicleCommandRouter.SeatHeatCommand) auto.befehle.get(2);
        assertEquals(2, b.position);
        assertEquals(2, b.level);                      // Stufe 1 -> 2
        assertTrue(b.localOnlyWhenAwake());            // bei wachem Auto nie die Cloud
    }

    @Test
    public void sitzlueftungWieDieHeizung() throws Exception {
        auto.daten = stand(1, 0, 0, 2, 1, 0);
        senden("lueftung_fahrer");
        VehicleCommandRouter.SeatVentCommand v = (VehicleCommandRouter.SeatVentCommand) auto.befehle.get(0);
        assertEquals(1, v.position);
        assertEquals(1, v.level);
        assertEquals(1, v.driverHeat);
        assertEquals(2, v.passengerVent);
        senden("lueftung_beifahrer");
        assertEquals(0, ((VehicleCommandRouter.SeatVentCommand) auto.befehle.get(1)).level);   // Stufe 2 -> aus
        assertTrue(v.localOnlyWhenAwake());
    }

    @Test
    public void ohneLesbarenStandNichtsSenden() throws Exception {
        for (String a : Arrays.asList("sitz_fahrer", "sitz_beifahrer", "lueftung_fahrer", "lueftung_beifahrer", "lenkrad", "umluft")) {
            auto.daten = null;
            String r = senden(a);
            assertTrue(a + r, r.startsWith("HTTP/1.1 409"));
            assertTrue(a + r, r.contains("stufe_unbekannt"));
        }
        // veralteter Sitz-Stand (älter als 15 s) zählt als nicht lesbar
        auto.daten = new BydVehicleData.Builder().seatHeat(new int[]{0, 0}).seatCool(new int[]{0, 0})
                .seatClimateAtMs(System.currentTimeMillis() - 60_000L).build();
        assertTrue(senden("sitz_fahrer").contains("stufe_unbekannt"));
        // Stufe außerhalb 0..2
        auto.daten = stand(3, 0, 0, 0, 1, 0);
        assertTrue(senden("sitz_fahrer").contains("stufe_unbekannt"));
        // Lenkrad/Umluft nie gemeldet (UNAVAILABLE)
        auto.daten = stand(0, 0, 0, 0, BydVehicleData.UNAVAILABLE, BydVehicleData.UNAVAILABLE);
        assertTrue(senden("lenkrad").contains("stufe_unbekannt"));
        assertTrue(senden("umluft").contains("stufe_unbekannt"));
        assertTrue(auto.befehle.isEmpty());
    }

    // ------------------------------------------------------------- Lenkrad, Umluft: Gegenzustand

    @Test
    public void lenkradUndUmluftSchaltenDenGegenzustand() throws Exception {
        auto.daten = stand(0, 0, 0, 0, 1, 0);          // Lenkrad aus (1), Frischluft (0)
        senden("lenkrad");
        assertTrue(((VehicleCommandRouter.SteeringWheelHeatCommand) auto.befehle.get(0)).on);
        senden("umluft");
        assertTrue(((VehicleCommandRouter.AcRecirculationCommand) auto.befehle.get(1)).recirculate);

        auto.daten = stand(0, 0, 0, 0, 2, 1);          // Lenkrad an (2), Umluft (1)
        senden("lenkrad");
        assertFalse(((VehicleCommandRouter.SteeringWheelHeatCommand) auto.befehle.get(2)).on);
        senden("umluft");
        assertFalse(((VehicleCommandRouter.AcRecirculationCommand) auto.befehle.get(3)).recirculate);
    }

    // ------------------------------------------------------------- Ohne Lese-Wert: an/aus-Paare

    @Test
    public void paareOhneLeseWert() throws Exception {
        auto.daten = null;                              // der Zustand wird nicht gebraucht
        senden("auto_an"); senden("auto_aus");
        senden("entfrosten_vorn_an"); senden("entfrosten_vorn_aus");
        senden("entfrosten_hinten_an"); senden("entfrosten_hinten_aus");
        senden("nur_geblaese_an"); senden("nur_geblaese_aus");
        assertEquals(8, auto.befehle.size());
        assertTrue(((VehicleCommandRouter.AcAutoModeCommand) auto.befehle.get(0)).on);
        assertFalse(((VehicleCommandRouter.AcAutoModeCommand) auto.befehle.get(1)).on);
        assertTrue(((VehicleCommandRouter.FrontDefrostCommand) auto.befehle.get(2)).on);
        assertFalse(((VehicleCommandRouter.FrontDefrostCommand) auto.befehle.get(3)).on);
        assertTrue(((VehicleCommandRouter.RearDefrostCommand) auto.befehle.get(4)).on);
        assertFalse(((VehicleCommandRouter.RearDefrostCommand) auto.befehle.get(5)).on);
        assertTrue(((VehicleCommandRouter.FanOnlyModeCommand) auto.befehle.get(6)).on);
        assertFalse(((VehicleCommandRouter.FanOnlyModeCommand) auto.befehle.get(7)).on);
    }

    // ------------------------------------------------------------- Wer darf, Ablehnung, Ratenlimit

    @Test
    public void fremderAufruferBekommt403() throws Exception {
        geraet.uid = 2000;                              // shell
        auto.daten = stand(0, 0, 0, 0, 1, 0);
        for (String a : LauncherKlimaBefehl.KOMFORT_AKTIONEN) {
            String r = senden(a);
            assertTrue(a + r, r.startsWith("HTTP/1.1 403"));
        }
        assertTrue(auto.befehle.isEmpty());
    }

    @Test
    public void ablehnungDesRoutersKommtEhrlichZurueck() throws Exception {
        auto.daten = stand(0, 0, 0, 0, 1, 0);
        auto.ausgang = "blocked_driving";
        String r = senden("sitz_fahrer");
        JSONObject o = new JSONObject(r.substring(r.indexOf('{')));
        assertFalse(o.getBoolean("ok"));
        assertEquals("blocked_driving", o.getString("grund"));
        auto.ausgang = "failed";
        o = new JSONObject(senden("entfrosten_vorn_an").replaceAll("(?s)^.*?(\\{)", "{"));
        assertFalse(o.getBoolean("ok"));
        assertEquals("failed", o.getString("grund"));
    }

    @Test
    public void ratenlimitGiltAuchHier() throws Exception {
        auto.daten = stand(0, 0, 0, 0, 1, 0);
        LauncherKlimaBefehl.ratenlimitLeeren();
        long t = 9_000_000L;
        Object[] erste = LauncherKlimaBefehl.ausfuehren("{\"aktion\":\"lenkrad\"}", t);
        assertEquals(200, erste[0]);
        Object[] zweite = LauncherKlimaBefehl.ausfuehren("{\"aktion\":\"lenkrad\"}", t + 10);
        assertEquals(429, zweite[0]);
        assertEquals(1, auto.befehle.size());
    }

    @Test
    public void unbekannteAktionBleibt400() throws Exception {
        for (String a : Arrays.asList("hupe", "flash_lights", "unlock", "find_car", "alarm", "fenster_auf", "sitz_speicher", "sitz_fahrer_2", "")) {
            Object[] r = LauncherKlimaBefehl.ausfuehren("{\"aktion\":\"" + a + "\"}", 1L);
            assertEquals(a, 400, r[0]);
        }
        assertTrue(auto.befehle.isEmpty());
        assertNull(LauncherKlimaBefehl.komfortBefehl("an", null, new String[1]));
    }

    // ------------------------------------------------------------- Temperatur: Stufen, Grad oder gar nicht (nie raten)

    @Test
    public void unbekanntesModellBedientDieTemperaturNicht() throws Exception {
        auto.art = "unbekannt";
        for (String a : Arrays.asList("temp_hoch", "temp_runter")) {
            String r = senden(a);
            assertTrue(a + r, r.startsWith("HTTP/1.1 409"));
            assertTrue(a + r, r.contains("temperatur_unbekannt"));
        }
        assertTrue(auto.befehle.isEmpty());                 // kein stiller Dreh-Befehl
        // Gebläse, an/aus und Komfort gehen weiter
        assertTrue(senden("luft_hoch").contains("\"ok\":true"));
        assertTrue(senden("an").contains("\"ok\":true"));
        assertEquals(2, auto.befehle.size());
    }

    @Test
    public void stufenUndGradNehmenIhrenWeg() throws Exception {
        auto.art = "stufen";
        auto.stufen = true;
        senden("temp_hoch");
        assertTrue(auto.befehle.get(0) instanceof VehicleCommandRouter.ClimateSetTempCommand);   // absolute Stufe
        auto.art = "grad";
        auto.stufen = false;
        senden("temp_hoch");
        assertTrue(auto.befehle.get(1) instanceof VehicleCommandRouter.ClimateStepTempCommand);
    }

    @Test
    public void gradModellNurPerKonfigBestaetigt() throws Exception {
        org.json.JSONObject k = new org.json.JSONObject().put("gradModelle", new org.json.JSONArray().put("Atto3"));
        at.greenchili.surfmate.byd.KlimaStufe.fuerTest(null);
        at.greenchili.surfmate.byd.KlimaStufe.fuerTestGrad(null);
        at.greenchili.surfmate.byd.KlimaStufe.fuerTest(false);
        at.greenchili.surfmate.byd.KlimaStufe.fuerTestGrad(false);
        assertEquals("unbekannt", at.greenchili.surfmate.byd.KlimaStufe.temperaturArt());
        at.greenchili.surfmate.byd.KlimaStufe.fuerTestGrad(true);
        assertEquals("grad", at.greenchili.surfmate.byd.KlimaStufe.temperaturArt());
        at.greenchili.surfmate.byd.KlimaStufe.fuerTest(true);
        assertEquals("stufen", at.greenchili.surfmate.byd.KlimaStufe.temperaturArt());   // Stufen gehen vor Grad
        at.greenchili.surfmate.byd.KlimaStufe.fuerTest(null);
        at.greenchili.surfmate.byd.KlimaStufe.fuerTestGrad(null);
    }

    // ------------------------------------------------------------- Anzeige: nur Lesbares

    @Test
    public void nurLesbaresWirdAngezeigt() {
        assertEquals(-1, LauncherKlimaBefehl.sitzStufe(null, true, 0));
        assertEquals(1, LauncherKlimaBefehl.sitzStufe(stand(1, 0, 0, 0, 1, 0), true, 0));
        assertEquals(0, LauncherKlimaBefehl.sitzStufe(stand(1, 0, 0, 0, 1, 0), true, 1));
        assertEquals(1, LauncherKlimaBefehl.lenkradStand(stand(0, 0, 0, 0, 2, 0)));
        assertEquals(0, LauncherKlimaBefehl.lenkradStand(stand(0, 0, 0, 0, 1, 0)));
        assertEquals(-1, LauncherKlimaBefehl.lenkradStand(stand(0, 0, 0, 0, 5, 0)));
        assertEquals(1, LauncherKlimaBefehl.umluftStand(stand(0, 0, 0, 0, 1, 1)));
        assertEquals(0, LauncherKlimaBefehl.umluftStand(stand(0, 0, 0, 0, 1, 0)));
        assertEquals(-1, LauncherKlimaBefehl.umluftStand(null));
    }
}
