package at.greenchili.surfmate.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Hinweis „Bildschirm wird geteilt“: erscheint mit der Fernansicht, geht mit ihr, in allen 5 Sprachen. */
public class TeilenHinweisTest {

    private static String lies(String p) throws Exception {
        return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8);
    }

    @Test
    public void erscheintBeimStartUndGehtBeimEnde() {
        TeilenHinweisRegel r = new TeilenHinweisRegel();
        assertFalse(r.sichtbar(0));
        r.gestartet(1_000);
        assertTrue(r.sichtbar(1_000));
        r.zugriff(200_000);
        assertTrue(r.sichtbar(200_000 + TeilenHinweisRegel.LEERLAUF_MS));
        r.beendet();
        assertFalse(r.sichtbar(200_001));
    }

    @Test
    public void verschwindetOhneZugriffeNachDerSitzungsZeit() {
        TeilenHinweisRegel r = new TeilenHinweisRegel();
        r.gestartet(0);
        assertTrue(r.sichtbar(TeilenHinweisRegel.LEERLAUF_MS));
        assertFalse(r.sichtbar(TeilenHinweisRegel.LEERLAUF_MS + 1));
        // Zugriff nach Ablauf bringt ihn nicht zurück, nur ein neuer Start
        r.zugriff(TeilenHinweisRegel.LEERLAUF_MS + 2);
        assertFalse(r.sichtbar(TeilenHinweisRegel.LEERLAUF_MS + 3));
        assertTrue("länger als die Sitzungs-Leerlaufzeit im Daemon (5 min)",
                TeilenHinweisRegel.LEERLAUF_MS > 5 * 60 * 1000L);
    }

    @Test
    public void bruekeZeigtHinweisBeiStartUndNimmtIhnBeiStopp() throws Exception {
        String s = lies("src/main/java/at/greenchili/surfmate/remote/RemoteDevViewBridgeService.kt");
        int launch = s.indexOf("\"launch\" ->");
        int stop = s.indexOf("\"stop\" ->");
        assertTrue(launch > 0 && stop > 0);
        assertTrue(s.indexOf("TeilenHinweis.gestartet(", launch) > launch
                && s.indexOf("TeilenHinweis.gestartet(", launch) < s.indexOf("RemoteDevVirtualDisplay.start(", launch));
        assertTrue(s.indexOf("TeilenHinweis.beendet()", stop) > stop
                && s.indexOf("TeilenHinweis.beendet()", stop) < s.indexOf("RemoteDevVirtualDisplay.stop()", stop));
        assertTrue(s.contains("if (!launch.success) TeilenHinweis.beendet()"));
        assertTrue(s.contains("if (command in FERNANSICHT_BEFEHLE) TeilenHinweis.zugriff()"));
        String c = lies("src/main/java/at/greenchili/surfmate/server/RemoteDevViewBridgeClient.java");
        assertTrue(c.contains("if (launchActivity) request.put(\"hinweis\", teilenHinweis())"));
        String h = lies("src/main/java/at/greenchili/surfmate/remote/TeilenHinweis.java");
        assertTrue("nicht berührbar", h.contains("FLAG_NOT_TOUCHABLE"));
        assertTrue("oben", h.contains("Gravity.TOP"));
    }

    @Test
    public void textInAllenFuenfSprachen() throws Exception {
        String[] erwartet = {"Bildschirm wird geteilt", "Screen is being shared", "Écran en cours de partage",
                "Schermo condiviso", "Scherm wordt gedeeld"};
        String[] sprachen = {"de", "en", "fr", "it", "nl"};
        for (int i = 0; i < sprachen.length; i++) {
            JSONObject k = new JSONObject(lies("src/main/assets/server-i18n/" + sprachen[i] + ".json"));
            JSONObject d = k.optJSONObject("display");
            assertNotNull(sprachen[i], d);
            assertEquals(sprachen[i], erwartet[i], d.optString("teilen_hinweis"));
        }
    }
}
