package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.ui.NavigateZiel;
import java.io.File;
import java.nio.file.Files;

/**
 * Fix R1 (0.1.43.4): /live-view?cam=N lieferte "Not Found", weil die statischen Seiten den Pfad samt Query
 * verglichen. Jetzt vergleicht die Kette pathOnly, und die Seite waehlt die Kamera aus der Query vor (ES5).
 */
public class LiveViewKameraVorwahlTest {

    private static String lies(String rel) throws Exception {
        File f = new File(rel);
        if (!f.isFile()) f = new File("app/" + rel);
        return new String(Files.readAllBytes(f.toPath()), "UTF-8");
    }

    @org.junit.Test
    public void statischeSeitenVergleichenNurDenPfadOhneQuery() throws Exception {
        String q = lies("src/main/java/at/greenchili/surfmate/server/HttpServer.java");
        int a = q.indexOf("// Static pages");
        int b = q.indexOf("path.equals(\"/api/i18n/lang\")");
        assertTrue(a > 0 && b > a);
        String kette = q.substring(a, b);
        assertTrue(kette.contains("pathOnly.equals(\"/live-view\")"));
        assertTrue(kette.contains("pathOnly.equals(\"/live-view.html\")"));
        // Kein exakter Vergleich mit dem rohen Pfad mehr (die Events-Zeile mit startsWith("...?") bleibt).
        assertFalse(kette.contains(" path.equals(\"/"));
        assertFalse(kette.contains("(path.equals(\"/"));
    }

    @org.junit.Test
    public void livePfadUndSeiteWaehlenDieKameraVor() throws Exception {
        assertEquals("/live-view?cam=3", NavigateZiel.livePfad("3"));
        String h = lies("src/main/assets/web/local/live-view.html");
        assertTrue(h.contains("function startKamera()"));
        assertTrue(h.contains("[?&]cam=([0-4])"));
        assertTrue(h.contains("selectCamera(startKamera())"));
        assertFalse(h.contains("await BYD.stream.selectCamera(0);"));
        // ES5 im neuen Block: kein const/let/Pfeil in startKamera
        int i = h.indexOf("function startKamera()");
        String block = h.substring(i, h.indexOf("return 0;", i));
        assertFalse(block.contains("const ")); assertFalse(block.contains("let ")); assertFalse(block.contains("=>"));
    }
}
