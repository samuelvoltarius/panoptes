package at.greenchili.surfmate.ha;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.auth.Kopplungen;
import at.greenchili.surfmate.server.ExternGuard;
import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** HA-Geräte Stufe 2 (0.1.38.8): Tor und Haustür-Schloss steuern, Echo, Ratenlimit, Heimkommen, Recht, Pfade. */
public class HaSteuernTest {

    private static final String KID = "k-ha";

    /** Gefälschtes HA: merkt sich gesendete Befehle; {@code verbunden=false} = kein Stream. */
    static final class FalschesHa implements HaSteuern.Sender {
        final List<String[]> befehle = new ArrayList<>();
        boolean verbunden = true;

        @Override
        public boolean sende(String kid, JSONObject befehl) {
            if (!verbunden) return false;
            befehle.add(new String[]{kid, befehl.toString()});
            return true;
        }
    }

    static final class Protokoll implements HaSteuern.Melder {
        final List<String> zeilen = new ArrayList<>();

        @Override
        public void aktion(String art, String aktion, String entity, String herkunft, String ergebnis) {
            zeilen.add(art + "." + aktion + ":" + ergebnis + "@" + herkunft);
        }
    }

    private static JSONObject g(String entity, String art, String klasse) throws JSONException {
        JSONObject o = new JSONObject().put("entity", entity).put("art", art).put("titel", "Mein " + entity)
                .put("raum", "Garage").put("zustand", "closed");
        if (klasse != null) o.put("deviceClass", klasse);
        return o;
    }

    /** Bestand mit Garagentor, Haustür, Steckdose; HA-Kopplung KID. */
    private static JSONObject bestand() throws JSONException {
        JSONArray a = new JSONArray().put(g("cover.garage", "tor", "garage")).put(g("lock.haustuer", "schloss", null))
                .put(g("switch.steckdose", "schalter", null));
        HaGeraete.Meldung m = HaGeraete.pruefe(new JSONObject().put("v", 1).put("geraete", a).toString());
        return HaGeraete.zusammenfuehren(null, m, 1L).put("kid", KID);
    }

    private static JSONObject frei(JSONObject b, String... entities) throws JSONException {
        for (String e : entities) b = HaGeraete.setzeFreigabe(b, e, true);
        return b;
    }

    @Test
    public void nurTorUndSchlossDienste() {
        assertEquals("cover.open_cover", HaSteuern.dienst("tor", "oeffnen"));
        assertEquals("cover.close_cover", HaSteuern.dienst("tor", "schliessen"));
        assertEquals("lock.unlock", HaSteuern.dienst("schloss", "aufsperren"));
        assertEquals("lock.lock", HaSteuern.dienst("schloss", "zusperren"));
        assertNull(HaSteuern.dienst("tor", "aufsperren"));
        assertNull(HaSteuern.dienst("schalter", "oeffnen"));
        assertNull(HaSteuern.dienst("alarm", "oeffnen"));
        assertTrue(HaSteuern.oeffnet("oeffnen") && HaSteuern.oeffnet("aufsperren"));
        assertFalse(HaSteuern.oeffnet("schliessen") || HaSteuern.oeffnet("zusperren"));
    }

    @Test
    public void steuernStandardAusUndNurFuerTorUndSchloss() throws Exception {
        JSONObject b = bestand();
        assertFalse(HaGeraete.freigegeben(b, "cover.garage"));
        assertNull("Steckdose nicht in Stufe 2", HaGeraete.setzeFreigabe(b, "switch.steckdose", true));
        assertNull("unbekannt", HaGeraete.setzeFreigabe(b, "cover.fremd", true));
        JSONObject f = HaGeraete.setzeFreigabe(b, "cover.garage", true);
        assertTrue(HaGeraete.freigegeben(f, "cover.garage"));
        assertFalse("alter Bestand unverändert", HaGeraete.freigegeben(b, "cover.garage"));
        assertEquals(0, HaGeraete.fuerIntegration(f, false).getJSONArray("freigaben").length());
        assertEquals(1, HaGeraete.fuerIntegration(f, true).getJSONArray("freigaben").length());
        JSONObject anz = HaGeraete.fuerAnzeige(f);
        assertEquals(2, anz.getInt("stufe"));
        assertTrue(anz.getJSONArray("geraete").getJSONObject(0).getBoolean("steuerbar"));
    }

