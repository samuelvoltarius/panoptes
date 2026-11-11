package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.appupdate.LauncherPaket;
import at.greenchili.surfmate.byd.routing.VehicleCommandRouter;
import at.greenchili.surfmate.byd.routing.VehicleCommandRouter.VehicleCommand;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 0.1.37 Stufe 4 („Klimasteuerung muss gehen“): {@code POST /api/launcher/v1/klima} ist der einzige
 * Fahrzeugweg des Launchers. Nur die Launcher-APK mit Panoptes-Signatur, nur sechs Klimaaktionen über die
 * Router-Klimabefehle, Ratenlimit, alles im Befehls-Log; {@code vehicle/*} bleibt 404 ({@link LauncherApiSperreTest}).
 */
public class LauncherKlimaWegTest {

    /** Gerät: Anfrage direkt am Auto von UID 10123, Launcher mit UID 10123 und gleicher Signatur. */
    static final class Geraet implements LauncherAufrufer.Quelle {
        int port = 40000, uid = 10123, launcherUid = 10123, launcherVc = 3;
        String eigen = "a".repeat(64), fremd = "a".repeat(64);
        long jetzt = 1_000_000L;
        int paketAbfragen;

        @Override public int quellport() { return port; }
        @Override public int uid(int quellport, int serverport) { return uid; }
        @Override public LauncherPaket.Stand launcher() {
            paketAbfragen++;
            return launcherStand(launcherVc, launcherUid);
        }
        @Override public String launcherZertifikat(LauncherPaket.Stand s) { return fremd; }
        @Override public String eigenesZertifikat() { return eigen; }
        @Override public long jetzt() { return jetzt; }
    }

    static LauncherPaket.Stand launcherStand(int vc, int uid) {
        try {
            java.lang.reflect.Method m = LauncherPaket.class.getDeclaredMethod("ausListe", String.class);
            m.setAccessible(true);
            return (LauncherPaket.Stand) m.invoke(null, vc > 0
                    ? "package:" + LauncherPaket.PAKET + " versionCode:" + vc + " uid:" + uid : "");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** Auto: merkt sich jeden Router-Befehl, Gebläse und Sollwert frei setzbar. */
    static final class Auto implements LauncherKlimaBefehl.Auto {
        Integer luft = 3;
        Double soll = 21.0;
        Integer roh = 26;          // Surf: Stufe 10 = Heizen 1
        boolean stufen = false;
        String ausgang = "success";
        final List<VehicleCommand> befehle = new ArrayList<>();

        @Override public Integer luft() { return luft; }
        @Override public Double sollC() { return soll; }
        @Override public Integer sollRoh() { return roh; }
        @Override public boolean stufenModell() { return stufen; }
        @Override public LauncherKlimaBefehl.Ergebnis ausfuehren(VehicleCommand c) {
            befehle.add(c);
            return new LauncherKlimaBefehl.Ergebnis(ausgang, "sdk");
        }
    }

    Geraet geraet;
    Auto auto;
    LauncherAufrufer.Quelle alteQuelle;
    LauncherKlimaBefehl.Auto altesAuto;
    long zeit = 5_000_000L;

    @Before
    public void vorher() {
        alteQuelle = LauncherAufrufer.quelle;
        altesAuto = LauncherKlimaBefehl.auto;
        geraet = new Geraet();
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

    /** Eine Anfrage; das Ratenlimit wird vorher geleert (eigener Test {@link #ratenlimit}). */
    private String klima(String aktion) throws Exception {
        LauncherKlimaBefehl.ratenlimitLeeren();
        return klimaRoh(aktion);
    }

    private String klimaRoh(String aktion) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(LauncherApiHandler.handle("POST", LauncherApiHandler.KLIMA_PFAD,
                "{\"aktion\":\"" + aktion + "\"}", out));
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------- Wer darf

    @Test
    public void nurLauncherApkMitPanoptesSignatur() throws Exception {
        assertNull(LauncherAufrufer.pruefe());
        LauncherAufrufer.vergessen();
        geraet.port = -1;                                   // Tunnel, tailscaled, Kopplung, Handy-Web, fremder Host
        assertEquals(LauncherAufrufer.NICHT_LOKAL, LauncherAufrufer.pruefe());
        geraet.port = 40000;
        geraet.uid = 2000;                                  // shell (adb, Skripte), keine App
        assertEquals(LauncherAufrufer.NICHT_LAUNCHER, LauncherAufrufer.pruefe());
        geraet.uid = 10124;                                 // andere App
        assertEquals(LauncherAufrufer.NICHT_LAUNCHER, LauncherAufrufer.pruefe());
        geraet.uid = 10123;
        geraet.launcherVc = -1;                             // Launcher nicht installiert
        assertEquals(LauncherAufrufer.NICHT_LAUNCHER, LauncherAufrufer.pruefe());
        geraet.launcherVc = 3;
        geraet.fremd = "b".repeat(64);                      // gleiches Paket, anderer Schlüssel
        assertEquals(LauncherAufrufer.SIGNATUR, LauncherAufrufer.pruefe());
        geraet.fremd = null;
        assertEquals(LauncherAufrufer.SIGNATUR, LauncherAufrufer.pruefe());
        geraet.fremd = geraet.eigen;
        geraet.eigen = null;                                // eigenes Zertifikat nicht lesbar: zu
        assertEquals(LauncherAufrufer.SIGNATUR, LauncherAufrufer.pruefe());
        geraet.eigen = geraet.fremd;
        assertNull(LauncherAufrufer.pruefe());
    }

    @Test
    public void ergebnisWirdKurzGemerkt() {
        assertNull(LauncherAufrufer.pruefe());
        assertNull(LauncherAufrufer.pruefe());
        assertEquals(1, geraet.paketAbfragen);
        geraet.jetzt += LauncherAufrufer.MERKEN_MS;
        assertNull(LauncherAufrufer.pruefe());
        assertEquals(2, geraet.paketAbfragen);
        geraet.uid = 10200;                                 // andere UID: neu prüfen
        assertEquals(LauncherAufrufer.NICHT_LAUNCHER, LauncherAufrufer.pruefe());
    }

    @Test
    public void fremderAufruferBekommt403UndNichtsAmAuto() throws Exception {
        geraet.uid = 2000;
        String r = klima("temp_hoch");
        assertTrue(r, r.startsWith("HTTP/1.1 403"));
        assertTrue(r.contains("nicht_launcher"));
        assertTrue(auto.befehle.isEmpty());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LauncherApiHandler.handle("POST", LauncherApiHandler.UPDATE_PFAD + "/installieren",
                "{\"paket\":\"launcher\",\"versionCode\":3}", out);
        assertTrue(new String(out.toByteArray(), StandardCharsets.UTF_8).startsWith("HTTP/1.1 403"));
    }

    @Test
    public void uidAusProcNetTcp() {
        String tabelle = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n"
                + "   0: 0100007F:9C40 0100007F:1FF4 01 00000000:00000000 00:00000000 00000000 10123        0 4711 1\n"
                + "   1: 0100007F:1FF4 0100007F:9C40 01 00000000:00000000 00:00000000 00000000  2000        0 4712 1\n";
        assertEquals(10123, LoopbackPeer.findClientUid(tabelle, 40000, 8180));
        assertEquals(-1, LoopbackPeer.findClientUid(tabelle, 40001, 8180));
        assertEquals(-1, LoopbackPeer.findClientUid("", 40000, 8180));
    }

    // ------------------------------------------------------------- Was geht

    @Test
    public void sechsAktionenUeberRouterKlimabefehle() throws Exception {
        assertTrue(klima("temp_hoch").contains("\"ok\":true"));
        zeit();
        klima("temp_runter");
        klima("luft_hoch");
        klima("luft_runter");
        klima("an");
        klima("aus");
        assertEquals(6, auto.befehle.size());
        VehicleCommandRouter.ClimateStepTempCommand hoch = (VehicleCommandRouter.ClimateStepTempCommand) auto.befehle.get(0);
        assertEquals(1, hoch.delta);
        assertEquals(0, hoch.zone);
        assertEquals(-1, ((VehicleCommandRouter.ClimateStepTempCommand) auto.befehle.get(1)).delta);
        assertEquals(4, ((VehicleCommandRouter.ClimateSetFanCommand) auto.befehle.get(2)).level);
        assertEquals(2, ((VehicleCommandRouter.ClimateSetFanCommand) auto.befehle.get(3)).level);
        VehicleCommandRouter.ClimateOnCommand an = (VehicleCommandRouter.ClimateOnCommand) auto.befehle.get(4);
        assertEquals(21.0, an.tempCelsius, 1e-9);           // aktueller Sollwert, nie eine Zahl von außen
        assertTrue(auto.befehle.get(5) instanceof VehicleCommandRouter.ClimateOffCommand);
        auto.soll = null;
        LauncherKlimaBefehl.ratenlimitLeeren();
        klima("an");
        assertEquals(LauncherKlimaBefehl.SOLL_STANDARD_C,
                ((VehicleCommandRouter.ClimateOnCommand) auto.befehle.get(6)).tempCelsius, 1e-9);
    }

    private void zeit() {
        LauncherKlimaBefehl.ratenlimitLeeren();
    }

    @Test
    public void allesAndereWirdAbgelehnt() throws Exception {
        for (String a : new String[]{"flash", "find", "lock", "unlock", "trunk", "window", "hupe", "power_on",
                "set_temp", "defrost_front_on", "", "AN"}) {
            String r = klima(a);
            assertTrue(a + " → " + r, r.startsWith("HTTP/1.1 400"));
        }
        LauncherKlimaBefehl.ratenlimitLeeren();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LauncherApiHandler.handle("POST", LauncherApiHandler.KLIMA_PFAD, "{\"aktion\":\"luft_hoch\",\"stufe\":7}", out);
        assertEquals("Zahlen von außen zählen nicht", 4,
                ((VehicleCommandRouter.ClimateSetFanCommand) auto.befehle.get(0)).level);
        assertEquals(1, auto.befehle.size());
        // GET geht nicht
        out = new ByteArrayOutputStream();
        LauncherApiHandler.handle("GET", LauncherApiHandler.KLIMA_PFAD, null, out);
        assertTrue(new String(out.toByteArray(), StandardCharsets.UTF_8).startsWith("HTTP/1.1 404"));
    }

    @Test
    public void geblaeseGrenzenUndUnbekannt() throws Exception {
        auto.luft = 7;
        String r = klima("luft_hoch");
        assertTrue(r, r.startsWith("HTTP/1.1 409") && r.contains("grenze"));
        zeit();
        auto.luft = 1;
        assertTrue(klima("luft_runter").contains("grenze"));
        zeit();
        auto.luft = null;
        assertTrue(klima("luft_hoch").contains("stufe_unbekannt"));
        zeit();
        auto.luft = 0;
        assertTrue(klima("luft_runter").contains("stufe_unbekannt"));
        assertTrue(auto.befehle.isEmpty());
    }

    @Test
    public void routerAblehnungKommtZurueck() throws Exception {
        auto.ausgang = "blocked_driving";
        String r = klima("temp_hoch");
        assertTrue(r, r.contains("\"ok\":false") && r.contains("blocked_driving"));
    }

    /** 0.1.38.5, Befund am Auto: der Surf stellt die Temperatur in Stufen; der Dreh-Befehl wirkt dort nicht. */
    @Test
    public void surfTemperaturInStufenWiePanoptes() throws Exception {
        auto.stufen = true;
        auto.roh = 26;                                       // Stufe 10
        assertTrue(klima("temp_hoch").contains("\"ok\":true"));
        klima("temp_runter");
        assertEquals(2, auto.befehle.size());
        for (VehicleCommand c : auto.befehle) {
            assertTrue("kein Dreh-/Grad-Schritt am Surf", c instanceof VehicleCommandRouter.ClimateSetTempCommand);
            assertEquals("Zone 1 wie vehicle-control set_temp", 1, ((VehicleCommandRouter.ClimateSetTempCommand) c).zone);
        }
        assertEquals(27.0, ((VehicleCommandRouter.ClimateSetTempCommand) auto.befehle.get(0)).tempCelsius, 1e-9);
        assertEquals(25.0, ((VehicleCommandRouter.ClimateSetTempCommand) auto.befehle.get(1)).tempCelsius, 1e-9);
        auto.roh = 33;                                       // Stufe 17 = Heizen 8
        assertTrue(klima("temp_hoch").contains("grenze"));
        auto.roh = 17;                                       // Stufe 1 = Kühlen 8
        assertTrue(klima("temp_runter").contains("grenze"));
        auto.roh = null;
        assertTrue(klima("temp_hoch").contains("stufe_unbekannt"));
        auto.roh = 12;                                       // außerhalb der Skala
        assertTrue(klima("temp_hoch").contains("stufe_unbekannt"));
        assertEquals(2, auto.befehle.size());
        // andere Modelle unverändert: relativer Schritt
        auto.stufen = false; auto.roh = 22;
        klima("temp_hoch");
        assertTrue(auto.befehle.get(2) instanceof VehicleCommandRouter.ClimateStepTempCommand);
    }

    @Test
    public void ratenlimit() throws Exception {
        long t = 1_000_000L;
        assertTrue(LauncherKlimaBefehl.ratenlimit(t));
        assertFalse("zu schnell", LauncherKlimaBefehl.ratenlimit(t + LauncherKlimaBefehl.MIN_ABSTAND_MS - 1));
        int erlaubt = 1;
        for (int i = 1; i < 40; i++) if (LauncherKlimaBefehl.ratenlimit(t + i * LauncherKlimaBefehl.MIN_ABSTAND_MS)) erlaubt++;
        assertEquals(LauncherKlimaBefehl.MAX_IM_FENSTER, erlaubt);
        assertTrue("Fenster vorbei", LauncherKlimaBefehl.ratenlimit(t + LauncherKlimaBefehl.FENSTER_MS + 20_000));
        klima("temp_hoch");
        String r = klimaRoh("temp_hoch");
        assertTrue(r, r.startsWith("HTTP/1.1 429"));
    }

    // ------------------------------------------------------------- Quelltext-Wächter

    @Test
    public void nurKlimabefehleImQuelltext() throws Exception {
        String src = lies("app/src/main/java/at/greenchili/surfmate/server/LauncherKlimaBefehl.java");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("VehicleCommandRouter\\.(\\w+Command)\\b").matcher(src);
        List<String> erlaubt = Arrays.asList("ClimateOnCommand", "ClimateOffCommand", "ClimateStepTempCommand",
                "ClimateSetFanCommand", "ClimateSetTempCommand",
                // 0.1.38.6 Komfort (LauncherKomfortWegTest): nur diese, nie Fenster, Schloss, Licht, Hupe, Kofferraum
                "SeatHeatCommand", "SeatVentCommand", "SteeringWheelHeatCommand", "AcRecirculationCommand",
                "AcAutoModeCommand", "FrontDefrostCommand", "RearDefrostCommand", "FanOnlyModeCommand");
        int n = 0;
        while (m.find()) {
            if (m.group(1).equals("VehicleCommand")) continue;   // Import des Typs
            assertTrue(m.group(1), erlaubt.contains(m.group(1)));
            n++;
        }
        assertTrue(n >= 4);
        for (String verboten : new String[]{"VehicleControlApiHandler.handle", "Remote", "Flash", "FindCar", "Lock",
                "Window", "Trunk", "Horn", "Hazard", "executeSdkOnly"}) {
            assertFalse(verboten, src.contains(verboten));
        }
        assertEquals(new java.util.HashSet<>(Arrays.asList("an", "aus", "temp_hoch", "temp_runter", "luft_hoch",
                "luft_runter")), LauncherKlimaBefehl.KLIMA_AKTIONEN);
        assertEquals(20, LauncherKlimaBefehl.AKTIONEN.size());
    }

    @Test
    public void nebenDemLauncherNieDerLauncherSelbst() throws Exception {
        for (String body : new String[]{"{\"package\":\"at.greenchili.panoptes.launcher\",\"mode\":\"neben\"}",
                "{\"mode\":\"neben\"}"}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            LauncherApiHandler.handle("POST", "/api/launcher/v1/apps/launch", body, out);
            assertTrue(body, new String(out.toByteArray(), StandardCharsets.UTF_8).startsWith("HTTP/1.1 400"));
        }
        String src = lies("app/src/main/java/at/greenchili/surfmate/server/LauncherApiHandler.java");
        assertTrue(src.contains("fwd.put(\"package\", LAUNCHER_PAKET);"));
        assertTrue(src.contains("fwd.put(\"secondaryPackage\", pkg);"));
    }

    @Test
    public void klimaUndUpdateImPflichtProtokoll() {
        assertTrue(Arrays.asList(BefehlsLog.PFLICHT_PFADE).contains("/api/launcher/v1/klima"));
        assertTrue(Arrays.asList(BefehlsLog.PFLICHT_PFADE).contains("/api/launcher/v1/update"));
    }

    private static String lies(String rel) throws Exception {
        Path cur = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (cur != null) {
            for (Path p : new Path[]{cur.resolve(rel), cur.resolve(rel.replaceFirst("^app/", ""))}) {
                if (Files.isRegularFile(p)) return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            }
            cur = at.greenchili.surfmate.testutil.RepoWurzel.hoch(cur);
        }
        throw new AssertionError("Nicht gefunden: " + rel);
    }
}
