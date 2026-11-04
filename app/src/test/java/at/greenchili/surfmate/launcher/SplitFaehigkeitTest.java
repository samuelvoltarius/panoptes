package at.greenchili.surfmate.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 0.1.38.5: Teilbarkeit vorher ehrlich prüfen (Befund am Auto: YouTube/Netflix/CarPlay nicht resizeable). */
public class SplitFaehigkeitTest {

    @Test
    public void urteilAusResizeMode() {
        assertEquals(SplitFaehigkeit.NEIN, SplitFaehigkeit.urteil(0, 30, false));          // resizeableActivity=false
        assertEquals(SplitFaehigkeit.JA, SplitFaehigkeit.urteil(0, 30, true));             // force_resizable_activities
        assertEquals(SplitFaehigkeit.JA, SplitFaehigkeit.urteil(2, 30, false));
        assertEquals(SplitFaehigkeit.JA, SplitFaehigkeit.urteil(1, 28, false));            // Standard ab API 24
        assertEquals("BYD ScreenSplitUtils: VIA_SDK gilt als teilbar", SplitFaehigkeit.JA, SplitFaehigkeit.urteil(1, 23, false));
        assertEquals(SplitFaehigkeit.NEIN, SplitFaehigkeit.urteil(5, 30, false));
        assertEquals(SplitFaehigkeit.NEIN, SplitFaehigkeit.urteil(6, 30, false));
        assertEquals(SplitFaehigkeit.UNBEKANNT, SplitFaehigkeit.urteil(null, 30, false));
        assertEquals(SplitFaehigkeit.UNBEKANNT, SplitFaehigkeit.fuer("../x"));
    }

    /** Ausgabe von `cmd package resolve-activity` (Emulator API 30, Kamera-App). */
    @Test
    public void shellRueckfallParser() {
        String kamera = "  ActivityInfo:\n    name=com.android.camera.CameraLauncher\n    resizeMode=RESIZE_MODE_UNRESIZEABLE\n";
        assertEquals(Integer.valueOf(0), SplitFaehigkeit.resizeModeAus(kamera));
        assertEquals(SplitFaehigkeit.NEIN, SplitFaehigkeit.urteil(SplitFaehigkeit.resizeModeAus(kamera), 30, false));
        assertEquals(Integer.valueOf(2), SplitFaehigkeit.resizeModeAus("resizeMode=RESIZE_MODE_RESIZEABLE"));
        assertEquals(Integer.valueOf(1), SplitFaehigkeit.resizeModeAus("resizeMode=RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION"));
        assertEquals(null, SplitFaehigkeit.resizeModeAus("No activity found"));
        assertEquals(23, SplitFaehigkeit.targetSdkAus("targetSdkVersion=23"));
        assertEquals(30, SplitFaehigkeit.targetSdkAus(""));
    }

    @Test
    public void launcherFragtVorherUndLoggtPaketUndGrund() throws Exception {
        String src = lies("app/src/main/java/at/greenchili/surfmate/server/LauncherApiHandler.java");
        int a = src.indexOf("if (\"neben\".equals(mode))");
        String neben = src.substring(a, src.indexOf("fwd.put(\"package\", pkg == null", a));
        assertTrue(neben.indexOf("SplitFaehigkeit.fuer(pkg)") < neben.indexOf("AppsApiHandler.handle("));
        assertTrue(neben.contains("\"{\\\"success\\\":false,\\\"error\\\":\\\"nicht_teilbar\\\"}\""));
        assertTrue(neben.contains("BefehlsLog.ereignis(logName"));
        assertTrue(neben.contains("BefehlsLog.grund(\"nicht_teilbar\")"));
        // Befund Emulator 04.11.: ohne flush kam die zwischengespeicherte Antwort nie beim Launcher an
        assertTrue(neben.indexOf("out.flush();") > neben.indexOf("out.write(puffer.toByteArray());"));
        // nichts wird gesetzt (Koordination 04.11.: kein force_resizable am Auto)
        String f = lies("app/src/main/java/at/greenchili/surfmate/launcher/SplitFaehigkeit.java");
        assertTrue(f.contains("\"settings\", \"get\", \"global\", \"force_resizable_activities\""));
        assertFalse(f.contains("\"put\""));
        assertFalse(src.contains("teilbar-alle"));
        // nach gelungenem Split werden Player-Tasks nachgeführt
        assertTrue(neben.contains("AppLauncher.haltePaarImSplit(pkg"));
    }

    /** am stack list (Emulator API 30) mit einem Player-Task der zweiten App außerhalb des Splits. */
    @Test
    public void playerTaskWirdNachgefuehrt() {
        String liste = String.join("\n",
                "Stack id=3 bounds=[0,0][635,720] displayId=0 userId=0",
                " configuration={1.0 winConfig={ mBounds=Rect(0, 0 - 635, 720) mWindowingMode=split-screen-primary mActivityType=standard}}",
                "  taskId=205: at.greenchili.panoptes.launcher/at.greenchili.panoptes.launcher.MainActivity bounds=[0,0][635,720] userId=0 visible=true topActivity=ComponentInfo{com.netflix.mediaclient/x.Y}",
                "Stack id=4 bounds=[645,0][1280,720] displayId=0 userId=0",
                " configuration={1.0 winConfig={ mWindowingMode=split-screen-secondary mActivityType=standard}}",
                "  taskId=207: com.netflix.mediaclient/com.netflix.mediaclient.ui.launch.UIWebViewActivity bounds=[645,0][1280,720] userId=0 visible=true",
                "Stack id=9 bounds=[0,0][1280,720] displayId=0 userId=0",
                " configuration={1.0 winConfig={ mWindowingMode=fullscreen mActivityType=standard}}",
                "  taskId=211: com.netflix.mediaclient/com.netflix.mediaclient.ui.player.PlayerActivity bounds=[0,0][1280,720] userId=0 visible=true",
                "  taskId=212: com.byd.carplay.ui/com.byd.carplay.ui.VideoActivity bounds=[0,0][1280,720] userId=0 visible=true");
        assertEquals(4, AppLauncher.secondaryStackAusListe(liste));
        assertEquals(java.util.Collections.singletonList(211), AppLauncher.fremdeTasksAusListe(liste, "com.netflix.mediaclient"));
        assertEquals(java.util.Collections.singletonList(212), AppLauncher.fremdeTasksAusListe(liste, "com.byd.carplay.ui"));
        assertTrue("topActivity zählt nicht", AppLauncher.fremdeTasksAusListe(liste, "x").isEmpty());
        assertEquals(-1, AppLauncher.secondaryStackAusListe(String.join("\n",
                "Stack id=1 bounds=[0,0][1280,720]", " configuration={ mWindowingMode=fullscreen}")));
    }

    private static String lies(String rel) throws Exception {
        Path cur = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (cur != null) {
            for (Path p : new Path[]{cur.resolve(rel), cur.resolve(rel.replaceFirst("^app/", ""))}) {
                if (Files.isRegularFile(p)) return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            }
            cur = cur.getParent();
        }
        throw new AssertionError("Nicht gefunden: " + rel);
    }
}