    @Test
    public void auftragNurMitFreigabeRechtUndStream() throws Exception {
        FalschesHa ha = new FalschesHa();
        Protokoll p = new Protokoll();
        HaSteuern s = new HaSteuern(ha, p);
        JSONObject b = bestand();
        assertEquals("nicht_freigegeben", s.auftrag(b, KID, true, "cover.garage", "oeffnen", "display", 0).getString("grund"));
        b = frei(b, "cover.garage", "lock.haustuer");
        assertEquals("recht_fehlt", s.auftrag(b, KID, false, "cover.garage", "oeffnen", "display", 0).getString("grund"));
        assertEquals("keine_kopplung", s.auftrag(b, null, true, "cover.garage", "oeffnen", "display", 0).getString("grund"));
        assertEquals("aktion", s.auftrag(b, KID, true, "cover.garage", "aufsperren", "display", 0).getString("grund"));
        assertEquals("unbekannt", s.auftrag(b, KID, true, "alarm_control_panel.haus", "oeffnen", "display", 0).getString("grund"));
        assertEquals("aktion", s.auftrag(b, KID, true, "switch.steckdose", "oeffnen", "display", 0).getString("grund"));
        ha.verbunden = false;
        assertEquals("nicht_verbunden", s.auftrag(b, KID, true, "cover.garage", "oeffnen", "display", 0).getString("grund"));
        assertTrue("nichts gesendet", ha.befehle.isEmpty());
        ha.verbunden = true;
        JSONObject r = s.auftrag(b, KID, true, "cover.garage", "oeffnen", "display", 1000);
        assertTrue(r.getBoolean("ok"));
        assertTrue(r.getString("id").matches("b-[0-9a-f]{16}"));
        assertEquals(1, ha.befehle.size());
        assertEquals(KID, ha.befehle.get(0)[0]);
        JSONObject bef = new JSONObject(ha.befehle.get(0)[1]);
        assertEquals("cover.open_cover", bef.getString("dienst"));
        assertEquals("cover.garage", bef.getString("entity"));
        assertFalse("keine Namen/Räume im Befehl", ha.befehle.get(0)[1].contains("Mein") || ha.befehle.get(0)[1].contains("Garage\""));
        assertTrue(p.zeilen.contains("tor.oeffnen:gesendet@display"));
        assertTrue("jede Ablehnung protokolliert", p.zeilen.contains("tor.oeffnen:abgelehnt:recht_fehlt@display"));
    }

    @Test
    public void ratenlimitJeGeraetUndGesamt() throws Exception {
        FalschesHa ha = new FalschesHa();
        HaSteuern s = new HaSteuern(ha, new Protokoll());
        JSONObject b = frei(bestand(), "cover.garage", "lock.haustuer");
        assertTrue(s.auftrag(b, KID, true, "cover.garage", "schliessen", "login", 10_000).getBoolean("ok"));
        assertEquals("zu_oft", s.auftrag(b, KID, true, "cover.garage", "schliessen", "login", 11_000).getString("grund"));
        assertTrue("anderes Gerät", s.auftrag(b, KID, true, "lock.haustuer", "zusperren", "login", 11_000).getBoolean("ok"));
        assertTrue(s.auftrag(b, KID, true, "cover.garage", "schliessen", "login", 13_000).getBoolean("ok"));
        long t = 13_000;
        int ok = 3;
        while (ok < HaSteuern.MAX_JE_MINUTE) {
            t += 3_000;
            assertTrue(s.auftrag(b, KID, true, ok % 2 == 0 ? "cover.garage" : "lock.haustuer",
                    ok % 2 == 0 ? "schliessen" : "zusperren", "login", t).getBoolean("ok"));
            ok++;
        }
        assertEquals("zu_oft", s.auftrag(b, KID, true, "cover.garage", "schliessen", "login", t + 3_000).getString("grund"));
        assertTrue("nach einer Minute wieder", s.auftrag(b, KID, true, "cover.garage", "schliessen", "login", 80_000).getBoolean("ok"));
    }

