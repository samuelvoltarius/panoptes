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

    // 0.1.39 A1: zusammenfassungUndDialogZeigenBeideWerte entfällt – die alte native Oberfläche ist gelöscht (DashboardFragment)
}
