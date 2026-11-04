package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 0.1.38.9 Schnellstart 2 (STARTZEIT_ANALYSE_01388): V4 Geräte-ID nicht mehr bis 60 s vor dem Start, asynchrone
 * Web-Brücke gegen das Einfrieren der Seite, V6/V7/V8 kürzere Wartezeiten, Hinweis "andere Werkzeug-App".
 */
public class Schnellstart2Test {

    private static String quelle(String rel) throws Exception {
        File f = new File(rel);
        if (!f.exists()) f = new File("app/" + rel);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static final String WVF = "src/main/java/at/greenchili/surfmate/ui/fragment/WebViewFragment.kt";

    @Test
    public void geraeteIdKeinLangesWartenVorDemStart() throws Exception {
        String d = quelle("src/main/java/at/greenchili/surfmate/util/DeviceIdGenerator.kt");
        assertTrue("kurze Frist statt 60 s", d.contains("const val SYNC_FRIST_MS = 8_000L"));
        assertTrue(d.contains("executor.executeSync(\"echo '$id' > $ID_FILE\", SYNC_FRIST_MS)"));
        assertTrue("schon geschrieben → kein ADB-Aufruf", d.contains("istSchonGeschrieben(context, id)"));
        assertTrue(d.contains("fun syncDeviceIdToFileSync(context: Context, erzwingen: Boolean = false)"));
        String m = quelle("src/main/java/at/greenchili/surfmate/ui/MainActivity.kt");
        assertTrue("Nachlauf heilt eine gelöschte Datei im Hintergrund",
                m.contains("syncDeviceIdToFileSync(this, erzwingen = true)") && m.contains("\"DeviceIdNachlauf\""));
    }

    @Test
    public void webBrueckeBlockiertDenJsThreadNicht() throws Exception {
        String w = quelle(WVF);
        assertTrue(w.contains("fun httpRequestAsyncWeb("));
        assertTrue("Rückruf an die Seite", w.contains("window.__pnHttpDone&&window.__pnHttpDone("));
        assertTrue("JS nutzt die asynchrone Brücke, sonst Rückfall auf die alte",
                w.contains("typeof AndroidBridge.httpRequestAsyncWeb === 'function'")
                        && w.contains("AndroidBridge.httpRequestAsyncWeb(fullUrl, method, body, JSON.stringify(headers), id);")
                        && w.contains("fertig(AndroidBridge.httpRequest(fullUrl, method, body, JSON.stringify(headers)));"));
        assertTrue("nur /api/ und /auth/ des lokalen Daemons",
                w.contains("url.path.startsWith(\"/api/\") || url.path.startsWith(\"/auth/\")"));
        assertTrue("Reihenfolge der Schreibaufrufe bleibt (eine Warteschlange), lange Aufrufe eigener Thread",
                w.contains("webHttpQueue.execute(arbeit)") && w.contains("\"WebHttpLang\""));
        assertTrue(w.contains("newSingleThreadExecutor"));
    }

    @Test
    public void kuerzereWartezeiten() throws Exception {
        assertEquals(1_000L, KernDateien.warteMs(0));
        assertEquals(15_000L, KernDateien.warteMs(4));
        String w = quelle(WVF);
        assertTrue("V7: 40 x 0,5 s dann 2 s", w.contains("if (attempt < 40) 500L else 2000L") && w.contains("attempt < 80"));
        String map = quelle("src/main/assets/web/shared/panoptes-map.js");
        assertTrue("V8: ohne Netz gleich Rückfallkarte", map.contains("navigator.onLine === false"));
    }

    @Test
    public void fremdAppHinweisAmLadebildschirm() throws Exception {
        String w = quelle(WVF);
        assertTrue(w.contains("private fun fremdPruefen(runde: Int, nochmal: Boolean = false)"));
        assertTrue(w.contains("FremdApps.abfrageSkript()") && w.contains("FremdApps.auswerten(roh)"));
        assertTrue("Protokollzeile für die Diagnose", w.contains("LauncherEreignisse.merken(\"FREMDE_WERKZEUG_APP\""));
        assertTrue(w.contains("R.string.panoptes_fremd_app"));
        String l = quelle("src/main/res/layout/fragment_webview.xml");
        assertTrue(l.contains("@+id/startFremd"));
        for (String d : new String[]{"values", "values-de", "values-fr", "values-it", "values-nl"}) {
            String s = quelle("src/main/res/" + d + "/strings.xml");
            assertTrue(d, s.contains("name=\"panoptes_fremd_app\"") && s.contains("%1$s"));
        }
    }
}