    @Test
    public void erfolgNurMitPassendemEcho() throws Exception {
        FalschesHa ha = new FalschesHa();
        Protokoll p = new Protokoll();
        HaSteuern s = new HaSteuern(ha, p);
        JSONObject b = frei(bestand(), "lock.haustuer");
        String id = s.auftrag(b, KID, true, "lock.haustuer", "aufsperren", "display", 0).getString("id");
        assertEquals(HaSteuern.OFFEN, s.status(id, 1).getString("status"));
        JSONObject echo = new JSONObject().put("id", id).put("ok", true).put("zustand", "unlocked");
        assertFalse("fremde Kopplung", s.echo("k-andere", echo, 2).getBoolean("ok"));
        assertFalse("unbekannte id", s.echo(KID, new JSONObject().put("id", "b-0000000000000000").put("ok", true), 2).getBoolean("ok"));
        assertTrue(s.echo(KID, echo, 2).getBoolean("ok"));
        assertEquals("schon_beantwortet", s.echo(KID, echo, 3).getString("grund"));
        JSONObject st = s.status(id, 4);
        assertEquals(HaSteuern.OK, st.getString("status"));
        assertEquals("unlocked", st.getString("zustand"));
        assertTrue(p.zeilen.contains("schloss.aufsperren:ok@display"));
        assertEquals(id, s.letzter(5).getString("id"));
    }

    @Test
    public void ohneEchoNach30sNichtBestaetigt() throws Exception {
        Protokoll p = new Protokoll();
        HaSteuern s = new HaSteuern(new FalschesHa(), p);
        JSONObject b = frei(bestand(), "cover.garage");
        String id = s.auftrag(b, KID, true, "cover.garage", "oeffnen", "heimkommen", 0).getString("id");
        s.aufraeumen(HaSteuern.ABLAUF_MS - 1);
        assertEquals(HaSteuern.OFFEN, s.status(id, HaSteuern.ABLAUF_MS - 1).getString("status"));
        assertEquals(HaSteuern.NICHT_BESTAETIGT, s.status(id, HaSteuern.ABLAUF_MS).getString("status"));
        assertTrue(p.zeilen.contains("tor.oeffnen:nicht_bestaetigt@heimkommen"));
        assertFalse("spätes Echo zählt nicht", s.echo(KID, new JSONObject().put("id", id).put("ok", true),
                HaSteuern.ABLAUF_MS + 5).getBoolean("ok"));
        s.vergessen(KID);
        assertNull(s.status(id, HaSteuern.ABLAUF_MS + 6));
    }

    @Test
    public void torAutomatikNurMitFreigegebenemTorUndZone() throws Exception {
        JSONObject b = bestand();
        assertNull("Tor nicht freigegeben", HaGeraete.setzeAutomatik(b, "cover.garage", "z1"));
        b = frei(b, "cover.garage", "lock.haustuer");
        assertNull("Haustür nie automatisch", HaGeraete.setzeAutomatik(b, "lock.haustuer", "z1"));
        assertNull("ohne Zone nie", HaGeraete.setzeAutomatik(b, "cover.garage", null));
        JSONObject a = HaGeraete.setzeAutomatik(b, "cover.garage", "z1");
        assertEquals("cover.garage", a.getJSONObject("automatik").getString("tor"));
        // HA meldet weiter → Automatik bleibt; Freigabe aus → Automatik weg
        HaGeraete.Meldung m = HaGeraete.pruefe(new JSONObject().put("v", 1)
                .put("geraete", new JSONArray().put(g("cover.garage", "tor", "garage"))).toString());
        assertTrue(HaGeraete.zusammenfuehren(a, m, 2L).has("automatik"));
        assertFalse(HaGeraete.setzeFreigabe(a, "cover.garage", false).has("automatik"));
        assertFalse(HaGeraete.zusammenfuehren(a, HaGeraete.pruefe("{\"v\":1,\"geraete\":[]}"), 3L).has("automatik"));
        assertFalse("aus", HaGeraete.setzeAutomatik(a, null, null).has("automatik"));
    }

