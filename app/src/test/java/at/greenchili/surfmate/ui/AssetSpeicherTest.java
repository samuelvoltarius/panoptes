package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

/** 0.1.20 Tempo: statische /shared/-Dateien im Arbeitsspeicher der App-WebView-Weiche. */
public class AssetSpeicherTest {

    @After
    public void leeren() { AssetSpeicher.leeren(); }

    @Test
    public void nurStatischeSharedDateienOhneRange() {
        assertTrue(AssetSpeicher.geeignet("GET", "/shared/app-shell.js", null));
        assertTrue(AssetSpeicher.geeignet("GET", "/shared/fonts/x.woff2", ""));
        assertFalse(AssetSpeicher.geeignet("POST", "/shared/app-shell.js", null));
        assertFalse(AssetSpeicher.geeignet("GET", "/api/status", null));
        assertFalse(AssetSpeicher.geeignet("GET", "/system.html", null));
        assertFalse(AssetSpeicher.geeignet("GET", "/shared/models/surf.glb", null));
        assertFalse(AssetSpeicher.geeignet("GET", "/shared/x.js", "bytes=0-"));
        assertFalse(AssetSpeicher.geeignet("GET", "/shared/../config.json", null));
        assertFalse(AssetSpeicher.geeignet("GET", "/shared/seite.html", null));
    }

    @Test
    public void direktLadenNurAbM72OhneProxyUndNurStatisch() {
        assertEquals(74, AssetSpeicher.hauptversion("74.0.3729.186"));
        assertEquals(0, AssetSpeicher.hauptversion(null));
        assertEquals(0, AssetSpeicher.hauptversion("x"));
        assertTrue(AssetSpeicher.direkt("GET", "/shared/app-shell.js", null, 74, false));
        assertTrue("Modelle dürfen in den WebView-Cache", AssetSpeicher.direkt("GET", "/shared/models/surf.glb", null, 74, false));
        assertFalse("alte WebView", AssetSpeicher.direkt("GET", "/shared/app-shell.js", null, 58, false));
        assertFalse("Proxy für 127.0.0.1", AssetSpeicher.direkt("GET", "/shared/app-shell.js", null, 74, true));
        assertFalse(AssetSpeicher.direkt("GET", "/api/status", null, 74, false));
        assertFalse(AssetSpeicher.direkt("GET", "/index.html", null, 74, false));
        assertFalse(AssetSpeicher.direkt("GET", "/shared/x.mp4", "bytes=0-", 74, false));
        assertFalse(AssetSpeicher.direkt("POST", "/shared/x.js", null, 74, false));
    }

    @Test
    public void lruHaeltDieGrenzeUndLaesstZuGrosseWeg() {
        byte[] mb = new byte[1024 * 1024];
        for (int i = 0; i < 20; i++) AssetSpeicher.merken("u" + i, "application/javascript", "utf-8", Collections.<String, String>emptyMap(), mb);
        assertTrue(AssetSpeicher.belegt() <= AssetSpeicher.MAX_GESAMT);
        assertNull("älteste weg", AssetSpeicher.holen("u0"));
        assertNotNull(AssetSpeicher.holen("u19"));
        AssetSpeicher.merken("gross", "x", null, null, new byte[AssetSpeicher.MAX_EINTRAG + 1]);
        assertNull(AssetSpeicher.holen("gross"));
        AssetSpeicher.merken("u19", "x", null, null, new byte[10]);
        assertEquals(10, AssetSpeicher.holen("u19").daten.length);
    }

    @Test
    public void weicheFragtVorDerVerbindungUndMerktNurGanzGeleseneAntworten() throws Exception {
        String w = lies("app/src/main/java/at/greenchili/surfmate/ui/fragment/WebViewFragment.kt");
        int holen = w.indexOf("AssetSpeicher.holen(url)");
        int verbindung = w.indexOf("openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection", holen);
        assertTrue(holen > 0 && verbindung > holen);
        assertTrue(w.contains("if (it.size == length) at.greenchili.surfmate.ui.AssetSpeicher.merken(url, mime, encoding, headers, it)"));
        assertTrue(w.contains("assetCachebar && connection.responseCode == 200 && !splicedHtml"));
        int direkt = w.indexOf("AssetSpeicher.direkt(request?.method");
        assertTrue("direkt vor dem Abfangen", direkt > 0 && direkt < holen);
        assertTrue(w.contains("?.any { it.type() != java.net.Proxy.Type.DIRECT } ?: false"));
    }

    private static String lies(String rel) throws Exception {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (p != null) {
            for (Path c : new Path[] {p.resolve(rel), p.resolve(rel.replaceFirst("^app/", ""))}) {
                if (Files.isRegularFile(c)) return new String(Files.readAllBytes(c), "UTF-8");
            }
            p = p.getParent();
        }
        throw new java.io.FileNotFoundException(rel);
    }
}
