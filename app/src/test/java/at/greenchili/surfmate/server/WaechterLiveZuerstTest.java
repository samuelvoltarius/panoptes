package at.greenchili.surfmate.server;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 0.1.26.1: Im Reiter „Wächter“ kommt Live zuerst, ein Tipp auf „Wächter“ öffnet das Live-Bild. */
public class WaechterLiveZuerstTest {

    @Test
    public void liveIstDieErsteSeiteDesWaechters() throws Exception {
        String js = LiveQualitaetNurSitzungTest.lies("src/main/assets/web/shared/app-shell.js");
        int g = js.indexOf("{ id: 'guard'");
        String[] reihe = {"live-view.html", "waechter.html", "events.html", "parking.html", "communicate.html",
                "surveillance.html", "recording.html", "notifications.html"};
        int vorher = g;
        for (String s : reihe) {
            int i = js.indexOf("href: '" + s + "'", g);
            assertTrue(s, i > vorher);
            vorher = i;
        }
        // die Leiste verlinkt die erste Seite des Abschnitts
        assertTrue(js.contains("s.pages[0].href"));
    }
}
