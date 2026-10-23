package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.1.16: Jede Seite aus dem Menü der Web-Hülle (app-shell.js) braucht eine Route im HttpServer, sonst zeigt die App
 * „Not Found“ (Emulator-Abnahme 23.10.: camping.html fehlte nach dem Merge von camping-0116).
 */
public class MenueSeitenRoutenTest {

    static String lies(String rel) throws Exception {
        File f = new File(rel);
        if (!f.isFile()) f = new File("app/" + rel);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void jedeMenueSeiteHatEineRoute() throws Exception {
        String shell = lies("src/main/assets/web/shared/app-shell.js");
        String server = lies("src/main/java/at/greenchili/surfmate/server/HttpServer.java");
        Matcher m = Pattern.compile("href: '([a-z0-9_-]+\\.html)'").matcher(shell);
        List<String> fehlt = new ArrayList<>();
        int n = 0;
        while (m.find()) {
            n++;
            String seite = m.group(1);
            if (!server.contains("\"/" + seite + "\"")) fehlt.add(seite);
            assertTrue(seite, new File(new File("src/main/assets/web/local").isDirectory()
                    ? "src/main/assets/web/local" : "app/src/main/assets/web/local", seite).isFile());
        }
        assertTrue("Menü gefunden", n > 5);
        assertEquals("Seiten ohne Route im HttpServer", "[]", fehlt.toString());
    }
}
