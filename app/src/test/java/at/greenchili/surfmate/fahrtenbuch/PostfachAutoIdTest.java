package at.greenchili.surfmate.fahrtenbuch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * Bugfix 0.1.9: Ohne planner.autoId fragte das Fahrtenbuch-Postfach den Planer nie ab. Neuinstallation →
 * verbinden → autoId gesetzt → Postfach fragt ab; Status sichtbar; Einschalten wirkt ohne Neustart.
 */
public class PostfachAutoIdTest {

    @Test
    public void neuinstallationVerbindenDannFragtPostfachAb() throws Exception {
        final String[] autoId = {null};          // Neuinstallation: keine Kennung
        final String[][] schluessel = {null};    // noch nicht verbunden
        final boolean[] an = {false};
        final long[] now = {1_761_040_000_000L};
        final List<String> calls = new ArrayList<>();
        FahrtenbuchPostfach.Quelle q = new FahrtenbuchPostfach.Quelle() {
            public boolean eingeschaltet() { return an[0]; }
            public String autoId() { return autoId[0]; }
            public String[] schluessel() { return schluessel[0]; }
            public Object[] http(String m, String op, String[] k, String body) {
                calls.add(op);
                try {
                    return new Object[]{200, new JSONObject().put("anfragen", new JSONArray())};
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            public List<FahrtenbuchSeite.Fahrt> fahrten(long v, long b) { return new ArrayList<>(); }
            public TimeZone zone() { return TimeZone.getTimeZone("Europe/Vienna"); }
            public long jetzt() { return now[0]; }
        };
        FahrtenbuchPostfach p = new FahrtenbuchPostfach(q);
        p.takt();
        assertEquals("aus", p.statusIntern().getString("grund"));
        an[0] = true;                            // zur Laufzeit eingeschaltet – kein Neustart nötig
        p.takt();
        assertEquals("kein_schluessel", p.statusIntern().getString("grund"));
        schluessel[0] = new String[]{"X-Geraete-Token", "x"};   // verbunden …
        autoId[0] = "a-0123456789abcdef";                      // … und die Kennung beim Verbinden erzeugt
        now[0] += 61_000;
        p.takt();
        assertEquals(1, calls.size());
        assertTrue(calls.get(0).startsWith("fahrtenbuch/offen?auto=a-0123456789abcdef"));
        JSONObject st = p.statusIntern();
        assertEquals("ok", st.getString("grund"));
        assertEquals(200, st.getInt("httpStatus"));
        assertEquals(now[0], st.getLong("letzteAbfrage"));
        assertFalse(st.getBoolean("netzFehler"));
    }

    @Test
    public void verbindungUndStartErzeugenDieKennung() throws Exception {
        String v = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/planer/PlanerVerbindung.java"));
        assertTrue(v.contains("if (ok) autoIdSicherstellen();"));
        String d = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/daemon/CameraDaemon.java"));
        assertTrue(d.contains("PlanerSchluessel.kopf() != null"));
        String pf = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/fahrtenbuch/FahrtenbuchPostfach.java"));
        assertTrue(pf.contains("EinstellungenApiHandler.autoId()"));
        String api = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/EinstellungenApiHandler.java"));
        assertTrue(api.contains("st.put(\"fahrtenbuchPostfach\", "));
    }
}
