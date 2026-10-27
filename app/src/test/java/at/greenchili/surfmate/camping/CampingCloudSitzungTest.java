package at.greenchili.surfmate.camping;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.byd.BydVehicleData;
import at.greenchili.surfmate.byd.routing.CloudPowerGate;
import at.greenchili.surfmate.byd.routing.VehicleCommandRouter;
import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 0.1.18 Camping 2b echt (27.10.2026): Cloud-Heizen nur mit Bestätigung, höchstens zwei
 * Fehlschläge, dann Pause und Meldung; jede Cloud-Entscheidung im Protokoll; nie CLOSEAIR.
 */
public class CampingCloudSitzungTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long MIN = 60_000L;

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private final CampingHeizwegTest.FakeRouter router = new CampingHeizwegTest.FakeRouter();
    private final CloudPowerGate.Decision gate = CloudPowerGate.evaluate("climate-remote-on", 0, NOW, true, false, NOW);
    private final List<JSONObject> protokoll = new ArrayList<>();

    private RouterHeizweg weg() {
        return new RouterHeizweg(router::apply, n -> gate, () -> Boolean.TRUE, 31, protokoll::add, null);
    }

    private static CampingRegler.Lage aus(long jetzt) {
        CampingRegler.Lage l = new CampingRegler.Lage();
        l.jetztMs = jetzt;
        l.powerLevel = 0;
        l.powerLevelAlterMs = 5_000;
        l.fahrt = CampingRegler.Fahrt.STEHT;
        return l;
    }

    @Test
    public void erfolgNurMitCloudBestaetigung() {
        RouterHeizweg w = weg();
        router.antwort = VehicleCommandRouter.CommandResult.success(VehicleCommandRouter.Path.CLOUD, "", 1);
        assertEquals(Heizweg.Ergebnis.Art.CLOUD, w.ein(30, 2, 15, aus(NOW), true).art);
        JSONObject p = protokoll.get(0);
        assertTrue(p.optBoolean("bestaetigt"));
        assertEquals("erlaubt", p.optString("gate"));
        assertEquals(15, p.optInt("dauerMin"));
        assertEquals(0, p.optInt("powerLevel"));
        // ein „Erfolg“ ohne Cloud-Pfad ist keine Bestätigung
        router.antwort = VehicleCommandRouter.CommandResult.success(VehicleCommandRouter.Path.SDK, "", 1);
        Heizweg.Ergebnis e = w.ein(30, 2, 15, aus(NOW + 50 * MIN), true);
        assertEquals(Heizweg.Ergebnis.Art.FEHLER, e.art);
        assertFalse(protokoll.get(1).optBoolean("bestaetigt"));
    }

    @Test
    public void zweiFehlschlaegeDannPauseBisZumNaechstenCamping() {
        RouterHeizweg w = weg();
        router.antwort = VehicleCommandRouter.CommandResult.failed(VehicleCommandRouter.Path.CLOUD, "", 1, null);
        assertEquals(Heizweg.Ergebnis.Art.FEHLER, w.ein(30, 2, 15, aus(NOW), true).art);
        assertFalse(w.cloudPausiert());
        assertEquals(Heizweg.Ergebnis.Art.FEHLER, w.ein(30, 2, 15, aus(NOW + 46 * MIN), true).art);
        assertTrue(w.cloudPausiert());
        int n = router.befehle.size();
        Heizweg.Ergebnis e = w.ein(30, 2, 15, aus(NOW + 120 * MIN), true);
        assertEquals("cloud_pausiert", e.grund);
        assertEquals("kein dritter Versuch", n, router.befehle.size());
        assertTrue(w.stand().optBoolean("cloudPausiert"));
        w.zuruecksetzen();
        assertFalse(w.cloudPausiert());
    }

    @Test
    public void erfolgSetztFehlversucheZurueck() {
        RouterHeizweg w = weg();
        router.antwort = VehicleCommandRouter.CommandResult.failed(VehicleCommandRouter.Path.CLOUD, "", 1, null);
        w.ein(30, 2, 15, aus(NOW), true);
        router.antwort = VehicleCommandRouter.CommandResult.success(VehicleCommandRouter.Path.CLOUD, "", 1);
        w.ein(30, 2, 15, aus(NOW + 46 * MIN), true);
        router.antwort = VehicleCommandRouter.CommandResult.failed(VehicleCommandRouter.Path.CLOUD, "", 1, null);
        w.ein(30, 2, 15, aus(NOW + 92 * MIN), true);
        assertFalse("nur Fehlschläge in Folge zählen", w.cloudPausiert());
    }

    @Test
    public void nieCloseairUndUebersprungenesWirdProtokolliert() {
        RouterHeizweg w = weg();
        router.antwort = VehicleCommandRouter.CommandResult.success(VehicleCommandRouter.Path.CLOUD, "", 1);
        w.ein(30, 2, 15, aus(NOW), true);
        w.aus(aus(NOW + 10 * MIN));
        assertFalse(router.hat(VehicleCommandRouter.ClimateRemoteOffCommand.class));
        Heizweg.Ergebnis e = w.ein(30, 2, 15, aus(NOW + 20 * MIN), true);
        assertEquals("weck_abstand", e.grund);
        assertTrue(protokoll.get(protokoll.size() - 1).optString("ergebnis").contains("weck_abstand"));
        // ohne Cloud-Wahl kein Protokoll
        int n = protokoll.size();
        w.ein(30, 2, 15, aus(NOW + 100 * MIN), false);
        assertEquals(n, protokoll.size());
        assertEquals(RouterHeizweg.STAND_PROTOKOLL >= 2, w.stand().optJSONArray("protokoll").length() >= 2);
    }

    // ------------------------------------------------------------ 0.1.19 Messung nach der Sitzung

    private BydVehicleData daten;

    private RouterHeizweg wegMitDaten() {
        router.antwort = VehicleCommandRouter.CommandResult.success(VehicleCommandRouter.Path.CLOUD, "", 1);
        return new RouterHeizweg(router::apply, n -> gate, () -> Boolean.TRUE, 31, protokoll::add, () -> daten);
    }

    private JSONObject letzteMessung() {
        JSONObject m = null;
        for (JSONObject o : protokoll) if ("messung".equals(o.optString("art"))) m = o;
        return m;
    }

    private int messungen() {
        int n = 0;
        for (JSONObject o : protokoll) if ("messung".equals(o.optString("art"))) n++;
        return n;
    }

    @Test
    public void messungNachZweiMinutenNurLesend() throws Exception {
        RouterHeizweg w = wegMitDaten();
        w.ein(30, 2, 15, aus(NOW), true);
        int befehle = router.befehle.size();
        daten = new BydVehicleData.Builder().klimaSollwertC(33, NOW + 90_000).acSetpointDriver(33).acFanLevel(3)
                .acStartState(1).powerLevel(1, NOW + 100_000).build();
        w.nachTakt(NOW + MIN);
        assertEquals("vor 2 min nichts", 0, messungen());
        w.nachTakt(NOW + 2 * MIN);
        JSONObject m = letzteMessung();
        assertTrue(m.getBoolean("lesbar"));
        assertEquals(33.0, m.getDouble("sollwertC"), 0.01);
        assertEquals(3, m.getInt("geblaese"));
        assertEquals(1, m.getInt("klimaAn"));
        assertEquals(1, m.getInt("powerLevel"));
        assertEquals(31, m.getInt("cloudTemperatur"));
        assertEquals("nur lesen: kein Befehl", befehle, router.befehle.size());
        w.nachTakt(NOW + 3 * MIN);
        assertEquals("nur einmal", 1, messungen());
    }

    @Test
    public void alteDatenSindNichtLesbar() throws Exception {
        RouterHeizweg w = wegMitDaten();
        daten = new BydVehicleData.Builder().klimaSollwertC(25, NOW - 10 * MIN).acFanLevel(0)
                .powerLevel(0, NOW - 5 * MIN).build();
        w.ein(30, 2, 15, aus(NOW), true);
        w.nachTakt(NOW + 2 * MIN);
        JSONObject m = letzteMessung();
        assertFalse(m.getBoolean("lesbar"));
        assertFalse("ohne frische Daten keine Werte", m.has("sollwertC") || m.has("geblaese"));
    }

    @Test
    public void ohneBestaetigungKeineMessungUndVerpasstNachAblauf() throws Exception {
        RouterHeizweg w = wegMitDaten();
        router.antwort = VehicleCommandRouter.CommandResult.failed(VehicleCommandRouter.Path.CLOUD, "", 1, null);
        w.ein(30, 2, 15, aus(NOW), true);
        w.nachTakt(NOW + 2 * MIN);
        assertEquals(0, messungen());
        // bestätigt, aber der Takt kommt erst nach Ablauf der 15-min-Sitzung
        router.antwort = VehicleCommandRouter.CommandResult.success(VehicleCommandRouter.Path.CLOUD, "", 1);
        w.ein(30, 2, 15, aus(NOW + 50 * MIN), true);
        w.nachTakt(NOW + 70 * MIN);
        assertTrue(letzteMessung().getBoolean("verpasst"));
        // zuruecksetzen verwirft eine fällige Messung
        w.ein(30, 2, 15, aus(NOW + 120 * MIN), true);
        w.zuruecksetzen();
        int n = messungen();
        w.nachTakt(NOW + 123 * MIN);
        assertEquals(n, messungen());
    }

    @Test
    public void protokolldateiWirdAngehaengtUndBegrenzt() throws Exception {
        Camping c = new Camping(tmp.getRoot().getAbsolutePath());
        File f = new File(tmp.getRoot(), Camping.CLOUD_PROTOKOLL);
        c.cloudProtokoll(new JSONObject().put("art", "ein").put("n", 1));
        c.cloudProtokoll(new JSONObject().put("art", "ein").put("n", 2));
        List<String> z = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        assertEquals(2, z.size());
        assertEquals(2, new JSONObject(z.get(1)).getInt("n"));
        StringBuilder gross = new StringBuilder();
        while (gross.length() < Camping.CLOUD_PROTOKOLL_MAX + 1000) gross.append("{\"art\":\"alt\"}\n");
        Files.write(f.toPath(), gross.toString().getBytes(StandardCharsets.UTF_8));
        c.cloudProtokoll(new JSONObject().put("art", "neu"));
        assertTrue(f.length() < Camping.CLOUD_PROTOKOLL_MAX);
        z = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        assertEquals("neu", new JSONObject(z.get(z.size() - 1)).getString("art"));
        for (String s : z) new JSONObject(s);   // nur ganze Zeilen
    }

    @Test
    public void pauseWirdEinmalGemeldetUndTexteDa() throws Exception {
        String q = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/camping/Camping.java"));
        assertTrue(q.contains("einmalMelden(\"cloud_pausiert\", l);"));
        assertTrue(q.contains("new RouterHeizweg(tabelle.cloudTemperatur, this::cloudProtokoll, () -> daten.get())"));
        for (String l : new String[]{"de", "en", "fr", "it", "nl"}) {
            JSONObject d = new JSONObject(Quelltext.lies(Paths.get("src/main/assets/server-i18n/" + l + ".json")));
            assertTrue(l, d.getJSONObject("notifications").getString("camping_cloud_pausiert").length() > 20);
        }
        assertEquals("Standard bleibt probelauf", Camping.WEG_PROBE, at.greenchili.surfmate.einstellungen.Katalog.finde(
                at.greenchili.surfmate.einstellungen.Katalog.felder(new at.greenchili.surfmate.einstellungen.Katalog.Kontext()),
                "camping.heizweg").standard);
    }
}
