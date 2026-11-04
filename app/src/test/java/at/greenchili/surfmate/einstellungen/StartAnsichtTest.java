package at.greenchili.surfmate.einstellungen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Schema- und Schreibvertrag fuer die Startansicht am Autobildschirm. */
public class StartAnsichtTest {

    private static final Einstellungen.Texte TEXTE = new Einstellungen.Texte() {
        public String text(String key) { return key; }
        public JSONObject unter(String key) { return new JSONObject(); }
    };

    private static final class Merker implements Einstellungen.Schreiber {
        final Map<String, Map<String, Object>> werte = new HashMap<>();
        public boolean schreibe(String abschnitt, Map<String, Object> neu) {
            werte.put(abschnitt, new HashMap<>(neu));
            return true;
        }
    }

    @Test
    public void schemaHasFiveProfilesAndPanoptesDefault() throws Exception {
        JSONObject f = feld(Einstellungen.schema(Katalog.felder(new Katalog.Kontext()),
                new JSONObject(), new Katalog.Kontext(), TEXTE), "anzeige.startAnsicht");
        assertEquals("auswahl", f.getString("typ"));
        assertEquals("panoptes", f.getString("standard"));
        assertFalse("0.1.38.7: Fernzugriff erlaubt", f.getBoolean("nurLesendExtern"));
        JSONArray optionen = f.getJSONArray("optionen");
        assertEquals(5, optionen.length());
        assertEquals("panoptes", optionen.getJSONObject(0).getString("wert"));
        assertEquals("fahren", optionen.getJSONObject(1).getString("wert"));
        assertEquals("fahrzeug", optionen.getJSONObject(2).getString("wert"));
        assertEquals("kompakt", optionen.getJSONObject(3).getString("wert"));
        assertEquals("cockpit", optionen.getJSONObject(4).getString("wert"));
    }

    @Test
    public void localWriteRoundTripsUnknownIsRejectedAndExternalMayWrite() throws Exception {
        Katalog.Kontext k = new Katalog.Kontext();
        Merker lokal = new Merker();
        Einstellungen.Ergebnis ok = aendern(k, "cockpit", false, lokal);
        assertTrue(ok.ok());
        assertEquals("cockpit", lokal.werte.get("anzeige").get("startAnsicht"));

        Einstellungen.Ergebnis falsch = aendern(k, "beliebig", false, new Merker());
        assertFalse(falsch.ok());
        assertTrue(falsch.fehler.has("anzeige.startAnsicht"));

        // 0.1.38.7: vom PC/Handy aus (extern) darf die Startansicht des Auto-Displays gesetzt werden
        Merker extern = new Merker();
        Einstellungen.Ergebnis erlaubt = aendern(k, "kompakt", true, extern);
        assertTrue(erlaubt.ok());
        assertEquals("kompakt", extern.werte.get("anzeige").get("startAnsicht"));
    }

    private static Einstellungen.Ergebnis aendern(Katalog.Kontext k, String wert,
            boolean extern, Merker merker) throws Exception {
        JSONObject body = new JSONObject().put("werte",
                new JSONObject().put("anzeige.startAnsicht", wert));
        return Einstellungen.aendern(Katalog.felder(k), new JSONObject(), k, body,
                extern, merker, 0L);
    }

    private static JSONObject feld(JSONObject schema, String key) throws Exception {
        JSONArray gruppen = schema.getJSONArray("gruppen");
        for (int i = 0; i < gruppen.length(); i++) {
            JSONArray felder = gruppen.getJSONObject(i).getJSONArray("felder");
            for (int j = 0; j < felder.length(); j++) {
                JSONObject f = felder.getJSONObject(j);
                if (key.equals(f.getString("key"))) return f;
            }
        }
        throw new AssertionError("Missing: " + key);
    }
}
