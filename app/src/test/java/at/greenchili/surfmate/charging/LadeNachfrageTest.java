package at.greenchili.surfmate.charging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

/** LADENACHFRAGE_VERTRAG: kein stiller Ersatzwert, AC-Standard, Liste ohne Koordinaten, Antworten → Preis-Weg. Synthetisch. */
public class LadeNachfrageTest {

    static final long T = LadeNachfrage.SEIT + 86_400_000L;

    private static JSONObject cfg(String laden) throws Exception {
        return new JSONObject().put("laden", new JSONObject(laden == null ? "{}" : laden))
                .put("tripAnalytics", new JSONObject().put("electricityRate", 0.30).put("currency", "€"));
    }

    private static JSONObject zeile(long id, long start, double rate, String tarif, String label, String quelle, String nachfrage,
                                    Boolean dc, double lat, double lng) throws Exception {
        return new JSONObject().put("id", id).put("start", start).put("ende", start + 3_600_000L).put("kwh", 20.0)
                .put("dc", dc == null ? JSONObject.NULL : dc).put("rate", rate).put("cost", rate > 0 ? 20 * rate : -1)
                .put("currency", "€").put("tariffId", tarif).put("tariffLabel", label).put("quelle", quelle)
                .put("nachfrage", nachfrage).put("lat", lat).put("lng", lng);
    }

    @Test
    public void schliessRegel_keinErsatzwert_acStandard_abschaltbar() throws Exception {
        LadeNachfrage.Entscheid e = LadeNachfrage.ohneTarif(cfg(null), 1, 0.30);
        assertEquals(-1, e.rate, 0);                                        // Standard an: Preis fehlt
        assertEquals(-1, LadeNachfrage.ohneTarif(cfg(null), 0, 0.30).rate, 0);
        LadeNachfrage.Entscheid ac = LadeNachfrage.ohneTarif(cfg("{\"acStandard\":0}"), 0, 0.30);
        assertEquals(0, ac.rate, 0);
        assertTrue(ac.gratis);
        assertEquals(LadeNachfrage.STANDARD_LABEL, ac.label);
        assertEquals(-1, LadeNachfrage.ohneTarif(cfg("{\"acStandard\":0}"), 1, 0.30).rate, 0);   // DC: trotzdem fragen
        assertEquals(0.25, LadeNachfrage.ohneTarif(cfg("{\"acStandard\":0.25}"), -1, 0.30).rate, 1e-9);   // unbekannt = AC
        assertEquals(0.30, LadeNachfrage.ohneTarif(cfg("{\"nachfragen\":false}"), 1, 0.30).rate, 1e-9);   // aus: altes Verhalten
    }

    @Test
    public void quelle_und_offen() throws Exception {
        JSONObject c = cfg(null);
        assertEquals("fehlt", LadeNachfrage.quelle(zeile(1, T, -1, "", "", "", "", true, 0, 0)));
        assertEquals("tarif", LadeNachfrage.quelle(zeile(2, T, 0.3, "t1", "Lidl", "", "", true, 0, 0)));
        assertEquals("ersatz", LadeNachfrage.quelle(zeile(3, T, 0.3, "", "", "", "", true, 0, 0)));
        assertEquals("standard", LadeNachfrage.quelle(zeile(4, T, 0, "", LadeNachfrage.STANDARD_LABEL, "", "", false, 0, 0)));
        assertEquals("nutzer", LadeNachfrage.quelle(zeile(5, T, 0.3, "", "", "nutzer", "erledigt", true, 0, 0)));
        assertTrue(LadeNachfrage.offen(zeile(1, T, -1, "", "", "", "", true, 0, 0), c));
        assertTrue("Bestand ab 20.10. mit Ersatzwert", LadeNachfrage.offen(zeile(3, T, 0.3, "", "", "", "", true, 0, 0), c));
        assertFalse(LadeNachfrage.offen(zeile(2, T, 0.3, "t1", "Lidl", "", "", true, 0, 0), c));
        assertFalse(LadeNachfrage.offen(zeile(4, T, 0, "", LadeNachfrage.STANDARD_LABEL, "", "", false, 0, 0), c));
        assertFalse(LadeNachfrage.offen(zeile(5, T, 0.3, "", "", "nutzer", "erledigt", true, 0, 0), c));
        assertFalse("Overdrive-Zeit", LadeNachfrage.offen(zeile(6, LadeNachfrage.SEIT - 1, -1, "", "", "", "", true, 0, 0), c));
        assertFalse(LadeNachfrage.offen(zeile(1, T, -1, "", "", "", "", true, 0, 0), cfg("{\"nachfragen\":false}")));
        JSONObject offenNoch = zeile(7, T, -1, "", "", "", "", true, 0, 0);
        offenNoch.put("ende", JSONObject.NULL);
        assertFalse("laufende Ladung nie", LadeNachfrage.offen(offenNoch, c));
    }

