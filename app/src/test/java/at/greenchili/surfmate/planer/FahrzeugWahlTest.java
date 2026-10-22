package at.greenchili.surfmate.planer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.einstellungen.Feld;
import at.greenchili.surfmate.einstellungen.Katalog;
import at.greenchili.surfmate.einstellungen.LadenKatalog;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Koordination 22.10.: fahrzeug immer mitschicken, Varianten nie raten, unbekannt → einmal fragen. Synthetische Profile. */
public class FahrzeugWahlTest {

    static final JSONArray PROFILE = profile();

    private static JSONArray profile() {
        try {
            return new JSONArray("[{\"name\":\"Modell A 40\",\"modell_id\":\"aaa\",\"akku_kwh\":40.0},"
                    + "{\"name\":\"Modell A 30\",\"modell_id\":\"aaa\",\"akku_kwh\":30.0},"
                    + "{\"name\":\"Modell B 60\",\"modell_id\":\"bbb\",\"akku_kwh\":60.0},"
                    + "{\"name\":\"Eigenes B\",\"modell_id\":\"bbb\",\"akku_kwh\":60.0,\"eigen\":true}]");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static JSONObject root(String modell, String quelle, String gewaehlt) throws Exception {
        JSONObject r = new JSONObject();
        if (modell != null) r.put("vehicle", new JSONObject().put("modelId", modell).put("modelSource", quelle));
        if (gewaehlt != null) r.put("laden", new JSONObject().put("fahrzeug", gewaehlt));
        return r;
    }

    @Test
    public void gewaehltGewinnt() throws Exception {
        assertEquals("Modell A 30", FahrzeugWahl.wirksam(root("bbb", "user", "Modell A 30"), PROFILE, Double.NaN));
    }

    @Test
    public void eindeutig_automatisch_mehrdeutig_fragen() throws Exception {
        assertEquals("Modell B 60", FahrzeugWahl.wirksam(root("bbb", "auto", null), PROFILE, Double.NaN));   // eigenes Profil zählt nicht
        assertNull(FahrzeugWahl.wirksam(root("aaa", "user", null), PROFILE, Double.NaN));                    // zwei Varianten → fragen
        assertEquals(2, FahrzeugWahl.kandidaten(root("aaa", "user", null), PROFILE, Double.NaN).length());
        assertEquals("Modell A 30", FahrzeugWahl.wirksam(root("aaa", "user", null), PROFILE, 29.5));          // Akkugröße grenzt ein
    }

    @Test
    public void rendererRueckfallUndLegacyNieAlsWahl() throws Exception {
        assertNull(FahrzeugWahl.wirksam(root("bbb", "unset", null), PROFILE, Double.NaN));
        assertNull(FahrzeugWahl.wirksam(root("bbb", "legacy", null), PROFILE, Double.NaN));
        assertNull(FahrzeugWahl.wirksam(new JSONObject(), PROFILE, Double.NaN));
        assertNull(FahrzeugWahl.wirksam(root("bbb", "auto", null), null, Double.NaN));   // Planer-Liste unbekannt
    }

    @Test
    public void einsetzen_nurWennFehlt() throws Exception {
        assertEquals("X", new JSONObject(FahrzeugWahl.einsetzen("{\"fahrzeug\":null}", "X")).getString("fahrzeug"));
        assertEquals("X", new JSONObject(FahrzeugWahl.einsetzen("{\"fahrzeug\":\" \"}", "X")).getString("fahrzeug"));
        assertEquals("Y", new JSONObject(FahrzeugWahl.einsetzen("{\"fahrzeug\":\"Y\"}", "X")).getString("fahrzeug"));
        assertEquals("{\"a\":1}", FahrzeugWahl.einsetzen("{\"a\":1}", null));
        assertEquals("kaputt", FahrzeugWahl.einsetzen("kaputt", "X"));
    }

    @Test
    public void feld_ohneStand_undGeprueft() throws Exception {
        Feld f = Katalog.finde(LadenKatalog.felder(), "laden.fahrzeug");
        java.util.Map<String, java.util.Map<String, Object>> w = f.zugriff.schreibe("Modell B 60", new JSONObject());
        assertFalse("Fahrzeugwahl macht das Auto nicht zur Vorlieben-Quelle", w.get("laden").containsKey("stand"));
        assertEquals("Modell B 60", at.greenchili.surfmate.einstellungen.Einstellungen.pruefe(f, "  Modell B 60 "));
        try {
            at.greenchili.surfmate.einstellungen.Einstellungen.pruefe(f, "");
            throw new AssertionError();
        } catch (IllegalArgumentException ok) {
            assertTrue(ok.getMessage().startsWith("max"));
        }
    }

    @Test
    public void autoZustand_traegtFahrzeug() throws Exception {
        AutoZustandTest.Netz n = new AutoZustandTest.Netz();
        AutoZustandVorliebenTest.Env e = new AutoZustandVorliebenTest.Env() {
            @Override public String fahrzeug() { return "Modell B 60"; }
        };
        e.m = new AutoZustand.Messung(60, 230, false, Double.NaN, 1_000L);
        new AutoZustand(n, e).takt(1_000L);
        assertEquals("Modell B 60", n.bodies.get(0).getString("fahrzeug"));
    }
}
