package at.greenchili.surfmate.einrichtung;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.TimeZone;

/** Einrichtung Teil G (0.1.10): nur echte Prüfungen, nichts behaupten. */
public class SelbsttestTest {

    private static final long JETZT = 1_800_000_000_000L;

    private static Selbsttest.Fakten alleGut() {
        Selbsttest.Fakten f = new Selbsttest.Fakten();
        f.jetzt = JETZT;
        f.autostart = true;
        f.kameraSchritt = "erledigt";
        f.kameraAnzahl = 4;
        f.letztesBildMs = JETZT - 5_000;
        f.speicherFreiGb = 40.0;
        f.internet = true;
        f.planerSchluessel = true;
        f.planerSchritt = "erledigt";
        f.planerLetzterCode = 200;
        f.planerLetzteZeit = JETZT - 60_000;
        f.fernzugangEingerichtet = true;
        f.fernzugangOnline = true;
        f.waechterStatus = "shock";
        return f;
    }

    private static String status(JSONObject r, String id) throws Exception {
        JSONArray p = r.getJSONArray("punkte");
        for (int i = 0; i < p.length(); i++) {
            if (id.equals(p.getJSONObject(i).getString("id"))) return p.getJSONObject(i).getString("status");
        }
        throw new AssertionError(id);
    }

    @Test
    public void allesGutIstOk() throws Exception {
        JSONObject r = Selbsttest.bewerte(alleGut());
        assertTrue(r.getBoolean("ok"));
        for (String id : new String[] {"autostart", "dienste", "kameras", "speicher", "internet", "planer",
                "fernzugang", "waechter"}) {
            assertEquals(id, "ok", status(r, id));
        }
        assertEquals("kameras", r.getJSONArray("punkte").getJSONObject(2).getString("ziel"));
    }

    @Test
    public void uebersprungenerKameraSchrittIstFehltNieOk() throws Exception {
        for (String st : new String[] {"offen", "spaeter"}) {
            Selbsttest.Fakten f = alleGut();
            f.kameraSchritt = st;   // auch wenn die Kamera Bilder liefert
            JSONObject r = Selbsttest.bewerte(f);
            assertEquals(st, "fehlt", status(r, "kameras"));
            assertFalse(r.getBoolean("ok"));
        }
        Selbsttest.Fakten ruht = alleGut();
        ruht.letztesBildMs = 0;
        assertEquals("ungeprueft", status(Selbsttest.bewerte(ruht), "kameras"));
        Selbsttest.Fakten keine = alleGut();
        keine.kameraSchritt = "entfaellt";
        assertEquals("entfaellt", status(Selbsttest.bewerte(keine), "kameras"));
    }

    @Test
    public void ohneInternetPlanerUngeprueft() throws Exception {
        Selbsttest.Fakten f = alleGut();
        f.internet = false;
        JSONObject r = Selbsttest.bewerte(f);
        assertEquals("fehlt", status(r, "internet"));
        assertEquals("ungeprueft", status(r, "planer"));
        assertEquals("ungeprueft", status(r, "fernzugang"));
    }

    @Test
    public void planerUndFernzugang() throws Exception {
        Selbsttest.Fakten f = alleGut();
        f.planerLetzterCode = 401;
        assertEquals("fehlt", status(Selbsttest.bewerte(f), "planer"));
        f.planerLetzterCode = 0;
        assertEquals("noch keine Antwort", "ungeprueft", status(Selbsttest.bewerte(f), "planer"));
        f.planerSchluessel = false;
        f.planerSchritt = "spaeter";
        assertEquals("entfaellt", status(Selbsttest.bewerte(f), "planer"));
        Selbsttest.Fakten g = alleGut();
        g.fernzugangEingerichtet = false;
        g.fernzugangSchritt = "spaeter";
        assertEquals("entfaellt", status(Selbsttest.bewerte(g), "fernzugang"));
        g.fernzugangSchritt = "erledigt";
        assertEquals("fehlt", status(Selbsttest.bewerte(g), "fernzugang"));
    }

    @Test
    public void waechterUndSpeicher() throws Exception {
        Selbsttest.Fakten f = alleGut();
        f.waechterStatus = "nicht_gewaehlt";
        f.speicherFreiGb = 0.5;
        JSONObject r = Selbsttest.bewerte(f);
        assertEquals("fehlt", status(r, "waechter"));
        assertEquals("fehlt", status(r, "speicher"));
        f.speicherFreiGb = null;
        f.autostart = null;
        r = Selbsttest.bewerte(f);
        assertEquals("ungeprueft", status(r, "speicher"));
        assertEquals("ungeprueft", status(r, "autostart"));
    }

    @Test
    public void internetNurAusSystemValidierung() {
        assertEquals(Boolean.TRUE, Selbsttest.internet("NetworkAgentInfo{ ... Capabilities: INTERNET&VALIDATED ...}"));
        assertEquals(Boolean.FALSE, Selbsttest.internet("Active default network: none\n"));
        assertEquals(Boolean.FALSE, Selbsttest.internet("NetworkAgentInfo{ ... Capabilities: INTERNET ...}"));
        assertNull("nichts lesbar = ungeprüft", Selbsttest.internet(""));
        assertNull(Selbsttest.internet(null));
    }

    @Test
    public void autostartJeInstallation() throws Exception {
        TimeZone tz = TimeZone.getTimeZone("UTC");
        String inst = "    lastUpdateTime=2026-10-22 08:00:00\n";
        assertEquals(Boolean.TRUE, Selbsttest.autostart(inst, "2026-10-22 08:00:00\n", null, tz));
        assertEquals("alte Marke (vor dem Update)", Boolean.FALSE,
                Selbsttest.autostart(inst, "2026-10-01 08:00:00", null, tz));
        long nachInst = 1_792_656_000_000L + 60_000L;   // 2026-10-22 08:01 UTC
        assertEquals(Boolean.TRUE, Selbsttest.autostart(inst, null,
                new JSONObject().put("ok", true).put("zeit", nachInst), tz));
        assertEquals(Boolean.FALSE, Selbsttest.autostart(inst, null,
                new JSONObject().put("ok", true).put("zeit", nachInst - 3_600_000L), tz));
        assertNull("Installationszeit unlesbar", Selbsttest.autostart("", "x", null, tz));
    }

    @Test
    public void tailscaleStatus() {
        Selbsttest.Fakten f = new Selbsttest.Fakten();
        Selbsttest.fernzugang(f, "{\"BackendState\":\"Running\",\"Self\":{\"Online\":true}}");
        assertTrue(f.fernzugangEingerichtet);
        assertEquals(Boolean.TRUE, f.fernzugangOnline);
        Selbsttest.Fakten g = new Selbsttest.Fakten();
        Selbsttest.fernzugang(g, "{\"BackendState\":\"NeedsLogin\"}");
        assertFalse(g.fernzugangEingerichtet);
        assertNull(g.fernzugangOnline);
        Selbsttest.Fakten h = new Selbsttest.Fakten();
        Selbsttest.fernzugang(h, "");
        assertFalse(h.fernzugangEingerichtet);
    }

    @Test
    public void planerAntwortZaehltNurMitSchluessel() {
        Selbsttest.planerAntwort(false, 500, 1L);
        Selbsttest.planerAntwort(true, 200, 2L);
        Selbsttest.planerAntwort(false, 401, 3L);
        Selbsttest.Fakten f = Selbsttest.messenPlanerFuerTest();
        assertEquals(200, f.planerLetzterCode);
        assertEquals(2L, f.planerLetzteZeit);
    }
}