    @Test
    public void liste_vorschlaegeLokal_ohneKoordinaten_nurImStand() throws Exception {
        JSONObject c = cfg(null);
        c.getJSONObject("laden").put("karten", new JSONArray().put(new JSONObject().put("id", 4).put("anzeige", "Karte X")));
        JSONArray zeilen = new JSONArray()
                .put(zeile(1, T, -1, "", "", "", "", true, 48.0000, 14.7350))
                .put(zeile(2, T - 1000, 0.3, "", "", "", "spaeter", false, 0, 0))
                .put(zeile(3, T - 2000, 0.3, "t", "x", "", "", false, 47, 13));
        JSONArray tarife = new JSONArray().put(new TariffProfile("Gratis-Ort", 48.0010, 14.7350, 100, 0, 0, "€") {{ setAcGratis(true); }}.toJson())
                .put(new TariffProfile("weit weg", 48.0, 14.0, 100, 0.4, 0, "€").toJson());
        JSONArray stopps = new JSONArray().put(new JSONObject().put("name", "Ladepark Y").put("lat", 48.0005).put("lng", 14.7351));
        JSONObject l = LadeNachfrage.liste(zeilen, c, tarife, stopps, true);
        assertEquals(2, l.getInt("anzahl"));
        assertEquals(1, l.getInt("spaeter"));
        assertTrue(l.getBoolean("jetztZeigen"));
        assertFalse(LadeNachfrage.liste(zeilen, c, tarife, stopps, false).getBoolean("jetztZeigen"));   // fährt: nie zeigen
        JSONObject e = l.getJSONArray("offen").getJSONObject(0);
        assertEquals("Gratis-Ort", e.getJSONObject("ort").getString("name"));
        JSONArray v = e.getJSONArray("vorschlaege");
        assertEquals(2, v.length());   // Gratis-Ort (110 m) + Planer-Stopp, „weit weg“ nicht
        assertEquals("ortskreis", v.getJSONObject(0).getString("art"));
        assertEquals("planer_stopp", v.getJSONObject(1).getString("art"));
        assertFalse("keine Koordinaten in der Liste", l.toString().contains("14.735") || l.toString().contains("\"lat\""));
        JSONArray a = e.getJSONArray("auswahl");
        assertEquals("karte", a.getJSONObject(0).getString("art"));
        assertEquals("zuhause_gratis", a.getJSONObject(a.length() - 1).getString("art"));
        assertEquals(0.30, a.getJSONObject(a.length() - 2).getDouble("preisKwh"), 1e-9);
        assertEquals(0.3, l.getJSONArray("offen").getJSONObject(1).getDouble("bisherSatz"), 1e-9);
    }

    @Test
    public void antwort_wirdPreisAnfrage() throws Exception {
        JSONObject c = cfg("{\"karten\":[{\"id\":4,\"anzeige\":\"Karte X\"}]}");
        String[] g = new String[1];
        JSONObject k = LadeNachfrage.alsPreisAnfrage(new JSONObject("{\"id\":9,\"art\":\"karte\",\"karteId\":4,\"preisKwh\":0.59}"), c, g);
        assertEquals(0.59, k.getDouble("rate"), 1e-9);
        assertEquals("Karte X", k.getString("label"));
        assertEquals("nutzer", k.getString("quelle"));
        JSONObject z = LadeNachfrage.alsPreisAnfrage(new JSONObject("{\"id\":9,\"art\":\"zuhause_gratis\"}"), c, g);
        assertTrue(z.getBoolean("gratis"));
        JSONObject n = LadeNachfrage.alsPreisAnfrage(new JSONObject("{\"id\":9,\"art\":\"adhoc\",\"preisKwh\":0}"), c, g);
        assertTrue("0 eingetippt = ausdrücklich gratis", n.getBoolean("gratis"));
        assertNull(LadeNachfrage.alsPreisAnfrage(new JSONObject("{\"id\":9,\"art\":\"adhoc\"}"), c, g));
        assertEquals("preisKwh", g[0]);
        assertNull(LadeNachfrage.alsPreisAnfrage(new JSONObject("{\"id\":9,\"art\":\"raten\",\"preisKwh\":1}"), c, g));
        assertEquals("art", g[0]);
        assertNull(LadeNachfrage.alsPreisAnfrage(new JSONObject("{\"art\":\"adhoc\",\"preisKwh\":1}"), c, g));
        assertEquals("ladungen_ohne_preis", LadeNachfrage.nochOffen(3).getString("id"));
        JSONArray alt = new JSONArray().put(zeile(1, LadeNachfrage.SEIT - 5000, -1, "", "", "fehlt", "", true, 0, 0))
                .put(zeile(2, LadeNachfrage.SEIT - 9000, 0.3, "t", "x", "tarif", "", true, 0, 0))
                .put(zeile(3, T, -1, "", "", "", "", true, 0, 0));
        assertEquals(1, LadeNachfrage.ohnePreisVorSeit(alt));
        assertEquals("uebernommene_ladungen_ohne_preis", LadeNachfrage.nochOffenAlt(1).getString("id"));
        assertNull(LadeNachfrage.nochOffenAlt(0));
        assertNull(LadeNachfrage.nochOffen(0));
    }

    @Test
    public void seit_ist20Oktober2026() {
        java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(LadeNachfrage.SEIT);
        assertEquals(2026, c.get(java.util.Calendar.YEAR));
        assertEquals(java.util.Calendar.OCTOBER, c.get(java.util.Calendar.MONTH));
        assertEquals(20, c.get(java.util.Calendar.DAY_OF_MONTH));
    }

    @Test
    public void schliesspfad_verdrahtet() throws Exception {
        String p = Files.exists(Paths.get("src/main/java")) ? "src/main/java/" : "app/src/main/java/";
        String db = new String(Files.readAllBytes(Paths.get(p + "at/greenchili/surfmate/monitor/SocHistoryDatabase.java")), "UTF-8");
        int a = db.indexOf("private PricingDecision priceSessionForClose(");
        int b = db.indexOf("private PricingDecision globalPricing(", a);
        String close = db.substring(a, b);
        assertTrue(close.contains("LadeNachfrage") && close.contains(".ohneTarif("));
        assertFalse("kein direkter Ersatzwert mehr im Schließpfad", close.contains("return globalPricingStrict(isDc);"));
    }
}
