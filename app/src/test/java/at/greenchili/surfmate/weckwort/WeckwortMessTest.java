package at.greenchili.surfmate.weckwort;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.einstellungen.Feld;
import at.greenchili.surfmate.einstellungen.Katalog;
import at.greenchili.surfmate.server.ExternGuard;
import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Weckwort-Messung Stufe 1: Regeln, Zahlen, Datenschutz und „keine Befehle“ als Quelltext-Prüfung. */
public class WeckwortMessTest {

    // ------------------------------------------------------------------ Schalter und Start-Regeln

    @Test
    public void schalterIstAbWerkAusUndAusDerFerneNurLesbar() throws Exception {
        Feld f = Katalog.finde(Katalog.felder(new Katalog.Kontext()), WeckwortMessRegeln.SCHALTER);
        assertEquals(Boolean.FALSE, f.standard);
        assertTrue("Kopplung darf nicht einschalten", f.nurLesendExtern);
        assertEquals(Feld.Typ.BOOL, f.typ);
        assertFalse(WeckwortMessRegeln.schalterAn(null));
        assertFalse(WeckwortMessRegeln.schalterAn(new JSONObject()));
        assertFalse(WeckwortMessRegeln.schalterAn(new JSONObject().put("sprache", new JSONObject())));
        assertFalse(WeckwortMessRegeln.schalterAn(new JSONObject().put("sprache",
                new JSONObject().put("weckwortMessung", "ja"))));
        assertTrue(WeckwortMessRegeln.schalterAn(new JSONObject().put("sprache",
                new JSONObject().put("weckwortMessung", true))));
    }

    @Test
    public void startNurAmAutobildschirmMitSchalterAnzeigeUndRecht() {
        assertEquals("nur_am_autobildschirm",
                WeckwortMessRegeln.startVerweigert(false, true, true, true, false, false));
        assertEquals("schalter_aus", WeckwortMessRegeln.startVerweigert(true, false, true, true, false, false));
        assertEquals("laeuft_schon", WeckwortMessRegeln.startVerweigert(true, true, true, true, false, true));
        assertEquals("keine_anzeige", WeckwortMessRegeln.startVerweigert(true, true, false, true, false, false));
        assertEquals("kein_mikrofonrecht", WeckwortMessRegeln.startVerweigert(true, true, true, false, false, false));
        assertEquals("panoptes_ton_aktiv", WeckwortMessRegeln.startVerweigert(true, true, true, true, true, false));
        assertNull(WeckwortMessRegeln.startVerweigert(true, true, true, true, false, false));
    }

    @Test
    public void stoppBeiSchalterAnzeigeTonUndZeit() {
        assertEquals("schalter_aus", WeckwortMessRegeln.stoppGrund(0, 10, false, false, true));
        assertEquals("anzeige_weg", WeckwortMessRegeln.stoppGrund(0, 10, true, false, false));
        assertEquals("panoptes_ton_aktiv", WeckwortMessRegeln.stoppGrund(0, 10, true, true, true));
        assertEquals("zeit_um", WeckwortMessRegeln.stoppGrund(10, 10, true, false, true));
        assertNull(WeckwortMessRegeln.stoppGrund(9, 10, true, false, true));
    }

    @Test
    public void dauerIstBegrenzt() {
        assertEquals(30, WeckwortMessRegeln.minuten(0));
        assertEquals(30, WeckwortMessRegeln.minuten(-5));
        assertEquals(15, WeckwortMessRegeln.minuten(15));
        assertEquals(120, WeckwortMessRegeln.minuten(100000));
    }

    @Test
    public void messwortVergleichNurAufTextfeld() {
        assertTrue(WeckwortMessRegeln.istMesswort("{\"text\" : \"hallo auto\"}"));
        assertTrue(WeckwortMessRegeln.istMesswort("{\"text\" : \"[unk] hallo  auto\"}"));
        assertFalse(WeckwortMessRegeln.istMesswort("{\"text\" : \"[unk]\"}"));
        assertFalse(WeckwortMessRegeln.istMesswort("{\"text\" : \"\"}"));
        assertFalse(WeckwortMessRegeln.istMesswort("kaputt"));
        assertFalse(WeckwortMessRegeln.istMesswort(null));
        assertTrue(WeckwortMessRegeln.grammatik().contains("\"hallo auto\""));
        assertTrue(WeckwortMessRegeln.grammatik().contains("[unk]"));
    }

    // ------------------------------------------------------------------ Zahlen

