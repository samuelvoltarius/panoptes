package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 0.1.39 A1 (nach Surf-Bestätigung 0.1.38.14): Die alte native Oberfläche ist gelöscht. Kein Code navigiert mehr zu
 * einer gelöschten Nav-ID, im Nav-Graphen stehen nur noch Web-Start, Projektion und BYD-Cloud.
 */
public class AltDesignGeloeschtTest {

    static final String[] GELOESCHT = {
            "dashboardFragment", "genAiFragment", "liveViewFragment", "waechterFragment", "naviFragment", "systemFragment",
            "recordingsFragment", "parkingFragment", "surveillanceSettingsWebFragment", "recordingSettingsWebFragment",
            "vehicleControlFragment", "tripsFragment", "chargingFragment", "automationsFragment", "seatPositionsFragment",
            "keyMappingFragment", "integrationsFragment", "telegramSettingsFragment", "abrpSettingsFragment", "mqttFragment",
            "roadSenseFragment", "networkFragment", "diagnosticsFragment", "adbConsoleFragment", "settingsFragment",
            "daemonsFragment", "settingsAboutFragment", "settingsSecurityFragment", "videoPlayerFragment"};

    @Test
    public void navGraphNurNochDreiZiele() throws Exception {
        String nav = lies("src/main/res/navigation/nav_graph.xml");
        List<String> ids = new ArrayList<>();
        Matcher m = Pattern.compile("<(fragment|activity)\\b[^>]*android:id=\"@\\+id/(\\w+)\"").matcher(nav);
        while (m.find()) ids.add(m.group(2));
        assertEquals(java.util.Arrays.asList("panoptesStartFragment", "projectionFragment", "bydCloudFragment"), ids);
        assertFalse("kein globaler Sprung in den alten Player", nav.contains("action_global_videoPlayer"));
    }

    @Test
    public void keineNavigationZuGeloeschtenIds() throws Exception {
        List<String> treffer = new ArrayList<>();
        try (Stream<Path> st = Files.walk(pfad("src/main"))) {
            st.filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".kt") || n.endsWith(".java") || n.endsWith(".xml");
            }).forEach(p -> {
                String s;
                try { s = new String(Files.readAllBytes(p), StandardCharsets.UTF_8); } catch (Exception e) { return; }
                for (String id : GELOESCHT) {
                    if (s.contains("R.id." + id) || s.contains("@id/" + id) || s.contains("@+id/" + id)) {
                        treffer.add(p.getFileName() + ":" + id);
                    }
                }
            });
        }
        assertTrue("Verweise auf gelöschte Ziele: " + treffer, treffer.isEmpty());
    }

    @Test
    public void alteDateienSindWeg() {
        String f = "src/main/java/at/greenchili/surfmate/ui/fragment/";
        for (String d : new String[]{f + "DashboardFragment.kt", f + "RecordingsFragment.kt", f + "VideoPlayerFragment.kt",
                f + "SettingsFragment.kt", f + "settings/SettingsAboutFragment.kt",
                "src/main/java/at/greenchili/surfmate/onboarding/ExpertTourCoach.kt",
                "src/main/java/at/greenchili/surfmate/onboarding/DashboardTourCoach.kt",
                "src/main/res/menu/rail_menu.xml", "src/main/res/layout/fragment_dashboard.xml"}) {
            assertFalse(d, Files.exists(pfad(d)));
        }
        // Funktionsflächen bleiben
        for (String d : new String[]{f + "ProjectionFragment.kt", "src/main/java/at/greenchili/surfmate/ui/PinLockActivity.kt",
                "src/main/java/at/greenchili/surfmate/DeterrentActivity.java",
                "src/main/java/at/greenchili/surfmate/navmap/RoadSenseMapActivity.kt"}) {
            assertTrue(d, Files.exists(pfad(d)));
        }
    }

    private static Path pfad(String rel) {
        Path p = Paths.get(rel);
        return Files.exists(p) || Files.exists(Paths.get("src")) ? p : Paths.get("app").resolve(rel);
    }

    private static String lies(String rel) throws Exception {
        return new String(Files.readAllBytes(pfad(rel)), StandardCharsets.UTF_8);
    }
}
