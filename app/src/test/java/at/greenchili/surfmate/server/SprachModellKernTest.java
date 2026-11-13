package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.sprache.SprachModellLaden;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 0.1.43.2 (Fix E2E M2/G1) Sprachmodell: Der Kern lädt nicht selbst (ab Android 11 darf er den App-Ordner nicht anlegen),
 * er startet nur auf Tipp den App-Dienst (Brücke am start-foreground-service, Dienst nur mit DUMP), reicht die Datei aus
 * Download an die App weiter und zeigt den Stand, den die App meldet.
 */
public class SprachModellKernTest {

    private final List<String> befehle = new ArrayList<>();
    private String antwort = "Starting service: Intent { cmp=at.greenchili.surfmate/.sprache.SprachModellDienst }";
    private SprachModellKern.Shell shellVorher;
    private File downloadVorher, ordner;

    @Before
    public void auf() throws Exception {
        shellVorher = SprachModellKern.shell;
        downloadVorher = SprachModellKern.download;
        ordner = Files.createTempDirectory("download").toFile();
        SprachModellKern.download = ordner;
        SprachModellKern.shell = b -> { befehle.add(b); return antwort; };
        SprachModellKern.vergessen();
    }

    @After
    public void ab() {
        SprachModellKern.shell = shellVorher;
        SprachModellKern.download = downloadVorher;
        SprachModellKern.vergessen();
        for (File f : ordner.listFiles()) f.delete();
        ordner.delete();
    }

    @Test
    public void ladenStartetNurDenAppDienstUeberDieBruecke() throws Exception {
        JSONObject r = SprachModellKern.starten("de", "netz", 1000);
        assertTrue(r.getBoolean("ok"));
        assertEquals(1, befehle.size());
        assertEquals("am start-foreground-service -n at.greenchili.surfmate/.sprache.SprachModellDienst --es aktion laden --es sprache de --es quelle netz",
                befehle.get(0));
        assertEquals("laeuft_schon", SprachModellKern.starten("en", "netz", 2000).getString("grund"));
        assertEquals("nach dem Herzschlag ohne Meldung nicht mehr „läuft“", true,
                SprachModellKern.starten("en", "netz", 1000 + SprachModellKern.HERZSCHLAG_MS + 1).getBoolean("ok"));
    }

    @Test
    public void ungueltigeEingabenOhneShell() throws Exception {
        assertEquals("sprache", SprachModellKern.starten("fr", "netz", 1).getString("grund"));
        assertEquals("sprache", SprachModellKern.starten("de; rm -rf /", "netz", 1).getString("grund"));
        assertEquals("datei_fehlt", SprachModellKern.starten("de", "hand", 1).getString("grund"));
        assertTrue("nichts ausgeführt", befehle.isEmpty());
        // fremde Quelle wird „netz“ (feste Vosk-URL), nie eine fremde Adresse
        assertTrue(SprachModellKern.starten("de", "http://boese", 1).getBoolean("ok"));
        assertTrue(befehle.get(0).endsWith("--es quelle netz"));
    }

    @Test
    public void dienstFehltIstEinEigenerGrund() throws Exception {
        antwort = "Error: Not found; no service started.";
        assertEquals("kein_dienst", SprachModellKern.starten("de", "netz", 1).getString("grund"));
    }

    @Test
    public void handDateiAusDownload() throws Exception {
        SprachModellLaden.Modell de = SprachModellLaden.MODELLE.get("de");
        assertFalse(SprachModellKern.stand(1).getJSONObject("modelle").getJSONObject("de").getBoolean("handDatei"));
        Files.write(new File(ordner, "vosk-model-small-de-0.15.zip").toPath(), new byte[] {1, 2, 3});
        assertEquals(new File(ordner, "vosk-model-small-de-0.15.zip"), SprachModellKern.handDatei(de));
        assertTrue(SprachModellKern.stand(2).getJSONObject("modelle").getJSONObject("de").getBoolean("handDatei"));
        assertTrue(SprachModellKern.starten("de", "hand", 3).getBoolean("ok"));
        assertTrue(befehle.get(befehle.size() - 1).endsWith("--es sprache de --es quelle hand"));
        assertEquals("/storage/emulated/0/Download", SprachModellKern.DOWNLOAD);
    }

