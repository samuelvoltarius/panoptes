package at.greenchili.surfmate.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 0.1.40 (Folgepunkt E2E 0.1.39 B): findTaskId zählt nur die Basis-Komponente (realActivity) eines Tasks. Vorher
 * hielt openApp den Ghost-Task (dessen Intent die Navi nennt) für die Navi und dockte ihn statt des Launchers an.
 */
public class FindTaskIdBasisTest {

    @Test
    public void ghostTaskZaehltNieFuerDieNavi() throws Exception {
        String maps = "com.google.android.apps.maps";
        String beide = "* TaskRecord{aaa #243 A=at.greenchili.surfmate U=0 StackId=3 sz=1}\n"
                + "    realActivity=at.greenchili.surfmate/.launcher.AppLauncherGhostActivity\n"
                + "    intent={cmp=com.google.android.apps.maps/com.google.android.maps.MapsActivity}\n"
                + "* TaskRecord{bbb #240 A=com.google.android.apps.maps U=0 StackId=4 sz=1}\n"
                + "    realActivity=com.google.android.apps.maps/com.google.android.maps.MapsActivity\n";
        assertEquals(240, AppLauncher.taskIdInDump(beide, maps));
        String nurGhost = "* TaskRecord{aaa #243 A=at.greenchili.surfmate U=0 StackId=3 sz=1}\n"
                + "    realActivity=at.greenchili.surfmate/.launcher.AppLauncherGhostActivity\n"
                + "    com.google.android.apps.maps/com.google.android.maps.MapsActivity\n";
        assertEquals("Ghost-Task zählt nie für die Navi (bestätigt nicht vorhanden)", -1, AppLauncher.taskIdInDump(nurGhost, maps));
        // ohne realActivity-Zeile (ältere Form) gilt die bisherige Regel weiter
        assertEquals(42, AppLauncher.taskIdInDump("* Task{bbb #42 type=standard}\n    com.google.android.apps.maps/.X\n", maps));
    }

    @Test
    public void grepLiefertDieBasisZeilen() throws Exception {
        Path cur = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        String rel = "src/main/java/at/greenchili/surfmate/launcher/AppLauncher.java";
        Path f = Files.isRegularFile(cur.resolve(rel)) ? cur.resolve(rel) : cur.resolve("app").resolve(rel);
        String a = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
        assertTrue(a.contains("TaskRecord\\\\{|Task\\\\{|realActivity=|\" + pkg + \"/'"));
    }
}
