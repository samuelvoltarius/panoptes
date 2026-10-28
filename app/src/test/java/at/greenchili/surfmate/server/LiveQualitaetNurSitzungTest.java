package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 0.1.26.1: Live (Live-Ansicht, Brücke LIVE) ändert die gespeicherte Stream-Qualität nie dauerhaft. */
public class LiveQualitaetNurSitzungTest {

    @After
    public void aufraeumen() {
        StreamingApiHandler.sitzungZuruecksetzenFuerTest();
    }

    private static String setzeUeberLive(String stufe) throws Exception {
        Method m = StreamingApiHandler.class.getDeclaredMethod("handleSetStreamQuality", OutputStream.class, String.class);
        m.setAccessible(true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        m.invoke(null, out, stufe);
        return out.toString("UTF-8");
    }

    @Test
    public void liveWahlGiltNurFuerDieSitzung() throws Exception {
        StreamingApiHandler.setStreamingQuality("MEDIUM");   // gespeicherte Wahl der Einstellungsseite
        String antwort = setzeUeberLive("MAX");
        assertTrue(antwort, antwort.contains("\"nurSitzung\":true"));
        assertEquals("gespeichert bleibt", "MEDIUM", StreamingApiHandler.getStreamingQuality());
        assertEquals("gestreamt wird", "MAX", StreamingApiHandler.wirksameQualitaet());

        // Brücke LIVE: ULTRA_LOW, danach Zurückstellen – gespeichert bleibt immer MEDIUM
        setzeUeberLive("ULTRA_LOW");
        assertEquals("MEDIUM", StreamingApiHandler.getStreamingQuality());
        assertEquals("ULTRA_LOW", StreamingApiHandler.wirksameQualitaet());

        // eine neue Wahl auf der Einstellungsseite beendet die Sitzungs-Stufe
        StreamingApiHandler.setStreamingQuality("HIGH");
        assertEquals("HIGH", StreamingApiHandler.getStreamingQuality());
        assertEquals("HIGH", StreamingApiHandler.wirksameQualitaet());
    }

    @Test
    public void liveRuftKeinSpeichernMehr() throws Exception {
        String q = lies("src/main/java/at/greenchili/surfmate/server/StreamingApiHandler.java");
        int a = q.indexOf("private static void handleSetStreamQuality(");
        int b = q.indexOf("HttpResponse.sendJson(out, response.toString());", a);
        String rumpf = q.substring(a, b);
        assertFalse("kein persistSettings() im Live-Weg", rumpf.contains("QualitySettingsApiHandler.persistSettings();"));
        assertFalse("keine Zuweisung der gespeicherten Stufe", rumpf.contains("streamingQuality = "));
        int d = q.indexOf("private static void handleDisableStreaming(");
        assertTrue("Ende der Sitzung setzt zurück", q.indexOf("sitzungsQualitaet = null;", d) - d < 200);
        // persistiert wird nur die gespeicherte Stufe
        String qs = lies("src/main/java/at/greenchili/surfmate/server/QualitySettingsApiHandler.java");
        assertFalse(qs.contains("wirksameQualitaet"));
    }

    @Test
    public void ueberDieBrueckeKommtDerHinweisStattWebSocket() throws Exception {
        // Cookie-Merker der Brücke: BrueckeWegCookieTest
        String js = lies("src/main/assets/web/shared/stream.js");
        int s = js.indexOf("async startStream() {");
        assertTrue(js.indexOf("this._ueberBruecke()", s) > s && js.indexOf("this._ueberBruecke()", s) < js.indexOf("this.streamStarted = true;", s));
        assertTrue(js.contains("data.gespeichert || data.current"));
        for (String l : new String[]{"de", "en", "fr", "it", "nl"}) {
            String t = new JSONObject(lies("src/main/assets/web/i18n/" + l + ".json")).getJSONObject("stream").optString("ueber_bruecke", "");
            assertFalse(l, t.isEmpty());
            assertTrue(l, t.contains("Live"));
        }
    }

    static String lies(String pfad) throws Exception {
        File f = new File(pfad);
        if (!f.isFile()) f = new File("app/" + pfad);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
