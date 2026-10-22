package at.greenchili.surfmate.overlay;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.TimeZone;

/** Einrichtungsdialog nur, wenn wirklich etwas fehlt (Koordination 21.10.2026). */
public class SetupNeedsTest {

    private static final TimeZone VIENNA = TimeZone.getTimeZone("Europe/Vienna");
    /** 2026-10-21 00:47:12.345 MESZ */
    private static final long INSTALL = 1792536432345L;

    @Test
    public void nothingMissingWhenEnablerConfirmedThisInstallAndOverlayGranted() {
        SetupNeeds n = SetupNeeds.evaluate(INSTALL, INSTALL, null, true, true, VIENNA);
        assertFalse(n.anything());
    }

    @Test
    public void enablerSuccessOfAnOlderInstallDoesNotCount() {
        SetupNeeds n = SetupNeeds.evaluate(INSTALL, INSTALL - 86_400_000L, null, true, true, VIENNA);
        assertTrue(n.autostartMissing);
        assertTrue(n.anything());
    }

    @Test
    public void deployConfirmationInDumpsysFormatCounts() {
        assertFalse(SetupNeeds.evaluate(INSTALL, 0L, "2026-10-21 00:47:12", true, true, VIENNA)
                .autostartMissing);
        // falsche Installation (eine Minute daneben)
        assertTrue(SetupNeeds.evaluate(INSTALL, 0L, "2026-10-21 00:48:12", true, true, VIENNA)
                .autostartMissing);
        // Müll / null
        assertTrue(SetupNeeds.evaluate(INSTALL, 0L, "null", true, true, VIENNA).autostartMissing);
        assertTrue(SetupNeeds.evaluate(INSTALL, 0L, "", true, true, VIENNA).autostartMissing);
    }

    @Test
    public void noBydAutostartManagerMeansNothingToDoForAutostart() {
        assertFalse(SetupNeeds.evaluate(INSTALL, 0L, null, false, true, VIENNA).anything());
    }

    @Test
    public void missingOverlayAloneShowsTheDialog() {
        SetupNeeds n = SetupNeeds.evaluate(INSTALL, INSTALL, null, true, false, VIENNA);
        assertFalse(n.autostartMissing);
        assertTrue(n.overlayMissing);
        assertTrue(n.anything());
    }

    @Test
    public void dialogAndReceiverAreWired() throws Exception {
        String dlg = new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/overlay/SetupGuideDialog.java")), StandardCharsets.UTF_8);
        int fn = dlg.indexOf("public static boolean showIfNeeded(Context context)");
        // Entscheidung (inkl. „nichts fehlt“ → nichts zeigen) liegt in SetupNeeds.anzeige, vor jeder Anzeige.
        int check = dlg.indexOf("SetupNeeds.anzeige(", fn);
        // 0.1.10: „Erste Schritte“ übernimmt der Einrichtungsassistent; der Einzelhinweis bleibt.
        int show = dlg.indexOf("zeigeEinzelhinweis(context, needs);", fn);
        assertTrue(fn > 0 && check > fn && check < show);
        assertTrue(dlg.contains("markAutoStartOkForCurrentInstall(context);\n")
                || dlg.contains("markAutoStartOkForCurrentInstall(context);\r\n"));
        String manifest = new String(Files.readAllBytes(Paths.get("src/main/AndroidManifest.xml")),
                StandardCharsets.UTF_8);
        int r = manifest.indexOf("receiver.AutoStartCheckReceiver");
        assertTrue(r > 0);
        int end = manifest.indexOf("</receiver>", r);
        assertTrue("nur Shell/System dürfen senden",
                manifest.substring(r, end).contains("android:permission=\"android.permission.DUMP\""));
        String script = new String(Files.readAllBytes(Paths.get("../deploy/autostart-check.sh")),
                StandardCharsets.UTF_8);
        assertTrue(script.contains("settings put global panoptes_autostart_ok"));
        assertTrue(script.contains(SetupNeeds.GLOBAL_AUTOSTART_OK));
        assertFalse("nie den Log-Puffer löschen", script.contains("logcat -c"));
    }
}
