package at.greenchili.surfmate.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

/**
 * 0.1.38.8 Der rote Knopf „Heizen stoppen“ erscheint nur, wenn etwas laufen könnte (04.11.: seit {@code acOn} bei Zündung aus
 * fehlt, stand er dauerhaft da und tat nichts). Eine Entscheidung im Kern ({@code climate.stopp}), die Web und Handy-App lesen.
 */
public class StoppKnopfTest {

    private static final long T = 1_800_000_000_000L;

    @After
    public void zurueck() {
        KlimaStufe.fuerTest(null);
    }

    private static KlimaLaeuft.Ergebnis ergebnis(boolean autoAn, int acStart, int air, long airAlter, boolean befohlen) {
        return KlimaLaeuft.bewerte(autoAn, acStart, air, airAlter, befohlen);
    }

    private static JSONObject fernAktiv() throws Exception {
        KlimaStufe.fuerTest(true);
        return FernKlima.json(FernKlima.Sitzung.neu(T - 2 * 60_000L, 10, KlimaStufe.zuSdk(12)), T);
    }

    private static JSONObject fernBeendetVor(long ms) throws Exception {
        KlimaStufe.fuerTest(true);
        FernKlima.Sitzung s = FernKlima.Sitzung.neu(T - ms - 10 * 60_000L, 10, KlimaStufe.zuSdk(12));   // Ende = T - ms
        return FernKlima.json(s, T);
    }

    @Test
    public void unbekanntOhneFernSitzungIstVersteckt() throws Exception {
        // Auto aus, lokal 0, keine Cloud-Rückmeldung, keine Sitzung: der Fall von abends (roter Knopf ohne Grund)
        JSONObject j = StoppKnopf.json(ergebnis(false, 0, -1, -1, false), null, false, T);
        assertFalse(j.getBoolean("zeigen"));
        assertEquals(StoppKnopf.GRUND_KEINER, j.getString("grund"));
        // Auto an und Klima AUS laut Auto, nichts läuft
        assertFalse(StoppKnopf.json(ergebnis(true, 0, -1, -1, false), null, false, T).getBoolean("zeigen"));
        // ein ungemessener oder zu alter Cloud-Wert ist kein Beleg
        assertFalse(StoppKnopf.json(ergebnis(false, 0, 2, 1000, false), null, false, T).getBoolean("zeigen"));
        assertFalse(StoppKnopf.json(ergebnis(false, 0, 1, KlimaLaeuft.CLOUD_MAX_ALTER_MS + 1, false), null, false, T).getBoolean("zeigen"));
        // kein Ergebnis, kein Fern, kein Akku
        assertFalse(StoppKnopf.json(null, null, false, T).getBoolean("zeigen"));
    }

    @Test
    public void zeigtNurMitBeleg() throws Exception {
        // Klima läuft laut Auto: lokal stoppen
        JSONObject auto = StoppKnopf.json(ergebnis(true, 1, -1, -1, false), null, false, T);
        assertTrue(auto.getBoolean("zeigen"));
        assertEquals(StoppKnopf.GRUND_AUTO, auto.getString("grund"));
        assertEquals(StoppKnopf.WEG_LOKAL, auto.getString("weg"));
        // frische Cloud-Rückmeldung „läuft“ (airRunState 1): über die Cloud stoppen
        JSONObject cloud = StoppKnopf.json(ergebnis(false, 0, 1, 1000, false), null, false, T);
        assertTrue(cloud.getBoolean("zeigen"));
        assertEquals(StoppKnopf.GRUND_CLOUD, cloud.getString("grund"));
        assertEquals(StoppKnopf.WEG_CLOUD, cloud.getString("weg"));
        // Akku-Heizung läuft laut Cloud
        JSONObject akku = StoppKnopf.json(ergebnis(false, 0, -1, -1, false), null, true, T);
        assertTrue(akku.getBoolean("zeigen"));
        assertEquals(StoppKnopf.GRUND_AKKU, akku.getString("grund"));
    }

    @Test
    public void fernSitzungZeigtUndGehtUeberDieCloud() throws Exception {
        JSONObject j = StoppKnopf.json(ergebnis(false, 0, -1, -1, true), fernAktiv(), false, T);
        assertTrue(j.getBoolean("zeigen"));
        assertEquals(StoppKnopf.GRUND_FERN, j.getString("grund"));
        assertEquals("bei laufender Fern-Sitzung muss der Stopp über die Cloud gehen", StoppKnopf.WEG_CLOUD, j.getString("weg"));
    }

