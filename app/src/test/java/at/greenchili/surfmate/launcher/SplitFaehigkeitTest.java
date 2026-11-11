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
        assertEquals("0.1.39: VIA_SDK erst ab targetSdk 24 teilbar (sonst Rückfall)", SplitFaehigkeit.NEIN, SplitFaehigkeit.urteil(1, 23, false));
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

    /** 0.1.39 (L1-Nachbesserung c): „Start“ verlässt den Split – Launcher-Task (links) in den Secondary-Stack, Navi bleibt. */
    @Test
    public void splitEndeSchiebtLauncherTaskInDenSecondary() throws Exception {
        String liste = String.join("\n",
                "Stack id=3 bounds=[0,0][635,720] displayId=0 userId=0",
                " configuration={1.0 winConfig={ mWindowingMode=split-screen-primary mActivityType=standard}}",
                "  taskId=205: at.greenchili.panoptes.launcher/at.greenchili.panoptes.launcher.MainActivity bounds=[0,0][635,720] userId=0 visible=true",
                "Stack id=4 bounds=[645,0][1280,720] displayId=0 userId=0",
                " configuration={1.0 winConfig={ mWindowingMode=split-screen-secondary mActivityType=standard}}",
                "  taskId=207: com.google.android.apps.maps/com.google.android.maps.MapsActivity bounds=[645,0][1280,720] userId=0 visible=true",
                "Stack id=9 bounds=[0,0][1280,720] displayId=0 userId=0",
                " configuration={1.0 winConfig={ mWindowingMode=fullscreen mActivityType=standard}}",
                "  taskId=211: at.greenchili.panoptes.launcher/at.greenchili.panoptes.launcher.MainActivity bounds=[0,0][1280,720] userId=0 visible=false");
        assertEquals(205, AppLauncher.primaryTaskAusListe(liste, "at.greenchili.panoptes.launcher"));
        assertEquals("die Navi wird nie verschoben", -1, AppLauncher.primaryTaskAusListe(liste, "com.google.android.apps.maps"));
        assertEquals("ohne Split nichts", -1, AppLauncher.primaryTaskAusListe(String.join("\n",
                "Stack id=9 bounds=[0,0][1280,720]", " configuration={ mWindowingMode=fullscreen}",
                "  taskId=211: at.greenchili.panoptes.launcher/at.greenchili.panoptes.launcher.MainActivity bounds=[0,0][1280,720]"),
                "at.greenchili.panoptes.launcher"));
        String a = lies("app/src/main/java/at/greenchili/surfmate/launcher/AppLauncher.java").replace("\r\n", "\n");
        String f = a.substring(a.indexOf("public static boolean splitBeenden("), a.indexOf("static int primaryTaskAusListe("));
        assertTrue(f.contains("\"am stack move-task \" + task + \" \" + sekundaer + \" true\""));
        assertTrue("kein Beenden der Navi", !f.contains("force-stop") && !f.contains("stack remove"));
        String h = lies("app/src/main/java/at/greenchili/surfmate/server/LauncherApiHandler.java").replace("\r\n", "\n");
        assertTrue("nur der eigene Launcher", h.contains("AppLauncher.splitBeenden(LAUNCHER_PAKET)"));
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

    /** 0.1.39 E2E R2-1: „Start“ (splitBeenden) und ein neuer Split beenden ein laufendes haltePaarImSplit. */
    @org.junit.Test
    public void startBeendetDasHaltenImSplit() throws Exception {
        int gen = AppLauncher.HALTEN_GENERATION.incrementAndGet();
        org.junit.Assert.assertTrue(AppLauncher.halteNoch(gen));
        AppLauncher.HALTEN_GENERATION.incrementAndGet();
        org.junit.Assert.assertFalse(AppLauncher.halteNoch(gen));
        java.nio.file.Path p = java.nio.file.Paths.get("src/main/java/at/greenchili/surfmate/launcher/AppLauncher.java");
        if (!java.nio.file.Files.exists(p)) p = java.nio.file.Paths.get("app").resolve(p);
        String src = new String(java.nio.file.Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
        int sb = src.indexOf("public static boolean splitBeenden(");
        org.junit.Assert.assertTrue("splitBeenden zählt hoch, bevor es verschiebt",
                src.indexOf("HALTEN_GENERATION.incrementAndGet()", sb) < src.indexOf("am stack move-task", sb));
        int h = src.indexOf("public static void haltePaarImSplit(");
        String rumpf = src.substring(h, src.indexOf("public static boolean splitBeenden(", 0) > h ? src.indexOf("public static boolean splitBeenden(") : src.length());
        org.junit.Assert.assertTrue(rumpf.contains("final int gen = HALTEN_GENERATION.incrementAndGet();"));
        int mv = rumpf.indexOf("am stack move-task");
        org.junit.Assert.assertTrue("Prüfung direkt vor dem Verschieben", rumpf.lastIndexOf("if (!halteNoch(gen)) break;", mv) > 0);
    }
}