    @Test
    public void statistikRechnetCpuLatenzUndTrefferJeStunde() throws Exception {
        WeckwortMessStatistik s = new WeckwortMessStatistik("vosk", "m", "hallo auto", "MIC", 16000, 100);
        s.kerne = 4;
        s.startMs = 1_000_000L;
        s.endeMs = s.startMs + 2 * 3_600_000L;       // 2 h
        s.threadCpuMs = 720_000L;                      // 12 min CPU in 2 h = 10 % eines Kerns
        for (int i = 0; i < 100; i++) {
            s.stueck(1600, i < 10, -40.0);
            s.verarbeitung(i < 95 ? 5.0 : 50.0);
        }
        s.leseFehler(-3);
        s.leseFehler(-3);
        s.leseFehler(0);
        s.treffer(10);
        s.treffer(20);
        s.treffer(30);
        s.pss(100_000);
        s.pss(150_000);
        s.pss(140_000);
        s.prozessCpu(1_000, 0);
        s.prozessCpu(1_000 + 360_000, 3_600_000);   // 6 min in 1 h = 10 %

        JSONObject j = s.alsJson(0);
        assertEquals(10.0, j.getJSONObject("cpu").getDouble("messThreadProzentEinKern"), 1e-9);
        assertEquals(2.5, j.getJSONObject("cpu").getDouble("messThreadProzentGesamt"), 1e-9);
        assertEquals(10.0, j.getJSONObject("cpu").getDouble("appProzessProzentEinKern"), 1e-9);
        assertEquals(1.5, j.getJSONObject("treffer").getDouble("jeStunde"), 1e-9);
        assertEquals(3, j.getJSONObject("treffer").getJSONArray("sekundenSeitStart").length());
        assertEquals(5.1, j.getJSONObject("latenz").getDouble("p50Ms"), 1e-9);
        assertEquals(5.1, j.getJSONObject("latenz").getDouble("p95Ms"), 1e-9);
        assertEquals(50.1, j.getJSONObject("latenz").getDouble("p99Ms"), 1e-9);
        assertEquals(50.0, j.getJSONObject("latenz").getDouble("maxMs"), 1e-9);
        // 100 Stücke à 100 ms = 10 s Audio, Verarbeitung 95*5 + 5*50 = 725 ms
        assertEquals(0.0725, j.getJSONObject("latenz").getDouble("echtzeitFaktor"), 1e-9);
        JSONObject mik = j.getJSONObject("mikrofon");
        assertEquals(10, mik.getLong("nullStuecke"));
        assertEquals(0.1, mik.getDouble("nullAnteil"), 1e-9);
        assertEquals(2, mik.getJSONObject("leseFehler").getLong("-3"));
        assertEquals(3, mik.getLong("leseFehlerSumme"));
        assertEquals(-40.0, mik.getDouble("pegelDbfsMittel"), 1e-9);
        assertEquals(50_000, j.getJSONObject("ram").getLong("zuwachsMaxKb"));
        assertEquals("weckwort-messung", j.getString("art"));
    }

    @Test
    public void ergebnisEnthaeltKeinAudioUndKeinenErkennungstext() throws Exception {
        WeckwortMessStatistik s = new WeckwortMessStatistik("vosk", "m", "hallo auto", "MIC", 16000, 100);
        s.startMs = 1;
        short[] buf = new short[1600];
        for (int i = 0; i < buf.length; i++) buf[i] = (short) (i * 7);
        s.stueck(buf.length, WeckwortMessStatistik.alleNull(buf, buf.length),
                WeckwortMessStatistik.pegelDbfs(buf, buf.length));
        String text = s.alsJson(10_000).toString();
        // nur Zahlen und feste Wörter: keine Sample-Folgen, keine langen Zahlenreihen
        assertFalse(text.contains("[0,7,14"));
        for (String k : new String[] {"audio\"", "pcm", "wav", "text\"", "partial", "lat\"", "lon\""}) {
            assertFalse(k, text.toLowerCase().contains(k));
        }
        assertTrue(text.length() < 4000);
    }

    @Test
    public void pegelUndStille() {
        short[] still = new short[160];
        assertTrue(WeckwortMessStatistik.alleNull(still, still.length));
        assertEquals(-120.0, WeckwortMessStatistik.pegelDbfs(still, still.length), 1e-9);
        short[] voll = new short[160];
        java.util.Arrays.fill(voll, Short.MAX_VALUE);
        assertEquals(0.0, WeckwortMessStatistik.pegelDbfs(voll, voll.length), 0.01);
    }

