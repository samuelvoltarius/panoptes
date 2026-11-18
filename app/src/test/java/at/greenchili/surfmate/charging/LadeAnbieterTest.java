package at.greenchili.surfmate.charging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Eigene Ladeanbieter (0.1.43.15): Anlegen, Auswählen in der Ladenachfrage, Preis passend zu AC/DC.
 * Synthetisch – Namen und Preise sind Testwerte, keine echten Anbieterpreise.
 */
public class LadeAnbieterTest {

    private static JSONObject cfgMit(JSONArray liste) throws Exception {
        return new JSONObject().put(LadeAnbieter.ABSCHNITT, new JSONObject().put(LadeAnbieter.LISTE, liste));
    }

    @Test
    public void anlegen_pruefen_und_gleichnamigAendern() throws Exception {
        LadeAnbieter.Ergebnis e = LadeAnbieter.anlegen(new JSONArray(),
                new JSONObject().put("name", "  Test\nAnbieter ").put("preisDc", 0.5).put("grundgebuehr", 4.99).put("karte", true));
        assertTrue(e.ok());
        assertTrue(e.neu);
        assertEquals("a1", e.anbieter.getString("id"));
        assertEquals("Test Anbieter", e.anbieter.getString("name"));
        assertTrue(e.anbieter.isNull("preisAc"));
        assertEquals(0.5, e.anbieter.getDouble("preisDc"), 1e-9);
        assertEquals(4.99, e.anbieter.getDouble("grundgebuehr"), 1e-9);
        assertTrue(e.anbieter.getBoolean("karte"));

        // gleicher Name (Groß/klein egal): ändert, kein zweiter Eintrag; DC-Preis bleibt, AC kommt dazu
        LadeAnbieter.Ergebnis e2 = LadeAnbieter.anlegen(e.liste, new JSONObject().put("name", "test anbieter").put("preisAc", 0.39));
        assertTrue(e2.ok());
        assertFalse(e2.neu);
        assertEquals(1, e2.liste.length());
        assertEquals("a1", e2.anbieter.getString("id"));
        assertEquals(0.39, e2.anbieter.getDouble("preisAc"), 1e-9);
        assertEquals(0.5, e2.anbieter.getDouble("preisDc"), 1e-9);

        LadeAnbieter.Ergebnis e3 = LadeAnbieter.anlegen(e2.liste, new JSONObject().put("name", "Zweiter").put("preisAc", 0));
        assertTrue(e3.ok());
        assertEquals("a2", e3.anbieter.getString("id"));   // 0 = gratis ist ein gültiger Preis
        assertEquals(0.0, e3.anbieter.getDouble("preisAc"), 0);

        assertEquals("name", LadeAnbieter.anlegen(new JSONArray(), new JSONObject().put("name", "   ").put("preisAc", 1)).grund);
        assertEquals("preis", LadeAnbieter.anlegen(new JSONArray(), new JSONObject().put("name", "X")).grund);
        assertEquals("preisAc", LadeAnbieter.anlegen(new JSONArray(), new JSONObject().put("name", "X").put("preisAc", -1)).grund);
        assertEquals("preisDc", LadeAnbieter.anlegen(new JSONArray(), new JSONObject().put("name", "X").put("preisDc", "0,5")).grund);
        assertEquals("preisDc", LadeAnbieter.anlegen(new JSONArray(), new JSONObject().put("name", "X").put("preisDc", 101)).grund);
        assertEquals("grundgebuehr", LadeAnbieter.anlegen(new JSONArray(),
                new JSONObject().put("name", "X").put("preisDc", 1).put("grundgebuehr", -2)).grund);
        assertEquals("karte", LadeAnbieter.anlegen(new JSONArray(),
                new JSONObject().put("name", "X").put("preisDc", 1).put("karte", "ja")).grund);
        assertEquals(60, LadeAnbieter.anlegen(new JSONArray(), new JSONObject()
                .put("name", new String(new char[90]).replace('\0', 'x')).put("preisAc", 1)).anbieter.getString("name").length());

        JSONArray voll = new JSONArray();
        for (int i = 0; i < LadeAnbieter.MAX; i++) voll = LadeAnbieter.anlegen(voll, new JSONObject().put("name", "N" + i).put("preisAc", 1)).liste;
        assertEquals("max " + LadeAnbieter.MAX, LadeAnbieter.anlegen(voll, new JSONObject().put("name", "Neu").put("preisAc", 1)).grund);
        assertTrue(LadeAnbieter.anlegen(voll, new JSONObject().put("name", "n3").put("preisAc", 2)).ok());   // ändern geht weiter
    }

