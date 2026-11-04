package at.greenchili.surfmate.server;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 0.1.38.8: Die Launcher-Zusammenfassung liefert die Akku-Gesundheit wie die Web-Akku-Karte doppelt: BYD-Index
 * ({@code sohAutoPct}) und eigene Schätzung ({@code sohSchaetzungPct}), je null wenn nicht vorhanden; der native
 * Fahrzeug-Dialog zeigt beide nebeneinander.
 */
public class LauncherSohWerteTest {
    private static String lies(String pfad) throws Exception {
        return new String(Files.readAllBytes(Paths.get(pfad)), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    public void zusammenfassungUndDialogZeigenBeideWerte() throws Exception {
        String handler = lies("src/main/java/at/greenchili/surfmate/server/LauncherApiHandler.java");
        assertTrue(handler.contains("o.put(\"sohAutoPct\", sohAutoPct);"));
        assertTrue(handler.contains("o.put(\"sohSchaetzungPct\", sohSchaetzungPct);"));
        assertTrue("Schätzung nur über den beschrifteten Getter (CanonicalSohConsumerAssetTest)",
                handler.contains("soh.getSchaetzungNebenSoh()"));
        assertTrue(lies("src/main/java/at/greenchili/surfmate/abrp/SohEstimator.java")
                .contains("public double getOemSohPercent() { return readOemSohPercent(); }"));
        String dialog = lies("src/main/java/at/greenchili/surfmate/ui/fragment/DashboardFragment.kt");
        assertTrue(dialog.contains("finalOemSoh > 0 || finalEstimatedSoh > 0 -> {"));
        assertTrue(dialog.contains("getString(R.string.vehicle_dialog_soh_erklaerung)"));
        for (String d : new String[] {"values", "values-de", "values-fr", "values-it", "values-nl"}) {
            assertTrue(d, lies("src/main/res/" + d + "/strings.xml").contains("name=\"vehicle_dialog_soh_beide\"")
                    && lies("src/main/res/" + d + "/strings.xml").contains("name=\"vehicle_dialog_soh_erklaerung\""));
        }
    }
}
