package at.greenchili.surfmate.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.byd.routing.VehicleCommandRouter;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

/**
 * 0.1.38.7 Fernklima ehrlich (04.11., 16:49): Heizen Stufe 3 für 10 min per Cloud gestartet, die Heizung lief, Panoptes
 * zeigte „Klima aus“. Ursache: der ausdrückliche Fern-Start ({@code remote:true}, {@code ClimateRemoteOnCommand}) wurde im Router
 * nie als Sitzung vermerkt. Jetzt gilt: nach einem erfolgreichen Start „läuft laut Befehl“, danach „beendet“, nie „aus“.
 */
public class FernKlimaTest {

    private static final long T0 = 1_800_000_000_000L;

    @After
    public void zurueck() {
        KlimaStufe.fuerTest(null);
        VehicleCommandRouter.getInstance().clearRemoteClimateSession();
    }

    // ------------------------------------------------------------------ reine Sitzung

    @Test
    public void laeuftNachBefehlDannBeendetDannWeg() throws Exception {
        KlimaStufe.fuerTest(true);                         // Surf: Stufen
        FernKlima.Sitzung s = FernKlima.Sitzung.neu(T0, 10, KlimaStufe.zuSdk(12));   // Heizen Stufe 3
        JSONObject laeuft = FernKlima.json(s, T0 + 5 * 60_000L);
        assertTrue(laeuft.getBoolean("aktiv"));
        assertEquals("befehl", laeuft.getString("laut"));
        assertEquals(T0, laeuft.getLong("gestartet"));
        assertEquals(T0 + 10 * 60_000L, laeuft.getLong("bis"));
        assertEquals(12, laeuft.getInt("stufe"));
        assertEquals("heizen", laeuft.getString("art"));
        assertFalse(laeuft.getBoolean("beendet"));
        assertTrue(laeuft.isNull("beendetUm"));

        JSONObject beendet = FernKlima.json(s, T0 + 11 * 60_000L);
        assertFalse(beendet.getBoolean("aktiv"));
        assertTrue(beendet.getBoolean("beendet"));
        assertEquals(T0 + 10 * 60_000L, beendet.getLong("beendetUm"));
        assertEquals(FernKlima.ENDE_ABGELAUFEN, beendet.getString("endeGrund"));

        assertNotNull("eine Stunde sichtbar", FernKlima.json(s, T0 + 10 * 60_000L + FernKlima.BEENDET_SICHTBAR_MS - 1));
        assertNull("danach weg", FernKlima.json(s, T0 + 10 * 60_000L + FernKlima.BEENDET_SICHTBAR_MS + 1));
        assertNull(FernKlima.json(null, T0));
    }

    @Test
    public void ausBeendetSofortUndKuehlenNeutralHeizenUnterschieden() throws Exception {
        KlimaStufe.fuerTest(true);
        FernKlima.Sitzung s = FernKlima.Sitzung.neu(T0, 20, KlimaStufe.zuSdk(6)).beendet(T0 + 3 * 60_000L, FernKlima.ENDE_AUS);
        JSONObject j = FernKlima.json(s, T0 + 4 * 60_000L);
        assertTrue(j.getBoolean("beendet"));
        assertEquals(FernKlima.ENDE_AUS, j.getString("endeGrund"));
        assertEquals(T0 + 3 * 60_000L, j.getLong("beendetUm"));
        assertEquals("kuehlen", FernKlima.json(FernKlima.Sitzung.neu(T0, 10, KlimaStufe.zuSdk(6)), T0).getString("art"));
        assertEquals("neutral", FernKlima.json(FernKlima.Sitzung.neu(T0, 10, KlimaStufe.zuSdk(9)), T0).getString("art"));
        assertEquals("heizen", FernKlima.json(FernKlima.Sitzung.neu(T0, 10, KlimaStufe.zuSdk(10)), T0).getString("art"));
        // schon beendet: ein zweites Beenden ändert nichts
        assertEquals(s.endeMs, s.beendet(T0 + 9 * 60_000L, "x").endeMs);
    }

