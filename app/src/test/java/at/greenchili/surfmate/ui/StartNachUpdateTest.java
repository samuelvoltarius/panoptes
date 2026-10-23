package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.updater.NachUpdateStart;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hotfix 0.1.15.1: (a) Ansicht „Panoptes startet …“ mit Abfrage und Neuladen, (c) nie rohes JSON als Hauptseite,
 * (d) Neustart nach MY_PACKAGE_REPLACED an IDE_START statt ENTWICKLUNG, entprellt gegen update-on-car.
 */
public class StartNachUpdateTest {

    private static String lies(String rel) throws Exception {
        File f = new File(rel);
        if (!f.exists()) f = new File("app/" + rel);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    static final String WVF = "src/main/java/at/greenchili/surfmate/ui/fragment/WebViewFragment.kt";

    // ------------------------------------------------------------- (a) Warten und Neuladen

    @Test
    public void wartetAlleZweiSekundenHoechstensDreiMinuten() {
        assertEquals(2_000L, StartWarten.INTERVALL_MS);
        assertEquals(180_000L, StartWarten.HOECHSTENS_MS);
        StartWarten w = new StartWarten(1_000);
        assertEquals(StartWarten.Schritt.WEITER_WARTEN, w.nachAbfrage(3_000, false));
        assertEquals(StartWarten.Schritt.NEU_LADEN, w.nachAbfrage(5_000, true));
        assertEquals(StartWarten.Schritt.WEITER_WARTEN, w.nachAbfrage(1_000 + 179_999, false));
        assertEquals(StartWarten.Schritt.AUFGEBEN, w.nachAbfrage(1_000 + 180_000, false));
        // antwortet der Server genau am Ende doch noch, wird geladen statt aufgegeben
        assertEquals(StartWarten.Schritt.NEU_LADEN, w.nachAbfrage(1_000 + 180_000, true));
    }

    @Test
    public void startFehlerNurHauptseiteMit5xx() {
        assertTrue(StartWarten.istStartFehler(true, 503));
        assertTrue(StartWarten.istStartFehler(true, 500));
        assertFalse(StartWarten.istStartFehler(false, 503));     // Unterressource: Seite regelt das selbst
        assertFalse(StartWarten.istStartFehler(true, 404));
        assertFalse(StartWarten.istStartFehler(true, 200));
        assertFalse(StartWarten.istStartFehler(true, 0));
    }

    @Test
    public void antwortetHeisstEchteAntwortUnter500() {
        assertTrue(StartWarten.antwortet(200));
        assertTrue(StartWarten.antwortet(302));
        assertTrue(StartWarten.antwortet(401));                   // Login-Seite: Server läuft
        assertFalse(StartWarten.antwortet(503));
        assertFalse(StartWarten.antwortet(-1));
    }

    @Test
    public void wurzelNurLokal() {
        assertEquals("http://127.0.0.1:8180/", StartWarten.wurzel("http://127.0.0.1:8180/index.html?x=1"));
        assertEquals("http://localhost:8180/", StartWarten.wurzel("http://localhost:8180"));
        assertNull(StartWarten.wurzel("https://example.com/"));
        assertNull(StartWarten.wurzel("http://127.0.0.1.evil:8180/"));
        assertNull(StartWarten.wurzel(null));
    }

    @Test
    public void fragmentFragtAbUndLaedtNeu() throws Exception {
        String k = lies(WVF);
        assertTrue(k.contains("override fun onReceivedHttpError("));
        assertTrue(k.contains("StartWarten.istStartFehler(\n                            request?.isForMainFrame == true, errorResponse?.statusCode ?: 0)"));
        // onReceivedError der Hauptseite führt ebenfalls in die Startansicht
        Matcher m = Pattern.compile("override fun onReceivedError\\([\\s\\S]*?\\n                }").matcher(k);
        assertTrue(m.find());
        assertTrue(m.group().contains("zeigeStart()"));
        // Reihenfolge-fest: onPageFinished der Ersatzseite schaltet auf die Startansicht (Emulator 23.10.: WebView
        // meldet onReceivedHttpError vor onPageStarted, das pageLoadFailed wieder zurücksetzt)
        int pf = k.indexOf("override fun onPageFinished(");
        int guard = k.indexOf("if (url != null && url == startSeiteFuer) {", pf);
        assertTrue(pf > 0 && guard > pf && guard < k.indexOf("if (!pageLoadFailed) {", pf));
        assertTrue(k.substring(guard, guard + 200).contains("zeigeStart()"));
        assertTrue(k.contains("if (request?.isForMainFrame == true && connection.responseCode < 500) startSeiteFuer = null"));
        // Abfrage direkt (ohne Proxy), Neuladen über retryLoad, Aufgeben zeigt den Knopf
        assertTrue(k.contains("openConnection(java.net.Proxy.NO_PROXY)"));
        assertTrue(k.contains("StartWarten.Schritt.NEU_LADEN -> retryLoad()"));
        assertTrue(k.contains("btnStartRetry?.visibility = View.VISIBLE"));
        assertTrue(k.contains("startHinweis?.visibility = View.GONE"));      // nach Aufgeben kein „lädt von selbst“
        // Aufräumen beim Abbau der Ansicht
        int d = k.indexOf("override fun onDestroyView()");
        assertTrue(k.indexOf("beendeStart()", d) > d);
        String layout = lies("src/main/res/layout/fragment_webview.xml");
        for (String id : new String[]{"startOverlay", "startTitel", "startHinweis", "startSpinner", "btnStartRetry"}) {
            assertTrue(id, layout.contains("@+id/" + id));
        }
        assertTrue(layout.contains("@mipmap/ic_panoptes"));
        assertTrue(layout.contains("android:textColor=\"#FFF2F4F7\""));   // hell auf dunkel
    }

    // ------------------------------------------------------------- (c) nie JSON als Hauptseite

    @Test
    public void synthesize503NurFuerUnterressourcen() throws Exception {
        String k = lies(WVF);
        // einziger Aufruf von synthesize503 liegt in fehlerAntwort, und dort nur für Nicht-Hauptseiten
        Matcher m = Pattern.compile("(?<!fun )synthesize503\\(").matcher(k);
        int n = 0;
        while (m.find()) n++;
        assertEquals(1, n);
        assertTrue(k.contains("if (request?.isForMainFrame == true) startSeite503(request?.url?.toString(), reason)\n"
                + "                    else synthesize503(reason)"));
        assertFalse(k.contains("return synthesize503("));
        // 5xx des Servers für die Hauptseite wird ersetzt (kein JSON-Fehlerkörper durchreichen)
        assertTrue(k.contains("return startSeite503(url, \"HTTP ${connection.responseCode}\")"));
    }

    @Test
    public void startSeiteIstLesbaresHtml() {
        String h = StartWarten.startSeiteHtml("Panoptes startet …", "Die Seite lädt <von> selbst.");
        assertTrue(h.startsWith("<!doctype html>"));
        assertTrue(h.contains("Panoptes startet …"));
        assertTrue(h.contains("&lt;von&gt;"));
        assertTrue(h.contains("background:#101418;color:#f2f4f7"));
        assertFalse(h.contains("daemon_unreachable"));
        assertFalse(h.trim().startsWith("{"));
    }

    @Test
    public void texteInFuenfSprachen() throws Exception {
        for (String d : new String[]{"values", "values-de", "values-fr", "values-it", "values-nl"}) {
            String s = lies("src/main/res/" + d + "/strings.xml");
            for (String n : new String[]{"panoptes_startet", "panoptes_startet_hinweis", "panoptes_startet_zu_lange"}) {
                assertTrue(d + " " + n, s.contains("<string name=\"" + n + "\">"));
            }
        }
        assertTrue(lies("src/main/res/values-de/strings.xml")
                .contains("<string name=\"panoptes_startet\">Panoptes startet …</string>"));
    }

    // ------------------------------------------------------------- (d) Neustart nach Update

    @Test
    public void bootReceiverHaengtAnIdeStartStandardAus() throws Exception {
        String b = lies("src/main/java/at/greenchili/surfmate/receiver/BootReceiver.kt");
        int i = b.indexOf("Intent.ACTION_MY_PACKAGE_REPLACED ->");
        String block = b.substring(i, b.indexOf("Intent.ACTION_SCREEN_ON", i));
        assertTrue(block.contains("if (at.greenchili.surfmate.BuildConfig.IDE_START) {"));
        assertFalse(block.contains("BuildConfig.ENTWICKLUNG"));
        assertTrue(block.contains("putExtra(\"minimize_on_start\", true)"));
        String g = lies("build.gradle.kts");
        assertTrue(g.contains("buildConfigField(\"boolean\", \"IDE_START\",\n"
                + "            if ((project.findProperty(\"panoptesIdeStart\") as? String) == \"true\") \"true\" else \"false\")"));
        // nirgends sonst auf true gesetzt (debug/release/diagnose)
        assertEquals(1, g.split("\"IDE_START\"", -1).length - 1);
    }

    @Test
    public void entprellungJeProzessUndFenster() {
        NachUpdateStart.freigeben();
        long t = 1_000_000;
        assertTrue(NachUpdateStart.beanspruchen(t));                     // BootReceiver → onCreate
        assertFalse(NachUpdateStart.beanspruchen(t + 3_000));            // update-on-car am start → entprellt
        assertFalse(NachUpdateStart.beanspruchen(t + NachUpdateStart.FENSTER_MS - 1));
        assertTrue(NachUpdateStart.beanspruchen(t + NachUpdateStart.FENSTER_MS));
        NachUpdateStart.freigeben();                                     // lief doch nicht → nächster darf
        assertTrue(NachUpdateStart.beanspruchen(t + NachUpdateStart.FENSTER_MS + 1));
        NachUpdateStart.freigeben();
    }

    @Test
    public void mainActivityEntprelltVorDemHartenNeustart() throws Exception {
        String k = lies("src/main/java/at/greenchili/surfmate/ui/MainActivity.kt");
        int a = k.indexOf("private fun runDaemonStartupJetzt(");
        int ent = k.indexOf("NachUpdateStart.beanspruchen(", a);
        int noop = k.indexOf("if (!fromOnCreate && !isPostUpdate)", a);
        int reset = k.indexOf("UpdateLifecycle.hardResetDaemons(", a);
        assertTrue(a > 0 && ent > a && ent < noop && noop < reset);
        assertTrue(k.substring(ent, noop).contains("isPostUpdate = false"));
        // Anspruch zurück, wenn ein anderer Start schon läuft
        int flight = k.indexOf("another startup pass is in flight", a);
        assertTrue(k.substring(flight, flight + 300).contains("NachUpdateStart.freigeben()"));
    }

    @Test
    public void updateOnCarStartetMinimiert() throws Exception {
        for (String p : new String[]{"src/main/assets/deploy/update-on-car.sh", "../deploy/update-on-car.sh"}) {
            String s = lies(p);
            assertTrue(p, s.contains("--ez post_update true --ez minimize_on_start true"));
        }
    }
}
