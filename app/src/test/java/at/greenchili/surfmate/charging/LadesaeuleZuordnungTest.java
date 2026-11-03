package at.greenchili.surfmate.charging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reine Zuordnung Ladesäule ↔ Ladung (Vorschlag v2 + Ergänzung Projektinhaber 03.11.2026). Testort Linz (öffentliche
 * Innenstadt), Säulen per Meter-Versatz erzeugt; synthetische Namen. Keine echten Ladeorte.
 */
public class LadesaeuleZuordnungTest {

    // Linz Hauptplatz-Gegend (öffentlich), nie ein privater Ort
    static final double LAT = 48.3059, LNG = 14.2862;

    static double[] versatz(double nordM, double ostM) {
        double dLat = nordM / 111_320.0;
        double dLng = ostM / (111_320.0 * Math.cos(Math.toRadians(LAT)));
        return new double[] {LAT + dLat, LNG + dLng};
    }

    static LadesaeuleZuordnung.Station st(String id, String name, String betr, String art, double kw, double nordM, double ostM) {
        double[] p = versatz(nordM, ostM);
        return new LadesaeuleZuordnung.Station(id, name, betr, art, kw, p[0], p[1], new ArrayList<>());
    }

    @Test
    public void anfrage_rundetAuf001Grad_ohneSchluessel_mitKartenIds() throws Exception {
        JSONArray karten = new JSONArray().put(new JSONObject().put("id", 13).put("anzeige", "Testkarte"));
        JSONObject b = LadesaeuleZuordnung.anfrage(48.30612, 14.28634, karten);
        assertEquals("48.31", b.get("lat").toString());
        assertEquals("14.29", b.get("lon").toString());
        assertEquals(2, b.getInt("radius"));
        assertEquals(13, b.getJSONArray("karten").getInt(0));
        String s = b.toString();
        assertFalse(s.contains("48.306"));
        assertFalse(s.contains("14.286"));
        assertFalse(s.toLowerCase().contains("token"));
        assertFalse(LadesaeuleZuordnung.anfrage(48.30612, 14.28634, null).has("karten"));
        // gleiche Zelle → gleiche Anfrage (Nachtragen fasst zusammen)
        assertEquals(LadesaeuleZuordnung.zelle(48.3061, 14.2863), LadesaeuleZuordnung.zelle(48.3089, 14.2911));
    }

    @Test
    public void eineDcSaeuleUnter60m_auto() {
        List<LadesaeuleZuordnung.Station> l = Arrays.asList(st("A1", "Supermarkt Test", "Testmarkt GmbH", "DC", 50, 30, 0));
        LadesaeuleZuordnung.Ergebnis e = LadesaeuleZuordnung.zuordnen(l, LAT, LNG, 1, 40);
        assertEquals(LadesaeuleZuordnung.AUTO, e.zuordnung);
        assertEquals("A1", e.station.station.id);
        assertEquals(30, e.station.abstandM, 1.0);
    }

    /** Projektinhaber: im Umkreis von 1 km mehrere Anbieter; zwei passende unter 60 m → nie automatisch, „prüfen“. */
    @Test
    public void mehrereAnbieterIm1kmUmkreis_zweiPassendeUnter60m_unsicher() throws Exception {
        List<LadesaeuleZuordnung.Station> l = Arrays.asList(
                st("L1", "Lidl Testort", "Lidl Test GmbH", "DC", 50, 25, 10),
                st("L2", "Lidl Testort", "Lidl Test GmbH", "AC", 22, 28, 12),
                st("E1", "Energie Schnell", "Energie Test AG", "DC", 150, -40, 5),
                st("S1", "Stadtwerk Platz", "Stadtwerk Test", "AC", 11, 300, 200),
                st("T1", "Tankstelle Nord", "Tank Test", "DC", 300, 800, -100),
                st("H1", "Hotel Ost", "Hotel Test", "AC", 11, -50, 900));
        LadesaeuleZuordnung.Ergebnis e = LadesaeuleZuordnung.zuordnen(l, LAT, LNG, 1, 45);
        assertEquals(LadesaeuleZuordnung.UNSICHER, e.zuordnung);
        assertEquals("L1", e.station.station.id);   // nächste passende als Vermutung
        // nur passende (DC) Kandidaten bis 150 m, die nächste zuerst
        assertEquals(2, e.kandidaten.size());
        assertEquals("L1", e.kandidaten.get(0).station.id);
        assertEquals("E1", e.kandidaten.get(1).station.id);
        // Kandidaten-JSON: Abstand ja, Koordinaten nie
        JSONArray k = e.kandidatenJson();
        assertTrue(k.getJSONObject(0).has("abstandM"));
        assertFalse(k.toString().contains("lat"));
        assertFalse(k.toString().contains("lon"));
    }