    @Test
    public void liste_bereinigt_kaputteEintraege() throws Exception {
        JSONArray roh = new JSONArray()
                .put(new JSONObject().put("id", "a1").put("name", "Gut").put("preisDc", 0.6))
                .put(new JSONObject().put("id", "").put("name", "ohne id").put("preisDc", 0.6))
                .put("kein objekt")
                .put(new JSONObject().put("id", "a3").put("name", "Komisch").put("preisAc", "x").put("preisDc", 500));
        JSONArray l = LadeAnbieter.liste(cfgMit(roh));
        assertEquals(2, l.length());
        assertEquals("Gut", l.getJSONObject(0).getString("name"));
        assertTrue(l.getJSONObject(1).isNull("preisAc"));
        assertTrue(l.getJSONObject(1).isNull("preisDc"));   // über der Obergrenze → nicht verwendet
        assertEquals(0, LadeAnbieter.liste(null).length());
        assertEquals(0, LadeAnbieter.liste(new JSONObject()).length());   // Bestand ohne Abschnitt: leer, kein Fehler
    }

    @Test
    public void preisFuer_acDcUnbekannt() throws Exception {
        JSONObject beide = new JSONObject().put("preisAc", 0.4).put("preisDc", 0.6);
        assertEquals(0.6, LadeAnbieter.preisFuer(beide, 1), 1e-9);
        assertEquals(0.4, LadeAnbieter.preisFuer(beide, 0), 1e-9);
        assertNull(LadeAnbieter.preisFuer(beide, -1));   // unbekannt + verschieden: Nutzer trägt ein
        JSONObject nurDc = new JSONObject().put("preisAc", JSONObject.NULL).put("preisDc", 0.6);
        assertNull(LadeAnbieter.preisFuer(nurDc, 0));
        assertEquals(0.6, LadeAnbieter.preisFuer(nurDc, -1), 1e-9);
        JSONObject gleich = new JSONObject().put("preisAc", 0.5).put("preisDc", 0.5);
        assertEquals(0.5, LadeAnbieter.preisFuer(gleich, -1), 1e-9);
    }

