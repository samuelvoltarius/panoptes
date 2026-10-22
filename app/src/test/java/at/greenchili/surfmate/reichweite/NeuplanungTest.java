package at.greenchili.surfmate.reichweite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** F1 Neuplanungs-Vorschlag: Takt, Ablehnen, Anfrage und Antwort nach dem Planer (POST plan). */
public class NeuplanungTest {

    /** Ausschnitt einer echten POST-plan-Antwort (planer.py plane(): stopps[].saeule, soc_vorher/nachher). */
    static final String ANTWORT = "{\"soc_start\":21,\"soc_ankunft\":17.4,\"stopps\":[{\"saeule\":{\"id\":\"osm-1\","
            + "\"name\":\"Smatrics Haag\",\"lat\":48.11,\"lon\":14.56,\"kw\":150,\"km_auf_route\":38.2,\"umweg_km\":0.4},"
            + "\"soc_vorher\":11.8,\"soc_nachher\":62,\"kwh\":21.9,\"ladedauer_min\":24}],\"warnungen\":[]}";

    @Test
    public void onlyWhenNotEnoughAndAtMostEveryFiveMinutes() {
        Neuplanung n = new Neuplanung();
        long t = 1_000_000L;
        assertFalse(n.faellig(t, RangeWatch.Stufe.PASST));
        assertFalse(n.faellig(t, RangeWatch.Stufe.KNAPP));
        assertFalse(n.faellig(t, null));
        assertTrue(n.faellig(t, RangeWatch.Stufe.REICHT_NICHT));
        n.versucht(t);
        assertFalse(n.faellig(t + 4 * 60_000L, RangeWatch.Stufe.REICHT_NICHT));
        assertTrue(n.faellig(t + 5 * 60_000L, RangeWatch.Stufe.REICHT_NICHT));
    }

    @Test
    public void rejectHidesAndBlocksForThirtyMinutes() throws Exception {
        Neuplanung n = new Neuplanung();
        long t = 1_000_000L;
        n.versucht(t);
        n.ergebnis(Neuplanung.auswerten(new JSONObject(ANTWORT), t));
        assertEquals("stopp", n.aktuell(RangeWatch.Stufe.REICHT_NICHT).art);
        assertTrue(n.ablehnen(t + 1000));
        assertNull(n.aktuell(RangeWatch.Stufe.REICHT_NICHT));
        assertFalse(n.faellig(t + 29 * 60_000L, RangeWatch.Stufe.REICHT_NICHT));
        assertTrue(n.faellig(t + 31 * 60_000L, RangeWatch.Stufe.REICHT_NICHT));
        assertFalse("nichts zum Ablehnen", n.ablehnen(t + 2000));
    }

    @Test
    public void backToFineDropsTheSuggestionAndNewRouteResets() throws Exception {
        Neuplanung n = new Neuplanung();
        n.ergebnis(Neuplanung.auswerten(new JSONObject(ANTWORT), 1));
        assertEquals("stopp", n.aktuell(RangeWatch.Stufe.KNAPP).art);
        assertNull(n.aktuell(RangeWatch.Stufe.PASST));
        n.ablehnen(10);
        n.zuruecksetzen();
        assertTrue(n.faellig(20, RangeWatch.Stufe.REICHT_NICHT));
    }

    @Test
    public void requestCarriesRoundedStartOnlyInBody() throws Exception {
        JSONObject b = Neuplanung.anfrage(48.123456, 14.567891, 21.4, 48.2, 16.37, 13, "Dolphin Surf", "a-0123456789abcdef");
        assertEquals(48.123, b.getJSONArray("start").getDouble(0), 0);
        assertEquals(14.568, b.getJSONArray("start").getDouble(1), 0);
        assertEquals(48.2, b.getJSONArray("ziel").getDouble(0), 0);
        assertEquals(21, b.getInt("soc_start"));
        assertEquals(13, b.getInt("soc_ziel_min"));
        assertEquals(13, b.getInt("soc_reserve"));
        assertEquals("Dolphin Surf", b.getString("fahrzeug"));
        assertEquals("a-0123456789abcdef", b.getString("auto"));
        assertFalse("ungültige Auto-Kennung nicht senden",
                Neuplanung.anfrage(1, 2, 50, 3, 4, 10, "", "VIN123").has("auto"));
    }

    @Test
    public void readsFirstStopOfPlannerAnswer() throws Exception {
        Neuplanung.Vorschlag v = Neuplanung.auswerten(new JSONObject(ANTWORT), 42);
        assertEquals("stopp", v.art);
        assertEquals("Smatrics Haag", v.name);
        assertEquals(38.2, v.kmAbHier, 0);
        assertEquals(150, v.kw, 0);
        assertEquals(11.8, v.socAn, 0);
        assertEquals(62, v.socAb, 0);
        JSONObject j = v.alsJson();
        assertEquals("stopp", j.getString("art"));
        assertEquals(38.2, j.getDouble("km"), 0);
        assertEquals(12, j.getInt("socAn"));
        assertEquals(17, j.getInt("ankunftProzent"));
        assertEquals(48.11, j.getDouble("lat"), 0);
    }

    @Test
    public void noStopErrorsAndGarbage() throws Exception {
        assertEquals("ohne_stopp", Neuplanung.auswerten(new JSONObject("{\"soc_ankunft\":22,\"stopps\":[]}"), 1).art);
        assertEquals("keiner", Neuplanung.auswerten(new JSONObject("{\"fehler\":\"kein Fahrzeugprofil hinterlegt\"}"), 1).art);
        assertEquals("keiner", Neuplanung.auswerten(null, 1).art);
        assertEquals("keiner", Neuplanung.auswerten(new JSONObject("{\"stopps\":[{\"saeule\":{\"name\":\"x\"}}]}"), 1).art);
    }

    /** Vertrag: Position nur im Body von POST plan, Ablehnen meldet „abgelehnt“, Endpunkt verdrahtet. */
    @Test
    public void wiring() throws Exception {
        String dienst = lies("app/src/main/java/at/greenchili/surfmate/reichweite/RangeWatchDienst.java");
        assertTrue(dienst.contains("http(\"POST\", \"plan\", k, at.greenchili.surfmate.planer.FahrzeugWahl.einsetzen(at.greenchili.surfmate.laden.LadenVorlieben.inPlanAnfrage(body.toString())))"));
        assertTrue(dienst.contains("b.put(\"status\", \"abgelehnt\")"));
        assertFalse("keine Position in URLs", dienst.matches("(?s).*\"plan\\?.*"));
        String navi = lies("app/src/main/java/at/greenchili/surfmate/server/NaviApiHandler.java");
        assertTrue(navi.contains("/api/navi/unterwegs/ablehnen"));
        assertTrue(navi.contains("RangeWatchDienst.ablehnen()"));
    }

    static String lies(String rel) throws Exception {
        java.nio.file.Path p = Paths.get(rel);
        if (!Files.exists(p)) p = Paths.get("..").resolve(rel);
        if (!Files.exists(p)) p = Paths.get(rel.replaceFirst("^app/", ""));
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
