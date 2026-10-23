package at.greenchili.surfmate.power;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 0.1.13 (Kern 7): keine dauerhaften globalen Eingriffe mehr; Ursprung sichern, nur eigenes stay_on zurücknehmen. */
public class SystemEingriffeTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void ursprungUndRuecknahmeRegeln() {
        assertEquals("unbekannt", SystemEingriffe.ursprungAus("7"));
        assertEquals("0", SystemEingriffe.ursprungAus("0"));
        assertEquals("3", SystemEingriffe.ursprungAus(" 3 "));
        assertEquals("unbekannt", SystemEingriffe.ursprungAus("null"));
        assertEquals("0", SystemEingriffe.zurueckWert("unbekannt"));
        assertEquals("2", SystemEingriffe.zurueckWert("2"));
        assertTrue(SystemEingriffe.zuruecksetzen("7", "unbekannt"));
        assertTrue(SystemEingriffe.zuruecksetzen("7", "0"));
        assertFalse("Nutzer/Hersteller hatte 7: bleibt", SystemEingriffe.zuruecksetzen("7", "7"));
        assertFalse("anderer Wert gehört nicht uns", SystemEingriffe.zuruecksetzen("2", "unbekannt"));
    }

    /** Führt Sicherung + Park-Skript mit einer settings-Attrappe aus; liefert den Endwert. */
    private String park(String start, String ursprungDatei) throws Exception {
        File d = tmp.newFolder();
        File wert = new File(d, "wert");
        Files.write(wert.toPath(), start.getBytes(StandardCharsets.UTF_8));
        File u = new File(d, "ursprung");
        if (ursprungDatei != null) Files.write(u.toPath(), ursprungDatei.getBytes(StandardCharsets.UTF_8));
        String w = wert.getAbsolutePath().replace('\\', '/');
        String attrappe = "settings() { if [ \"$1\" = get ]; then cat '" + w + "'; else printf %s \"$4\" > '" + w + "'; fi; }; ";
        String skript = attrappe + SystemEingriffe.parkSkript().replace(SystemEingriffe.URSPRUNG_DATEI,
                u.getAbsolutePath().replace('\\', '/'));
        Process p = new ProcessBuilder("sh", "-c", skript).redirectErrorStream(true).start();
        p.waitFor();
        return new String(Files.readAllBytes(wert.toPath()), StandardCharsets.UTF_8).trim();
    }

    private static boolean shDa() {
        try {
            Process p = new ProcessBuilder("sh", "-c", "echo ja").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    public void parkSkriptEchteShell() throws Exception {
        assumeTrue("sh nicht vorhanden", shDa());
        assertEquals("unser 7, Ursprung unbekannt → 0", "0", park("7", null));
        assertEquals("unser 7 → gesicherter Ursprung", "2", park("7", "global.stay_on_while_plugged_in=2\n"));
        assertEquals("Ursprung war 7 (Nutzer/Hersteller) → bleibt", "7", park("7", "global.stay_on_while_plugged_in=7\n"));
        assertEquals("anderer Wert bleibt", "3", park("3", null));
    }

    private static String quelle(String rel) throws Exception {
        File f = new File("src/main/java/" + rel);
        if (!f.isFile()) f = new File("app/src/main/java/" + rel);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void keineDauerhaftenEingriffeMehrImQuelltext() throws Exception {
        for (String rel : new String[] {
                "at/greenchili/surfmate/daemon/SentryDaemon.java",
                "at/greenchili/surfmate/launcher/ServiceLauncher.kt",
                "at/greenchili/surfmate/launcher/AdbDaemonLauncher.kt"}) {
            String s = quelle(rel);
            assertFalse(rel, s.contains("settings put global stay_on_while_plugged_in 7"));
            assertFalse(rel, s.contains("\"dumpsys deviceidle disable\""));
            assertFalse(rel, s.contains("\"input keyevent 224"));
            assertFalse(rel, s.contains("\"input keyevent 26\""));
            assertFalse(rel, s.contains("screen_off_timeout 1000"));
        }
        assertFalse(quelle("at/greenchili/surfmate/daemon/AccSentryDaemon.java").contains("svc power stayon false"));
    }

    /**
     * 0.1.15 (Kern 8): Der Park-Reaper läuft nur in onOnly. Nach dem 0.1.14-Update stand bei Projektinhaber (onAndOff) noch 7.
     * enterSentryMode – bei jedem Parken und beim Daemon-Start im Stand – nimmt das 7 jetzt auch in onAndOff zurück,
     * mit demselben parkSkript (echte Shell oben), außer DiLink 5, und nicht auf dem HAL-Listener-Thread.
     */
    @Test
    public void stayOnRueckgabeAuchInOnAndOff() throws Exception {
        String s = quelle("at/greenchili/surfmate/daemon/AccSentryDaemon.java");
        int ein = s.indexOf("private static void enterSentryMode()");
        int aus = s.indexOf("private static void exitSentryMode()");
        assertTrue(ein > 0 && aus > ein);
        String enter = s.substring(ein, aus);
        int aufruf = enter.indexOf("stayOnAltlastZuruecknehmen();");
        assertTrue("Aufruf in enterSentryMode", aufruf > 0);
        assertTrue("nach neuer Transition (nicht im „Already in sentry mode“-Zweig)",
                aufruf > enter.indexOf("requestTransitionReconciliation(transition);"));
        assertTrue("vor dem onOnly-return", aufruf < enter.indexOf("if (vehicleOnOnly) {"));
        String wache = enter.substring(enter.lastIndexOf("if (", aufruf), aufruf);
        assertTrue("nur onAndOff (onOnly hat den Reaper)", wache.contains("!vehicleOnOnly"));
        assertTrue("DiLink 5 ausgenommen", wache.contains("!at.greenchili.surfmate.camera.dilink5.DiLink5Platform.isSelected()"));
        int m = s.indexOf("private static void stayOnAltlastZuruecknehmen()");
        assertTrue(m > 0);
        String methode = s.substring(m, s.indexOf("private static String execShell(String cmd)", m));
        assertTrue("gleiches Skript wie der Park-Reaper", methode.contains("SystemEingriffe.parkSkript()"));
        assertTrue("eigener Thread", methode.contains("new Thread(") && methode.contains(".start();"));
        assertFalse("setzt nie selbst 7", methode.contains("stay_on_while_plugged_in 7"));
    }
}
