package at.greenchili.surfmate.navmap.nav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.einstellungen.Einstellungen;
import at.greenchili.surfmate.einstellungen.Feld;
import at.greenchili.surfmate.einstellungen.Katalog;
import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * 0.1.39 G5 (Regel A+): Die Panoptes-Führung spricht nur mit {@code navi.sprachansagen} = an. Standard aus,
 * eine Quelle (NaviAnsagen), über Kopplungen nur lesen, Ausgabe über den Medienkanal, sonst kein Ton.
 */
public class NaviAnsagenTest {

    private static final class Merker implements Einstellungen.Schreiber {
        final Map<String, Map<String, Object>> geschrieben = new HashMap<>();
        public boolean schreibe(String a, Map<String, Object> w) {
            geschrieben.computeIfAbsent(a, x -> new HashMap<>()).putAll(w);
            return true;
        }
    }

    private static Feld feld() {
        for (Feld f : Katalog.felder(new Katalog.Kontext())) {
            if (NaviAnsagen.SCHLUESSEL.equals(f.key)) return f;
        }
        return null;
    }

    @Test
    public void feldImKatalogStandardAus() {
        Feld f = feld();
        assertNotNull("navi.sprachansagen fehlt im Katalog", f);
        assertEquals("navi.sprachansagen", NaviAnsagen.SCHLUESSEL);
        assertEquals(Feld.Typ.BOOL, f.typ);
        assertEquals("system", f.gruppe);
        assertEquals(Boolean.FALSE, f.standard);
        assertEquals(NaviAnsagen.STANDARD, f.standard);
        assertTrue("hörbar im Auto → extern nur lesen", f.nurLesendExtern);
        assertEquals("settings.navi.sprachansagen.title", f.titelKey);
        assertEquals("settings.navi.sprachansagen.help", f.hilfeKey);
    }

    @Test
    public void nurAusdruecklichesJaSchaltetEin() throws Exception {
        assertFalse(NaviAnsagen.aus(null));
        assertFalse(NaviAnsagen.aus(new JSONObject()));
        assertFalse(NaviAnsagen.aus(new JSONObject("{\"navi\":{}}")));
        assertFalse(NaviAnsagen.aus(new JSONObject("{\"navi\":{\"sprachansagen\":false}}")));
        assertFalse(NaviAnsagen.aus(new JSONObject("{\"navi\":{\"sprachansagen\":\"true\"}}")));
        assertFalse(NaviAnsagen.aus(new JSONObject("{\"navi\":{\"sprachansagen\":1}}")));
        assertFalse(NaviAnsagen.aus(new JSONObject("{\"navi\":true}")));
        assertTrue(NaviAnsagen.aus(new JSONObject("{\"navi\":{\"sprachansagen\":true}}")));
    }

    @Test
    public void werteZeigenStandardAus() throws Exception {
        Katalog.Kontext k = new Katalog.Kontext();
        JSONObject w = Einstellungen.werte(Katalog.felder(k), new JSONObject(), k);
        JSONObject wert = w.getJSONObject("werte").getJSONObject(NaviAnsagen.SCHLUESSEL);
        assertEquals(Boolean.FALSE, wert.get("wert"));
        assertFalse(wert.getBoolean("gesetzt"));
    }

    @Test
    public void lokalSchreibbarExternNicht() throws Exception {
        Katalog.Kontext k = new Katalog.Kontext();
        JSONObject body = new JSONObject().put("werte", new JSONObject().put(NaviAnsagen.SCHLUESSEL, true));
        Merker m = new Merker();
        Einstellungen.Ergebnis e = Einstellungen.aendern(Katalog.felder(k), new JSONObject(), k, body, false, m, 0L);
        assertTrue(e.fehler.toString(), e.ok());
        assertEquals(Boolean.TRUE, m.geschrieben.get("navi").get("sprachansagen"));

        Merker x = new Merker();
        Einstellungen.Ergebnis ex = Einstellungen.aendern(Katalog.felder(k), new JSONObject(), k, body, true, x, 0L);
        assertEquals("nurLesendExtern", ex.fehler.getString(NaviAnsagen.SCHLUESSEL));
        assertFalse(x.geschrieben.containsKey("navi"));
    }

    @Test
    public void sprechenNurMitJaUndFehlerHeisstSchweigen() {
        assertTrue(NavVoice.darfSprechen(() -> true));
        assertFalse(NavVoice.darfSprechen(() -> false));
        assertFalse(NavVoice.darfSprechen(() -> { throw new IllegalStateException("Config kaputt"); }));
    }

    @Test
    public void eineQuelleUndMedienkanal() throws Exception {
        String katalog = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/einstellungen/Katalog.java"));
        assertFalse("Schlüssel nur in NaviAnsagen, keine Kopie", katalog.contains("\"navi.sprachansagen\""));
        assertTrue(katalog.contains("NaviAnsagen.STANDARD"));

        String stimme = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/navmap/nav/NavVoice.kt"));
        assertTrue(stimme.contains("NaviAnsagen.aktuell()"));
        assertTrue(stimme.contains("if (!darfSprechen(erlaubt))"));
        assertTrue(stimme.contains("AudioManager.STREAM_MUSIC"));
        assertTrue(stimme.contains("AudioAttributes.USAGE_MEDIA"));
        for (String ton : new String[] {"ToneGenerator", "SoundPool", "MediaPlayer", "Ringtone", "STREAM_NOTIFICATION",
                "STREAM_ALARM", "USAGE_ASSISTANCE_NAVIGATION_GUIDANCE"}) {
            assertFalse("kein anderer Ton: " + ton, stimme.contains(ton));
        }

        String karte = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/navmap/RoadSenseMapActivity.kt"));
        assertTrue("Engine nur bei eingeschalteten Ansagen",
                karte.contains("if (navVoice == null && NaviAnsagen.aktuell()) navVoice = NavVoice(applicationContext)"));
        int sprich = karte.indexOf("private fun speakOnce(");
        assertTrue(sprich > 0);
        String rumpf = karte.substring(sprich, karte.indexOf("\n    }", sprich));
        assertTrue(rumpf.indexOf("if (!NaviAnsagen.aktuell()) return") < rumpf.indexOf("navVoice?.speak("));
        int anlegen = 0, ab = 0;
        while ((ab = karte.indexOf("NavVoice(applicationContext)", ab)) >= 0) { anlegen++; ab++; }
        assertEquals("NavVoice entsteht nur an den zwei geprüften Stellen", 2, anlegen);
    }
}
