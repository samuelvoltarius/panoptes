package at.greenchili.surfmate.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/** P1 (0.1.43.20): Android-13-Gegenprüfung der DiLink-5-Task-Wache. */
public class Di5TaskBelegTest {

    private static final String NAVI = "com.telenav.app.arp";

    /** Aufbau wie `dumpsys activity activities` auf Android 13. */
    private static final String DUMP_AUF_ZIEL =
            "ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\n"
            + "Display #3 (activities from top to bottom):\n"
            + "  * Task{5c1a2b #122 type=standard A=10087:com.telenav.app.arp U=0 "
            + "visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}\n"
            + "    topResumedActivity=ActivityRecord{9f00aa u0 "
            + "com.telenav.app.arp/com.telenav.arp.module.map.MainActivity t122}\n"
            + "    * Hist  #0: ActivityRecord{9f00aa u0 "
            + "com.telenav.app.arp/com.telenav.arp.module.map.MainActivity t122}\n"
            + "Display #0 (activities from top to bottom):\n"
            + "  * Task{77aa #2 type=home A=10021:com.byd.launcher U=0 visible=true sz=1}\n"
            + "    * Hist  #0: ActivityRecord{11bb u0 com.byd.launcher/.Home t2}\n"
            + "\n"
            + "ActivityTaskSupervisor state:\n"
            + "  topDisplayFocusedRootTask=Task{77aa #2}\n"
            + "  mLastPausedActivity: ActivityRecord{dd u0 com.telenav.app.arp/.X t999}\n";

    private static final String DUMP_FALSCHES_DISPLAY =
            "ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\n"
            + "Display #3 (activities from top to bottom):\n"
            + "  * Task{5c #130 type=standard A=10091:com.byd.launchermap U=0 sz=1}\n"
            + "    * Hist  #0: ActivityRecord{1 u0 com.byd.launchermap/.automap.meter.MeterActivity t130}\n"
            + "Display #0 (activities from top to bottom):\n"
            + "  * Task{5d #122 type=standard A=10087:com.telenav.app.arp U=0 sz=1}\n"
            + "    * Hist  #0: ActivityRecord{2 u0 com.telenav.app.arp/.MainActivity t122}\n";

    private static final String DUMP_FEHLT =
            "ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\n"
            + "Display #3 (activities from top to bottom):\n"
            + "  * Task{5c #130 type=standard A=10091:com.byd.launchermap U=0 sz=1}\n"
            + "    * Hist  #0: ActivityRecord{1 u0 com.byd.launchermap/.MeterActivity t130}\n"
            + "Display #0 (activities from top to bottom):\n"
            + "    * Hist  #0: ActivityRecord{11bb u0 com.byd.launcher/.Home t2}\n";

    @Test
    public void exakterTrefferAufDemZielDisplay() {
        Di5TaskBeleg.Befund b = Di5TaskBeleg.auswerten(DUMP_AUF_ZIEL, NAVI, 3);
        assertEquals(Di5TaskBeleg.Art.AUF_ZIEL, b.art);
        assertEquals(122, b.taskId);
        assertEquals(3, b.displayId);
    }

    @Test
    public void zeilenNachDenDisplayBloeckenZaehlenNicht() {
        // t999 steht nur unter „ActivityTaskSupervisor state:“ und darf nie als Task gelten.
        Di5TaskBeleg.Befund b = Di5TaskBeleg.auswerten(DUMP_AUF_ZIEL, NAVI, 3);
        assertEquals(122, b.taskId);
    }

    @Test
    public void taskAufAnderemDisplayIstPositiverBeleg() {
        Di5TaskBeleg.Befund b = Di5TaskBeleg.auswerten(DUMP_FALSCHES_DISPLAY, NAVI, 3);
        assertEquals(Di5TaskBeleg.Art.ANDERES_DISPLAY, b.art);
        assertEquals(122, b.taskId);
        assertEquals(0, b.displayId);
    }

    @Test
    public void vollstaendigGelesenUndNichtDaIstFehlt() {
        assertEquals(Di5TaskBeleg.Art.FEHLT,
                Di5TaskBeleg.auswerten(DUMP_FEHLT, NAVI, 3).art);
    }

    @Test
    public void allesUnsichereIstUnbekannt() {
        assertEquals(Di5TaskBeleg.Art.UNBEKANNT, Di5TaskBeleg.auswerten(null, NAVI, 3).art);
        assertEquals(Di5TaskBeleg.Art.UNBEKANNT, Di5TaskBeleg.auswerten("", NAVI, 3).art);
        // ohne Kopfzeile = nicht sicher vollständig
        assertEquals(Di5TaskBeleg.Art.UNBEKANNT, Di5TaskBeleg.auswerten(
                "Display #3 (activities from top to bottom):\n", NAVI, 3).art);
        // Kopf da, aber kein einziger Display-Block
        assertEquals(Di5TaskBeleg.Art.UNBEKANNT, Di5TaskBeleg.auswerten(
                "ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\n", NAVI, 3).art);
        // Paket in unbekanntem Format
        assertEquals(Di5TaskBeleg.Art.UNBEKANNT, Di5TaskBeleg.auswerten(
                "ACTIVITY MANAGER ACTIVITIES\nDisplay #3 (x):\n"
                        + "    * Hist #0: ActivityRecord{zz?? u0 " + NAVI + "/.Main}\n",
                NAVI, 3).art);
        // gleichzeitig auf Ziel und anderem Display = mehrdeutig
        String beide = DUMP_AUF_ZIEL.replace("Display #0 (activities from top to bottom):\n",
                "Display #0 (activities from top to bottom):\n"
                        + "    * Hist  #0: ActivityRecord{3 u0 " + NAVI + "/.Main t140}\n");
        assertEquals(Di5TaskBeleg.Art.UNBEKANNT, Di5TaskBeleg.auswerten(beide, NAVI, 3).art);
    }