    // ------------------------------------------------------------------ Datenschutz und „keine Befehle“

    private static List<Path> weckwortQuellen() throws IOException {
        List<Path> l = new ArrayList<>();
        try (Stream<Path> s = Files.list(Paths.get("src/main/java/at/greenchili/surfmate/weckwort"))) {
            s.filter(p -> p.toString().endsWith(".java")).forEach(l::add);
        }
        assertTrue(l.size() >= 5);
        return l;
    }

    @Test
    public void kein_netz_keine_audiodatei_keine_befehle() throws Exception {
        String[] verboten = {
                "java.net", "okhttp", "Socket", "HttpURLConnection", "URL(",       // kein Netz
                "MediaRecorder.OutputFormat", "MediaCodec", ".wav", ".pcm",          // keine Audiodatei
                "VehicleCommandRouter", "VehicleControl", "Automation", "Actions",  // keine Fahrzeugbefehle
                "genai", "GenAi", "Telegram", "Bruecke", "Handy",                    // kein KI-Assistent, kein Fernweg
                "unlock", "aufsperr", "Aufsperr", "horn", "Hupe", "flash", "hazard", "light", "Licht", "alarm"
        };
        for (Path p : weckwortQuellen()) {
            // Kommentare dürfen die Verbote nennen; geprüft wird nur der Code.
            String q = Quelltext.lies(p).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
            for (String v : verboten) {
                assertFalse(p.getFileName() + " enthält " + v, q.contains(v));
            }
        }
    }

    @Test
    public void nurEineDateiWirdGeschriebenUndNurZahlen() throws Exception {
        String m = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/weckwort/WeckwortMessung.java"));
        assertEquals("genau ein FileOutputStream (Ergebnis-JSON)", 1, m.split("new FileOutputStream", -1).length - 1);
        assertTrue(m.contains("j.toString(2).getBytes"));
        assertFalse("Erkennungstext nie loggen", m.contains("getResult"));
        String v = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/weckwort/VoskErkenner.java"));
        assertFalse("kein Log mit Erkennungstext", v.contains("Log."));
        assertTrue("Vosk schweigt", v.contains("vosk_set_log_level(-1)"));
    }

    @Test
    public void startNurUeberDieAppBrueckeAufLokalerSeite() throws Exception {
        List<Path> treffer = new ArrayList<>();
        try (Stream<Path> s = Files.walk(Paths.get("src/main/java"))) {
            s.filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".kt"))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/weckwort/"))
                    .forEach(p -> {
                        try {
                            String q = Quelltext.lies(p).replaceAll("\\s+", "");
                            if (q.contains("WeckwortSteuerung.starten(") || q.contains("WeckwortMessDienst")
                                    || q.contains("imDienstStarten(")) {
                                treffer.add(p);
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
        String w = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/ui/fragment/WebViewFragment.kt"));
        assertTrue(w.contains(".starten(ctx, seiteIstLokal(), minuten)"));
        assertTrue(w.contains("host == \"127.0.0.1\" || host == \"localhost\""));
        assertEquals("genau ein Aufrufer außerhalb des Pakets", 1, treffer.size());
        assertEquals("WebViewFragment.kt", treffer.get(0).getFileName().toString());
        // Die Messung selbst startet nur der Dienst im eigenen Prozess; den Dienst startet nur die Steuerung.
        String dienst = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/weckwort/WeckwortMessDienst.java"));
        assertTrue(dienst.contains("WeckwortMessung.imDienstStarten("));
        String steuerung = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/weckwort/WeckwortSteuerung.java"));
        assertTrue(steuerung.contains("AppAudioCaptureController.isAnyCaptureRunning()"));
        assertTrue(steuerung.contains("setTonBedarfHorcher"));
        String manifest = Quelltext.lies(Paths.get("src/main/AndroidManifest.xml")).replaceAll("\\s+", " ");
        assertTrue(manifest.contains("android:name=\"at.greenchili.surfmate.weckwort.WeckwortMessDienst\""
                + " android:process=\":weckwort\" android:exported=\"false\""));
    }

    @Test
    public void endpunktFuerKopplungenZu() {
        assertTrue(ExternGuard.blocks("GET", "/api/weckwort-messung", "cockpit"));
        assertTrue(ExternGuard.blocks("POST", "/api/weckwort-messung", "cockpit"));
    }
}