    @Test
    public void mehrereAnbieter_nurEinePassendeUnter60m_auto() {
        // Lidl DC 30 m, AC-Säule eines anderen Anbieters 20 m: AC zählt bei einer DC-Ladung nicht
        List<LadesaeuleZuordnung.Station> l = Arrays.asList(
                st("L1", "Lidl Testort", "Lidl Test GmbH", "DC", 50, 30, 0),
                st("X1", "Parkhaus", "Parken Test", "AC", 22, 0, 20),
                st("E1", "Energie Schnell", "Energie Test AG", "DC", 150, 0, 400));
        LadesaeuleZuordnung.Ergebnis e = LadesaeuleZuordnung.zuordnen(l, LAT, LNG, 1, 45);
        assertEquals(LadesaeuleZuordnung.AUTO, e.zuordnung);
        assertEquals("L1", e.station.station.id);
        // dieselbe Lage als AC-Ladung: jetzt gewinnt die AC-Säule
        e = LadesaeuleZuordnung.zuordnen(l, LAT, LNG, 0, 11);
        assertEquals(LadesaeuleZuordnung.AUTO, e.zuordnung);
        assertEquals("X1", e.station.station.id);
    }

    @Test
    public void leistungZuHochFuerSaeule_passtNicht() {
        List<LadesaeuleZuordnung.Station> l = Arrays.asList(st("A1", "Test", "Test GmbH", "DC", 50, 20, 0));
        assertEquals(LadesaeuleZuordnung.KEINE, LadesaeuleZuordnung.zuordnen(l, LAT, LNG, 1, 80).zuordnung);
        assertEquals(LadesaeuleZuordnung.AUTO, LadesaeuleZuordnung.zuordnen(l, LAT, LNG, 1, 57.4).zuordnung);
        assertEquals(LadesaeuleZuordnung.KEINE, LadesaeuleZuordnung.zuordnen(l, LAT, LNG, 1, 57.6).zuordnung);
    }

    @Test
    public void abstand60bis150m_unsicher_ueber150m_keine() {
        assertEquals(LadesaeuleZuordnung.UNSICHER, LadesaeuleZuordnung.zuordnen(
                Arrays.asList(st("A1", "Test", "Test GmbH", "AC", 22, 100, 0)), LAT, LNG, 0, 11).zuordnung);
        assertEquals(LadesaeuleZuordnung.KEINE, LadesaeuleZuordnung.zuordnen(
                Arrays.asList(st("A1", "Test", "Test GmbH", "AC", 22, 160, 0)), LAT, LNG, 0, 11).zuordnung);
    }

    @Test
    public void zweitePassendeSaeuleDichtDaneben_unsicher() {
        // erste 55 m, zweite 70 m, aber nur ~15 m von der ersten entfernt
        List<LadesaeuleZuordnung.Station> l = Arrays.asList(
                st("A1", "Nord", "Eins GmbH", "DC", 50, 55, 0),
                st("B1", "Nord2", "Zwei GmbH", "DC", 50, 70, 0));
        assertEquals(LadesaeuleZuordnung.UNSICHER, LadesaeuleZuordnung.zuordnen(l, LAT, LNG, 1, 40).zuordnung);
    }

    @Test
    public void doppelterEintragGleicherNameUndBetreiber_zaehltEinmal() {
        List<LadesaeuleZuordnung.Station> l = Arrays.asList(
                st("A1", "Markt", "Markt GmbH", "DC", 50, 20, 0),
                st("A2", "Markt", "Markt GmbH", "DC", 50, 24, 3));
        LadesaeuleZuordnung.Ergebnis e = LadesaeuleZuordnung.zuordnen(l, LAT, LNG, 1, 40);
        assertEquals(LadesaeuleZuordnung.AUTO, e.zuordnung);
        assertEquals(1, e.kandidaten.size());
    }

    @Test
    public void stromartUnbekannt_beidePassen_ohnePosition_keine() {
        List<LadesaeuleZuordnung.Station> l = Arrays.asList(
                st("A1", "AC", "Eins", "AC", 22, 20, 0),
                st("B1", "DC", "Zwei", "DC", 50, 40, 0));
        assertEquals(LadesaeuleZuordnung.UNSICHER, LadesaeuleZuordnung.zuordnen(l, LAT, LNG, -1, -1).zuordnung);
        assertEquals(LadesaeuleZuordnung.KEINE, LadesaeuleZuordnung.zuordnen(l, 0, 0, 1, 40).zuordnung);
    }

