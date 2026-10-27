package at.greenchili.surfmate.power;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 0.1.19 A+: kein 8-min-Aufleuchten, solange das System wach ist; Erreichbarkeit nie schlechter als bisher. */
public class StandWeckRegelTest {

    @Test
    public void wachesSystemWirdNichtGeweckt() {
        assertFalse(StandWeckRegel.vollesWecken(false, Boolean.TRUE));
    }

    @Test
    public void schlafendesOderUnbekanntesSystemWirdGewecktWieBisher() {
        assertTrue(StandWeckRegel.vollesWecken(false, Boolean.FALSE));
        assertTrue(StandWeckRegel.vollesWecken(false, null));
    }

    @Test
    public void sdRettungWecktImmer() {
        assertTrue(StandWeckRegel.vollesWecken(true, Boolean.TRUE));
        assertTrue(StandWeckRegel.vollesWecken(true, Boolean.FALSE));
        assertTrue(StandWeckRegel.vollesWecken(true, null));
    }

    @Test
    public void keepAliveFragtDieRegelVorDemWakeUpUndSetztSchienenTrotzdem() throws Exception {
        String s = lies("app/src/main/java/at/greenchili/surfmate/daemon/AccSentryDaemon.java");
        int block = s.indexOf("tick % MCU_REWAKE_EVERY_TICKS == 0");
        assertTrue(block > 0);
        int schienen = s.indexOf("peripheralPowerReconciler.requestReapply(", block);
        int regel = s.indexOf("StandWeckRegel.vollesWecken(", block);
        int wecken = s.indexOf("boolean wakeCommitted = fullWake && runBoundedHardwareBoolean(", block);
        int wake = s.indexOf("() -> performSystemWakeUp(", block);
        assertTrue("Schienen vor der Regel", schienen > 0 && schienen < regel);
        assertTrue("Regel vor dem wakeUp", regel > 0 && regel < wecken && wecken < wake);
        assertTrue(s.contains("sdRecoveryTick, isApInteractive())"));
        assertTrue(s.contains("return pm == null ? null : pm.isInteractive();"));
    }

    private static String lies(String rel) throws Exception {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (p != null) {
            for (Path c : new Path[] {p.resolve(rel), p.resolve(rel.replaceFirst("^app/", ""))}) {
                if (Files.isRegularFile(c)) return new String(Files.readAllBytes(c), "UTF-8");
            }
            p = p.getParent();
        }
        throw new java.io.FileNotFoundException(rel);
    }
}
