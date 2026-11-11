package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.1.41 (G6 aus E2E 0.1.40): Das Demo-Summary des Launchers hat `naviApp` wie der echte Kern
 * ({@code LauncherApiHandler.buildNaviApp}), sonst sagt die Navi-App-Wahl im Demo-Profil „Keine Navi-App gefunden“.
 * Die Liste „bekannt“ entspricht {@code TachoNaviApp.naviPakete()} (ohne Panoptes selbst, sortiert).
 */
public class DemoNaviAppTest {

    private static String lies(String rel) throws Exception {
        File f = new File(rel);
        if (!f.exists()) f = new File("app/" + rel);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void demoSummaryHatNaviAppWieDerKern() throws Exception {
        JSONObject root = new JSONObject(lies("src/main/assets/demo/launcher_summary.json"));
        JSONObject n = root.getJSONObject("naviApp");
        assertTrue("keine Wahl im Demo", n.isNull("gewaehlt"));
        JSONArray a = n.getJSONArray("bekannt");
        List<String> demo = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) demo.add(a.getString(i));

        String q = lies("src/main/java/at/greenchili/surfmate/tacho/TachoNaviApp.java");
        String block = q.substring(q.indexOf("NAVI_PAKETE = "), q.indexOf(")));", q.indexOf("NAVI_PAKETE = ")));
        List<String> kern = new ArrayList<>();
        Matcher m = Pattern.compile("\"([a-z][a-z0-9_.]+)\"").matcher(block);
        while (m.find()) kern.add(m.group(1));
        kern.remove("at.greenchili.surfmate");
        Collections.sort(kern);
        assertTrue(kern.size() >= 5);
        assertEquals(kern, demo);
    }
}
