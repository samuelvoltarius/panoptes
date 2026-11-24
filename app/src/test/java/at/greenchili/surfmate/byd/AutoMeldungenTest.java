package at.greenchili.surfmate.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

/** 0.1.43.20 Meldungen des Autos: nur lesen, nur 1 = Meldung, alle Typen in allen Sprachen, kein UDS/TestDevice. */
public class AutoMeldungenTest {

    @Test
    public void nurEinsIstEineMeldung() throws Exception {
        JSONObject o = AutoMeldungen.auswerten(typ -> typ == 13 ? 1 : typ == 6 ? 1 : typ == 7 ? -2147482648 : 0, 5L);
        assertTrue(o.getBoolean("verfuegbar"));
        JSONArray m = o.getJSONArray("meldungen");
        assertEquals(2, m.length());
        assertEquals("abs", m.getJSONObject(0).getString("schluessel"));
        assertEquals("reifendruck", m.getJSONObject(1).getString("schluessel"));
        assertEquals(1, o.getInt("unbekannt"));
        assertEquals(AutoMeldungen.TYPEN.length - 1, o.getInt("geprueft"));
        assertEquals(5L, o.getLong("stand"));
    }

    @Test
    public void ohneAntwortNichtVerfuegbarStattAllesInOrdnung() throws Exception {
        JSONObject a = AutoMeldungen.auswerten(null, 1);
        assertFalse(a.getBoolean("verfuegbar"));
        assertEquals(0, a.getJSONArray("meldungen").length());
        JSONObject b = AutoMeldungen.auswerten(typ -> null, 1);
        assertFalse(b.getBoolean("verfuegbar"));
        JSONObject c = AutoMeldungen.auswerten(typ -> { throw new IllegalStateException("binder tot"); }, 1);
        assertFalse(c.getBoolean("verfuegbar"));
        // Werte außer 0/1 (z. B. 19 = MALFUNCTION_OK als Rückgabe) zählen nicht als Meldung
        assertFalse(AutoMeldungen.auswerten(typ -> 19, 1).getBoolean("verfuegbar"));
    }

    @Test
    public void typenEindeutigUndOhneOk() {
        Set<Integer> nr = new HashSet<>();
        Set<String> k = new HashSet<>();
        for (Object[] t : AutoMeldungen.TYPEN) {
            assertTrue(nr.add((Integer) t[0]));
            assertTrue(k.add((String) t[1]));
            assertTrue(((String) t[1]).matches("[a-z_]+"));
        }
        assertFalse("MALFUNCTION_OK ist kein Fehler", nr.contains(19));
        // SDK v1.0.5 (öffentlich dokumentiert) und Firmware 13.1.32.2510230 stimmen hier überein
        assertTrue(nr.contains(6) && nr.contains(13) && nr.contains(11) && nr.contains(16));
    }

    static File basis() {
        File b = new File("src/main");
        return b.isDirectory() ? b : new File("app/src/main");
    }

    @Test
    public void textInFuenfSprachen() throws Exception {
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject g = new JSONObject(new String(Files.readAllBytes(new File(basis(), "assets/web/i18n/" + l + ".json").toPath()),
                    StandardCharsets.UTF_8)).getJSONObject("auto_meldungen");
            for (String s : new String[] {"titel", "keine", "eine", "anzahl", "hinweis", "typ_unbekannt"}) assertTrue(l + s, g.has(s));
            JSONObject typ = g.getJSONObject("typ");
            for (Object[] t : AutoMeldungen.TYPEN) assertTrue(l + ":" + t[1], typ.optString((String) t[1], "").length() > 1);
            assertEquals(l, AutoMeldungen.TYPEN.length, typ.length());
        }
    }

    @Test
    public void nurLesenKeinUdsKeinTestDeviceKeinLoeschen() throws Exception {
        String q = new String(Files.readAllBytes(new File(basis(), "java/at/greenchili/surfmate/byd/AutoMeldungen.java").toPath()),
                StandardCharsets.UTF_8);
        // Code ohne Kommentare prüfen
        String code = q.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
        assertTrue(code.contains("\"getMalfunctionInfo\""));
        for (String verboten : new String[] {"TestDevice", "setClearFault", "Uds", "UDS", "callMethod", "sendSetCommand", "set("}) {
            assertFalse(verboten, code.contains(verboten));
        }
        String js = new String(Files.readAllBytes(new File(basis(), "assets/web/shared/auto-meldungen.js").toPath()),
                StandardCharsets.UTF_8);
        assertFalse("nur GET", js.contains("POST"));
        String start = new String(Files.readAllBytes(new File(basis(), "assets/web/local/index.html").toPath()), StandardCharsets.UTF_8);
        assertTrue(start.contains("auto-meldungen.js?v="));
        assertTrue(start.contains("id=\"sMeldTile\" hidden"));
    }
}