    @Test
    public void nachDemEndeNochFuenfzehnMinuten() throws Exception {
        JSONObject vor10 = StoppKnopf.json(ergebnis(false, 0, -1, -1, false), fernBeendetVor(10 * 60_000L), false, T);
        assertTrue(vor10.getBoolean("zeigen"));
        assertEquals(StoppKnopf.GRUND_FERN_BEENDET, vor10.getString("grund"));
        assertEquals(StoppKnopf.WEG_CLOUD, vor10.getString("weg"));
        JSONObject vor20 = StoppKnopf.json(ergebnis(false, 0, -1, -1, false), fernBeendetVor(20 * 60_000L), false, T);
        assertFalse("nach 15 Minuten weg", vor20.getBoolean("zeigen"));
        // Akku-Heizung bleibt ein eigener Grund
        assertTrue(StoppKnopf.json(ergebnis(false, 0, -1, -1, false), fernBeendetVor(20 * 60_000L), true, T).getBoolean("zeigen"));
    }

    @Test
    public void schlafendesAutoBekommtNieEinenLokalenStopp() throws Exception {
        assertTrue(StoppKnopf.schlaeft(true, 0));
        assertTrue(StoppKnopf.schlaeft(true, 1));
        assertFalse("Auto an", StoppKnopf.schlaeft(true, 2));
        assertFalse("Wert unbekannt (DiLink 5) gilt nicht als schlafend", StoppKnopf.schlaeft(false, 0));
        assertEquals(StoppKnopf.WEG_LOKAL, StoppKnopf.stoppWeg(false, false));
        assertEquals(StoppKnopf.WEG_LOKAL, StoppKnopf.stoppWeg(false, true));
        assertEquals("schläft + Fern-Sitzung: Cloud", StoppKnopf.WEG_CLOUD, StoppKnopf.stoppWeg(true, true));
        assertEquals("schläft, nichts läuft: kein Befehl", StoppKnopf.WEG_NICHTS, StoppKnopf.stoppWeg(true, false));
        assertTrue(StoppKnopf.fernRelevant(fernAktiv(), T));
        assertTrue(StoppKnopf.fernRelevant(fernBeendetVor(10 * 60_000L), T));
        assertFalse(StoppKnopf.fernRelevant(fernBeendetVor(20 * 60_000L), T));
        assertFalse(StoppKnopf.fernRelevant(null, T));
    }

    @Test
    public void handlerSchuetztDasSchlafendeAuto() throws Exception {
        String h = lies("app/src/main/java/at/greenchili/surfmate/server/VehicleControlApiHandler.java");
        int i = h.indexOf("StoppKnopf.stoppWeg(");
        int sw = h.indexOf("switch (action)", i);
        assertTrue("Wächter steht vor dem lokalen power_off", i > 0 && sw > i);
        assertTrue(h.contains("response.put(\"nichtsZuStoppen\", true);"));
    }

    @Test
    public void kernUndWebLesenDieseEineEntscheidung() throws Exception {
        String h = lies("app/src/main/java/at/greenchili/surfmate/server/VehicleControlApiHandler.java");
        assertTrue(h.contains("at.greenchili.surfmate.byd.StoppKnopf.json(laeuft, fern,"));
        assertTrue(h.contains("climate.put(\"stopp\", stopp)"));
        String idx = lies("app/src/main/assets/web/local/index.html");
        assertTrue("Knopf nur nach climate.stopp.zeigen", idx.contains("stopp.hidden = !cl.stopp.zeigen;"));
        assertTrue("Fern-Sitzung/Cloud: Stopp mit remote:true", idx.contains("{ action: 'power_off', remote: true }"));
        assertTrue(idx.contains("this.stoppWeg = cl.stopp && cl.stopp.weg === 'cloud' ? 'cloud' : 'lokal';"));
        assertTrue("sagt wohin der Stopp ging", idx.contains("t('home.stop_cloud'"));
        assertTrue("sagt, bis wann das Fernklima von selbst endet", idx.contains("t('home.stop_endet_selbst'"));
    }

    private static String lies(String rel) throws Exception {
        java.nio.file.Path p = java.nio.file.Paths.get("src/main/" + rel.replaceFirst("^app/src/main/", ""));
        if (!java.nio.file.Files.exists(p)) p = java.nio.file.Paths.get(rel);
        return new String(java.nio.file.Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    public void textenInFuenfSprachen() throws Exception {
        for (String l : new String[]{"de", "en", "fr", "it", "nl"}) {
            JSONObject home = new JSONObject(lies("app/src/main/assets/web/i18n/" + l + ".json")).getJSONObject("home");
            assertTrue(l, !home.optString("stop_cloud", "").isEmpty());
            assertTrue(l, home.getString("stop_endet_selbst").contains("{bis}"));
            assertTrue(l, !home.optString("stop_nichts", "").isEmpty());
        }
    }
}