    @Test
    public void standKommtVonDerAppUndWirdBereinigt() throws Exception {
        assertFalse(SprachModellKern.modellDa("de"));
        JSONObject roh = new JSONObject().put("laeuft", true)
                .put("laden", new JSONObject().put("sprache", "de").put("phase", "laedt").put("grund", "<script>").put("bytes", 500).put("gesamt", 1000))
                .put("modelle", new JSONObject().put("de", new JSONObject().put("da", true)).put("xx", new JSONObject().put("da", true)));
        SprachModellKern.melden(roh, 10);
        assertTrue(SprachModellKern.modellDa("de"));
        assertFalse(SprachModellKern.modellDa("en"));
        JSONObject s = SprachModellKern.stand(11);
        assertEquals("script", s.getJSONObject("laden").getString("grund"));
        assertEquals(500, s.getJSONObject("laden").getLong("bytes"));
        assertFalse(s.getJSONObject("modelle").has("xx"));
        assertEquals("app", s.getString("ort"));
    }

    @Test
    public void standFragtDieAppHoechstensJedeMinute() throws Exception {
        SprachModellKern.stand(SprachModellKern.STAND_ABFRAGE_MS + 1);
        SprachModellKern.stand(SprachModellKern.STAND_ABFRAGE_MS + 2);
        assertEquals(1, befehle.size());
        assertTrue(befehle.get(0).endsWith("--es aktion stand"));
        assertFalse("Stand fragen startet nie ein Laden", befehle.get(0).contains("aktion laden"));
    }

    @Test
    public void routenNurDieAppUndDienstNurMitDump() throws Exception {
        assertTrue(SprachApiHandler.methodeErlaubt(SprachApiHandler.MODELL_PFAD, "GET"));
        assertTrue(SprachApiHandler.methodeErlaubt(SprachApiHandler.MODELL_DATEI_PFAD, "GET"));
        assertFalse(SprachApiHandler.methodeErlaubt(SprachApiHandler.MODELL_DATEI_PFAD, "POST"));
        assertFalse(SprachApiHandler.methodeErlaubt(SprachApiHandler.MODELL_STAND_PFAD, "GET"));
        String h = new String(Files.readAllBytes(lies("app/src/main/java/at/greenchili/surfmate/server/SprachApiHandler.java").toPath()),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        int pruef = h.indexOf("SprachAufrufer.pruefe()");
        assertTrue("Aufrufer-Prüfung vor Datei und Stand", pruef > 0 && pruef < h.indexOf("if (MODELL_DATEI_PFAD.equals(p)) { datei(path, out); return true; }")
                && pruef < h.indexOf("SprachModellKern.melden(lesen(body)"));
        assertTrue("nur passende Datei, nicht größer als erwartet", h.contains("if (f == null || !f.isFile() || f.length() > m.zipBytes) {"));
        String man = new String(Files.readAllBytes(lies("app/src/main/AndroidManifest.xml").toPath()), StandardCharsets.UTF_8);
        String e = man.substring(man.indexOf("sprache.SprachModellDienst\""));
        e = e.substring(0, e.indexOf("/>"));
        assertTrue(e.contains("android:exported=\"true\"") && e.contains("android:permission=\"android.permission.DUMP\""));
        // Weg von Hand passt zum neuen Ort (Download), in 5 Sprachen
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject t = new JSONObject(new String(Files.readAllBytes(lies("app/src/main/assets/web/i18n/" + l + ".json").toPath()),
                    StandardCharsets.UTF_8)).getJSONObject("sprachmodell");
            assertTrue(l, t.getString("hand").contains("/sdcard/Download") && t.getString("hand").contains("{datei}"));
            assertFalse(l, t.getString("hand").contains("Android/data"));
            for (String k : new String[] {"fehler_ordner", "fehler_sprache", "fehler_dienst", "fehler_datei", "aus_download"})
                assertTrue(l + ": " + k, t.optString(k, "").length() > 5);
        }
    }

    private static File lies(String rel) {
        File f = new File(rel);
        return f.isFile() ? f : new File(rel.replaceFirst("^app/", ""));
    }
}
