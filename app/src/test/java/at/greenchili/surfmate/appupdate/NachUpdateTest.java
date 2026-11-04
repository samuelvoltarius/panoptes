package at.greenchili.surfmate.appupdate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 0.1.38.8 Nachlauf nach einem Update (Koordination 04.11.): Autostart bleibt nach allen Versuchen aus → genau eine
 * Meldung; Launcher-Update → gewählter Startbildschirm wird wiederhergestellt, nie der BYD-Rückweg angefasst.
 */
public class NachUpdateTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    static final String FREI = "Broadcast completed: result=1, data=\"ergebnis=OK ok=true\"";
    static final String GESCHEITERT = "Broadcast completed: result=0, data=\"ergebnis=NOT_FOUND ok=false\"";

    int meldungen;

    AutostartNachUpdate autostart(String antwort, List<String> befehle) throws Exception {
        File ok = tmp.newFile(".update-ok" + System.nanoTime());
        return new AutostartNachUpdate(ok, new File(tmp.getRoot(), "merk" + System.nanoTime() + ".json"), (cmd, t) -> {
            befehle.add(cmd);
            return antwort;
        }, () -> meldungen++);
    }

    @Test
    public void autostartFreiMeldetNichts() throws Exception {
        List<String> b = new ArrayList<>();
        AutostartNachUpdate a = autostart(FREI, b);
        assertTrue(a.pruefen(1000));
        assertFalse(a.pruefen(2000));
        assertEquals(0, meldungen);
        assertEquals("derselbe Broadcast wie autostart-check.sh", AutostartNachUpdate.ENABLE, b.get(0));
    }

    /** Attrappe der Shell am Auto: Broadcast-Antwort, Installationszeit, settings put/get. */
    AutostartNachUpdate autostartMitMarke(String antwort, String dumpsys, List<String> befehle) throws Exception {
        File ok = tmp.newFile(".update-ok" + System.nanoTime());
        String[] global = {"null"};
        return new AutostartNachUpdate(ok, new File(tmp.getRoot(), "merk" + System.nanoTime() + ".json"), (cmd, t) -> {
            befehle.add(cmd);
            if (cmd.equals(AutostartNachUpdate.INSTALLZEIT)) return dumpsys;
            if (cmd.equals(AutostartNachUpdate.MARKE_LESEN)) return global[0] + "\n";
            if (cmd.startsWith("settings put global panoptes_autostart_ok '")) {
                global[0] = cmd.substring(cmd.indexOf('\'') + 1, cmd.lastIndexOf('\''));
                return "";
            }
            return antwort;
        }, () -> meldungen++);
    }

    @Test
    public void freiSetztDieEigeneMarkeWieDasSkript() throws Exception {
        List<String> b = new ArrayList<>();
        AutostartNachUpdate a = autostartMitMarke(FREI, "    lastUpdateTime=2026-11-04 19:26:12\n", b);
        assertTrue(a.pruefen(1000));
        assertTrue(b.contains("settings put global panoptes_autostart_ok '2026-11-04 19:26:12'"));
        assertTrue("zurückgelesen", a.merk().getBoolean("marke"));
        assertEquals(0, meldungen);
    }

    @Test
    public void markeNurMitSauberemZeitstempel() throws Exception {
        List<String> b = new ArrayList<>();
        AutostartNachUpdate a = autostartMitMarke(FREI, "lastUpdateTime=2026-11-04 19:26:12'; reboot\n".replace("12'", "1x'"), b);
        assertTrue(a.pruefen(1000));
        assertFalse(b.stream().anyMatch(x -> x.startsWith("settings put")));
        assertFalse(a.merk().getBoolean("marke"));
    }

    @Test
    public void gescheitertSetztKeineMarke() throws Exception {
        List<String> b = new ArrayList<>();
        AutostartNachUpdate a = autostartMitMarke(GESCHEITERT, "lastUpdateTime=2026-11-04 19:26:12\n", b);
        assertTrue(a.pruefen(1000));
        assertFalse(b.stream().anyMatch(x -> x.startsWith("settings put")));
        assertEquals(1, meldungen);
    }

    @Test
    public void autostartGescheitertMeldetGenauEinmal() throws Exception {
        AutostartNachUpdate a = autostart(GESCHEITERT, new ArrayList<>());
        assertTrue(a.pruefen(1000));
        assertEquals(1, meldungen);
        assertFalse("kein zweiter Versuch, keine zweite Meldung", a.pruefen(2000));
        a.freigeben(3000);                               // Knopf am Display, wieder nicht frei
        assertEquals(1, meldungen);
        assertTrue(a.merk().optBoolean("gemeldet"));
    }

    @Test
    public void appNochNichtBereitMeldetErstNachDemLetztenVersuch() throws Exception {
        AutostartNachUpdate a = autostart(null, new ArrayList<>());   // keine Antwort: App/Receiver noch nicht da
        for (int i = 1; i < AutostartNachUpdate.MAX_VERSUCHE; i++) {
            assertTrue(a.pruefen(i * 1000L));
            assertEquals("Versuch " + i + ": noch keine Meldung", 0, meldungen);
        }
        assertTrue(a.pruefen(9000));
        assertEquals(1, meldungen);
        assertFalse(a.pruefen(10_000));
        assertEquals(1, meldungen);
    }

    @Test
    public void meldungTextInFuenfSprachen() throws Exception {
        for (String sp : new String[]{"de", "en", "fr", "it", "nl"}) {
            JSONObject k = new JSONObject(UpdateHinweisTest.lies("src/main/assets/server-i18n/" + sp + ".json"))
                    .getJSONObject("update_klein");
            for (String s : new String[]{"autostart_titel", "autostart_fehlt", "launcher_home"}) {
                assertFalse(sp + " " + s, k.getString(s).isEmpty());
                assertFalse(sp + " " + s + ": kein Markdown", k.getString(s).matches(".*[*_`\\[].*"));
            }
        }
    }

    // ------------------------------------------------------------- Launcher als Startbildschirm nach dem Update

    static final String MIT_ALIAS = "3 activities found:\n    at.greenchili.panoptes.launcher/.HomeAlias\n"
            + "    com.android.launcher3/.home.MainActivity\n"
            + "    com.byd.carsettings/com.byd.systemsettings.fallbackhome.FallbackHome\n";
    static final String OHNE_ALIAS = "2 activities found:\n    com.android.launcher3/.home.MainActivity\n"
            + "    com.byd.carsettings/com.byd.systemsettings.fallbackhome.FallbackHome\n";
    static final String WIR = "priority=0\nat.greenchili.panoptes.launcher/.HomeAlias\n";
    static final String BYD = "priority=0\ncom.android.launcher3/.home.MainActivity\n";

    /** Attrappe: nach set-home-activity auf uns löst HOME auf uns auf (wenn {@code wirkt}). */
    static LauncherPaket.Geraet geraet(String abfrage, String[] aufloesen, boolean wirkt, String deaktiviert,
                                       List<String> befehle) {
        return (b, s) -> {
            befehle.add(b);
            if (b.equals(LauncherHome.ABFRAGE)) return abfrage;
            if (b.equals(LauncherHome.AUFLOESEN)) return aufloesen[0];
            if (b.equals(LauncherHome.STANDARD_SETZEN)) {
                if (wirkt) aufloesen[0] = WIR;
                return "";
            }
            if (b.equals("pm list packages -d")) return deaktiviert;
            return "";
        };
    }

    static void nieBydAngefasst(List<String> befehle) {
        for (String b : befehle) {
            assertFalse(b, b.contains("disable") && b.contains("launcher3"));
            assertFalse(b, b.contains("set-home-activity") && b.contains("launcher3"));
            assertFalse(b, b.startsWith("pm uninstall") || b.startsWith("pm clear"));
        }
    }

    @Test
    public void startbildschirmWirdWiederhergestellt() {
        List<String> b = new ArrayList<>();
        assertNull(LauncherHome.nachUpdate(geraet(MIT_ALIAS, new String[]{BYD}, true, "", b), true, true));
        assertTrue(b.contains(LauncherHome.STANDARD_SETZEN));
        assertEquals("zurückgelesen", 2, b.stream().filter(LauncherHome.AUFLOESEN::equals).count());
        nieBydAngefasst(b);
    }

    @Test
    public void schonStandardNichtsTun() {
        List<String> b = new ArrayList<>();
        assertNull(LauncherHome.nachUpdate(geraet(MIT_ALIAS, new String[]{WIR}, true, "", b), true, true));
        assertFalse(b.contains(LauncherHome.STANDARD_SETZEN));
    }

    @Test
    public void nichtGewaehltOderNichtBehaltenNichtsTun() {
        List<String> b = new ArrayList<>();
        assertNull(LauncherHome.nachUpdate(geraet(MIT_ALIAS, new String[]{BYD}, true, "", b), false, false));
        assertNull("Totmann nicht bestätigt: der Wächter entscheidet",
                LauncherHome.nachUpdate(geraet(MIT_ALIAS, new String[]{BYD}, true, "", b), true, false));
        assertTrue("liest nicht einmal", b.isEmpty());
    }

    @Test
    public void rueckfallStufeAusgeschaltetBleibtAus() {
        List<String> b = new ArrayList<>();
        assertNull(LauncherHome.nachUpdate(geraet(MIT_ALIAS, new String[]{BYD}, true,
                "package:" + LauncherHome.PAKET + "\n", b), true, true));
        assertFalse(b.contains(LauncherHome.STANDARD_SETZEN));
        assertFalse(b.stream().anyMatch(x -> x.startsWith("pm enable")));
    }

    @Test
    public void aliasAusMeldetStattZuSetzen() {
        List<String> b = new ArrayList<>();
        assertEquals("alias_aus", LauncherHome.nachUpdate(geraet(OHNE_ALIAS, new String[]{BYD}, true, "", b), true, true));
        assertFalse(b.contains(LauncherHome.STANDARD_SETZEN));
        nieBydAngefasst(b);
    }

    @Test
    public void setzenWirktNichtMeldet() {
        List<String> b = new ArrayList<>();
        assertEquals("noch_nicht_standard",
                LauncherHome.nachUpdate(geraet(MIT_ALIAS, new String[]{BYD}, false, "", b), true, true));
        nieBydAngefasst(b);
    }

    @Test
    public void launcherUpdateRuftDenNachlauf() throws Exception {
        String dienst = new String(Files.readAllBytes(new File(new File("src").isDirectory() ? "src" : "app/src",
                "main/java/at/greenchili/surfmate/appupdate/AppUpdateDienst.java").toPath()), StandardCharsets.UTF_8);
        int inst = dienst.indexOf("String grund = env.launcherInstallieren(apk);");
        int nach = dienst.indexOf("env.launcherHomeNachUpdate()");
        assertTrue(inst > 0 && nach > inst);
        assertTrue(dienst.contains("LauncherHome.nachUpdate(LauncherPaket.SHELL, at.greenchili.surfmate.launcher.HomeWaechter.wunsch(),"));
    }
}
