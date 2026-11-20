package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.1.43.17: Nach dem Login nur auf eine Seite zurück, die es gibt, und nur auf diesem Gerät. Vorher führte ein unbekannter
 * Pfad (z. B. {@code /l}) nach dem Login auf „Not Found“, und {@code login.html} übernahm {@code redirect} ungeprüft
 * (offene Weiterleitung, {@code javascript:}).
 */
public class LoginZielTest {

    private static final Predicate<String> DATEIEN = rel -> rel.equals("local/hilfe.html") || rel.equals("shared/info.html");

    @Test
    public void unbekannterPfadFuehrtNachDemLoginAufDieStartseite() {
        assertEquals("/login.html", AuthMiddleware.loginUrl("/l", DATEIEN));
        assertEquals("/login.html", AuthMiddleware.loginUrl("/gibtsnicht.html", DATEIEN));
        assertEquals("/login.html", AuthMiddleware.loginUrl("/local/gibtsnicht.html", DATEIEN));
        assertEquals("ohne Dateiprüfung keine statische Seite", "/login.html", AuthMiddleware.loginUrl("/local/hilfe.html", null));
        assertEquals("Startseite braucht kein redirect", "/login.html", AuthMiddleware.loginUrl("/", DATEIEN));
        assertEquals("/login.html", AuthMiddleware.loginUrl("/index.html", DATEIEN));
    }

    @Test
    public void bekannteSeiteBleibtZiel() {
        assertEquals("/login.html?redirect=%2Fwaechter", AuthMiddleware.loginUrl("/waechter", DATEIEN));
        assertEquals("/login.html?redirect=%2Ftrips.html%3Fid%3D3", AuthMiddleware.loginUrl("/trips.html?id=3", DATEIEN));
        assertEquals("/login.html?redirect=%2Flocal%2Fhilfe.html", AuthMiddleware.loginUrl("/local/hilfe.html", DATEIEN));
        assertTrue(AuthMiddleware.loginZielBekannt("/shared/info.html", DATEIEN));
    }

    @Test
    public void nieAusserhalbOderDurchDenOrdnerHinauf() {
        assertFalse(AuthMiddleware.loginZielBekannt("//boese.example/waechter", DATEIEN));
        assertFalse(AuthMiddleware.loginZielBekannt("https://boese.example/", DATEIEN));
        assertFalse(AuthMiddleware.loginZielBekannt("javascript:alert(1)", DATEIEN));
        assertFalse(AuthMiddleware.loginZielBekannt("/local/../../etc/x.html", s -> true));
        assertFalse(AuthMiddleware.loginZielBekannt("/\\boese.example", DATEIEN));
        assertFalse(AuthMiddleware.loginZielBekannt("/wa\nechter", DATEIEN));
        assertFalse(AuthMiddleware.loginZielBekannt(null, DATEIEN));
        assertFalse("nur Seiten, keine Skripte", AuthMiddleware.loginZielBekannt("/local/app.js", s -> true));
        assertFalse("Prüfung wirft: nein", AuthMiddleware.loginZielBekannt("/local/x.html", s -> { throw new RuntimeException(); }));
    }

    /** Die Liste {@code SEITEN} deckt genau die Seiten-Pfade im Seiten-Teil von {@code HttpServer}. */
    @Test
    public void seitenListeWieImHttpServer() throws Exception {
        String h = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/HttpServer.java"));
        int von = h.indexOf("// Static pages");
        int bis = h.indexOf("path.startsWith(\"/shared/\") || path.startsWith(\"/local/\")", von);
        assertTrue(von > 0 && bis > von);
        Set<String> imServer = new TreeSet<>();
        for (String block : h.substring(von, bis).split("\\}\\s*else if")) {
            if (!block.contains("serveStaticFile(out, \"local/") || !block.contains(".html\")")) continue;
            Matcher m = Pattern.compile("pathOnly\\.equals\\(\"([^\"]+)\"\\)").matcher(block);
            while (m.find()) imServer.add(m.group(1));
        }
        assertTrue("Seiten im HttpServer nicht erkannt, Muster prüfen", imServer.size() > 40);
        assertEquals(imServer, new TreeSet<>(AuthMiddleware.SEITEN));
        assertTrue(h.contains("AuthMiddleware.setStatischDa(rel -> new File(WEB_ROOT, rel).isFile());"));
        String a = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/AuthMiddleware.java"));
        assertFalse("nie mehr jeden Pfad als Ziel", a.contains("String redirectUrl = \"/login.html?redirect=\" + urlEncode(path);"));
        assertTrue(a.contains("HttpResponse.sendRedirect(out, loginUrl(path, statischDa));"));
    }

    /** login.html prüft das Ziel selbst (Ablauf im Browser: Beleg mit node im Bericht 0.1.43.17). */
    @Test
    public void loginSeitePrueftDasZiel() throws Exception {
        String l = Quelltext.lies(Paths.get("src/main/assets/web/local/login.html"));
        assertTrue(l.contains("function sicheresLoginZiel(roh, origin)"));
        assertTrue(l.contains("redirectUrl = sicheresLoginZiel(urlParams.get('redirect'), window.location.origin);"));
        assertTrue("über URL und Origin-Vergleich", l.contains("new URL(roh, origin)") && l.contains("u.origin !== origin"));
        assertTrue("genau ein / vorne", l.contains("roh.charAt(0) !== '/' || roh.charAt(1) === '/'"));
        assertTrue("kein Backslash", l.contains("roh.indexOf('\\\\') >= 0"));
        assertFalse("nie mehr ungeprüft", l.contains("redirectUrl = decodeURIComponent(urlParams.get('redirect'))"));
    }
}
