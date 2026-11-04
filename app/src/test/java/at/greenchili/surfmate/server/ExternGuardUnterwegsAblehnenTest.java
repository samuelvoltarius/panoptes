package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.auth.Kopplungen;
import at.greenchili.surfmate.reichweite.Neuplanung;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 0.1.38.6 „Reicht es noch?“: Die Handy-App verwirft den Neuplanungs-Vorschlag mit
 * {@code POST /api/navi/unterwegs/ablehnen}. Das geht für eine Handy-Kopplung nur mit dem bestehenden Recht
 * {@code route} (200), ohne dieses Recht gibt es 403; kein neues Recht, keine neue Aktion. Das Ablehnen selbst setzt die
 * 30-Minuten-Sperre des Planers.
 */
public class ExternGuardUnterwegsAblehnenTest {

    private static final String P = "/api/navi/unterwegs/ablehnen";

    private static Predicate<String> mit(String... rechte) {
        Set<String> s = new HashSet<>(Arrays.asList(rechte));
        return s::contains;
    }

    @Test
    public void handyDarfNurMitRechtRoute() {
        // Pfad für die Art handy offen, das Recht prüft HttpServer über noetigesRecht
        assertFalse(ExternGuard.blocks("POST", P, Kopplungen.ART_HANDY));
        assertEquals(Kopplungen.RECHT_ROUTE, ExternGuard.noetigesRecht(P));
        assertEquals(Kopplungen.RECHT_ROUTE, ExternGuard.noetigesRecht(P + "?x=1"));
        assertTrue("mit Recht route: weiter (200)", ExternGuard.rechtReicht(ExternGuard.noetigesRecht(P), mit("route")));
        assertFalse("ohne Recht route: 403", ExternGuard.rechtReicht(ExternGuard.noetigesRecht(P), mit()));
        assertFalse("andere Rechte ersetzen route nicht", ExternGuard.rechtReicht(ExternGuard.noetigesRecht(P), mit("klima", "kamera", "launcher")));
    }

    @Test
    public void nurPostNurHandyNichtAndereArten() {
        assertTrue("GET bleibt zu", ExternGuard.blocks("GET", P, Kopplungen.ART_HANDY));
        assertTrue("PUT bleibt zu", ExternGuard.blocks("PUT", P, Kopplungen.ART_HANDY));
        for (String art : new String[]{"cockpit", "ha", Kopplungen.ART_DIENST}) {
            assertTrue(art, ExternGuard.blocks("POST", P, art));
        }
        assertTrue("allgemeine Schreibliste unverändert", ExternGuard.blocks("POST", P));
        assertTrue(ExternGuard.blocks("POST", P + "/x", Kopplungen.ART_HANDY));
        assertTrue(ExternGuard.blocks("POST", "/api/navi/unterwegs", Kopplungen.ART_HANDY));
        assertTrue(ExternGuard.blocks("POST", "/api/navi/planer-route/annehmen", Kopplungen.ART_HANDY));
    }

    @Test
    public void rechtFehltAntwortSagtWelchesRecht() throws Exception {
        JSONObject o = ExternGuard.rechtFehltAntwort(Kopplungen.RECHT_ROUTE);
        assertFalse(o.getBoolean("ok"));
        assertEquals("nicht_erlaubt", o.getString("grund"));
        assertEquals("route", o.getString("recht"));
        assertTrue(o.getString("text").toLowerCase().contains("route"));
    }

    @Test
    public void ablehnenSetztDieSperre() {
        Neuplanung n = new Neuplanung();
        long t = 1_800_000_000_000L;
        assertTrue(n.faellig(t, at.greenchili.surfmate.reichweite.RangeWatch.Stufe.REICHT_NICHT));
        assertFalse("nichts zum Ablehnen", n.ablehnen(t));
        assertFalse("30 min kein neuer Vorschlag", n.faellig(t + 29 * 60_000L, at.greenchili.surfmate.reichweite.RangeWatch.Stufe.REICHT_NICHT));
        assertTrue(n.faellig(t + 31 * 60_000L, at.greenchili.surfmate.reichweite.RangeWatch.Stufe.REICHT_NICHT));
    }

    @Test
    public void handlerHatDenPfadUndNurPost() throws Exception {
        String navi = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/at/greenchili/surfmate/server/NaviApiHandler.java")), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(navi.contains("\"/api/navi/unterwegs/ablehnen\""));
        assertTrue(navi.contains("RangeWatchDienst.ablehnen()"));
    }
}
