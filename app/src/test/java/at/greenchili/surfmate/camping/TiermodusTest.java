package at.greenchili.surfmate.camping;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.byd.BydVehicleData;
import at.greenchili.surfmate.byd.routing.CloudPowerGate;
import at.greenchili.surfmate.byd.routing.VehicleCommandRouter;
import at.greenchili.surfmate.monitor.GearMonitor;
import at.greenchili.surfmate.notifications.NotificationEvent;
import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Tiermodus (03.11.2026): Startbedingungen, Überhitzung, Akku-Grenze, 12 V, Wiederholung bis „Gesehen“, kein Alarm
 * und kein Hupen. Das Stellglied ist der echte {@link RouterHeizweg} mit einem Fake-Router – so ist belegt, welche
 * Fahrzeugbefehle der Tiermodus überhaupt senden kann.
 */
public class TiermodusTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long MIN = 60_000L;

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    final List<VehicleCommandRouter.VehicleCommand> befehle = new ArrayList<>();
    VehicleCommandRouter.CommandResult antwort = VehicleCommandRouter.CommandResult.success(VehicleCommandRouter.Path.SDK, "", 1);
    final List<NotificationEvent> meldungen = new ArrayList<>();
    long jetzt = NOW;
    int powerLevel = 2;
    double soc = 70, kmh = 0, innen = Double.NaN;
    long innenAlter = 1_000;
    boolean gangP = true, v12 = false, camping = false;
    /** Modell mit belegter Innenmessung (KabinenTemperatur.MESSUNG_BELEGT). */
    boolean messung = true;
    Integer klima = 1;
    TiermodusRegel.Einstellung einst = new TiermodusRegel.Einstellung();
    Tiermodus t;

    @Before
    public void auf() throws Exception {
        Tiermodus.testZuruecksetzen();
        t = Tiermodus.fuerTest(tmp.getRoot().getAbsolutePath());
        t.uhr = () -> jetzt;
        t.daten = () -> {
            BydVehicleData.Builder b = new BydVehicleData.Builder().gearMode(gangP ? GearMonitor.GEAR_P : 3)
                    .speedKmh(kmh).socPercent(soc).powerLevel(powerLevel, jetzt - 1_000);
            if (!Double.isNaN(innen)) b.insideTempC(innen, jetzt - innenAlter);
            return b.build();
        };
        t.kmh = () -> kmh;
        t.gangP = () -> gangP;
        t.klimaLive = () -> klima;
        t.v12Low = () -> v12;
        t.campingAktiv = () -> camping;
        t.innenMessung = () -> messung;
        t.einstellung = () -> einst;
        t.melder = meldungen::add;
        t.heizweg = new RouterHeizweg(c -> { befehle.add(c); return antwort; },
                n -> CloudPowerGate.evaluate(n, powerLevel, jetzt - 1_000, true, powerLevel != 0, jetzt),
                () -> Boolean.TRUE, 21);
        assertTrue(t.hinweisBestaetigenIntern(Tiermodus.HINWEIS_VERSION).optBoolean("ok"));
    }

    @After
    public void ab() {
        Tiermodus.testZuruecksetzen();
    }

    private List<NotificationEvent> alarme() {
        List<NotificationEvent> l = new ArrayList<>();
        for (NotificationEvent e : meldungen) if (Tiermodus.KATEGORIE_ALARM.equals(e.category)) l.add(e);
        return l;
    }

    private static Set<String> probleme(NotificationEvent e) throws Exception {
        java.util.Set<String> s = new java.util.LinkedHashSet<>();
        JSONArray a = e.data.getJSONArray("probleme");
        for (int i = 0; i < a.length(); i++) s.add(a.getString(i));
        return s;
    }

    private void starten() {
        JSONObject r = t.einschalten("display");
        assertTrue(r.toString(), r.optBoolean("ok"));
        assertTrue(Tiermodus.aktiv());
    }

    // ------------------------------------------------------------ Startbedingungen

    @Test
    public void startNurInPBeiNullKmhMitAutoAn() {
        gangP = false;
        assertEquals("nicht in P", "nicht_geparkt", t.einschalten("display").optString("fehler"));
        gangP = true;
        kmh = 3;
        assertEquals("nicht_geparkt", t.einschalten("display").optString("fehler"));
        kmh = 1.0;
        assertEquals("1 km/h ist nicht 0", "nicht_geparkt", t.einschalten("display").optString("fehler"));
        kmh = Double.NaN;
        assertEquals("fahrzustand_unbekannt", t.einschalten("display").optString("fehler"));
        kmh = 0;
        powerLevel = 0;
        assertEquals("auto_nicht_an", t.einschalten("display").optString("fehler"));
        powerLevel = 1;
        assertEquals("auto_nicht_an", t.einschalten("display").optString("fehler"));
        powerLevel = 2;
        camping = true;
        assertEquals("camping_laeuft", t.einschalten("display").optString("fehler"));
        camping = false;
        soc = 20;
        assertEquals("akku_grenze", t.einschalten("display").optString("fehler"));
        soc = 70;
        v12 = true;
        assertEquals("v12_niedrig", t.einschalten("display").optString("fehler"));
        v12 = false;
        assertFalse(Tiermodus.aktiv());
        assertEquals("abgelehnt = kein Befehl ans Auto", 0, befehle.size());
        starten();
        VehicleCommandRouter.ClimateOnCommand c = (VehicleCommandRouter.ClimateOnCommand) befehle.get(0);
        assertEquals("Sollwert aus der Einstellung", 21.0, c.tempCelsius, 0);
        assertEquals(TiermodusRegel.WUNSCH_STANDARD, 21);
        assertEquals(Tiermodus.KATEGORIE_HINWEIS, meldungen.get(0).category);
        assertEquals("an", meldungen.get(0).data.optString("code"));
    }

    @Test
    public void ohneHaftungshinweisKeinStart() throws Exception {
        java.io.File f = new java.io.File(tmp.getRoot(), Tiermodus.HINWEIS_DATEI);
        assertTrue(f.delete());
        assertFalse(t.statusJson().getBoolean("hinweisBestaetigt"));
        assertEquals("hinweis_fehlt", t.einschalten("display").optString("fehler"));
        assertEquals("hinweis_fehlt", Tiermodus.ein("handy:Pixel").optString("fehler"));
        assertFalse(Tiermodus.aktiv());
        assertEquals("kein Befehl ans Auto", 0, befehle.size());
        // alte Textversion zählt nicht
        assertEquals("hinweis_version", t.hinweisBestaetigenIntern("2020-01-01").optString("fehler"));
        java.nio.file.Files.write(f.toPath(), "{\"version\":\"2020-01-01\",\"zeit\":1}".getBytes("UTF-8"));
        assertEquals("nach Textänderung erneut", "hinweis_fehlt", t.einschalten("display").optString("fehler"));
        // bestätigen: nur Version und Zeit, keine Personendaten
        assertTrue(t.hinweisBestaetigenIntern(Tiermodus.HINWEIS_VERSION).optBoolean("ok"));
        JSONObject h = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
        assertEquals(2, h.length());
        assertEquals(Tiermodus.HINWEIS_VERSION, h.getString("version"));
        assertEquals(jetzt, h.getLong("zeit"));
        assertTrue(t.statusJson().getBoolean("hinweisBestaetigt"));
        assertFalse(t.statusJson().getString("hinweisText").isEmpty());
        starten();
    }

    @Test
    public void klimaGehtNichtAnDannKeinStart() {
        antwort = VehicleCommandRouter.CommandResult.failed(VehicleCommandRouter.Path.SDK, "", 1, null);
        assertEquals("klima_fehlgeschlagen", t.einschalten("display").optString("fehler"));
        assertFalse(Tiermodus.aktiv());
    }

    @Test
    public void campingStartetNichtNebenDemTiermodus() throws Exception {
        starten();
        Camping.testZuruecksetzen();
        Camping c = Camping.fuerTest(tmp.newFolder("c").getAbsolutePath(), t.daten, null, () -> 1);
        assertEquals("tiermodus_laeuft", c.einschalten("web").optString("fehler"));
        Camping.testZuruecksetzen();
    }

    // ------------------------------------------------------------ Überhitzung und Klima-Ausfall

    @Test
    public void ueberhitzungMeldetSofortUndCritical() throws Exception {
        starten();
        meldungen.clear();
        innen = 31;
        jetzt += 30_000;
        t.takt();
        assertEquals(1, alarme().size());
        NotificationEvent a = alarme().get(0);
        assertEquals(NotificationEvent.Severity.CRITICAL, a.severity);
        assertTrue(probleme(a).contains(TiermodusRegel.ZU_WARM));
        assertEquals("pruefen", t.statusJson().getString("anzeige"));
        assertEquals(31.0, t.statusJson().getDouble("innenC"), 0);
    }

    @Test
    public void frischerInnenwertUeberGrenzeAlarmiertLautUndWiederholt() throws Exception {
        innen = 24;
        starten();
        assertEquals(24.0, t.statusJson().getDouble("innenC"), 0);
        assertEquals("ok", t.statusJson().getString("anzeige"));
        meldungen.clear();
        innen = 30;   // genau die Grenze (Standard 30 °C)
        innenAlter = at.greenchili.surfmate.byd.BydVehicleData.CABIN_TEMP_MAX_AGE_MS;   // gerade noch frisch
        jetzt += 30_000;
        t.takt();
        assertEquals(1, alarme().size());
        assertEquals(NotificationEvent.Severity.CRITICAL, alarme().get(0).severity);
        assertEquals("alarm_zu_warm", TiermodusRegel.alarmTitel(probleme(alarme().get(0))));
        jetzt += 3 * MIN;
        t.takt();
        assertEquals("wiederholt wie Klima aus", 2, alarme().size());
        einst.alarmC = 35;   // einstellbar
        jetzt += 30_000;
        t.takt();
        assertFalse(t.probleme.contains(TiermodusRegel.ZU_WARM));
    }

    @Test
    public void veralteterInnenwertNichtMessbarUndWarnung() throws Exception {
        innen = 24;
        starten();
        meldungen.clear();
        innen = 40;   // heiß, aber veraltet: darf weder als Wert erscheinen noch „zu warm“ auslösen
        innenAlter = at.greenchili.surfmate.byd.BydVehicleData.CABIN_TEMP_MAX_AGE_MS + 1;
        jetzt += 30_000;
        t.takt();
        JSONObject s = t.statusJson();
        assertTrue("veraltet = nicht messbar", s.isNull("innenC"));
        assertEquals(1, alarme().size());
        Set<String> p = probleme(alarme().get(0));
        assertTrue(p.contains(TiermodusRegel.INNEN_VERALTET));
        assertFalse(p.contains(TiermodusRegel.ZU_WARM));
        assertEquals("alarm_innen_veraltet", TiermodusRegel.alarmTitel(p));
        assertEquals("Klima läuft weiter: Fenster zeigt kein „Klima aus“", "ok", s.getString("anzeige"));
        // gleiche Frische-Grenze wie BydEvent/hasFreshCabinTemperature
        assertEquals(at.greenchili.surfmate.byd.BydVehicleData.CABIN_TEMP_MAX_AGE_MS, TiermodusRegel.INNEN_MAX_ALTER_MS);
    }

    @Test
    public void modellOhneMessungNieInnenAlarmAuchNichtVeraltet() throws Exception {
        messung = false;   // z. B. Dolphin Surf: MESSUNG_BELEGT leer
        innen = 40;
        innenAlter = at.greenchili.surfmate.byd.BydVehicleData.CABIN_TEMP_MAX_AGE_MS + 1;
        starten();
        int hinweise = 0;
        for (NotificationEvent e : meldungen) {
            if ("ohne_fuehler".equals(e.data.optString("code"))) {
                hinweise++;
                assertEquals("ruhig, kein Alarm", NotificationEvent.Severity.INFO, e.severity);
                assertEquals(Tiermodus.KATEGORIE_HINWEIS, e.category);
            }
        }
        assertEquals("einmal beim Start", 1, hinweise);
        for (int i = 0; i < 20; i++) { jetzt += MIN; t.takt(); }
        assertEquals("kein Dauer-Alarm „nicht messbar“", 0, alarme().size());
        innenAlter = 1_000;   // selbst ein „frischer“ Wert zählt ohne Beleg nicht (es wäre der Sollwert)
        for (int i = 0; i < 5; i++) { jetzt += MIN; t.takt(); }
        assertEquals(0, alarme().size());
        JSONObject s = t.statusJson();
        assertFalse(s.getBoolean("innenMessung"));
        assertTrue(s.isNull("innenC"));
        assertEquals("ok", s.getString("anzeige"));
        // Schutz bleibt: Klima aus meldet weiterhin laut
        klima = 0;
        jetzt += MIN;
        t.takt();
        assertTrue(probleme(alarme().get(0)).contains(TiermodusRegel.KLIMA_AUS));
        // und ohne Hinweis-Wiederholung
        hinweise = 0;
        for (NotificationEvent e : meldungen) if ("ohne_fuehler".equals(e.data.optString("code"))) hinweise++;
        assertEquals(1, hinweise);
    }

    @Test
    public void modellMitMessungKeinHinweisOhneFuehler() throws Exception {
        innen = 24;
        starten();
        for (NotificationEvent e : meldungen) assertFalse("ohne_fuehler".equals(e.data.optString("code")));
        assertTrue(t.statusJson().getBoolean("innenMessung"));
    }

    @Test
    public void ohneGemesseneInnentemperaturKeinWert() throws Exception {
        messung = false;
        starten();
        assertTrue(t.statusJson().isNull("innenC"));
        assertEquals("ok", t.statusJson().getString("anzeige"));
        boolean hinweis = false;
        for (NotificationEvent e : meldungen) hinweis |= "ohne_fuehler".equals(e.data.optString("code"));
        assertTrue("ehrlicher Hinweis: kein Innenfühler", hinweis);
    }

    @Test
    public void klimaAusMeldetUndSchaltetNeuEin() throws Exception {
        starten();
        befehle.clear();
        meldungen.clear();
        klima = 0;
        jetzt += 3 * MIN;
        t.takt();
        assertEquals(1, alarme().size());
        assertTrue(probleme(alarme().get(0)).contains(TiermodusRegel.KLIMA_AUS));
        assertEquals("alarm_klima_aus", TiermodusRegel.alarmTitel(probleme(alarme().get(0))));
        assertTrue("neu einschalten", befehle.get(0) instanceof VehicleCommandRouter.ClimateOnCommand);
        assertEquals("pruefen", t.statusJson().getString("anzeige"));
    }

    @Test
    public void autoAusIstEinProblem() throws Exception {
        starten();
        meldungen.clear();
        powerLevel = 0;
        jetzt += 30_000;
        t.takt();
        assertTrue(probleme(alarme().get(0)).contains(TiermodusRegel.AUTO_AUS));
    }

    @Test
    public void klimaZustandUnbekanntErstNachZweiTakten() throws Exception {
        starten();
        meldungen.clear();
        klima = null;
        jetzt += 30_000;
        t.takt();
        assertEquals("ein Lesefehler alarmiert nicht", 0, alarme().size());
        jetzt += 30_000;
        t.takt();
        assertTrue(probleme(alarme().get(0)).contains(TiermodusRegel.KLIMA_UNBEKANNT));
    }

    // ------------------------------------------------------------ Wiederholen bis „Gesehen“

    @Test
    public void wiederholtBisGesehenDannNurNeues() throws Exception {
        starten();
        meldungen.clear();
        klima = 0;
        jetzt += 30_000;
        t.takt();
        assertEquals(1, alarme().size());
        jetzt += MIN;
        t.takt();
        assertEquals("vor der Frist keine Wiederholung", 1, alarme().size());
        jetzt += 3 * MIN;
        t.takt();
        assertEquals("nach 3 min wieder", 2, alarme().size());
        assertTrue(Tiermodus.quittieren("handy:Pixel").optBoolean("ok"));
        jetzt += 10 * MIN;
        t.takt();
        assertEquals("gesehen: Ruhe", 2, alarme().size());
        innen = 33;
        jetzt += 30_000;
        t.takt();
        assertEquals("neues Problem: sofort", 3, alarme().size());
    }

    @Test
    public void wiederOkMeldetSich() throws Exception {
        starten();
        klima = 0;
        jetzt += 30_000;
        t.takt();
        meldungen.clear();
        klima = 1;
        jetzt += 30_000;
        t.takt();
        assertEquals(0, alarme().size());
        assertEquals("wieder_ok", meldungen.get(0).data.optString("code"));
        assertEquals("ok", t.statusJson().getString("anzeige"));
    }

    // ------------------------------------------------------------ Schutz: Akku und 12 V

    @Test
    public void akkuGrenzeSchaltetKlimaAusUndMeldetLaut() throws Exception {
        starten();
        befehle.clear();
        meldungen.clear();
        soc = 19;
        jetzt += 30_000;
        t.takt();
        assertTrue(befehle.get(0) instanceof VehicleCommandRouter.ClimateOffCommand);
        NotificationEvent a = alarme().get(0);
        assertEquals(NotificationEvent.Severity.CRITICAL, a.severity);
        assertTrue(probleme(a).contains(TiermodusRegel.AKKU));
        assertEquals("„Klima AUS – Tier prüfen!“", "alarm_klima_aus", TiermodusRegel.alarmTitel(probleme(a)));
        assertTrue("bleibt an, damit es laut bleibt", Tiermodus.aktiv());
        befehle.clear();
        jetzt += 5 * MIN;
        t.takt();
        assertEquals("kein Wieder-Einschalten unter der Grenze", 0, befehle.size());
        assertEquals("Wiederholung", 2, alarme().size());
    }

    @Test
    public void akkuGrenzeNieUnterFuenfzehnProzent() throws Exception {
        TiermodusRegel.Einstellung e = TiermodusRegel.Einstellung.aus(new JSONObject().put("socGrenze", 5));
        assertEquals(TiermodusRegel.SOC_GRENZE_MIN, e.socGrenze);
        assertEquals(15, TiermodusRegel.SOC_GRENZE_MIN);
        assertEquals(20, new TiermodusRegel.Einstellung().socGrenze);
        assertEquals(30, new TiermodusRegel.Einstellung().alarmC);
        assertEquals(1, TiermodusRegel.Einstellung.aus(new JSONObject().put("wiederholenMinuten", 0)).wiederholenMinuten);
    }

    @Test
    public void zwoelfVoltLowSchaltetAusUndIstNichtAbschaltbar() throws Exception {
        starten();
        befehle.clear();
        meldungen.clear();
        v12 = true;
        jetzt += 30_000;
        t.takt();
        assertTrue(befehle.get(0) instanceof VehicleCommandRouter.ClimateOffCommand);
        assertTrue(probleme(alarme().get(0)).contains(TiermodusRegel.V12));
        // Es gibt keinen Schalter, der den 12-V-Schutz im Tiermodus abschaltet
        TiermodusRegel.Einstellung e = TiermodusRegel.Einstellung.aus(new JSONObject().put("v12Warnung", 0).put("v12", false));
        TiermodusRegel.Lage l = new TiermodusRegel.Lage();
        l.v12Low = true;
        assertTrue(TiermodusRegel.schutz(TiermodusRegel.probleme(e, l, 0, false)));
    }

    // ------------------------------------------------------------ Ende

    @Test
    public void losfahrenBeendetOhneKlimaAus() throws Exception {
        starten();
        befehle.clear();
        meldungen.clear();
        gangP = false;
        kmh = 12;
        jetzt += 30_000;
        t.takt();
        assertFalse(Tiermodus.aktiv());
        assertEquals("aus", meldungen.get(0).data.optString("code"));
        assertEquals("Ende schaltet das Klima nicht aus", 0, befehle.size());
    }

    @Test
    public void ausPerTippOhneKlimaAus() {
        starten();
        befehle.clear();
        assertTrue(Tiermodus.aus("display").optBoolean("ok"));
        assertFalse(Tiermodus.aktiv());
        assertEquals(0, befehle.size());
    }

    @Test
    public void ueberstehtNeustart() throws Exception {
        starten();
        Tiermodus.testZuruecksetzen();
        Tiermodus neu = Tiermodus.fuerTest(tmp.getRoot().getAbsolutePath());
        assertTrue("Bestätigung bleibt gespeichert", neu.hinweisBestaetigt());
        assertTrue("Tier ist noch im Auto: nach Neustart weiter an", Tiermodus.aktiv());
        assertTrue(neu.statusJson().getBoolean("aktiv"));
    }

    // ------------------------------------------------------------ Kein Alarm, kein Hupen (REGEL A+)

    @Test
    public void sendetNurKlimaBefehleNieHupeLichtAlarm() throws Exception {
        starten();
        klima = 0;
        for (int i = 0; i < 4; i++) { jetzt += 3 * MIN; t.takt(); }
        powerLevel = 0;
        einst.cloud = true;
        for (int i = 0; i < 4; i++) { jetzt += 50 * MIN; t.takt(); }
        soc = 10;
        jetzt += MIN;
        t.takt();
        assertFalse(befehle.isEmpty());
        for (VehicleCommandRouter.VehicleCommand c : befehle) {
            assertTrue(c.name(), c instanceof VehicleCommandRouter.ClimateOnCommand
                    || c instanceof VehicleCommandRouter.ClimateOffCommand
                    || c instanceof VehicleCommandRouter.ClimateSetFanCommand
                    || c instanceof VehicleCommandRouter.ClimateRemoteOnCommand);
        }
    }

    @Test
    public void quelltextOhneHupeLichtAlarm() throws Exception {
        for (String f : new String[]{"Tiermodus.java", "TiermodusRegel.java"}) {
            String s = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/camping/" + f));
            for (String verboten : new String[]{"FlashLights", "FindCar", "LightsCommand", "Horn", "Whistle",
                    "FLASHLIGHT", "Siren", "ScreenDeterrent.getInstance", "Lautsprecher"}) {
                assertFalse(f + ": " + verboten, s.contains(verboten));
            }
        }
    }

    @Test
    public void waechterIgnoriertInnenraumUndSchrecktNichtAb() throws Exception {
        String shock = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/surveillance/ShockMonitor.java"));
        assertTrue("Erschütterung (Tier bewegt das Auto) ruht", shock.contains("!at.greenchili.surfmate.camping.Tiermodus.aktiv()"));
        String screen = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/surveillance/ScreenDeterrent.java"));
        assertTrue("keine Abschreckung über der Tier-Karte",
                screen.contains("if (at.greenchili.surfmate.camping.Tiermodus.aktiv()) return;"));
        String cloud = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/byd/cloud/BydCloudDeterrent.java"));
        assertTrue("nie Licht/Hupe", cloud.contains("if (at.greenchili.surfmate.camping.Tiermodus.aktiv()) return;"));
        // Der Wächter wird NICHT abgeschaltet (anders als Camping „bewohnt“)
        String daemon = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/daemon/CameraDaemon.java"));
        assertFalse(daemon.contains("Tiermodus.blocksArming"));
        // Bildquelle des Wächters sind nur die vier Außenkameras (Panorama-Quadranten), kein Innenraum
        String pipe = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/surveillance/GpuSurveillancePipeline.java"));
        assertFalse(pipe.contains("CameraRole.CABIN"));
    }

    @Test
    public void statusOhneStartgrundWennAus() throws Exception {
        powerLevel = 0;
        JSONObject s = t.statusJson();
        assertFalse(s.getBoolean("aktiv"));
        assertEquals("aus", s.getString("anzeige"));
        assertEquals("auto_nicht_an", s.getString("startFehler"));
        powerLevel = 2;
        assertTrue(t.statusJson().isNull("startFehler"));
        assertNull(TiermodusRegel.startFehler(einst, t.lage(), false));
    }
}