    @Test
    public void planerAntwortLesen_hpcIstDc_tarifeNurEigeneAdhocUndBetreiber() throws Exception {
        double[] p = versatz(10, 0);
        JSONObject s = new JSONObject()
                .put("id", "AT*TST*1").put("name", "Testmarkt, Hauptstraße").put("betreiber", "Testmarkt GmbH ")
                .put("art", "HPC").put("kw", 150).put("lat", p[0]).put("lon", p[1])
                .put("alle_zahlungsarten", new JSONArray()
                        .put(za("Testmarkt App", "App AT", 0.19, 0, false, false, "TESTMARKT", "*", "2026-10-21"))
                        .put(za("Ad-hoc", "Direkt", 0.45, 0, false, true, "TESTMARKT", "AT*TST*1", "2026-11-03"))
                        .put(za("Fremdkarte", "Roaming", 0.62, 0, false, false, "*", "*", "2026-10-22"))
                        .put(za("Meine Karte", "Tarif", 0.39, 0, true, false, "*", "*", "2026-10-30"))
                        .put(za("Zeitkarte", "Minute", 0, 0.19, false, false, "TESTMARKT", "*", "2026-10-22")));
        JSONObject ohneOrt = new JSONObject().put("name", "x").put("art", "AC");          // keine Position → weg
        JSONObject wechsel = new JSONObject().put("name", "y").put("art", "Wechselrichter").put("lat", p[0]).put("lon", p[1]);
        List<LadesaeuleZuordnung.Station> l = LadesaeuleZuordnung.ausPlaner(new JSONObject()
                .put("stationen", new JSONArray().put(s).put(ohneOrt).put(wechsel)));
        assertEquals(1, l.size());
        LadesaeuleZuordnung.Station x = l.get(0);
        assertEquals("DC", x.art);
        assertEquals("Testmarkt GmbH", x.betreiber);
        assertEquals(3, x.tarife.size());   // App (Betreiber), ad hoc, meine; Fremdkarte und Zeittarif nicht
        assertEquals(0.19, x.tarife.get(0).preisKwh, 1e-9);
        assertTrue(x.tarife.get(1).adhoc);
        assertEquals("2026-11-03", x.tarife.get(1).stand);
        assertTrue(x.tarife.get(2).meine);
    }

    static JSONObject za(String msp, String tarif, double kwh, double min, boolean meine, boolean adhoc, String cpo,
                         String station, String stand) throws Exception {
        return new JSONObject().put("msp", msp).put("tarif", tarif).put("preis_kwh", kwh).put("preis_min", min)
                .put("besitze_ich", meine).put("ist_adhoc", adhoc).put("regel_cpo", cpo).put("regel_station", station)
                .put("herkunft", new JSONObject().put("preis_stand", stand));
    }

    @Test
    public void privat_zuhause_ruhezone_fragtNicht() throws Exception {
        JSONObject zuhause = new JSONObject().put("lat", LAT).put("lon", LNG).put("r", 100)
                .put("eigenschaften", new JSONObject().put("zuhause", true));
        JSONObject privatOrt = new JSONObject().put("lat", LAT).put("lon", LNG).put("r", 100)
                .put("eigenschaften", new JSONObject().put("privat", true));
        JSONObject nurTarif = new JSONObject().put("lat", LAT).put("lon", LNG).put("r", 100)
                .put("eigenschaften", new JSONObject().put("tarif", "t1"));
        assertTrue(LadesaeuleZuordnung.privat(new JSONArray().put(zuhause), null, LAT, LNG));
        assertTrue(LadesaeuleZuordnung.privat(new JSONArray().put(privatOrt), null, LAT, LNG));
        assertFalse(LadesaeuleZuordnung.privat(new JSONArray().put(nurTarif), null, LAT, LNG));
        assertTrue(LadesaeuleZuordnung.privat(null,
                new JSONArray().put(new JSONObject().put("lat", LAT).put("lon", LNG).put("r", 80)), LAT, LNG));
        double[] weit = versatz(1000, 0);
        assertFalse(LadesaeuleZuordnung.privat(new JSONArray().put(zuhause).put(privatOrt), null, weit[0], weit[1]));
    }

    @Test
    public void gemerkt_nurPassenderTarifUndStromart() throws Exception {
        JSONArray g = new JSONArray().put(new JSONObject().put("tarif", "t7").put("id", "AT*TST*1").put("name", "Testmarkt")
                .put("betreiber", "Testmarkt GmbH").put("art", "DC"));
        assertNotNull(LadesaeuleZuordnung.gemerkt(g, "t7", 1));
        assertNull(LadesaeuleZuordnung.gemerkt(g, "t7", 0));
        assertNull(LadesaeuleZuordnung.gemerkt(g, "t8", 1));
        assertNull(LadesaeuleZuordnung.gemerkt(g, "", 1));
    }
}