    @Test
    public void nachfrage_zeigtAnbieterMitPreisJeStromart_undBeantwortet() throws Exception {
        JSONArray liste = LadeAnbieter.anlegen(new JSONArray(),
                new JSONObject().put("name", "Testanbieter").put("preisAc", 0.39).put("preisDc", 0.5)).liste;
        JSONObject cfg = cfgMit(liste);
        // DC-Ladung ohne bekannten Ort, aber mit Position
        JSONObject z = new JSONObject().put("id", 42).put("start", LadeNachfrage.SEIT + 1000).put("ende", LadeNachfrage.SEIT + 5000)
                .put("kwh", 20.3).put("dc", true).put("rate", -1).put("tariffId", "").put("tariffLabel", "").put("quelle", "")
                .put("nachfrage", "").put("lat", 47.8).put("lng", 13.0);
        JSONObject l = LadeNachfrage.liste(new JSONArray().put(z), cfg, new JSONArray(), null, true);
        JSONObject e = l.getJSONArray("offen").getJSONObject(0);
        assertTrue(e.getBoolean("hatPosition"));
        assertFalse(e.toString().contains("47.8"));   // keine Koordinaten nach außen
        JSONObject a0 = e.getJSONArray("auswahl").getJSONObject(0);
        assertEquals("anbieter", a0.getString("art"));
        assertEquals("a1", a0.getString("id"));
        assertEquals("Testanbieter", a0.getString("anzeige"));
        assertEquals(0.5, a0.getDouble("preisKwh"), 1e-9);   // DC-Preis für die DC-Ladung
        // die bisherige Auswahl bleibt dahinter vollständig
        JSONArray aw = e.getJSONArray("auswahl");
        assertEquals("zuhause_gratis", aw.getJSONObject(aw.length() - 1).getString("art"));

        // Antwort: Preis vom Anbieter, überschrieben auf 0.45 → Nutzerpreis mit Anbietername
        String[] g = new String[1];
        JSONObject p = LadeNachfrage.alsPreisAnfrage(new JSONObject().put("id", 42).put("art", "anbieter")
                .put("anbieterId", "a1").put("preisKwh", 0.45), cfg, g);
        assertNotNull(p);
        assertEquals(0.45, p.getDouble("rate"), 1e-9);
        assertEquals("Testanbieter", p.getString("label"));
        assertEquals(LadePreis.QUELLE_NUTZER, p.getString("quelle"));
        // unbekannter Anbieter bzw. fehlender Preis
        assertNull(LadeNachfrage.alsPreisAnfrage(new JSONObject().put("id", 42).put("art", "anbieter")
                .put("anbieterId", "a9").put("preisKwh", 0.45), cfg, g));
        assertEquals("anbieterId", g[0]);
        assertNull(LadeNachfrage.alsPreisAnfrage(new JSONObject().put("id", 42).put("art", "anbieter")
                .put("anbieterId", "a1"), cfg, g));
        assertEquals("preisKwh", g[0]);
        // 0 = gratis
        JSONObject gr = LadeNachfrage.alsPreisAnfrage(new JSONObject().put("id", 42).put("art", "anbieter")
                .put("anbieterId", "a1").put("preisKwh", 0), cfg, g);
        assertTrue(gr.getBoolean("gratis"));
    }

    @Test
    public void erkannterBetreiber_schlaegtEigenenAnbieterVor() throws Exception {
        JSONArray liste = LadeAnbieter.anlegen(new JSONArray(),
                new JSONObject().put("name", "Testanbieter").put("preisDc", 0.5)).liste;
        JSONObject z = new JSONObject().put("id", 5).put("start", LadeNachfrage.SEIT + 1000).put("ende", LadeNachfrage.SEIT + 5000)
                .put("kwh", 10).put("dc", true).put("rate", -1).put("tariffId", "").put("tariffLabel", "").put("quelle", "")
                .put("nachfrage", "").put("lat", 0).put("lng", 0).put("betreiber", "Testanbieter Ladepark GmbH");
        JSONObject e = LadeNachfrage.liste(new JSONArray().put(z), cfgMit(liste), new JSONArray(), null, true)
                .getJSONArray("offen").getJSONObject(0);
        assertFalse(e.getBoolean("hatPosition"));
        JSONObject v = e.getJSONArray("vorschlaege").getJSONObject(0);
        assertEquals("anbieter", v.getString("art"));
        assertEquals("a1", v.getString("anbieterId"));
        assertEquals(0.5, v.getDouble("preisKwh"), 1e-9);
        assertNull(LadeAnbieter.zuBetreiber(liste, "EnBW"));
        assertNull(LadeAnbieter.zuBetreiber(liste, "Te"));   // zu kurz, kein Zufallstreffer
    }

    @Test
    public void ohneAnbieter_bleibtDieAuswahlWieBisher() throws Exception {
        JSONArray a = LadeNachfrage.auswahl(new JSONObject());
        assertEquals("adhoc", a.getJSONObject(0).getString("art"));
        assertEquals(3, a.length());
    }
}