    // ------------------------------------------------------------------ Heimkommen

    private static Heimkommen.Lage lage(double abstand, String gang, double kmh) {
        return new Heimkommen.Lage(abstand, 80, gang, kmh, true, 1_000, 10);
    }

    @Test
    public void heimkommenEinmalJeAnkunft() {
        Heimkommen h = new Heimkommen();
        assertFalse(h.takt(lage(400, "D", 40)));
        assertTrue(h.scharf());
        assertFalse(h.takt(lage(120, "D", 18)));
        assertFalse("weg vom Haus", h.takt(lage(130, "D", 18)));
        assertTrue(h.takt(lage(70, "D", 15)));
        assertFalse("höchstens 1×", h.takt(lage(40, "D", 10)));
        assertFalse(h.takt(lage(20, "R", 5)));
        assertFalse("weg, aber noch in der Hysterese", h.takt(lage(150, "D", 30)));
        assertFalse(h.takt(lage(70, "D", 15)));
        assertFalse(h.takt(lage(60, "D", 15)));
        assertFalse(h.takt(lage(181, "D", 40)));
        assertFalse("wieder scharf, noch außerhalb", h.takt(lage(100, "D", 15)));
        assertTrue("nächste Ankunft", h.takt(lage(75, "D", 15)));
    }

    @Test
    public void heimkommenNieBeiUnklarheit() {
        String[][] faelle = {{"P", "5"}, {"N", "5"}, {null, "5"}, {"D", "20"}, {"D", "NaN"}, {"D", "-1"}};
        for (String[] f : faelle) {
            Heimkommen h = new Heimkommen();
            h.takt(lage(400, "D", 40));
            h.takt(lage(90, f[0], Double.parseDouble(f[1])));
            assertFalse(f[0] + "/" + f[1], h.takt(lage(60, f[0], Double.parseDouble(f[1]))));
        }
        // Zündung aus, altes GPS, ungenaues GPS, keine Zone/Daten, Abstand nimmt zu
        Heimkommen h = new Heimkommen();
        h.takt(lage(400, "D", 40));
        h.takt(lage(90, "D", 10));
        assertFalse(h.takt(new Heimkommen.Lage(60, 80, "D", 10, false, 1_000, 10)));
        assertFalse(h.takt(new Heimkommen.Lage(50, 80, "D", 10, true, 60_000, 10)));
        assertFalse(h.takt(new Heimkommen.Lage(40, 80, "D", 10, true, 1_000, 200)));
        assertFalse(h.takt(null));
        assertFalse("nach Lücke erst zwei gute Werte", h.takt(lage(30, "D", 10)));
        assertFalse("weg vom Haus", h.takt(lage(35, "D", 10)));
        assertTrue("noch scharf, weil nie ausgelöst", h.scharf());
        assertTrue(h.takt(lage(20, "D", 10)));
    }

    @Test
    public void heimkommenNachNeustartImBereichNie() {
        Heimkommen h = new Heimkommen();
        assertFalse(h.takt(lage(70, "D", 10)));
        assertFalse(h.takt(lage(50, "D", 10)));
        assertFalse(h.takt(lage(30, "R", 5)));
        h.takt(lage(400, "D", 30));
        h.zuruecksetzen();
        h.takt(lage(70, "D", 10));
        assertFalse("neue Automatik: unscharf", h.takt(lage(50, "D", 10)));
    }

