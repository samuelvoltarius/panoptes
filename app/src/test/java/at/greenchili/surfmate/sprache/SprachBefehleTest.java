package at.greenchili.surfmate.sprache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.1.44 (P2) Sprachbefehle ohne eigenen KI-Schlüssel: feste Kommandos, echte Erkennung nur DE/EN, Fahrzeugbefehle nur
 * nach Rückfrage, Regel A+ (keine Hupe, kein Blinker, kein Licht, kein Alarm), Mikrofon nur mit Erlaubnis, ab Werk aus,
 * nichts aufnehmen oder speichern.
 */
public class SprachBefehleTest {

    private static String lies(String rel) throws Exception {
        File f = new File(rel);
        if (!f.isFile()) f = new File("..", rel);
        if (!f.isFile()) f = new File(rel.replaceFirst("^app/", ""));
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static final String PKG = "app/src/main/java/at/greenchili/surfmate/sprache/";

    @Test
    public void deUndEnErkennenFesteSaetze() {
        assertEquals(SprachBefehle.KLIMA_AN, SprachBefehle.befehl("de", "Klima an"));
        assertEquals(SprachBefehle.WAERMER, SprachBefehle.befehl("de-AT", "  WÄRMER "));
        assertEquals(SprachBefehle.KAELTER, SprachBefehle.befehl("de", "kälter"));
        assertEquals(SprachBefehle.NAVI_HEIM, SprachBefehle.befehl("de", "navi nach hause"));
        assertEquals(SprachBefehle.MUSIK_WEITER, SprachBefehle.befehl("de", "nächstes lied"));
        assertEquals(SprachBefehle.MUSIK_PAUSE, SprachBefehle.befehl("de", "pause"));
        assertEquals(SprachBefehle.WO_BIN_ICH, SprachBefehle.befehl("de", "wo bin ich"));
        assertEquals(SprachBefehle.REICHWEITE, SprachBefehle.befehl("de", "wie weit komme ich"));
        assertEquals(SprachBefehle.KLIMA_AUS, SprachBefehle.befehl("en", "climate off"));
        assertEquals(SprachBefehle.MUSIK_ZURUECK, SprachBefehle.befehl("en", "previous track"));
        assertEquals(SprachBefehle.REICHWEITE, SprachBefehle.befehl("en", "how far can I go?"));
        assertEquals(SprachBefehle.JA, SprachBefehle.befehl("de", "ja"));
        assertEquals(SprachBefehle.NEIN, SprachBefehle.befehl("en", "cancel"));
        // [unk] aus der Grammatik fällt weg; nur ganze Sätze zählen
        assertEquals(SprachBefehle.KLIMA_AN, SprachBefehle.befehl("de", "[unk] klima an"));
        assertNull(SprachBefehle.befehl("de", "klima an bitte sofort"));
        assertNull(SprachBefehle.befehl("de", "mach das fenster auf"));
        assertNull(SprachBefehle.befehl("de", ""));
        assertNull(SprachBefehle.befehl("de", null));
        assertEquals(SprachBefehle.KLIMA_AN, SprachBefehle.befehl("de", SprachBefehle.text("{\"text\" : \"klima an\"}")));
        assertEquals("", SprachBefehle.text("kaputt"));
    }

    @Test
    public void frItNlEhrlichNochNicht() {
        assertEquals(java.util.Arrays.asList("de", "en"), SprachBefehle.ERKENNUNG);
        for (String l : new String[] {"fr", "it", "nl"}) {
            assertFalse(l, SprachBefehle.erkennbar(l));
            assertNull(l, SprachBefehle.befehl(l, SprachBefehle.saetze(l, SprachBefehle.KLIMA_AN).get(0)));
            assertFalse(SprachBefehle.MODELL.containsKey(l));
            assertEquals("keine_erkennung", SprachRegeln.hoerenVerweigert(true, l, true));
        }
        assertEquals("vosk-model-small-de-0.15", SprachBefehle.MODELL.get("de"));
        assertEquals("vosk-model-small-en-us-0.15", SprachBefehle.MODELL.get("en"));
        assertEquals("en", SprachBefehle.sprache("xx"));
        assertEquals("de", SprachBefehle.sprache("de_AT"));
    }

    @Test
    public void jedeSpracheHatAlleSaetzeOhneDoppelte() {
        for (String l : SprachBefehle.SPRACHEN) {
            Map<String, String> gesehen = new HashMap<>();
            for (String b : SprachBefehle.BEFEHLE) assertFalse(l + ": " + b, SprachBefehle.saetze(l, b).isEmpty());
            for (String b : new String[] {SprachBefehle.JA, SprachBefehle.NEIN, SprachBefehle.VERBOTEN})
                assertFalse(l + ": " + b, SprachBefehle.saetze(l, b).isEmpty());
            for (Map.Entry<String, Map<String, List<String>>> s : SprachBefehle.SAETZE.entrySet()) {
                if (!s.getKey().equals(l)) continue;
                for (Map.Entry<String, List<String>> e : s.getValue().entrySet())
                    for (String satz : e.getValue()) {
                        String alt = gesehen.put(SprachBefehle.norm(satz), e.getKey());
                        assertTrue(l + ": „" + satz + "“ doppelt", alt == null || alt.equals(e.getKey()));
                    }
            }
        }
    }

    @Test
    public void grammatikNurAusDenSaetzen() throws Exception {
        JSONArray g = new JSONArray(SprachBefehle.grammatik("de", false));
        assertEquals("[unk]", g.getString(g.length() - 1));
        for (int i = 0; i < g.length() - 1; i++) assertTrue(g.getString(i), SprachBefehle.befehl("de", g.getString(i)) != null);
        JSONArray jn = new JSONArray(SprachBefehle.grammatik("de", true));
        for (int i = 0; i < jn.length() - 1; i++) {
            String b = SprachBefehle.befehl("de", jn.getString(i));
            assertTrue(jn.getString(i), SprachBefehle.JA.equals(b) || SprachBefehle.NEIN.equals(b));
        }
        assertEquals("[\"hallo auto\",\"[unk]\"]", SprachBefehle.weckGrammatik("de"));
        assertEquals("Weckwort wie Stufe 1", at.greenchili.surfmate.weckwort.WeckwortMessRegeln.MESSWORT, SprachBefehle.WECKWORT.get("de"));
        assertTrue(SprachBefehle.istWeckwort("de", "{\"text\":\"hallo auto\"}"));
        assertFalse(SprachBefehle.istWeckwort("de", "{\"text\":\"hallo\"}"));
        assertFalse(SprachBefehle.istWeckwort("fr", "{\"text\":\"hallo auto\"}"));
    }

    @Test
    public void regelAPlusUndRueckfrage() {
        String[] nie = {"hupe", "horn", "honk", "blinker", "indicator", "licht", "light", "lights", "fernlicht", "alarm", "hazard",
            "warnblinker", "fenster", "window", "tuer", "door", "unlock", "aufsperren", "kofferraum", "trunk"};
        for (String b : SprachBefehle.BEFEHLE) for (String n : nie) assertFalse(b, b.contains(n));
        for (String l : SprachBefehle.ERKENNUNG)
            for (String satz : SprachBefehle.saetze(l, SprachBefehle.VERBOTEN))
                assertEquals(l + ": " + satz, SprachBefehle.VERBOTEN, SprachBefehle.befehl(l, satz));
        assertFalse(SprachBefehle.KERN.contains(SprachBefehle.VERBOTEN));
        // Fahrzeugbefehle = nur Klima, alle mit Rückfrage; Klima nur in Stufen (Aktionen der Launcher-Kachel)
        assertEquals(java.util.Arrays.asList("klima_an", "klima_aus", "waermer", "kaelter"), SprachBefehle.FAHRZEUG);
        for (String b : SprachBefehle.FAHRZEUG) assertTrue(b, SprachBefehle.brauchtRueckfrage(b));
        for (String b : SprachBefehle.BEFEHLE) if (!SprachBefehle.FAHRZEUG.contains(b)) assertFalse(b, SprachBefehle.brauchtRueckfrage(b));
        assertEquals("temp_hoch", SprachBefehle.KLIMA_AKTION.get(SprachBefehle.WAERMER));
        assertEquals("temp_runter", SprachBefehle.KLIMA_AKTION.get(SprachBefehle.KAELTER));
    }

    @Test
    public void mikrofonNurMitSchalterErlaubnisUndOhnePanoptesTon() {
        assertEquals("schalter_aus", SprachRegeln.startVerweigert(false, false));
        assertEquals("panoptes_ton_aktiv", SprachRegeln.startVerweigert(true, true));
        assertNull(SprachRegeln.startVerweigert(true, false));
        assertEquals("kein_modell", SprachRegeln.hoerenVerweigert(true, "de", false));
        assertEquals("kein_mikrofonrecht", SprachRegeln.hoerenVerweigert(false, "de", true));
        assertNull(SprachRegeln.hoerenVerweigert(true, "en", true));
        assertEquals("weckwort_aus", SprachRegeln.weckVerweigert(true, false, false, true));
        assertEquals("schalter_aus", SprachRegeln.weckVerweigert(false, true, false, true));
        assertEquals("panoptes_ton_aktiv", SprachRegeln.weckVerweigert(true, true, true, true));
        assertNull(SprachRegeln.weckVerweigert(true, true, false, true));
        assertEquals("schalter_aus", SprachRegeln.weckStopp(0, 10, true, false, true));
        assertEquals("anzeige_weg", SprachRegeln.weckStopp(0, 10, true, true, false));
        assertEquals("zeit_um", SprachRegeln.weckStopp(10, 10, true, true, true));
        assertNull(SprachRegeln.weckStopp(9, 10, true, true, true));
        assertEquals(120, SprachRegeln.WECK_MAX_MIN);
    }

    @Test
    public void nichtsAufnehmenNichtsSpeichernKeinNetz() throws Exception {
        String erk = lies(PKG + "SprachErkenner.java");
        assertTrue("Puffer wird genullt", erk.contains("java.util.Arrays.fill(buf, (short) 0);"));
        assertTrue("Vosk-Log aus", erk.contains("LibVosk.vosk_set_log_level(-1);"));
        for (String f : new String[] {"SprachErkenner.java", "SprachActivity.java", "SprachWeckDienst.java", "SprachStart.java",
                "SprachBefehle.java", "SprachRegeln.java"}) {
            String q = lies(PKG + f);
            for (String v : new String[] {"FileOutputStream", "setOutputFile", "MediaRecorder.OutputFormat", "openFileOutput",
                    "Log.d(", "Log.i(", "BefehlsLog", "genai", "GenAi", "automation.", "FahrzeugAktionen", "HORN", "Hupe(",
                    "https://", "VehicleCommandRouter"})
                assertFalse(f + ": " + v, q.contains(v));
        }
        // Netz nur zum eigenen Kern über Loopback
        String act = lies(PKG + "SprachActivity.java");
        assertTrue(act.contains("\"http://127.0.0.1:\" + at.greenchili.surfmate.daemon.CameraDaemon.HTTP_PORT"));
        assertFalse(lies(PKG + "SprachWeckDienst.java").contains("http"));
    }

    @Test
    public void erlaubnisAbWerkAusUndEigenerProzess() throws Exception {
        String act = lies(PKG + "SprachActivity.java");
        assertTrue("Mikrofon nur mit Android-Erlaubnis",
                act.contains("requestPermissions(new String[] {android.Manifest.permission.RECORD_AUDIO}, RECHT_ANFRAGE);"));
        assertTrue("Rückfrage: keine Antwort = Nein",
                act.contains("if (!SprachBefehle.JA.equals(antwort)) { post(frage(befehl), getString(R.string.sprache_abgebrochen), 3000); return; }"));
        assertTrue("A+ nur Hinweis", act.contains("post(gehoert, getString(R.string.sprache_verboten), 6000);"));
        String man = lies("app/src/main/AndroidManifest.xml");
        for (String k : new String[] {"sprache.SprachActivity\"", "sprache.SprachWeckDienst\""}) {
            String e = man.substring(man.indexOf(k));
            e = e.substring(0, e.indexOf("/>"));
            assertTrue(k, e.contains("android:process=\":sprache\"") && e.contains("android:exported=\"false\""));
        }
        String start = man.substring(man.indexOf("sprache.SprachStart\""));
        start = start.substring(0, start.indexOf("/>"));
        assertTrue(start.contains("android:exported=\"true\"") && !start.contains(":sprache"));
        assertTrue("nicht klebrig", lies(PKG + "SprachWeckDienst.java").contains("return START_NOT_STICKY;"));
        String kat = lies("app/src/main/java/at/greenchili/surfmate/einstellungen/Katalog.java");
        assertTrue(kat.contains("sprBef.standard = false; sprBef.nurLesendExtern = true;"));
        assertTrue(kat.contains("sprWeck.reihenfolge = 82; sprWeck.standard = false; sprWeck.nurLesendExtern = true;"));
        assertEquals("sprache.befehle", SprachBefehle.SCHALTER);
        assertEquals("sprache.weckwort", SprachBefehle.SCHALTER_WECKWORT);
        // Panoptes-Ton hat Vorrang (gemeinsamer Horcher mit der Weckwort-Messung)
        String audio = lies("app/src/main/java/at/greenchili/surfmate/audio/AppAudioCaptureController.java");
        assertTrue(audio.contains("for (Runnable r : new Runnable[] {tonBedarfHorcher, sprachTonHorcher}) {"));
    }

    @Test
    public void texteInFuenfSprachen() throws Exception {
        java.util.Set<String> keys = new java.util.TreeSet<>();
        for (String f : new String[] {"SprachActivity.java", "SprachStart.java", "SprachRegeln.java", "SprachWeckDienst.java"}) {
            Matcher m = Pattern.compile("R\\.string\\.(sprache_[a-z_]+)").matcher(lies(PKG + f));
            while (m.find()) keys.add(m.group(1));
        }
        assertTrue(keys.size() > 30);
        for (String d : new String[] {"values", "values-de", "values-fr", "values-it", "values-nl"}) {
            String x = lies("app/src/main/res/" + d + "/strings_sprache.xml");
            for (String k : keys) assertTrue(d + ": " + k, x.contains("name=\"" + k + "\""));
        }
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            String s = lies("app/src/main/assets/server-i18n/" + l + ".json");
            assertTrue(l, s.contains("\"befehle\": {") && s.contains("\"weckwort\": {"));
        }
        // FR/IT/NL sagen ehrlich „noch nicht“
        assertTrue(lies("app/src/main/res/values-fr/strings_sprache.xml").contains("pas encore"));
        assertTrue(lies("app/src/main/res/values-it/strings_sprache.xml").contains("non ancora") || lies("app/src/main/res/values-it/strings_sprache.xml").contains("ancora"));
        assertTrue(lies("app/src/main/res/values-nl/strings_sprache.xml").contains("nog niet"));
    }
}