    @Test
    public void nurUserNullUndExaktesPaket() {
        String andererUser = DUMP_FEHLT.replace("Display #0 (activities from top to bottom):\n",
                "Display #0 (activities from top to bottom):\n"
                        + "    * Hist  #0: ActivityRecord{3 u10 " + NAVI + "/.Main t140}\n"
                        + "    * Hist  #1: ActivityRecord{4 u0 " + NAVI + ".helper/.Main t141}\n");
        assertEquals(Di5TaskBeleg.Art.FEHLT,
                Di5TaskBeleg.auswerten(andererUser, NAVI, 3).art);
    }

    @Test
    public void api32LiestNieEinenDump() {
        AtomicInteger aufrufe = new AtomicInteger();
        Di5TaskBeleg.Bewertung b = Di5TaskBeleg.bewerte(32,
                ClusterFreeformWindow.TaskLocation.absent(), NAVI, 3, 0,
                () -> { aufrufe.incrementAndGet(); return DUMP_AUF_ZIEL; });
        assertEquals(0, aufrufe.get());
        assertFalse(b.dumpGelesen);
        assertTrue(b.ort.known);
        assertEquals(-1, b.ort.taskId);
    }

    @Test
    public void api33MachtAusKeinTaskDenSichtbarenTask() {
        Di5TaskBeleg.Bewertung b = Di5TaskBeleg.bewerte(33,
                ClusterFreeformWindow.TaskLocation.absent(), NAVI, 3, 0, () -> DUMP_AUF_ZIEL);
        assertTrue(b.dumpGelesen);
        assertEquals(122, b.ort.taskId);
        assertEquals(3, b.ort.displayId);
        assertFalse(b.belegtFalschesDisplay);
    }

    @Test
    public void api33FehlgeschlagenerDumpIstUnbekanntNichtFehlt() {
        Di5TaskBeleg.Bewertung b = Di5TaskBeleg.bewerte(33,
                ClusterFreeformWindow.TaskLocation.absent(), NAVI, 3, 0, () -> null);
        assertFalse(b.ort.known);
        assertFalse(b.belegtFalschesDisplay);
        Di5TaskBeleg.Bewertung wirft = Di5TaskBeleg.bewerte(33,
                ClusterFreeformWindow.TaskLocation.absent(), NAVI, 3, 0,
                () -> { throw new IllegalStateException("x"); });
        assertFalse(wirft.ort.known);
    }

    @Test
    public void api33DumpsSindBegrenzt() {
        AtomicInteger aufrufe = new AtomicInteger();
        Di5TaskBeleg.Bewertung b = Di5TaskBeleg.bewerte(33,
                ClusterFreeformWindow.TaskLocation.absent(), NAVI, 3,
                Di5TaskBeleg.MAX_DUMPS_JE_WACHE,
                () -> { aufrufe.incrementAndGet(); return DUMP_FEHLT; });
        assertEquals(0, aufrufe.get());
        assertFalse(b.ort.known);
    }

    @Test
    public void api33ZwangsneustartNurBeiBelegUndHoechstensEinmal() {
        int n = Di5TaskBeleg.ZWANGSNEUSTART_NACH;
        // „nicht gefunden“ (bestätigt fehlend) → kein forceStop
        assertFalse(Di5TaskBeleg.zwangsneustartErlaubt(33, true, n, false, false, 0));
        // App sichtbar vorn → kein forceStop, auch bei Beleg
        assertFalse(Di5TaskBeleg.zwangsneustartErlaubt(33, true, n, true, true, 0));
        // positiver Beleg „falsches Display“ → einmal erlaubt
        assertTrue(Di5TaskBeleg.zwangsneustartErlaubt(33, true, n, true, false, 0));
        assertFalse(Di5TaskBeleg.zwangsneustartErlaubt(33, true, n, true, false, 1));
        // zu früh
        assertFalse(Di5TaskBeleg.zwangsneustartErlaubt(33, true, n - 1, true, false, 0));
    }

    @Test
    public void api28bis32BehaltenDasAlteVerhalten() {
        int n = Di5TaskBeleg.ZWANGSNEUSTART_NACH;
        for (int sdk = 28; sdk <= 32; sdk++) {
            assertTrue(Di5TaskBeleg.zwangsneustartErlaubt(sdk, true, n, false, true, 7));
            assertFalse(Di5TaskBeleg.zwangsneustartErlaubt(sdk, true, n - 1, false, false, 0));
            assertFalse(Di5TaskBeleg.zwangsneustartErlaubt(sdk, false, n, false, false, 0));
        }
    }

    @Test
    public void wacheNutztDieGegenpruefungUndKeinenFestenForceStopZaehlerMehr() throws Exception {
        String src = lies("app/src/main/java/at/greenchili/surfmate/launcher/DiLink5ClusterCast.kt");
        int wache = src.indexOf("private fun startTaskGuardian(");
        assertTrue(wache > 0);
        String body = src.substring(wache, src.indexOf("finalOnTarget", wache));
        assertTrue(body.contains("Di5TaskBeleg.bewerte("));
        assertTrue(body.contains("Di5TaskBeleg.zwangsneustartErlaubt("));
        assertFalse(src.contains("TASK_GUARD_FORCE_RESTART_COUNT"));
    }

    private static String lies(String rel) throws Exception {
        Path p = Paths.get(rel);
        for (int i = 0; i < 4 && !Files.exists(p); i++) p = Paths.get("..").resolve(p);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