    // ------------------------------------------------------------------ Recht, Pfade, Wege

    @Test
    public void meldeAnNurAnDieseKopplung() throws Exception {
        HaEreignisse.Abo a = HaEreignisse.abonnieren("k-test-a");
        HaEreignisse.Abo b = HaEreignisse.abonnieren("k-test-b");
        try {
            assertTrue(HaEreignisse.meldeAn("k-test-a", "befehl", new JSONObject().put("id", "b-1")));
            assertEquals("befehl", a.naechstes(10)[0]);
            assertNull(b.naechstes(10));
            assertFalse(HaEreignisse.meldeAn("k-ohne-stream", "befehl", new JSONObject()));
            assertFalse(HaEreignisse.meldeAn(null, "befehl", new JSONObject()));
        } finally {
            HaEreignisse.abmelden(a);
            HaEreignisse.abmelden(b);
        }
    }

    @Test
    public void steuerRechtGetrenntNurHomeAssistant() {
        assertTrue(Kopplungen.HA_RECHTE.contains(Kopplungen.RECHT_GERAETE_STEUERN));
        assertFalse(Kopplungen.RECHTE.contains(Kopplungen.RECHT_GERAETE_STEUERN));
        assertFalse(Kopplungen.FREMD_RECHTE.contains(Kopplungen.RECHT_GERAETE_STEUERN));
        assertFalse(Kopplungen.COCKPIT_RECHTE.contains(Kopplungen.RECHT_GERAETE_STEUERN));
        assertFalse(ExternGuard.blocks("POST", "/api/ha/v1/geraete/echo", "homeassistant"));
        assertTrue(ExternGuard.blocks("GET", "/api/ha/v1/geraete/echo", "homeassistant"));
        for (String art : new String[]{"cockpit", "handy", "dienst", "web"}) {
            assertTrue(art, ExternGuard.blocks("POST", "/api/ha/v1/geraete/echo", art));
            assertTrue(art, ExternGuard.blocks("POST", "/api/ha/geraete/befehl", art));
        }
        assertTrue("Befehl nie von Kopplungen", ExternGuard.blocks("POST", "/api/ha/geraete/befehl", "homeassistant"));
        assertEquals(Kopplungen.RECHT_GERAETE_STEUERN, ExternGuard.noetigesRecht("/api/ha/v1/geraete/echo"));
        assertTrue(at.greenchili.surfmate.server.WebHandyGuard.blocks("POST", "/api/ha/geraete/befehl"));
        assertTrue(at.greenchili.surfmate.server.WebHandyGuard.blocks("POST", "/api/ha/geraete/automatik"));
    }

    @Test
    public void meldungOhneNamenUndOeffnenNurAmAuto() throws Exception {
        String d = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/ha/HaSteuerDienst.java"));
        assertTrue("Log nur Kurzschlüssel", d.contains("HaSteuern.kurzHash(entity), null);"));
        assertFalse("nie Titel/Raum in Meldungen", d.contains("optString(\"titel\"") || d.contains("optString(\"raum\""));
        assertTrue("nur Daemon-Takt löst die Automatik aus", d.contains("auftrag(tor, \"oeffnen\", \"heimkommen\", jetzt);"));
        String h = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/HaApiHandler.java"));
        assertTrue(h.contains("if (at.greenchili.surfmate.ha.HaSteuern.oeffnet(aktion) && !amAuto) {"));
        assertTrue(h.contains("if (!req.optBoolean(\"bestaetigt\", false)) {"));
        assertTrue(h.contains("AuthMiddleware.isExternRequest() || AuthMiddleware.isHandyWebRequest()"));
        assertFalse("Kurzschlüssel verrät die Entität nicht", HaSteuern.kurzHash("cover.garage").contains("garage"));
        assertTrue(HaSteuern.kurzHash("cover.garage").matches("g-[0-9a-f]{8}"));
    }
}
