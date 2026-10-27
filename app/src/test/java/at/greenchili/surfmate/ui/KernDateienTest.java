package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 0.1.20: ungestylte Seite nach ACC an erkennen und mit Backoff neu laden; Proxy-503 protokollieren. */
public class KernDateienTest {

    @Test
    public void urteilAusDemJavascriptErgebnis() {
        assertEquals(KernDateien.OK, KernDateien.urteil("\"ok\""));
        assertEquals(KernDateien.FEHLT, KernDateien.urteil("\"fehlt:css\""));
        assertEquals(KernDateien.FEHLT, KernDateien.urteil("\"fehlt:cssjs\""));
        assertEquals(KernDateien.EGAL, KernDateien.urteil("\"egal\""));
        assertEquals(KernDateien.EGAL, KernDateien.urteil("null"));
        assertEquals(KernDateien.EGAL, KernDateien.urteil(null));
    }

    @Test
    public void backoffBegrenzt() {
        assertEquals(2_000L, KernDateien.warteMs(0));
        assertEquals(4_000L, KernDateien.warteMs(1));
        assertEquals(30_000L, KernDateien.warteMs(4));
        assertEquals(-1L, KernDateien.warteMs(KernDateien.MAX_VERSUCHE));
        assertEquals(-1L, KernDateien.warteMs(-1));
    }

    @Test
    public void webViewPrueftNachDemLadenUndProtokolliertJede503() throws Exception {
        String w = lies("app/src/main/java/at/greenchili/surfmate/ui/fragment/WebViewFragment.kt");
        int fertig = w.indexOf("override fun onPageFinished(view: WebView?, url: String?)");
        int pruef = w.indexOf("kernDateienPruefen(view)", fertig);
        assertTrue(fertig > 0 && pruef > fertig);
        assertTrue(w.contains("android.util.Log.w(\"WebViewProxy\", \"Proxy-503 (\" + reason"));
        int c = w.indexOf("android.util.Log.e(\"WebViewProxy\", \"Failed: $url - ${e.message}\")");
        int speicher = w.indexOf("AssetSpeicher.holen(url)?.let { c ->", c);
        int antwort = w.indexOf("return fehlerAntwort(request, e.message ?: \"connection failed\")", c);
        assertTrue("erst Speicher/Wiederholen, dann 503", c > 0 && speicher > c && antwort > speicher);
        assertTrue(KernDateien.PRUEF_JS.contains("--primary") && KernDateien.PRUEF_JS.contains("window.BYD"));
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