    @Test
    public void anderesModellNenntKeineStufeUndKeineArt() throws Exception {
        KlimaStufe.fuerTest(false);
        JSONObject j = FernKlima.json(FernKlima.Sitzung.neu(T0, 10, 26), T0);
        assertTrue(j.isNull("stufe"));
        assertTrue(j.isNull("art"));
        assertEquals(26, j.getInt("tempSdk"));
        assertTrue(j.getBoolean("aktiv"));
    }

    // ------------------------------------------------------------------ Router: der eigentliche Fehler

    private static void aktualisiere(VehicleCommandRouter router, VehicleCommandRouter.VehicleCommand cmd,
                                     VehicleCommandRouter.CommandResult r) throws Exception {
        java.lang.reflect.Method update = VehicleCommandRouter.class.getDeclaredMethod(
                "updateRemoteClimateSession", VehicleCommandRouter.VehicleCommand.class, VehicleCommandRouter.CommandResult.class);
        update.setAccessible(true);
        update.invoke(router, cmd, r);
    }

    @Test
    public void ausdruecklicherFernStartWirdAlsSitzungVermerkt() throws Exception {
        KlimaStufe.fuerTest(true);
        VehicleCommandRouter router = VehicleCommandRouter.getInstance();
        router.clearRemoteClimateSession();
        // Misserfolg: nichts vermerkt
        aktualisiere(router, new VehicleCommandRouter.ClimateRemoteOnCommand(KlimaStufe.zuSdk(12), 10),
                VehicleCommandRouter.CommandResult.failed(VehicleCommandRouter.Path.CLOUD, "failed", 0L, null));
        assertFalse(router.isRemoteClimateActive());
        assertNull(router.fernKlimaSitzung());
        // Erfolg über die Cloud: 16:49 Fall
        long vorher = System.currentTimeMillis();
        aktualisiere(router, new VehicleCommandRouter.ClimateRemoteOnCommand(KlimaStufe.zuSdk(12), 10),
                VehicleCommandRouter.CommandResult.success(VehicleCommandRouter.Path.CLOUD, "cloud", 0L));
        assertTrue("remoteClimateActive war der Beleg, der fehlte", router.isRemoteClimateActive());
        FernKlima.Sitzung s = router.fernKlimaSitzung();
        assertNotNull(s);
        assertEquals(10, s.dauerMin);
        assertEquals(KlimaStufe.zuSdk(12), s.tempSdk);
        assertTrue(s.startMs >= vorher && s.aktiv(System.currentTimeMillis()));
        // Fern-Aus: beendet, Grund „aus“, remoteClimateActive weg
        aktualisiere(router, new VehicleCommandRouter.ClimateRemoteOffCommand(),
                VehicleCommandRouter.CommandResult.success(VehicleCommandRouter.Path.CLOUD, "cloud", 0L));
        assertFalse(router.isRemoteClimateActive());
        assertEquals(FernKlima.ENDE_AUS, router.fernKlimaSitzung().endeGrund);
        // lokaler Start ist kein Fernklima
        router.clearRemoteClimateSession();
        aktualisiere(router, new VehicleCommandRouter.ClimateOnCommand(22),
                VehicleCommandRouter.CommandResult.success(VehicleCommandRouter.Path.SDK, "local", 0L));
        assertNull(router.fernKlimaSitzung());
        // Start über „klima_an“ mit Cloud-Weg zählt weiter (alter Weg)
        aktualisiere(router, new VehicleCommandRouter.ClimateOnCommand(22, 15),
                VehicleCommandRouter.CommandResult.success(VehicleCommandRouter.Path.SDK_THEN_CLOUD, "cloud", 0L));
        assertEquals(15, router.fernKlimaSitzung().dauerMin);
    }
}
